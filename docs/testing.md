# 测试与验证

- **回答的问题**：哪些行为已经被钉死、钉在哪个测试里；哪些**只能靠真机**，验收该跑什么命令、看什么现象。
- **范围**：as-built @ `ff9ad98` + 工作区增量（图标 / SplashScreen / 入场动画 / 单列目录浏览 / 英文本地化 + 跟随系统深色 / 玻璃 bar 改造），2026-10-21。计数来自 `grep -c "@Test"`（全部是普通 `@Test`，没有 `@ParameterizedTest`/`@TestFactory`），并与 `:app:testDebugUnitTest` 的 JUnit XML 对过一遍。

## 总览：213 条测试，三个 sourceSet

| sourceSet | 框架 | 条数 | 跑在哪 | CI 是否执行 |
| --- | --- | --- | --- | --- |
| `core/src/test` | JUnit 5 Jupiter（BOM 6.1.3），`useJUnitPlatform()` | **145** | 本机 JVM，不需要设备 | ✅ `:core:test` |
| `app/src/test` | JUnit 4.13.2，**不引 Robolectric** | **60** | 本机 JVM（真协程） | ✅ `:app:testDebugUnitTest`（本机 1 条 `assumeTrue` 跳过，见下） |
| `app/src/androidTest` | JUnit 4 + `AndroidJUnit4` / `AndroidJUnitRunner` | **8** | 真机/模拟器 | ❌ **只编译**（`assembleDebugAndroidTest`） |

每个文件的方法数：

```
core:  BatchRunnerTest 23 · MotionPhotoComposerTest 21 · TrailerClassifierTest 20
       PairPlannerTest 13 · JpegXmpPatcherTest 13 · JpegStructureTest 12 · XmpPacketTest 12
       InMemoryStorageTest 10 · VivoLivePhotoTailTest 9 · TrailerModelTest 7
       SourcesTest 3 · MotionDetectorTest 2      （Fixtures / InMemoryStorage 是夹具，0 条）
app:   BatchViewModelTest 14 · DirBrowserTest 15 · ReportPageTest 8 · LogRotationTest 8
       TouchedPathsForTest 4 · PermissionGateTest 2 · ui/TitleBarAlphaTest 6 · ui/AccentForTest 3
androidTest: FileStorageTest 8
```

`DirBrowserTest` 的 `absolute path is used as-is and ignores the root` 用 `assumeTrue(File.separator == "/")` 守 POSIX 专属规则，所以在 Windows 构建主机上是**跑过但跳过**（`DirBrowserTest` 那份 JUnit XML 报 `tests=15 skipped=1`；整个 `:app` 模块 60 条里跳的就是这一条），不是没编译进去。

## 命令

```bash
./gradlew :core:test                     # 145 条，最快反馈，纯 JVM
./gradlew :app:testDebugUnitTest         # 60 条 ViewModel / DirBrowser / ReportPage / LogRotation / PermissionGate / accent 四值 / titleBarAlpha
./gradlew :app:assembleDebug             # 产出 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebugAndroidTest   # CI 用它保证仪器化测试还能编译
./gradlew :app:connectedDebugAndroidTest  # 8 条契约测试，必须插着设备跑
```

CI 有两个工作流、三个 job。[build.yml](file:///D:/projects/piece/live-photo-batch/.github/workflows/build.yml#L12) 在 push / PR 上跑：`core-tests` 跑 `:core:test`，`apk` 跑 `:app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleRelease`，并上传两个产物 —— `vlive-debug` 与 `vlive-release`。release 用的是 `signingConfigs.debug`，所以那个包**能侧载、不能上商店**。
[release.yml](file:///D:/projects/piece/live-photo-batch/.github/workflows/release.yml) 只有 `workflow_dispatch`：自己构建两个 APK，然后发一个 tag 为 `YYYY-MM-DD-HHMM`（Asia/Shanghai）的 Release，asset 匿名可下 —— artifact 要登录网页才拿得到，这条路才是给人发版的。理由与两条实测教训见 [decisions.md §6.6](decisions.md#66-发版是一个手动按钮tag-用日期加时分2026-10-21-增量)。

## 六个测试接缝（为什么不需要设备）

| 接缝 | 做法 | 位置 |
| --- | --- | --- |
| 文件动作 | `InMemoryStorage` 实现 `BatchStorage`：`LinkedHashMap<String, ByteArray>`，目录由 key 前缀隐含；`openWrite` **只在 `close()` 返回后**发布条目；额外给 `tmpFiles` / `bytesOf` 两个测试专用读法 | [core/src/test/…/InMemoryStorage.kt:14-72](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/InMemoryStorage.kt#L14) |
| 字节来源 | `ByteArraySource` 让合成整条线直接吃内存数组（注意它住在 **main** sourceSet，见 [decisions.md 技术债](decisions.md#7-已知技术债下次动这片之前先读)） | [Sources.kt:20-36](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/Sources.kt#L20) |
| 权限 | `BatchViewModel(isGranted = { … })`：假 lambda 每次现读，不缓存 | [BatchViewModel.kt:122-135](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L122)、[BatchViewModelTest.kt:58-62](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/BatchViewModelTest.kt#L58) |
| 媒体扫描 | `BatchViewModel(scanner = { … })`：假扫描器记录收到的路径 | 同上 |
| 存储根 | `FileStorage(root)` / `DirBrowser(root)` / `BatchViewModel(root = …)` 允许注入临时目录，与生产同一个 root 契约规则 | [FileStorage.kt:78-82](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L78) |
| 目录设置 | `BatchViewModel(settings = …)`：`BatchSettings` 只有 `load()` / `save()` 两个 suspend 方法，测试给内存假实现，DataStore 那一层不进单测 | [BatchSettings.kt:28-36](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/settings/BatchSettings.kt#L28)、[BatchViewModelTest.kt:61-67](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/BatchViewModelTest.kt#L61) |
| 日志保留规则 | `planLogCleanup(files, now, …)` 是纯函数：目录列表由调用方给，删除决定在这里算 | [LogRotation.kt:21-57](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/log/LogRotation.kt#L21)、[LogRotationTest](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/log/LogRotationTest.kt) |

`app/src/test` 能驱动真协程是因为 lifecycle 2.11 在没有 Android 主线程时把 `viewModelScope` 退化成 `SupervisorJob + Dispatchers.Default`（实测，写在 [BatchViewModel.kt:100-101](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L100)）。

## 夹具都是合成的

[Fixtures.kt](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/Fixtures.kt) 里没有真实照片：没有 `src/test/resources`，没有 base64/hex 大块。工厂清单：

| 夹具 | 造的字节形状 | 位置 |
| --- | --- | --- |
| `segment` / `app1` / `sof0` / `sos` / `jpeg(tail=…)` | 最小合法 JPEG，可按需带 EOI 之后的尾巴 | [:11-40](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/Fixtures.kt#L11) |
| `mp4(size)` | `ftyp` 盒 + 每 16 字节塞一个 `FF D9` 诱饵（专门测"绝不搜 FFD9"） | [:42-60](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/Fixtures.kt#L42) |
| `box` / `ftypBox` / `uint32` / `zeroFill` | ISO-BMFF 盒与零填充 | [:69-88](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/Fixtures.kt#L69) |
| `vendorRecord` / `vendorTrailer` | 387 B `streamdataDEGS` 记录；399 B `vivo{json}` + `cameralbum!` 尾记录 | [:94-116](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/Fixtures.kt#L94) |
| `specPacket(videoSize)` | 那份**逐字节钉死**的 XMP 包（当前 1,057 字节） | [:164](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/Fixtures.kt#L164) |
| `gainMapXmp` / `gainMapImage` / `gainMapJpeg` | 带 ISO 21496-1 声明的副图 JPEG | [:174-232](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/Fixtures.kt#L174) |
| `vendorTrailerJpeg` / `zeroFilledJpeg` / `composedMotionPhotoJpeg` | 上一节那些尾巴的成品组合 | [:235-247](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/Fixtures.kt#L235) |
| `vivoTailBox` / `vivoMp4` + `REAL_LIVEPHOTO` | vivo 末盒（`livephoto` 值用真机编号字面量 `17924055463797e14dd500000000`） | [:257-279](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/Fixtures.kt#L257) |

**真机值被转述，真机字节没有**：X200 Pro mini 的 JSON 字段布局、5,976,771 字节的 `mdat` 都只以字面量出现在注释/夹具参数里。所以格式回归**不可复现**于真实语料 —— 这是 [decisions.md 技术债](decisions.md#7-已知技术债下次动这片之前先读) 里"加一份 golden 文件"那条的由来。

## 已经钉死的高价值契约

| 钉的是什么 | 测试 |
| --- | --- |
| `.part → close → move → 删原文件` 的**精确操作顺序** | [BatchRunnerTest.kt:62](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/BatchRunnerTest.kt#L62) `tmp write then move then delete is the exact operation order` |
| 删除只发生在提交之后；原地批次也要走 `.tmp` | [:54](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/BatchRunnerTest.kt#L54)、[:156](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/BatchRunnerTest.kt#L156)、[:417](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/BatchRunnerTest.kt#L417)（抛出的删除只加警告，不回改写 SUCCESS） |
| 取消只在项与项之间生效（含 `unpaired` 预循环；开局取消零效果） | [:121](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/BatchRunnerTest.kt#L121)、[:445](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/BatchRunnerTest.kt#L445)、[:461](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/BatchRunnerTest.kt#L461) |
| 失败项不留 `.part`；清理自己抛出也不许穿出 `run` | [:189](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/BatchRunnerTest.kt#L189)、[:360](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/BatchRunnerTest.kt#L360)、[:389](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/BatchRunnerTest.kt#L389) |
| 只有 `ALREADY_MOTION_PHOTO` 算跳过 | [:208](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/BatchRunnerTest.kt#L208) |
| `move` 返回 false → `IO_ERROR` 且原文件不动 | [:292](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/BatchRunnerTest.kt#L292) |
| **64 位扩展盒长**（`size==1` → `largesize`）：vivo/OPPO 的 `mdat` 形态；截断的 `largesize` 与盖住窗口的 64 位填充盒都**不许猜** | [TrailerClassifierTest.kt:58](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/TrailerClassifierTest.kt#L58)、[:66](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/TrailerClassifierTest.kt#L66)、[:72](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/TrailerClassifierTest.kt#L72) |
| XMP 信号优先于尾巴探针；裸词不许误判成动态照片 | [:123](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/TrailerClassifierTest.kt#L123)、[:153](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/TrailerClassifierTest.kt#L153)、[MotionDetectorTest.kt:20-22](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/MotionDetectorTest.kt#L20) |
| `GainMap` / `Padding` **互斥**，违反时必须是 `IllegalStateException`（**不是** `IllegalArgumentException`） | [XmpPacketTest.kt:151-162](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/XmpPacketTest.kt#L151) |
| XMP 包逐字节 = 1,057 那份夹具 | [XmpPacketTest.kt:24-26](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/XmpPacketTest.kt#L24) |
| vivo 末盒：加前缀 + 两处长度同步；伪 UUID 与尾记录逐字节不变；六种"看不懂"一律返回 null | [VivoLivePhotoTailTest.kt:72](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/VivoLivePhotoTailTest.kt#L72)、[:87](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/VivoLivePhotoTailTest.kt#L87)、[:100](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/VivoLivePhotoTailTest.kt#L100)、[:111-145](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/VivoLivePhotoTailTest.kt#L111) |
| 尾巴定性：真副图整条声明 `GainMap`；MPF 副图 / 厂商 JSON / 零填充 / **段长越界的畸形副图**一律降级 `Padding`；空尾巴什么都不声明 | [TrailerModelTest.kt:28](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/TrailerModelTest.kt#L28)、[:41](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/TrailerModelTest.kt#L41)、[:51](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/TrailerModelTest.kt#L51)、[:60](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/TrailerModelTest.kt#L60)、[:76](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/TrailerModelTest.kt#L76)、[:91](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/TrailerModelTest.kt#L91)（尾巴从 `imageEnd` 流式走、绝不碰主图） |
| 配对规则：`IMG_` 前缀、`_MP` 不作候选、自然序、同 stem 取第一个、`.nomedia` 之类的边界 | [PairPlannerTest.kt](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/PairPlannerTest.kt)（13 条） |
| 新 XMP 写回**旧段原位**、旧段"读掉不写"、尾巴整条搬走、`patch` 返回值语义 | [JpegXmpPatcherTest.kt](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/JpegXmpPatcherTest.kt)（13 条，含两条 `@Timeout(SEPARATE_THREAD)`） |
| `touchedPathsFor`：每个 SUCCESS 产物 + 每个已删原文件；取消尾巴天然覆盖 | [TouchedPathsForTest.kt](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/TouchedPathsForTest.kt)（4 条） |
| 收尾扫描在 `NonCancellable` 里送达；扫描未确认变成**可见的一行提示**而不是沉默；扫描器抛出不会卡死批次 | [BatchViewModelTest.kt:135](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/BatchViewModelTest.kt#L135)、[:297](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/BatchViewModelTest.kt#L297)、[:313](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/BatchViewModelTest.kt#L313) |
| 全局提示是**资源 id + 参数**而不是拼好的文案：id 与 `args` 各自断言，VM 因此不需要 `Context`（[:249](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/BatchViewModelTest.kt#L249) 钉 `err_source_unreadable` + 那条路径、[:313](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/BatchViewModelTest.kt#L313) 钉 `err_scan_failed` + `"boom"`) | 同上两行 |
| root 选择规则与 `FileStorage` 共享同一个谓词；越界快照静默拒绝 | [:279](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/BatchViewModelTest.kt#L279)、[:327](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/BatchViewModelTest.kt#L327) |
| 目录浏览**只出一列**：别名只替换显示名、当前目录不列自己、没被别名提到的子目录仍然可达、**嵌套别名不得补成快捷方式**（`相机 = DCIM/Camera` 那条机制已撤销）、别的根下的别名一律不掺、顺序稳定 | [DirBrowserTest.kt:103](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/browse/DirBrowserTest.kt#L103)、[:124](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/browse/DirBrowserTest.kt#L124)、[:138](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/browse/DirBrowserTest.kt#L138)、[:150](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/browse/DirBrowserTest.kt#L150)、[:166](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/browse/DirBrowserTest.kt#L166)、[:182](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/browse/DirBrowserTest.kt#L182) |
| 副标题 `displayPath`：根显示 root 别名、下级保持 `别名/DCIM/Camera`、**root 之外不改名**（那种路径选不了，改名就是骗人）。别名是**入参**而不是常量，所以这两种语言的说法都能在同一条测试里断言 | [DirBrowserTest.kt:200](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/browse/DirBrowserTest.kt#L200)、[:210](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/browse/DirBrowserTest.kt#L210)、[:221](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/browse/DirBrowserTest.kt#L221) |
| 报告分页与筛选：`All` 保留全部且顺序不变、三个状态档各只收自己的行、尾页是短页、**连续页不重也不漏**、页码越界夹回、空结果仍算一页、空批次也是一页、`size = 0` 不死循环 | [ReportPageTest.kt](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/ReportPageTest.kt)（8 条） |
| 目录选择的持久化：选过的路径写回设置；存下来的路径过不了 `isSelectable` 就**不采纳**；用户已经改过之后，一次迟到的 `restore()` 不许把它覆盖回去 | 3 条用例（[BatchViewModelTest.kt:362-406](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/BatchViewModelTest.kt#L362)） |
| 日志保留：过期 / 数量 / **总体积** 三条上限任一条超了都往下删，删除顺序从旧到新，**当前文件永不删**，`maxFiles = 0` 也不清空目录 | 8 条用例（[LogRotationTest.kt](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/log/LogRotationTest.kt)） |
| 端口实现分叉点：`exists`/`delete` 对目录必须 false、`list` 排序与缺目录给空、root 不二次拼接、close 后字节完整 | [FileStorageTest.kt:94](file:///D:/projects/piece/live-photo-batch/app/src/androidTest/kotlin/dev/zayn/vlive/io/FileStorageTest.kt#L94)、[:58](file:///D:/projects/piece/live-photo-batch/app/src/androidTest/kotlin/dev/zayn/vlive/io/FileStorageTest.kt#L58)、[:73](file:///D:/projects/piece/live-photo-batch/app/src/androidTest/kotlin/dev/zayn/vlive/io/FileStorageTest.kt#L73)、[:162](file:///D:/projects/piece/live-photo-batch/app/src/androidTest/kotlin/dev/zayn/vlive/io/FileStorageTest.kt#L162)、[:144](file:///D:/projects/piece/live-photo-batch/app/src/androidTest/kotlin/dev/zayn/vlive/io/FileStorageTest.kt#L144) |
| 内存实现镜像同样几条契约（两侧对齐才有意义） | [InMemoryStorageTest.kt:48-83](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/InMemoryStorageTest.kt#L48) |

## 本地化与深浅色：没有设备时验到哪一层

`app/src/test` 一条也不碰文案与配色 —— 那是资源系统 + 渲染管线的事，JVM 单测够不着（引 Robolectric 只为读字符串不值）。这一片靠**三个静态检查**兜住，都在本地可重复：

| 检查 | 命令 | 现在验到了什么 |
| --- | --- | --- |
| 两条语言都没有漏 key、没有硬编码文案 | `./gradlew :app:lintDebug` 后读 `app/build/reports/lint-results-debug.sarif` | `MissingTranslation` / `ExtraTranslation` / `MissingQuantity` / `HardcodedText` **全部 0 条**。基线剩 9 条信息级（`MonochromeLauncherIcon` ×2 是[刻意撤销的 monochrome 层](decisions.md#511-图标与启动过渡2026-10-21-增量)、`ObsoleteSdkInt` ×1 就是[不能删的 `-v26`](decisions.md#512--v26-这个多余的目录限定符不能删)；`minSdk` 抬到 31 删掉两条短路分支前是 12 条，见 [§5.20](decisions.md#520-minsdk-抬到-31真模糊的门槛也是它带走了分支2026-10-21-增量)） |
| 英文确实是**默认配置**、中文是 `zh` 限定 | `aapt2 dump resources app/build/outputs/apk/debug/app-debug.apk` | `string/action_cancel` → `() "Cancel"` / `(zh) "取消"`；`plurals/report_summary_warning` → 默认两档 `one`/`other`，`(zh)` 只 `other` |
| 冷启动那一帧在深色下是黑的、图标底色的蓝不跟着翻 | 同上 | `color/grouped_background` → `() #fff2f2f7` / `(night) #ff000000`；`color/icon_background` → **只有 `() #ff007aff`**（没有 night 变体，正是设计要的效果）；`style/Theme.Vlive.Starting` 的 `windowSplashScreenBackground=@color/grouped_background`、`windowSplashScreenIconBackgroundColor=@color/icon_background` |

这三条只证明**资源打进包了、并且配对正确**。渲染出来对不对要靠装机，2026-10-21 已经在 vivo V2419A 上看过一轮：浅色与深色的主界面、目录别名、桌面图标（含圆形遮罩）都对；**只有一件事不对并被裁定接受** —— 深色冷启动那一帧里 splash 的白标记被 OriginOS 画成黑色（探针定位过程与三条无效尝试见 [decisions.md §5.16](decisions.md#516-深色-splash-的白标记被-rom-画成黑2026-10-21-装机裁定)）。

**英文整屏与深色弹窗已经在 2026-10-22 看过一轮**（API 36 的 `vlive_api36` 模拟器，装的是 `cab321b`）：
英文主界面、英文目录弹窗（root 读作 `Internal storage`、`Choose here` / `Close` 都在自己的让位区里）、
深色下的弹窗与报告行（`#1C1C1E` 卡片 + 白字 + 绿色状态点）都对，没有中文残留也没有空字符串 ——
唯一的"中英混排"是浅色副标题 `live photo 批量合成` 这一条资源本身就这么写。截图与逐条结论在
[decisions.md §5.19 的"设备实测补记"](decisions.md#519-玻璃只给三条浮动-bar卡片与按钮不各自糊一层2026-10-21-增量)。
**切语言的方式要记下来**：`settings put system system_locales zh-CN` 在这块镜像上写完冷启动界面照旧英文，
中文是靠按应用的 `cmd locale set-app-locales dev.zayn.vlive --locales zh-CN` 渲染出来的，验完已还原。

报告那一屏在 2026-10-21 装机走过一遍（真机 36 项一批）：默认折叠成一行、点开后是筛选档 + 一页 20 行 + 分页行、`失败` 档显示空提示、`第 1/2 页` 与 `第 2/2 页` 的尾页 16 行、到边的那侧按钮禁用 —— 都对。

装机本身在这台机器上有一条固定流程：`adb install -r -t` 会被 vivo 拒（`INSTALL_FAILED_ABORTED: User rejected permissions`），把 APK 推到 `/data/local/tmp` 再 `pm install -r -t` 就能过；屏幕熄灭或锁屏时两条路都会被拒，先 `input keyevent KEYCODE_WAKEUP` + `wm dismiss-keyguard`。录冷启动用 `screenrecord --time-limit 2` 与 `am start` 同时开跑，回来按 12fps 拆帧才抓得到那一帧。

## 自动化测不到的（别假装测到了）

| 缺口 | 事实 | 兜底 |
| --- | --- | --- |
| **相册把产物认成动态照片** | 没有任何 API 可问；判据来自 vivo V2419A 真机实验 | 装机验收（下面步骤） |
| 现 XMP 包（1,057 字节那份）在真机上的接受度 | 注释明确要求重新装机验收（[XmpPacketTest.kt:19-20](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/XmpPacketTest.kt#L19)） | 同上 |
| `FileStorageTest` 8 条 | CI **只编译不执行**（[FileStorageTest.kt:16](file:///D:/projects/piece/live-photo-batch/app/src/androidTest/kotlin/dev/zayn/vlive/io/FileStorageTest.kt#L16)） | 插设备跑 `:app:connectedDebugAndroidTest` |
| `MediaStoreSync` 本体 | **零测试**（VM 侧只注入假扫描器） | 真机看相册 |
| 真实权限流：`PermissionGate.of()` / `requestIntent()` | 未测（[PermissionGateTest.kt:8-9](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/perm/PermissionGateTest.kt#L8)）；vivo 上跳不到系统页是已知风险 | 真机 + Toast 退化路径 |
| `MainActivity` / `BatchScreen` / `ui/*` 全部 | 零测试，依赖表里没有 Compose UI-test | 装机肉眼看 |
| `rename(2)` 原子性、跨卷 `copyTo` 退化、`ensureDir` 抛出路径 | 无覆盖 | 真机 / 仪器化测试补 |
| 进程被杀后的索引收敛 | 代码里如实写了"修不了"（[BatchViewModel.kt:113-120](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L113)） | 等 MediaStore 例行收敛 |
| 性能（"100 张约 15 秒"只是动机） | 无计时测试 | 真机计时 |
| 三条 gain map 声明串的实际覆盖 | 代码认三种拼法（连字符 / 冒号 / Adobe 旧命名空间，[TrailerModel.kt:46-50](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/TrailerModel.kt#L46)），但**只有冒号形态**被夹具真正走过（`urn:iso:std:iso:ts:21496:-1`，[Fixtures.kt:213](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/vlive/core/Fixtures.kt#L213)）；另两种没有各自的用例 | 各补一条 `TrailerModelTest` |
| 启动入场的**手感**（logo 淡出与卡片上浮接不接得上、系统关掉动画时入场是否归零）与目录弹窗的返回手势 / 换层滑动 | 2026-10-21 装机看过一轮：桌面图标（含圆形遮罩、深色下蓝底白标记）、冷启动 splash 的**存在与配色场**、交错入场落位都正常；**逐帧手感没有量化**，`ANIMATOR_DURATION_SCALE` 归零行为仍未验 | 再装机看；要收口就补 Compose UI 测试 |
| 深色下的**其它**渲染面：`Dialog`（目录弹窗）表面、进度条、开关轨道、报告行的语义色 | 主界面与冷启动色场已实测（黑底 + `#1C1C1E` 卡片 + 白字，状态栏图标翻亮）；**2026-10-22 深色下的弹窗与报告行都看过了**（`D:/tmp/em41_dark_sheet_scrolled.png`、`em40_dark_en_scrolled.png`、`em51_dark_zh_scrolled.png`），弹窗与列表之间没有多出来的接缝，进度条正常。**开关那一格当时记成"正常"，是错的**：那三张图里圆点从来没动过 —— `IosSwitch` 的 `.offset(...)` 当时排在 `.background(...)` 之后，只挪到了这颗圆点并不存在的子内容，轨道换了色就把"整颗开关是好的"冒名顶替了。深色下的开关**至今没有过一次有效观察**。另有一条已开缺陷：弹窗顶栏的副标题被 bar 下边缘裁掉（[§5.19](decisions.md#519-玻璃只给三条浮动-bar卡片与按钮不各自糊一层2026-10-21-增量) 末尾与技术债表） | 深色下把开关点到开再截一次（量圆点左沿是否从 x=867 走到 x=920）；副标题那条修完一起补 |
| **modifier 链的次序、`LayoutModifier` 摆在哪一格** | 纯 JVM 单测在结构上看不见这类错（上一行就是代价：一个"颜色变了形态没变"的开关被记成已验证，活完整整一轮装机）。这两天查出来的两个 UI bug 同属一类 —— `Modifier.shadow` 在层内预留一圈把画面和子节点一起裁进去（[§5.21](decisions.md#521-玻璃-bar-上的-modifiershadow-全部删掉它在层内预留一圈把玻璃切成白色方块2026-10-22-装机裁定)），`Modifier.offset` 排在绘制类之后等于没写 | 要么补 Compose UI 测试（依赖表里没有这条），要么每个新控件装机后**逐项量像素**：位置、尺寸、边界各量一个数，不许只看颜色对不对 |
| **系统语言切成英文之后**整屏是否全英文（含目录别名、错误提示、Toast） | 2026-10-22 在 API 36 模拟器上看过：主界面、目录弹窗（root 读作 `Internal storage`）、报告行与筛选档全英文，没有中文残留、没有空字符串；`Movies` 这类真实目录名在弹窗里保持原样。**注意这块镜像不认 `settings put system system_locales`**，中文那轮是用 `cmd locale set-app-locales` 按应用切的。**没验到**：错误提示与 Toast 的英文（这一批夹具跑不出失败项）、以及三按钮导航形态下的 shipped 截图 | 要收口就补一条失败项（把一个配对 mp4 换成非 JPEG）+ 一次三按钮形态截图 |
| `RollingLogTree` 真的在写文件、轮转与清理真的发生 | 只测到决策函数 `planLogCleanup`；树本身要 `Context` + 真实文件系统（`filesDir/logs`）与一条后台线程 | 装机跑一批，然后 `adb shell run-as dev.zayn.vlive ls -l /data/data/dev.zayn.vlive/files/logs`：文件数 ≤ 4、合计 ≤ 2 MB、里面能 grep 到 `batch start` / `batch end` |
| `PreferencesBatchSettings` 真的跨进程活下来 | DataStore 需要 Android `Context`，本地只测到接口那一侧（内存假实现） | 装机：改一次目标目录 → `am force-stop` → 重开 → 那一行还是改过的值 |
| 非 vivo 厂商（OPPO/小米/Pixel）识别 | 只有 OPPO 作为**反例**被分析过 | 各厂商真机各跑一批 |

## 真机验收清单（改了字节层就必须重跑）

前置：一台已开"所有文件访问"的 Android 12+ 设备（历史判据来自 vivo V2419A / X200 Pro mini）。
玻璃那两条判据（下面第 11、12 条）**必须**在真机上量，模拟器只能用来截图：`vlive_api36`（API 36 /
`google_apis` / x86_64，WHPX 加速，boot 约 45 秒）承担过 2026-10-22 那一轮的截图矩阵、手势与命中测试，
但它一次滚动报 `Janky frames: 6 (3.87%)` 与 `Janky frames (legacy): 120 (77.42%)` —— 两个字段同时荒谬，
软件合成的帧节拍不能当预算证据。设备路径要 `export MSYS_NO_PATHCONV=1`，`adb push` 的本地路径写 Windows 风格。

```bash
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
# vivo OriginOS 会在**机身上**弹确认；不点就报 INSTALL_FAILED_ABORTED: User rejected permissions，
# 屏幕熄灭/锁定时同样会被拒。这一步是装机动作，不是构建故障，别靠重试解决。
adb shell am start -n dev.zayn.vlive/dev.zayn.vlive.MainActivity
adb shell "cmd uimode night yes"   # 深色：必须 force-stop 后重新冷启动才看得到 splash 那一帧
adb shell "cmd uimode night no"
adb shell "ls -l /storage/emulated/0/Pictures/LivePhoto"          # 产物与 .tmp 是否清干净
adb shell content query --uri content://media/external/images/media --projection _display_name \
  | grep _MP                                                      # MediaStore 是否收到条目
```

逐项目视检查：

1. 相册里 `x_MP.jpg` 会**自动播放**（不只是显示一张静图）—— 这是唯一的成功判据。
2. 长按/详情里视频时长正常；HDR 副图没丢（对比源图亮度）。
3. 勾了"合成后删除原文件"时：相册里**不该再留**指向已删 `x.jpg` / `IMG_x.mp4` 的幽灵缩略图。
4. UI 报告行与磁盘一致：`成功/跳过/失败` 计数、橙色"原文件没删掉"警告。
5. `.tmp/` 目录里不留 `.part`（三条出路都会清理）。
6. 拿一份"上一轮产物"再跑一次：应报 `已是动态照片（ALREADY_MOTION_PHOTO）` 并跳过，而不是二次追加。
7. 拿一份录屏 mp4（非 `IMG_` 前缀）：应当**完全不出现**在报告里。
8. 对拍字节：`adb pull` 产物后 `xxd -c 16` 看 `imageEnd` 之后是否只有 XMP 那一段被换掉，末尾视频字节数 == `MicroVideoOffset`。
9. 深浅色各冷启动一次：深色下 splash 的**大背景必须是黑**，进界面后卡片与分组背景分层清楚、状态栏图标跟着翻亮。logo 的白标记在深色下**会被 OriginOS 画成黑色 —— 这是已实测并接受的行为，不是回归项**（[decisions.md §5.16](decisions.md#516-深色-splash-的白标记被-rom-画成黑2026-10-21-装机裁定)：红 / 绿探针原样通过，白与浅灰全部变黑，`forceDarkAllowed=false` 无效）。
10. 系统语言切英文走一遍同样的路：主界面、目录弹窗（root 别名读作 `Internal storage`）、一条失败项的报告行（`Skipped …: not a JPEG (NOT_JPEG)`）都不该掉回中文。
11. **玻璃上的文字可读性**（spec §3.4 第 1/2 条）：两种外观 × 两种语言各截三张 —— 静止、大标题折叠完成（顶栏 alpha=1）、一张卡片正划到操作条底下。判据是眼睛：`vlive` 紧凑标题、`开始合成` 上的白字、`取消` 上的 accent 字都要读得清，卡片仍是实心白 / `#1C1C1E`，顶栏与弹窗顶栏底下要看得见**被抹平**的内容而不是清晰的字（清晰的字从玻璃底下透出来 = 那一层没糊上，或者它就是那条不糊的 `iosGlassTray`）。**这条不许用截图工具代替 Read 打开图片**：2026-10-22 那一轮就是这样发现弹窗副标题被 bar 裁掉、以及底部条底下 `Succeeded IMG_20260008 → …` 与 `Start merging` 打架的。
12. **帧预算**（spec §3.4 第 4 条）：`am force-stop` → `dumpsys gfxinfo dev.zayn.vlive reset` → 冷启动 → 等 3 秒 → 12 次固定 `input swipe` → `dumpsys gfxinfo dev.zayn.vlive` 整份存盘，**每个构建三遍取中位数**（单次读数不算数），基线由 `739b388`（最后一个没有任何模糊消费方的构建）单独构建一份 APK 来取。判据：改造后 ≤ 基线 +3 个百分点 **且** ≤ 10%。**读哪一行是设备事实**：V2419A（API 36）上 spec 点名的 `Janky frames:` 九个样本全是 `0 (0.00%)`、`Number Missed Vsync` 全是 0，只有 `Janky frames (legacy)` 与帧时间直方图会动 —— 所以判据落在后两者上（2026-10-22 实测：基线 10.13% → 三条 bar 全糊 13.62% → 回退后 13.20%，超线，回退已启用）。

如果 ① 失败：先看 [容器规则 §2 的对照表](motion-photo-container.md#为什么尾巴必须留xmp-必须回原位)（尾巴被截了？XMP 插错位置？合并了源包？），再看 [vivo 末盒](motion-photo-container.md#5-vivo-末盒改写)（`livephoto` 值是不是数字开头且没被改写）。这四条里任何一条破了，vivo 相册就不认，而且**不会有任何报错**。
