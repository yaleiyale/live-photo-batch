# 测试与验证

- **回答的问题**：哪些行为已经被钉死、钉在哪个测试里；哪些**只能靠真机**，验收该跑什么命令、看什么现象。
- **范围**：as-built @ `ff9ad98` + 工作区增量（图标 / SplashScreen / 入场动画 / 单列目录浏览），2026-10-21。计数来自 `grep -c "@Test"`（全部是普通 `@Test`，没有 `@ParameterizedTest`/`@TestFactory`）。

## 总览：186 条测试，三个 sourceSet

| sourceSet | 框架 | 条数 | 跑在哪 | CI 是否执行 |
| --- | --- | --- | --- | --- |
| `core/src/test` | JUnit 5 Jupiter（BOM 6.1.3），`useJUnitPlatform()` | **145** | 本机 JVM，不需要设备 | ✅ `:core:test` |
| `app/src/test` | JUnit 4.13.2，**不引 Robolectric** | **33** | 本机 JVM（真协程） | ✅ `:app:testDebugUnitTest` |
| `app/src/androidTest` | JUnit 4 + `AndroidJUnit4` / `AndroidJUnitRunner` | **8** | 真机/模拟器 | ❌ **只编译**（`assembleDebugAndroidTest`） |

每个文件的方法数：

```
core:  BatchRunnerTest 23 · MotionPhotoComposerTest 21 · TrailerClassifierTest 20
       PairPlannerTest 13 · JpegXmpPatcherTest 13 · JpegStructureTest 12 · XmpPacketTest 12
       InMemoryStorageTest 10 · VivoLivePhotoTailTest 9 · TrailerModelTest 7
       SourcesTest 3 · MotionDetectorTest 2      （Fixtures / InMemoryStorage 是夹具，0 条）
app:   BatchViewModelTest 11 · DirBrowserTest 16 · TouchedPathsForTest 4 · PermissionGateTest 2
androidTest: FileStorageTest 8
```

## 命令

```bash
./gradlew :core:test                     # 145 条，最快反馈，纯 JVM
./gradlew :app:testDebugUnitTest         # 33 条 ViewModel / DirBrowser / PermissionGate
./gradlew :app:assembleDebug             # 产出 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebugAndroidTest   # CI 用它保证仪器化测试还能编译
./gradlew :app:connectedDebugAndroidTest  # 8 条契约测试，必须插着设备跑
```

CI 只有两个 job（[.github/workflows/build.yml:12-55](file:///D:/projects/piece/live-photo-batch/.github/workflows/build.yml#L12)）：`core-tests` 跑 `:core:test`，`apk` 跑 `:app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleRelease`，并上传两个产物 —— `live-photo-batch-debug` 与 `live-photo-batch-release`。release 用的是 `signingConfigs.debug`，所以那个包**能侧载、不能上商店**。

## 四个测试接缝（为什么不需要设备）

| 接缝 | 做法 | 位置 |
| --- | --- | --- |
| 文件动作 | `InMemoryStorage` 实现 `BatchStorage`：`LinkedHashMap<String, ByteArray>`，目录由 key 前缀隐含；`openWrite` **只在 `close()` 返回后**发布条目；额外给 `tmpFiles` / `bytesOf` 两个测试专用读法 | [core/src/test/…/InMemoryStorage.kt:14-72](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/InMemoryStorage.kt#L14) |
| 字节来源 | `ByteArraySource` 让合成整条线直接吃内存数组（注意它住在 **main** sourceSet，见 [decisions.md 技术债](decisions.md#7-已知技术债下次动这片之前先读)） | [Sources.kt:20-36](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/Sources.kt#L20) |
| 权限 | `BatchViewModel(isGranted = { … })`：假 lambda 每次现读，不缓存 | [BatchViewModel.kt:107-120](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/BatchViewModel.kt#L107)、[BatchViewModelTest.kt:54](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/BatchViewModelTest.kt#L54) |
| 媒体扫描 | `BatchViewModel(scanner = { … })`：假扫描器记录收到的路径 | 同上 |
| 存储根 | `FileStorage(root)` / `DirBrowser(root)` / `BatchViewModel(root = …)` 允许注入临时目录，与生产同一个 root 契约规则 | [FileStorage.kt:78-82](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/io/FileStorage.kt#L78) |

`app/src/test` 能驱动真协程是因为 lifecycle 2.11 在没有 Android 主线程时把 `viewModelScope` 退化成 `SupervisorJob + Dispatchers.Default`（实测，写在 [BatchViewModel.kt:84-87](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/BatchViewModel.kt#L84)）。

## 夹具都是合成的

[Fixtures.kt](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/Fixtures.kt) 里没有真实照片：没有 `src/test/resources`，没有 base64/hex 大块。工厂清单：

| 夹具 | 造的字节形状 | 位置 |
| --- | --- | --- |
| `segment` / `app1` / `sof0` / `sos` / `jpeg(tail=…)` | 最小合法 JPEG，可按需带 EOI 之后的尾巴 | [:11-40](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/Fixtures.kt#L11) |
| `mp4(size)` | `ftyp` 盒 + 每 16 字节塞一个 `FF D9` 诱饵（专门测"绝不搜 FFD9"） | [:42-60](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/Fixtures.kt#L42) |
| `box` / `ftypBox` / `uint32` / `zeroFill` | ISO-BMFF 盒与零填充 | [:69-88](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/Fixtures.kt#L69) |
| `vendorRecord` / `vendorTrailer` | 387 B `streamdataDEGS` 记录；399 B `vivo{json}` + `cameralbum!` 尾记录 | [:94-116](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/Fixtures.kt#L94) |
| `specPacket(videoSize)` | 那份**逐字节钉死**的 XMP 包（当前 1,057 字节） | [:164](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/Fixtures.kt#L164) |
| `gainMapXmp` / `gainMapImage` / `gainMapJpeg` | 带 ISO 21496-1 声明的副图 JPEG | [:174-232](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/Fixtures.kt#L174) |
| `vendorTrailerJpeg` / `zeroFilledJpeg` / `composedMotionPhotoJpeg` | 上一节那些尾巴的成品组合 | [:235-247](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/Fixtures.kt#L235) |
| `vivoTailBox` / `vivoMp4` + `REAL_LIVEPHOTO` | vivo 末盒（`livephoto` 值用真机编号字面量 `17924055463797e14dd500000000`） | [:257-279](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/Fixtures.kt#L257) |

**真机值被转述，真机字节没有**：X200 Pro mini 的 JSON 字段布局、5,976,771 字节的 `mdat` 都只以字面量出现在注释/夹具参数里。所以格式回归**不可复现**于真实语料 —— 这是 [decisions.md 技术债](decisions.md#7-已知技术债下次动这片之前先读) 里"加一份 golden 文件"那条的由来。

## 已经钉死的高价值契约

| 钉的是什么 | 测试 |
| --- | --- |
| `.part → close → move → 删原文件` 的**精确操作顺序** | [BatchRunnerTest.kt:62](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/BatchRunnerTest.kt#L62) `tmp write then move then delete is the exact operation order` |
| 删除只发生在提交之后；原地批次也要走 `.tmp` | [:54](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/BatchRunnerTest.kt#L54)、[:156](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/BatchRunnerTest.kt#L156)、[:417](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/BatchRunnerTest.kt#L417)（抛出的删除只加警告，不回改写 SUCCESS） |
| 取消只在项与项之间生效（含 `unpaired` 预循环；开局取消零效果） | [:121](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/BatchRunnerTest.kt#L121)、[:445](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/BatchRunnerTest.kt#L445)、[:461](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/BatchRunnerTest.kt#L461) |
| 失败项不留 `.part`；清理自己抛出也不许穿出 `run` | [:189](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/BatchRunnerTest.kt#L189)、[:360](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/BatchRunnerTest.kt#L360)、[:389](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/BatchRunnerTest.kt#L389) |
| 只有 `ALREADY_MOTION_PHOTO` 算跳过 | [:208](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/BatchRunnerTest.kt#L208) |
| `move` 返回 false → `IO_ERROR` 且原文件不动 | [:292](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/BatchRunnerTest.kt#L292) |
| **64 位扩展盒长**（`size==1` → `largesize`）：vivo/OPPO 的 `mdat` 形态；截断的 `largesize` 与盖住窗口的 64 位填充盒都**不许猜** | [TrailerClassifierTest.kt:58](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/TrailerClassifierTest.kt#L58)、[:66](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/TrailerClassifierTest.kt#L66)、[:72](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/TrailerClassifierTest.kt#L72) |
| XMP 信号优先于尾巴探针；裸词不许误判成动态照片 | [:123](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/TrailerClassifierTest.kt#L123)、[:153](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/TrailerClassifierTest.kt#L153)、[MotionDetectorTest.kt:20-22](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/MotionDetectorTest.kt#L20) |
| `GainMap` / `Padding` **互斥**，违反时必须是 `IllegalStateException`（**不是** `IllegalArgumentException`） | [XmpPacketTest.kt:151-162](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/XmpPacketTest.kt#L151) |
| XMP 包逐字节 = 1,057 那份夹具 | [XmpPacketTest.kt:24-26](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/XmpPacketTest.kt#L24) |
| vivo 末盒：加前缀 + 两处长度同步；伪 UUID 与尾记录逐字节不变；六种"看不懂"一律返回 null | [VivoLivePhotoTailTest.kt:72](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/VivoLivePhotoTailTest.kt#L72)、[:87](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/VivoLivePhotoTailTest.kt#L87)、[:100](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/VivoLivePhotoTailTest.kt#L100)、[:111-145](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/VivoLivePhotoTailTest.kt#L111) |
| 尾巴定性：真副图整条声明 `GainMap`；MPF 副图 / 厂商 JSON / 零填充 / **段长越界的畸形副图**一律降级 `Padding`；空尾巴什么都不声明 | [TrailerModelTest.kt:28](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/TrailerModelTest.kt#L28)、[:41](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/TrailerModelTest.kt#L41)、[:51](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/TrailerModelTest.kt#L51)、[:60](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/TrailerModelTest.kt#L60)、[:76](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/TrailerModelTest.kt#L76)、[:91](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/TrailerModelTest.kt#L91)（尾巴从 `imageEnd` 流式走、绝不碰主图） |
| 配对规则：`IMG_` 前缀、`_MP` 不作候选、自然序、同 stem 取第一个、`.nomedia` 之类的边界 | [PairPlannerTest.kt](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/PairPlannerTest.kt)（13 条） |
| 新 XMP 写回**旧段原位**、旧段"读掉不写"、尾巴整条搬走、`patch` 返回值语义 | [JpegXmpPatcherTest.kt](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/JpegXmpPatcherTest.kt)（13 条，含两条 `@Timeout(SEPARATE_THREAD)`） |
| `touchedPathsFor`：每个 SUCCESS 产物 + 每个已删原文件；取消尾巴天然覆盖 | [TouchedPathsForTest.kt](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/TouchedPathsForTest.kt)（4 条） |
| 收尾扫描在 `NonCancellable` 里送达；扫描未确认变成**可见的一行提示**而不是沉默；扫描器抛出不会卡死批次 | [BatchViewModelTest.kt:130](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/BatchViewModelTest.kt#L130)、[:291](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/BatchViewModelTest.kt#L291)、[:307](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/BatchViewModelTest.kt#L307) |
| root 选择规则与 `FileStorage` 共享同一个谓词；越界快照静默拒绝 | [:273](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/BatchViewModelTest.kt#L273)、[:320](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/BatchViewModelTest.kt#L320) |
| 目录浏览**只出一列**：预设只替换显示名、当前目录不列自己、没被预设提到的子目录仍然可达、**嵌套预设不得补成快捷方式**（`相机 = DCIM/Camera` 那条机制已撤销）、别的根下的预设一律不掺、顺序稳定 | [DirBrowserTest.kt:105](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/browse/DirBrowserTest.kt#L105)、[:126](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/browse/DirBrowserTest.kt#L126)、[:140](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/browse/DirBrowserTest.kt#L140)、[:152](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/browse/DirBrowserTest.kt#L152)、[:168](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/browse/DirBrowserTest.kt#L168)、[:184](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/browse/DirBrowserTest.kt#L184) |
| 副标题 `displayPath`：根显示 `内部存储`、下级保持 `内部存储/DCIM/Camera`、**root 之外不改名**（那种路径选不了，改名就是骗人） | [DirBrowserTest.kt:201](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/browse/DirBrowserTest.kt#L201)、[:211](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/browse/DirBrowserTest.kt#L211)、[:219](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/browse/DirBrowserTest.kt#L219) |
| 端口实现分叉点：`exists`/`delete` 对目录必须 false、`list` 排序与缺目录给空、root 不二次拼接、close 后字节完整 | [FileStorageTest.kt:94](file:///D:/projects/piece/live-photo-batch/app/src/androidTest/kotlin/dev/zayn/livephotobatch/app/io/FileStorageTest.kt#L94)、[:58](file:///D:/projects/piece/live-photo-batch/app/src/androidTest/kotlin/dev/zayn/livephotobatch/app/io/FileStorageTest.kt#L58)、[:73](file:///D:/projects/piece/live-photo-batch/app/src/androidTest/kotlin/dev/zayn/livephotobatch/app/io/FileStorageTest.kt#L73)、[:162](file:///D:/projects/piece/live-photo-batch/app/src/androidTest/kotlin/dev/zayn/livephotobatch/app/io/FileStorageTest.kt#L162)、[:144](file:///D:/projects/piece/live-photo-batch/app/src/androidTest/kotlin/dev/zayn/livephotobatch/app/io/FileStorageTest.kt#L144) |
| 内存实现镜像同样几条契约（两侧对齐才有意义） | [InMemoryStorageTest.kt:48-83](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/InMemoryStorageTest.kt#L48) |

## 自动化测不到的（别假装测到了）

| 缺口 | 事实 | 兜底 |
| --- | --- | --- |
| **相册把产物认成动态照片** | 没有任何 API 可问；判据来自 vivo V2419A 真机实验 | 装机验收（下面步骤） |
| 现 XMP 包（1,057 字节那份）在真机上的接受度 | 注释明确要求重新装机验收（[XmpPacketTest.kt:19-20](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/XmpPacketTest.kt#L19)） | 同上 |
| `FileStorageTest` 8 条 | CI **只编译不执行**（[FileStorageTest.kt:16](file:///D:/projects/piece/live-photo-batch/app/src/androidTest/kotlin/dev/zayn/livephotobatch/app/io/FileStorageTest.kt#L16)） | 插设备跑 `:app:connectedDebugAndroidTest` |
| `MediaStoreSync` 本体 | **零测试**（VM 侧只注入假扫描器） | 真机看相册 |
| 真实权限流：`PermissionGate.of()` / `requestIntent()` | 未测（[PermissionGateTest.kt:8-9](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/livephotobatch/app/perm/PermissionGateTest.kt#L8)）；vivo 上跳不到系统页是已知风险 | 真机 + Toast 退化路径 |
| `MainActivity` / `BatchScreen` / `ui/*` 全部 | 零测试，依赖表里没有 Compose UI-test | 装机肉眼看 |
| `rename(2)` 原子性、跨卷 `copyTo` 退化、`ensureDir` 抛出路径 | 无覆盖 | 真机 / 仪器化测试补 |
| 进程被杀后的索引收敛 | 代码里如实写了"修不了"（[BatchViewModel.kt:104-105](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/livephotobatch/app/BatchViewModel.kt#L104)） | 等 MediaStore 例行收敛 |
| 性能（"100 张约 15 秒"只是动机） | 无计时测试 | 真机计时 |
| 三条 gain map 声明串的实际覆盖 | 代码认三种拼法（连字符 / 冒号 / Adobe 旧命名空间，[TrailerModel.kt:46-50](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerModel.kt#L46)），但**只有冒号形态**被夹具真正走过（`urn:iso:std:iso:ts:21496:-1`，[Fixtures.kt:213](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/Fixtures.kt#L213)）；另两种没有各自的用例 | 各补一条 `TrailerModelTest` |
| 图标观感、启动入场时序、以及目录弹窗的**返回手势与换层滑动** | 验到的只有「XML 资源打进 APK（`aapt2 dump badging` 实测）+ 编译 + 178 条 JVM 测试绿 + `:app:lintDebug`」。圆形遮罩下会不会啃到图形、主题图标重染后齿孔还在不对对、logo 淡出与卡片上浮接不接得上、返回键是否真的先回上一级再到 root 才关窗、系统关掉动画时入场是否直接落位 —— 全部**未确认**，这批 UI 代码一条自动化测试都没有 | 装机看一次：桌面（含遮罩与主题图标切换）+ 冷启动 + 逐级进出目录 |
| 非 vivo 厂商（OPPO/小米/Pixel）识别 | 只有 OPPO 作为**反例**被分析过 | 各厂商真机各跑一批 |

## 真机验收清单（改了字节层就必须重跑）

前置：一台已开"所有文件访问"的 Android 11+ 设备（历史判据来自 vivo V2419A / X200 Pro mini）。

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.zayn.livephotobatch/dev.zayn.livephotobatch.app.MainActivity
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

如果 ① 失败：先看 [容器规则 §2 的对照表](motion-photo-container.md#为什么尾巴必须留xmp-必须回原位)（尾巴被截了？XMP 插错位置？合并了源包？），再看 [vivo 末盒](motion-photo-container.md#5-vivo-末盒改写)（`livephoto` 值是不是数字开头且没被改写）。这四条里任何一条破了，vivo 相册就不认，而且**不会有任何报错**。
