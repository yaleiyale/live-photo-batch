package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel

class JpegXmpPatcherTest {
    private val app1 = XmpPacket.app1Segment(7L)

    private fun patch(bytes: ByteArray): ByteArray {
        val structure = JpegScanner.scan(Channels.newChannel(java.io.ByteArrayInputStream(bytes)))
            as JpegScanResult.Parsed
        val s = structure.structure
        val out = ByteArrayOutputStream()
        val written = JpegXmpPatcher.patch(s, ByteArraySource(bytes), Channels.newChannel(out), app1)
        // 契约：返回值 = 从源图**实际写入 sink** 的字节数。旧 XMP 被丢弃，它那一段一个字节都没写，
        // 所以有旧 XMP 时是 imageEnd - oldXmp.length，而不是 imageEnd。
        // 简报原文在这里写的是 `assertEquals(imageEnd, written)`，与同一个类里
        // `old xmp is dropped and replaced`（断言输出里没有 "OLD"）自相矛盾：
        // 既不写旧段字节、又数出 imageEnd 个源字节，是不可能的。实测 115 vs 31，31 = 115 - 84。
        val oldXmpLength = s.oldXmp?.length ?: 0L
        assertEquals(s.imageEnd - oldXmpLength, written)
        // sink 收到的总字节 = 源图字节 + 新 APP1（SOI 已计入 written）
        assertEquals(written + app1.size, out.size().toLong())
        return out.toByteArray()
    }

    @Test fun `new xmp sits immediately after soi`() {
        val result = patch(Fixtures.jpeg())
        assertEquals(0xFF.toByte(), result[0])
        assertEquals(0xD8.toByte(), result[1])
        assertEquals(0xFF.toByte(), result[2])
        assertEquals(0xE1.toByte(), result[3])
        assertArrayEquals(app1, result.copyOfRange(2, 2 + app1.size))
    }

    @Test fun `exif and quant table segments survive byte for byte`() {
        val exif = Fixtures.segment(0xE1, "Exif".toByteArray() + byteArrayOf(0, 0) + ByteArray(16))
        val dqt = Fixtures.segment(0xDB, ByteArray(33) { it.toByte() })
        val bytes = Fixtures.jpeg(headSegments = listOf(exif, dqt, Fixtures.sof0()))
        val result = patch(bytes)
        // 源图里除旧 XMP 外的全部字节顺序不变；这里没有旧 XMP，所以尾部应完全等于源图 EOI 前内容
        assertArrayEquals(
            bytes.copyOfRange(2, bytes.size - 2),           // 去掉 SOI 与尾部 EOI
            result.copyOfRange(2 + app1.size, result.size - 2)
        )
        // EXIF 与 APP1(XMP) 同为 0xE1：把 marker 当类型来处理、或按 marker 去重的实现
        // 会吞掉这条，所以再直接点名 EXIF 段必须原样在场。
        assertEquals(2 + app1.size, result.indexOfSlice(exif))
        assertEquals(2 + app1.size + exif.size, result.indexOfSlice(dqt))
    }

    @Test fun `old xmp is dropped and replaced by exactly one new xmp`() {
        // 旧 XMP 前面先放一段 EXIF APP1——真实相机的 JPEG 就是这个顺序。简报原文把旧 XMP
        // 直接摆在 SOI 之后（oldXmp.start == 2），那条 [2, oldXmp.start) 的区间恒为 0 字节，
        // "gap 非空时要先搬 gap"这条算术一次都没被走过。加了 EXIF 前缀才真的覆盖到。
        val exif = Fixtures.segment(
            0xE1,
            "Exif".toByteArray() + byteArrayOf(0xFF.toByte(), 0xD9.toByte(), 0, 1, 2, 3),
        )
        val old = Fixtures.app1(Fixtures.XAP, Fixtures.xmpPacket)
        val bytes = Fixtures.jpeg(headSegments = listOf(exif, old, Fixtures.sof0()))
        val structure = (JpegScanner.scan(Channels.newChannel(java.io.ByteArrayInputStream(bytes)))
            as JpegScanResult.Parsed).structure
        val result = patch(bytes)
        val occurrences = Regex(Regex.escape(Fixtures.XAP)).findAll(result.decodeToString()).count()
        assertEquals(1, occurrences)
        assertTrue(result.decodeToString().contains("MotionPhoto"))
        assertFalse(result.decodeToString().contains("OLD"))
        // 精确字节映射：source[2,16) 的 EXIF 原样保留，source[16,100) 的旧 XMP（84 字节）整段
        // 消失，其后 29 字节按原序紧跟新 APP1。这条同时钉住两件事：漏掉"读掉旧段"这一步
        // （实测写出的是旧段开头 29 字节、真正的图片尾部整个丢失），以及越过 imageEnd 多搬。
        val oldXmp = structure.oldXmp!!
        assertEquals((2 + exif.size).toLong(), oldXmp.start)                  // 16
        assertEquals((2 + exif.size + old.size).toLong(), oldXmp.endExclusive) // 100
        assertEquals(bytes.size.toLong(), structure.imageEnd)                  // 129，无追加尾部
        val expected = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 +
            bytes.copyOfRange(2, oldXmp.start.toInt()) +
            bytes.copyOfRange(oldXmp.endExclusive.toInt(), structure.imageEnd.toInt())
        assertArrayEquals(expected, result)
        // EXIF 里的 FFD9 诱饵不该被当成图片终点：EOI 必须还是输出的最后两字节。
        assertEquals(0xFF.toByte(), result[result.size - 2])
        assertEquals(0xD9.toByte(), result[result.size - 1])
    }

    @Test fun `appended video tail is not copied`() {
        val video = Fixtures.mp4(512)
        val bytes = Fixtures.jpeg(tail = video)
        val result = patch(bytes)
        assertEquals(bytes.size - video.size + app1.size.toLong(), result.size.toLong())
        // 输出最后两格就是图片段的 EOI（FF 在前、D9 在后）。简报原文写的是
        // result[result.size - 2 - 1]，即倒数第三格——那一格是熵数据的最后一个字节 0x44，
        // 实测 expected <-39>(0xD9) but was <68>(0x44)。真正的 D9 在 size-1。
        assertEquals(0xFF.toByte(), result[result.size - 2])
        assertEquals(0xD9.toByte(), result[result.size - 1])
        // 视频一个字节都不许进来：抄进 imageEnd 之后的任何字节都会露出 ftyp 签名。
        assertFalse(result.toString(Charsets.ISO_8859_1).contains("ftyp"))
    }

    @Test fun `extension xmp is left untouched`() {
        val extended = Fixtures.app1(Fixtures.XMP_EXTENSION, ByteArray(40))
        val bytes = Fixtures.jpeg(headSegments = listOf(extended, Fixtures.sof0()))
        val result = patch(bytes)
        assertTrue(result.indexOfSlice(extended) > app1.size)
        // 扫描器只把它记为 hasExtendedXmp，复制器必须原样搬过去：位置恰好在 SOI + 新 APP1 之后。
        assertEquals(2 + app1.size, result.indexOfSlice(extended))
    }

    @Test fun `truncated source throws instead of emitting a short file`() {
        val bytes = Fixtures.jpeg()
        val structure = (JpegScanner.scan(Channels.newChannel(java.io.ByteArrayInputStream(bytes)))
            as JpegScanResult.Parsed).structure
        val out = ByteArrayOutputStream()
        // structure 说 imageEnd=31，源里却只有 10 字节：必须炸，不能安静写完前 10 字节就返回。
        val error = assertThrows(java.io.EOFException::class.java) {
            JpegXmpPatcher.patch(structure, ByteArraySource(bytes.copyOf(10)),
                Channels.newChannel(out), app1)
        }
        // 必须是 IOException 子类，Task 5 的 composer 才能把它归进 IO_ERROR。
        assertInstanceOf(java.io.IOException::class.java, error)
    }

    @Test fun `partial writes from the sink are drained and memory stays constant`() {
        val entropy = ByteArray(200_000) { (it % 251).toByte() }   // 不含 0xFF，不造出假 marker
        val bytes = Fixtures.jpeg(entropy = entropy)
        val structure = (JpegScanner.scan(Channels.newChannel(java.io.ByteArrayInputStream(bytes)))
            as JpegScanResult.Parsed).structure
        val sink = ChunkSink(3_000)
        val written = JpegXmpPatcher.patch(structure, ByteArraySource(bytes), sink, app1)
        assertEquals(structure.imageEnd, written)                  // 无旧 XMP：整段图片都要搬
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 + bytes.copyOfRange(2, bytes.size),
            sink.bytes()
        )
        // 一次 write 就吞 3000 字节的 sink，必须被反复调用才能搬完 200 KB——
        // 只 put 一次的实现写出的是短文件，上面的数组比较先失败，这条给出定位信息。
        assertTrue(sink.writeCalls > 60, "writeCalls=${sink.writeCalls}")
        // 常数内存：交给 sink 的单个缓冲区从不超过一个 64 KiB 缓冲，绝不整图进内存。
        assertTrue(sink.maxRequested <= 64 * 1024, "maxRequested=${sink.maxRequested}")
    }

    /** 每次 write 只肯吞 [limit] 字节的 sink，并记录被请求的上限。 */
    private class ChunkSink(private val limit: Int) : WritableByteChannel {
        private val out = ByteArrayOutputStream()
        var writeCalls = 0
            private set
        var maxRequested = 0
            private set
        private var open = true

        override fun write(src: ByteBuffer): Int {
            writeCalls++
            maxRequested = maxOf(maxRequested, src.remaining())
            val n = minOf(limit, src.remaining())
            val chunk = ByteArray(n)
            src.get(chunk)
            out.write(chunk)
            return n
        }

        override fun close() { open = false }

        override fun isOpen() = open

        fun bytes(): ByteArray = out.toByteArray()
    }
}

private fun ByteArray.indexOfSlice(slice: ByteArray): Int {
    outer@ for (i in 0..(size - slice.size)) {
        for (j in slice.indices) if (this[i + j] != slice[j]) continue@outer
        return i
    }
    return -1
}
