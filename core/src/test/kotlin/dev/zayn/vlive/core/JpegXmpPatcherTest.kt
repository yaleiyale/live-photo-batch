package dev.zayn.vlive.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Timeout.ThreadMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel
import java.util.concurrent.TimeUnit

/**
 * 产物布局：`SOI + 源图[2, 旧XMP起点) + 新 XMP APP1 + 源图[旧XMP终点, trailerEnd)`。
 * 新段写回旧段原来的位置，所以源片里排在 XMP 之前的 EXIF `APP1` 仍然在它之前；
 * `imageEnd` 之后的尾巴按调用方点名的 `trailerEnd` 一起搬（合成器给文件大小 = 源 JPEG 整文件），
 * 截掉尾巴的产物 vivo 相册不认。
 */
class JpegXmpPatcherTest {
    private val app1 = XmpPacket.app1Segment(7L)

    private fun scan(bytes: ByteArray): JpegStructure =
        (JpegScanner.scan(Channels.newChannel(java.io.ByteArrayInputStream(bytes)))
            as JpegScanResult.Parsed).structure

    /** 按合成器的用法搬一遍：尾巴一路搬到文件末尾。 */
    private fun patchWith(bytes: ByteArray, trailerEnd: Long = bytes.size.toLong()): Pair<ByteArray, JpegStructure> {
        val structure = scan(bytes)
        val out = ByteArrayOutputStream()
        val written = JpegXmpPatcher.patch(
            structure, ByteArraySource(bytes), Channels.newChannel(out), app1, trailerEnd
        )
        // 契约：返回值 = 从源图**实际写入 sink** 的字节数。旧 XMP 被丢弃，它那一段一个字节都没写，
        // 而 imageEnd 与 trailerEnd 之间那条尾巴全算。
        val oldXmpLength = structure.oldXmp?.length ?: 0L
        assertEquals(structure.imageEnd - oldXmpLength + (trailerEnd - structure.imageEnd), written)
        // sink 收到的总字节 = 写出的源图字节 + 新 APP1
        assertEquals(written + app1.size, out.size().toLong())
        return out.toByteArray() to structure
    }

    private fun patch(bytes: ByteArray, trailerEnd: Long = bytes.size.toLong()): ByteArray =
        patchWith(bytes, trailerEnd).first

    /** JFIF APP0：语料里的源片没有它，但"源无 XMP 时新段插在 SOI 之后"会把排好的段次序改掉，要留个证人。 */
    private fun app0(): ByteArray =
        Fixtures.segment(0xE0, Fixtures.ascii("JFIF\u0000") + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0, 0))

    private fun exif(): ByteArray =
        Fixtures.segment(0xE1, "Exif".toByteArray() + byteArrayOf(0, 0) + ByteArray(16))

    /** 源图里旧 XMP 被新段取代之后，EOI 在产物中的落点。 */
    private fun trailerStart(structure: JpegStructure, oldXmpLength: Long): Long =
        structure.imageEnd - oldXmpLength + app1.size

    @Test fun `vivo layout keeps exif before the new xmp and copies the gain map trailer`() {
        val old = Fixtures.app1(Fixtures.XAP, Fixtures.xmpPacket)
        val dqt = Fixtures.segment(0xDB, ByteArray(33) { it.toByte() })
        val trailer = Fixtures.gainMapImage(entropySize = 200)
        val bytes = Fixtures.jpeg(
            headSegments = listOf(app0(), exif(), old, dqt, Fixtures.sof0()),
            entropy = ByteArray(64) { (it % 251).toByte() },
            tail = trailer,
        )
        val (result, structure) = patchWith(bytes)
        val oldXmp = structure.oldXmp!!
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
                bytes.copyOfRange(2, oldXmp.start.toInt()) + app1 +
                bytes.copyOfRange(oldXmp.endExclusive.toInt(), bytes.size),
            result,
        )
        // 副图原样在位：它自己的 hdrg 盒只能来自 EOI 之后那段。
        assertTrue(result.toString(Charsets.ISO_8859_1).contains("hdrg"), "gain map 副图没被搬进产物")
        assertEquals(trailerStart(structure, oldXmp.length) + trailer.size, result.size.toLong())
        // 段次序：APP0 → EXIF → 新 XMP → 其余。EXIF 必须仍然在 XMP 之前。
        assertEquals(2, result.indexOfSlice(app0()))
        assertEquals(2 + app0().size, result.indexOfSlice(exif()))
        assertEquals(2 + app0().size + exif().size, result.indexOfSlice(app1))
        assertEquals(2 + app0().size + exif().size + app1.size, result.indexOfSlice(dqt))
        // 主图的 EOI 现在躺在尾巴之前；产物末尾那对 FF D9 是副图自己的。
        assertEquals(0xFF.toByte(), result[trailerStart(structure, oldXmp.length).toInt() - 2])
        assertEquals(0xD9.toByte(), result[trailerStart(structure, oldXmp.length).toInt() - 1])
        assertEquals(0xFF.toByte(), result[result.size - 2])
        assertEquals(0xD9.toByte(), result[result.size - 1])
    }

    @Test fun `source without xmp gets the new segment right after soi`() {
        // 契约：源无 XMP 时插在 SOI 之后，新段因此把 APP0 推到它后面。
        // 语料里的源片都带 XMP，这种形状没有参考实现可比，按字面断言，不为它加特例。
        val bytes = Fixtures.jpeg(headSegments = listOf(app0(), Fixtures.sof0()))
        val result = patch(bytes)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte()), result.copyOfRange(0, 2))
        assertArrayEquals(app1, result.copyOfRange(2, 2 + app1.size))
        assertEquals(2 + app1.size, result.indexOfSlice(app0()))
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 + bytes.copyOfRange(2, bytes.size),
            result,
        )
    }

    @Test fun `exif and quant table segments survive byte for byte`() {
        val exif = exif()
        val dqt = Fixtures.segment(0xDB, ByteArray(33) { it.toByte() })
        val bytes = Fixtures.jpeg(headSegments = listOf(exif, dqt, Fixtures.sof0()))
        val result = patch(bytes)
        // 源图里除旧 XMP 外的全部字节顺序不变；这里没有旧 XMP，所以整份源图都在产物里。
        assertArrayEquals(
            bytes.copyOfRange(2, bytes.size - 2),           // 去掉 SOI 与尾部 EOI
            result.copyOfRange(2 + app1.size, result.size - 2)
        )
        // EXIF 与 APP1(XMP) 同为 0xE1：把 marker 当类型来处理、或按 marker 去重的实现
        // 会吞掉这条，所以再直接点名 EXIF 段必须原样在场。
        assertEquals(2 + app1.size, result.indexOfSlice(exif))
        assertEquals(2 + app1.size + exif.size, result.indexOfSlice(dqt))
    }

    @Test fun `old xmp is dropped and replaced by exactly one new xmp in its own place`() {
        // 旧 XMP 前面先放一段 EXIF APP1——真实相机的 JPEG 就是这个顺序，也是语料的顺序。
        // 把新段插在 SOI 之后的实现会让 XMP 跑到 EXIF 前面，相册不认。
        val exif = Fixtures.segment(
            0xE1,
            "Exif".toByteArray() + byteArrayOf(0xFF.toByte(), 0xD9.toByte(), 0, 1, 2, 3),
        )
        val old = Fixtures.app1(Fixtures.XAP, Fixtures.xmpPacket)
        val bytes = Fixtures.jpeg(headSegments = listOf(exif, old, Fixtures.sof0()))
        val structure = scan(bytes)
        val result = patch(bytes)
        val occurrences = Regex(Regex.escape(Fixtures.XAP)).findAll(result.decodeToString()).count()
        assertEquals(1, occurrences)
        assertTrue(result.decodeToString().contains("MotionPhoto"))
        assertFalse(result.decodeToString().contains("OLD"))
        // 精确字节映射：SOI 之后先是源图的 EXIF，再是落在旧段位置上的新段，其后才是图片尾部。
        val oldXmp = structure.oldXmp!!
        assertEquals((2 + exif.size).toLong(), oldXmp.start)
        assertEquals((2 + exif.size + old.size).toLong(), oldXmp.endExclusive)
        assertEquals(bytes.size.toLong(), structure.imageEnd)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + exif + app1 +
                bytes.copyOfRange(oldXmp.endExclusive.toInt(), structure.imageEnd.toInt()),
            result,
        )
        // EXIF 里的 FFD9 诱饵不该被当成图片终点：EOI 必须还是输出的最后两字节。
        assertEquals(0xFF.toByte(), result[result.size - 2])
        assertEquals(0xD9.toByte(), result[result.size - 1])
    }

    @Test fun `only the bytes up to trailerEnd cross the EOI`() {
        val video = Fixtures.mp4(512)
        val bytes = Fixtures.jpeg(tail = video)
        val structure = scan(bytes)
        // 点名只要 200 字节尾巴：越过 trailerEnd 的那 312 字节一个都不许写进 sink。
        val trailerEnd = structure.imageEnd + 200
        val out = ByteArrayOutputStream()
        val written = JpegXmpPatcher.patch(
            structure, ByteArraySource(bytes), Channels.newChannel(out), app1, trailerEnd
        )
        assertEquals(structure.imageEnd + 200, written)     // 这张图没有旧 XMP
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 +
                bytes.copyOfRange(2, trailerEnd.toInt()),
            out.toByteArray(),
        )
        // 反向的输入也不许放过：trailerEnd 落在 imageEnd 之前就是"少搬"，必须当场拒绝。
        assertThrows(IllegalArgumentException::class.java) {
            JpegXmpPatcher.patch(
                structure, ByteArraySource(bytes), Channels.newChannel(out), app1,
                trailerEnd = structure.imageEnd - 1
            )
        }
    }

    @Test fun `the default trailerEnd stops at the EOI`() {
        val video = Fixtures.mp4(512)
        val bytes = Fixtures.jpeg(tail = video)
        val structure = scan(bytes)
        val out = ByteArrayOutputStream()
        val written = JpegXmpPatcher.patch(structure, ByteArraySource(bytes), Channels.newChannel(out), app1)
        assertEquals(structure.imageEnd, written)
        val result = out.toByteArray()
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 + bytes.copyOfRange(2, structure.imageEnd.toInt()),
            result,
        )
        // 没点名要尾巴时就停在 EOI：视频字节一个都不在。
        assertFalse(result.toString(Charsets.ISO_8859_1).contains("ftyp"))
    }

    @Test fun `a non video trailer is copied whatever it holds`() {
        // gain map 副图、厂商记录、零填充都只是"非视频尾巴"，
        // 一律原样搬到 XMP 之后、视频之前——合成器把 trailerEnd 传到文件末尾。
        for (trailer in listOf(Fixtures.gainMapImage(entropySize = 1024), Fixtures.vendorTrailer(), Fixtures.zeroFill(47))) {
            val bytes = Fixtures.jpeg(tail = trailer)
            val structure = scan(bytes)
            assertEquals((bytes.size - trailer.size).toLong(), structure.imageEnd, "夹具的尾巴确实躺在 EOI 之后")
            val result = patch(bytes)
            assertEquals(structure.imageEnd + trailer.size + app1.size, result.size.toLong())
            assertArrayEquals(
                byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 + bytes.copyOfRange(2, bytes.size),
                result,
            )
            assertArrayEquals(trailer, result.copyOfRange(result.size - trailer.size, result.size))
        }
    }

    @Test fun `the old xmp is replaced while the trailer is kept`() {
        val exif = Fixtures.segment(0xE1, "Exif".toByteArray() + byteArrayOf(0, 1, 2, 3))
        val old = Fixtures.app1(Fixtures.XAP, Fixtures.xmpPacket)
        val trailer = Fixtures.vendorTrailer()
        val bytes = Fixtures.jpeg(headSegments = listOf(exif, old, Fixtures.sof0()), tail = trailer)
        val structure = scan(bytes)
        val result = patch(bytes)
        // 只有旧 XMP 被丢掉：返回值与产物长度都算上尾巴。
        assertEquals((bytes.size - old.size + app1.size).toLong(), result.size.toLong())
        assertEquals(1, Regex(Regex.escape(Fixtures.XAP)).findAll(result.decodeToString()).count())
        assertFalse(result.decodeToString().contains("OLD"))
        assertTrue(result.toString(Charsets.ISO_8859_1).contains("cameralbum!"), "厂商记录没被搬进产物")
        // 尾巴就在新段与图片主体之后：上面那一条数组比较已经钉死"末尾整段就是源片那段尾巴"。
        assertArrayEquals(trailer, result.copyOfRange(result.size - trailer.size, result.size))
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
        val structure = scan(bytes)
        val out = ByteArrayOutputStream()
        // structure 说 imageEnd=31，源里却只有 10 字节：必须炸，不能安静写完前 10 字节就返回。
        val error = assertThrows(java.io.EOFException::class.java) {
            JpegXmpPatcher.patch(structure, ByteArraySource(bytes.copyOf(10)),
                Channels.newChannel(out), app1, trailerEnd = bytes.size.toLong())
        }
        // 必须是 IOException 子类，composer 才能把它归进 IO_ERROR。
        assertInstanceOf(java.io.IOException::class.java, error)
    }

    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    @Test fun `truncation inside the discarded old segment throws too`() {
        val exif = Fixtures.segment(0xE1, "Exif".toByteArray() + byteArrayOf(0, 1, 2, 3))
        val old = Fixtures.app1(Fixtures.XAP, Fixtures.xmpPacket)
        val bytes = Fixtures.jpeg(headSegments = listOf(exif, old, Fixtures.sof0()))
        val structure = scan(bytes)
        val oldXmp = structure.oldXmp!!
        // 源在"要读掉的旧 XMP"中间就断了：写出分支一切正常，缺字节的是丢弃分支。这条专门盯
        // 那一步的短读处理——"能跳多少算多少"式的 skip 只返回 0/-1：要么旧段字节被当成图片
        // 内容写进输出，要么循环条件永不满足而原地挂死，所以整条跑在独立线程 + 超时上。
        val truncated = bytes.copyOf((oldXmp.start + old.size / 2).toInt())
        val out = ByteArrayOutputStream()
        val error = assertThrows(java.io.EOFException::class.java) {
            JpegXmpPatcher.patch(structure, ByteArraySource(truncated), Channels.newChannel(out), app1)
        }
        assertInstanceOf(java.io.IOException::class.java, error)
        // 已经吐给 sink 的部分里不许混进旧段内容：断掉的那一步只能是"读掉并丢弃"。
        assertFalse(out.toByteArray().toString(Charsets.ISO_8859_1).contains("OLD"))
    }

    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    @Test fun `a sink that refuses the write fails fast instead of spinning`() {
        val bytes = Fixtures.jpeg()
        val structure = scan(bytes)
        val sink = RejectingSink()
        // write 返回 0 既不是"写完"也不是"没东西可写"：必须立刻变成 IOException。
        // 少了这个判断，排空循环原地挂死，调用方会把挂死当成成功。
        val error = assertThrows(java.io.IOException::class.java) {
            JpegXmpPatcher.patch(structure, ByteArraySource(bytes), sink, app1)
        }
        // 是 sink 拒绝，不是源截断：分类成 EOFException 会掩盖真正的写入故障。
        assertEquals(java.io.IOException::class.java, error.javaClass)
        assertTrue(sink.calls > 0, "calls=${sink.calls}")
    }

    /** write 永远返回 0 的 sink：任何字节都不肯接收。 */
    private class RejectingSink : WritableByteChannel {
        var calls = 0
            private set
        private var open = true

        override fun write(src: ByteBuffer): Int {
            calls++
            return 0
        }

        override fun close() { open = false }

        override fun isOpen() = open
    }

    @Test fun `partial writes from the sink are drained and memory stays constant`() {
        val entropy = ByteArray(200_000) { (it % 251).toByte() }   // 不含 0xFF，不造出假 marker
        val bytes = Fixtures.jpeg(entropy = entropy)
        val structure = scan(bytes)
        val sink = ChunkSink(3_000)
        val written = JpegXmpPatcher.patch(structure, ByteArraySource(bytes), sink, app1)
        assertEquals(structure.imageEnd, written)                  // 无旧 XMP、无尾巴：整段图片都要搬
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
