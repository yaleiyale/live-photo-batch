# 产物格式：Motion Photo 1.0 容器规则（as-built）

- **回答的问题**：`x_MP.jpg` 里到底有哪些字节，每一段的长度字段该填什么，相册凭什么认它。
- **读者**：要改字节层的人 / agent，以及在真机上排查"为什么相册不认"的人。
- **范围**：**当前实现实际写出的形状**（基准 `52d0621`，2026-10-20）。代码 KDoc 里剩下的 `spec §…` / `增补 …` 引用指向已删除的设计稿；冲突时以本文 + 代码为准，映射表在 [decisions.md](decisions.md#spec-引用索引)。
- **一句话**：不解码、不转码、不重编码 —— 源 JPEG 整文件搬进产物，只换掉那一段 XMP，视频原始字节追加在末尾，然后用一份清空白写的 XMP 把每一段字节都声明出来。

## 1. 命名与配对

| 规则 | 值 | 依据 |
| --- | --- | --- |
| 驱动集 | **只有视频驱动**：`IMG_` 前缀（大小写不敏感）+ `.mp4` 才去找同名图像 | [PairPlanner.kt:69](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/PairPlanner.kt#L69)、[:82-83](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/PairPlanner.kt#L82) |
| 图像候选 | `.jpg` / `.jpeg`（大小写不敏感） | [PairPlanner.kt:77-81](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/PairPlanner.kt#L77) |
| 排除 | stem 以 `_MP` 结尾（大小写不敏感）的**图像**不作输入候选 → 上一轮产物不会被再吃一遍 | 同上 |
| 产物名 | `<stem>_MP.jpg` | [PairPlanner.kt:97](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/PairPlanner.kt#L97) |
| 为什么是 `_MP` | Motion Photo 1.0 建议文件名匹配 `^([^\s/\][^/\]*MP)\.(JPG|jpg|JPEG|jpeg|HEIC|heic|AVIF|avif)`，即扩展名前以 `MP` 收尾，与 Pixel 自己的 `IMG_xxxxMP.jpg` 同形 | [PairPlanner.kt:58-63](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/PairPlanner.kt#L58) |
| 为什么以视频驱动 | 相机目录里绝大多数 JPEG 是普通照片。以 JPEG 驱动时它们每个都产一条"缺视频"的跳过记录（真机第一批 54 项里 28 项是这种噪声）；没视频就不是动态照片，这是常识不是异常 | [PairPlanner.kt:24-29](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/PairPlanner.kt#L24) |
| 为什么必须有 `IMG_` 前缀 | 相册目录里还躺着录屏、下载的短视频、微信存下来的 mp4，它们不是动态照片 | [PairPlanner.kt:64-69](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/PairPlanner.kt#L64) |
| 排序 | stem 的**自然序**（`String.compareTo`，逐 16 位码元、与 locale 无关）→ `'B' < '_' < 'a'`，且 `IMG_10` 排在 `IMG_2` 之前（相册顺序因此不等同于拍摄顺序） | [PairPlanner.kt:51-56](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/PairPlanner.kt#L51) |
| 同 stem 两张候选 | 取**列表中第一个**，另一张静默丢弃（`a.jpeg` 排在 `a.jpg` 之前） | [PairPlanner.kt:44-47](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/PairPlanner.kt#L44) |
| 原地生成 | `source == target` 安全：产物名带 `_MP` 且不作图像候选 | [PairPlanner.kt:48-51](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/PairPlanner.kt#L48) |

## 2. 字节布局

```
┌──────────────────────────── 产物 x_MP.jpg ────────────────────────────┐
│ SOI FFD8                                                            │
│ 源图 [2, 旧XMP起点)          ← APP0 / EXIF APP1 / ICC / … 原样        │
│ 新 XMP APP1 (FF E1 + len + "http://ns.adobe.com/xap/1.0/" + NUL + 包)│
│ 源图 [旧XMP终点, 文件大小)    ← 其余段 + 熵数据 + EOI FFD9 + 尾巴      │
│ 视频字节（MP4 原始，前 verbatimBytes 原样 + 可能的重写末盒）           │
└─────────────────────────────────────────────────────────────────────┘
     ↑ imageEnd = EOI 之后一个字节的位置        ↑ 尾巴 = [imageEnd, 文件大小)
```

三条硬规则：

1. **源 JPEG 整文件进产物**，包括 `imageEnd`（EOI）之后的 HDR gain map 副图与厂商记录。新 XMP 写回**旧 XMP 段原来的位置**，所以源片里排在 XMP 之前的 `EXIF APP1` 仍然在它之前（[MotionPhotoComposer.kt:28-34](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposer.kt#L28)、[JpegXmpPatcher.kt:8-30](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegXmpPatcher.kt#L8)）。
2. **`GCamera:MicroVideoOffset` == 追加的视频字节数 == `MotionPhoto` 项的 `Item:Length`**。它是从文件末尾倒推的，与中间夹了多少尾巴无关：`文件大小 − MicroVideoOffset == 视频起点`，而 `imageEnd` 在更前面（[MotionPhotoComposer.kt:34-35](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposer.kt#L34)）。
3. **唯一的例外**是 vivo 相机末盒被改写时：那两处长度填的是**改写后实际写出的字节数** `Rewrite.totalBytes`，不是 `video.size()`（[MotionPhotoComposer.kt:113-122](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposer.kt#L113)）。

### 为什么尾巴必须留、XMP 必须回原位

真机单变量对照（同一个包、同一个视频，只变一个因素）：

| 变量 | 结果 |
| --- | --- |
| 保留源片尾巴（那张 442,350 字节的 gain map 副图） | vivo 相册**认**；副图留在产物里还顺带保住了 HDR |
| 截到 EOI | **不认** |
| 重编码过的主图 | **不认** |
| XMP 插在 SOI 之后（把 XMP 挤到 EXIF 前面） | **不认** —— vivo 相册与 Exiv2 按 `EXIF APP1 在前、XMP APP1 在后` 的形状读元数据 |
| 从源包合并（带上 `hdrgm:`、`dc:`、厂商命名空间） | **不认** —— 必须清空白写 |

证据位置：[JpegXmpPatcher.kt:20-30](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegXmpPatcher.kt#L20)、[MotionPhotoComposer.kt:31-33](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposer.kt#L31)、[XmpPacket.kt:27-32](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/XmpPacket.kt#L27)。

**源片没有 XMP 段时**没有"原位"可回，按规范插在 SOI 之后（[JpegXmpPatcher.kt:57-61](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegXmpPatcher.kt#L57)）。这个形状**没有参考实现可比**（语料的源片都带 XMP），所以它的真机可识别性尚未验证 —— 别把它当已验证路径。

## 3. XMP 包：产物里唯一的元数据

生成入口只有一个：[XmpPacket.motionPhoto()](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/XmpPacket.kt#L73)。**清空重写** —— 源 XMP 里的 `hdrgm:*`、`dc:*`、厂商命名空间和源片自己的 `Container:Directory` 项一个都不掺进来。

形状（三个要点，`XmpPacketTest` 拿夹具字面量逐字节钉死，当前 1,057 字节）：

```xml
<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 7.0-106">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999-02-22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
    xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
    xmlns:Container="http://ns.google.com/photos/1.0/container/"
    xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
    [xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"   ← 仅当尾巴是副图
     hdrgm:Version="1.0"                                   ← 同上
    ] GCamera:MicroVideo="1" GCamera:MicroVideoVersion="1"
    GCamera:MicroVideoOffset="<视频字节数>"
    GCamera:MicroVideoPresentationTimestampUs="-1"
    GCamera:MotionPhoto="1" GCamera:MotionPhotoVersion="1"
    GCamera:MotionPhotoPresentationTimestampUs="-1">
   <Container:Directory><rdf:Seq>
    <rdf:li><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"
             Item:Length="0" Item:Padding="<尾巴或 0>"/></rdf:li>
    [<rdf:li><Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg"
             Item:Length="<整条尾巴>"/></rdf:li>   ← 仅当尾巴是副图]
    <rdf:li><Container:Item Item:Semantic="MotionPhoto" Item:Mime="video/mp4"
             Item:Length="<视频字节数>"/></rdf:li>
   </rdf:Seq></Container:Directory>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>
```

- **一个** `rdf:RDF`、**一个** `rdf:Description`：七个动照片属性与 `Container:Directory` 同居其上。
- 容器项写成 `Container:Item` 上的**属性**（与 vivo 源包同形），不是 `<Item:Semantic>` 子元素文本。
- 项顺序 `Primary →（GainMap）→ MotionPhoto`，视频项在最后。

字段规则（判据是**规范正文**，不是任何厂商样本）：

| 规则 | 为什么 |
| --- | --- |
| `Primary` 带 `Item:Length="0"` | 规范原话："主媒体内容的长度应为 0"，主图长度"可通过解析主图片确定"。这个 `0` 是规范要求的写法，不是可选装饰 |
| `Item:Padding` **只**写在 `Primary` 上 | 规范："只有第一项媒体内容可以包含 Padding 属性"。对 JPEG 主图它是可选的，我们**恒写**（无空隙时写 `0`） |
| `MotionPhoto` 项必须正好结束在 EOF | 规范："此媒体项的位置必须位于文件末尾，此媒体项的字节终止后不得放置其他字节" |
| `GainMap.Length` = **整条尾巴**，不是副图的诚实内长 | 规范只给第一项 `Padding`，副图与视频之间没有别的合法字段。诚实内长会留下没人声明的字节，二选一取合规的那个 |
| 两个 `*PresentationTimestampUs` 固定 `-1` | 桌面端会先读源包里已有的值、能解析成整数就沿用，否则 `-1`。输入是 vivo 原片，源包里没有这两个标签，读与不读都是 `-1`；而**读源包正是上一版掺脏东西的入口**。被认的第三方样本写 `0`，但 `0` 的语义是"从第 0 微秒播"，与"未提供"不是一回事，没有真机依据前不改 |
| 命名空间 URI 一个都不能"顺手改" | RDF 那条栽过跟头：源片用带斜杠的标准 URI `1999/02/22-rdf-syntax-ns#`，写成 `1999-02-22` 短横形会**另起**一个 `rdf:RDF`，真机产物因此不被认 |
| **序列化形式规范没有规定** | 那一页只有属性表与散文，没有任何示例 XMP 包。因此 `<rdf:li>` 与 `<rdf:li rdf:parseType="Resource">`、属性先后顺序都不产生规范性差异 —— 本实现按自己的历史形状写，**不为了"对齐某厂商样本"去动它** |
| APP1 长度字段是 16 位 | 包体上限 65,533 字节（[XmpPacket.kt:5](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/XmpPacket.kt#L5)）。超长折成 `EXTENDED_XMP_UNSUPPORTED`，和分块扩展 XMP 同一个下场：不做处理 |

证据位置：[XmpPacket.kt:33-71](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/XmpPacket.kt#L33)、[:107-116](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/XmpPacket.kt#L107)。

## 4. 尾巴声明矩阵：EOI 与视频之间不留一个没人声明的字节

规范 MUST：媒体项**紧密封装**，项间每一段字节都要被目录声明。定性由 [TrailerModel.describe()](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerModel.kt#L62) 做出，调用方不得自己猜：

| `imageEnd` 之后的尾巴 | 分类（关口⑤） | 声明（关口⑥） | 目录 |
| --- | --- | --- | --- |
| 空 | `NONE` | 什么都不声明 | `Primary(Padding=0)` → `MotionPhoto` |
| 真 gain map 副图（APP1/APP2 里声明过 ISO 21496-1 或 Adobe hdr-gain-map）**含副图之后的厂商记录** | `OTHER`（放行） | `gainMapBytes = 整条尾巴` | `Primary(Padding=0)` → `GainMap(Length=整条尾巴)` → `MotionPhoto` |
| MPF 副图 / 厂商 JSON / 零填充 / 非副图的第二张 JPEG | `OTHER`（放行） | `paddingBytes = 整条尾巴` | `Primary(Padding=尾巴长度)` → `MotionPhoto` |
| **遍历不收敛的畸形副图** | `OTHER`（放行） | `paddingBytes = 整条尾巴`（降级，不抛） | 同上 |
| 上一轮追加的视频（ISO-BMFF 盒形状，或源 XMP 已声明 `video/`） | `VIDEO` | —— | 整项拒绝：`ALREADY_MOTION_PHOTO` → `SKIPPED` |

- 三条 gain map 声明串都要认：`urn:iso:std:iso:ts:21496-1`（连字符）、`urn:iso:std:iso:ts:21496:-1`（冒号，Android 的 APP2 标识符就写这个）、`http://ns.adobe.com/hdr-gain-map/1.0/`（Adobe 旧命名空间）（[TrailerModel.kt:41-50](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerModel.kt#L41)）。
- **降级而不是报错**：把尾巴声明成 Padding 永远合法，而把一张坏图谎称 GainMap 才是骗目录。所以 `describe` 对任何输入都不抛，I/O 故障与畸形一律走 `paddingBytes`。
- `gainMapBytes` 与 `paddingBytes` **互斥**，同时非 null 说明上游定性出错 —— 用 `check`（`IllegalStateException`）而不是 `require`，因为这是**上游不变量被违反**，不是输入形状；合成器只把 `IllegalArgumentException` 折成"不支持的输入"（[XmpPacket.kt:78-84](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/XmpPacket.kt#L78)）。
- 旧规则"EOI 后有任何字节就跳过"在 V2419A 上会把用户 **100%** 的真实输入判成已合成过 —— 那台机器上每张 JPEG 都有尾巴（[TrailerClassifier.kt:8-11](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerClassifier.kt#L8)）。

## 5. vivo 末盒改写

vivo 相机 mp4 末尾那个 `uuid` + 伪 UUID `vivoMediaExtInfo` 盒里，`com.android.camera.livephoto` 的值**必须以字面量 `motionphoto` 开头**，这台机器的相册才认。相机自己写的是数字开头的 id（真机样本 `17924055463797e14dd500000000`），所以**原样追加必然不被认**（[VivoLivePhotoTail.kt:8-16](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/VivoLivePhotoTail.kt#L8)）。

判据来自真机二分（2026-10-20）：同一张图配同一件事，只把末盒换成"值以 motionphoto 开头"的那一份，vivo 相册就从"不认"变"认"；而盒尺寸、JSON 里其余的键、`version` 数字、`cameralbum!` 那条尾记录**全都不影响**（Y1/Y2/Y3 认、Y4 用字母开头但非该前缀 → 不认）。

盒内布局：

```
[u32 size]['uuid'][16 B 伪 UUID vivoMediaExtInfo]['vivo'][JSON][u32BE = JSON 字节数][尾记录]
```

改写它必须同步三处自洽数据，因为**盒会变长**（多 11 字节前缀）：

1. 盒头的 `size`；
2. JSON 之后那个描述 JSON 字节数的 `u32BE`；
3. XMP 里的 `Item:Length` / `GCamera:MicroVideoOffset` —— 由调用方用 `Rewrite.totalBytes` 负责。

安全性：末盒排在 `moov` 之后，视频里所有 `stco`/`co64` 的 chunk 偏移都指向它之前，所以重写它不会让任何偏移失效；前半一字不动。

**看不懂就不动**（返回 `null`，产物与"原样追加"逐字节相同）：没有这个盒 / 标记落在 64 KiB 读窗口之外 / 盒的 `size` 没正好走到文件末尾 / `uuid` tag 不符 / `vivo` 魔数不符 / JSON 花括号不配对 / 长度字段与实际 JSON 字节数不符 / JSON 后面放不下 4 个字节 / 没有 `livephoto` 键 / 值已经合规。原则：**不改自己读不懂的字节，也不替非 vivo 的视频伪造厂商结构**（[VivoLivePhotoTail.kt:22-24](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/VivoLivePhotoTail.kt#L22)、[:58-92](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/VivoLivePhotoTail.kt#L58)）。

## 6. 厂商差异：规范允许 ≠ 厂商做了

仓库里没有第二家厂商的**通过样本**，OPPO 那份只作为**反例**存在（下面每个数字都取自该文件本身：9,609,672 B、`imageEnd` 3,546,201）（[XmpPacket.kt:46-53](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/XmpPacket.kt#L46)）：

| OPPO 那份文件的问题 | 后果 | 我们的做法 |
| --- | --- | --- |
| `GainMap.Length = 585,268` 声明了一份**该文件里根本不存在**的副图 | 目录按项累加正好溢出文件末尾 585,268 字节 —— **溢出量的来源是这一项，不是 MotionPhoto** | `GainMap.Length` = 整条尾巴，且只在尾巴真是副图时写 |
| `MotionPhoto.Length = 6,063,471` 数的是整条尾巴（mp4 5,468,971 + `jxrs` 594,332 + 末盒 168） | 数字诚实，但**视频项没有结束在 EOF**（规范 MUST） | `MotionPhoto.Length` = 追加的视频字节数，正好结束在 EOF |
| 三项**都**写了 `Padding` | 其中两项正是规范明令禁止的非首项 | `Padding` 只写在 `Primary` |

其他厂商（小米、Pixel）：没有任何通过样本，也没有针对性代码 —— `VivoLivePhotoTail` 只认 vivo 末盒，其余一律原样追加。**别把 vivo 的判据当成通用规则**。

## 7. 明确不做的事

| 不做 | 原因 |
| --- | --- |
| 不解码 / 不转码 / 不重编码主图 | 重编码过的主图真机**不被认**（§2 对照表） |
| 不处理分块扩展 XMP | `EXTENDED_XMP_UNSUPPORTED`，需要重写整个 APP1 分块结构 |
| 不把 HEIC / AVIF 当输入 | 只吃 `.jpg` / `.jpeg`（规范允许三种扩展名，我们只做 JPEG） |
| 不递归子目录 | 只列一层 |
| 不合并源 XMP 属性 | 清空白写是唯一被认的形状 |
| 不伪造厂商结构 | 末盒看不懂就返回 `null` |
| 不做视频完整性校验 | 只探测起始 12 字节内的 `ftyp`，不解析 `moov`；坏视频会写出一个相册可能不认的产物 |

## 8. 实测数字（都来自代码注释，非本文新增）

| 数字 | 含义 | 出处 |
| --- | --- | --- |
| 442,350 B | 真机语料里那张 gain map 副图的大小（保留尾巴才被认的那一版） | [JpegXmpPatcher.kt:22](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegXmpPatcher.kt#L22) |
| 37 万 ~ 106 万 B | vivo 语料尾巴大小（`TrailerModel` 因此会多读这一遍） | [TrailerModel.kt:59](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/TrailerModel.kt#L59) |
| 5,976,771 B | 真机 `mdat` 大小 —— 前 4 字节读出来是 `1`（64 位扩展长度的标志） | [TrailerClassifierTest.kt:59](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/TrailerClassifierTest.kt#L59) |
| 1,057 B | 现 XMP 包体大小（加 `Primary Length=0` + `Padding` 后从 1,024 挪上来） | [XmpPacketTest.kt:19-26](file:///D:/projects/piece/live-photo-batch/core/src/test/kotlin/dev/zayn/livephotobatch/core/XmpPacketTest.kt#L19) |
| 65,533 B | 单个 APP1 段体上限 | [XmpPacket.kt:5](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/XmpPacket.kt#L5) |
| 64 KiB | 全部读写缓冲（扫描、搬运、末盒读窗口） | [MotionPhotoComposer.kt:54](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/MotionPhotoComposer.kt#L54) |
| 100 张 / 约 4 MB JPEG / 15 秒 | **未验证**的动机性预算，只用来解释为什么逐字节读不行 | [JpegStructure.kt:100-102](file:///D:/projects/piece/live-photo-batch/core/src/main/kotlin/dev/zayn/livephotobatch/core/JpegStructure.kt#L100) |
