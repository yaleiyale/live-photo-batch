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

    /**
     * 产物长度的唯一算式（spec 增补 2026-10-18c §1）：**源文件整份**扣掉被丢弃的旧 XMP、加上新 APP1，
     * 再加上视频字节。`imageEnd` 之后的尾巴包含在"整份"里，所以它不单独出现在算式上。
     */
    private fun expectedSize(fileSize: Int, droppedOldXmp: Int, app1Size: Int, videoSize: Int): Long =
        fileSize.toLong() - droppedOldXmp + app1Size + videoSize

    /**
     * §4 的判据：`文件大小 - MicroVideoOffset == 视频起点`，且该处起前 12 字节内出现 `ftyp`。
     * 偏移量从**产物自己的字节**里读，不硬编码。尾巴在不在位由调用方另有条款钉。
     */
    private fun assertVideoAtFileSizeMinusOffset(out: ByteArray): Long {
        val text = out.toString(Charsets.ISO_8859_1)
        val offset = Regex("""GCamera:MicroVideoOffset="(\d+)"""").find(text)!!.groupValues[1].toLong()
        val start = out.size - offset
        val window = out.copyOfRange(start.toInt(), minOf(start.toInt() + 12, out.size))
            .toString(Charsets.ISO_8859_1)
        assertTrue(window.contains("ftyp"), "视频起点 $start 之后 12 字节内没有 ftyp")
        return offset
    }

    /**
     * 产物里那段 XMP 必须与真机认下来的那份（[Fixtures.specPacket]）**逐字节**相同。
     * 起点 = APP1 里的 XAP 标识符 + 结尾 NUL 之后；标识符在产物里只出现一次，由别的条款钉。
     * 这条只用于**无尾巴**输入：有尾巴时目录必须带 GainMap/Padding 声明，见 [assertPacket]。
     */
    private fun assertPacketIsTheAcceptedOne(out: ByteArray, videoSize: Long) {
        assertPacket(out, Fixtures.specPacket(videoSize))
    }

    /** 产物里的包必须与 [expected] 逐字节相同——位置同样从 XAP 标识符倒推，不硬编码偏移。 */
    private fun assertPacket(out: ByteArray, expected: ByteArray) {
        val at = out.indexOfSlice(XmpPacket.XAP_IDENTIFIER.toByteArray(Charsets.ISO_8859_1)) +
            XmpPacket.XAP_IDENTIFIER.length + 1
        assertArrayEquals(expected, out.copyOfRange(at, at + expected.size))
    }

    /** [VivoLivePhotoTail] 给 livephoto 值加的字面量前缀的字节数。 */
    private val PREFIX_LEN = "motionphoto".length

    /** ISO-BMFF 盒头那个 32 位大端总长。 */
    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

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
        // 偷懒切分照样会被下面重扫产物那一步抓住。
        val image = Fixtures.jpeg()
        val (result, out) = compose(image, video)
        assertInstanceOf(ComposeResult.Success::class.java, result)
        val offset = assertVideoAtFileSizeMinusOffset(out)
        assertEquals(video.size.toLong(), offset, "MicroVideoOffset 不是追加的视频字节数")
        assertArrayEquals(video, out.copyOfRange(out.size - video.size, out.size))
        // 字节账本：这张图没有旧 XMP 可丢、也没有尾巴，所以视频正好从 31 + app1.size 开始。
        assertEquals((image.size + XmpPacket.app1Segment(video.size.toLong()).size).toLong(), out.size - offset)
        // 重扫产物：EOI 就在视频起点之前，中间零字节（这条只对**没有尾巴**的输入成立）。
        assertEquals(out.size - offset, scan(out).imageEnd)
    }

    // ---------- spec「增补（2026-10-18c）」§1：源 JPEG 整文件进产物 ----------

    @Test fun `gain map trailer is kept byte for byte and the video follows it`() {
        val image = Fixtures.gainMapJpeg()
        val video = Fixtures.mp4(300)
        val structure = scan(image)
        val trailer = image.copyOfRange(structure.imageEnd.toInt(), image.size)
        assertTrue(trailer.size > 4000, "夹具的副图应当是一段真实 JPEG，实测 ${trailer.size}")

        val (result, out) = compose(image, video)
        val success = assertInstanceOf(ComposeResult.Success::class.java, result)
        val oldXmp = structure.oldXmp ?: fail("gain map 夹具必须带一段源 XMP")
        // 有副图 → 目录里多了 GainMap 项与 hdrgm 声明，新段比 18c 那份长：字节账本要用这一份。
        val app1 = XmpPacket.app1Segment(video.size.toLong(), gainMapBytes = trailer.size.toLong())

        // 产物 = 源文件整份（旧 XMP 原位换成新段）+ 视频：一段不少、一字节不改。
        val imagePart = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            image.copyOfRange(2, oldXmp.start.toInt()) + app1 +
            image.copyOfRange(oldXmp.endExclusive.toInt(), image.size)
        assertArrayEquals(imagePart, out.copyOfRange(0, imagePart.size))
        assertArrayEquals(video, out.copyOfRange(imagePart.size, out.size))
        assertEquals(expectedSize(image.size, oldXmp.length.toInt(), app1.size, video.size), out.size.toLong())
        assertEquals(out.size.toLong(), success.totalBytes, "totalBytes 不是产物文件的真实字节数")

        // 尾巴**逐字节**躺在它自己的位置上，且尾巴之后紧跟视频字节：截掉尾巴的产物真机不认。
        val trailerAt = (structure.imageEnd - oldXmp.length + app1.size).toInt()
        assertArrayEquals(trailer, out.copyOfRange(trailerAt, trailerAt + trailer.size))
        assertArrayEquals(video, out.copyOfRange(trailerAt + trailer.size, out.size))
        // 副图字节在场，所以它自己的 APP2 标识符也在场（上一版这里断言的是"必须消失"）。
        assertTrue(out.toString(Charsets.ISO_8859_1).contains("21496"), "gain map 副图没被搬进产物")

        // 包必须把整条尾巴（副图 + 厂商记录）声明成 GainMap：逐字节对照带声明的那一份。
        assertPacket(out, XmpPacket.motionPhoto(video.size.toLong(), gainMapBytes = trailer.size.toLong()))
        assertEquals(video.size.toLong(), assertVideoAtFileSizeMinusOffset(out))
    }

    @Test fun `source xmp declarations never reach the product`() {
        // 清空重写与合并的分水岭：源包里的 dc、厂商 GCamera 与 hdrgm 增益属性一个都不许出现在产物里；
        // 产物自己那份干净的 hdrgm:Version 由下面的"恰好一条"断言放进来。
        val source = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="vivo XMP Toolkit 1.0">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
                hdrgm:Version="1.0" GCamera:BoostCorrection="1.3600">
               <dc:description>sunset by the sea</dc:description>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>""".trimIndent()
        val image = Fixtures.jpeg(
            headSegments = listOf(Fixtures.app1(Fixtures.XAP, source.toByteArray(Charsets.UTF_8)), Fixtures.sof0()),
            tail = Fixtures.gainMapImage(entropySize = 128),
        )
        val video = Fixtures.mp4(64)
        val (result, out) = compose(image, video)
        assertInstanceOf(ComposeResult.Success::class.java, result)
        val text = out.toString(Charsets.ISO_8859_1)
        listOf("dc:description", "sunset by the sea", "BoostCorrection", "vivo XMP Toolkit",
            "hdrgm:GainMapHeadroom").forEach {
            assertFalse(text.contains(it), "源包的 $it 被掺进了产物")
        }
        // hdrgm 前缀本身**允许**出现——但只能是我们清空白写的那一条 hdrgm:Version="1.0"
        // （spec 2026-10-18d §2：有副图时目录必须带它）。出现两条就是源包被掺进来了。
        assertEquals(1, Regex(Regex.escape("hdrgm:Version=\"1.0\"")).findAll(text).count(),
            "产物里的 hdrgm:Version 必须恰好一条，且是我们写的那份")
        assertTrue(text.contains("GCamera:MotionPhoto=\"1\""), "产物没写成动态照片")
        // 尾巴是 gain map 副图 → 目录声明整条尾巴（这里副图之后没有厂商记录，尾巴 == 副图）。
        assertPacket(out, XmpPacket.motionPhoto(video.size.toLong(), gainMapBytes = Fixtures.gainMapImage(entropySize = 128).size.toLong()))
    }

    @Test fun `the product of the previous run is refused as already a motion photo`() {
        // 上一轮产物：尾巴先是副图、视频躺在更后面，16 字节探针认不出来；
        // 是它自己的 XMP 声明（GCamera:MicroVideoOffset）把这一次拦下来的。
        val (first, out) = compose(Fixtures.gainMapJpeg(), Fixtures.mp4(300))
        assertInstanceOf(ComposeResult.Success::class.java, first)
        val (second, out2) = compose(out, Fixtures.mp4(300))
        assertEquals(FailureReason.ALREADY_MOTION_PHOTO, (second as ComposeResult.Failure).reason)
        assertEquals(0, out2.size)
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
        // 尾巴原样在位（副图的 APP2 标识符就是它的在场证明），视频排在它之后。
        assertVideoAtFileSizeMinusOffset(out)
        assertTrue(out.toString(Charsets.ISO_8859_1).contains("21496"), "gain map 尾巴没被搬进产物")
    }

    @Test fun `vendor json trailer is copied`() {
        val image = Fixtures.vendorTrailerJpeg()
        val video = Fixtures.mp4(512)
        val structure = scan(image)
        val trailer = image.copyOfRange(structure.imageEnd.toInt(), image.size)
        assertEquals(399, trailer.size)
        assertEquals("vivo", trailer.copyOfRange(0, 4).toString(Charsets.ISO_8859_1))

        val (result, out) = compose(image, video)
        assertInstanceOf(ComposeResult.Success::class.java, result)
        // 厂商 JSON 不是 gain map → 整条尾巴声明成主图 Padding（spec 2026-10-18d §2 第二行）。
        val app1 = XmpPacket.app1Segment(video.size.toLong(), paddingBytes = trailer.size.toLong())
        assertEquals(expectedSize(image.size, 0, app1.size, video.size), out.size.toLong())
        // 厂商记录原样躺在 EOI 与视频之间：这张图没有源 XMP，新段插在 SOI 之后，
        // 所以尾巴整体后移了 app1.size 字节。
        val trailerAt = structure.imageEnd.toInt() + app1.size
        assertArrayEquals(trailer, out.copyOfRange(trailerAt, trailerAt + trailer.size))
        assertEquals(
            1,
            Regex("cameralbum!").findAll(out.toString(Charsets.ISO_8859_1)).count(),
            "厂商记录在产物里不止一次",
        )
        assertArrayEquals(video, out.copyOfRange(out.size - video.size, out.size))
        // 没有源 XMP 的图也照样带目录声明：产物里的 APP1 与带 Padding 的那份一字不差
        assertArrayEquals(app1, out.copyOfRange(2, 2 + app1.size))
    }

    @Test fun `zero filled trailer is copied`() {
        val image = Fixtures.zeroFilledJpeg()
        val video = Fixtures.mp4(256)
        val structure = scan(image)
        val (result, out) = compose(image, video)
        assertInstanceOf(ComposeResult.Success::class.java, result)
        val trailer = image.copyOfRange(structure.imageEnd.toInt(), image.size)
        assertEquals(47, trailer.size)
        val app1 = XmpPacket.app1Segment(video.size.toLong(), paddingBytes = trailer.size.toLong())
        assertEquals(
            expectedSize(image.size, 0, app1.size, video.size),
            out.size.toLong(),
        )
        // 47 个零字节确实被搬进来了：视频起点比 imageEnd 多挪了 47 字节，
        // 少搬（截到 EOI）或搬错位置都会让这条等式先红。
        assertEquals(
            structure.imageEnd + 47 + app1.size,
            out.size - assertVideoAtFileSizeMinusOffset(out),
        )
    }

    @Test fun `gain map trailer with no xmp at all is copied`() {
        val inner = Fixtures.gainMapImage(entropySize = 128)
        val image = Fixtures.jpeg(tail = inner)
        val video = Fixtures.mp4(128)
        val (result, out) = compose(image, video)
        assertInstanceOf(ComposeResult.Success::class.java, result)
        assertTrue(out.toString(Charsets.ISO_8859_1).contains("21496"), "副图字节没被搬进产物")
        // 源文件整份 + 视频：一个字节都不许多，也不许少。尾巴是 gain map → 目录带 GainMap 项。
        val app1 = XmpPacket.app1Segment(video.size.toLong(), gainMapBytes = inner.size.toLong())
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app1 + image.copyOfRange(2, image.size) + video,
            out,
        )
        assertEquals(video.size.toLong(), assertVideoAtFileSizeMinusOffset(out))
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

    @Test fun `a source packet nobody can parse is simply overwritten`() {
        // 2026-10-18c §2/§3：写路径不再解析源 XMP，所以畸形包既不是失败理由，也不需要那套
        // 安全解析（DTD、外部实体一律没人去读）。它的下场和别的源声明一样——被清空白掉。
        val broken = Fixtures.app1(Fixtures.XAP, "<x:xmpmeta><unclosed>".toByteArray(Charsets.UTF_8))
        val image = Fixtures.jpeg(headSegments = listOf(broken, Fixtures.sof0()))
        val video = Fixtures.mp4(64)
        val (result, out) = compose(image, video)
        assertInstanceOf(ComposeResult.Success::class.java, result)
        val text = out.toString(Charsets.ISO_8859_1)
        assertFalse(text.contains("<unclosed"), "旧 XMP 的字节还留在产物里")
        assertPacketIsTheAcceptedOne(out, video.size.toLong())

        val dtdText = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" +
            "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF" +
            " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description>&e;" +
            "</rdf:Description></rdf:RDF></x:xmpmeta>"
        val doctype = Fixtures.app1(Fixtures.XAP, dtdText.toByteArray(Charsets.UTF_8))
        val bad2 = Fixtures.jpeg(headSegments = listOf(doctype, Fixtures.sof0()))
        val (result2, out2) = compose(bad2, video)
        assertInstanceOf(ComposeResult.Success::class.java, result2)
        assertFalse(out2.toString(Charsets.ISO_8859_1).contains("DOCTYPE"), "源包文本被原样留进了产物")
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

    // ---------- 真机二分（2026-10-20）：vivo 末盒的 livephoto 值必须带 motionphoto 前缀 ----------

    @Test fun `vivo tail box gets the prefix and both declared sizes follow the rewrite`() {
        val image = Fixtures.jpeg()
        val video = Fixtures.vivoMp4()
        val oldBox = Fixtures.vivoTailBox()
        val head = video.size - oldBox.size
        val (result, out) = compose(image, video)
        val success = assertInstanceOf(ComposeResult.Success::class.java, result)

        // 前缀那 11 字节全落在末盒里：视频前半一字不动，产物只比"原样追加"多这 11 字节。
        assertEquals((video.size + PREFIX_LEN).toLong(), success.videoSize, "videoSize 不是实际写出的视频字节数")
        assertEquals(
            (image.size + XmpPacket.app1Segment(success.videoSize).size + video.size + PREFIX_LEN).toLong(),
            out.size.toLong(), "产物长度账本没跟着末盒改写",
        )
        val offset = assertVideoAtFileSizeMinusOffset(out)
        assertEquals(success.videoSize, offset, "MicroVideoOffset 填的不是写出去的字节数")
        val text = out.toString(Charsets.ISO_8859_1)
        assertEquals(1, Regex(Regex.escape("Item:Length=\"${success.videoSize}\"")).findAll(text).count(),
            "容器项的 Length 没跟着末盒改写（两处长度必须是同一个数）")
        assertTrue("motionphoto${Fixtures.REAL_LIVEPHOTO}" in text, "末盒的 livephoto 值没被加前缀")
        // 视频前半（ftyp 盒）逐字节不动：chunk 偏移的基准没被挪过。
        assertArrayEquals(video.copyOfRange(0, head), out.copyOfRange(out.size - success.videoSize.toInt(), out.size - success.videoSize.toInt() + head))
        val newBox = out.copyOfRange(out.size - success.videoSize.toInt() + head, out.size)
        // 盒子的前 4 字节**就是** size 字段，加前缀必然动它（下面一条专门盯新长度）；
        // 不许动的是从 tag 起的 24 字节：'uuid' + 16 字节伪 UUID + 'vivo' 魔数。
        assertArrayEquals(oldBox.copyOfRange(4, 28), newBox.copyOfRange(4, 28), "tag/伪 UUID/魔数被动过")
        assertEquals(newBox.size, be32(newBox, 0), "末盒 size 字段没同步成新长度")
    }

    @Test fun `video without the vivo tail box is appended byte for byte`() {
        // 回归钉桩：非 vivo 的视频（没有那个盒）产物必须与"原样追加"逐字节相同——不伪造厂商结构。
        val video = Fixtures.mp4(300)
        val (_, out) = compose(Fixtures.jpeg(), video)
        assertArrayEquals(video, out.copyOfRange(out.size - video.size, out.size), "没有末盒时视频必须原样追加")
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
