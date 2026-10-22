workspace "vlive" "当前状态（as-built）的 C4 模型：Android 批量动态照片合成器。建模方式 system-modeler + c4model。每个元素与每条边都能追溯到代码，证据索引见 system-model.evidence.md。基准 ff9ad98 + 品牌/包名改到 vlive 的工作区增量，采集 2026-10-20、刷新 2026-10-21。" {
    !identifiers hierarchical

    model {
        # ---------- 系统边界之外 ----------
        operator = person "操作者" "侧载 APK、选源/目标目录、勾选「合成后删除原文件」、点开始或取消。"

        sharedStorage = softwareSystem "共享存储文件系统" "/storage/emulated/0 —— 读相机目录里的 IMG_*.jpg 与 IMG_*.mp4，产物、.tmp/*.part 与被删原文件都落在这棵树上。" {
            tags "External"
        }

        privateStorage = softwareSystem "应用私有目录" "/data/data/dev.zayn.vlive —— DataStore 设置（vlive.preferences_pb）与滚动日志（filesDir/logs）的落点：不需要任何存储权限，卸载即清，出问题时 adb shell run-as 可以取到。" {
            tags "External"
        }
        mediaStore = softwareSystem "MediaStore / 相册" "收尾接收「产物 + 已删原文件」的绝对路径扫描；vivo 相册据此把 x_MP.jpg 当动态照片播放。识别结果就是本项目的验收判据。" {
            tags "External"
        }

        settingsUi = softwareSystem "系统设置授权页" "ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION 的落点；vivo/OriginOS 上可能跳不到，只能回查 granted()。" {
            tags "External"
        }

        # ---------- 被建模的系统 ----------
        lpb = softwareSystem "live-photo-batch" "把同名 JPEG 与 IMG_*.mp4 拼成 Google Motion Photo 1.0 单文件动态照片：纯字节搬运 + 元数据重写，不解码、不转码、不重编码。" {

            # ========== :core ==========
            core = container ":core (JVM library)" "构建单元，不是进程：编译成 core.jar 链进同一个 APK。插件只有 kotlin.jvm，全量源码无 import android.*，因此 145 条测试不需要设备。字节层面的判断全部在这里。" "Kotlin 2.4.21 / JVM" {

                pairPlanner = component "PairPlanner" "一层目录的同名配对。驱动集是视频：只有 IMG_*.mp4 去找同名 jpg/jpeg；_MP 结尾的图像不作输入候选；产物名 <stem>_MP.jpg；jobs/unpaired 按 stem 码元自然序排序。" "Kotlin object" {
                    tags "Core"
                }
                batchRunner = component "BatchRunner" "逐项状态机：plan/run、.part → close → rename → 删原文件的提交顺序、取消只在项与项之间生效、单项失败不中断批次、三条出路都要清 .part。" "Kotlin class" {
                    tags "Core"
                }
                batchStoragePort = component "BatchStorage + ChannelSource" ":core 认识的全部文件动作。路径是字符串、分隔符固定 /，来源可反复 open（合成要读两遍），所以 :core 不认识 java.io.File。7 条方法级约定是接口契约。" "Kotlin interface" {
                    tags "Port"
                }
                composer = component "MotionPhotoComposer" "合成入口。六道关口按「便宜的先做」排成一条线，被校验拒掉的项对 sink 一个字节不写；全程 64 KiB 定长缓冲、常数内存，无一处 readBytes()。" "Kotlin object" {
                    tags "Core"
                }
                jpegScanner = component "JpegScanner / JpegStructure" "按 marker 遍历 JPEG 得到 imageEnd、旧 XMP 的字节区间与文本、是否带分块扩展 XMP。绝不搜 FFD9 字节序列（视频里合法地会出现）。64 KiB 预读 + rewindUnused() 把 channel 精确停在 imageEnd。" "Kotlin object" {
                    tags "Core"
                }
                trailerClassifier = component "TrailerClassifier" "只回答「EOI 之后的尾巴是不是上一轮追加的视频」：16 字节探针 + 源 XMP 信号，XMP 信号排在最前；ISO-BMFF 走盒时读 64 位扩展长度（vivo/OPPO 的 mdat 就是那个形态）。据此判 ALREADY_MOTION_PHOTO。" "Kotlin object" {
                    tags "Core"
                }
                trailerModel = component "TrailerModel" "只回答「这段尾巴该被容器目录声明成什么」：真副图 → GainMap 整条，其余一切形状（含遍历不收敛的畸形副图）→ 主图 Padding 整条。自带 BoundedCursor，对任何输入都不抛。" "Kotlin object" {
                    tags "Core"
                }
                motionDetector = component "MotionDetector" "源 XMP 是否已带动态照片语义。正则必须带 GCamera: / Item:Semantic 命名空间前缀，否则普通照片会被误判成已合成过而整批跳过。" "Kotlin object" {
                    tags "Core"
                }
                vivoTail = component "VivoLivePhotoTail" "把 vivo 相机 mp4 末盒里 com.android.camera.livephoto 的值加上 motionphoto 前缀 —— 这台机器相册的唯一判据（真机二分 2026-10-20）。看不懂一律返回 null、原样搬运，不伪造厂商结构；盒会变长，所以三处自洽长度必须同步。" "Kotlin object" {
                    tags "Core"
                }
                xmpPacket = component "XmpPacket" "产物元数据的唯一生成入口：清空白写，源包属性一个不掺。目录 Primary →（GainMap）→ MotionPhoto，字段集合按规范正文而非厂商样本。gainMap/padding 互斥用 check 守。" "Kotlin object" {
                    tags "Core"
                }
                patcher = component "JpegXmpPatcher" "纯区间搬运：源图[0, 旧XMP起点) + 新 APP1 + 源图[旧XMP终点, trailerEnd)。新段写回旧段原位而不是插在 SOI 后；旧段靠「读掉不写」丢弃。" "Kotlin object" {
                    tags "Core"
                }
                byteChannels = component "ByteChannels (writeAll)" "internal 扩展函数：write() 允许只消费部分缓冲，单次调用会写出短文件，所以所有写出都走这一个排空循环。" "Kotlin internal" {
                    tags "Core"
                }
                sources = component "ByteRange / ByteArraySource" "端口值类型与一个纯内存 ChannelSource 实现。注意 ByteArraySource 住在 main sourceSet，但当前只有测试在用。" "Kotlin" {
                    tags "Core"
                }
                inMemoryStorage = component "InMemoryStorage (test)" "端口的第二个实现：把整批编排搬到无设备的 JVM 上可测。只在 core 的 test sourceSet。" "Kotlin test fixture" {
                    tags "Test"
                }
            }

            # ========== :app ==========
            app = container ":app (Android application)" "构建单元，不是进程：与 :core 一起打包成单个 app-debug.apk，跑在同一个 Android 进程里。只提供六类适配器 —— 文件、权限、目录浏览、媒体扫描、设置持久化、日志 —— 加上 UI（UI 里含 2026-10-21 增量的那一层玻璃）。" "Kotlin / Compose / AGP 9.4.1 / Haze 2.0.1 / minSdk 31" {

                activity = component "MainActivity" "唯一 Activity。installSplashScreen() 在 super.onCreate 之前，退出动画放完才放行入场闸门；viewModelFactory 造 BatchViewModel；IosTheme 是浅/深唯一判定处；跳授权页失败退化成 Toast 指引（文案取资源）；onResume 回查授权。刻意不开 enableEdgeToEdge()。" "ComponentActivity" {
                    tags "App"
                }
                screen = component "BatchScreen + DirectoryPicker" "单屏 UI：权限提示、两个目录、危险开关、进度、逐项报告、全屏目录浏览弹窗。目录读取在 produceState + Dispatchers.IO，绝不发生在组合期；报告列表刻意不用 LazyColumn：整张列表默认折叠成一行（ReportCard），展开后一次只画一页（reportPage 那个纯函数定筛选与分页规则）。" "Compose（自绘控件）" {
                    tags "App"
                }
                uiKit = component "ui/IosControls + IosStyle + IosEntrance" "按 iOS「设置」分组列表自绘的控件层与令牌层：两份 IosPalette（浅/深）+ IosStyle（颜色与文字样式）+ IosMetrics / IosMotion（几何与时长）。IosTheme 一次判定 isSystemInDarkTheme，把同一套色板同时给 LocalIosStyle 与 MaterialTheme。图形用 Canvas 画，不引入位图资源；状态全部提升，内部无 value 状态。IosEntrance 是冷启动入场闸门。" "Compose" {
                    tags "App"
                }
                glassBar = component "ui/GlassBar + ui/TitleBarAlpha" "玻璃层（2026-10-21 增量）。真模糊只给两个面：主屏顶栏与目录弹窗顶栏，一条 Modifier.iosGlassBar 把顺序画完（clip 在 hazeBlur 之前 —— 背景糊完再按 shape 裁，才有那条干净的玻璃边缘；高光与描边叠在糊好的背景之上）；主屏底部操作栏在 2026-10-22 装机实测超预算之后按 spec §3.4 预先约定的回退改用同文件的 Modifier.iosGlassTray —— 层序一模一样，只把 hazeBlur 换成一条 background(glass.tint)。两条链上都没有 Modifier.shadow：它在层内预留一圈扩散区，会把玻璃切成一块白色方形色块、把条内按钮切出矩形补丁，2026-10-22 七台构建实测之后连同 GlassMaterial.shadowElevation 一起删掉，裁定见 docs/decisions.md §5.21。每扇窗口恰好一个 HazeState + 一个 hazeSource —— 主屏是那个内容层、弹窗是那条列表，因为 Dialog 是独立窗口、跨窗口取不到位图，于是全项目两个源、两个模糊消费者（底部那条只吃同一份预设，不吃模糊）。hazeBlur 落在 RenderEffect 上，要求 API 31：minSdk 就是为它从 30 抬上来的。卡片保持实心，条内按钮只加自己的 tint / 高光 / 描边，绝不各糊第二层（spec §1.1「一层玻璃只模糊一次」）。顶栏淡入由纯函数 titleBarAlpha(scrollY, collapseRange) 决定：恒在 0f..1f、线性、两端饱和，alpha > 0 才进组合，刻意不做动画（加了就落后于手指），且 alpha 以 State 传进 BatchScreen 的私有 GlassTitleBar 里读，滚动只无效化那一小块玻璃。两条贴边 bar 走默认 radius = 0.dp，只有底部那条显式传 IosMetrics.glassRadius。裁定见 docs/decisions.md §5.19。" "Compose Modifier + Haze 2.0.1（haze / haze-blur）/ RenderEffect" {
                    tags "App"
                }
                viewModel = component "BatchViewModel" "批次所有权：开工快照 → 全局前置校验 → plan/run → 逐项累积 UiState → NonCancellable 收尾扫描。root 字符串契约在这里与 FileStorage/DirBrowser 共享；权限与扫描器都是构造器注入的测试接缝。" "ViewModel + StateFlow" {
                    tags "App"
                }
                permissionGate = component "PermissionGate" "MANAGE_EXTERNAL_STORAGE 门面；真值经注入的 () -> Boolean 每次现读、不缓存。" "Kotlin" {
                    tags "App"
                }
                dirBrowser = component "DirBrowser + mergedRootEntries + displayPath" "真文件系统目录浏览：只列可读、非点开头的子目录，按 name.lowercase() 升序，绝不抛只给空列表。presets() 已删除 —— 显示名改由调用方用字符串资源拼出别名传入，两个纯函数（mergedRootEntries / displayPath）继续在本地 JVM 上直测。" "java.io.File" {
                    tags "App"
                }
                fileStorage = component "FileStorage" "BatchStorage 的生产实现：RandomAccessFile + FileChannel、close 时 force(true) 再 close、move 走 rename(2) 且不先删目标、exists/delete 一律先判 isFile。" "java.io.File" {
                    tags "App", "Adapter"
                }
                resLayer = component "res/values（语言 + 主题限定符）" "第二条通道：默认（未限定）配置是英文、中文在 values-zh；values-night 只管窗口级颜色与主题（isSystemInDarkTheme 管不到冷启动那一帧）。界面文案零字面量，globalError 在状态里是 MessageText(id, args)，到这里才按当前语言解析。" "Android resources"
                {
                    tags "App"
                }
                settingsStore = component "settings/BatchSettings" "目录选择的持久化：两方法接口（load/save），生产实现是 Preferences DataStore，写在应用私有目录。删除开关刻意不记；恢复时先过 isSelectable，并且不覆盖用户刚选的那一下。" "DataStore Preferences" {
                    tags "App"
                }
                logTree = component "log/RollingLogTree + planLogCleanup" "Timber 的落点：私有目录里的滚动日志文件。单文件 512 KB / 最多 4 个 / 合计 2 MB / 7 天，当前文件永不删；所有磁盘动作在 vlive-log 守护线程上，日志自己吞 IOException。" "Timber + java.io.File" {
                    tags "App"
                }
                application = component "VliveApp" "唯一的 Application：种两棵日志树（logcat 只在可调试构建 + 文件树）。不在 Activity 里种，因为 Timber 没种树时是静默丢弃。" "Application" {
                    tags "App"
                }
                mediaStoreSync = component "MediaStoreSync" "一次性把全部路径交给 scanFile，最多等 2 秒；绝不抛，但把「未确认」回报给 UI。必须从非主线程调用。" "MediaScannerConnection" {
                    tags "App", "Adapter"
                }
            }
        }

        # ---------- L1 关系 ----------
        operator -> lpb "选目录、勾选删原文件、开始合成、取消"
        lpb -> sharedStorage "读 IMG_*.jpg / IMG_*.mp4，写 x_MP.jpg，可选删原文件" "java.io.File / FileChannel"
        lpb -> mediaStore "提交产物与已删原文件的绝对路径" "MediaScannerConnection.scanFile"
        lpb -> settingsUi "跳转「所有文件访问」授权页" "Intent"

        # ---------- L3 关系：:app ----------
        lpb.app.activity -> lpb.app.screen "setContent(vm, request)"
        lpb.app.activity -> lpb.app.uiKit "IosTheme { }：同一套色板同时给自绘层与 MaterialTheme"
        lpb.app.activity -> lpb.app.permissionGate "requestIntent()；回查由 vm.refreshPermission()"
        lpb.app.activity -> lpb.app.viewModel "by viewModels { initializer { ... } }"
        lpb.app.screen -> lpb.app.viewModel "collectAsState 读 UiState；发 start/cancel/setSource/setTarget/setDeleteOriginals"
        lpb.app.screen -> lpb.app.uiKit "SectionCard / DetailRow / SwitchRow / StatusRow / IosSheet / IosButton / ThinProgressBar"
        lpb.app.screen -> lpb.app.glassBar "内容层是本窗口的 hazeSource；主屏顶栏（私有 GlassTitleBar，alpha 以 State 传下去、不做动画）与底部操作栏各挂一条 iosGlassBar，底部那条显式传 glassRadius"
        lpb.app.uiKit -> lpb.app.glassBar "IosSheet 的顶栏是第二个模糊面：弹窗是 Dialog（独立窗口，取不到主屏位图），所以那条列表自己注册第二个 HazeState + hazeSource；LightGlass / DarkGlass 两套预设与 glassRadius / hairline 令牌由令牌层供给"
        lpb.app.screen -> lpb.app.dirBrowser "DirectoryPicker 在 Dispatchers.IO 上调 subdirs()；别名与 root 标签来自字符串资源"
        lpb.app.viewModel -> lpb.app.permissionGate "granted() 每次现读"
        lpb.app.viewModel -> lpb.app.dirBrowser "val browser = DirBrowser(root) 暴露给 UI"
        lpb.app.viewModel -> lpb.app.fileStorage "runBatch 里构造 FileStorage(root)"
        lpb.app.viewModel -> lpb.app.mediaStoreSync "finally 里提交 touchedPathsFor(...)"
        lpb.app.viewModel -> lpb.app.settingsStore "init 里 restore()（先过 isSelectable）；改目录时 save()"
        lpb.app.viewModel -> lpb.app.logTree "Timber.i/w：批次边界、非成功项、删除失败"
        lpb.app.application -> lpb.app.logTree "onCreate 里 Timber.plant 两棵树"
        lpb.app.settingsStore -> privateStorage "vlive.preferences_pb"
        lpb.app.logTree -> privateStorage "filesDir/logs/log-<ms>.txt"
        lpb.app.viewModel -> lpb.core.batchRunner "plan() 出总数，再把同一份 PlanResult 原样交给 run()"
        lpb.app.dirBrowser -> sharedStorage "listFiles() 列可读子目录" "java.io.File"
        lpb.app.fileStorage -> sharedStorage "open / openWrite / renameTo / delete" "RandomAccessFile"
        lpb.app.mediaStoreSync -> mediaStore "scanFile(paths) + 2 秒 CountDownLatch"
        lpb.app.permissionGate -> settingsUi "Environment.isExternalStorageManager() 的真值来源"
        lpb.app.screen -> lpb.app.resLayer "stringResource / pluralStringResource；别名表由 root + 资源拼出"
        lpb.app.uiKit -> lpb.app.resLayer "values-night 的窗口级颜色与 Compose 色板必须是两套配置、一个设计"

        # ---------- L3 关系：:core ----------
        lpb.core.batchRunner -> lpb.core.pairPlanner "source/target 两层列表 → jobs + unpaired"
        lpb.core.batchRunner -> lpb.core.composer "compose(image, video, sink)"
        lpb.core.batchRunner -> lpb.core.batchStoragePort "list / open / openWrite / exists / ensureDir / move / delete 只走这个端口"
        lpb.core.composer -> lpb.core.jpegScanner "扫段得到 JpegStructure"
        lpb.core.composer -> lpb.core.trailerClassifier "尾巴是不是上一轮的视频"
        lpb.core.composer -> lpb.core.trailerModel "尾巴该声明成 GainMap 还是 Padding"
        lpb.core.composer -> lpb.core.vivoTail "plan(video) 决定末盒改不改、改完多少字节"
        lpb.core.composer -> lpb.core.xmpPacket "app1Segment(videoBytes, gainMapBytes, paddingBytes)"
        lpb.core.composer -> lpb.core.patcher "patch(structure, image, sink, app1, trailerEnd = fileSize)"
        lpb.core.composer -> lpb.core.byteChannels "追加视频与重写后的末盒"
        lpb.core.patcher -> lpb.core.byteChannels "写出新 APP1 与每块搬运的字节"
        lpb.core.trailerClassifier -> lpb.core.motionDetector "源 XMP 是否已声明动态照片"
        lpb.core.composer ..> lpb.core.batchStoragePort "只消费 ChannelSource / WritableByteChannel 类型，实例由 BatchRunner 从端口取"
        lpb.app.fileStorage ..> lpb.core.batchStoragePort "implements（生产）"
        lpb.core.inMemoryStorage ..> lpb.core.batchStoragePort "implements（测试）"
    }

    views {
        systemContext lpb "SystemContext" "L1：APK 之外只有三个系统 —— 共享存储、MediaStore、系统授权页。没有网络、没有服务端、没有数据库。" {
            include *
            autoLayout lr
        }

        container lpb "Containers" "L2：两个构建单元、一个进程。:core 与 :app 的边界是构建隔离（:core 无 android.*），不是部署边界。" {
            include *
            autoLayout lr
        }

        component lpb.core "CoreComponents" "L3（:core）：配对 → 批次状态机 → 合成流水线。核心逻辑所在；include * 会把端口与它的两个实现一起拉进来。" {
            include *
            autoLayout tb
        }

        component lpb.app "AppComponents" "L3（:app）：UI + 自绘控件层 + 玻璃层（两个模糊面 + 一条不糊的 tray）+ 权限 + 目录浏览 + 存储/媒体两个适配器。" {
            include *
            autoLayout lr
        }

        styles {
            element "External" {
                background #999999
                color #ffffff
            }
            element "Core" {
                background #1f6f5c
                color #ffffff
            }
            element "App" {
                background #2a5d9f
                color #ffffff
            }
            element "Port" {
                background #6a4c93
                color #ffffff
            }
            element "Adapter" {
                border #d4a017
                strokeWidth 3
            }
            element "Test" {
                background #cccccc
                color #333333
                border #999999
            }
        }
    }
}
