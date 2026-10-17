package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels

class MotionPhotoComposerTest {
    private fun compose(image: ByteArray, video: ByteArray): Pair<ComposeResult, ByteArray> {
        val out = ByteArrayOutputStream()
        val result = MotionPhotoComposer.compose(
            ByteArraySource(image), ByteArraySource(video), Channels.newChannel(out)
        )
        return result to out.toByteArray()
    }

    @Test fun `happy path writes image then video and records video size`() {
        val video = Fixtures.mp4(900)
        val image = Fixtures.jpeg()
        val (result, out) = compose(image, video)
        val success = result as ComposeResult.Success
        assertEquals(900L, success.videoSize)
        // totalBytes 必须算上新插入的 APP1：patched 只是"源图字节数"，app1 与视频都得另加。
        // 少了 app1.size 这一项，这里就会短一截（out 里可是实打实写了那 1KB 多的 XMP）。
        assertEquals(out.size.toLong(), success.totalBytes)
        assertEquals(image.size + XmpPacket.app1Segment(900L).size + video.size, out.size)
        // 尾部逐字节等于源 MP4
        assertArrayEquals(video, out.copyOfRange(out.size - video.size, out.size))
        // 头 + 新 XMP + 源图剩余 + 视频
        val expectedHead = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            XmpPacket.app1Segment(900L) + image.copyOfRange(2, image.size - 2)
        assertArrayEquals(expectedHead, out.copyOfRange(0, expectedHead.size))
        // sink 归调用方：成功路径也不许替它 close()（Task 8 用 use { } 包着它，compose 先关
        // 就等于在 rename 之前把 .part 通道关掉）。只有走完写入阶段的这条能盯住这件事。
        val owned = CountingSink()
        MotionPhotoComposer.compose(ByteArraySource(image), ByteArraySource(video), owned)
        assertTrue(owned.isOpen, "compose 关掉了调用方的 sink")
        assertEquals(owned.size().toLong(), success.totalBytes)
    }

    @Test fun `round trip locates video by file size minus offset`() {
        val video = Fixtures.mp4(1234)
        // Fixtures.mp4 已在 box header 之后每 16 字节埋入真实 FF D9 诱饵；
        // 不要再手写 video[6]/video[7]——那会覆盖 'ftyp' 签名，让 composer 先以 NOT_MP4 拒绝。
        //
        // 简报原文这里的图是 Fixtures.jpeg(tail = Fixtures.mp4(200))。带尾的源图按 spec §7.4
        // 第 3 条必然命中 ALREADY_MOTION_PHOTO（imageEnd=31 < size=231，正是下一条测试的输入），
        // compose 返回 Failure 且一个字节都不写，于是 out.size=0、start = 0 - 1234 = -1234：
        // 按简报原样跑，实测 java.lang.ArrayIndexOutOfBoundsException。那个 tail 是从 Task 4 的
        // patcher 测试抄来的：patcher 要证明"越过 EOI 一个字节都不搬"，composer 正相反——
        // 它只接受干净的 JPEG。换夹具不动任何断言：FF D9 诱饵仍然在图片 EOI 之后成片地出现，
        // 偷懒的"找最后一对 FF D9"式切分照样会被下面的边界断言抓住。
        val image = Fixtures.jpeg()
        val (result, out) = compose(image, video)
        assertInstanceOf(ComposeResult.Success::class.java, result)
        // 偏移要从**产物自己的字节**里读：简报用 XmpPacket.motionPhoto(...) 现算，验证的是生成器
        // 本身，composer 往文件里写了什么 XMP 根本没人看。
        val outText = out.toString(Charsets.ISO_8859_1)
        val offset = Regex("""GCamera:MicroVideoOffset="(\d+)"""").find(outText)!!.groupValues[1].toLong()
        assertEquals(video.size.toLong(), offset)
        // Item:Length 与 MicroVideoOffset 必须指同一个数：播放器一个用来切、一个用来核。
        assertEquals(offset, Regex("""<Item:Length>(\d+)""").find(outText)!!.groupValues[1].toLong())
        val start = out.size - offset
        assertEquals(0x66.toByte(), out[start.toInt() + 4]) // 'f' of ftyp
        assertArrayEquals(video, out.copyOfRange(start.toInt(), out.size))
        // 图片段以真 EOI 收尾、视频紧贴其后：这 2 字节是"追加"的唯一边界。
        assertEquals(0xFF.toByte(), out[start.toInt() - 2])
        assertEquals(0xD9.toByte(), out[start.toInt() - 1])
        // 字节账本：产物里的图片段 = 源图 31 字节 + 新 APP1（这张图没有旧 XMP 可丢），
        // 视频正好从那之后开始，所以 fileSize - offset 必须落在 31 + app1.size 上。
        assertEquals((image.size + XmpPacket.app1Segment(video.size.toLong()).size).toLong(), start)
    }

    @Test fun `not jpeg`() {
        // PNG 魔数：真实世界最常混进来的就是它，比简报那个带乱码的 "PNGâPNG" 更确定（不依赖源码编码）。
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) +
            byteArrayOf(0x0D, 0x0A, 0x1A, 0x0A)
        val (result, out) = compose(png, Fixtures.mp4())
        assertEquals(FailureReason.NOT_JPEG, (result as ComposeResult.Failure).reason)
        assertEquals(0, out.size)
    }

    @Test fun `not mp4 when no ftyp near the head`() {
        val bad = ByteArray(2048)
        val (result, out) = compose(Fixtures.jpeg(), bad)
        assertEquals(FailureReason.NOT_MP4, (result as ComposeResult.Failure).reason)
        assertEquals(0, out.size)
        // 顺序也要钉住：ruling 3 要求先探测视频头（最便宜），再扫 JPEG。
        // 两头都坏时答案必须是 NOT_MP4——把 JPEG 扫描挪到前面的实现会先撞上 NOT_JPEG，
        // 于是坏视频在整批任务里被误报成坏图片，报告与 SKIPPED/FAILED 分类都跟着错。
        val both = compose("PNG".toByteArray(), bad)
        assertEquals(FailureReason.NOT_MP4, (both.first as ComposeResult.Failure).reason)
        assertEquals(0, both.second.size)
        // 不足 12 字节的头没有 ftyp 的容身之处：同样 NOT_MP4，绝不抛异常。
        val tiny = compose(Fixtures.jpeg(), byteArrayOf(0, 0, 0, 4, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte()))
        assertEquals(FailureReason.NOT_MP4, (tiny.first as ComposeResult.Failure).reason)
        assertEquals(0, tiny.second.size)
    }

    @Test fun `already a motion photo is skipped without writing a byte`() {
        val embedded = Fixtures.app1(Fixtures.XAP, XmpPacket.motionPhoto(300L))
        val image = Fixtures.jpeg(headSegments = listOf(embedded, Fixtures.sof0()))
        val (result, out) = compose(image, Fixtures.mp4(300))
        assertEquals(FailureReason.ALREADY_MOTION_PHOTO, (result as ComposeResult.Failure).reason)
        assertEquals(0, out.size)
    }

    @Test fun `video appended past jpeg eoi counts as already motion photo`() {
        val image = Fixtures.jpeg(tail = Fixtures.mp4(200))
        val (result, out) = compose(image, Fixtures.mp4(300))
        assertEquals(FailureReason.ALREADY_MOTION_PHOTO, (result as ComposeResult.Failure).reason)
        assertEquals(0, out.size)
    }

    @Test fun `extended xmp is refused`() {
        val extended = Fixtures.app1(Fixtures.XMP_EXTENSION, ByteArray(40))
        val (result, out) = compose(Fixtures.jpeg(headSegments = listOf(extended)), Fixtures.mp4())
        assertEquals(FailureReason.EXTENDED_XMP_UNSUPPORTED, (result as ComposeResult.Failure).reason)
        assertEquals(0, out.size)
    }

    @Test fun `truncated jpeg is malformed`() {
        val (result, out) = compose(Fixtures.jpeg().copyOf(9), Fixtures.mp4())
        assertEquals(FailureReason.MALFORMED_JPEG, (result as ComposeResult.Failure).reason)
        assertEquals(0, out.size)
    }

    @Test fun `nothing is written before validation fails`() {
        val sink = CountingSink()
        val result = MotionPhotoComposer.compose(
            ByteArraySource(Fixtures.jpeg()), ByteArraySource(ByteArray(8)), sink
        )
        // 无 ftyp 的视频在写任何东西之前就被拒
        assertEquals(FailureReason.NOT_MP4, (result as ComposeResult.Failure).reason)
        assertFalse(sink.wroteAnything)
        // sink 归调用方所有（Task 8 用 use { } 包着它）：compose 连"失败"都不许替它关。
        assertTrue(sink.isOpen, "compose 关掉了调用方的 sink")
    }

    /** 记录"有没有被写过 / 有没有被关掉 / 一共收到多少字节"的 sink。 */
    private class CountingSink : java.nio.channels.WritableByteChannel {
        var wroteAnything = false
        private val bytes = ByteArrayOutputStream()
        private var open = true
        override fun write(src: ByteBuffer): Int {
            wroteAnything = true
            val n = src.remaining()
            val chunk = ByteArray(n)
            src.get(chunk)
            bytes.write(chunk)
            return n
        }
        override fun close() { open = false }
        override fun isOpen() = open
        fun size(): Int = bytes.size()
    }
}
