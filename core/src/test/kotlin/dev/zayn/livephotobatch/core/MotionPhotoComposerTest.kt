package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.fail
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
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

    private fun scan(image: ByteArray): JpegStructure =
        (JpegScanner.scan(Channels.newChannel(java.io.ByteArrayInputStream(image)))
            as JpegScanResult.Parsed).structure

    /** 夹具图都是"单段 EOI 收尾"，所以最后一对 FF D9 就是它——只在这条辅助函数里允许这种找法。 */
    private fun ByteArray.indexOfEoi(): Int =
        (size - 2 downTo 0).first { this[it] == 0xFF.toByte() && this[it + 1] == 0xD9.toByte() }

    /**
     * 产物长度的唯一算法（spec 增补 2026-10-18b §2）：`imageEnd` 扣掉被丢弃的旧 XMP，加上新 APP1，
     * 再加上视频字节。`imageEnd` 之后的源字节一个都不搬，所以尾巴不在账上。
     */
    private fun expectedSize(imageEnd: Long, droppedOldXmp: Int, app1Size: Int, videoSize: Int): Long =
        imageEnd - droppedOldXmp + app1Size + videoSize

    /**
     * §4 的布局判据：`文件大小 - MicroVideoOffset == 重扫产物得到的 imageEnd`，且 imageEnd 起前 12
     * 字节内出现 ftyp——EOI 与视频起点之间零字节。偏移量从**产物自己的字节**里读，imageEnd 靠重扫，
     * 两头都不硬编码。
     */
    private fun assertVideoFollowsEoi(out: ByteArray): Long {
        val text = out.toString(Charsets.ISO_8859_1)
        val offset = Regex("""GCamera:MicroVideoOffset="(\d+)"""").find(text)!!.groupValues[1].toLong()
        val start = out.size - offset
        assertEquals(scan(out).imageEnd, start, "文件大小减 MicroVideoOffset 不等于产物自己的 imageEnd")
        assertEquals(0xFF.toByte(), out[(start - 2).toInt()])
        assertEquals(0xD9.toByte(), out[(start - 1).toInt()])
        val window = out.copyOfRange(start.toInt(), minOf(start.toInt() + 12, out.size))
            .toString(Charsets.ISO_8859_1)
        assertTrue(window.contains("ftyp"), "EOI 之后 12 字节内没有 ftyp：中间夹了别的字节")
        return offset
    }

    @Test fun `happy path writes image then video and records video size`() {
        val video = Fixtures.mp4(900)
        val image = Fixtures.jpeg()
        val (result, out) = compose(image, video)
        val success = result as ComposeResult.Success
        assertEquals(900L, success.videoSize)
        // totalBytes 必须算上新插入的 APP1：patched 只是"源图字节数"，app1 与视频都得另加。
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
        // Fixtures.mp4 已在 box header 之后每 16 字节埋入真实 FF D9 诱饵，"找最后一对 FF D9"的
        // 偷懒切分照样会被下面重扫 imageEnd 那一步抓住。
        val image = Fixtures.jpeg()
        val (result, out) = compose(image, video)
        assertInstanceOf(ComposeResult.Success::class.java, result)
        val offset = assertVideoFollowsEoi(out)
        assertEquals(video.size.toLong(), offset, "MicroVideoOffset 不是追加的视频字节数")
        assertArrayEquals(video, out.copyOfRange(out.size - video.size, out.size))
        // 字节账本：这张图没有旧 XMP 可丢、也没有尾巴，所以视频正好从 31 + app1.size 开始。
        assertEquals((image.size + XmpPacket.app1Segment(video.size.toLong()).size).toLong(), out.size - offset)
    }

    // ---------- spec「增补（2026-10-18b）」§2：EOI 之后只放视频 ----------

    @Test fun `gain map trailer is dropped and the xmp is merged, not replaced`() {
        val image = Fixtures.gainMapJpeg()
        val video = Fixtures.mp4(300)
        val structure = scan(image)
        val trailer = image.copyOfRange(structure.imageEnd.toInt(), image.size)
        assertTrue(trailer.size > 4000, "夹具的副图应当是一段真实 JPEG，实测 ${trailer.size}")

        val (result, out) = compose(image, video)
        val success = assertInstanceOf(ComposeResult.Success::class.java, result)
        val app1 = XmpPacket.app1SegmentFor(XmpMerger.merge(structure.oldXmpText, video.size.toLong()))
        val oldXmp = structure.oldXmp ?: fail("gain map 夹具必须带一段源 XMP")
        assertEquals(
            expectedSize(structure.imageEnd, oldXmp.length.toInt(), app1.size, video.size),
            out.size.toLong(),
        )
        assertEquals(out.size.toLong(), success.totalBytes, "totalBytes 不是产物文件的真实字节数")

        // SOI + 源图[2, 旧XMP) + 合并 APP1 + 源图[旧XMP 终点, imageEnd) + 视频：尾巴不在账上。
        val imagePart = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            image.copyOfRange(2, oldXmp.start.toInt()) + app1 +
            image.copyOfRange(oldXmp.endExclusive.toInt(), structure.imageEnd.toInt())
        assertArrayEquals(imagePart, out.copyOfRange(0, imagePart.size))
        assertArrayEquals(video, out.copyOfRange(imagePart.size, out.size))

        // 副图字节的声明一起消失，但源片自己的 hdrgm 版本留着。
        val text = out.toString(Charsets.ISO_8859_1)
        assertFalse(text.contains("<Item:Semantic>GainMap</Item:Semantic>"), "产物不携带副图字节，却还声明副图项")
        assertFalse(text.contains("Item:Length"), "副图长度声明还留着")
        assertFalse(text.contains("<Item:Semantic>MotionPhoto</Item:Semantic>"), "视频项也不该声明")
        assertFalse(text.contains("21496"), "gain map 副图的 APP2 标识符还跟着进了产物")
        assertTrue(text.contains("hdrgm:Version=\"1.0\""), "HDR 版本声明被替换掉了")
        assertTrue(text.contains("GCamera:MotionPhoto=\"1\""), "产物没写成动态照片")
        assertEquals(video.size.toLong(), assertVideoFollowsEoi(out))
    }

    @Test fun `exif app1 stays before the xmp app1 in the product`() {
        // 语料的段序是 SOI → APP0 → EXIF APP1 → XMP APP1 → …；Task 13 把新段插在 SOI 之后，
        // EXIF 被挤到了 XMP 后面。这里用真合成的产物形状钉住"原位写回"。
        val exif = Fixtures.segment(0xE1, "Exif".toByteArray() + byteArrayOf(0, 0) + ByteArray(32))
        val old = Fixtures.app1(Fixtures.XAP, Fixtures.gainMapXmp(1234L).toByteArray(Charsets.UTF_8))
        val app0 = Fixtures.segment(0xE0, Fixtures.ascii("JFIF\u0000") + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0, 0))
        val image = Fixtures.jpeg(
            headSegments = listOf(app0, exif, old, Fixtures.sof0()),
            entropy = ByteArray(64) { (it % 251).toByte() },
            tail = Fixtures.gainMapImage(entropySize = 200),
        )
        val video = Fixtures.mp4(512)
        val (result, out) = compose(image, video)
        assertInstanceOf(ComposeResult.Success::class.java, result)
        val exifAt = out.indexOfSlice(exif)
        // 新段就落在旧段原来的位置上：XAP 标识符前面第三、四字节必须是 FF E1。
        val xmpAt = out.indexOfSlice(XmpPacket.XAP_IDENTIFIER.toByteArray(Charsets.ISO_8859_1))
        assertTrue(xmpAt > 0, "产物里没有 XAP 标识符")
        assertEquals(1, Regex(Regex.escape(Fixtures.XAP)).findAll(out.toString(Charsets.ISO_8859_1)).count())
        assertEquals(0xFF.toByte(), out[xmpAt - 4])
        assertEquals(0xE1.toByte(), out[xmpAt - 3])
        assertTrue(exifAt in 0..<xmpAt, "EXIF 跑到新 XMP 后面去了：exifAt=$exifAt xmpAt=$xmpAt")
        assertVideoFollowsEoi(out)
        assertFalse(out.toString(Charsets.ISO_8859_1).contains("21496"), "gain map 副图还跟着进了产物")
    }

    @Test fun `vendor json trailer is dropped`() {
        val image = Fixtures.vendorTrailerJpeg()
        val video = Fixtures.mp4(512)
        val structure = scan(image)
        val trailer = image.copyOfRange(structure.imageEnd.toInt(), image.size)
        assertEquals(399, trailer.size)
        assertEquals("vivo", trailer.copyOfRange(0, 4).toString(Charsets.ISO_8859_1))

        val (result, out) = compose(image, video)
        assertInstanceOf(ComposeResult.Success::class.java, result)
        assertEquals(
            expectedSize(structure.imageEnd, 0, XmpPacket.app1Segment(video.size.toLong()).size, video.size),
            out.size.toLong(),
        )
        // 尾巴留在源文件里，不进产物：厂商记录只在视频之前出现过一次的可能位置是 imageEnd 之前。
        assertFalse(out.toString(Charsets.ISO_8859_1).contains("cameralbum!"), "厂商记录被搬进了产物")
        assertArrayEquals(video, out.copyOfRange(out.size - video.size, out.size))
        // 没有源 XMP 的图仍然走模板：产物里的 APP1 与 XmpPacket.app1Segment 一字不差
        val app1 = XmpPacket.app1Segment(video.size.toLong())
        assertArrayEquals(app1, out.copyOfRange(2, 2 + app1.size))
    }

    @Test fun `zero filled trailer is dropped`() {
        val image = Fixtures.zeroFilledJpeg()
        val video = Fixtures.mp4(256)
        val structure = scan(image)
        val (result, out) = compose(image, video)
        assertInstanceOf(ComposeResult.Success::class.java, result)
        val trailer = image.copyOfRange(structure.imageEnd.toInt(), image.size)
        assertEquals(47, trailer.size)
        assertEquals(
            expectedSize(structure.imageEnd, 0, XmpPacket.app1Segment(video.size.toLong()).size, video.size),
            out.size.toLong(),
        )
        // 47 个零字节要是被搬进来，视频起点就会往后挪 47 字节，这条等式立刻不成立。
        assertEquals(video.size.toLong(), assertVideoFollowsEoi(out))
    }

    @Test fun `gain map trailer with no xmp at all is dropped`() {
        val image = Fixtures.jpeg(tail = Fixtures.gainMapImage(entropySize = 128))
        val video = Fixtures.mp4(128)
        val (result, out) = compose(image, video)
        assertInstanceOf(ComposeResult.Success::class.java, result)
        assertFalse(out.toString(Charsets.ISO_8859_1).contains("21496"), "副图字节被搬进了产物")
        assertEquals(video.size.toLong(), assertVideoFollowsEoi(out))
        assertArrayEquals(video, out.copyOfRange(out.size - video.size, out.size))
    }

    @Test fun `composed output of the previous run is still skipped`() {
        // 自己上一轮的产物：尾巴是 ftyp 视频 + XMP 里有 GCamera:MicroVideoOffset，两个信号都对。
        val (result, out) = compose(Fixtures.composedMotionPhotoJpeg(300L), Fixtures.mp4(300))
        assertEquals(FailureReason.ALREADY_MOTION_PHOTO, (result as ComposeResult.Failure).reason)
        assertEquals(0, out.size)
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

    @Test fun `unparseable source xmp fails as malformed xmp and writes nothing`() {
        // 简报要求：畸形 XMP 报 FAILED，绝不"当它没有 XMP"然后静默丢掉用户的元数据。
        val broken = Fixtures.app1(Fixtures.XAP, "<x:xmpmeta><unclosed>".toByteArray(Charsets.UTF_8))
        val image = Fixtures.jpeg(headSegments = listOf(broken, Fixtures.sof0()))
        val sink = CountingSink()
        val result = MotionPhotoComposer.compose(ByteArraySource(image), ByteArraySource(Fixtures.mp4(64)), sink)
        assertEquals(FailureReason.MALFORMED_XMP, (result as ComposeResult.Failure).reason)
        assertFalse(sink.wroteAnything, "校验阶段的失败也往 sink 里写了字节")
        assertTrue(sink.isOpen)
        // 同一段畸形文本配上 DTD 也一样：安全解析不是可以绕过去的
        val dtdText = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF" +
            " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description>&e;" +
            "</rdf:Description></rdf:RDF></x:xmpmeta>"
        val doctype = Fixtures.app1(Fixtures.XAP, dtdText.toByteArray(Charsets.UTF_8))
        val bad2 = Fixtures.jpeg(headSegments = listOf(doctype, Fixtures.sof0()))
        assertEquals(
            FailureReason.MALFORMED_XMP,
            (compose(bad2, Fixtures.mp4(64)).first as ComposeResult.Failure).reason,
        )
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

    @Test fun `write phase io error leaves partial bytes in the sink`() {
        val video = Fixtures.mp4(4096)
        val image = Fixtures.jpeg()
        // patch 全程跑完后 sink 里的字节数 = 源图 + 新 APP1（这张图没有旧 XMP 可丢）。
        val imagePart = image.size + XmpPacket.app1Segment(video.size.toLong()).size
        // 上限故意设在图片段**之后**：坏掉的必须是写入阶段的视频拷贝，而不是任何一条校验。
        // 校验阶段的失败永远不碰 sink（上一条测试盯着），只有这里是"失败也可能留下字节"的路径。
        val limit = imagePart + 100
        val sink = ExhaustingSink(limit)
        val result = MotionPhotoComposer.compose(ByteArraySource(image), ByteArraySource(video), sink)
        // 真的是 sink 抛的 IOException 被转成了 Failure（而不是原样向上传播）。
        assertTrue(sink.failed, "sink 从未被要求写入超出上限的字节，这条测试是空的")
        assertEquals(FailureReason.IO_ERROR, (result as ComposeResult.Failure).reason)
        // committed 行为：已落盘的字节不会因为失败而回滚或被清零——它们实打实留在 sink 里，
        // 由调用方按 spec §7.5 无条件删掉 .part。谁若把"失败 = 什么都没写"当成前提（无论是
        // 重新加回一个恒为 0 的 bytesWritten 字段，还是在清理时跳过删除），这条断言就红。
        assertEquals(limit.toLong(), sink.size().toLong())
        assertTrue(sink.size() > imagePart, "半截视频字节应当已经进了 sink")
        // 留下的不是垃圾，而是合法产物的前缀：忘记删 .part 的调用方拿到的是一个截断文件。
        val (_, full) = compose(image, video)
        assertArrayEquals(
            full.copyOfRange(0, imagePart),
            sink.toByteArray().copyOfRange(0, imagePart),
        )
        // 写入阶段失败同样不许替调用方关 sink。
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

    /**
     * 收满 [limit] 字节就把后续写入变成 [IOException] 的 sink——模拟设备写到一半就坏掉。
     * [write] 允许只消费部分缓冲（`writeAll` 正是为此而循环），所以它能先吞掉剩余额度再抛，
     * 于是失败发生时 sink 里留下的是**真实写出去过**的字节，不是内存里的镜像。
     */
    private class ExhaustingSink(private val limit: Int) : java.nio.channels.WritableByteChannel {
        private val bytes = ByteArrayOutputStream()
        private var open = true
        var failed = false
            private set
        override fun write(src: ByteBuffer): Int {
            if (bytes.size() >= limit) {
                failed = true
                throw IOException("sink 在第 $limit 字节后拒绝写入")
            }
            val n = minOf(src.remaining(), limit - bytes.size())
            val chunk = ByteArray(n)
            src.get(chunk)
            bytes.write(chunk)
            return n
        }
        override fun close() { open = false }
        override fun isOpen() = open
        fun size(): Int = bytes.size()
        fun toByteArray(): ByteArray = bytes.toByteArray()
    }
}

private fun ByteArray.indexOfSlice(slice: ByteArray): Int {
    outer@ for (i in 0..(size - slice.size)) {
        for (j in slice.indices) if (this[i + j] != slice[j]) continue@outer
        return i
    }
    return -1
}
