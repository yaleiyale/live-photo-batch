package dev.zayn.livephotobatch.core

object XmpPacket {
    const val XAP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/"
    private const val MAX_SEGMENT_BODY = 65533 // 长度字段自身占 2 字节，字段上限 65535

    // spec §7.3 用到的命名空间；Task 13 的合并器按限定名增删节点，所以这些常量对外可见。
    const val CAMERA_NS = "http://ns.google.com/photos/1.0/camera/"
    const val CONTAINER_NS = "http://ns.google.com/photos/1.0/container/"
    const val ITEM_NS = "http://ns.google.com/photos/1.0/container/item/"
    const val RDF_NS = "http://www.w3.org/1999-02-22-rdf-syntax-ns#"
    const val XMPMETA_NS = "adobe:ns:meta/"
    const val ITEM_SEMANTIC_PRIMARY = "Primary"
    const val ITEM_SEMANTIC_MOTION_PHOTO = "MotionPhoto"

    /**
     * 包的起止处理指令：XMP 规范要求 `<?xpacket begin>` 用 U+FEFF 声明字节序，
     * 而 `<?xpacket end>` 告诉解析方"到这里为止是包"。合并器与模板共用这两行——
     * 输出必须以它们包裹，两次合并才能落在同一个字节上。
     */
    const val PACKET_BEGIN = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
    const val PACKET_END = "<?xpacket end=\"w\"?>"

    /**
     * 行拼接而不是整块原始字符串：`<?xpacket begin=...>` 里的 U+FEFF 在 Kotlin 原始字符串中
     * 不会被解释成转义，写成字面量又极易被编辑器吞掉。
     */
    fun motionPhoto(videoSize: Long): ByteArray {
        val size = videoSize.toString()
        val lines = buildList {
            add(PACKET_BEGIN)
            add("<x:xmpmeta xmlns:x=\"$XMPMETA_NS\">")
            add(" <rdf:RDF xmlns:rdf=\"$RDF_NS\">")
            add("  <rdf:Description xmlns:GCamera=\"$CAMERA_NS\"")
            add("      GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\"")
            add("      GCamera:MotionPhotoPresentationTimestampUs=\"-1\"/>")
            add("  <rdf:Description xmlns:GCamera=\"$CAMERA_NS\"")
            add("      GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\"")
            add("      GCamera:MicroVideoOffset=\"$size\"")
            add("      GCamera:MicroVideoPresentationTimestampUs=\"-1\"/>")
            add("  <rdf:Description xmlns:Container=\"$CONTAINER_NS\">")
            add("   <Container:Directory>")
            add("    <rdf:Seq>")
            add("     <rdf:li xmlns:Item=\"$ITEM_NS\" rdf:parseType=\"Resource\">")
            add("      <Item:Mime>image/jpeg</Item:Mime>")
            add("      <Item:Semantic>Primary</Item:Semantic>")
            add("     </rdf:li>")
            add("     <rdf:li xmlns:Item=\"$ITEM_NS\" rdf:parseType=\"Resource\">")
            add("      <Item:Mime>video/mp4</Item:Mime>")
            add("      <Item:Semantic>MotionPhoto</Item:Semantic>")
            add("      <Item:Length>$size</Item:Length>")
            add("     </rdf:li>")
            add("    </rdf:Seq>")
            add("   </Container:Directory>")
            add("  </rdf:Description>")
            add(" </rdf:RDF>")
            add("</x:xmpmeta>")
            add(PACKET_END)
        }
        return requireFitsInSegment(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    /** 完整 APP1 段：FF E1 + 16 位长度 + 标识符 + NUL + XMP 包。 */
    fun app1Segment(videoSize: Long): ByteArray = app1SegmentFor(motionPhoto(videoSize))

    /**
     * 把**任意**一个 XMP 包（模板产物或 [XmpMerger] 的合并产物）包成 APP1 段。
     * 16 位长度字段的上限只在这一处检查，两条生成路径共用同一条守卫。
     */
    fun app1SegmentFor(packet: ByteArray): ByteArray {
        requireFitsInSegment(packet)
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
