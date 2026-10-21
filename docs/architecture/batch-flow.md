# 一批的端到端（从侧载到相册能播）

- **回答的问题**：操作者点下"开始合成"之后，一个批次按什么顺序经过哪些所有权边界？什么算成功，什么只是"索引还没跟上"？
- **读者**：第一次接手这个仓库的人 / 要判断"改这里会影响端到端链路的哪一段"的 agent。
- **范围**：as-built @ `ff9ad98` + 工作区增量（图标 / SplashScreen / 入场动画 / 单列目录浏览），2026-10-21。建模方式 `flow-visualizer` + `graphviz`。
- **图源**：[batch-flow.dot](batch-flow.dot)（渲染产物 [batch-flow.svg](batch-flow.svg)，勿手改）。
- **和另一张图的分工**：本文是**一批**的流程；**一项**内部怎么过八道关口，在 [compose-pipeline.md](compose-pipeline.md)。

## 主路径（同步、顺序、单协程）

| 步 | 发生什么 | 归属 | 代码 |
| --- | --- | --- | --- |
| 1 | 首次打开：跳系统授权页要 `MANAGE_EXTERNAL_STORAGE`；vivo 上可能落不到系统页 → 退化成 Toast 手动指引 | `:app` → 系统 | [MainActivity.kt:66-75](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/MainActivity.kt#L66) |
| 2 | 回前台 `onResume` 重读授权 —— 授权状态只能在运行时问，缓存会一直说谎 | `:app` | [MainActivity.kt:75-79](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/MainActivity.kt#L75) |
| 3 | 选源/目标目录：全屏 `DirectoryPicker`，**只有一条列表**（`上一级` + 当前层条目，根上由 `mergedRootEntries` 把资源里的别名盖到真实子目录上）；`subdirs()` 在 `Dispatchers.IO` 上读，组合期只消费"已载入"的结果 | `:app` → 共享存储 | [BatchScreen.kt:354-471](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt#L354)、[DirBrowser.kt:71-84](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/browse/DirBrowser.kt#L71) |
| 4 | 开工快照：`running \|\| !isGranted()` 直接静默忽略；快照一旦取定，运行中禁改目录与删除开关 | `:app` | [BatchViewModel.kt:152-175](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L152) |
| 5 | 全局前置校验：快照路径在 root 内 → 源目录可读 → 目标目录建得起来（`ensureDir` 抛出的 `IOException` 由这里兜住） | `:app` | [BatchViewModel.kt:189-207](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L189) |
| 6 | `plan()`：`list(source)` + `list(target)` → `jobs` + `unpaired`，先让用户看到 `total` | `:core` | [BatchRunner.kt:59-60](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/BatchRunner.kt#L59) |
| 7 | `run()`：**同一份 PlanResult 原样交给 run，不重新推导**；逐项 `composeOne` → `onItem` | `:core` | [BatchRunner.kt:62-104](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/BatchRunner.kt#L62) |
| 8 | 每项的字节动作只经 `BatchStorage` 端口：`openWrite(.part)` → `close()` → `move(→ 最终名)` → 可选 `delete(原文件)` | `:core` 端口 / `:app` 实现 | [BatchStorage.kt:13-84](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/BatchStorage.kt#L13)、[FileStorage.kt:78-147](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L78) |
| 9 | 收尾：`touchedPathsFor(targetDir, items)` → 一次性提交全部路径给 `scanFile`，最多等 2 秒，跑在 `NonCancellable` 里 | `:app` → MediaStore | [BatchViewModel.kt:63-68](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L63)、[:223-249](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L223)、[MediaStoreSync.kt:44-63](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/MediaStoreSync.kt#L44) |

主路径**没有任何异步交接、没有队列、没有并行**：一个协程、一条顺序 `for`。这是刻意的取舍 —— 见 [decisions.md](../decisions.md)。

## 谁拥有什么

| 边界 | 拥有者 | 交出去的是什么 |
| --- | --- | --- |
| 目录、权限、扫描 | `:app` | 只有 `BatchStorage` 的 7 个方法 + 一个可反复 `open()` 的 `ChannelSource` |
| 配对、批次状态机、字节判断 | `:core` | 一份 `BatchReport`（`items` / `composedBytes` / `cancelled`） |
| 磁盘事实 | 文件系统 | `rename` 成功那一刻即提交，之后不再回改写 |
| 索引新鲜度 | MediaStore（系统） | 尽力而为：扫描未确认**不改变**磁盘事实 |

`:core` 不知道 `java.io.File` 存在，也不知道 Android 存在；`:app` 不知道 XMP、JPEG marker、ISO-BMFF 盒子。这条分工是整个架构最重要的一刀。

## 失败与退出的四条路

| 情况 | 呈现 | 磁盘后果 |
| --- | --- | --- |
| **整批被拒绝**（源不可读 / 目标建不起来 / 批次意外中断） | `UiState.globalError` 一行，**不进报告列表** | 一个字节都没写 |
| **单项失败**（`FAILED` / `SKIPPED`） | 报告列表一行 + 底部计数汇总 | `.part` 已清理；最终名上什么都没有；原文件不动 |
| **取消** | `BatchReport.cancelled = true`，已提交项照常留在报告里 | 正在写的项走完 rename 或清理，**不留半截 `.part`** |
| **收尾扫描未确认** | 同样借 `globalError` 提示一行"媒体索引未确认刷新" | **磁盘事实已定**，只是相册可能暂时看不到新产物 / 仍留着指向已删文件的条目 |

第四条是最容易被误读的一条：扫描失败不是合成失败。代码把话说死在这里 —— 借用 `globalError` 通道是因为 `UiState` 形状被钉死、没有新字段可去（[BatchViewModel.kt:33-36](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L33)）。

## 图上刻意不画的

- **一项内部的八道关口** → [compose-pipeline.md](compose-pipeline.md)。
- **UI 控件层的重组行为**（`StateFlow` CAS、`produceState`、为什么不用 `LazyColumn`）→ 属于结构，见 [system-model.md](system-model.md) 的 `:app` 一节。
- **并发分支**：不存在。整批就是一条协程，唯一"并发"是主线程可能同时改权限快照与删除开关。

## 唯一无法在图里闭合的环节

```
产物字节正确  ──?──>  相册把 x_MP.jpg 当动态照片播放
```

这一步**没有 API 可问**。代码对 MediaStore 的唯一调用只提交路径去扫描（[MediaStoreSync.kt:48](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/MediaStoreSync.kt#L48)），"认不认"完全由字节布局 + XMP 目录决定，判据来自 vivo V2419A 上的真机单变量对照与二分实验（2026-10-18 ~ 2026-10-20，记录在 [motion-photo-container.md](../motion-photo-container.md)）。

因此这张端到端图的最后一条边是**低置信**的，而且是系统的全部 193 条自动化测试覆盖不到的唯一实质风险面：

- 145 条 `:core` JVM 测试能证明字节布局符合我们自己钉下的规则；
- 8 条仪器化测试能证明 `FileStorage` 满足端口契约；
- 但没有任何测试能证明 vivo 相册认可这份布局 —— 那需要装机肉眼看。

每次改动 `XmpPacket` / `VivoLivePhotoTail` / 尾巴声明规则之后，**必须重跑真机验收**（步骤在 [testing.md](../testing.md)）。

## 渲染与再生成

`node render-svg.mjs batch-flow.dot`（依赖 `@viz-js/viz`）。当前 SVG 由 Graphviz 16.1.0 WASM 构建生成：parse + layout 通过，17 个节点、19 条边、2 个边界 cluster 全部布局成功；在浏览器里按 `viewBox` 量过所有绘制元素，**无内容被裁**（四周 `pad=0.2`）。CJK 字体度量缺失的瑕疵同上。
