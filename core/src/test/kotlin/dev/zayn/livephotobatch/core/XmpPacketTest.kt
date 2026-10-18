package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XmpPacketTest {

    /**
     * spec 增补（2026-10-18c）§2 那份包：真机候选 A 写的就是它，vivo 相册认、能播。
     * 判据是**字节**，不是"实现现在吐了什么"——A 与 Task 13 产物只差这份包，合并形状已经被真机否掉。
     */
    @Test fun `the packet is byte for byte the one the phone accepted`() {
        val accepted = Fixtures.specPacket(3_863_927L)
        assertEquals(1_024, accepted.size, "夹具自己就不是那份 1,024 字节的包：抄漏了字符")
        assertArrayEquals(accepted, XmpPacket.motionPhoto(3_863_927L))
    }

    @Test fun `both video size slots carry the argument and the packet stays far below the segment limit`() {
        val size = 3_863_927L
        val packet = XmpPacket.motionPhoto(size)
        val text = packet.decodeToString()
        assertTrue(text.contains("GCamera:MicroVideoOffset=\"$size\""), "偏移量那一格没填视频长度")
        assertTrue(text.contains("Item:Length=\"$size\""), "容器项的 Item:Length 没填视频长度")
        assertEquals(2, Regex("\"$size\"").findAll(text).count(), "视频长度出现的地方不止这两处")
        assertTrue(packet.size < 64 * 1024, "整包 ${packet.size} 字节，逼近 APP1 的 16 位长度字段")
    }

    @Test fun `the packet has one rdf RDF block, seven attributes and two items in order`() {
        val text = XmpPacket.motionPhoto(1L).decodeToString()
        assertEquals(1, Regex("<rdf:RDF").findAll(text).count(), "又起了第二个 rdf:RDF")
        assertEquals(1, Regex("<rdf:Description").findAll(text).count(), "又起了第二个 rdf:Description")
        assertEquals(7, Regex("GCamera:(MicroVideo|MicroVideoVersion|MicroVideoOffset|" +
            "MicroVideoPresentationTimestampUs|MotionPhoto|MotionPhotoVersion|" +
            "MotionPhotoPresentationTimestampUs)=").findAll(text).count(), "动照片属性不止七个")
        assertEquals(listOf("Primary", "MotionPhoto"), Regex("""Item:Semantic="(\w+)"""").findAll(text)
            .map { it.groupValues[1] }.toList(), "容器项的顺序或写法变了")
    }

    @Test fun `app1 segment is marker, length, identifier, nul, packet`() {
        val segment = XmpPacket.app1Segment(123L)
        assertEquals(0xFF.toByte(), segment[0])
        assertEquals(0xE1.toByte(), segment[1])
        val declared = ((segment[2].toInt() and 0xFF) shl 8) or (segment[3].toInt() and 0xFF)
        assertEquals(segment.size - 2, declared)
        assertTrue(segment.startsWith(XmpPacket.XAP_IDENTIFIER.toByteArray(Charsets.ISO_8859_1), 4))
        assertEquals(0.toByte(), segment[4 + XmpPacket.XAP_IDENTIFIER.length])
        assertEquals(
            XmpPacket.motionPhoto(123L).size,
            segment.size - 4 - XmpPacket.XAP_IDENTIFIER.length - 1
        )
    }

    @Test fun `segment exceeding the 16 bit length field is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            // 用超大数字无法撑爆包体；这里直接断言上限保护存在
            XmpPacket.requireFitsInSegment(ByteArray(65534 - XmpPacket.XAP_IDENTIFIER.length))
        }
    }

    private fun ByteArray.startsWith(other: ByteArray, at: Int): Boolean {
        if (size - at < other.size) return false
        for (i in other.indices) if (this[at + i] != other[i]) return false
        return true
    }
}
