# live-photo-batch 架构模型（当前状态）

- **这张图回答什么问题**：这个仓库的架构是什么 —— 边界在哪里、每个模块负责什么判断、一项批次任务怎么落地。不回答"该不该这么设计"（那在 [decisions.md](../decisions.md)）和"格式细则"（那在 [motion-photo-container.md](../motion-photo-container.md)）。
- **读者**：第二次接手这个仓库的人 / 要改它的 agent。
- **范围**：**as-built（代码现状）**，不是目标状态。基准 `ff9ad98` + 工作区增量，采集 2026-10-20、刷新 2026-10-21。
- **建模方式**：`system-modeler` + `c4model`（Structurizr DSL）+ `graphviz`（两张流程 DOT）。
- **证据索引**：每条断言对应哪个文件哪一行、置信度多高，全部在 [system-model.evidence.md](system-model.evidence.md)。本文只写结论，不重复证据。
- **读图顺序**：本文 → [SystemContext / Containers / CoreComponents / AppComponents 四个 C4 视图](vlive.structurizr.dsl) → [batch-flow.dot](batch-flow.dot)（一批的端到端）→ [compose-pipeline.dot](compose-pipeline.dot)（一项的八道关口）。

## L0 · 系统能力

一次装机的、离线的、单目录批处理：**把相册目录里同名的 `IMG_x.jpg` 与 `IMG_x.mp4` 拼成一个符合 Google Motion Photo 1.0 的单文件动态照片**，写到目标目录，并让相册立刻认得它。

能力边界就三句话：

- 不解码、不转码、不重编码 —— 只做字节搬运与元数据重写（[MotionPhotoComposer.kt:25-27](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/MotionPhotoComposer.kt#L25)）。
- 只处理一层目录，不递归（[PairPlanner.kt:32-34](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/PairPlanner.kt#L32)）。
- 只在同一台设备的共享存储上工作，没有网络参与。

## L1 · 系统上下文

APK 之外只有三个东西，全部是设备本地的：

| 外部 | 与系统的关系 | 交互方式 |
| --- | --- | --- |
| 操作者 | 侧载 APK、选源/目标目录、勾选"合成后删除原文件"、开始/取消 | 唯一一屏 UI |
| 共享存储文件系统 `/storage/emulated/0` | **唯一的输入源与唯一的输出汇**：读 `IMG_x.jpg`/`IMG_x.mp4`，写 `x_MP.jpg`、`目标目录/.tmp/*.part`，可选删原文件 | `java.io.File` / `FileChannel` |
| MediaStore / 相册 | 收尾接收"产物 + 已删原文件"的绝对路径扫描；它认不认，就是这个项目的验收判据 | `MediaScannerConnection.scanFile` |
| 系统设置授权页 | `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` 的落点；vivo/OriginOS 上可能跳不到，只能回查 | `Intent` + Toast 退化 |

**系统边界内没有网络、没有服务端、没有数据库、没有后台线程池。** 全部工作发生在一次进程内的顺序文件读写上。

"相册能播"这件事要单独说清：它**不是一条运行时集成边**。代码里没有任何 API 可以问"这张图被认成动态照片了吗"——识别完全由产物的字节布局与 XMP 目录决定（见 [motion-photo-container.md](../motion-photo-container.md)）。所以这条边在模型里只以"验收判据"的形式存在，只有真机能判。

## L2 · 容器：两个构建单元，一个进程

| 断言 | 依据 |
| --- | --- |
| 只有**一个**可部署单元（单个 Android 进程，唯一 Activity 是 `.app.MainActivity`）；CI 每次 push 同时上传 debug 与 release 两个 APK，release 沿用 debug 签名 | [.github/workflows/build.yml:46-55](file:///D:/projects/piece/live-photo-batch/.github/workflows/build.yml#L46)、[app/build.gradle.kts:31-34](file:///D:/projects/piece/live-photo-batch/app/build.gradle.kts#L31)、[AndroidManifest.xml:19-26](file:///D:/projects/piece/live-photo-batch/app/src/main/AndroidManifest.xml#L19) |
| `:core` 与 `:app` 的边界是**构建隔离**，不是进程边界也不是部署边界 | [settings.gradle.kts:15](file:///D:/projects/piece/live-photo-batch/settings.gradle.kts#L15)、[app/build.gradle.kts:49](file:///D:/projects/piece/live-photo-batch/app/build.gradle.kts#L49) |
| `:core` 是纯 JVM：插件只有 `kotlin.jvm`，依赖只有 JUnit，`core/src/main` 全量无 `import android` | [core/build.gradle.kts:1-9](file:///D:/projects/piece/live-photo-batch/core/build.gradle.kts#L1) |
| `:app` 从 `:core` 只 import 9 个符号（`BatchRunner`、`BatchOptions`、`BatchStorage`、`ChannelSource`、`DirEntry`、`ItemResult`、`ItemStatus`、`FailureReason`、`CancelToken`） | [FileStorage.kt:3-5](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L3)、[BatchViewModel.kt:9-13](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L9) |
| 并发模型是**顺序单线程**：一个协程跑完整批，没有并行合成 | [BatchViewModel.kt:174](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L174)、[BatchRunner.kt:95](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/BatchRunner.kt#L95) |
| 设备要求：`minSdk 30` / `targetSdk 36` / `compileSdk 37.0`，JVM 17；release 也用 debug 签名（内部工具，不发商店） | [app/build.gradle.kts:17-35](file:///D:/projects/piece/live-photo-batch/app/build.gradle.kts#L17) |

`:core` 之所以值得单独一个构建单元，只有一个理由：**它能在没有设备的 JVM 上被测**（145 条测试，见 [testing.md](../testing.md)）。这不是分层仪式，是测试接缝。

## L3 · `:core` —— 字节层面的判断全在这里

两级编排 + 一条合成线。`PairPlanner` 决定"有哪些活"，`BatchRunner` 决定"每项怎么落地"，字节的判断全部集中在 `MotionPhotoComposer` 调度的那几个对象里。

| 组件 | 负责的那一个判断 | 位置 |
| --- | --- | --- |
| `PairPlanner` | 哪些文件算一对。**驱动集是视频**：只有 `IMG_*.mp4` 去找同名 `jpg/jpeg`；`_MP` 结尾的图像不作候选；产物名 `<stem>_MP.jpg` | [PairPlanner.kt:71](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/PairPlanner.kt#L71) |
| `BatchRunner` | 一项怎么落地、批次怎么中断。`.part → close → rename → 删原文件` 的提交顺序；取消只在项与项之间生效；单项失败不中断批次 | [BatchRunner.kt:62](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/BatchRunner.kt#L62) |
| `BatchStorage` / `ChannelSource`（**端口**） | `:core` 认识的全部文件动作。路径是字符串、分隔符固定 `/`，来源可反复 `open()`（合成要读两遍） | [BatchStorage.kt:13](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/BatchStorage.kt#L13)、[Sources.kt:15](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/Sources.kt#L15) |
| `MotionPhotoComposer` | 合成入口与**关口的顺序**。八道关口全过之前对 sink 一个字节不写；全程 64 KiB 定长缓冲、常数内存 | [MotionPhotoComposer.kt:59](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/MotionPhotoComposer.kt#L59) |
| `JpegScanner` / `JpegStructure` | JPEG 的真实边界在哪：`imageEnd`、旧 XMP 的字节区间与文本、有没有分块扩展 XMP。按 marker 走段，**绝不搜 `FFD9`** | [JpegStructure.kt:31](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/JpegStructure.kt#L31) |
| `TrailerClassifier` | 尾巴**是不是**上一轮追加的视频（16 字节探针 + 源 XMP 信号）→ `ALREADY_MOTION_PHOTO` | [TrailerClassifier.kt:42](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/TrailerClassifier.kt#L42) |
| `TrailerModel` | 尾巴**该被目录声明成什么**（GainMap 整条 / 主图 Padding 整条）。对任何输入都不抛 | [TrailerModel.kt:62](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/TrailerModel.kt#L62) |
| `MotionDetector` | 源 XMP 是否已带动态照片语义（正则必须带命名空间前缀） | [MotionDetector.kt:14](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/MotionDetector.kt#L14) |
| `VivoLivePhotoTail` | vivo 末盒能不能改、改完多少字节。看不懂就返回 `null`，不伪造厂商结构 | [VivoLivePhotoTail.kt:52](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/VivoLivePhotoTail.kt#L52) |
| `XmpPacket` | 产物元数据的**唯一**生成入口：清空白写，目录 `Primary →（GainMap）→ MotionPhoto` | [XmpPacket.kt:73](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/XmpPacket.kt#L73) |
| `JpegXmpPatcher` | 区间搬运：新 XMP 写回**旧段原位**，旧段靠"读掉不写"丢弃，`imageEnd` 之后的尾巴整条搬走 | [JpegXmpPatcher.kt:37](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/JpegXmpPatcher.kt#L37) |
| `ByteChannels.writeAll` | 排空写：`write()` 允许只消费部分缓冲，单次调用会写出短文件 | [ByteChannels.kt:7](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/ByteChannels.kt#L7) |
| `InMemoryStorage`（test） | 端口的第二个实现，让整批编排无设备可测 | [InMemoryStorage.kt](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/InMemoryStorage.kt) |

### 两条刻意**不画**的边

画了就是假事实：

1. **`TrailerModel` 不调用 `JpegScanner`。** 它自带一份 `BoundedCursor` 重新实现了同一套 marker 规则，只多一条"物理读取总量不超过文件大小"的硬边界（[TrailerModel.kt:132](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/TrailerModel.kt#L132) vs [JpegStructure.kt:107](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/JpegStructure.kt#L107)）。两者共享的是**规则**，不是代码。
2. **`MotionPhotoComposer` 到端口之间是虚线。** 合成器只消费 `ChannelSource` / `WritableByteChannel` 这两个类型，实例由 `BatchRunner` 从端口取（[MotionPhotoComposer.kt:113](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/MotionPhotoComposer.kt#L113) 的对端在 [BatchRunner.kt:112](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/BatchRunner.kt#L112)）。

同理，`TrailerClassifier` 与 `TrailerModel` 是两个问题，不许合并（[TrailerModel.kt:20-21](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/TrailerModel.kt#L20)）：一个问身份，一个问声明。

## L3 · `:app` —— 四类适配器 + 一屏 UI

`:app` 不参与任何字节判断。它提供文件、权限、目录浏览、媒体扫描四类能力，加上一个把状态画出来的界面。

| 组件 | 职责 | 位置 |
| --- | --- | --- |
| `MainActivity` | 唯一 Activity；`installSplashScreen()` 在 `super.onCreate` **之前**、退出动画 220ms 淡出收缩后显式 `setTheme`；`viewModelFactory` 造 VM；跳授权页失败退化成 Toast；`onResume` 回查授权；刻意**不**开 `enableEdgeToEdge()` | [MainActivity.kt:25](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/MainActivity.kt#L25)、[:36-54](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/MainActivity.kt#L36) |
| `BatchScreen` + `DirectoryPicker` | 单屏：权限提示、两个目录、危险开关、进度、逐项报告、全屏目录浏览弹窗（**只有一条列表**：`上一级` + 当前层条目）。系统返回键**先当"上一级"用**，到 root 才关窗；换层用 `AnimatedContent` 整层推入/弹出+淡入淡出；别名与`上一级`都按**画出来的那一层**算，返回行、返回手势、列表说的是同一件事。目录读取在 `produceState` + `Dispatchers.IO`，**绝不发生在组合期**。**这一屏没有一个字面文案**，全部 `stringResource` / `pluralStringResource`；`globalError` 是 `MessageText(id, args)`，在这里才按当前语言解析 | [BatchScreen.kt:96](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt#L96)、[:354](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt#L354)、[:399](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt#L399)、[:414](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt#L414) |
| `ui/IosControls` + `ui/IosStyle` + `ui/IosEntrance` | 按 iOS「设置」分组列表自绘的控件层与令牌层。颜色不再是编译期常量，而是两份 [`IosPalette`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L27)（浅 / 深），[`IosTheme` 是全项目唯一一次深浅判定](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L197)：`isSystemInDarkTheme()` 选的同一套色板同时交给 `LocalIosStyle`（自绘层）与 `MaterialTheme`（Dialog / 兜底色）。几何与时长仍是常量（`IosMetrics` / `IosMotion`）。图形用 Canvas 画，不引入位图资源；状态全部提升。`IosEntrance` 是冷启动入场闸门：splash 退出前各段停在 alpha 0，退出后按 40ms 交错上浮，另有 600ms 超时兜底 | [IosStyle.kt:27 与 :115](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L27)、[IosTheme:197](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L197)、[IosMetrics:120](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L120)、[IosMotion:138](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L138)、[IosControls.kt:59](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt#L59)、[IosEntrance.kt:26-58](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosEntrance.kt#L26) |
| `res/values` 与两个 qualifier 目录 | 语言与主题的第二条通道，`isSystemInDarkTheme()` 管不到的窗口级颜色在这里。**默认（未限定）配置是英文**，中文在 `values-zh`（约 40 条 key + 一条 `plurals`）；`values/colors.xml` 给 `grouped_background` / `icon_background`，`values-night/colors.xml` **只**翻背景为纯黑（图标那块蓝刻意不翻），`values-night/themes.xml` 把 parent 换成不带 `.Light` 的 `DeviceDefault` 并关掉 light status/nav bar。`Theme.Vlive.Starting` 的 splash 底色引用 `@color/grouped_background`，所以冷启动那一帧跟着配置翻 | [values/strings.xml](file:///D:/projects/piece/live-photo-batch/app/src/main/res/values/strings.xml)、[values-zh/strings.xml](file:///D:/projects/piece/live-photo-batch/app/src/main/res/values-zh/strings.xml)、[values-night/colors.xml](file:///D:/projects/piece/live-photo-batch/app/src/main/res/values-night/colors.xml)、[values-night/themes.xml](file:///D:/projects/piece/live-photo-batch/app/src/main/res/values-night/themes.xml)、[values/themes.xml:24-28](file:///D:/projects/piece/live-photo-batch/app/src/main/res/values/themes.xml#L24) |
| `BatchViewModel` | 批次所有权：开工快照 → 全局前置校验 → `plan()` → `run()` → 逐项 CAS 累积 `UiState` → `NonCancellable` 收尾扫描。面向用户的提示以 [`MessageText`（`@StringRes id` + `List<Any> args`）](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L64) 形式给出，VM 因此不碰 `Context` | [BatchViewModel.kt:122](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L122) |
| `PermissionGate` | `MANAGE_EXTERNAL_STORAGE` 门面；真值经注入的 `() -> Boolean` **每次现读**，低于 API 30 短路放行 | [PermissionGate.kt:37](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/perm/PermissionGate.kt#L37) |
| `DirBrowser` + `mergedRootEntries` + `displayPath` | 真文件系统目录浏览：只列可读、非点开头的子目录；绝不抛，只给空列表。`presets()` **已删除** —— 它同时给别名和"要画出来的条目"，于是列表里会冒出磁盘上不是这么叫的项。现在 [`mergedRootEntries(aliases, children, currentPath)`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/browse/DirBrowser.kt#L106) 只把别名盖到这一层的真实子目录上（`<root>/Pictures → 图片`），[`displayPath(path, root, rootLabel)`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/browse/DirBrowser.kt#L31) 只管**显示**：把存储根换成传进来的别名、往下一级照原样跟着，root 之外的串不重命名（真路径仍旧喂给批次）。别名与标签都由调用方用字符串资源拼（[`folderAliases`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt#L322)），这个文件才能继续在本地 JVM 上测 | [DirBrowser.kt:71-84](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/browse/DirBrowser.kt#L71)、[:106](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/browse/DirBrowser.kt#L106)、[:31](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/browse/DirBrowser.kt#L31) |
| `FileStorage` | 端口的生产实现：`RandomAccessFile` + `FileChannel`、`close` 时 `force(true)` 再关、`move` 走 `rename(2)` 且不先删目标、`exists`/`delete` 一律先判 `isFile` | [FileStorage.kt:78](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L78) |
| `MediaStoreSync` | 一次性把全部路径交给 `scanFile`，最多等 2 秒；绝不抛，但把"未确认"回报给 UI | [MediaStoreSync.kt:44](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/MediaStoreSync.kt#L44) |

`BatchViewModel` 有两个构造器注入的测试接缝（权限供应器、收尾扫描器），生产入口用 `Application` 构造（[BatchViewModel.kt:131-135](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L131)）；因此 `:app` 的 32 条本地单测不需要 Robolectric，也不新增依赖 —— 连面向用户的提示也只带资源 id 与参数（`MessageText`），VM 全程不碰 `Context`。

## 端口契约：`BatchStorage` 的 7 条方法级约定

这个接口是 `:core` 唯一的外部依赖，也是两套实现最容易**静默分叉**的地方。每条约定都是契约文字，不是实现细节：

| 方法 | 契约要点 | 内存实现 | `FileStorage` |
| --- | --- | --- | --- |
| `list` | 只列一层、只给普通文件；**按 name 自然升序**（`PairPlanner` 取同 stem 的第一个候选）；目录不存在返回**空列表**，绝不抛 | [InMemoryStorage.kt:17-23](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/InMemoryStorage.kt#L17) | [FileStorage.kt:84-91](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L84)（`listFiles()` 返回 null → 空；`sortedBy` 不能省） |
| `open` | 只读、**可反复打开**（合成读两遍）；不存在抛 `FileNotFoundException` | 同上 | [FileStorage.kt:22-31](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L22)（每次新建一条 `RandomAccessFile` 通道） |
| `exists` | **只对当前存在的普通文件为 true**；目录一律 false | 天然 false（map 只装文件） | 必须写 `isFile`，不能用 `exists()`（[FileStorage.kt:96](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L96)） |
| `ensureDir` | 幂等建目录；建不起来**抛 `IOException`**，由调用方当"整批不开工"处理 | 同上 | [FileStorage.kt:98-103](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L98) |
| `openWrite` | 截断式；**`close()` 成功返回后字节才完整可见**，之前不作任何保证；重复 `close` 幂等，关闭后再 `write` 必须失败 | 攒到 close 才发布；抛 `IllegalStateException` | 做不到"关闭才可见"（打开即 truncate），可见性交给调用方的 `.part` + rename；抛 `ClosedChannelException`（[FileStorage.kt:40-52](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L40)） |
| `move` | 原子改名，**允许覆盖已存在的目标**；成功唯一可观察结果：`to` 内容 = `from` 原内容、`from` 不再存在；`from` 不存在返回 false 且两侧状态不变 | [InMemoryStorage.kt:54-57](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/InMemoryStorage.kt#L54) | `renameTo` → `rename(2)`，**禁止先删目标**（会开出"旧产物已消失、新产物未就位"的窗口）；失败才退化 `copyTo`（[FileStorage.kt:120-142](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L120)） |
| `delete` | 只删普通文件；目录返回 false 且不碰它 | 同上 | `File.delete()` 对**空目录**返回 true，所以先判 `isFile`（[FileStorage.kt:145](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L145)） |

三个共享同一个字符串的地方必须一起改：`BatchViewModel.DEFAULT_ROOT = "/storage/emulated/0"`（[BatchViewModel.kt:292](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L292)）同时喂 `FileStorage(root)`、`DirBrowser(root)` 和 `isSelectable()`。**不带尾斜杠、绝不空**——root 一旦带尾斜杠，`"/storage/emulated/0"` 本身会被当相对路径二次拼接成 `/storage/emulated/0/storage/emulated/0`，`list(root)` 静默返回空、`ensureDir(root)` 在真实存储里种一棵垃圾树（[FileStorage.kt:60-66](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L60)）。

## 数据与状态

没有数据库、没有迁移、没有 ORM。这个系统的数据形状就是三样：

- **磁盘**：`源目录/IMG_x.jpg` + `源目录/IMG_x.mp4` → `目标目录/x_MP.jpg`（+ `目标目录/.tmp/x_MP.jpg.part` 这一过路文件）。字节布局见 [motion-photo-container.md](../motion-photo-container.md)。
- **计划**：`PlanResult(jobs: List<PairJob>, unpaired: List<Unpaired>)`，UI 先拿它预览总数，再原样交给 `run()`，**不重新推导**（[BatchRunner.kt:59-60](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/BatchRunner.kt#L59)）。
- **结果**：`ItemResult(stem, outputName, status, reason, bytes, deleteFailures, deletedSources)`，状态三值 `SUCCESS / SKIPPED / FAILED`（[BatchRunner.kt:9-19](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/BatchRunner.kt#L9)）。

一项的生命周期只有三条出路，且**三条都执行 `.part` 清理**：

1. 被关口拒掉 → 无条件删 `.part` → `SKIPPED`（仅 `ALREADY_MOTION_PHOTO` 与 `NO_PAIR`）或 `FAILED`；
2. 合成成功但 `rename` 失败 → 删 `.part` → `FAILED(IO_ERROR)`，最终名上什么都没有，原文件一律不动；
3. `rename` 成功 → **本项当场提交**，先建出 `SUCCESS`，然后才依次删原文件；删除失败只进 `deleteFailures`，没有任何路径能把 `SUCCESS` 回改写（[BatchRunner.kt:142-166](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/BatchRunner.kt#L142)）。

不变量（spec §7.6 的 as-built 版本）：**每一个写出的产物，加上每一个已删原文件，都必须到达媒体扫描**。唯一计算处是 `touchedPathsFor()`（[BatchViewModel.kt:78-83](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L78)），且只跑在 `NonCancellable + Dispatchers.IO` 的收尾块里。

## 信任与权限边界

- 唯一的权限是 `MANAGE_EXTERNAL_STORAGE`（[AndroidManifest.xml:5-10](file:///D:/projects/piece/live-photo-batch/app/src/main/AndroidManifest.xml#L5)）。它是**开工的全局前置条件**：没授权时按钮禁掉，`start()` 还静默拒绝第二次（[BatchViewModel.kt:182-184](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L182)），绝不带着读写去撞 `SecurityException`。
- **能看 ≠ 能选**：`DirBrowser.subdirs` 只判 `startsWith("/")`，能浏览到 `/sdcard`、`/storage/emulated`；而 `FileStorage.resolve()` 只对 root 之内（含 root 本身）原样使用。这个分叉由 `BatchViewModel.isSelectable()` 在 UI 侧挡掉，并在 `runBatch` 用**同一个谓词**再守一遍（[BatchViewModel.kt:155](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L155)、[:206](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L206)）。
- 危险动作只有一个：`deleteOriginals`。它被渲染成系统红的开关，且只在 `rename` 成功之后才动原文件。
- 不可撤销性集中在文件覆盖：同名产物**直接替换**（`move` 允许覆盖，`PairJob.outputExists` 只是给 UI 看的信号）。

## 未知与低置信区（下一步要验的）

| 项 | 现状 | 怎么验 |
| --- | --- | --- |
| 相册是否把产物认成动态照片 | 判据来自真机单变量对照与二分（2026-10-18 ~ 2026-10-20，vivo V2419A），**当前 185 条测试没有一条能判它** | 装机跑一批，肉眼 + `adb` 查 MediaStore 条目 |
| 现 XMP 包（1,057 字节那份）在真机上的接受度 | 夹具与断言已就位（[XmpPacketTest.kt:24-26](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/XmpPacketTest.kt#L24)），注释明确要求重新装机验收 | 同上 |
| 非 vivo 厂商（OPPO/小米/Pixel）的识别 | 只在 `TrailerClassifier` 的 64 位盒反例、`XmpPacket` 的 OPPO 反例里作为**反例**出现过；`VivoLivePhotoTail` 只认 vivo 末盒，其余厂商一律原样追加 | 各厂商真机各跑一批 |
| 启动入场的逐帧手感（logo 淡出与卡片上浮接不接得上、系统关动画时入场是否归零） | 2026-10-21 已在 V2419A 装机看过一轮：桌面图标（含圆形遮罩与深色）、冷启动色场、交错入场落位均正常；深色 splash 的白标记被 ROM 画成黑，**实测定位后裁定接受**（[decisions.md §5.16](../decisions.md#516-深色-splash-的白标记被-rom-画成黑2026-10-21-装机裁定)）。`ANIMATOR_DURATION_SCALE` 的行为仍未确认（入场刻意不读它，见 [§5.11](../decisions.md#511-图标与启动过渡2026-10-21-增量)） | 再装机看；要收口补 Compose UI 测试 |
| 性能预算（"100 张约 15 秒"） | 只在注释里作为**动机**出现（[JpegStructure.kt:100-102](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/JpegStructure.kt#L100)），没有任何计时测试 | 真机计时 |
| 跨卷 `move` 退化路径 | 代码里有（`copyTo` + `delete`），无测试覆盖 | 仪器化测试构造跨卷场景 |
| `ByteArraySource` 住在 main sourceSet | 当前只有测试在用（[Sources.kt:20](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/Sources.kt#L20)） | 确认是否应移入 test，或留给未来的内存来源 |

## 维护这份模型

- 图源即事实载体：改代码就改 `.dsl` / `.dot`，**不要手改 `.svg`**（派生产物）。
- 重渲染：`npm i @viz-js/viz && node render-svg.mjs`（或本机 graphviz：`dot -Tsvg x.dot -o x.svg`）。渲染验证状态记录在 [docs/README.md](../README.md)。
- 结构性事实（模块、端口、并发模型）过时了要同时改 [system-model.evidence.md](system-model.evidence.md) 的 `sourceRefs`；证据表里的行号会随代码漂移，这是唯一需要定期重新对齐的部分。
