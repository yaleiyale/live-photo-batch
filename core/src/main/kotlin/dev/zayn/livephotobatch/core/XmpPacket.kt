package dev.zayn.livephotobatch.core

object XmpPacket {
    const val XAP_IDENTIFIER = "http://ns.adobe.com/xap/1.0/"
    private const val MAX_SEGMENT_BODY = 65533 // 长度字段自身占 2 字节，字段上限 65535

    // spec §7.3 用到的命名空间；合并器按限定名增删节点，所以这些常量对外可见。
    // 这几个 URI 一个都不能"顺手改"：值错了，源包里的 rdf:RDF 就匹配不上，我们会在它之外
    // 另起一个 RDF——真机产物不被 vivo 相册认作动态照片的根因就是这个（spec 增补 2026-10-18b §3.1）。
    const val CAMERA_NS = "http://ns.google.com/photos/1.0/camera/"
    const val CONTAINER_NS = "http://ns.google.com/photos/1.0/container/"
    const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    const val XMPMETA_NS = "adobe:ns:meta/"

    /**
     * 包的起止处理指令：XMP 规范要求 `<?xpacket begin>` 用 U+FEFF 声明字节序，
     * 而 `<?xpacket end>` 告诉解析方"到这里为止是包"。合并器与模板共用这两行——
     * 输出必须以它们包裹，两次合并才能落在同一个字节上。
     */
    const val PACKET_BEGIN = "<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>"
    const val PACKET_END = "<?xpacket end=\"w\"?>"

    /** 无源 XMP 时的包：与合并产物同一个构造器（spec 增补 2026-10-18b §3 末段）。 */
    fun motionPhoto(videoSize: Long): ByteArray = XmpMerger.merge(null, videoSize)

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
