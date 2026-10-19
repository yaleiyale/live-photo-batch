workspace "live-photo-batch" "Current-state C4 model of the Android batch motion-photo composer. Skill: system-modeler + c4model. Every element and edge traces to code — see system-model.evidence.md." {
    !identifiers hierarchical

    model {
        # ---------- 系统边界之外 ----------
        operator = person "操作者" "侧载 APK、选源/目标目录、勾选「合成后删除原文件」、点开始或取消。"

        sharedStorage = softwareSystem "共享存储文件系统" "/storage/emulated/0 —— 读相机目录里的 IMG_*.jpg 与 IMG_*.mp4，产物与被删原文件都落在这棵树上。" {
            tags "External"
        }

        mediaStore = softwareSystem "MediaStore / 相册" "接收「产物 + 已删原文件」的路径扫描；vivo 相册据此把 _MP.jpg 当动态照片播放。" {
            tags "External"
        }

        settingsUi = softwareSystem "系统设置授权页" "ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION 的落点；vivo/OriginOS 上可能跳不到，只能回查 granted()。" {
            tags "External"
        }

        # ---------- 被建模的系统 ----------
        lpb = softwareSystem "live-photo-batch" "把同名 JPEG 与 IMG_*.mp4 拼成 Google Motion Photo 1.0 单文件动态照片：纯字节搬运 + 元数据重写，不解码、不转码、不重编码。" {
            core = container ":core (JVM library)" "构建单元，不是进程：编译成 JAR 进同一个 APK。无 android.* 依赖，因此 127 条测试不需要设备。核心逻辑全在这里。" "Kotlin JVM" {
                pairPlanner = component "PairPlanner" "一层目录同名配对。驱动集是视频：只有 IMG_*.mp4 去找同名 jpg/jpeg；_MP 结尾的图像不作输入候选；产物名 <stem>_MP.jpg。" "Kotlin object" {
                    tags "Core"
                }
                batchRunner = component "BatchRunner" "逐项状态机：plan/run、.part→rename→删原文件的提交顺序、取消只在项与项之间生效、单项失败不中断批次。" "Kotlin class" {
                    tags "Core"
                }
                batchStoragePort = component "BatchStorage + ChannelSource" ":core 认识的全部文件动作。路径是字符串、分隔符固定 /，所以 :core 不认识 java.io.File。" "Kotlin interface" {
                    tags "Port"
                }
                composer = component "MotionPhotoComposer" "合成入口。判据按便宜的先做排成一条线，被拒的项对 sink 一个字节不写；全程 64 KiB 定长缓冲、常数内存。" "Kotlin object" {
                    tags "Core"
                }
                jpegScanner = component "JpegScanner / JpegStructure" "按 marker 遍历 JPEG 得 imageEnd、旧 XMP 区间与文本、是否带扩展 XMP。绝不搜 FFD9 字节序列。" "Kotlin object" {
                    tags "Core"
                }
                patcher = component "JpegXmpPatcher" "纯区间搬运：源图[0,旧XMP起点) + 新 APP1 + 源图[旧XMP终点, trailerEnd)。旧段靠「读掉不写」丢弃。" "Kotlin object" {
                    tags "Core"
                }
                xmpPacket = component "XmpPacket" "产物元数据的唯一生成入口：清空白写，源包属性一个不掺；目录 Primary→(GainMap)→MotionPhoto。" "Kotlin object" {
                    tags "Core"
                }
                trailerClassifier = component "TrailerClassifier" "只问「EOI 之后的尾巴是不是上一轮追加的视频」：16 字节探针 + 源 XMP 信号。据此判 ALREADY_MOTION_PHOTO。" "Kotlin object" {
                    tags "Core"
                }
                trailerModel = component "TrailerModel" "只问「这段尾巴该被容器目录声明成什么」：副图→GainMap 整条，其余或畸形→主图 Padding 整条。对任何输入都不抛。" "Kotlin object" {
                    tags "Core"
                }
                motionDetector = component "MotionDetector" "源 XMP 是否已带动态照片语义；正则必须带 GCamera:/Item:Mime 命名空间前缀，否则普通照片会被误判。" "Kotlin object" {
                    tags "Core"
                }
                inMemoryStorage = component "InMemoryStorage (test)" "端口的第二个实现：让全部批次逻辑在无设备 JVM 上可测。只在 core 的 test sourceSet。" "Kotlin test fixture" {
                    tags "Test"
                }
            }

            app = container ":app (Android application)" "构建单元，不是进程：与 :core 一起打包成同一个 app-debug.apk，跑在同一个 Android 进程里。" "Kotlin / Compose / AGP 9 / minSdk 30" {
                activity = component "MainActivity" "唯一 Activity；MaterialTheme 包裹；onResume 回查授权。" "ComponentActivity" {
                    tags "App"
                }
                screen = component "BatchScreen + DirectoryPicker" "单屏 UI：权限提示、两个只读路径、危险开关、进度、逐项报告。目录读取在 Dispatchers.IO，绝不发生在组合期。" "Compose Material3" {
                    tags "App"
                }
                viewModel = component "BatchViewModel" "批次所有权：开工快照 → 全局前置校验 → plan/run → 逐项累积 UiState → NonCancellable 收尾扫描。" "ViewModel + StateFlow" {
                    tags "App"
                }
                permissionGate = component "PermissionGate" "MANAGE_EXTERNAL_STORAGE 门面；真值经注入的 () -> Boolean 每次现读，低于 API 30 短路放行。" "Kotlin" {
                    tags "App"
                }
                dirBrowser = component "DirBrowser" "真文件系统目录浏览；只列可读子目录、过滤点开头名，绝不抛只给空列表。" "java.io.File" {
                    tags "App"
                }
                fileStorage = component "FileStorage" "BatchStorage 的生产实现：RandomAccessFile + FileChannel、close 时 force(true)、move 走 rename(2) 且不先删目标。" "java.io.File" {
                    tags "App", "Adapter"
                }
                mediaStoreSync = component "MediaStoreSync" "一次性把全部路径交给 scanFile，最多等 2 秒；绝不抛，但把未确认回报给 UI。" "MediaScannerConnection" {
                    tags "App", "Adapter"
                }
            }
        }

        # ---------- 关系 ----------
        operator -> lpb "选目录、开始合成、取消"
        lpb -> sharedStorage "读 IMG_*.jpg / IMG_*.mp4，写 x_MP.jpg，可选删原文件" "java.io.File / FileChannel"
        lpb -> mediaStore "提交产物与已删原文件的绝对路径" "MediaScannerConnection.scanFile"
        lpb -> settingsUi "跳转「所有文件访问」授权页" "Intent"

        lpb.app.activity -> lpb.app.screen "setContent"
        lpb.app.activity -> lpb.app.permissionGate "requestIntent / onResume 回查"
        lpb.app.screen -> lpb.app.viewModel "读 StateFlow，发 start/cancel/set*"
        lpb.app.viewModel -> lpb.app.permissionGate "每次现读 granted()"
        lpb.app.viewModel -> lpb.app.dirBrowser "暴露给目录浏览弹窗"
        lpb.app.viewModel -> lpb.core.batchRunner "plan() 预览总数，再把同一份 PlanResult 交给 run()"
        lpb.app.viewModel -> lpb.app.fileStorage "构造 FileStorage(root)"
        lpb.app.viewModel -> lpb.app.mediaStoreSync "收尾扫描 touchedPathsFor(...)"
        lpb.app.dirBrowser -> sharedStorage "listFiles() 列可读子目录" "java.io.File"
        lpb.app.fileStorage -> sharedStorage "open / openWrite / rename / delete" "RandomAccessFile"
        lpb.app.fileStorage ..> lpb.core.batchStoragePort "implements（生产）"
        lpb.core.inMemoryStorage ..> lpb.core.batchStoragePort "implements（测试）"
        lpb.app.mediaStoreSync -> mediaStore "scanFile(paths) + 2 秒 latch"
        lpb.app.permissionGate -> settingsUi "granted() 的真值来源"

        lpb.core.batchRunner -> lpb.core.pairPlanner "source/target 两层列表 → jobs + unpaired"
        lpb.core.batchRunner -> lpb.core.composer "compose(image, video, sink)"
        lpb.core.batchRunner -> lpb.core.batchStoragePort "全部文件动作只走这个端口"
        lpb.core.composer -> lpb.core.jpegScanner "扫段得到 JpegStructure"
        lpb.core.composer -> lpb.core.trailerClassifier "尾巴是不是上一轮的视频"
        lpb.core.composer -> lpb.core.trailerModel "尾巴该声明成 GainMap 还是 Padding"
        lpb.core.composer -> lpb.core.xmpPacket "app1Segment(videoSize, gainMap, padding)"
        lpb.core.composer -> lpb.core.patcher "patch(structure, image, sink, app1, trailerEnd)"
        lpb.core.composer ..> lpb.core.batchStoragePort "只消费 ChannelSource / WritableByteChannel 类型，实例由 BatchRunner 从端口取"
        lpb.core.trailerClassifier -> lpb.core.motionDetector "源 XMP 是否已声明动态照片"
    }

    views {
        systemContext lpb "SystemContext" "L1：APK 之外只有三个系统 —— 共享存储、MediaStore、系统授权页。没有网络、没有服务端。" {
            include *
            autoLayout lr
        }

        container lpb "Containers" "L2：两个构建单元，一个进程。:core 与 :app 的边界是构建隔离（:core 无 android.*），不是部署边界。" {
            include *
            autoLayout lr
        }

        component lpb.core "CoreComponents" "L3（:core）：配对 → 批次状态机 → 合成流水线。核心逻辑所在；include * 会把端口与它的两个实现一起拉进来。" {
            include *
            autoLayout tb
        }

        component lpb.app "AppComponents" "L3（:app）：UI + 权限 + 目录浏览 + 存储/媒体两个适配器。" {
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
