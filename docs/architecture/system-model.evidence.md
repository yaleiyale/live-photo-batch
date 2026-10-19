# live-photo-batch 架构模型 —— 证据索引

- 模型范围：当前状态（as-built），仓库 `feat/live-photo-batch` @ `9dfbc85`，采集日期 2026-10-19。
- 建模方式：`system-modeler` + `c4model`（Structurizr DSL）+ `graphviz`（DOT）。
- 图源与产物：
  - [live-photo-batch.structurizr.dsl](live-photo-batch.structurizr.dsl) —— 4 个视图（Context / Container / 两个 Component）。
  - [compose-pipeline.dot](compose-pipeline.dot) + [compose-pipeline.svg](compose-pipeline.svg)（派生产物，勿手改）。
  - README 的「核心逻辑」一节内嵌一份 mermaid 模块图，GitHub 原生渲染。
- 渲染验证状态（2026-10-19 实机跑过，不是纸面推断）：
  - **DOT：已验证**。真 Graphviz 16.1.0（WASM）parse + layout 通过，35 条边全部布局成功，导出 SVG 后在浏览器里按 `viewBox` 逐元素量过边界：无内容被裁。
    已知瑕疵：WASM 构建没有 CJK 字体度量，按估算字宽画框，所以中文标签会略微越过自己的圆角边框（不影响可读性）。要出终稿请用带 CJK 字体的 graphviz 重渲染。
  - **mermaid：已验证**。真 mermaid（flowchart-v2）parse 通过，并在浏览器里完整渲染：17 个节点、18 条边，两个 subgraph 边界与 `implements` 虚线都在。
  - **Structurizr DSL：未做机器验证**。本机无 Java（官方解析器是 Java），npm 上的 `structurizr-parser@0.4.0` 发布包里根本没有 `dist/`，装不上也用不了。因此这份 DSL 是**按官方文法手写**的：只用 `include *` 与 `autoLayout` 这类示例里出现过的形式，位置参数不超过各关键字的上限（`component` 4 个、`->` 4 个），所有点号路径都在 `!identifiers hierarchical` 之下。请在 Qoder 的 Structurizr DSL 视图里打开确认；若某个视图报错，以 README 的 mermaid 与 DOT 为准。
- 置信度定义见插件 `references/architecture-contract.md`。**high** = 直接来自代码/配置。

本模型描述的是**代码现状**。`docs/superpowers/specs/…-design.md` §6 那份模块表已经过期（见文末「与规格文档的分叉」），不要拿它当现状读。

## L1 边界（Context）

| 节点 | 与系统的关系 | 证据 | 置信度 |
| --- | --- | --- | --- |
| 操作者 | 选目录、勾选删原文件、开始/取消 | [BatchScreen.kt:130](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/BatchScreen.kt#L130)（开始按钮）、[BatchScreen.kt:105](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/BatchScreen.kt#L105)（勾选） | high |
| 共享存储文件系统 | 唯一的输入源与输出汇 | [FileStorage.kt:103](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/io/FileStorage.kt#L103)（`openWrite`）、[BatchViewModel.kt:39](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/BatchViewModel.kt#L39)（默认目录） | high |
| MediaStore / 相册 | 收尾接收路径扫描；识别结果是本项目的验收判据 | [MediaStoreSync.kt:44](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/MediaStoreSync.kt#L44)、[README.md:47](file:///D:/projects/piece/live-photo-batch/README.md#L47)（真机相册可播） | high |
| 系统设置授权页 | 跳转目标；vivo 上可能落不到，只能回查 | [PermissionGate.kt:50](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/perm/PermissionGate.kt#L50)、[MainActivity.kt:37](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/MainActivity.kt#L37)（`runCatching` + Toast 退化） | high |
| 相册「能播放」这件事 | 由 XMP + 字节布局决定，**不是**运行时集成，没有 API 调用 | [XmpPacket.kt:52](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/XmpPacket.kt#L52) | high |

系统之外没有网络、没有服务端、没有数据库：全部工作发生在一次进程内的文件读写上。

## L2 容器

| 断言 | 证据 | 置信度 |
| --- | --- | --- |
| 只有**一个**可部署单元：`app-debug.apk`（单个 Android 进程） | [build.yml:46](file:///D:/projects/piece/live-photo-batch/.github/workflows/build.yml#L46)（`assembleDebug` + 上传单个 APK）、[AndroidManifest.xml:19](file:///D:/projects/piece/live-photo-batch/app/src/main/AndroidManifest.xml#L19)（唯一 Activity） | high |
| `:core` 与 `:app` 是**构建隔离**边界而非进程边界：`:app` 依赖 `:core`，编译进同一个 APK | [settings.gradle.kts:15](file:///D:/projects/piece/live-photo-batch/settings.gradle.kts#L15)、[app/build.gradle.kts:49](file:///D:/projects/piece/live-photo-batch/app/build.gradle.kts#L49)（`implementation(project(":core"))`） | high |
| `:core` 无 `android.*` 依赖：插件只有 `kotlin.jvm`，依赖只有 JUnit | [core/build.gradle.kts:1-9](file:///D:/projects/piece/live-photo-batch/core/build.gradle.kts#L1)；`core/src/main` 全量 grep 无 `import android` | high |
| 并发模型是**顺序单线程**处理（`Dispatchers.IO` 上一个协程跑完整批），没有并行合成 | [BatchViewModel.kt:174](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/BatchViewModel.kt#L174)（单个 `launch`）、[BatchRunner.kt:95](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L95)（顺序 `for`） | high |

## L3 组件 —— `:core`（核心逻辑）

| 组件 | 职责（核心逻辑） | 证据 | 置信度 |
| --- | --- | --- | --- |
| `PairPlanner` | 驱动集是**视频**：只有 `IMG_*.mp4` 去找同名 jpg/jpeg；`_MP` 结尾的图像不作候选；输出名 `<stem>_MP.jpg`；按 stem 码元自然序排序 | [PairPlanner.kt:71](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/PairPlanner.kt#L71)、[:82-83](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/PairPlanner.kt#L82)（`VIDEO_PREFIX` 判定）、[:63](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/PairPlanner.kt#L63) | high |
| `BatchRunner` | 逐项状态机；`unpaired` 先出且不碰文件；`.part → close → move → 删原文件`；提交后状态不可回改写 | [BatchRunner.kt:62](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L62)、[:106](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L106)、[:148](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L148) | high |
| `BatchStorage` / `ChannelSource`（端口） | `:core` 认识的**全部**文件动作；路径是字符串、分隔符固定 `/`；`:core` 因此不认识 `java.io.File` | [BatchStorage.kt:13](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchStorage.kt#L13)、[Sources.kt:15](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/Sources.kt#L15) | high |
| `MotionPhotoComposer` | 七道校验的**顺序**本身是设计：坏视频绝不让后面白扫一遍 JPEG；校验阶段对 sink 一字节不写 | [MotionPhotoComposer.kt:57-130](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposer.kt#L57) | high |
| `JpegScanner` / `JpegStructure` | 按 marker 遍历得 `imageEnd` + 旧 XMP 区间与文本；绝不搜 `FFD9`；64 KiB 预读 + `rewindUnused()` 把 channel 精确停在 `imageEnd` | [JpegStructure.kt:31](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegStructure.kt#L31)、[:157](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegStructure.kt#L157) | high |
| `JpegXmpPatcher` | 纯区间搬运；新 XMP 写回**旧段原位**（不是插在 SOI 后）；旧段靠「读掉不写」丢弃 | [JpegXmpPatcher.kt:37](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegXmpPatcher.kt#L37)、[:62-70](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegXmpPatcher.kt#L62) | high |
| `XmpPacket` | 产物元数据的**唯一**生成入口，清空白写；`Primary →(GainMap)→ MotionPhoto`；两个长度字段都等于视频字节数 | [XmpPacket.kt:52](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/XmpPacket.kt#L52)、[:91-93](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/XmpPacket.kt#L91) | high |
| `TrailerClassifier` | 只回答「尾巴是不是上一轮追加的视频」；16 字节探针 + 源 XMP 信号，XMP 信号排在最前 | [TrailerClassifier.kt:42](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerClassifier.kt#L42)、[:43-44](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerClassifier.kt#L43) | high |
| `TrailerModel` | 只回答「尾巴该被目录声明成 GainMap 还是 Padding」；互斥；任何输入都不抛（畸形一律降级成 Padding） | [TrailerModel.kt:62](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerModel.kt#L62)、[:94](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerModel.kt#L94)、[XmpPacket.kt:60](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/XmpPacket.kt#L60)（`check` 守互斥） | high |
| `MotionDetector` | 源 XMP 是否已带动态照片语义；正则必须带命名空间前缀，否则普通照片会被误判 | [MotionDetector.kt:10-16](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionDetector.kt#L10) | high |

两个刻意的**不画的边**（画了就是假事实）：

- `TrailerModel` **不调用** `JpegScanner`。它自带 `BoundedCursor` 重新实现了一遍 §7.2 的 marker 规则，
  只多一条「物理读取总量不超过文件大小」的硬边界（[TrailerModel.kt:127-132](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerModel.kt#L127)），
  而 `JpegScanner.Cursor` 是另一份（[JpegStructure.kt:107](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegStructure.kt#L107)）。
  两者共享的是**规则**，不是代码，所以模型里它们之间没有调用边。
- `MotionPhotoComposer` 到 `BatchStorage` 端口之间画的是**虚线**：合成器只消费 `ChannelSource` /
  `WritableByteChannel` 这两个类型，实例是 `BatchRunner` 从端口取出来递进去的
  （[BatchRunner.kt:113](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L113)）。

## L3 组件 —— `:app`（四件适配器 + UI）

| 组件 | 职责 | 证据 | 置信度 |
| --- | --- | --- | --- |
| `BatchViewModel` | 批次所有权：开工快照 → 全局前置 → `plan()` 出总数 → `run()` 逐项 `update{}` → `finally` 里 `NonCancellable` 收尾 | [BatchViewModel.kt:189-250](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/BatchViewModel.kt#L189) | high |
| `touchedPathsFor` | §7.6 不变量的唯一计算处：SUCCESS 产物 + `deletedSources` | [BatchViewModel.kt:63](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/BatchViewModel.kt#L63) | high |
| `FileStorage` | `BatchStorage` 生产实现；`exists`/`delete` 必须先 `isFile`；`move` 不先删目标；`openWrite` 打开即 truncate | [FileStorage.kt:78-147](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/io/FileStorage.kt#L78) | high |
| `MediaStoreSync` | 一次性提交全部路径，`CountDownLatch` 最多等 2 秒；绝不抛但回报未确认 | [MediaStoreSync.kt:44-63](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/MediaStoreSync.kt#L44) | high |
| `PermissionGate` | 授权真值经注入的 `() -> Boolean` 读取，每次现读不缓存；低于 API 30 短路 | [PermissionGate.kt:35-47](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/perm/PermissionGate.kt#L35) | high |
| `DirBrowser` | 只列可读子目录、过滤点开头名；「绝对路径」判定只看的 `startsWith("/")`，比存储层宽 | [DirBrowser.kt:64-71](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/browse/DirBrowser.kt#L64) | high |
| `BatchScreen` / `DirectoryPicker` | 单屏；目录读取挪进 `produceState + Dispatchers.IO`，绝不发生在组合期 | [BatchScreen.kt:223-236](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/BatchScreen.kt#L223) | high |
| `InMemoryStorage` | 端口第二实现，让 `:core` 全部逻辑在无设备 JVM 上可测 | [InMemoryStorage.kt](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/InMemoryStorage.kt) | high |

## 关键不变量（结构 + 行为都有证据）

| 不变量 | 证据 |
| --- | --- |
| 取消只在**项与项之间**生效；正在写的项一定走完 rename 或 `.part` 清理 | [BatchRunner.kt:76-77](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L76)、[:96-97](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L96)、[BatchViewModel.kt:253](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/BatchViewModel.kt#L253)（`cancel()` 只翻 `@Volatile` 标志） |
| `run` 必定返回一份 `BatchReport`：清理自身抛出也不许穿出 `run` | [BatchRunner.kt:176-181](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L176) |
| `composedBytes` 只累加 SUCCESS；跳过/失败项 `bytes` 恒为 0 | [BatchRunner.kt:100](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L100) |
| `SKIPPABLE` 只有 `ALREADY_MOTION_PHOTO`；`MALFORMED_JPEG` 故意算 FAILED（免得磁盘故障被埋进「坏文件」） | [BatchRunner.kt:183-187](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L183) |
| 全程常数内存：64 KiB 定长缓冲，绝不 `readBytes()` 整图或整视频 | [MotionPhotoComposer.kt:52](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposer.kt#L52)、[JpegStructure.kt:202](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegStructure.kt#L202)、[TrailerModel.kt:39](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerModel.kt#L39) |
| 同一条尾巴被读两遍（定性一遍、搬运一遍）—— 这是 `TrailerModel` 判定收敛的代价，已在 KDoc 写明 | [TrailerModel.kt:56-60](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerModel.kt#L56) |
| root 字符串契约：`FileStorage`、`DirBrowser`、`isSelectable` 共享同一个未规范化 `"/storage/emulated/0"` | [BatchViewModel.kt:140](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/BatchViewModel.kt#L140)、[FileStorage.kt:80](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/io/FileStorage.kt#L80)、[DirBrowser.kt:18-31](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/browse/DirBrowser.kt#L18) |

## 假设与未决项（不要当现状读）

| 项 | 现状 | 需要的验证动作 |
| --- | --- | --- |
| 「vivo 相册把 `_MP.jpg` 当动态照片播放」 | 只在 vivo V2419A / Android 16 上人工验收过（[README.md:47](file:///D:/projects/piece/live-photo-batch/README.md#L47)）。识别谓词是厂商实现，规范只给必要条件是**推断** | 换机型/换相册应用（Google Photos、小米）各跑一批；失败即回到「逆向厂商标记」 |
| `MediaStore` 对被删路径的收敛 | `scanFile` 返回 true 只代表「超时内收到至少一次回调」，不代表索引已无幽灵条目（spec R4） | 真机删原文件后查 `content query --uri content://media/external/images/media` 是否还留着旧行 |
| 进程被杀后的索引新鲜度 | 无代码覆盖：协程、`NonCancellable`、扫描一起死（[BatchViewModel.kt:104-105](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/BatchViewModel.kt#L104)） | 靠 MediaStore 例行收敛，装机验收项 |
| SD 卡 / 跨卷 `rename` | `move` 有 copy+delete 退化分支，但**非原子**且未被真机覆盖（spec R5，目标固定内部存储） | 插 SD 卡把目标目录设过去跑一批 |
| `compileSdk = 37` 而 spec 写 36 | 为过 `checkDebugAarMetadata` 升上来的，`targetSdk` 仍是 36（[app/build.gradle.kts:13-19](file:///D:/projects/piece/live-photo-batch/app/build.gradle.kts#L13)） | 无需动作，记录分叉即可 |

## 与规格文档的分叉（spec §6 已过期）

`docs/superpowers/specs/2026-10-17-live-photo-batch-android-design.md` §6「架构与模块边界」描述的是计划形态，与代码现状有 5 处不一致。本模型按**代码**建模：

| spec §6 写的 | 代码现状 | 证据 |
| --- | --- | --- |
| `NestedMotionPhotoDetector` | `MotionDetector` + `TrailerClassifier` 两个职责，且判定条件从「EOI 后有任何字节」改成「只有视频形状才算」 | [MotionDetector.kt:9](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionDetector.kt#L9)、[TrailerClassifier.kt:3-13](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerClassifier.kt#L3) |
| （无） | 新增 `TrailerModel`（尾巴定性 → 目录声明） | [TrailerModel.kt:23](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerModel.kt#L23) |
| `FileSystemGateway` / `MediaStoreGateway` | `FileStorage` / `MediaStoreSync` | [FileStorage.kt:78](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/io/FileStorage.kt#L78)、[MediaStoreSync.kt:37](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/MediaStoreSync.kt#L37) |
| `JpegStructure` 返回 `SegmentList(imageEnd, xmpIndex, exifIndex…)` | 返回 `JpegStructure(imageEnd, oldXmp, oldXmpText, hasExtendedXmp)`；没有 exifIndex | [JpegStructure.kt:7-13](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegStructure.kt#L7) |
| spec §7.1「紧随 SOI 的新 XMP APP1」 | 已被「增补 2026-10-18c §1」取代：新 XMP 写回**旧 XMP 原位**，且源图整文件（含尾巴）搬进产物 | [JpegXmpPatcher.kt:25-29](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegXmpPatcher.kt#L25)（理由）、[MotionPhotoComposer.kt:28-30](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposer.kt#L28) |

## 重新生成

```bash
# 首选：装了 graphviz + CJK 字体的机器（中文标签才不会被算窄）
dot -Tsvg docs/architecture/compose-pipeline.dot -o docs/architecture/compose-pipeline.svg

# 本次实际用的无 graphviz 路径（WASM，Node 即可；注意它没有 CJK 字体度量）：
npm i @hpcc-js/wasm-graphviz
node --input-type=module -e "
import { Graphviz } from '@hpcc-js/wasm-graphviz'; import fs from 'node:fs';
const gv = await Graphviz.load();
fs.writeFileSync('docs/architecture/compose-pipeline.svg', gv.dot(fs.readFileSync('docs/architecture/compose-pipeline.dot','utf8'),'svg'));"

# mermaid 的 parse 校验（需要 jsdom 提供 DOM；布局测量仍建议用真浏览器）
# Structurizr DSL：Qoder 的 DSL 视图，或 structurizr-cli / Structurizr Play
#   https://structurizr.com/share  （需要 Java 或浏览器，本机都没有）
```

三份表达内容一致：DSL 与 DOT 是源，README 的 mermaid 面向读者，`compose-pipeline.svg` 是派生产物。
本仓库的 CI（`.github/workflows/build.yml`）不校验这些图，改动图源后请照上面的命令手动重渲染。
