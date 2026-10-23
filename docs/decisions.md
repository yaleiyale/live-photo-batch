# 设计裁定与刻意的偏离

- **回答的问题**：这里为什么是这样？哪些地方**故意**不像一个普通 Android 项目，改动前必须知道的理由。
- **读者**：要改这个仓库的人 / agent。看到"这里写得好怪"就先来这一页。
- **范围**：as-built @ `ff9ad98` + 工作区增量（图标 / SplashScreen / 入场动画 / 单列目录浏览），2026-10-21。每条都能追溯到代码；被删掉的预构建设计稿不构成依据。
- **格式**：决定 → 为什么 → 代价。判据来自真机实验的地方都点名是哪一次实验。

## 1. 架构分层

### 1.1 `:core` 是纯 JVM 库，文件动作只有一个端口

**决定**：字节层与批次层全在 `:core`，它只依赖 JDK（`kotlin.jvm` 插件 + JUnit），不知道 `java.io.File` 也不知道 Android。全部文件动作走 `BatchStorage`（7 个方法）+ `ChannelSource`（可反复 `open()`）。

**为什么**：这是一次性拿到可测性的唯一办法 —— 145 条测试不需要设备，`PairPlanner`/`BatchRunner`/合成整条线都能在 JVM 上驱动。路径是字符串、分隔符固定 `/`，`ChannelSource` 表达"同一个文件要读两遍"，所以 `:core` 不需要真实路径抽象。

**代价**：端口契约成了**双语义风险面**——`exists`、`delete`、`openWrite` 的可见性、`list` 的排序在内存实现与 `FileStorage` 上各有一套天然行为，必须靠契约文字对齐（见 [system-model.md 的端口契约表](architecture/system-model.md#端口契约batchstorage-的-7-条方法级约定)）。任何一侧自行放宽都会让批次静默产出半截文件而**没有任何测试变红**。

证据：[BatchStorage.kt:5-12](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/BatchStorage.kt#L5)、[Sources.kt:14-18](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/Sources.kt#L14)、[core/build.gradle.kts:1-9](file:///D:/projects/piece/live-photo-batch/core/build.gradle.kts#L1)。

### 1.2 纯字节搬运，不解码不转码

**决定**：源 JPEG 整文件进产物，视频原始字节追加；只有 XMP 那一段被替换。

**为什么**：真机单变量对照里，**重编码过的主图不被 vivo 相册认**。另外这条路线能做到常数内存、不需要解码器、不损失原始画质与 HDR 副图。

**代价**：产物里带着厂商副图与记录，格式判断（尾巴定性）变复杂；我们放弃了"清理一遍元数据"的机会。

证据：[MotionPhotoComposer.kt:24-34](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/MotionPhotoComposer.kt#L24)、[JpegXmpPatcher.kt:20-24](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/JpegXmpPatcher.kt#L20)。

## 2. 配对与命名

### 2.1 驱动集是视频，不是图像

**决定**：只有 `IMG_*.mp4` 会去找同名 JPEG；普通照片**连候选都不是**，不产"缺视频"记录。`Missing` 枚举只剩 `IMAGE`。

**为什么**：以 JPEG 驱动时，真机第一批 54 项里 **28 项**是"这张图没有配对视频"的噪声，既刷长报告也说明不了任何问题。

**代价**：`x.jpg` + `y.mp4`（名字不同）这种输入永远静默无输出，用户看不出来。目前接受，因为相机产物的命名本就是同名。

证据：[PairPlanner.kt:22-29](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/PairPlanner.kt#L22)、[:15-18](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/PairPlanner.kt#L15)。

### 2.2 `IMG_` 前缀硬编码

**决定**：视频候选必须以 `IMG_` 开头（大小写不敏感）。

**为什么**：相册目录里还躺着录屏、下载的短视频、微信保存的 mp4；vivo/Google/小米相机动态照片一律 `IMG_<时间戳>.mp4`。

**代价**：非相机来源（改名过的、第三方 App 存的）不会被处理。`VIDEO_PREFIX` 是常量而不是参数 —— 想放宽就改 [PairPlanner.kt:69](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/PairPlanner.kt#L69)。

### 2.3 排序用码元自然序，且**不许改成大小写无关**

**决定**：`jobs` 与 `unpaired` 按 `String.compareTo` 升序，不走 locale collator。后果：`'B'(0x42) < '_'(0x5F) < 'a'(0x61)`，且 `IMG_10` 排在 `IMG_2` 之前。

**为什么**：与设备语言无关 + UI 的稳定顺序依赖它。同 stem 双候选"取第一个"也要求两次列出同一目录得到同一个列表。

**代价**：相册顺序不等同于拍摄顺序，数字编号看起来"乱"。这是刻意的。

证据：[PairPlanner.kt:51-56](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/PairPlanner.kt#L51)、[BatchStorage.kt:17-22](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/BatchStorage.kt#L17)。

## 3. 字节与元数据

### 3.1 XMP 清空重写，不合并源包

**决定**：产物里唯一那份 XMP 从清空白写生成，源包的 `hdrgm:*`、`dc:*`、厂商命名空间、源片自己的容器项**一个不掺**。

**为什么**：真机单变量对照判的就是这个 —— 同一个包、同一个视频，清空白写的那份被认，从源包合并的那份不认。**读源包正是上一版掺脏东西的入口**。

**代价**：不做时间戳沿用（两个 `*PresentationTimestampUs` 固定 `-1`）；第三方样本写 `0`，但 `0` 的语义是"从第 0 微秒播"，与"未提供"不是一回事，没有真机依据前不改。

证据：[XmpPacket.kt:27-32](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/XmpPacket.kt#L27)、[:65-69](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/XmpPacket.kt#L65)。

### 3.2 字段集合按规范正文写，厂商样本只当反例

**决定**：`Primary Item:Length="0"`、`Padding` 只写在第一项、视频项必须正好结束在 EOF —— 判据是 Motion Photo 1.0 **规范原文**。OPPO 那份文件在这里只作为反例存在（它声明了不存在的副图、让视频项没结束在 EOF、三项都写 `Padding`）。

**为什么**：`2026-10-19e` 第一次改错了方向（为了"对齐厂商样本"动序列化形状），已回退。规范那一页只有属性表与散文，**没有任何示例 XMP 包**，所以 `<rdf:li>` 与 `parseType="Resource"`、属性顺序都不产生规范性差异。

**代价**：我们的形状和任何一家的都不完全一样；如果将来某个相册按字符串匹配读，这条裁定要重新检验。

证据：[XmpPacket.kt:33-53](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/XmpPacket.kt#L33)。

### 3.3 `GainMap.Length` 用整条尾巴，不用副图诚实内长

**决定**：声明成 GainMap 时，`Length` = EOI 之后的**整条**尾巴（含副图之后的厂商记录）。

**为什么**：规范只给第一项 `Padding`，副图与视频之间没有别的合法字段。诚实内长会留下一段没人声明的字节，违反"紧密封装"MUST —— 二选一取合规的那个。

**代价**：`Length` 数值不等于"那张副图 JPEG 的大小"，读代码的人会觉得奇怪；`Description.innerJpegBytes` 保留诚实内长只供诊断与测试留档，目录里的 `Length` **不用它**。

证据：[TrailerModel.kt:12-15](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/TrailerModel.kt#L12)、[XmpPacket.kt:110-113](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/XmpPacket.kt#L110)。

### 3.4 畸形尾巴降级成 Padding，而不是报错

**决定**：`TrailerModel.describe` 对任何输入都不抛：不是 JPEG、非副图的第二张 JPEG、厂商 JSON、零填充、**遍历不收敛的畸形副图** → 整条声明成主图 `Padding`。

**为什么**：把尾巴声明成 Padding 永远合法，而**把一张坏图谎称 GainMap 才是骗目录**。

**代价**：坏输入不再被拒绝，产出的文件里那一段字节被声明成"主图后面的填充"。真 I/O 故障仍由后续搬运阶段报 `IO_ERROR`，这里不抢那个分类。

### 3.5 只改读得懂的字节（vivo 末盒）

**决定**：`VivoLivePhotoTail.plan` 只在能完整验证末盒形状与自洽性时改写，否则返回 `null`，产物与"原样追加"逐字节相同。

**为什么**：真机二分（2026-10-20）证明 `livephoto` 值必须以 `motionphoto` 开头这一台机器才认，而相机自己写的是数字开头的 id —— 原样追加必然不认。但**不伪造、不强改**：非 vivo 的视频里没有这个结构，改它就是猜。

**代价**：其他厂商机型可能整批不被认，且不会报错、不会有任何提示。这是刻意的保守。

证据：[VivoLivePhotoTail.kt:8-24](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/VivoLivePhotoTail.kt#L8)。

### 3.6 `check` 还是 `require`：不变量与输入形状必须分开

**决定**：`gainMapBytes`/`paddingBytes` 互斥用 `check`（`IllegalStateException`）；APP1 超长用 `require`（`IllegalArgumentException`）。合成器**只**把 `IllegalArgumentException` 折成 `EXTENDED_XMP_UNSUPPORTED`。

**为什么**：前者是**上游不变量被违反**（定性出错），后者是真·输入上限。两者混在一个类型里，`catch` 会把"我们自己算错了"报成"这张图不支持"，把调查方向彻底指错（`ddc0af3` 修的就是这条）。

## 4. 批次语义

### 4.1 提交点是 `rename`，删除失败只是警告

**决定**：`rename` 成功的一刻本项**当场提交**，`SUCCESS` 与字节数在 `commit()` 第一行定死；之后删原文件失败只进 `deleteFailures`，没有任何路径能回改写状态。

**为什么**：产物已经在最终名上了 —— 一个源文件没删掉是警告，不是失败。把它算失败会让用户重跑整批，而重跑又会撞上 `ALREADY_MOTION_PHOTO`。

**代价**：报告里"成功但带警告"需要 UI 单独显示（橙色状态点 + 括号里的文件名列表，[BatchScreen.kt:428-446](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt#L428)）。

### 4.2 `MALFORMED_JPEG` 刻意不算跳过

**决定**：只有 `ALREADY_MOTION_PHOTO`（和计划层的 `NO_PAIR`）算 `SKIPPED`，其余拒绝全算 `FAILED`。

**为什么**：`JpegScanner` 会把 `IOException` 折叠成 Malformed。若把 Malformed 算跳过，**真实磁盘故障会被埋进"坏文件"里**。

证据：[BatchRunner.kt:184-187](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/BatchRunner.kt#L184)。

### 4.3 取消只查在项与项之间

**决定**：`CancelToken` 是标志，不靠协程取消掐断循环；每一项之前查一次，unpaired 行也算一项。

**为什么**：正在写的项必须走完 rename 或删除，**绝不留半截 `.part`**。同时一个开局就取消的批次不该把整屏 `NO_PAIR` 推给 UI 之后还报告 `cancelled = false`。

**代价**：取消响应粒度是一项的时间（大图可能几秒）。

### 4.4 全局前置失败与单项失败分两个通道

**决定**：源不可读 / 目标建不起来 / 批次意外中断 → `UiState.globalError` 一行，**不进报告列表**；单项问题 → 报告列表一行。

**为什么**：spec §9 的意图是"输出目录建不起来不是某一张图失败"。把它塞进报告会让用户以为只是几张图出问题。`FileStorage.ensureDir` 因此**刻意抛 `IOException`** 并让 `BatchRunner.run` 放它逃出去。

**代价**：收尾扫描的提示也必须借 `globalError` 这个通道（`UiState` 形状被计划钉死，没有新字段可去）—— 这是一个如实记录的妥协，不是设计。

证据：[FileStorage.kt:54-58](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L54)、[BatchViewModel.kt:33-36](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L33)。

### 4.5 `openWrite` 的可见性不对称：写进契约，不去"修"它

**决定**：契约只承诺 **`close()` 成功返回后**字节完整可见，`close()` 之前不作任何保证。`FileStorage` 打开即 truncate，半途可见是文件系统事实。

**为什么**：真实文件系统做不到"关闭才可见"。可见性由调用方保证 —— `BatchRunner` 先写 `.part`、`close`、再 `move`，靠 `rename(2)` 完成发布。这一层再"顺手"加临时名/改名只会把这个不对称挪个地方，还让 `move` 的替换语义变含混。

**代价**：内存实现与真实实现行为不同（内存 close 才发布），测试无法暴露"半途可见"这类问题。

证据：[BatchStorage.kt:54-61](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/BatchStorage.kt#L54)、[FileStorage.kt:40-52](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L40)。

### 4.6 `move` 绝不先删目标

**决定**：直接 `renameTo`（→ `rename(2)`，同卷对已存在目标本就是原子替换）；只有 rename 失败才退化 `copyTo(overwrite=true)` + `delete`，且**源删不掉不把结果翻成 false**。

**为什么**：先删会开出"旧产物已经没了、新产物还没就位"的窗口，中途崩溃或被媒体扫描撞上就永久丢掉上一版产物。退化路径里 `copyTo` 成功而 `src.delete()` 失败若返回 false，`BatchRunner` 会报 FAILED 并再删一次 `.part` —— 于是"失败但产物存在"，比留一个没人认领的 `.part` 糟糕得多。

证据：[BatchStorage.kt:68-75](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/BatchStorage.kt#L68)、[FileStorage.kt:120-142](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L120)。

### 4.7 顺序单线程，不并行合成

**决定**：一个协程、一条 `for`，一次一项。

**为什么**：瓶颈是磁盘顺序读写与 rename，并行只会让 `.part` 与删除交错、让取消语义变复杂；逐项报告与 `done/total` 进度天然是顺序模型。

**代价**：大目录慢。真要提速，先测再改（目前没有任何计时测试）。

## 5. `:app` 侧

### 5.1 三个共享 root 字符串的类必须一起改

**决定**：`BatchViewModel.DEFAULT_ROOT = "/storage/emulated/0"`（**不带尾斜杠、绝不空**）同时喂 `FileStorage(root)`、`DirBrowser(root)`，且 `isSelectable()` 照抄 `FileStorage.resolve()` 的判定。

**为什么**：root 带尾斜杠或为空时，`"/storage/emulated/0"` 本身会被当相对路径二次拼接成 `/storage/emulated/0/storage/emulated/0` —— `list(root)` 静默返回空（用户以为"这个目录没有文件"），`ensureDir(root)` 在真实存储里种一棵垃圾树。

**代价**：一个跨三个类的隐式约定，只能靠注释与测试守。

证据：[FileStorage.kt:60-66](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L60)、[BatchViewModel.kt:74-82](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L74)、[FileStorageTest.kt:162](file:///D:/projects/piece/live-photo-batch/app/src/androidTest/kotlin/dev/zayn/vlive/io/FileStorageTest.kt#L162)。

### 5.2 "能浏览"比"能选"宽，且批次用同一个谓词再守一遍

**决定**：`DirBrowser` 只看 `startsWith("/")`，root 之外的 `/sdcard`、`/storage/emulated` 照样能浏览；`BatchScreen` 靠 `isSelectable` 禁掉"选这里"，`runBatch` 开头**再查一次同一个谓词**。

**为什么**：那种串喂给 `FileStorage.resolve()` 会被重新拼到 root 之下，选了就等于把批次指向另一个目录。第二次检查把"UI 用的谓词"和"批次真正读写的路径"钉死成同一个，防止两条判定将来漂移分叉。

### 5.3 权限、目录读取都不许发生在组合期

**决定**：授权状态由 VM 构造、`onResume`、`LaunchedEffect` 三处推进；`subdirs()`/`File.parentFile` 全在 `produceState` + `Dispatchers.IO` 里。

**为什么**：`isExternalStorageManager()` 是一次 binder 调用，组合期读它会在批次逐项回报时拖慢主线程；`remember` **只是缓存，不是副作用护栏** —— 槽位恢复和被丢弃的重组都会重新执行它的 lambda。

### 5.4 报告列表刻意不用 `LazyColumn`，改用整表折叠 + 分页 + 筛选

**决定**：外层一条 `verticalScroll` 的 `Column`，报告用普通 `Column` + `forEach`；**整张列表默认收成一行** —— [`ReportCard`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt#L334) 折叠时只有表头那一行（标题 + 汇总计数 + chevron），展开之后才有四个筛选档位（全部 / 成功 / 跳过 / 失败）、**一页 20 行**（[`REPORT_PAGE_SIZE`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ReportPage.kt#L20)）与分页行。分页与筛选的规则在纯函数 [`reportPage`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ReportPage.kt#L47) 里。

**「收起」收的是整张列表，不是逐行**。需求原话是「报告列表支持展开收起，默认收起」，指的是列表本身收成一行（这一条在第一版实现之后由用户澄清；第一版做成了逐行展开，已改）。所以每一行没有各自的展开态，摘要行回到一行装完：状态 + `stem → 产物名` + 原因短语（后面仍跟着机器可读的枚举名，spec §9）+ 没删掉的原文件警告。

**为什么不能嵌 `LazyColumn`**：在里面再嵌一个纵向 `LazyColumn` 会带着无限高度去测量，Compose 直接抛 `"Vertically scrollable component was measured with an infinity maximum height constraints"`。所以「少画一点」这件事只能靠折叠与分页做 —— 也就正好是需求要的那一条（「避免界面数据占用过大内存」）。

**折叠与分页省的都是组合，不是数据**：`state.items` 每条只有几个字符串，几百条几十 KB，留着它不贵；贵的是把每一行都**组合 + 测量 + 排版**一遍。折叠时 `content` 连组合都不发生，展开后一次也只组合一页。`UiState` 一个字没改，VM 仍旧完整回报每一项 —— 把 items 也裁掉是另一件事，那样就没有「整批汇总计数」和「翻回去看第一项」了。

**哪些状态活过旋转**：折叠、筛选、页码都走 `rememberSaveable`（横过来都还在）。页码存的是原始值，越界由 `reportPage` 夹回，所以换筛选把页数变小也不会停在空白页上。

**代价**：`ReportCard` / `FilterChipRow` / `ReportPager` 三个控件仍然零自动化测试（与 §5.5 同一件事）；钉住的是纯函数那一层 —— 8 条用例（空结果也算一页、页码夹回合法区间、**连续页不重也不漏**、顺序就是回报顺序、`size = 0` 不死循环），见 [ReportPageTest](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/ReportPageTest.kt)。

### 5.5 自绘 iOS 分组列表，而不是 Material3 控件

**决定**：`ui/IosControls` + `IosStyle` 自己画 `SectionCard`/`DetailRow`/`SwitchRow`/`StatusRow`/`IosSheet`/`IosButton`/`ThinProgressBar`，图形用 Canvas 画不引入矢量资源；`MaterialTheme` 仍保留但只为了 `Text` 兜底色与 `Dialog` 表面色（[themes.xml](file:///D:/projects/piece/live-photo-batch/app/src/main/res/values/themes.xml) 也只对齐页面底色与系统栏）。

**为什么**：外观按 iOS「设置」的分组卡片重排（`fcb9d48`）。破坏性开关刻意用系统红而不是 iOS 默认绿。

**代价**：**没有 `@Preview`**（`compose-ui-tooling` 在依赖表里但没人用），且这批 UI 代码**一条自动化测试都没有**；设计令牌之外还有散落的魔法数字（开关 51/31/27 dp、导航栏 52 dp、`hairline 0.6 dp` 之外的 9/6/14/28 dp 内边距）。改这里只能靠装机肉眼看。

### 5.6 不开 `enableEdgeToEdge()`

**决定**：`MainActivity` 不调它，`BatchScreen` 自己在窗口层用 `WindowInsets.safeDrawing` 避让。

**为什么**：真机（targetSdk 36 + API 37）本来就强制 edge-to-edge，再叠一层只会把已审过的 insets 行为改掉。底色必须铺到状态栏底下，否则顶部一条系统给的接缝。

### 5.7 媒体扫描：一次性、2 秒上限、非主线程、绝不抛但回报

**决定**：整批结束后一次 `scanFile` 全部路径；`CountDownLatch.await(2s)`；只能从 `Dispatchers.IO` 调；返回确认信号并把失败打进 logcat。

**为什么**：只要"别留幽灵条目"这个最终效果，不关心逐条确认；`await` 阻塞的是**调用线程**，而 `onScanCompleted` 回调投递到主线程 —— 在主线程等必然等满 2 秒并顺手 ANR。"绝不抛"是因为扫描是尽力而为的收尾；"绝不静默"是因为这条不变量一旦被藏起来就没人知道相册为什么没更新。

证据：[MediaStoreSync.kt:11-36](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/MediaStoreSync.kt#L11)。

### 5.8 收尾扫描放在 `NonCancellable` 里

**决定**：`finally { withContext(NonCancellable + Dispatchers.IO) { 扫描; running = false } }`。

**为什么**：§7.6 的不变量不该指望"没人取消这个协程"。实测：外层 scope 在扫描挂起时被取消，NonCancellable 块仍完整执行。

**代价（如实记录的两个残余）**：① 批次协程**还没被线程启动**就碰上 scope 已取消 → 整个协程体连 finally 都不执行（此刻零文件效果，代价只是 `running` 卡在 true，而 ViewModel 已销毁）；② **进程被杀** → 协程、NonCancellable、扫描一起死，索引只能等 MediaStore 自己例行收敛。两者都"JVM 测不到/修不了"。

证据：[BatchViewModel.kt:99-106](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L99)、[:223-249](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L223)。

### 5.9 状态写入一律 `update { }`（CAS）

**决定**：不用 `value = value.copy(...)`。

**为什么**：状态只在批次协程（`Dispatchers.IO`）上被逐项推进，但主线程同时会写权限快照与删除开关；直接赋值会在竞争时**静默丢掉一方的更新**。`StateFlow` 本身线程安全，且相等的新值不重发。

### 5.10 目录浏览只出一列，根上只给别名、不给快捷方式

**决定**：`DirectoryPicker` 只渲染**一个** `SectionCard`、**一条**列表：`上一级`（父级还在 root 之内时才有）排在最前，后面就是这一层要显示的目录。在存储根那一级，[`mergedRootEntries`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/browse/DirBrowser.kt#L106) 只做一件事 —— 把**别名表**里的那些路径换成本地语言的说法（`<root>/Pictures → 图片`）；列表内容始终**只有 `subdirs(root)` 的真实子目录**，不补任何快捷方式。顺序一律按显示名 `lowercase()` 码元自然序，**不给常用项开小灶**。

**为什么不两段并列**：旧结构是"上面一坨预设入口、下面一坨当前层子目录"，同一件事（"这个目录下能进哪儿"）被画成两个来源，用户要先判断哪一段才是当前层。

**为什么把快捷方式撤掉**：曾有一条 `相机 = DCIM/Camera` 被提到根级别，画出来和真实目录一模一样，而文件管理里根本没有这么个根级条目 —— 看着就像凭空多出来的目录。撤掉之后到相机目录是两跳（`DCIM → Camera`），换来的是"列表里每一条都真的在那一层"。这条约束反过来也解释了 `Documents`、`Music` 为什么必须保留：它们是真实子目录，不能因为"不在别名表里"就够不着。

**代价**：
- 排序按显示名，别名参与排序 → 中文别名与 ASCII 名混排时按码元序（`DCIM · Documents · Music · 下载 · 图片 · 视频`），常用项不再固定在顶部。英文那一份别名与磁盘真实名相同（见 [§5.14](#514-文案全部进资源默认语言是英文2026-10-21-增量)），所以英文下这一列就是纯 ASCII 自然序。
- `presets()` **已整个删除**：它既给别名又给"要画出来的条目"，还自己探 `/storage/emulated/0`。别名表现在由 UI 侧用字符串资源拼（[`folderAliases`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt#L511)），`DirBrowser` 只回答"这一层到底有什么"。
- 路径比较必须与主机分隔符无关：`canonical()` 用 `File.absoluteFile`，**不许**改回手写 `"$currentPath/"` 拼接，否则 Windows 上注入临时目录的用例会静默失真。代价是它**大小写敏感**，而共享存储挂载大小写不敏感：磁盘上叫 `download` 而别名写 `Download` 时别名不命中，会露出真实名。
- UI 本身仍无自动化测试，钉住的是纯函数那一层（6 条用例，见 [testing.md](testing.md)）。

**同一条决定的另一半 —— 返回手势先当"上一级"，换层用整层滑动**

**决定**：目录弹窗里系统返回键**先当"上一级"用**，只有在 root（没有可去的父级）才关闭弹窗；换层用 `AnimatedContent` 做整层推入/弹出 + 淡入淡出，进子目录向左推、返回向右弹，与标题改动同一时刻发生（[BatchScreen.kt:426-455](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt#L426)）。时长与缓动复用 `IosMotion`（[IosStyle.kt:138](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L138)）那一套令牌，和冷启动入场是同一种手感。

**为什么**：这是一台设备上的文件浏览器，"返回键直接把整个弹窗关掉"会让人反复重选层级；而方向感（向左=进深、向右=回退）是让列表可读的关键，尤其在只剩一列之后。`BackHandler` 必须放在 `IosSheet` 的 content 里 —— 那是 Dialog 自己的组合，返回键先落到它的 `OnBackPressedDispatcher`，放在 Activity 那层拦不到。

**为什么 `上一级` 只在父级可选时出现**：root 之上（`/storage/emulated`）是实测定出来的死角 —— 那里读不出子目录，root 只剩一个"0"条目，是一条回不来的路。所以 root 这一级就是最顶层，既不给"再往上一级"的入口，返回键到这一层就直接关窗（[BatchScreen.kt:336-345](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt#L336)）。

### 5.11 图标与启动过渡（2026-10-21 增量）

**决定**：桌面图标是**纯 XML 的 adaptive icon**（`drawable/ic_launcher_foreground.xml` + `ic_launcher_background.xml`，`mipmap-anydpi-v26/ic_launcher.xml` 与 `_round.xml`），零 PNG；两层结构只有 `background` + `foreground`，**不给 `<monochrome>`**（理由见下面那段）；冷启动走 `androidx.core:core-splashscreen`（1.2.0，`:app` 唯一新增的 androidx 依赖），退出动画再由 `BatchScreen` 的交错入场接住。

**为什么是纯矢量**：仓库里有一条刻意的约定「图形用 Canvas 画，不引入矢量资源」（[IosControls.kt:442](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt#L442)）。launcher 图标必须是资源文件，所以这条只能破一半：破的是"要有图形资源文件"，守的是"不要二进制美术资产"——XML 能 diff、能进代码评审、改一行的改动看得出来，PNG 六档密度做不到。

**为什么现在没有 monochrome 层**：起初 `<monochrome>` 直接复用纯白前景层（省一份会各自漂移的副本），代价是任何走「主题图标」的渲染会把那一层重染成主题色。装机复核（2026-10-21，V2419A 深色）：撤掉之后**桌面图标是对的**（蓝底白标记，圆形遮罩没啃到图形），这条决定就凭这一点成立 —— 它**不是**冷启动 splash 黑标记的解药，那件事另有原因，见 [§5.16](#516-深色-splash-的白标记被-rom-画成黑2026-10-21-装机裁定)。

现在两层结构只留 `background` + `foreground`：**不给任何渲染路径留下可染色的白色形状**，launcher 想要单色版就退回普通图标。齿孔仍然用 `fillType="evenOdd"` 挖成透明而不是涂背景蓝 —— 涂成背景色的孔在任何压平渲染下都会消失，这一点不变。

**尺寸边界不是审美**：adaptive 前景的安全区是居中 66×66，圆形遮罩内切半径 33。所有描边外缘到画布中心 `(54,54)` 的距离都控制在 33 以内（最远是相框左上角 ≈29.1、齿孔条右下角 ≈28.8），所以圆形 launcher 不会啃掉图形。

**为什么 splash 与入场要分成两段**：系统 splash 管进程启动→首帧那段真空（这段 Compose 还没起来，做不了任何事），Compose 入场管首帧之后的衔接。`IosEntrance` 是它们之间的闸门：内容在 splash 退出动画放完之前停在 `alpha = 0`（[IosEntrance.kt:24](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosEntrance.kt#L24)），否则卡片在 logo 背后就演完了。

**代价与两处刻意**：
- `installSplashScreen()` 必须在 `super.onCreate()` **之前**（window 级的，晚一步拦不到首帧），并且随后**显式** `setTheme(Theme.Vlive)`（[MainActivity.kt:36-40](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/MainActivity.kt#L36)）。`core-splashscreen` 在 31+ 走的是系统那条分支，而 `minSdk 31` 之后这条就是唯一一条；显式 `setTheme` 保留，是为了不把恢复时机压在库的内部行为上。
- 入场有一条 **600ms 超时兜底**（[IosStyle.kt:153](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L153)）：OEM 的 splash 分支不少，退出监听万一不回叫，没有兜底整屏就**永久**停在 alpha 0。看不见动画可以接受，看不见界面不行。
- 入场**不读** `Settings.Global.ANIMATOR_DURATION_SCALE`：那是一次跨进程查询，与 §5.3「组合期不碰 binder」冲突。用户在系统里关掉动画时这段会不会归零，**尚未装机确认**。

### 5.12 `-v26` 这个"多余"的目录限定符不能删

**决定**：图标目录保留 `res/mipmap-anydpi-v26/`，尽管 `minSdk 31` 让 lint 的 `ObsoleteSdkInt` 建议把它并成 `res/mipmap-anydpi`。

**实测**：改名之后 `:app:processDebugResources` 直接失败 ——
`AAPT: error: resource mipmap/ic_launcher not found`（`ic_launcher_round` 同），`:app:clean` 之后仍然如此，所以不是增量合并的缓存残留。改回 `-v26` 即通过，`aapt2 dump badging` 能解析出 `application: icon='res/mipmap-anydpi-v26/ic_launcher.xml'`。

**结论**：这条 lint 建议在当前 AGP 9.4.1 / AAPT2 组合下会把构建搞坏，因此**不要照它改**。lint 里这一项是 information 级、不参与构建门槛（CI 不跑 lint），留着比全局 `disable += "ObsoleteSdkInt"` 好 —— `minSdk` 抬到 31 之后（见 [§5.20](#520-minsdk-抬到-31真模糊的门槛也是它带走了分支2026-10-21-增量)），原先 `PermissionGate.kt:46/51` 那两条"低于 API 30 的短路分支"已经是死代码并被删除、它们名下那两条 finding 也随之消失，于是整份报告里剩下的**唯一一条** `ObsoleteSdkInt` 就是这里说的 `-v26`；整类静音换来的只是把这一条也一起藏掉，而它仍然不能照建议改。

### 5.13 品牌名 vlive，包名一路改到 `dev.zayn.vlive`

**决定**：应用名、`applicationId`、`namespace`、Kotlin 包（含 `:core` 的 `dev.zayn.vlive.core`）、主题样式名（`Theme.Vlive`）、CI 产物名（`vlive-debug` / `vlive-release`）、C4 图源文件名（`vlive.structurizr.dsl`）全部改到 `vlive`。主界面大标题 `vlive`，功能说明退到副标题 `live photo 批量合成`。GitHub 仓库名仍是 `live-photo-batch`，文档里出现它时指的是**仓库**，不是产品。

**为什么连包名一起改**：只改 label + applicationId 也能交付品牌，但会永久留下"桌面上的 vlive，装在一个叫 livephotobatch 的包里"这种第二套名字。这次替换是纯机械的（54 个文件、零逻辑改动、测试与构建同轮验证），越晚做越贵。

**代价（实测）**：
- `applicationId` 变了 = 设备上这是**另一个 app**：旧包名那份不会自动升级，也不共享它的私有目录（第 4 片的持久化与日志因此天然按新包名重来）。装机前先卸旧包。
- 清单组件名从 `dev.zayn.livephotobatch/dev.zayn.livephotobatch.app.MainActivity` 变成 `dev.zayn.vlive/dev.zayn.vlive.MainActivity`，`am start` 那行要跟着换（testing.md 已改）。
- 文档里 380+ 条 `file:行` 引用的**路径**全部要重算（行号本身没动 —— 包声明与 import 都是等行替换，这一点是这次敢整片改的依据）。

### 5.14 文案全部进资源，默认语言是英文（2026-10-21 增量）

**决定**：`res/values/strings.xml` 写**英文**（未限定配置），`res/values-zh/strings.xml` 写中文；`:app` 的界面上没有一个字面文案 —— 一屏、目录弹窗、Toast、失败原因短语全部走 `stringResource` / `pluralStringResource`。`:app` 唯一新增的语言相关结构是 [`MessageText`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L64)：`@StringRes id + List<Any> args`。

**为什么默认给英文，而不是让中文当默认**：未匹配到任何 `values-*` 语言时拿到的是未限定配置。默认是中文的话，任何非中文环境（含 es / fr / ja，本仓库不打算伺候它们）都会**看到中文** —— 那等于把"没翻译"伪装成"翻译好了"。默认放英文，剩下的语言落到一种全球读得懂的说法上。

**代价**：中文从"就是它"降级成一个 qualifier。改文案要改两个文件，漏一条就会在中文环境下掉回英文 —— 这条靠 lint 的 `MissingTranslation` 兜（当前 0 条，见 [testing.md](testing.md#本地化与深浅色没有设备时验到哪一层)）。

**为什么 `UiState.globalError` 不是 `String`**（[:50](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L50)）：拼好的串会把语言焊死在状态里 —— 系统语言切换后重建的组合读到的还是上一条语言的旧文案；而且 VM 要摸 `Context` 才能拼串，`app/src/test` 那条"不引 Robolectric"的前提就没了。`args` 用 `List<Any>` 而不是 `vararg`：`data class` 的 `equals` 要能整条比较，`Array` 是引用相等。

**为什么目录别名由调用方注入**：`DirBrowser` 原来的 `presets()` 既给别名又给"要画出来的条目"，而且自己探 `/storage/emulated/0`。现在只剩 [`mergedRootEntries(aliases, children, currentPath)`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/browse/DirBrowser.kt#L106) 与 [`displayPath(path, root, rootLabel)`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/browse/DirBrowser.kt#L31) 两条**纯函数**，别名表由 [`folderAliases(root)`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt#L511) 用资源拼出来。理由和 §5.10 是同一条：这个文件要能在本地 JVM 上直接测，一旦去摸 `R.string` 就得把资源系统一起拖进来。

**英文那份别名 `Movies → Movies` 是恒等映射，不是偷懒**：主界面的路径行画的永远是真名（`Internal storage/Movies`），弹窗里若把同一目录在英文中叫成 `Videos`，就会出现"一个目录两种说法"。中文跟着系统文件管理器的叫法（视频 / 图片 / 下载），换语言时别名自然退化。`folder_internal_storage` 是另一回事：`/storage/emulated/0` 不是给人读的名字，两种语言都需要一个别名。

**单复数只做了一条**：只有 `report_summary_warning` 用 `<plurals>`（英文 `1 original not deleted` 与 `…2 originals…` 确实不同）；`report_summary` 里的 succeeded / skipped / failed 是过去分词，`1 succeeded` 本来就是正确英文，为它开 plurals 只是喂 lint。`values-zh` 只给 `other` —— 中文没有单复数，给 `one` 会被 `UnusedQuantity` 挑出来，不给也不会被 `MissingQuantity` 挑（那条检查按语言的复数规则判断）。

**两处跟着语言改的连带决定**：
- 失败原因后面仍然跟着机器可读的枚举名（`reason_code` = `%1$s（%2$s）` / `%1$s (%2$s)`）—— spec §9 那条"给人短语、留着能贴进 issue 的名字"的要求不因语言变。
- [`FileStorage.ensureDir` 的 `IOException` 消息改成英文](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/io/FileStorage.kt#L100)：它的 `e.message` 会被当**参数**拼进本地化的句子（`err_target_uncreatable`），中文串嵌进英文句子（或反过来）比一句开发者看得懂的英文更难解释。

### 5.15 深色跟随系统，判定只有 `IosTheme` 一处（2026-10-21 增量）

**决定**：颜色从 `object IosColor { val card = Color(...) }` 那种**编译期常量**搬进 [`IosPalette`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L27)（浅/深两份实例），[**唯一**一次深浅判定在 `IosTheme`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L197)：`isSystemInDarkTheme()` 选的色板同时交给自绘层（`LocalIosStyle`）和 `MaterialTheme` 的兜底色。**不做应用内开关**，跟随系统。

**为什么先把常量搬成色板**（这一条推翻原来"只有浅色"的写法）：常量把浅色的值烧进了 14 个调用点，加深色只能逐点重写、还可能只改一半；`LocalIosStyle` 让"这次运行是哪套色板"变成一个运行时取值，判定只剩一处。控件层里剩下的 `Color.White` 是有意的两处：开关旋钮与主按钮文字（蓝底上的白，深色下仍然是白）。

**窗口级颜色不在 Compose 的管辖里**，所以 `values-night/` 必须自己有一条：`grouped_background` → 纯黑、`Theme.Vlive` 的 parent 换成不带 `.Light` 的 `Theme.DeviceDefault.NoActionBar`、`windowLightStatusBar` / `windowLightNavigationBar` → false（[values-night/themes.xml](file:///D:/projects/piece/live-photo-batch/app/src/main/res/values-night/themes.xml)、[values-night/colors.xml](file:///D:/projects/piece/live-photo-batch/app/src/main/res/values-night/colors.xml)）。装机反馈的"深色下大背景反而不是黑色"就破在这一层：splash 那一帧由 `windowSplashScreenBackground` 决定，`isSystemInDarkTheme()` 那时还没跑起来。

**`icon_background` 刻意没有 night 变体**：那块蓝是识别物，不跟着翻。原来 `values-night/colors.xml` 里重复写了一遍同一个值 —— 已删，两处各写一遍迟早漂成两个蓝。

**深色 splash 那一帧的白标记变黑，不是这一片改坏的，也不是深色模式本身**：撤掉 `<monochrome>` 之后桌面图标正确，而 splash 的标记照旧是黑的 —— 真因与裁定见 [§5.16](#516-深色-splash-的白标记被-rom-画成黑2026-10-21-装机裁定)。

**装机之前不许宣布深色可用**：本地能验的只有「资源打进包 + 配置对得上」（`aapt2 dump resources` 实测 `color/grouped_background` 有 `() #fff2f2f7` / `(night) #ff000000`，而 `icon_background` 只有默认档）。渲染结果 —— 深色 splash、Dialog 表面、状态栏明暗 —— 一条自动化都没有，见 [testing.md](testing.md#本地化与深浅色没有设备时验到哪一层)。

### 5.16 深色 splash 的白标记被 ROM 画成黑（2026-10-21 装机裁定）

**结论先说**：vivo V2419A 深色模式下，冷启动那一帧里 `splash_logo` 的**白色标记被改画成黑色**，蓝底方块与黑色背景都原样保留。
这是 ROM 对**系统绘制的 splash 窗口**做的颜色替换，我们的资源与主题管不到它。**裁定：让步接受**，不为它保留任何"修复代码"。

**怎么测出来的**（两次探针构建，各装一次、录一帧）：

| 探针 | 同一张 `splash_logo.xml` 里三个部件的颜色 | 深色冷启动实际画出来 |
| --- | --- | --- |
| 1 | 相框 `#FF3B30`（红）· 三角 `#34C759`（绿）· 齿孔条 `#FFFFFF`（白） | 红**保留** · 绿**保留** · 白→**黑** |
| 2 | 相框 `#E4E4EA` · 三角 `#C7C7D2` · 齿孔条 `#FFFFFF` | **三个全部→黑** |

→ 规则是**"浅色 / 低饱和 → 黑"**，不是"只有纯白中招"，也不是整层 tint（饱和色原样通过）。所以任何白色系的标记在系统 splash 图标里都活不下来 —— 换成浅灰只是让同一个问题换个颜色。

**试过且无效的三条**（都不许再加回来）：
1. **撤掉 `<monochrome>` 层**（[§5.11](#511-图标与启动过渡2026-10-21-增量)）—— 撤了之后桌面图标确实正确，但 splash 的黑标记**一模一样**，所以那两层结构从来不是这件事的原因。
2. **`android:forceDarkAllowed=false`**（同时加在 `Theme.Vlive.Starting` 与 `Theme.Vlive` 上）—— 装机实测**毫无变化**。AOSP 的强制深色确实做同一种"浅色→深色"替换，但那条开关管的是应用自己的窗口，splash 窗口由系统绘制、不吃它。
3. **`windowSplashScreenIconBackgroundColor=@color/icon_background`** —— 它买的保险是"某条渲染路径丢掉 drawable 自带的蓝底方块时，白图形仍坐在蓝底上"。实测那件事**没有发生**（蓝底方块一直在），而它会在图标背后多画一层系统色圆，所以**已删除**（[values/themes.xml:22-26](file:///D:/projects/piece/live-photo-batch/app/src/main/res/values/themes.xml#L22)）。

**同一次装机一起看过、没有中招的三处**：桌面图标（深色下蓝底白标记）、Compose 界面（黑底白字，`IosDarkPalette` 那套）、状态栏图标（随 night 主题翻成亮色）—— 所以这不是"整台机器把白变黑"，只发生在系统画的那一帧里。

**为什么到此为止**：剩下的办法都要牺牲一样明写着更好的东西 —— 把标记换成高饱和色（不再是白标记，蓝底上对比度还更差）、深色下干脆不画 logo（冷启动只剩一段纯黑）、或把 logo 搬进 Compose 自绘（logo 晚一帧出现，等于把系统 splash 存在的理由让出去）。这些都不值得为一帧开机画面的颜色付。**要重开这件事，从上面那张探针表开始，不要从猜颜色开始。**

### 5.17 目录选择持久化：只记两个路径，采纳前先验一遍（2026-10-21 增量）

**决定**：源/目标目录写进 Preferences DataStore（`vlive.preferences_pb`，应用私有目录），
[`BatchViewModel`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L126) 只认 [`BatchSettings`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/settings/BatchSettings.kt#L28) 这个两方法接口 ——
`load()` / `save()`，生产实现是 [`PreferencesBatchSettings`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/settings/BatchSettings.kt#L46)。恢复在 `init` 里由
`viewModelScope.launch(Dispatchers.IO)` 发起，**过一遍 `isSelectable` 才采纳**。

**删除开关刻意不记**（[`StoredPaths`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/settings/BatchSettings.kt#L20) 里只有两个字段）。它是唯一会动原文件的那一项：
记住它等于「某次勾了一下，之后每次冷启动都悄悄带着它」，而需求要记的是「目录选择结果」。
所以它每次启动回到关，代价是勾着删原文件跑完一批、下次要重新勾 —— 这个代价是故意付的。

**为什么恢复要过 `isSelectable`**：存下来的串可能已经不再合法（注入过别的 root、或上一次存的本来就是
root 之外那种只能看不能选的串）。那种情况静默留着默认值 —— 让批次悄悄指向另一个目录，
比忘掉一次记忆严重得多。用例：[BatchViewModelTest 的 `a stored path outside the root is never adopted`](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/BatchViewModelTest.kt)。

**为什么 `restore()` 要认「用户已经选过」**：恢复是异步的（DataStore 第一次访问要开文件），
一次慢速恢复可以把用户刚点下去的选择**覆盖回去**。[`userPickedDir`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchViewModel.kt#L157) 让最近的人类意图赢，
`running` 时同样不覆盖。

**为什么必须显式 `Dispatchers.IO`**（这条是实测撞出来的）：`viewModelScope` 默认落在
`Dispatchers.Main.immediate` 上，纯 JVM 单测里没有主线程 —— `init` 里那条 `launch` 一旦不指定调度器，
14 条 ViewModel 测试全红，报
`IllegalStateException: Module with the Main dispatcher had failed to initialize`。
`runBatch` 早就写着 `launch(Dispatchers.IO)`，当时只当是"IO 活该在 IO 线程"，现在多了一条理由：
**它是这批测试能继续不引 Robolectric 的前提**。

**读写都吞 `IOException`**：读不出来当"没存过"，存不进去只是下次回到默认值。这两件事都不值得
掀翻界面，但也不能静默 —— 两条都走 `Timber.w`，见 [§5.18](#518-日志写进应用私有目录三条上限一条写线程2026-10-21-增量)。

### 5.18 日志写进应用私有目录：三条上限、一条写线程（2026-10-21 增量）

**决定**：`Timber` 是唯一入口（`android.util.Log` 全部换掉，[MediaStoreSync](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/MediaStoreSync.kt) 是原来唯一的用户），
[`VliveApp`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/VliveApp.kt) 种两棵树：`DebugTree` 进 logcat（**只在可调试构建**），
[`RollingLogTree`](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/log/RollingLogTree.kt#L37) 进 `filesDir/logs/`，自带轮转与清理。

**为什么是私有目录而不是共享存储**：这个 app 有 `MANAGE_EXTERNAL_STORAGE`，写到
`/storage/emulated/0` 当然做得到 —— 但那正是需求里「避免垃圾过多」要防的东西：日志散在相册看得见
的地方，用户既找不到也删不干净。私有目录不需要任何权限、卸载即消失，出问题时
`adb shell run-as dev.zayn.vlive ls /data/data/dev.zayn.vlive/files/logs` 拿得到。

**三条上限同时生效**（默认：单文件 512 KB、最多 4 个、合计 ≤ 2 MB、最多留 7 天）：
写满就换文件；数量、总体积、年龄任何一条超了都往下删。只限文件数会漏掉「单个文件被写爆」，
只限体积会留一堆空壳文件，所以两条都要。**当前正在写的这个永不删**（[planLogCleanup](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/log/LogRotation.kt#L21)），
否则等于把「刚刚发生了什么」丢掉；删除顺序从旧到新，部分失败也不会在中间打洞。
8 条用例钉着：[LogRotationTest](file:///D:/projects/piece/live-photo-batch/app/src/test/kotlin/dev/zayn/vlive/log/LogRotationTest.kt)。

**所有磁盘动作在 `vlive-log` 这一条守护线程上**：`Timber.log` 是同步调用，落在调用者线程，
而调用者经常是主线程。所以 `log()` 只把参数交给单线程 executor，`mkdirs` / `listFiles` / 写 / 删
全在后台做 —— 连时间戳都在后台格式化，因为 `SimpleDateFormat` 不是线程安全的。

**日志自己不许把 app 带崩**：每个入口都吞 `IOException` / `RuntimeException`。写不进日志只是丢日志。

**记什么**：批次的边界（`batch start` / `batch end`，含源、目标、删除开关、总数、字节、是否取消）、
**没成功的项**（`item X -> SKIPPED: NO_PAIR`）、以及删除失败（`Timber.w`）。成功项不逐条写 ——
它们已经在报告列表里，再写一份只是把文件撑大。

**没验到的**：`RollingLogTree` 与 `PreferencesBatchSettings` 都是**只有真机才能验**的那类
（一个要跑起来才会写文件，一个要 `Context`），本地测的是它们的决策逻辑。见
[testing.md](testing.md) 的缺口表。

### 5.19 玻璃只给三条浮动 bar，卡片与按钮不各自糊一层（2026-10-21 增量）

**决定**：真模糊（Haze 2.0.1 → `RenderEffect`）只出现在**三个浮动面**上：主屏顶栏、主屏底部操作栏、
目录弹窗顶栏（[GlassBar.kt](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/GlassBar.kt)
的 `Modifier.iosGlassBar`）。卡片回到实心面（浅 `#FFFFFF` / 深 `#1C1C1E`，[IosStyle.kt 的两份 IosPalette](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L48)）；
条内的按钮**不再各糊一层**，`IosButton` 只加自己的 tint / 高光 / hairline 描边。accent 一次给四个值
（浅/深 × 常规/高对比，[accentFor](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L93)），
一个构件都不多引。全项目**每扇窗口恰好一个 `HazeState` + 一个 `hazeSource`**：主屏那个内容层是源，
弹窗的列表是第二个源（`Dialog` 是独立窗口，跨窗口取不到位图）。

**2026-10-22 凌晨实测之后，这条决定改了一半，按下面"帧预算"那段的数字**：三个浮动面里**底部操作栏不再糊**
（改用同文件的 `Modifier.iosGlassTray`，层序一模一样、只把 `hazeBlur` 换成 `.background(glass.tint)`），
真模糊现在只剩两处：主屏顶栏 + 弹窗顶栏（这两条不同时在场）。其余全部照原样 —— 卡片实心、条内按钮
不各自糊一层、accent 四个值、每扇窗口一个源。

**为什么**：苹果自己的采纳建议就是"自定义玻璃留给一两个关键浮动控件"，实心面上的卡片读起来更原生，
玻璃不许叠玻璃。工程侧的理由更硬：需要实时模糊的绘制面从"每张卡片"降到三块（2026-10-22 实测之后是
**两块**，见上面那段与下面的"帧预算"），帧开销是可测的差别；
而"一层玻璃只模糊一次"是被这样满足的，不是靠把按钮改成实心（spec §1.1）。

**顺序是这条决定的全部内容**（[GlassBar.kt:26-28](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/GlassBar.kt#L26)）：
`clip` 落在 `hazeBlur` 之前（背景糊完再按 shape 裁，才有那条干净的玻璃边缘），高光与描边叠在糊好的背景之上、
不参与模糊本身。**这一段原本还写着第三条"`shadow` 必须在 `clip` 之前"，2026-10-22 实测之后整条作废** ——
`Modifier.shadow` 把阴影的扩散区留在本层**内部**，两条玻璃链上的 `shadow` 连同 `GlassMaterial.shadowElevation`
一起删掉了；机制、七台构建的读数与"不许再加回来"见
[§5.21](#521-玻璃-bar-上的-modifiershadow-全部删掉它在层内预留一圈把玻璃切成白色方块2026-10-22-装机裁定)。
两套预设的 `radius` 都是 `0.dp`
是刻意的 —— 贴边的两条 bar 本来不带圆角；**要圆角的那条（底部操作栏）必须显式传 `IosMetrics.glassRadius`**，
走默认值只会得到一条方角 bar，编译器、测试和 lint 都不会吱声。这条规则现在由 `iosGlassBar` 与
`iosGlassTray` 各自守一遍（[GlassBar.kt:47-66 与 :90-96](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/GlassBar.kt#L47)），
后者是前者的层序去掉 `hazeBlur`、换成一条 `.background(glass.tint)`。

**对比度这一条按实测改写，不许掰数字**：spec §1.5 原文要求"Tinted 主按钮上的白字在各自外观下 ≥ 4.5:1"，
整枝评审把这个判据拿去对 spec 自己的色板算了一遍，量出来两个数**不可能同时成立** ——
按相对亮度在合成色上算（`accent.copy(alpha = 0.85f)` over 该外观的玻璃底，浅 `≈#F9F9FB`、深 `≈#111113`）：

| 组合 | 白字在 Tinted 底上 | 同一个 accent 不透明时 | accent 当文字压在该外观玻璃上 |
| --- | --- | --- | --- |
| 浅色 · 常规 `#007AFF` | **3.3:1** | 4.0:1 | 3.8:1 |
| 浅色 · 高对比 `#0040DD` | **5.7:1** ✅ | 7.6:1 | 7.2:1 |
| 深色 · 常规 `#0A84FF` | **4.7:1** | 3.7:1 | 5.2:1 |
| 深色 · 高对比 `#66B3FF` | **2.9:1** ❌ | 2.2:1 | 8.5:1 |

**裁定**：常规那一列**不追 4.5:1**，改为"量出来、记录在案"，并接受它是 WCAG 1.4.3 的一条**有记录的偏离**，
理由不是"内部工具所以可以不管"，而是**苹果自己的系统按钮同样做不到** —— 不透明 `#007AFF` 配白字只有 4.0:1，
把它压到 4.5:1 需要的深色正好毁掉用户点名要的那份 iOS 身份。过硬指标的只有高对比那一列：浅色 `#0040DD`
（合成后 5.7:1）过了。
**没过的这一条不许悄悄收下，也不许盲调**：深色高对比 `#66B3FF` 作白字底只有 2.9:1，是本表里**唯一**
达不到本条自定硬指标（高对比那一列 ≥ 4.5:1）的一格。它不是改个数值就能了事的疏漏：`s.blue` 这一个 token
同时兼两个角色 —— accent 当文字压在深色表面上要**亮**（同一格 8.5:1），白字压在 accent 填充上又要**暗**
（2.9:1 正是"亮"这一面带来的），一个值不可能两头都赢。本轮不动任何 preset / accent 值；两条出路
**用户已经拍板（2026-10-22）：对"白字在深色高对比填充上"这一格放宽判据** —— `accentFor` 不拆成
accent + accent-fill，四个值一个都不改，2.9:1 从"未决"改成**已裁定接受的偏离**记在这里。

**这一格今天在设备上能碰，碰了，结论是"信号取不到"**：测试硬件是 API 36（V2419A 与本轮新装的
`vlive_api36` 模拟器同级，正是 `isHighContrastTextEnabled` 存在的第一档，见 §5.20）。在模拟器上把
`settings put secure accessibility_high_text_contrast_enabled 1` 写进去、`settings get` 读回 `1`、
`am force-stop` 后冷启动，取样像素与关掉时逐点相同 —— 浅色 Tinted 底 `#1D85F6`、深色 `#0B72DB`，
开关两态一字不差（`D:/tmp/em31_hc_off_light.png` / `em32_hc_on_light.png` / `em33_hc_on_dark_en.png` /
`em34_hc_off_dark_en.png`），而这一版 Settings 里没有"高对比文字"这一行（Accessibility → Color and
motion 下只有 Color inversion / Dark theme / Color correction / Remove animations）。所以
**四个值里高对比那一列仍然没有被任何设备渲染过一次**，只是原因从"够不着 API 36"换成了"API 够到了、
信号没送到应用"。这条缺口按 §1.5 的原规矩记着，不许因为"值写了"就当它生效过；验完设置已还原
（`settings delete secure …` 读回 `null`）。

**代价**：两块实时模糊层（主屏顶栏 + 弹窗顶栏；2026-10-21 写下这句时是三块，底部那条已经按 §3.4 的回退
不糊了）+ 每帧一次滚动驱动的 alpha。两个代价的形状已经被代码钉住：滚动折叠区间内
只有那条顶栏会被无效化（`alpha` 以 `State` 传进 `BatchScreen` 的私有 `GlassTitleBar`，
不在顶层委托，见 [BatchScreen.kt:445-497](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/BatchScreen.kt#L445)），
弹窗的四条 inset 在它自己的窗口里现算（[IosControls.kt:551-558](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt#L551)）。

**帧预算：量到了，两条线都超，回退已启用但没有把预算拉回来**（2026-10-22 00:00–00:10，V2419A /
API 36 / 120 Hz）。流程按 runbook：`dumpsys gfxinfo dev.zayn.vlive reset` 先行 → 冷启动 → 12 次固定
swipe → 每个构建三遍取中位数，每遍的完整 dump 各自存盘（`D:/tmp/T9_base_s1..3.txt`、`T9_head_s1..3.txt`、
`T9_tray_s1..3.txt`）。判据是 spec §3.4 第 4 条：**改造后 ≤ 基线 +3 个百分点，且绝对值 ≤ 10%**。

| 构建 | 总帧数（三遍） | `Janky frames (legacy)` 三遍 → 中位数 | 50 分位 | 帧时间 >8ms 的占比 → 中位数 |
| --- | --- | --- | --- | --- |
| 基线 `739b388`（全项目没有模糊消费方） | 914 / 928 / 934 | 11.16% / 10.13% / 9.64% → **10.13%** | 7 / 6 / 6 ms → 6 ms | 12.25% / 12.50% / 12.85% → **12.50%** |
| 三条 bar 全糊 `41e5e2a`（= `cab321b` 的父提交） | 888 / 896 / 924 | 11.71% / 13.62% / 13.85% → **13.62%** | 8 / 8 / 9 ms → 8 ms | 23.42% / 46.65% / 51.08% → **46.65%** |
| 回退后 `cab321b`（底部那条不糊） | 906 / 926 / 924 | 8.61% / 14.25% / 13.20% → **13.20%** | 8 / 8 / 8 ms → 8 ms | 33.33% / 30.24% / 25.54% → **30.24%** |

**判定：超预算。** 13.62% 对基线 10.13% 是 **+3.49 个百分点**，同时越过"基线 +3pp"和"≤10%"两条线，
所以 §3.4 那条预先约定的回退被触发（不是临场发明）。另有一组更早的独立采样（2026-10-21 23:28 / 23:31，
盘上只留下三行摘要 `D:/tmp/gfx_baseline_t9.txt`、`gfx_after_t9.txt`，没有直方图）给出基线中位数
**10.75%**、改造后 **13.98%**（+3.23pp）—— 两组方向一致、绝对值不同，谁也不能冒充谁；上面这张表以
留有完整 dump 的那一组为准，`cab321b` 提交信息里那三个数（10.13% → 13.62% → 13.20%、>8ms 30.2%）
对得上这一组，对不上摘要那一组。

**判据落在哪个字段不是选择，是设备事实**：spec §3.4 点名的 `Janky frames:` 那一行在九个样本里**全部**
读 `0 (0.00%)`，同一批 dump 里 `Number Missed Vsync: 0` 也全部为 0，而 legacy 那一行同一批数到 78–136
帧 —— 这台机器上那个字段根本不触发，判据只能用 `Janky frames (legacy)` + 帧时间直方图（spec §3.4 已按
这条补注）。早先记录里"这台 ROM 的 presentation deadline 有 12.3 ms"那半句在盘上没有对应文件，本轮
没有复现它，只把能核对的那半句写在这里。

**回退收回了什么、没收回什么**：legacy 中位数 13.62% → 13.20%（−0.42pp，**小于它自己三个样本的散布
8.61–14.25**，算不上信号）；>8ms 中位数 46.65% → 30.24%（−16.4pp，但两组的逐样本区间重叠：
23.42–51.08 对 25.54–33.33，同样不能当干净结论）。**没有收回的是判定本身：13.20% 仍然同时越过两条线。**
换来的是看得见的代价 —— 底部那条不再糊，卡片文字从它底下直接透出来和按钮文字打架，浅色深色都拍到了：
`D:/tmp/em20_light_en_titlebar_in.png`（`Succeeded IMG_20260008 → …` 压在 "Start merging" 上）、
`D:/tmp/em40_dark_en_scrolled.png`、`D:/tmp/em71_tray_tap.png`。同一些图里顶栏与弹窗顶栏糊得干净，
这个反差就是"砍掉哪一层"的视觉证据。

**模拟器不能用来量这条预算**：`vlive_api36` 上一次滚动报 `Janky frames: 6 (3.87%)` 与
`Janky frames (legacy): 120 (77.42%)`，两个字段同时荒谬（软件合成的帧节拍）。上表全部来自 V2419A；
模拟器只承担截图矩阵、手势与命中测试这些"看得到"的判据。

**设备实测补记（2026-10-22 中午，`vlive_api36` / API 36 / 1080x2400，装的是 `cab321b` 的包）**：
逐条对着 spec §3.4 与 §3.6 看过，图都在 `D:/tmp/`。
- **保留模糊的两条 bar 真的在糊内容**：主屏顶栏下 "Progress 12/12 · 87 MB written"、"Delete originals
  after merging (irreversible)"、"内部存储/Pictures/LivePhoto" 被抹平而 `vlive` 清晰
  （`em20_light_en_titlebar_in.png`、`em40_dark_en_scrolled.png`、`em51_dark_zh_scrolled.png`、
  `em54_light_zh_scrolled.png`）；弹窗顶栏糊的是它自己的列表 —— root 层滚起来后两枚蓝色目录图标在玻璃
  下糊成两团，`关闭 / 选这里 / Camera` 清晰在上（`em13_sheet_root_scrolled.png` 浅色、
  `em41_dark_sheet_scrolled.png` 深色）。玻璃叠玻璃：没有出现过（弹窗两颗按钮是纯文字）。
- **手势与命中（spec §3.6 第三条落地）**：从玻璃顶栏里起手的滑动会滚后面的内容 —— 起手 y=150、位移 100px，
  标记行 `Report` 从 y=658 走到 569（89px）；从底部 tray 的非按钮区起手同样成立，两按钮之间那条缝
  （x=711）走 569→481，tray 下沿（y=2300）走 481→394。**顶栏上的点击穿透**：在 y=150（玻璃带内）点下去，
  底下那行"源目录"响应、弹窗打开（`em21_tapthrough_titlebar.png`）。底部 tray 的非按钮带也点了
  （x=711 / y=2300，底下正压着一张报告行），状态毫无变化 —— 但报告行本来就不吃点击，所以这一条只能证明
  "点错不到按钮"，**不能**证明点击穿过它：这份夹具里没有任何可点元素能被滚到底部 tray 底下（报告只有一页），
  记在未验清单里。没有为此加过任何 `hitTestBehavior`。
- **两种外观 × 两种语言都看过**：浅色英文 `em01_now.png` / `em60_restored_light_en.png`、深色英文
  `em34_hc_off_dark_en.png`、深色中文 `em50_dark_zh_idle.png`、浅色中文 `em53_light_zh_idle.png`，
  加上各自的滚动态与弹窗态。bar 上的文字（`vlive` 紧凑标题、`开始合成`/`Start merging` 白字、
  `取消`/`Cancel` accent 字）四种组合都读得清；卡片在两种外观下仍是实心白 / `#1C1C1E`。
  **中文不是系统语言切出来的**：`settings put system system_locales zh-CN` 写完冷启动界面照旧是英文，
  中文是靠 `cmd locale set-app-locales dev.zayn.vlive --locales zh-CN` 这个**按应用**开关渲染出来的，
  验完已改回 `en-US`、`system_locales` 删回 `null`。跑完的还原证据是 `em60_restored_light_en.png`。
- **权限缺失态与批次运行态各看过**：`appops … deny` 后冷启动是红字提示卡 + Tinted 的
  "Grant all files access"，底部那颗 `Start merging` 走淡底淡字禁用（`em30_perm_denied_light_en.png`）；
  批次运行中的那一帧是从 30fps 录屏里挑出来的 `D:/tmp/emrun/g040.png`（深色，`Progress 1/12 · 8 MB written`、
  `Start merging` 禁用、`Cancel` 是亮 accent 可点）—— §1.4 改的两种按钮画法都对。验完 `appops` 已回到 `allow`。
- **冷启动那一帧没被玻璃碰到**：同一个模拟器上分别装 `739b388` 与 `cab321b` 录冷启动，
  `cab321b` 的 splash 帧 `D:/tmp/emsp_head/s11.png` 与基线的 `D:/tmp/emsp_basef/b12.png` 逐点比对
  （每 9px 采样 26,760 点）最大差 12/765、平均 0.11、超过 30 的点 **0 个** —— 编码噪声以内。
  浅灰底 + 蓝底白标记，画面里没有任何玻璃。
- **一条真实缺陷（没改，按规矩报）**：弹窗顶栏的**副标题被 bar 自己的下边缘裁掉**——`Internal storage/DCIM/Camera`
  与 `内部存储/DCIM/Camera` 的下半截（`g`、`y` 的下伸部）在玻璃条的 hairline 处被切平，浅色深色、
  root 与下级都一样（`em10_sheet_root.png`、`em14_crop_bar.png`、`em41_dark_sheet_scrolled.png`）。
  几何在 [IosControls.kt:611-647](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt#L611)：
  bar 实高 `topInset + IosMetrics.sheetBarHeight`（52.dp），里面那列是 17.sp 标题 + 11.sp 副标题两行，
  居中后超出这一层的高度就被 `iosGlassBar` 的 `clip` 裁掉。技术债表里有一条。

### 5.20 minSdk 抬到 31：真模糊的门槛，也是它带走了分支（2026-10-21 增量）

**决定**：`minSdk 30 → 31`，且**不为 API 30 保留任何降级路径**。

**为什么**：`RenderEffect` 模糊是 API 31 起的；Haze 2.0.1 的 androidMain 里
`platformIsBlurEnabledByDefault() = Build.VERSION.SDK_INT >= 31` —— **库自己在 30 上默认关掉模糊**，
只剩一块半透明遮罩。与其维护"30 上是另一种观感"这条分支，不如把地板抬过去。
判据来自构件源码，不是文档转述。

**代价**：`PermissionGate` 的两条 below-API-30 分支变成死代码并删除（连带 §5.12 那句"双保险"作废，
lint 基线 12 → 9、`ObsoleteSdkInt` 3 → 1 —— 总数掉三条而不只是那两条 `ObsoleteSdkInt`：被删的 `else`
分支里那句 `Uri.parse` 自己还挂着一条 `UseKtx`，它跟着分支一起没了）；`WRITE_EXTERNAL_STORAGE maxSdkVersion="29"` 变成永不上号的
死声明，删；`READ_EXTERNAL_STORAGE maxSdkVersion="32"` 保留（31–32 仍归它管）；accent 那四个值也吃同一道地板：
信号 getter `android.view.accessibility.AccessibilityManager.isHighContrastTextEnabled()` 在本地
`platforms/android-37.0/data/api-versions.xml` 里标 `since="36"`，高于 `minSdk 31`，于是四个值照发、`accentFor`
也照常在四者里选，但高对比那一**对**只可能在 36+ 被选中，31–35 上永远渲染常规 accent —— 这就是 spec §1.5 要求记录、
不许静默把四个值退化成两个当作完成的那条缺口，它与最终跑在哪台机器上做验收无关；判据是本地 `api-versions.xml`
与 `javap` 的读数，不是文档转述；依赖侧同一条决定也锁定了构件形状 —— **本项目固定的 2.0.1** 里，模糊
那一半（`dev.chrisbanes.haze:haze-blur` = `hazeBlur` / `HazeBlurStyle` / `HazeColorEffect`）与 source 那一半
（`dev.chrisbanes.haze:haze` = `hazeSource` / `HazeState` / `HazeInput`）是两个构件，`:app` 因此要同时声明两个；
这条 source/consumer 的拆分**不是 2.0.1 才有的**（Maven Central 上 2.0.0 那条线就有 `haze-blur`），
下面这些读数只对本地缓存里这一版成立，别把它读成"版本史实"。两者
AAR 都只要求 `minSdkVersion="23"`（`haze-blur-android` 的 POM 反向带上 `haze-android:2.0.1` 与 compose `ui 1.12.1`，
`haze-utils` 由它传递进来），抬地板不与 `:app:checkDebugAarMetadata` 冲突。
设备侧：Android 11 的原装机器从此装不上，当前 V2419A 满足。

### 5.21 玻璃 bar 上的 `Modifier.shadow` 全部删掉：它在层内预留一圈，把玻璃切成白色方块（2026-10-22 装机裁定）

**结论先说**：`Modifier.shadow` 不往本层外面画阴影，而是在本层**内部预留**一圈阴影扩散区 —— 排在它后面的
`hazeBlur` / `.background` / `.border` 一起被缩进那一圈以内，**连子节点也一起裁进去**。三条玻璃面上它都留了痕：
主屏顶栏与弹窗顶栏被切出一块"玻璃背后压着的白色方形色块"，底部操作栏把两颗按钮各切出一块矩形补丁。
**裁定：`iosGlassBar` 与 `iosGlassTray` 两条链上都不再调用 `Modifier.shadow`，`GlassMaterial.shadowElevation`
随之删除。这一条不许再加回来。**

**读数环境**：emulator-5554 / API 36 / 420dpi（density 2.625）/ 1080x2400，每台构建 `install -r` 后跑同一套脚本
（冷启动 → Start merging → 展开 Report → 6 次固定 swipe → `screencap`），读数一律取 `y=60..200` 的列均值。
参照值：页面底色 **243.0**、卡片 **254-255**（卡片左右边 x=42 / x=1037，即 `screenMargin` 16dp = 42px）。
**V2419A 当天没有连着，机上没有复量过** —— 这条机制是 compose-ui 的层内预留，不是某台机器的渲染怪癖，
但"应当与设备无关"是推理，不是实测。

**七台构建的对照**（x 均为 1080 宽屏幕上的像素列）：

| 构建 | 改动 | 顶栏那一行的读数 |
| --- | --- | --- |
| 基线 `00c2579` | 原样 | x=0..1 → 211 · x=2..25 → 238.1 缓降到 230.6 · **x=26..1053 → 247.3-249.3** · x=1054..1077 → 230.6 缓升到 237.1 · x=1078..1079 → 210.4 |
| A | 只去掉 `hazeBlur` | 台阶原样还在（x=24 → 230.6，x=26 → 247.3）→ **Haze 无罪** |
| B | A 再去掉 `shadow` | x=2..1076 均一 **249.3** → 台阶来自 `shadow` |
| C | `hazeBlur` 回来 + `shadow(clip = false)` | 与基线逐列相同（237.2→233.1 / 247.3-248.9）→ **`clip` 不是原因** |
| D | C 把 elevation 改成 `24.dp` | 画面缩到 **x=229..984** → 预留宽度跟着 elevation 走 |
| E | 交付的顶栏修法：`iosGlassBar` 不再 `shadow`，`hazeBlur` 保留 | x=4..1076 均一 **246.2-247.7**，中段随被糊的内容起伏（207-252）→ 铺满且在糊东西 |

**底部操作栏上量到的是同一条机制的第二种症状**（基线 `00c2579` / C / E 三台里 `iosGlassTray` 都还带着 `shadow(6.dp)`）：

- 它把**子节点裁了**。Cancel 那颗的填充列（x=850）在 E 上是 `200 → 180` 的渐变走到 y=2236，然后在 **y=2242 跳回 193**、
  一路平到 y=2272；同一行（y=2250）在 **x=960→980 从 193 掉到 178**。两个方向各一条硬边 = 截图里那块矩形补丁。
  去掉 `iosGlassTray` 的 `shadow`（构建 F）之后，同一列是 `208 → 196` 的平滑渐变、同一行 x=740..980 全是 196，**两处台阶都没有了**。
- 它把本层也缩了一圈。托盘的布局盒是 x=42..1037，可见药丸（描边在 x=60 与 x=1018，填充 ≥248 从 61 到 1017）
  → **6.dp 预留了 20px**。这一条单独看只是"药丸比布局盒小一圈"，不构成可见缺陷；和上面那条合起来才说明预留是整层生效的。

**试过且无效的三条**：
1. **`clip = false`**（构建 C）—— 逐列与基线相同，`clipToOutline` 从来不是原因。
2. **调低 elevation** —— 只是把那一圈按比例缩小（8.dp→26px、6.dp→20px、24.dp→229px 三个点连成一条单调曲线），不会归零。
3. **给 bar 补一道等于预留宽度的 padding 把它抵消掉** —— 没做。它要把一个"随 density 与 elevation 变、只有三个实测点"
   的常量写进布局，正是 [§5.16](#516-深色-splash-的白标记被-rom-画成黑2026-10-21-装机裁定) 那条要求删掉的那类保险。

**放弃了什么**：底部操作栏从此没有投影，浮动感改由 hairline 描边 + 与页面底色的色差承担。浅色（`xG_light.png`）、
深色（`xG_dark.png`，纯黑页底上这条深色药丸仍读得出边界）、弹窗（`xG_sheet_scrolled.png`，顶栏照样糊自己那张列表）
三张里都没有再出现方块或补丁。**条内按钮自己的 `shadow(2.dp)` 保留**：F/G 两台里两颗按钮都带着它，补丁已经没有了，
所以这一档预留宽度小到没有可见后果 —— 这句是"在带它的构建里没观察到症状"，不是"量过它不预留"。

**验收**：`:app:testDebugUnitTest` + `:core:test` 共 **205 个用例、0 失败、1 跳过**（跳过那条是既有的）；
`:app:lintDebug` **9 条**，与 [§5.19](#519-玻璃只给三条浮动-bar卡片与按钮不各自糊一层2026-10-21-增量) 记下的那一组
逐条同名（`EmptySuperCall` / `OldTargetApi` / `ModifierParameter` / `PluralsCandidate` / `ObsoleteSdkInt` /
`UseOfNonLambdaOffsetOverload` / `UseKtx` / `MonochromeLauncherIcon`x2），没有新增。

### 5.22 开关的圆点从来没动过：`Modifier.offset` 排在绘制类之后等于没写（2026-10-22 装机裁定）

**结论先说**：`IosSwitch` 那颗圆点从这条开关落地起就**没有移动过一步**。轨道换色是真的、动画值 `knob` 从 0f 走到 1f
也是真的，被丢掉只有最后那一步位移。裁定：`.offset(...)` 提到链子最前面，**并且把 `testing.md` 那条
"深色下开关轨道正常"的记录作废** —— 它是一次不成立的验证，比没测更贵。

**机制**：`Modifier.offset` 是 `LayoutModifier`，它挪的是排在它**内侧**的那个 measurable；而圆点这颗 `Box`
没有子内容。所以 `.shadow() → .background(Color.White, CircleShape) → .offset(...)` 这个顺序下，被挪走的是
"什么都没有"，圆点自己的白色背景留在原地。和 [§5.21](#521-玻璃-bar-上的-modifiershadow-全部删掉它在层内预留一圈把玻璃切成白色方块2026-10-22-装机裁定)
是同一类错：**modifier 的位置就是它的语义**，编译器、测试和 lint 全都不吱声。

**怎么量出来的**（emulator-5554 / API 36 / 420dpi，V2419A 当天没连着）：轨道盒 x=862…996，取 y=742 一行看白色像素。
修之前 —— 关：圆点 867…941、轨道右段 230；开：圆点**还是** 867…941、轨道右段 116。修之后 —— 关：左沿 867；
开：左沿 **920**，走掉 53px = 20.2dp，正是代码里那条 `行程 = 51 - 27 - 2*2` = 20dp。直径 77px ≈ 29dp，
说明它没被自己那层 `shadow(2.dp)` 缩掉。

**顺带给 §5.21 补的下界**：同一次扫描量了 `IosButton` —— 蓝色填充包围盒 x=74…694 / y=2154…2283，
与布局盒 74…695 / 2154…2284 严丝合缝。所以 **2.dp 这一档预留量不到**，按钮与圆点上的 `shadow(2.dp)` 保留，
不必跟着 §5.21 一起删。那条机制是随 elevation 长出来的，不是"只要有 shadow 就坏"。

**为什么活了整整一轮**：`testing.md` 的装机矩阵只看颜色与边界，圆点不动被轨道换色冒名顶替了；
`:app` 的 JVM 单测没有 Compose UI-test（依赖表里就没有），结构上测不到任何 modifier 次序问题。
缺口表新增一行「modifier 链的次序 / `LayoutModifier` 摆在哪一格」，判据写成可量的形式，别再只看颜色。

**验收**：205 个用例、0 失败、1 条既有跳过；`:app:lintDebug` 仍 9 条、无新增；`UseOfNonLambdaOffsetOverload`
这一条 lint 依旧命中（修法是把 lambda 版 `offset` 用对，但这条改的是**位置**不是重载，不在本次范围内）。

### 5.23 不可逆删除必须留下一份凭据（journal，2026-10-23 增量）

**结论先说**：`filesDir/journal/` 里一批一个文件，记下这一批**真的删掉了哪些**原文件；界面上"已删除的原文件"那一行读的是这个文件，不是内存。裁定：这是修 bug，不是加功能 —— 在此之前，这个 app 做完一件不可逆的事之后不留任何记录。

**之前是什么样**：`BatchRunner.commit()` 把绝对路径放进 `ItemResult.deletedSources`，`:app` 只把它喂给媒体索引扫描器（`touchedPathsFor`），然后就活在 `UiState.items` 里。而下一次 `start()` 第一句就是 `items = emptyList()`；进程被杀同理。日志那条路也补不上：[§5.18](#518-日志写进应用私有目录三条上限一条写线程2026-10-21-增量) 为了不让文件膨胀，**刻意只记非 SUCCESS 的项**，恰好把"删了东西的那些"全跳过了。所以两条持久化通道都精确地漏掉了同一批数据。

**定下来的形状**（三条都是问过才定的）：

| 决定 | 取值 | 理由 |
| --- | --- | --- |
| 给谁看 | 文件 + 批次结束后就地可看 | 补的就是"下一次 Start 之后看不见"这个洞；独立的跨批次浏览页要引入分组、分页与导航入口，明显更重，不做 |
| 写点 | 每一项 `commit` **之后**、逐项回调里 | 记的是事实不是意图。预记账要引入 pending 状态和双写，只是把窗口从"删完没记"挪到"记了没删" |
| 保留 | 60 个文件 / 180 天 / 合计 2 MB | 选了"跟日志同一套策略、带时间上限"。**代价如实写在这**：过期那批整体淘汰，"半年前删了哪些"按策略就没有答案 |

**几个不那么显然的选择**：

- **一批一个文件**，名字 `deleted-<13位毫秒>.txt`。定宽所以字典序 == 时间序（和日志同一条理由）；"这一批删了什么"就是读一个文件，不用解析历史，淘汰也是整文件删，不会把一批劈成半截。`newBatchId()` 会跳过已存在的名字 —— 同一毫秒撞车的概率被 `running` 挡着，但撞上的后果是两批混进一个文件、读回来互相污染，不对称，所以让一次 `stat`。
- **保留策略直接调用 `log/planLogCleanup`**。它是 `LogRotation.kt` 的顶层纯函数，输入只是 `LogFile(name, bytes, lastModified)`，跟"日志"没有任何耦合，所以零复制、零改动、也不动日志那三个上限。
- **TSV，不引入 JSON 依赖**。字段 `epochMillis / batchId / outputName / count / 路径…`，尾部变长；读取时用行内记的 `count` 收口，所以真出现带制表符的路径也只会截在那儿，不会把字段读串。
- **界面上的数字从文件读回**（`runBatch` 的 `finally` 里读一次），不把内存计数搬过去。写失败时它会诚实地停在 0、那一行根本不出现 —— "界面说有、文件里其实没有"是这份凭据最不该有的样子。展开时才读清单，且走协程：`BatchScreen` 守着"合成期不碰 binder/磁盘"。

**同期修掉的相邻两条竞态**（都是为这条写测试时暴露的，见 commit `874799e`）：`userPickedDir` 一个标志位同时管源和目标，只改目标会把记住的源丢掉；而 `persist()` 是**整份**写回 `StoredPaths`，恢复还没落地时那一格在内存里仍是默认值，于是这次 save 把磁盘上记着的源覆盖成默认值 —— 源目录是在存储层丢的，不只是这一次运行没读到。现在 `persist()` 也先 join 恢复再重读状态。

**怎么验的**：`DeleteJournalTest` 7 条（行格式往返、空删除不留文件、一批一文件、过期整批淘汰且**要第三批才轮到淘汰**、条数超限从旧到新、撕裂行跳过不外抛、目录写不进时吞掉）+ `BatchViewModelTest` 1 条回归（跑一批开删除的 → 再 Start 一次 → 界面上的计数归零，但文件里上一批四条路径一条不少）。装机实测（emulator-5554）：`run-as` 看到 `deleted-1792734407652.txt` 296 字节，`od -c` 确认行尾是裸 `\n`（先前看到的 `^M` 是 `adb shell` 的 PTY 转换，不是文件内容），折叠态读作 `4 files deleted`，展开列出 4 条绝对路径；深色外观看过一张。
**没验到的一条如实说**：中文那一屏这轮没截到 —— `cmd locale set-app-locales` 在这块镜像上设不进去（`get-app-locales` 回 `[]`）。两语对齐改由 lint（无 `MissingTranslation`/`ExtraTranslation`）与键集合直接核对：`values` 与 `values-zh` 各 54 个键、无单边。

**仍然残余**：时间上限会淘汰旧记录（选定的代价）；没有跨批次浏览；`am force-stop` 留在 `.tmp/` 的半截 `.part` 仍无人清扫，批次也仍跑在 `viewModelScope` 而不是前台服务 —— 那是审计里另一条，没在这次的范围里。

**验收**：225 个用例、0 失败、1 条既有跳过；`:app:lintDebug` 仍 9 条、同一组规则名，无新增。

## 6. 构建与 CI

### 6.1 不 apply `org.jetbrains.kotlin.android`

**决定**：`:app` 只声明 `android.application` + `kotlin.compose`；Kotlin 编译选项写在 `android { }` 同级的 `kotlin { compilerOptions { … } }`。

**为什么**：AGP 9.0 起 Kotlin 支持内置在 AGP 里，再声明那个插件会被 KGP 直接拒绝加载（本地 `:app:tasks` 实测），而 `version.ref = "kotlin"` 还会撞上 "plugin is already on the classpath with an unknown version"。

### 6.2 `compileSdk 37.0` 但 `targetSdk 36`

**决定**：升 compileSdk（`compileSdkMinor = 0`，因为 SDK 仓库里 37 只有 `android-37.0`/`android-37.1` 两个包名），targetSdk 不动。

**为什么**：compose-bom 2026.09.00（→ compose 1.12.1）与 lifecycle 2.11.0 的 AAR 元数据要求"compile against version 37 or later"，AGP 的建议动作就是升 37。**AAR 检查只管 compileSdk，运行时行为不该被一条依赖约束牵着走。**

证据：[app/build.gradle.kts:13-19](file:///D:/projects/piece/live-photo-batch/app/build.gradle.kts#L13)。

### 6.3 CI 不用 `android-actions/setup-android@v3`

**决定**：直接 `sdkmanager` 装 `build-tools;36.0.0` 与 `platforms;android-37.0`，其中 `platforms;android-37.0` **不允许失败**，备用名继续容忍。

**为什么**：该 action 默认 packages 里的 `tools` 早已被 Google 从 SDK 仓库删掉，sdkmanager 直接 exit 1（某次 run 的 apk job 在 Gradle 启动前就挂了）。而硬依赖装不上必须让这一步红，不能跟着循环一起被容忍 —— 否则后果只是 Gradle 在后面报一堆难读的 "no matching platform"。

证据：[build.yml:27-45](file:///D:/projects/piece/live-photo-batch/.github/workflows/build.yml#L27)。

### 6.4 仪器化测试只编译不执行

**决定**：CI 跑 `:app:assembleDebugAndroidTest` 但不在 CI 里跑它。

**为什么**：没有设备农场；`FileStorageTest` 是契约测试，人工在 vivo V2419A 上跑。

**代价**：端口实现与内存实现的分叉风险**不在 CI 的守备范围里** —— 见 [testing.md](testing.md)。

### 6.5 release 包由 CI 直接产出，签名沿用 debug key

**决定**：`apk` job 在 `assembleDebug` 之后追加 `:app:assembleRelease`，并上传 `vlive-release` 产物；不引入 keystore secret，release 仍走 `signingConfigs.getByName("debug")`。

**为什么**：这是一台设备上用的侧载工具，"能装、能覆盖 debug 包"就是需求；把密钥材料塞进仓库或 CI 变量换不来任何收益，反而多一处会泄漏的东西。要出正式签名版时再建 secret，工作流里只引用名字。

**代价**：release 产物**不可上商店**；`minifyEnabled = false`，所以它和 debug 包体积几乎一样（实测 23 MB），"release" 在这里只意味着不走 debug build type。

证据：[app/build.gradle.kts:31-34](file:///D:/projects/piece/live-photo-batch/app/build.gradle.kts#L31)、[.github/workflows/build.yml:46-55](file:///D:/projects/piece/live-photo-batch/.github/workflows/build.yml#L46)。

### 6.6 发版是一个手动按钮，tag 用日期加时分（2026-10-21 增量）

**决定**：新增 [`.github/workflows/release.yml`](file:///D:/projects/piece/live-photo-batch/.github/workflows/release.yml)，
只有 `workflow_dispatch` 一个触发器。它自己构建两个 APK，然后建 tag `YYYY-MM-DD-HHMM` 并发布 Release，
两个 asset 改名成 `vlive-debug-<tag>.apk` / `vlive-release-<tag>.apk`，标成 pre-release，
说明里带 commit、每个文件的字节数与 sha256、"CI 的 debug key 与本机不同、换装要先卸载"、
以及深色 splash 那条已知缺陷。

**时间按 `Asia/Shanghai` 算，不是 runner 的 UTC**：tag 是人用来区分两个包的字符串，
"这是几点出的"必须和用户手表一致，否则下午两点出的包会顶着凌晨的标签。

**为什么不跟 push 自动发**：一条 `feat/*` 分支一天能推十几次，每次 push 都产一个 Release 会把发布页
变成构建日志的垃圾桶；`build.yml` 的 artifact 本来就是"给人挑版本"的那一层。
**`concurrency` 刻意不 `cancel-in-progress`** —— 一次被掐在上传一半的 run 会留下半个 release，
比排队等它跑完更糟。

**为什么不引第三方 action**（`softprops/action-gh-release` 之类）：runner 自带 `gh`，
一条 `gh release create` 就够；多引一个 action 就多一个要 pin 版本、要盯更新的外部供应链面。

**可选的 `note` 输入走 `env`，不直接插进 shell**：`${{ inputs.note }}` 出现在 `run:` 里就是脚本注入的入口，
哪怕只有仓库写权限的人能按那个按钮。

**两条实测踩出来的**：
1. `POST /repos/…/releases` 的 `target_commitish` 给短 SHA 会被 422 拒
   （`tag_name is not a valid tag` + `target_commitish` invalid）—— 要给**完整 40 位**。
2. `gh release view --json htmlUrl` 没有这个字段（是 `url`），于是 **release 已经建好、步骤却红**
   （run 39532833851 实测：`https://github.com/yaleiyale/live-photo-batch/releases/tag/2026-10-21-1028`
   留下了，但 run 是 failure）。回读那一行现在读 `url`。

**跑通的证据**：run 39533390568 success → tag `2026-10-21-1033`，两个 asset 匿名 GET `HTTP 200`
且字节数与发布页一致。手工 API 那条路仍然写在 README 里当备用（按钮不可用、或要发一个
CI 之外的本机构建时）。

## 7. 已知技术债（下次动这片之前先读）

| 项 | 现状 | 建议 |
| --- | --- | --- |
| 代码里 **49 处 `spec §…` / `增补 …` 引用**指向已删除的设计稿 | 悬空引用（见下面的索引） | 要么把设计稿作为历史归档恢复并标"已废弃"，要么把 KDoc 引用改指本文档 |
| `ByteArraySource` 住在 `:core` 的 **main** sourceSet，只有测试用 | [Sources.kt:20](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/Sources.kt#L20) | 移进 test，或明确它是有意的公开测试工具 |
| `compose-ui-tooling` 在依赖表里但没有任何 `@Preview` | [libs.versions.toml:18](file:///D:/projects/piece/live-photo-batch/gradle/libs.versions.toml#L18) | 要么用起来，要么删依赖 |
| `PermissionGate.of(context)` 的 `context` 参数未使用，靠 `@Suppress("UNUSED_PARAMETER")` | [PermissionGate.kt:44-45](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/perm/PermissionGate.kt#L44) | 真需要按包名查询时再展开 |
| `IosMetrics.sectionGap` 在控件层内部没被使用（间距交给调用方） | [IosStyle.kt:195](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L195) | 收拢间距或删令牌 |
| 控件层散落魔法数字（开关尺寸、导航栏高、若干内边距） | [IosControls.kt:201-228](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt#L201) | 进 `IosMetrics` |
| 真机语料不在仓库，格式回归不可复现 | 只有合成夹具 | 加一份 golden 文件 + 断言测试 |
| 无计时/性能测试，"100 张 15 秒"只是注释里的动机 | [JpegStructure.kt:100-102](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/vlive/core/JpegStructure.kt#L100) | 要提速先测 |
| 图标观感、入场时序、系统关动画时是否归零 —— 全部未装机确认 | 只验到「资源进包 + 编译 + 205 条 JVM 测试绿（145 `:core` + 60 `:app`）+ lint」这一层 | 装机看一次：桌面图标（含圆形遮罩与主题图标）、冷启动 logo→内容的衔接 |
| `RollingLogTree` 与 `PreferencesBatchSettings` 没有自动化测试 | 测到的只有决策函数（`planLogCleanup`、`restore`/`save` 走的是内存假实现）；真的写文件与真的 DataStore 都要 `Context` | 要么补 `:app` 的仪器化测试（`androidTest` 里已经有一条 `FileStorageTest` 的先例），要么把「装机看 `run-as` 目录」固定成验收步骤 —— 现在选的是后者，见 testing.md 的缺口表 |
| lint 有 9 条 information 级基线，而且 **CI 不跑 lint** | 手动 `:app:lintDebug` 的结果；其中一条是[被迫保留的 `-v26`](#512--v26-这个多余的目录限定符不能删)（也是 `ObsoleteSdkInt` 唯一剩下的一条），两条是[刻意撤销的 monochrome 层](#511-图标与启动过渡2026-10-21-增量)，一条是 `report_summary` 的 `PluralsCandidate` **误报**（见 [§5.14](#514-文案全部进资源默认语言是英文2026-10-21-增量)） | 要收口就把 `lintDebug` 加进 CI 并逐条处置，别整类静音 |
| `:core` 的异常文案仍是**中文**，`err_batch_aborted` 会把 `e.message` 当参数拼进本地化句子 | 英文提示里会嵌一句中文（`BatchRunner` / `JpegXmpPatcher` / `XmpPacket` 那几条抛出都走这条路）。`MediaStoreSync` 那两条已经随日志层收敛到 `Timber`（[§5.18](#518-日志写进应用私有目录三条上限一条写线程2026-10-21-增量)），它们只进日志文件、不进 UI，所以不再是本地化缺陷 | 抛出消息属于「给开发者读」的边界：要么也改成英文（同 [§5.14](#514-文案全部进资源默认语言是英文2026-10-21-增量) 里 `ensureDir` 的处理），要么在 VM 侧只报枚举/类名而不报 `message` |
| `IosControls` 里仍有散装动效字面量（220/180ms）和一处非 lambda 的 `offset` | [IosControls.kt:199](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt#L199)、[:222](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt#L222) 与 [:290](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt#L290) | 令牌 [IosStyle.kt:138](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L138) 已就位，改这两处即可对齐 |
| **弹窗顶栏的副标题被 bar 自己的下边缘裁掉**（2026-10-22 装机实测，浅色深色、root 与下级都一样） | 标题列是 17.sp + 11.sp 两行、居中在 `sheetBarHeight = 52.dp` 里，超出部分被 `iosGlassBar` 的 `clip` 切平：`D:/tmp/em14_crop_bar.png`。位置 [IosControls.kt:611-647](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosControls.kt#L611)、[IosStyle.kt:223](file:///D:/projects/piece/live-photo-batch/app/src/main/kotlin/dev/zayn/vlive/ui/IosStyle.kt#L223) | 要么把 bar 抬到装得下两行（同时改列表那段 `top = barHeight` 留白），要么副标题只在单行高度里显示；装机时按 `D:/tmp/em14_crop_bar.png` 那种 3× 裁图复核 |
| **底部操作栏不糊之后，卡片文字从它底下透出来和按钮文字打架**（回退的已知代价，不是回归） | `iosGlassTray` 只画 tint，不再抹平底下的内容：`D:/tmp/em20_light_en_titlebar_in.png`、`em40_dark_en_scrolled.png`、`em71_tray_tap.png` 三种状态都拍到了 | 唯一允许的调法是 spec §1.3 那张预设表里的 `tint` alpha（调用点不许写散装 alpha）；改完必须重跑 §3.4 第 1/2 条并给出前后截图。本轮没改 |
| **高对比那一列 accent 至今没被任何设备渲染过**：API 够到了，信号没送到 | 模拟器 API 36 上把 `accessibility_high_text_contrast_enabled` 写成 1、冷启动后取样像素与 0 时逐点相同（见 [§5.19](#519-玻璃只给三条浮动-bar卡片与按钮不各自糊一层2026-10-21-增量) 的那四张图），这一版 Settings 也没有"高对比文字"这一行 | 换一台 ROM 真带这个开关的机器再验一次；要能在 CI 里钉住，就得把"读信号"抽成可注入的谓词（现在它是 `IosTheme` 里的一次 `getSystemService`） |

## spec 引用索引

代码 KDoc 里的 `spec` 引用现在指向**已删除**的 `docs/superpowers/specs/2026-10-17-live-photo-batch-android-design.md`（`38bd595` 退役）与其 plan（`52d0621` 删除）。下表把仍有效的 section 号映射到本文档体系；`as-built` 与旧设计稿冲突时**以代码 + 本文档为准**。

| KDoc 里的引用 | 讲的规则 | 现在写在哪 |
| --- | --- | --- |
| `spec §2`（含 `G5`） | 紧密封装 MUST、常数内存预算 | [容器规则 §3/§4](motion-photo-container.md#3-xmp-包产物里唯一的元数据)、性能未知项见 [system-model.md](architecture/system-model.md#未知与低置信区下一步要验的) |
| `spec §7.2` | JPEG 按 marker 遍历规则（FF 填充串、`FF00` 转义、`RSTn`） | [合成流水线 关口②](architecture/compose-pipeline.md#阶段一八道关口全过之前对-sink-一个字节不写) |
| `spec §7.3` | 源无 XMP 段时插在 SOI 之后 | [容器规则 §2](motion-photo-container.md#为什么尾巴必须留xmp-必须回原位) |
| `spec §7.4`（含 `.4/.5/.6`） | 校验顺序与输入约定 | [合成流水线 阶段一](architecture/compose-pipeline.md#阶段一八道关口全过之前对-sink-一个字节不写) + [容器规则 §1](motion-photo-container.md#1-命名与配对) |
| `spec §7.5` | `.part` 无条件清理、单项失败不中断批次 | [合成流水线 阶段三](architecture/compose-pipeline.md#阶段三三条出路且三条都执行-part-清理) |
| `spec §7.6` | 每个产物 + 每个已删原文件都必须到达媒体扫描 | [端到端 步 9 与四条路](architecture/batch-flow.md#主路径同步顺序单协程)、不变量见 [system-model.md](architecture/system-model.md#数据与状态) |
| `spec §9` | 全局前置条件 vs 单项失败；取消语义；面向用户的中文短语 | [端到端 四条路](architecture/batch-flow.md#失败与退出的四条路) + 本页 [§4.4](#44-全局前置失败与单项失败分两个通道) |
| `增补（2026-10-18）§1` | 只有视频形状的尾巴才算"已合成过" | [容器规则 §4](motion-photo-container.md#4-尾巴声明矩阵eoi-与视频之间不留一个没人声明的字节) |
| `增补（2026-10-18c）§1` | 尾巴必须搬进产物 | [容器规则 §2](motion-photo-container.md#2-字节布局) |
| `增补（2026-10-18c）§2` | XMP 清空重写 | [容器规则 §3](motion-photo-container.md#3-xmp-包产物里唯一的元数据) |
| `增补（2026-10-18d）§2/§3` | 每一段字节都要被声明；尾巴定性为 GainMap/Padding | [容器规则 §4](motion-photo-container.md#4-尾巴声明矩阵eoi-与视频之间不留一个没人声明的字节) |
| `增补（2026-10-19e）` | 容器项字段集合以规范正文为准，序列化形式不具规范性 | 本页 [§3.2](#32-字段集合按规范正文写厂商样本只当反例) |
| `Task N` 编号（散落在 KDoc） | 原实现计划的步骤号 | 已失效，仅作历史线索；不要再据它推断需求 |
