package dev.zayn.vlive.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel

class JpegStructureTest {
    private fun scan(bytes: ByteArray) =
        JpegScanner.scan(Channels.newChannel(java.io.ByteArrayInputStream(bytes)))

    @Test fun `plain jpeg ends right at eoi`() {
        val bytes = Fixtures.jpeg()
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        assertEquals(bytes.size.toLong(), structure.imageEnd)
        assertNull(structure.oldXmp)
    }

    @Test fun `every xap segment is counted - the last one must not silently win`() {
        fun structureOf(headSegments: List<ByteArray>) =
            (scan(Fixtures.jpeg(headSegments = headSegments)) as JpegScanResult.Parsed).structure

        assertEquals(0, structureOf(listOf(Fixtures.sof0())).xmpSegments)
        assertEquals(
            1,
            structureOf(listOf(Fixtures.app1(Fixtures.XAP, Fixtures.xmpPacket), Fixtures.sof0()))
                .xmpSegments,
        )
        val two = structureOf(
            listOf(
                Fixtures.app1(Fixtures.XAP, Fixtures.xmpPacket),
                Fixtures.app1(Fixtures.XAP, Fixtures.ascii("<x:xmpmeta/>")),
                Fixtures.sof0(),
            ),
        )
        assertEquals(2, two.xmpSegments)
    }

    @Test fun `entropy byte stuffing hides a fake eoi`() {
        // 熵数据里 FF 00 D9 是被填充过的 0xFF，不是 marker（这条测的是填充规则，
        // 与下面两条"真 FFD9 必须被跳过"的测试是两种性质，都要留着）。
        val bytes = Fixtures.jpeg(entropy = byteArrayOf(0, 0xFF.toByte(), 0x00, 0xD9.toByte(), 7, 7))
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        assertEquals(bytes.size.toLong(), structure.imageEnd)
    }

    @Test fun `ffd9 inside a length delimited segment payload is skipped`() {
        // COM 段带 2 字节长度字段，payload 里出现真 FFD9 完全合法。按字节对搜 FFD9 的
        // 实现会停在第 6 字节（返回 8），按长度走位的实现停在真正的 EOI。
        val com = Fixtures.segment(
            0xFE,
            byteArrayOf(0xFF.toByte(), 0xD9.toByte(), 0xFF.toByte(), 0xD9.toByte(), 'x'.code.toByte()),
        )
        val video = Fixtures.mp4(64)
        val bytes = Fixtures.jpeg(headSegments = listOf(com, Fixtures.sof0()), tail = video)
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        // SOI(2) + COM(2+2+5=9) + SOF0(2+2+9=13) + SOS(2+2+6=10) + entropy(4) + EOI(2) = 40
        assertEquals(40, structure.imageEnd.toInt())
        assertEquals(2 + com.size + Fixtures.sof0().size + Fixtures.sos().size + 4 + 2,
            structure.imageEnd.toInt())                        // 同一答案，由夹具尺寸独立复算
        val pairs = bytes.ffD9Positions()
        assertEquals(6, pairs.first())                          // 诱饵在 EOI 之前：夹具里真有真 FFD9
        assertTrue(structure.imageEnd > (pairs.first() + 2).toLong())  // 搜字节对的实现停在 8
    }

    @Test fun `appended video tail is not mistaken for image data`() {
        val video = Fixtures.mp4(256)
        val bytes = Fixtures.jpeg(tail = video)
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        // bytes.size - video.size 是 Int，imageEnd 是 Long：不加 toLong 会落到
        // assertEquals(Object, Object) 上，Integer(31) 与 Long(31) 永不相等。
        assertEquals((bytes.size - video.size).toLong(), structure.imageEnd)
        // 尾部现在真的带了一批 FFD9（Fixtures.mp4 会埋），所以"读完整段找最后一个 FFD9"
        // 的实现会一路跑到视频里去；真正的 EOI 必须是第一处 FFD9。
        val pairs = bytes.ffD9Positions()
        assertTrue(pairs.size > 1, "视频尾部应当还有一整批 FFD9，实际只有 $pairs")
        assertEquals(structure.imageEnd - 2, pairs.first().toLong())
        assertTrue(structure.imageEnd < pairs.last().toLong())
    }

    @Test fun `legacy exif app1 is kept but xmp app1 is located`() {
        // EXIF 的填充字节里放两处真 FFD9：APP1 是带长度字段的段，扫描器必须按长度跳过它们
        // 才拿得到真正的 XMP 段与 imageEnd。段长与原来一致（payload 14 字节）。
        val exif = Fixtures.segment(
            0xE1,
            "Exif".toByteArray() + byteArrayOf(0, 0) +
                byteArrayOf(0xFF.toByte(), 0xD9.toByte(), 0xFF.toByte(), 0xD9.toByte(), 0, 1, 2, 3),
        )
        val xmp = Fixtures.app1(Fixtures.XAP, Fixtures.xmpPacket)
        val bytes = Fixtures.jpeg(headSegments = listOf(exif, xmp, Fixtures.sof0()))
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        assertEquals(Fixtures.xmpPacket.decodeToString(), structure.oldXmpText)
        val start = bytes.indexOfSlice(xmp).toLong()
        assertEquals(start, structure.oldXmp!!.start)
        assertEquals(start + xmp.size, structure.oldXmp.endExclusive)
        val pairs = bytes.ffD9Positions()
        assertEquals(3, pairs.size)                            // EXIF 里两处诱饵 + 真正的 EOI
        assertEquals(bytes.size - 2, pairs.last())             // 最后一处才是该认的那个
        assertTrue(pairs.first() + 2 < structure.imageEnd)     // 搜字节对会停在第 14 字节
        assertEquals(bytes.size.toLong(), structure.imageEnd)  // 按长度走位才能走到真正的末尾
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
        // Fixtures.mp4 只改 filler 内容、不改长度，所以这条的算术不受影响。
        assertEquals(2 + 13 + 10 + 4 + 2, structure.imageEnd.toInt())
    }

    @Test fun `position stays byte exact across buffer refills`() {
        // 熵数据跨过两个以上的 64 KiB 缓冲区，其后还跟着一个 APP1(XMP)：
        // markerStart 与 oldXmp 区间必须仍然精确落位，缓冲区提前量不能把 position 带偏。
        val headEntropy = ByteArray(150_000) { (it % 251).toByte() }
        val xmp = Fixtures.app1(Fixtures.XAP, Fixtures.xmpPacket)
        val bytes = Fixtures.jpeg(entropy = headEntropy + xmp, tail = Fixtures.mp4(64))
        val structure = (scan(bytes) as JpegScanResult.Parsed).structure
        val xmpStart = 2L + Fixtures.sof0().size + Fixtures.sos().size + headEntropy.size
        assertEquals(xmpStart, structure.oldXmp!!.start)              // 真正的 FF E1 那一格
        assertEquals(xmpStart + xmp.size, structure.oldXmp.endExclusive)
        assertEquals(Fixtures.xmpPacket.decodeToString(), structure.oldXmpText)
        assertEquals(xmpStart + xmp.size + 2, structure.imageEnd)     // 再加 2 字节 EOI
    }

    @Test fun `entropy is read in chunks, not one byte at a time`() {
        // 260 KB 熵数据 + 300 KB 视频尾部。缓冲前：每个熵字节一次 channel read +
        // 两个小对象，约 26 万次；缓冲后：每 64 KiB 一次。这条就是钉住那个回归的。
        val entropy = ByteArray(260_000) { (it % 251).toByte() }   // 不含 0xFF，避免造出假 marker
        val video = Fixtures.mp4(300_000)
        val bytes = Fixtures.jpeg(entropy = entropy, tail = video)
        val probe = ProbeChannel(bytes)
        val structure = (JpegScanner.scan(probe) as JpegScanResult.Parsed).structure
        val imageEnd = 2L + Fixtures.sof0().size + Fixtures.sos().size + entropy.size + 2
        assertEquals(imageEnd, structure.imageEnd)
        assertTrue(probe.readCalls <= entropy.size / 8_192 + 16,
            "读取没有被分块：readCalls=${probe.readCalls}")        // 逐字节读会是 26 万量级
        assertEquals(4, probe.readCalls)
        // 常数内存：单次请求从不超过一个 64 KiB 缓冲区，绝不整文件 readBytes()。
        assertTrue(probe.maxRequested <= 64 * 1024, "maxRequested=${probe.maxRequested}")
        // 提前量有界：只可能被预读一个缓冲区，300 KB 的尾部不会被拉进内存。
        assertTrue(probe.highWaterMark - structure.imageEnd <= 64 * 1024L,
            "highWaterMark=${probe.highWaterMark}")
        assertTrue(probe.highWaterMark < bytes.size.toLong())
    }

    @Test fun `channel is left exactly at imageEnd`() {
        // Task 4/5 之后可能还要从同一个 channel 接着读，所以扫描必须停在 EOI，
        // 不能把后面的视频字节吃掉。可定位的 channel（生产环境是 FileChannel）
        // 上这条是精确成立的，不是"大概不超过一个缓冲区"。
        val video = Fixtures.mp4(4096)
        val bytes = Fixtures.jpeg(tail = video)
        val probe = ProbeChannel(bytes)
        val structure = (JpegScanner.scan(probe) as JpegScanResult.Parsed).structure
        assertEquals(31L, structure.imageEnd)
        assertEquals(structure.imageEnd, probe.position())
        val after = ByteBuffer.allocate(12)
        assertEquals(12, probe.read(after))
        // 退回的确实是视频尾部的开头（含 ftyp），不是熵数据、也不是零。
        assertArrayEquals(video.copyOfRange(0, 12), after.array())
        assertEquals("ftyp", String(after.array(), 4, 4, Charsets.ISO_8859_1))
    }

    /** 计数用的可定位 channel：记录读次数、单次请求上限、到达过的高水位与当前位置。 */
    private class ProbeChannel(private val data: ByteArray) : SeekableByteChannel {
        var readCalls = 0
            private set
        var maxRequested = 0
            private set
        var highWaterMark = 0L
            private set
        private var pos = 0L
        private var open = true

        override fun read(dst: ByteBuffer): Int {
            readCalls++
            maxRequested = maxOf(maxRequested, dst.remaining())
            if (pos >= data.size) return -1
            val n = minOf(dst.remaining(), data.size - pos.toInt())
            dst.put(data, pos.toInt(), n)
            pos += n
            highWaterMark = maxOf(highWaterMark, pos)
            return n
        }

        override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException("只读")

        override fun position(): Long = pos

        override fun position(newPos: Long): SeekableByteChannel {
            pos = newPos
            return this
        }

        override fun size(): Long = data.size.toLong()

        override fun truncate(size: Long): SeekableByteChannel = throw UnsupportedOperationException()

        override fun close() { open = false }

        override fun isOpen() = open
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

/** 所有 `FF D9` 字节对的下标（重叠的也算），用来证明夹具里真的埋了诱饵。 */
private fun ByteArray.ffD9Positions(): List<Int> {
    val out = ArrayList<Int>()
    for (i in 0..(size - 2)) {
        if (this[i] == 0xFF.toByte() && this[i + 1] == 0xD9.toByte()) out.add(i)
    }
    return out
}
