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
  `x_live.jpg` 写入目标目录。
- G2 可选删除原文件：勾选后，在产物成功落盘的前提下删除这一对的 `x.jpg` 与 `x.mp4`。
- G3 冲突处理：目标目录已存在 `x_live.jpg` 时**覆盖**。
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
  PairPlanner        一层目录条目列表 -> List<PairJob>（同名配对、_live 命名、覆盖）
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

`x_live.jpg` 的字节 = `patchedImageBytes ++ videoBytes`，其中：

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
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999-02-22-rdf-syntax-ns#">
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
5. 输出名：`x.jpg` 去掉扩展名后加 `_live.jpg`。扩展名判定大小写不敏感（`.JPG`、`.Mp4` 都算），
   文件名主干（stem）严格区分大小写，与 Android 文件系统语义一致。
6. 自产物流水保护：stem 以 `_live` 结尾（大小写不敏感）的 JPEG 不作为本轮输入候选，避免把上一轮的
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
- 源目录与目标目录**可以是同一个**：产物名带 `_live` 后缀且不会作为输入候选，同目录原地生成安全。
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
6. `PairPlanner`：同名配对、缺对、大小写、`_live` 产物被剔除、非一层目录不被递归。
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

`SOI` + 新 XMP APP1 + `源图[2, imageEnd)` + **`源图[imageEnd, fileSize)`（trailer 原样）** + 视频字节。
`MicroVideoOffset` 与视频项 `Item:Length` 仍等于**追加的视频字节数**：该值从文件末尾倒推，与中间是否
夹着 gain map 无关，因此偏移语义不变。gain map 项的 `Item:Length` 保持源值不变（字节没动）。
项顺序固定为 Primary → GainMap → MotionPhoto(视频在最后)，符合 Google container 规范。

### 3. XMP 从「整体替换」改为「合并」

旧实现丢掉源 XMP 再写我们的模板，这会让 gain map 失去声明（字节还在 → 解码器可能误读或渲染错）。
新规则：

- 源无 XMP → 输出 §7.3 的模板（与现在一致）。
- 源有 XMP → 以源包为基底合并：保留 `hdrgm:` 等无关命名空间与属性、保留既有 `Container:Directory`
  的 Primary/GainMap 项、剔除任何旧的 `GCamera:*` 属性与 `Item:Semantic` 为 `MotionPhoto` 的项，
  然后追加我们的 MotionPhoto 项并写入 §7.3 要求的全部 `GCamera:*` 属性。
- 实现手段：JDK/Android 自带的 `javax.xml` DOM（不引新依赖，不手写正则改 XML）。
- 源 XMP 无法解析（畸形）→ 该项 `FAILED`，原因 `MALFORMED_XMP`（新增枚举值），不静默丢用户元数据。

### 4. 验收补充

- 真机：取 vivo 相机原片 + 同名 mp4 合成，产物必须（a）仍被系统相册当动态照片，
  （b）保留 gain map（对比合成前后同一张图在支持 HDR 的查看器里亮度一致），
  （c）XMP 同时声明 GainMap 与 MotionPhoto 两项。
- 单测必须覆盖：VIDEO 尾巴被跳过、gain map 尾巴被保留且 XMP 合并正确、厂商 JSON 尾巴被保留、
  零填充被保留、无 XMP 时模板不变、畸形 XMP 报 `MALFORMED_XMP`。
