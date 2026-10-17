package dev.zayn.livephotobatch.core

object Fixtures {
    const val XAP = "http://ns.adobe.com/xap/1.0/"
    const val XMP_EXTENSION = "http://ns.adobe.com/xmp/extension/"

    fun uint16(value: Int): ByteArray =
        byteArrayOf((value shr 8).toByte(), value.toByte())

    /** marker + 2 字节长度字段（含长度字段自身）+ payload */
    fun segment(marker: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), marker.toByte()) + uint16(payload.size + 2) + payload

    fun app1(identifier: String, payload: ByteArray): ByteArray =
        segment(0xE1, identifier.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + payload)

    fun sof0(): ByteArray =
        segment(0xC0, byteArrayOf(0x08, 0x00, 0x11, 0x01, 0x01, 0x00, 0x01, 0x11, 0x00))

    fun sos(): ByteArray =
        segment(0xDA, byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x3F, 0x00))

    /** SOI + 头部段 + SOS + 熵数据 + EOI (+ 尾部追加字节) */
    fun jpeg(
        headSegments: List<ByteArray> = listOf(sof0()),
        entropy: ByteArray = byteArrayOf(0x11, 0x22, 0x33, 0x44),
        tail: ByteArray = ByteArray(0),
    ): ByteArray {
        val body = headSegments.fold(byteArrayOf()) { acc, seg -> acc + seg }
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + body + sos() + entropy +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte()) + tail
    }

    /** 标准 ftyp 头 + filler，总长 size */
    fun mp4(size: Long = 4096): ByteArray {
        require(size >= 12)
        val out = ByteArray(size.toInt())
        // 前 4 字节 = 32 位大端 box size
        val boxSize = size.toInt()
        out[0] = (boxSize ushr 24).toByte()
        out[1] = (boxSize ushr 16).toByte()
        out[2] = (boxSize ushr 8).toByte()
        out[3] = boxSize.toByte()
        "ftyp".toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        "isom".toByteArray(Charsets.ISO_8859_1).copyInto(out, 8)
        return out
    }

    val xmpPacket: ByteArray =
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">OLD</x:xmpmeta>".toByteArray(Charsets.UTF_8)
}
