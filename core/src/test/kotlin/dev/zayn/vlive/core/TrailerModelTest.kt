package dev.zayn.vlive.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SeekableByteChannel

/**
 * `imageEnd` 之后那段尾巴该被目录声明成什么。
 * 期望值全部从**夹具自己的构造**推导（副图字节数、厂商记录字节数、尾巴=两者之和），
 * 不拿 [TrailerModel] 的输出反推输出。
 */
class TrailerModelTest {

    private fun scan(image: ByteArray): JpegStructure =
        (JpegScanner.scan(Channels.newChannel(ByteArrayInputStream(image))) as JpegScanResult.Parsed).structure

    private fun describeTrailer(image: ByteArray): TrailerModel.Description {
        val end = scan(image).imageEnd
        return TrailerModel.describe(ByteArraySource(image), end, image.size.toLong())
    }

    @Test
    fun `a gain map trailer declares the whole tail, vendor record included`() {
        val inner = Fixtures.gainMapImage()
        val vendor = Fixtures.vendorRecord()
        val d = describeTrailer(Fixtures.gainMapJpeg())
        // 夹具前提：副图 JPEG 之后确实还躺着一段非 JPEG 字节——没有它，"Length 含厂商记录"
        // 这条决定就测不到（tail 会恰好等于副图自身，两种写法都给同一个数）。
        assertTrue(vendor.isNotEmpty(), "厂商记录夹具是空的")
        assertEquals((inner.size + vendor.size).toLong(), d.gainMapBytes ?: -1L, "GainMap 的声明必须是整条尾巴")
        assertEquals(inner.size.toLong(), d.innerJpegBytes, "诚实长度应当只是副图 JPEG 自身")
        assertNull(d.paddingBytes, "尾巴已被声明成 GainMap，就不许再冒充 Padding")
    }

    @Test
    fun `a second jpeg without any gain map declaration becomes padding`() {
        // 模拟 MPF 副图：合法 JPEG、能走完，但所有 APPn 里都没有 21496/hdr-gain-map 声明。
        val secondary = Fixtures.jpeg()
        val d = describeTrailer(Fixtures.jpeg(tail = secondary))
        assertEquals(secondary.size.toLong(), d.paddingBytes)
        assertNull(d.gainMapBytes)
        assertEquals(secondary.size.toLong(), d.innerJpegBytes, "遍历是收敛的，诚实长度应当留作诊断")
    }

    @Test
    fun `a vendor json trailer becomes padding`() {
        val vendor = Fixtures.vendorTrailer()
        val d = describeTrailer(Fixtures.vendorTrailerJpeg())
        assertEquals(vendor.size.toLong(), d.paddingBytes)
        assertNull(d.gainMapBytes)
        assertNull(d.innerJpegBytes, "根本不是 JPEG，没有诚实长度可言")
    }

    @Test
    fun `a zero filled trailer becomes padding`() {
        val d = describeTrailer(Fixtures.zeroFilledJpeg())
        assertEquals(47L, d.paddingBytes)
        assertNull(d.gainMapBytes)
        assertNull(d.innerJpegBytes)
    }

    @Test
    fun `no trailer declares nothing`() {
        val d = describeTrailer(Fixtures.jpeg())
        assertNull(d.gainMapBytes)
        assertNull(d.paddingBytes)
        assertNull(d.innerJpegBytes)
    }

    @Test
    fun `a secondary jpeg whose segment length runs past the trailer degrades to padding`() {
        // FFD8 起头、APP2 声明俱在，但把它的段长度改成越界值：遍历不可能收敛。
        // 这种尾巴**不是**声明成 GainMap 的理由——坏图被说成副图才是骗目录，降级成 Padding。
        val broken = Fixtures.gainMapImage()
        // 段布局：FF D8 | FF E2 <len> …——长度字段就在偏移 4..5。
        broken[4] = 0xFF.toByte()
        broken[5] = 0xFF.toByte()
        val image = Fixtures.jpeg(tail = broken)
        val d = describeTrailer(image)
        assertEquals(broken.size.toLong(), d.paddingBytes)
        assertNull(d.gainMapBytes)
        assertNull(d.innerJpegBytes)
    }

    @Test
    fun `describe streams the trailer from imageEnd in one open and never touches the primary image`() {
        val inner = Fixtures.gainMapImage()
        val vendor = Fixtures.vendorRecord()
        // 主图故意做到 ~200 KB，远大于 64 KiB 缓冲：任何"从头整读"或"整条尾巴进内存再慢慢找"
        // 的实现都会在这里现形——读的字节数上限就是尾巴本身。
        val image = Fixtures.jpeg(entropy = ByteArray(200_000) { (it % 251).toByte() }) + inner + vendor
        val end = scan(image).imageEnd
        val trailerBytes = image.size.toLong() - end
        val source = RecordingSeekableSource(image)

        val d = TrailerModel.describe(source, end, image.size.toLong())
        assertEquals((inner.size + vendor.size).toLong(), d.gainMapBytes, "夹具前提：尾巴必须是副图+厂商记录")
        assertEquals(1, source.opens, "describe 应当只开一次来源")
        assertEquals(end, source.firstReadPosition, "读了 imageEnd 之前的主图字节：没有走定位/跳过，而是整图重读")
        assertTrue(
            source.bytesRead <= trailerBytes,
            "读出的字节超过了尾巴总长 $trailerBytes：实测 ${source.bytesRead}，说明它没有按定长缓冲流式走",
        )
    }

    /**
     * 可定位 + 可计数的来源：记录 `open()` 次数、首次读的绝对位置与累计**读出**字节数。
     * 真实来源是 `RandomAccessFile.channel`，可定位；不可定位时 TrailerModel 退化为定长缓冲跳过，
     * 这条测试钉的是生产路径不整图进内存。
     */
    private class RecordingSeekableSource(private val bytes: ByteArray) : ChannelSource {
        var opens = 0
            private set
        var bytesRead = 0L
            private set
        var firstReadPosition = Long.MAX_VALUE
            private set

        override fun size(): Long = bytes.size.toLong()

        override fun open(): SeekableByteChannel {
            opens++
            return object : SeekableByteChannel {
                private var pos = 0L
                private var open = true
                override fun read(dst: ByteBuffer): Int {
                    if (!open) throw java.nio.channels.ClosedChannelException()
                    if (pos >= bytes.size) return -1
                    if (firstReadPosition == Long.MAX_VALUE) firstReadPosition = pos
                    val n = minOf(dst.remaining().toLong(), bytes.size - pos).toInt()
                    dst.put(bytes, pos.toInt(), n)
                    pos += n
                    bytesRead += n
                    return n
                }
                override fun write(src: ByteBuffer): Int = throw UnsupportedOperationException()
                override fun position(): Long = pos
                override fun position(newPosition: Long): SeekableByteChannel {
                    pos = newPosition
                    return this
                }
                override fun size(): Long = bytes.size.toLong()
                override fun truncate(size: Long): SeekableByteChannel = this
                override fun isOpen(): Boolean = open
                override fun close() { open = false }
            }
        }
    }
}
