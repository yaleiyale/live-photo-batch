package dev.zayn.livephotobatch.core

object XmpPacket {
    const val XAP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/"
    private const val MAX_SEGMENT_BODY = 65533 // 长度字段自身占 2 字节，字段上限 65535

    private const val CAMERA_NS = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER_NS = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM_NS = "http://ns.google.com/photos/1.0/container/item/"

    /**
     * 行拼接而不是整块原始字符串：`<?xpacket begin=...>` 里的 U+FEFF 在 Kotlin 原始字符串中
     * 不会被解释成转义，写成字面量又极易被编辑器吞掉。
     */
    fun motionPhoto(videoSize: Long): ByteArray {
        val size = videoSize.toString()
        val lines = buildList {
            add("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
            add("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
            add(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999-02-22-rdf-syntax-ns#\">")
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
            add("<?xpacket end=\"w\"?>")
        }
        return requireFitsInSegment(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
    }

    /** 完整 APP1 段：FF E1 + 16 位长度 + 标识符 + NUL + XMP 包。 */
    fun app1Segment(videoSize: Long): ByteArray {
        val packet = motionPhoto(videoSize)
        val identifier = XAP_IDENTIFIER.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        val body = identifier.size + packet.size
        require(body <= MAX_SEGMENT_BODY) { "APP1 body $body exceeds $MAX_SEGMENT_BODY" }
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
