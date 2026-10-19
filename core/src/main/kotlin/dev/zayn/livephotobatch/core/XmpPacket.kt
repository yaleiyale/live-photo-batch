package dev.zayn.livephotobatch.core

object XmpPacket {
    const val XAP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/"
    private const val MAX_SEGMENT_BODY = 65533 // 长度字段自身占 2 字节，字段上限 65535

    // spec 增补 §2 那份包用到的命名空间。值一个都不能"顺手改"：URI 对不上，按
    // `xmpmeta/rdf:RDF/rdf:Description` 取属性的读取方（相册、Exiv2）就看不见我们的声明。
    // RDF 那条栽过跟头：源片用的是带斜杠的标准 URI `1999/02/22-rdf-syntax-ns#`，
    // 写成 `1999-02-22` 那种短横形我们会**另起**一个 rdf:RDF，真机产物因此不被认作动态照片。
    private const val XMPMETA_NS = "adobe:ns:meta/"
    private const val CAMERA_NS = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER_NS = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM_NS = "http://ns.google.com/photos/1.0/container/item/"
    private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    // Ultra HDR 副图（GainMap 项）的版本命名空间：Google Motion Photo 1.0 与 Adobe 增益图声明同一个 URI。
    private const val HDRGM_NS = "http://ns.adobe.com/hdr-gain-map/1.0/"

    /**
     * 包的起止处理指令：XMP 规范要求 `<?xpacket begin>` 用 U+FEFF 声明字节序，
     * 而 `<?xpacket end>` 告诉解析方"到这里为止是包"。模板与测试夹具里的源包共用这两行。
     * BOM 必须是 `\uFEFF` 转义：Kotlin 原始字符串不解释转义，裸写的 U+FEFF 又极易被编辑器吞掉。
     */
    const val PACKET_BEGIN = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
    const val PACKET_END = "<?xpacket end=\"w\"?>"

    /**
     * 产物 XMP 的**唯一**生成入口，也是 [MotionPhotoComposer] 唯一写进产物的元数据
     * （spec 增补（2026-10-18c）§2）：**清空重写**——源 XMP 里的 `hdrgm:*`、`dc:*`、厂商命名空间
     * 和源片自己的 `Container:Directory` 项一个都不掺进来。真机单变量对照就是这么判的：
     * 同一个包、同一个视频，包从清空白写的那份被 vivo 相册认下来，从源包合并出来的那份不认。
     *
     * 三个形状要点照 spec §2 逐字节抄（`XmpPacketTest` 拿 fixture 字面量钉死）：
     * 1. **一个** `rdf:RDF`、**一个** `rdf:Description`，七个动照片属性与 `Container:Directory` 同居其上；
     * 2. 容器项写成 `<Container:Item Item:Semantic="…" Item:Mime="…" Item:Length="…"/>`：语义与 MIME 是
     *    这个**子元素上的属性**（与 vivo 源包同形），不是 `Item:Semantic` 子元素的文本；
     * 3. 项顺序 Primary →（GainMap）→ MotionPhoto，视频项在最后。
     *
     * 目录必须声明 EOI 与视频之间的**每一段字节**（增补 2026-10-18d §2，"紧密封装"MUST）：
     * [gainMapBytes] 把整条尾巴声明成排在视频前的 `GainMap` 项，并给宿主 Description 带回
     * `xmlns:hdrgm` + `hdrgm:Version="1.0"`（只在有副图时写）；[paddingBytes] 把整条尾巴声明成
     * 主图的 `Item:Padding`（规范只允许第一项有）。两者**互斥**且至多其一非 null；都不给时产出的
     * 包与 2026-10-18c 那份**逐字节相同**（回归钉桩：无副图无填充时形状不许漂移）。
     * 定性由 [TrailerModel.describe] 做出，调用方不得自己猜。
     *
     * 两个时间戳固定 `-1`：桌面端会先读源包里已有的 `*PresentationTimestampUs`，能解析成整数就沿用，
     * 否则 `-1`。输入是 vivo 原片，源包里没有这两个标签，读与不读都是 `-1`；这里不做那次读取——
     * 读源包正是上一版掺脏东西的入口。
     *
     * 逐行 `joinToString("\n")` 而不是整块原始字符串：见 [PACKET_BEGIN] 的 BOM 理由。
     */
    fun motionPhoto(
        videoSize: Long,
        gainMapBytes: Long? = null,
        paddingBytes: Long? = null,
    ): ByteArray {
        // `check` 而不是 `require`：这是**上游不变量**被违反，不是输入形状。合成器只把
        // IllegalArgumentException 折成 EXTENDED_XMP_UNSUPPORTED（包超 64 KiB 那条真·输入上限），
        // 两个类型分开才不会被那个 catch 吞成一条指向错误方向的报告。
        check(gainMapBytes == null || paddingBytes == null) {
            "gainMapBytes 与 paddingBytes 互斥：一条尾巴只有一个身份，两个都给说明上游定性出错，" +
                "静默挑一个会产出一个骗人的目录"
        }
        val size = videoSize.toString()
        val lines = buildList {
            add(PACKET_BEGIN)
            add("<x:xmpmeta xmlns:x=\"$XMPMETA_NS\" x:xmptk=\"Adobe XMP Core 7.0-106\">")
            add(" <rdf:RDF xmlns:rdf=\"$RDF_NS\">")
            add("  <rdf:Description rdf:about=\"\"")
            add("    xmlns:GCamera=\"$CAMERA_NS\"")
            add("    xmlns:Container=\"$CONTAINER_NS\"")
            add("    xmlns:Item=\"$ITEM_NS\"")
            if (gainMapBytes != null) {
                add("    xmlns:hdrgm=\"$HDRGM_NS\"")
                add("    hdrgm:Version=\"1.0\"")
            }
            add("    GCamera:MicroVideo=\"1\"")
            add("    GCamera:MicroVideoVersion=\"1\"")
            add("    GCamera:MicroVideoOffset=\"$size\"")
            add("    GCamera:MicroVideoPresentationTimestampUs=\"-1\"")
            add("    GCamera:MotionPhoto=\"1\"")
            add("    GCamera:MotionPhotoVersion=\"1\"")
            add("    GCamera:MotionPhotoPresentationTimestampUs=\"-1\">")
            add("   <Container:Directory>")
            add("    <rdf:Seq>")
            val padding = if (paddingBytes != null) " Item:Padding=\"$paddingBytes\"" else ""
            add("     <rdf:li><Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"$padding/></rdf:li>")
            if (gainMapBytes != null) {
                // Length 是**整条尾巴**（含副图 JPEG 之后的厂商记录），不是副图诚实内长：
                // 规范只给第一项 Padding，内长会在副图与视频之间留下没人声明的字节（spec §2 末段）。
                add("     <rdf:li><Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" Item:Length=\"$gainMapBytes\"/></rdf:li>")
            }
            add("     <rdf:li><Container:Item Item:Semantic=\"MotionPhoto\" Item:Mime=\"video/mp4\" Item:Length=\"$size\"/></rdf:li>")
            add("    </rdf:Seq>")
            add("   </Container:Directory>")
            add("  </rdf:Description>")
            add(" </rdf:RDF>")
            add("</x:xmpmeta>")
            add(PACKET_END)
        }
        return requireFitsInSegment(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    /** 完整 APP1 段：FF E1 + 16 位长度 + 标识符 + NUL + XMP 包。超长由 [motionPhoto] 里的守卫报。 */
    fun app1Segment(
        videoSize: Long,
        gainMapBytes: Long? = null,
        paddingBytes: Long? = null,
    ): ByteArray {
        val packet = motionPhoto(videoSize, gainMapBytes, paddingBytes)
        val identifier = XAP_IDENTIFIER.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        val body = identifier.size + packet.size
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (body + 2 shr 8).toByte(), (body + 2).toByte()) +
            identifier + packet
    }

    /** 供测试与调用方复用：包体超过 16 位长度字段能表达的上限就拒绝。 */
    fun requireFitsInSegment(packet: ByteArray): ByteArray {
        val needed = XAP_IDENTIFIER.length + 1 + packet.size
        require(needed <= MAX_SEGMENT_BODY) {
            "XMP packet needs $needed bytes, over the 16-bit APP1 limit $MAX_SEGMENT_BODY"
        }
        return packet
    }
}
