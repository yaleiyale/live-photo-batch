# 一项的生命周期（合成流水线）

- **回答的问题**：一个 `PairJob` 从两个源文件到相册可见，要按什么顺序过哪几道关口？失败和取消时磁盘上留下什么？
- **读者**：要改字节层判断的人 / agent。**改 `MotionPhotoComposer` 之前先读这张图** —— 关口顺序本身就是设计，不是实现细节。
- **范围**：as-built @ `ff9ad98` + 工作区增量（图标 / SplashScreen / 入场动画 / 单列目录浏览），2026-10-21。建模方式 `flow-visualizer` + `graphviz`。
- **图源**：[compose-pipeline.dot](compose-pipeline.dot)（渲染产物 [compose-pipeline.svg](compose-pipeline.svg)，勿手改）。
- **证据**：逐条断言见 [system-model.evidence.md](system-model.evidence.md)；本文只标关键行号。

## 阶段一：八道关口，全过之前对 sink 一个字节不写

顺序按"便宜的放前面"排：坏视频绝不让后面白扫一遍 JPEG。

| # | 关口 | 拒绝时的原因 | 代码 |
| --- | --- | --- | --- |
| ① | 读视频开头 16 字节，前 12 字节内出现 `ftyp`？ | `NOT_MP4`（读失败 → `IO_ERROR`） | [MotionPhotoComposer.kt:64-70](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposer.kt#L64)、[:198-206](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposer.kt#L198) |
| ② | `JpegScanner` 按 marker 走段，得 `imageEnd` / 旧 XMP 区间与文本 / 扩展 XMP 标志 | `NOT_JPEG`、`MALFORMED_JPEG` | [JpegStructure.kt:31](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegStructure.kt#L31) |
| ③ | 有分块扩展 XMP（`xmp/extension/`）？ | `EXTENDED_XMP_UNSUPPORTED`（不做处理） | [MotionPhotoComposer.kt:82-84](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposer.kt#L82) |
| ④ | 从 `imageEnd` 读 `min(16, 尾巴长)` 字节 —— 精确窗口长度是**契约**，短于 16 代表整条尾巴都在手里 | `IO_ERROR` | [MotionPhotoComposer.kt:89-99](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposer.kt#L89)、[TrailerClassifier.kt:19-22](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerClassifier.kt#L19) |
| ⑤ | `TrailerClassifier`：源 XMP 声明过视频，**或**这段字节本身是 ISO-BMFF → 上一轮已合成过 | `ALREADY_MOTION_PHOTO`（唯一算 `SKIPPED` 的校验原因） | [TrailerClassifier.kt:42-48](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerClassifier.kt#L42)、[BatchRunner.kt:187](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L187) |
| ⑥ | `TrailerModel`：沿尾巴自己的 marker 走完一遍，定性成 `gainMapBytes` 或 `paddingBytes` | **不拒绝** —— 任何输入都不抛，畸形一律降级成 Padding | [TrailerModel.kt:62-73](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerModel.kt#L62) |
| ⑦ | `VivoLivePhotoTail.plan(video)`：末盒能不能改 | **不拒绝** —— 看不懂返回 `null`，视频按原样追加 | [VivoLivePhotoTail.kt:52-92](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/VivoLivePhotoTail.kt#L52) |
| ⑧ | `XmpPacket.app1Segment` 清空白写 | `EXTENDED_XMP_UNSUPPORTED`（包体 > 65,533 字节）；两长度同时非 null → `IllegalStateException` **刻意不兜** | [XmpPacket.kt:78-84](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/XmpPacket.kt#L78)、[MotionPhotoComposer.kt:115-126](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposer.kt#L115) |

两个"不拒绝"是刻意的：⑥ 声明成 Padding 永远合法，把坏图谎称 GainMap 才是骗目录；⑦ 不改自己读不懂的字节，也不替非 vivo 的视频伪造厂商结构。

关口 ⑤ 与 ⑥ 是**两个问题**，不许合并成一个组件：一个问身份（这尾巴是不是上一轮的视频），一个问声明（这段字节该被目录写成什么）。

## 阶段二：写入（此时 sink 里可能有半截文件）

```
JpegXmpPatcher.patch(structure, image, sink, app1, trailerEnd = 文件大小)
  源图[0, 旧XMP起点) → 新 APP1 → 源图[旧XMP终点, 文件大小)
  旧 XMP 段靠「读掉不写」丢弃；imageEnd 之后的尾巴整条搬走
copyVideo(video, sink, tail)
  前 verbatimBytes 一块 64 KiB 流式追加，再接重写后的末盒（若有）
close() 成功返回 → .part 上全部字节完整可见
```

- 写入目标是 `目标目录/.tmp/<输出名>.part`（[BatchRunner.kt:110](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L110)）。
- 新 XMP 必须写回**旧段原位**，不是插在 SOI 之后：vivo 相册与 Exiv2 按 `EXIF APP1 在前、XMP APP1 在后` 读元数据，插在 SOI 后实测不被认（[JpegXmpPatcher.kt:25-30](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegXmpPatcher.kt#L25)）。
- 视频前半一字不动，所以视频里所有 `stco`/`co64` 的 chunk 偏移仍然有效（[MotionPhotoComposer.kt:176-178](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposer.kt#L176)）。
- **`.part` 半途可见是文件系统的真实事实**：`RandomAccessFile(file,"rw")` 一打开就创建并 truncate 到 0。契约因此把发布责任交给调用方（先 close、再 rename），而不是假装这一层能"关闭才可见"（[FileStorage.kt:40-52](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/io/FileStorage.kt#L40)）。

## 阶段三：三条出路，且三条都执行 `.part` 清理

| 出路 | 状态 | 磁盘后果 | 代码 |
| --- | --- | --- | --- |
| 被关口拒掉 | 原因 ∈ {`ALREADY_MOTION_PHOTO`, `NO_PAIR`} → `SKIPPED`，否则 `FAILED` | `.part` 无条件删除；`bytes = 0`，不进 `composedBytes` | [BatchRunner.kt:116-123](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L116) |
| 合成成功但 `move` 返回 false | `FAILED(IO_ERROR)` | 删 `.part`；**最终名上什么都没有**，原文件一律不动 | [BatchRunner.kt:125-127](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L125) |
| `move` 成功 | **★ 提交点**：`SUCCESS` 与字节数在 `commit()` 第一行就定死 | 才（在勾选删除时）依次删 `x.jpg`、删 `IMG_x.mp4`；删除失败只补 `deleteFailures` / `deletedSources` 两条记录，**没有任何路径能把 SUCCESS 回改写** | [BatchRunner.kt:142-166](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L142) |

`MALFORMED_JPEG` **刻意不算跳过**：`JpegScanner` 会把 `IOException` 折叠成 Malformed，若算跳过，真实磁盘故障就被埋进"坏文件"里（[BatchRunner.kt:184-187](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L184)）。

## 取消

`CancelToken` 只翻标志，`BatchRunner` 在**每一项之前**查一次 —— unpaired 行与 job 行都算一项（[BatchRunner.kt:76-77](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L76)、[:96-97](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/BatchRunner.kt#L96)）。

- 结果：**绝不留半截 `.part`** —— 正在写的项一定走完 rename 或删除。
- 反过来，一个开局就取消的批次也不该把整屏 `NO_PAIR` 推给 UI 之后还报告 `cancelled = false`，所以预循环同样查标志。
- `ViewModel.cancel()` 不靠协程取消掐断循环（[BatchViewModel.kt:253-255](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/BatchViewModel.kt#L253)）。

## 图上刻意没画的东西

- **`BatchReport` 的累积细节**（`items`/`composedBytes`/`cancelled`）—— 属于结构而不是流动，见 [system-model.md](system-model.md)。
- **尾巴被读两遍**：`TrailerModel.describe` 走一遍 marker，`patch` 搬它时再走一遍。代价写在注释里（vivo 实测尾巴 37 万~106 万字节），但这条重复读取在图上不显式成环。
- **相册识别**：产物字节对了才算成，可这一步没有任何 API 能查询，只有真机能判（见 [batch-flow.md](batch-flow.md) 末节）。
- **跨卷 `move` 退化路径**（`copyTo` + `delete`）：只在 rename 已不可能成功时走，且不是原子的（[FileStorage.kt:127-141](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/io/FileStorage.kt#L127)）。图上 `move` 只有成功/失败两个出口。

## 渲染与再生成

`node render-svg.mjs compose-pipeline.dot`（依赖 `@viz-js/viz`）。当前 SVG 由 Graphviz 16.1.0 WASM 构建生成：parse + layout 通过，22 个节点、31 条边全部布局成功；在浏览器里按 `viewBox` 量过所有 `text`/`path`/`polygon`，**无内容被裁**（图四周加了 `pad=0.2`）。已知瑕疵：WASM 构建没有 CJK 字体度量，按估算字宽画框，中文标签可能略微越过自己的圆角边框 —— 不影响可读性，出终稿请用带 CJK 字体的本机 graphviz 重渲染。
