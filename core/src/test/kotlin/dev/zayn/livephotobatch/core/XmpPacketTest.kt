package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XmpPacketTest {

    /** 模板不再是自己拼字符串，而是与合并路径同一个构造器（增补 2026-10-18b §3 末段）：形状判据共用一套。 */
    @Test fun `template carries the vivo shape with one group of seven attributes`() {
        val text = XmpPacket.motionPhoto(987_654L).decodeToString()
        val host = XmpShape.assertVivoShape(text, 987_654L)
        listOf(
            "MicroVideo" to "1",
            "MicroVideoVersion" to "1",
            "MicroVideoOffset" to "987654",
            "MicroVideoPresentationTimestampUs" to "-1",
            "MotionPhoto" to "1",
            "MotionPhotoVersion" to "1",
            "MotionPhotoPresentationTimestampUs" to "-1",
        ).forEach { (name, value) ->
            assertEquals(value, host.getAttributeNS(XmpPacket.CAMERA_NS, name), "模板缺少 $name")
        }
    }

    @Test fun `template declares no container items`() {
        val root = XmpShape.parse(XmpPacket.motionPhoto(42L).decodeToString()).documentElement
        assertEquals(
            0,
            XmpShape.childrenNamed(XmpShape.childrenNamed(XmpShape.directories(root).single(), "Seq").single(), "li")
                .size,
            "模板又往 Seq 里塞项了",
        )
        assertTrue(
            XmpShape.descendantsNamed(root, XmpPacket.CONTAINER_NS, "Item").isEmpty(),
            "模板还在声明 Container:Item",
        )
    }

    @Test fun `the video size is carried by exactly one attribute`() {
        val root = XmpShape.parse(XmpPacket.motionPhoto(42L).decodeToString()).documentElement
        val carriers = XmpShape.allElements(root).flatMap { el ->
            (0 until el.attributes.length).map { el.attributes.item(it)!! }
        }.filter { it.nodeValue == "42" }
        // 旧模板里视频长度出现两次（MicroVideoOffset 与 MotionPhoto 项的 Item:Length）；
        // 产物不再声明任何项，所以只剩偏移量这一处。
        assertEquals(listOf("GCamera:MicroVideoOffset"), carriers.map { it.nodeName })
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
