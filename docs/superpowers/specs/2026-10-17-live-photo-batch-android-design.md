# live-photo-batch (Android) 设计文档

日期：2026-10-17
状态：已与用户逐节确认，待用户复核本文
参考实现：`wszqkzqk/live-photo-conv` 的合成侧核心逻辑（`src/livemaker.vala`）

## 1. 背景与问题

`live-photo-conv` 的 Android 版把整套桌面 GTK4 / LibAdwaita / GStreamer 栈塞进 APK。它在 vivo
V2419A 上先是冷启动即 SIGABRT（glib 请求 `PTHREAD_INHERIT_SCHED`，厂商在冷启动阶段把主线程提到
`SCHED_FIFO | SCHED_RESET_ON_FORK`，bionic 重放策略需要 app 不具备的 `CAP_SYS_NICE`，64 位上
`pthread_create` 回传 EPERM，glib 直接 `g_error` abort）。启动问题可用
`pthread-restrictions.patch` 绕过并且构建成功，但实际用起来运行性能差，且合成导出没有成功。

合成这件事本身不需要视频解码，也不需要任何 GUI 框架：它的实质是"复制一张 JPEG（换掉其中的
XMP 段）+ 把 MP4 原始字节整段追加到文件尾"。因此本设计用 Kotlin 原生重写一个只做合成的
Android app，去掉分离、抽帧和厂商私有格式支持。

## 2. 目标

- G1 批量：选择源目录，把其中**文件名完全相同、仅扩展名不同**的 `x.jpg` 与 `x.mp4` 合成
  `x_MP.jpg` 写入目标目录。
- G2 可选删除原文件：勾选后，在产物成功落盘的前提下删除这一对的 `x.jpg` 与 `x.mp4`。
- G3 冲突处理：目标目录已存在 `x_MP.jpg` 时**覆盖**。
- G4 产物被 vivo V2419A 系统相册识别为动态照片，并且写完即可在相册里看到。
- G5 性能预算：100 对典型素材（JPEG 约 4 MB + MP4 约 8 MB，合计约 1.2 GB）在 V2419A 上
  15 秒内完成，进程内存峰值不超过 32 MB。该预算在验收任务里用真机实测数字确认。

## 3. 非目标

分离（live photo → 图 + 视频）、只用 MP4 抽首帧当主图、HEIC/AVIF 主图、vivo/小米/OPPO 私有
标记、子目录递归、视频转码或校验内容、撤销、批量以外的手动单文件配对 UI、云备份、MediaStore
之外的媒体库集成。

## 4. 验收标准

1. 真机（V2419A，Android 16/API 36）：合成 3 对素材后，在系统相册中对应条目显示为动态照片，
   长按/播放能看到运动画面；文件在合成完成后无需重启相册即出现在列表中。
2. 勾选"删除原文件"后：源 `x.jpg` 与 `x.mp4` 从磁盘消失，且相册中不再残留指向它们的条目。
3. 对"源 JPEG 本身已是动态照片"的输入：不产出嵌套文件，该项在报告中被标为跳过并说明原因。
4. `core` 模块单元测试全部通过，且不需要连接设备即可在 CI 中运行。

## 5. 技术选型

| 决策 | 选择 | 理由与否决项 |
| --- | --- | --- |
| 存储访问 | `MANAGE_EXTERNAL_STORAGE`（所有文件访问）+ `java.io.File` | 直接路径语义清晰、`FileChannel` 零拷贝最省事、删除原文件无需 MediaStore 所有权。**否决** SAF 目录树（每文件一次 ContentProvider 往返，正是"性能差"的来源之一）；**否决** MediaStore Only 方案（拿不到任意目录，也无法自由删除）。代价：只能侧载，不能上 Google Play。 |
| XMP/JPEG 处理 | 自研纯 JVM 段编辑器 | `androidx.exifinterface` 不支持 XMP；引 exiv2/jexif 的 JNI 又把原生依赖拉回来。**否决**。 |
| UI | Jetpack Compose，单屏 | 只有一个页面，声明式代码量最小。 |
| 并发 | 协程 `Dispatchers.IO` 顺序处理 | 瓶颈是磁盘吞吐，并发不增产反而抢 IO；顺序执行让进度与报告可解释。 |
| SDK | `minSdk 30`，`targetSdk 36`，`compileSdk 36` | 所有文件访问自 Android 11 起存在。 |
| 语言/构建 | Kotlin 2.x + Gradle (Kotlin DSL)，两个模块：`:core`（纯 JVM）与 `:app` | `:core` 可脱离设备测试。 |

## 6. 架构与模块边界

```
:core  (纯 JVM，无 android.* 依赖)
  JpegStructure      扫描 JPEG 段 -> SegmentList(imageEnd, xmpIndex, exifIndex, ...)
  XmpPacket          生成/替换 XMP APP1 payload（模板固定）
  JpegXmpPatcher     删除旧 XMP APP1、在 SOI 后插入新 APP1，其余段原样保留
  MotionPhotoComposer  compose(image, video, sink): ComposeResult
  NestedMotionPhotoDetector  判断输入是否已是动态照片
  PairPlanner        一层目录条目列表 -> List<PairJob>（同名配对、_MP 命名、覆盖）
  BatchRunner        顺序推进 + 进度流 + 取消 + 逐项报告
  model DirEntry     (name: String, size: Long) —— 目录枚举由 :app 提供，:core 不碰文件系统
:app   (Android)
  PermissionGate     isExternalStorageManager / 跳转授权页 / 状态回显
  FileSystemGateway  java.io.File + FileChannel 适配 :core 的通道接口
  MediaStoreGateway  MediaScannerConnection.scanFile 收敛索引
  BatchScreen        路径选择、删除勾选、开始/取消、进度、报告列表
```

`:core` 不碰文件系统：它只收发 `ReadableByteChannel` / `WritableByteChannel`，目录枚举由 `:app`
以 `List<DirEntry>` 交给 `PairPlanner`。因此全部算法可在 JVM 单测里用内存字节夹具和假目录列表验证。
`:app` 是唯一持有 `android.*` 类型和 `java.io.File` 的地方。

## 7. 合成规格（规范性）

### 7.1 输出布局

`x_MP.jpg` 的字节 = `patchedImageBytes ++ videoBytes`，其中：

- `patchedImageBytes`：源 JPEG 的 SOI、紧随其后的新 XMP APP1、源文件其余全部段（EXIF/ICC/
  量化表/帧数据等原样复制，不重编码）、直到源 JPEG 真正的 EOI。
- `videoBytes`：源 MP4 的完整原始字节，长度记为 `videoSize`。

`MicroVideoOffset` 与 `Item:Length` 都等于 `videoSize`（不是 `patchedImageBytes.size`）；读取方
用 `fileSize - offset` 反推视频起点，这与上游 `livephoto.vala` 的 `get_video_offset()` 语义一致。

### 7.2 JPEG 结构解析

按段遍历，不允许"搜 `FFD9` 字节"这种判定：JPEG 二进制数据里合法地会出现 `FFD9`，追加的视频字节里
更会出现。遍历规则：从 `FFD8` 起，读 `FF <marker>`，对带 2 字节长度字段的 marker 按长度跳过；
遇到 `SOS`（`FFDA`）后进入熵编码数据，扫描其间的 `FFD9` 以外的 marker 直到 EOI（`FFD9`）结束。
`imageEnd` = EOI 之后一个字节的位置。

### 7.3 XMP 段处理

- 若存在 `FFE1` 且 payload 以 `http://ns.adobe.com/xap/1.0/` + `0x00` 开头：删除该段。
- 若存在 `FFE1` 且 payload 以 `http://ns.adobe.com/xmp/extension/` 开头（分块扩展 XMP）：该项**失败**，
  原因记为 `EXTENDED_XMP_UNSUPPORTED`，不做处理。
- 新 XMP APP1 插入位置：SOI 之后、其他所有段之前。
- APP1 payload = `http://ns.adobe.com/xap/1.0/` + `0x00` + UTF-8 XMP 包。段长度字段受 65535 上限
  约束，我们的包远小于此，仍要在生成时断言长度 `<= 65533`。

XMP 包模板（`<VIDEO_SIZE>` 处填 `videoSize`，时间戳固定 `-1`）：

```xml
<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
      GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1"
      GCamera:MotionPhotoPresentationTimestampUs="-1"/>
  <rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
      GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1"
      GCamera:MicroVideoOffset="<VIDEO_SIZE>"
      GCamera:MicroVideoPresentationTimestampUs="-1"/>
  <rdf:Description xmlns:Container="http://ns.google.com/photos/1.0/container/">
   <Container:Directory>
    <rdf:Seq>
     <rdf:li xmlns:Item="http://ns.google.com/photos/1.0/container/item/" rdf:parseType="Resource">
      <Item:Mime>image/jpeg</Item:Mime>
      <Item:Semantic>Primary</Item:Semantic>
     </rdf:li>
     <rdf:li xmlns:Item="http://ns.google.com/photos/1.0/container/item/" rdf:parseType="Resource">
      <Item:Mime>video/mp4</Item:Mime>
      <Item:Semantic>MotionPhoto</Item:Semantic>
      <Item:Length><VIDEO_SIZE></Item:Length>
     </rdf:li>
    </rdf:Seq>
   </Container:Directory>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>
```

字段集合逐项对应 `src/livemaker.vala` 第 171-192 行。Vala 里
`set_tag_string(".../Container:Item", "type=Struct")` 是 GExiv2 建立结构节点的写法，不是最终
XML 文本，因此不进入模板。

### 7.4 输入校验与嵌套防护

按顺序判定，任一失败即记入报告且**不产出文件**：

1. `x.jpg` 头两字节必须是 `FFD8`，否则 `NOT_JPEG`。
2. 段遍历能正常收敛到 EOI，否则 `MALFORMED_JPEG`。
3. `imageEnd < fileSize(x.jpg)`（EOI 后有残留字节）**或**源 XMP 含 `GCamera:MicroVideoOffset` /
   `GCamera:MotionPhoto` → `ALREADY_MOTION_PHOTO`，跳过。
   （本条已被文末「增补（2026-10-18，真机验收后）」§1 取代：只有**视频形状**的尾巴才算已合成过，
   非视频尾巴原样保留；新增 `MALFORMED_XMP`。）
4. `x.mp4` 起始 12 字节内必须出现 `ftyp`，否则 `NOT_MP4`。
5. 输出名：`x.jpg` 去掉扩展名后加 `_MP.jpg`。扩展名判定大小写不敏感（`.JPG`、`.Mp4` 都算），
   文件名主干（stem）严格区分大小写，与 Android 文件系统语义一致。
6. 自产物流水保护：stem 以 `_MP` 结尾（大小写不敏感）的 JPEG 不作为本轮输入候选，避免把上一轮的
   产物当成本轮的源。

### 7.5 原子落盘与删除

写入 `<目标目录>/.tmp/<name>.part`，`flush` + `force(true)` 后 `rename` 到最终名（同目录 rename
才是原子的；因此临时目录放在目标目录内）。覆盖语义即"rename 直接替换已有同名文件"。只有 rename
成功后，若勾选了删除原文件，才依次删除 `x.jpg`、`x.mp4`；删除失败不影响本项成功状态，但记为
`DELETE_FAILED` 警告。取消标志在**当前项 rename 之后**生效，之后不再开始新项。

### 7.6 MediaStore 收敛

每项成功后：`scanFile([产物绝对路径])`；若发生删除：把两个源路径一并加入同一次 `scanFile` 调用，
让被删条目的索引消失。批处理结束后再对整批路径做一次补扫，避免逐项 scan 的排队丢帧。

## 8. 数据流

```
用户选源目录/目标目录 → PairPlanner 列一层目录并配对
  → BatchRunner 逐项：
      FileSystemGateway.open(image) / open(video)
      MotionPhotoComposer.compose(imageChannel, videoChannel, tmpChannel)
      → rename → (可选) delete 源 → MediaStoreGateway.scan
      → emit ItemResult(SUCCESS | SKIPPED(reason) | FAILED(reason))
  → UI 聚合：进度(done/total/bytes)、取消按钮、三类结果列表
```

## 9. 错误处理原则

- 单项失败不中断批次，逐项报告并继续。
- 全局前置失败直接拒绝开始：未授予所有文件访问、源目录不存在或不可读、目标目录无法创建。
- 源目录与目标目录**可以是同一个**：产物名带 `_MP` 后缀且不会作为输入候选，同目录原地生成安全。
- 磁盘写满（`IOException` 且非取消）记为 `IO_ERROR`，批次继续，UI 在末尾汇总失败计数。
- 所有面向用户的失败原因用中文短语呈现，同时保留机器可读枚举名。

## 10. 测试策略

`:core` JVM 单测（无需设备，CI 必跑）：

1. `JpegStructure`：对构造的最小 JPEG 断言 `imageEnd`；对 EOI 后追加随机含 `FFD9` 字节的数据仍
   断言出正确的 `imageEnd`（证明不靠搜字节）。
2. `JpegXmpPatcher`：无 XMP / 已有 XMP / 已有扩展 XMP 三种输入；断言输出段顺序为
   SOI→XMP→其余原样，且 EXIF 与 ICC 字节完全不变。
3. `XmpPacket`：快照测试，字段与模板逐字符比对，`MicroVideoOffset` 与 `Item:Length` 等于传入值。
4. `MotionPhotoComposer`：round-trip——合成后用 `fileSize - MicroVideoOffset` 反解视频起点，断言
   该处起 `ftyp` 且尾部字节与源 MP4 逐字节相等。
5. 嵌套防护：输入已是 motion photo 时返回 `ALREADY_MOTION_PHOTO` 且不写任何字节。
6. `PairPlanner`：同名配对、缺对、大小写、`_MP` 产物被剔除、非一层目录不被递归。
7. `BatchRunner`：取消时机（rename 之后停）、逐项报告分类计数、删除仅在 rename 成功后发生。

`:app` 仪器化冒烟测试：`PermissionGate` 状态判断；`FileSystemGateway` 在真临时目录写读一致。

真机验收清单（V2419A）：第 4 节四条，外加记录 100 对素材的实际耗时与内存峰值，对照 G5 预算。

## 11. 构建与 CI

- 仓库：GitHub 上 `yaleiyale/live-photo-batch`，本地 `D:\projects\piece\live-photo-batch`。
- GitHub Actions：`workflow_dispatch` + `push` + `pull_request` 触发。本仓库是独立仓库而非 fork，
  `push` 事件可正常派发（`yaleiyale/live-photo-conv` 那个 fork 上 `push`/`pull_request` 被静默忽略，
  只有 `workflow_dispatch` 有效，实测于 2026-10-17；保留手动入口以防同类问题）。job1 `./gradlew :core:test`（纯 JVM，快速反馈）；
  job2 `./gradlew assembleDebug` 并上传 APK 工件。真机安装仍由本地 adb 完成。
- 签名：debug keystore（自用侧载），不引入 release 签名密钥管理。

## 12. 风险与未决项

- **R1 权限模型演进**：`MANAGE_EXTERNAL_STORAGE` 在 Android 15/16 上持续收紧（对
  `Android/data` 等路径的访问限制）。本项目只碰公共媒体目录（DCIM/Movies/Download），实测确认。
- **R2 vivo 授权路径**：OriginOS 的"所有文件管理"授权页可能与原生
  `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` 不同；若跳转失败，退化为提示用户到设置里手动
  开启，并在 UI 显示当前授权状态。实现时真机确认。
- **R3 相册识别的最终判定**：本设计假定"Google Motion Photo 规范产物即可被该机型识别"，依据是用户
  已用 Windows 版产物在本机验证过。真机验收若失败，则回到"逆向 vivo 私有标记"的新立项。
- **R4 幽灵条目**：`scanFile` 对已删除路径的索引收敛行为在不同厂商 ROM 上不一致，需实测；若残留，
  改为先通过 MediaStore 按 `_data` 删除行再扫描。
- **R5 rename 语义**：SD 卡/特殊文件系统上跨目录 rename 或覆盖可能失败。目标固定为内部共享存储，
  且临时目录与产物同目录，实测确认。


---

## 增补（2026-10-18，真机验收后）：源 JPEG 的尾巴策略

真机验收发现：vivo V2419A 的 `DCIM/Camera` 里每张 JPEG 在 EOI 之后都有字节，抽样 8 张的结果是
6 张 HDR gain map（XMP 声明 `Item:Semantic="GainMap"` + `Item:Length`）、1 张 399 字节的
`vivo{...}` + `cameralbum!<UUID>` 厂商记录、1 张 47 字节零填充。原 §7.4.3「EOI 后有任何残留即判为
已是动态照片」会让用户的全部真实输入被跳过，功能等于不可用。用户裁定如下，取代 §7.4.3 的第 3 条。

> **2026-10-18b 再取代**：本节 §1（尾巴分类作为「已是动态照片」的判据）继续有效；§2「保留 trailer」、
> §3「合并并保留 Container 项」、§4「必须保住 gain map」三条被下面的同名小节整体替换。原因：按 §1-§3
> 产出的文件真机**不被 vivo 相册识别**，而参考语料（vivo 自己认可的合成结果）既不保留 trailer，
> 也不声明任何 Container 项。用户裁定「目标是做到和这个路径的合成结果一致，只是 app 的带 live 后缀」。

### 1. 尾巴分类（只有视频尾巴才算「已是动态照片」）

- `VIDEO`（跳过，报 `ALREADY_MOTION_PHOTO`）满足任一：
  - 位于 `imageEnd` 处的起始字节构成合法的 ISO-BMFF box：前 4 字节为大端长度 `N`，
    `4 <= N <= 剩余字节数`，且第 5-8 字节 ∈ {`ftyp`,`moov`,`mdat`,`free`,`skip`,`wide`,`pnot`}；
    或 `imageEnd` 起前 12 字节内出现 `ftyp`；
  - 源 XMP 文本含命名空间限定的 `GCamera:MicroVideoOffset`、`GCamera:MotionPhoto`、
    `Item:Semantic>MotionPhoto`，或 `Container` 里存在 `Item:Mime` 为 `video/*` 的项。
- `OTHER`（放行，字节原样保留）：gain map（以 `FFD8` 开头）、厂商 JSON/文本尾巴、零填充等。
- `NONE`：`imageEnd == 文件大小`。

### 2. 保留 trailer 时的产物布局

（2026-10-18b 起作废，见文末同名小节 §2。）

### 3. XMP 从「整体替换」改为「合并」

（2026-10-18b 起作废，见文末同名小节 §3。旧实现丢掉源 XMP 再写模板会让 gain map 失去声明的顾虑已消解：
产物不再携带副图字节，所以也不需要声明副图项。）

### 4. 验收补充

（2026-10-18b 起作废，见文末同名小节 §4。原 (b)「保留 gain map」与 (c)「同时声明 GainMap 与
MotionPhoto 两项」被推翻——参考语料两者都不做。）

---

## 增补（2026-10-18b，对照 vivo 已识别语料）：产物形态收敛

参考语料 = `D:\file\图像\carl的vivo X200 Pro mini+6665a2\图片\相机\合成图片文件夹` 里 17 张
**vivo 相册正常当动态照片**的合成结果；同名源片与 mp4 在上一级 `<stem>(动态图)/` 目录里。
本节逐字节验证了 3 张（`IMG_20261015_191555`、`IMG_20261018_093558`、`IMG_20261016_125806`），
取代上一节 §2、§3、§4；上一节 §1（尾巴分类）继续作为「已是动态照片」的判据。

### 1. 实测到的参考形态

| stem | 源 jpg | 源 imageEnd | 源 trailer | 产物 | 产物 imageEnd | 产物 trailer | MicroVideoOffset |
|---|---|---|---|---|---|---|---|
| 191555 | 5,025,864 | 4,583,514 | 442,350 | 5,516,857 | 1,652,930 | **0** | 3,863,927 = mp4 |
| 093558 | 3,894,226 | 3,466,139 | 428,087 | 1,476,734 | 1,054,759 | **0** | 421,975 = mp4 |
| 125806 | 8,347,228 | 7,286,943 | 1,060,285 | 11,967,974 | 2,972,768 | **0** | 8,995,206 = mp4 |

- 产物里 **EOI 之后紧跟 mp4 的第一个字节，中间零字节**；`文件大小 - MicroVideoOffset == imageEnd`
  三张全部成立。源片那 40 万~106 万字的 gain map 副图在产物里**不存在**——vivo 自己的合成把它丢了。
- 段的先后顺序保持源片的样子：`SOI` → `APP0(JFIF)` → **EXIF `APP1`（42 KB）** → **XMP `APP1`** →
  量化表/帧头/熵数据 → `FFD9`。XMP 不在 EXIF 之前。源片本身也是 EXIF 在前、XMP 在后。
- 产物的 XMP 包是**一个** `rdf:RDF`、动照片属性全在**一个** `rdf:Description` 上，
  `<Container:Directory>` 里是**空的 `<rdf:Seq/>`**：既不再声明 gain map 项，也不声明视频项，
  读取方靠 `GCamera:MicroVideoOffset` 定位视频。摘录（191555，包体 2,813 字节，尾段是 Exiv2 的
  空格填充，属于可选装饰）：

  ```xml
  <rdf:Description rdf:about="" xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
      xmlns:Container="http://ns.google.com/photos/1.0/container/"
      xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
      hdrgm:Version="1.0"
      GCamera:MicroVideoVersion="1" GCamera:MicroVideo="1"
      GCamera:MicroVideoOffset="3863927" GCamera:MicroVideoPresentationTimestampUs="-1"
      GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1"
      GCamera:MotionPhotoPresentationTimestampUs="-1">
    <Container:Directory><rdf:Seq/></Container:Directory>
  </rdf:Description>
  ```

  七属性（四 `MicroVideo*` + 三 `MotionPhoto*`）与 §7.3 一致；参考语料两组都写，两组都保留。
  `x:xmptk` 是写入方签名（语料里是 `XMP Core 4.4.0-Exiv2`），不属于形态要求：本 app 保留源值。

### 2. 产物布局：EOI 之后只放视频

取代上一节 §2。

```
SOI + 源图[2, 旧XMP起点) + 新 XMP APP1 + 源图[旧XMP终点, imageEnd) + 视频字节
```

- `imageEnd` 之后的源字节**一律不搬**（gain map、厂商记录、零填充都丢掉）。分类器只保留一件事：
  `TrailerKind.VIDEO` → `ALREADY_MOTION_PHOTO` 跳过；`OTHER`/`NONE` 一律放行并丢弃尾巴。
- 新 XMP APP1 **写回旧 XMP 段原来的位置**。上一节实现把它插在 SOI 之后，于是产物里 XMP 跑到了 EXIF
  前面，与参考语料相反；源没有 XMP 时仍插在 `SOI` 之后（此时它前面最多只有一个 `APP0`）。
- 包与 APP1 都不携带 `Container` 视频项，`MicroVideoOffset` 取 `videoSize`：偏移量与文件布局之间
  没有循环依赖，`文件大小 - MicroVideoOffset == imageEnd` 是产物自身必须成立的可断言等式。
- `JpegXmpPatcher.patch` 的 `trailerEnd` 参数删除：不再有"越过 EOI 还要搬字节"的调用方。

代价与裁定：产物丢掉 gain map → HDR 源片在相册里按 SDR 显示。用户裁定「目标是做到和这个路径的合成
结果一致，只是 app 的带 live 后缀」，而参考语料本身就不带 gain map；两个源文件仍在源目录里
（除非勾选删除原片），信息没有丢。**放弃**上一节 §4 的「保住 gain map」验收项。

### 3. XMP：单根、单 Description、空 Directory

取代上一节 §3。合并规则重写为四条，全部可在单测里断言：

1. **只有一个 `rdf:RDF`**：取 `<x:xmpmeta>` 下第一个 `rdf:RDF` 作宿主，一个都没有才新建并追加。
   上一节实现实测产出**两个兄弟 `rdf:RDF`**——源包被掏空成只剩 `hdrgm:Version="1.0"` 的空壳留在
   第一个 RDF 里，我们的三组声明挂在第二个 RDF 里。XML 仍然合法所以解析不报错，但按
   `xmpmeta/rdf:RDF/rdf:Description` 取属性的读取方（相册、Exiv2）只会看到那个空壳 → 等于没声明。
   根因是 `XmpPacket.RDF_NS` 当年把 URI 抄成了 `1999-02-22`（短横线）：命名空间对不上，源片那个 RDF
   就匹配不到，我们才在它旁边另起一个。语料的源片与产物用的都是标准 RDF 命名空间
   `http://www.w3.org/1999/02/22-rdf-syntax-ns#`（逐字节验过），常量必须与它一致；
   `XmpMergerTest` 的真实 vivo 源包夹具就是这条的回归用例。
2. **动照片七属性只写在一个 `rdf:Description` 上**：宿主 = 携带 `Container:Directory` 的那个
   Description；没有 Directory 就用第一个 Description；一个 Description 都没有才新建。其余
   Description 上的七属性（`MOTION_PHOTO_ATTRIBUTES`）一律剔掉，不再新建第二组。
3. **`Container:Directory` 只有一个，且 `rdf:Seq` 为空**：把宿主上已有的 Directory 替换成
   `<Container:Directory><rdf:Seq/></Container:Directory>`；源里的项一律丢弃——vivo 把项写成
   `<Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/>`，语义与 MIME 是一个
   `Container:Item` **子元素上的属性**，上一节实现按「`Item:Semantic` 子元素的文本」去找，
   永远找不到，于是源项既没被识别也没被重建。副图字节不再进产物、视频项无需声明，空 Seq 即参考形态。
   其他位置重复出现的 Directory 同样丢弃。
4. **其余内容一概不动**：源包里任何命名空间的属性与子元素（`hdrgm:*`、`dc:*`、`photoshop:*`、
   厂商命名空间、非 `Container:Directory` 的元素）原样保留；`x:xmptk` 保留源值，源没有就缺省。

源无 XMP（或整段只是空白）→ 输出一个**与合并产物同形**的包：单 RDF、单 Description、七属性、
空 `Container:Directory`。§7.3 的旧模板（三组 Description + 两项 `rdf:li`）作废，
`XmpPacket.motionPhoto()` 与合并路径共用同一个构造器，两条路径不得产出两种形状。

实现手段仍是 `javax.xml` DOM（不引依赖、不用正则改 XML）+ 上一节那套确定性序列化：`XmpMerger` 的
`Writer`、`MAX_DEPTH`、实体解析与 `MalformedXmpException` 全部保留，只换上面四条规则。

### 4. 验收补充

取代上一节 §4。

- **fixture 用真实 vivo 包**：上一节 §3 那份 957 字节 APP1（`http://ns.adobe.com/xap/1.0/` + NUL 起头、
  无 `<?xpacket?>`、`Container:Item` 属性式、`hdrgm:Version` 与 Directory 同居一个 Description）。
  它就是 `XmpMerger` 的回归用例——上一节实现被它产出了两个 `rdf:RDF`。
- 合并产物的**形状断言**（重解析后检查，不比字符串）：
  (a) `documentElement` 名下 `rdf:RDF` 子元素恰好 1 个；
  (b) 七属性只出现在 1 个 `rdf:Description` 上，且该 Description 是 `Container:Directory` 的父节点；
  (c) 那个 Directory 的 `rdf:Seq` 没有 `rdf:li` 子元素；
  (d) `GCamera:MicroVideoOffset` 等于 `videoSize`；
  (e) 源的 `hdrgm:Version` 仍在。
- 布局断言：`产物大小 - MicroVideoOffset == 产物 imageEnd`，且 `imageEnd` 起前 12 字节内出现 `ftyp`；
  EOI 与视频起点之间零字节；EXIF `APP1` 仍在 XMP `APP1` 之前（源里有的话）。
- 幂等：本 app 的产物再合成一次必须报 `ALREADY_MOTION_PHOTO`（尾巴是视频形状）；把合并产物再喂给
  合并器，字节必须相同。
- **真机判据（本轮唯一成功标准）**：vivo 相册把 `<stem>_MP.jpg` 当动态照片。
- 明确放弃：产物保留 gain map；`Container` 里声明 MotionPhoto 视频项。


---

## 增补（2026-10-18c，真机单变量对照）：尾巴保留 + XMP 清空重写

上一节的裁定来自"照着被识别的语料比形态"，但语料是别人（Exiv2 + 重编码）产出的，形态一致不等于
判据一致。这次改成**在真机上做单变量对照**：同一对源片（`IMG_20261015_191555`，源 jpg 5,025,864 +
mp4 3,863,927）、同一份 XMP 包、同一个视频，只改一个变量，推进 `Pictures/LivePhoto/` 让 vivo 相册判。

| 候选 | 主图字节 | EOI 后的尾巴 | XMP 包 | 相册判定 |
|---|---|---|---|---|
| `..._A_live.jpg`（8,889,891） | 源 JPEG **整文件** | **保留** 442,350 字节 gain map | 清空重写（下面那份） | **认，能播** |
| `..._B_live.jpg`（6,375,403） | ffmpeg 重编码的干净 JPEG | 无（视频紧贴 EOI） | 与 A 同一份 | 不认 |
| app 的 2026-10-18b 产物（8,447,407） | 源 JPEG 截到 EOI | 无 | 以源包为基底合并（单 RDF、空 Seq、留 hdrgm） | 不认 |
| app 的 Task 13 产物（8,890,749） | 源 JPEG 整文件 | 保留 | 以源包为基底合并（实测两个兄弟 `rdf:RDF`） | 不认 |

A 与 B 只差"主图是不是源片原样 + 尾巴在不在"，包完全相同 → **尾巴必须保留**（重编码反而坏事）。
A 与 Task 13 只差 XMP 包 → **包必须清空重写**，不能把源包的声明掺进来。两条同时成立才被认。

### 1. 产物布局（取代 2026-10-18b §2）

```
源 JPEG 整文件（SOI 到文件末，含 imageEnd 之后的 gain map 副图/厂商记录/零填充，原样一个字节不改）
    其中旧 XMP APP1 原位换成新 XMP APP1
  + 视频原始字节
```

- 这就是桌面端 `livemaker.vala` 的 `export_with_main_image()`：`write_main_image()` 对受支持的
  JPEG 走 `Utils.write_stream(main_input_stream, output_stream)` 整文件拷贝，然后
  `live_file.append_to(...)` 追加视频。Android 侧一一对应。
- `GCamera:MicroVideoOffset` 与视频项的 `Item:Length` 都等于 `videoSize`（从文件末尾倒推），
  与中间夹多少尾巴无关。A 实测：`文件大小 - 3,863,927 == 视频起点`，而 EOI 在更前面 442,350 字节。
- 2026-10-18b §2 的"截到 EOI、丢掉尾巴"作废；`JpegXmpPatcher` 的 `trailerEnd` 参数恢复，
  合成器再传源文件大小。**保留** 2026-10-18b §2 的另一半：新 XMP 写回旧段的原位（EXIF 仍在前）。
- gain map 因此回到产物里，HDR 不丢——上一节记下的那条代价撤销。

### 2. XMP：清空重写，不合并（取代 2026-10-18b §3）

桌面端 `export()` 在写标签前先 `this.metadata.clear_xmp()`。Android 侧照做：**源 XMP 一个属性都不带进
产物**（`hdrgm:Version`、`dc:*`、厂商命名空间、源片的 `Container:Directory` 项全部丢掉），只写下面这份。
Exif APP1 与其余段仍按源字节原样保留（A 就是这么做的，42 KB Exif 一字未动）。

整包逐字节照抄（`<VIDEO_SIZE>` 两处都填 `videoSize`；这就是真机认下来的那份，1,024 字节）：

```xml
<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 7.0-106">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
    GCamera:MicroVideo="1"
    GCamera:MicroVideoVersion="1"
    GCamera:MicroVideoOffset="<VIDEO_SIZE>"
    GCamera:MicroVideoPresentationTimestampUs="-1"
    GCamera:MotionPhoto="1"
    GCamera:MotionPhotoVersion="1"
    GCamera:MotionPhotoPresentationTimestampUs="-1">
   <Container:Directory>
    <rdf:Seq>
     <rdf:li><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/></rdf:li>
     <rdf:li><Container:Item Item:Semantic="MotionPhoto" Item:Mime="video/mp4" Item:Length="<VIDEO_SIZE>"/></rdf:li>
    </rdf:Seq>
   </Container:Directory>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>
```

三个必须照抄的形状要点，都是上一版栽过的地方：
1. **一个** `rdf:RDF`、**一个** `rdf:Description`，七属性与 `Container:Directory` 同居其上；
2. 容器项写成 `<Container:Item Item:Semantic="…" Item:Mime="…" Item:Length="…"/>`——语义与 MIME 是
   **`Container:Item` 这个子元素上的属性**（与 vivo 源包同形），不是 `Item:Mime` 子元素的文本；
3. 项顺序 Primary → MotionPhoto，视频项在最后。

时间戳取 `-1`（桌面端会先读源包里已有的 `MotionPhotoPresentationTimestampUs`/
`MicroVideoPresentationTimestampUs`，能解析成整数就沿用，否则 `-1`）。本 app 的输入是 vivo 原片，
源包里没有这两个标签，读与不读结果都是 `-1`；实现按固定 `-1` 走，不做那次读取（YAGNI，
且读源包正是上一版掺脏东西的入口）。

### 3. 代码后果

- `XmpMerger`（DOM 解析、`Writer`、深度上限、实体防护、`MalformedXmpException`）**整体删除**：
  不再解析源 XMP，就没有需要防护的不可信 XML。包由 `XmpPacket.motionPhoto(videoSize)` 一处生成。
- `FailureReason.MALFORMED_XMP` 随之删除（不再有产生它的路径）。
- `TrailerClassifier` 留着，但只用于 `ALREADY_MOTION_PHOTO` 判据（`TrailerKind.VIDEO`）；
  它读源 XMP 文本只是为了认出"上一轮已经追加过视频"，与写包无关。
- 源 XMP 文本仍由 `JpegStructure.oldXmpText` 提供，消费方只剩分类器。

### 4. 验收补充（取代 2026-10-18b §4）

- 单测必须钉住：产物 = 源文件字节 + 视频字节，且 `imageEnd` 之后那段尾巴**逐字节在产物里**；
  新 XMP 落在旧 XMP 原来的位置、EXIF 仍在它之前；产物大小 - `MicroVideoOffset` == 视频起点，
  且该处 12 字节内出现 `ftyp`；包与 §2 那份**逐字节相同**（只有两处 `<VIDEO_SIZE>` 随长度变）；
  源包里有 `hdrgm:`/`dc:`/厂商属性时，产物包里**一个都不出现**。
- 真机判据（决定性，唯一成功标准）：vivo 相册把 `<stem>_MP.jpg` 当动态照片。
- 上一节"产物必须丢掉 gain map"的要求作废。

---

## 增补（2026-10-18d，对齐 Google Motion Photo 1.0）：目录必须声明每一段字节

依据：Android 官方 [Motion Photo format 1.0](https://developer.android.com/media/platform/motion-photo-format)。
用户裁定「完全按照 Google 规范来」。这一节只改 **XMP 目录的内容**，不改 2026-10-18c 的字节布局
（源 JPEG 整文件 + 尾巴保留 + 视频追加），也不改「清空重写、不掺源包属性」那条规则。

### 1. 规范里咬得住我们的四条

1. 媒体项**必须**按目录顺序排列且**紧密封装**（tightly packed）——任何落在两项之间、没被声明的字节都是违规。
2. 主图是 Ultra HDR 的动态照片**必须**含 `Item:Semantic="GainMap"` 的项，且该项**必须**排在视频项之前。
3. `Mime` 与 `Semantic` 每项必填；`Length` 对**次要项必填**；主图项的 `Length` 若写则应为 `0`（本规范不要求写）。
4. `Padding` 只允许出现在**第一项**，语义是"已编码主图结尾与下一媒体项之间的额外字节"。

vivo 源片自己是第 2 条的模范生：目录 `[Primary, GainMap]`，`Item:Length="371541"`，主图 XMP 带
`hdrgm:Version="1.0"`。但它自己**不满足第 1 条**：副图 JPEG 之后还有 387 字节 `streamdataDEGS…`
厂商记录没被任何项声明（092349：尾巴 371,928 = 副图 371,541 + 387）。

### 2. 目录规则（取代 2026-10-18c §2 的包内容，布局不变）

先给 `imageEnd` 之后那段尾巴定性，再按定性生成目录：

| 尾巴是什么 | 目录项 | 附带 |
|---|---|---|
| 空（`imageEnd == 文件大小`） | `Primary` → `MotionPhoto` | 无 `Padding`、无 `hdrgm` |
| 一张 **gain map**（`FFD8` 起头，内部段里出现 `urn:iso:std:iso:ts:21496-1` 或 `http://ns.adobe.com/hdr-gain-map/1.0/`） | `Primary` → `GainMap` → `MotionPhoto` | `GainMap` 的 `Item:Length` = **整条尾巴的字节数**；主图 XMP 写 `hdrgm:Version="1.0"` |
| 另一张 JPEG 但**不是** gain map（MPF 副图、连拍缩略图等），或厂商记录/零填充 | `Primary` → `MotionPhoto` | `Primary` 写 `Item:Padding="<尾巴字节数>"` |

**为什么 `GainMap` 的 `Length` 要含那 387 字节厂商记录**：规范只给第一项 `Padding`，副图与视频之间的
空隙没有任何合法字段可以声明。写 `Length=371541`（副图 JPEG 自身的诚实长度）就必然留下 387 字节未声明，
直接违反第 1 条 MUST；写 `Length=371928`（整条尾巴）则两项之间零空隙、每个字节都有归属，
而 JPEG 解码器读到自己的 `FFD9` 就停，多出来的尾巴对它无害。二者只能选一，选合规的那个。
诊断日志里要同时留下诚实长度（`371541`），便于人核对。

`Item:Semantic` 的顺序固定 `Primary → GainMap → MotionPhoto`；`MotionPhoto` 项必须是最后一项，
其后不得再有字节（现状已满足）。`GCamera` 前缀保留：规范的默认前缀是 `Camera`，但前缀只是标签，
URI 才是语义，且 Pixel 自己的文件写的就是 `GCamera`。四个 `MicroVideo*` 属性保留：规范明确它们
"已从本规范删除，若存在必须被忽略"，而 vivo 恰恰只读它们。

### 3. 尾巴定性要可测试、可解释

新增 `TrailerModel`（`:core`）：输入 `imageEnd`、文件大小与可重开的 `ChannelSource`，输出
`object Description { gainMapBytes: Long?, paddingBytes: Long?, innerJpegBytes: Long? }`。

- 尾巴为 0 → 全 null。
- 头两字节不是 `FFD8` → `paddingBytes = 尾巴长度`。
- 是 `FFD8` → 从 `imageEnd` 起做一次**流式** marker 遍历（常数内存、64 KiB 缓冲，规则同 §7.2：
  按段长度跳、进 SOS 后只认非 `FF00`/非 `RSTn` 的 marker），得到副图自身的 `innerJpegBytes`；
  遍历途中检查每个 `APPn` 载荷里有没有 `urn:iso:std:iso:ts:21496-1` 或 `hdr-gain-map`：
  命中 → `gainMapBytes = 尾巴长度`（含厂商记录），否则 → `paddingBytes = 尾巴长度`。
- 遍历不收敛（畸形副图）→ 按 `paddingBytes` 处理，**不报错**：尾巴能不能被声明不影响产物合法，
  但如果它其实是张坏图，声明成 GainMap 才是真骗人。

`TrailerClassifier`（`ALREADY_MOTION_PHOTO` 判据）与 `TrailerModel` 是两件事，不许合并：前者只问
"这尾巴是不是上一轮追加的视频"，后者问"这段字节该被声明成什么"。

### 4. 验收补充

- 包必须与 §2 规则逐条对应，且**每个字节都被某个项声明**：单测断言
  `Primary.Padding(若有) + (GainMap.Length 若有) + MotionPhoto.Length + 主图编码长度 == 文件大小`。
  这条等式是"紧密封装"的可执行形式。
- 三种尾巴（空 / gain map / 非副图的 JPEG 或厂商记录）各一条包断言；`hdrgm:Version` 只在
  gain map 那条里出现。
- `Item:Length` 只出现在 `GainMap` 与 `MotionPhoto` 两项上，`Padding` 只出现在 `Primary` 上。
- 畸形副图（`FFD8` 起头但遍历不收敛）→ 降级成 `Padding`，不抛异常。
- 真机判据不变：vivo 相册认作动态照片。这条**每次改包都要重跑**，因为上一轮的判定就是包决定的。

### 5. 文件名：`_live` 改 `_MP`（用户裁定）

规范 §"File name pattern" 建议（SHOULD）作者的文件名匹配
`^([^\s\/\][^\/\]*MP)\.(JPG|jpg|JPEG|jpeg|HEIC|heic|AVIF|avif)` —— 扩展名前以 `MP` 收尾，
Pixel 自己的产物就是 `IMG_xxxxMP.jpg`。原裁定用的 `_live` 不匹配这条，用户改判为 `_MP`。

- `PairPlanner.OUTPUT_SUFFIX = "_MP"`，产物名 `<stem>_MP.jpg`。
- **自产物流水保护必须跟着大小写无关**：`stem.lowercase().endsWith(OUTPUT_SUFFIX.lowercase())`。
  常量本身从 `_live` 变成带大写的 `_MP`，再拿小写化后的 stem 去 `endsWith` 一个含大写的后缀，
  永远返回 false —— 那条保护会**静默失效**，上一轮产物会被当成本轮的新输入再合成一遍。
  单测 `the output guard ignores case` 钉这个：`a_MP.jpg` 与 `a_mp.jpeg` 都不许进候选，
  同时留一个 `b.jpg`（真缺视频）作反证，防断言空转。

---

## 增补（2026-10-19）：配对以视频为驱动集，只认 `IMG_*.mp4`

取代 §7.4.5/§7.4.6 里"以 JPEG 找同名 MP4"的方向，以及 §2/G1 与 §6 中"同名配对"的措辞。

真机第一批 54 项里 **28 项是 `NO_PAIR`**，全部是"照片没有配对视频"。这不是异常，是常识：相机目录里
绝大多数 JPEG 就是普通照片。以 JPEG 驱动会让每个普通照片都产一条跳过记录，报告被噪声填满，
用户看不出任何值得看的东西。方向反过来：

1. **驱动集是视频**。只有 `.mp4` 会去找同名 JPEG。没有配对视频的 JPEG **连候选都不是**，
   不产作业、不产跳过记录、不进任何报告。
2. **视频必须以 `IMG_` 开头**（大小写不敏感）。相机目录里还躺着录屏、下载的短视频、聊天工具存进来的
   mp4，它们不是动态照片的素材。`IMG_<时间戳>.mp4` 是 vivo/Google/小米相机的共同形状。
   前缀忽略大小写，**stem 匹配仍然严格区分大小写**（Android 文件系统语义，与 §7.4.5 一致）：
   `IMG_003.jpg` 配不上 `img_003.mp4`，那对视频会报"缺图"。
3. 报告里唯一剩下的配对异常是 **`Missing.IMAGE`**（有 `IMG_*.mp4` 却找不到同名图像）。
   `Missing.VIDEO` 从此不可达，**枚举值删除**，不留兼容分支。
4. 自产物流水保护（stem 以 `_MP` 结尾的图像不作输入）**只对图像侧有意义**，且必须两侧都
   `lowercase()` 比较：后缀本身含大写，漏掉一边就是静默失效。视频侧不需要这条——我们从不产出
   `_MP.mp4`；真有人手写一个，它会作为"缺图"出现在报告里，看得见比被静默吞掉好。

`source == target`（原地生成）依旧安全：产物名带 `_MP` 且不作为图像候选。

代码后果：`PairPlanner.plan` 的候选收集与主循环都改成视频驱动；`Missing` 只剩 `IMAGE`；
`BatchRunner` 的 NO_PAIR 预扫描逻辑不变（它消费 `plan.unpaired`，只是来源换了方向）。
