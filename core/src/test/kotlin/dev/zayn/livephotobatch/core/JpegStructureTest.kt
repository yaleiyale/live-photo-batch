package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.channels.Channels

class JpegStructureTest {
    private fun scan(bytes: ByteArray) =
        JpegScanner.scan(Channels.newChannel(java.io.ByteArrayInputStream(bytes)))

    @Test fun `plain jpeg ends right at eoi`() {
        val bytes = Fixtures.jpeg()
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        assertEquals(bytes.size.toLong(), structure.imageEnd)
        assertNull(structure.oldXmp)
    }

    @Test fun `entropy byte stuffing hides a fake eoi`() {
        // 熵数据里 FF 00 D9 是被填充过的 0xFF，不是 marker
        val bytes = Fixtures.jpeg(entropy = byteArrayOf(0, 0xFF.toByte(), 0x00, 0xD9.toByte(), 7, 7))
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        assertEquals(bytes.size.toLong(), structure.imageEnd)
    }

    @Test fun `appended video tail is not mistaken for image data`() {
        val video = Fixtures.mp4(256)
        val bytes = Fixtures.jpeg(tail = video)
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        // bytes.size - video.size 是 Int，imageEnd 是 Long：不加 toLong 会落到
        // assertEquals(Object, Object) 上，Integer(31) 与 Long(31) 永不相等。
        assertEquals((bytes.size - video.size).toLong(), structure.imageEnd)
    }

    @Test fun `legacy exif app1 is kept but xmp app1 is located`() {
        val exif = Fixtures.segment(0xE1, "Exif".toByteArray() + byteArrayOf(0, 0) + ByteArray(8))
        val xmp = Fixtures.app1(Fixtures.XAP, Fixtures.xmpPacket)
        val bytes = Fixtures.jpeg(headSegments = listOf(exif, xmp, Fixtures.sof0()))
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        assertEquals(Fixtures.xmpPacket.decodeToString(), structure.oldXmpText)
        val start = bytes.indexOfSlice(xmp).toLong()
        assertEquals(start, structure.oldXmp!!.start)
        assertEquals(start + xmp.size, structure.oldXmp.endExclusive)
    }

    @Test fun `extension xmp is flagged`() {
        val extended = Fixtures.app1(Fixtures.XMP_EXTENSION, ByteArray(40))
        val bytes = Fixtures.jpeg(headSegments = listOf(extended, Fixtures.sof0()))
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        assertTrue(structure.hasExtendedXmp)
        assertNull(structure.oldXmp)
    }

    @Test fun `non jpeg prefix`() {
        assertTrue(scan("GIF89a".toByteArray()) is JpegScanResult.NotJpeg)
    }

    @Test fun `truncated file is malformed`() {
        val bytes = Fixtures.jpeg().copyOf(7)
        assertTrue(scan(bytes) is JpegScanResult.Malformed)
    }

    @Test fun `imageEnd counts every image byte exactly`() {
        val bytes = Fixtures.jpeg(tail = Fixtures.mp4(64))
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        // SOI(2) + SOF0(2+2+9) + SOS(2+2+6) + entropy(4) + EOI(2)
        // 计划原文这里写的是 2 + 11 + 8 + 4 + 2 = 27，漏算了 SOF0 与 SOS 各自的 2 字节
        // marker，和它上面的注释分解式（=31）自相矛盾；也与同文件
        // `plain jpeg ends right at eoi`（断言 imageEnd == Fixtures.jpeg() 全长）冲突。
        // 31 由夹具单独复算即可得到，不依赖扫描器，故按注释的分解式修正期望值。
        assertEquals(2 + 13 + 10 + 4 + 2, structure.imageEnd.toInt())
    }
}

private fun ByteArray.indexOfSlice(slice: ByteArray): Int {
    if (slice.isEmpty()) return 0
    outer@ for (i in 0..(size - slice.size)) {
        for (j in slice.indices) {
            if (this[i + j] != slice[j]) continue@outer
        }
        return i
    }
    return -1
}
