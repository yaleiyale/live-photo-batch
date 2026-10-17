package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class XmpPacketTest {
    @Test fun `packet carries the exact spec field set`() {
        val text = XmpPacket.motionPhoto(987654L).decodeToString()
        listOf(
            "GCamera:MicroVideo=\"1\"",
            "GCamera:MicroVideoVersion=\"1\"",
            "GCamera:MicroVideoOffset=\"987654\"",
            "GCamera:MicroVideoPresentationTimestampUs=\"-1\"",
            "GCamera:MotionPhoto=\"1\"",
            "GCamera:MotionPhotoVersion=\"1\"",
            "GCamera:MotionPhotoPresentationTimestampUs=\"-1\"",
            "http://ns.google.com/photos/1.0/camera/",
            "http://ns.google.com/photos/1.0/container/",
            "http://ns.google.com/photos/1.0/container/item/",
            "<Item:Mime>image/jpeg</Item:Mime>",
            "<Item:Semantic>Primary</Item:Semantic>",
            "<Item:Mime>video/mp4</Item:Mime>",
            "<Item:Semantic>MotionPhoto</Item:Semantic>",
            "<Item:Length>987654</Item:Length>",
        ).forEach { assertTrue(text.contains(it), "XMP 缺少：$it") }
    }

    @Test fun `only the two size fields carry the value`() {
        val text = XmpPacket.motionPhoto(42L).decodeToString()
        assertEquals(2, Regex("42").findAll(text).count())
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
