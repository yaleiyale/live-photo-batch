package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * spec「增补 §1」的尾巴分类。分类器只认**交给它的 head**（从 imageEnd 起最多 16 字节）
 * 与源 XMP 文本，所以这里所有用例都能用一组字节 + 一段文本完全表达。
 */
class TrailerClassifierTest {

    private fun kind(head: ByteArray, xmp: String? = null) = TrailerClassifier.classify(head, xmp)

    // ---------- VIDEO：合法的 ISO-BMFF 尾巴 ----------

    @Test fun `ftyp box with a self consistent length is video`() {
        val head = Fixtures.ftypBox(24).copyOf(16)
        assertEquals(TrailerKind.VIDEO, kind(head))
    }

    @Test fun `moov and mdat boxes are video too`() {
        for (tag in listOf("moov", "mdat")) {
            assertEquals(TrailerKind.VIDEO, kind(Fixtures.box(tag, 16)), "tag=$tag")
        }
    }

    @Test fun `padding box followed by a real box is video`() {
        // free 32 字节盒子打头，尾巴只有这 16 字节可见：必须能一路走到第二个盒子的 ftyp
        val head = (Fixtures.box("free", 8) + Fixtures.ftypBox(24)).copyOf(16)
        assertEquals(TrailerKind.VIDEO, kind(head))
        val skipFirst = (Fixtures.box("skip", 8) + Fixtures.box("moov", 8)).copyOf(16)
        assertEquals(TrailerKind.VIDEO, kind(skipFirst))
    }

    @Test fun `box longer than the visible window is still video`() {
        // 真机追加的视频动辄几 MB：ftyp 盒本身就 24-32 字节，声明的总长远超 16 字节的窗口。
        // 窗口装满时只能验下界，判据落在 tag 与 ftyp 兜底规则上。
        val head = Fixtures.mp4(8_000_000L).copyOf(16)
        assertEquals(TrailerKind.VIDEO, kind(head))
        val mdat = Fixtures.box("mdat", 1_000_000).copyOf(16)
        assertEquals(TrailerKind.VIDEO, kind(mdat))
    }

    @Test fun `ftyp anywhere inside the first twelve bytes is video`() {
        // 长度字段自洽（N=0 不自洽！）但 ftyp 就在第 4-8 字节：兜底规则要认。
        val head = ByteArray(16).also { Fixtures.ascii("ftyp").copyInto(it, 4) }
        assertEquals(TrailerKind.VIDEO, kind(head))
    }

    // ---------- 64 位扩展长度（ISO-BMFF：size 字段写 1，真实长度是 tag 之后的 8 字节） ----------

    /** 只给 16 字节盒头：size=1 + tag + 8 字节 largesize。分类器本来也只看得到这么多。 */
    private fun header64(tag: String, size: Long): ByteArray =
        byteArrayOf(0, 0, 0, 1) + Fixtures.ascii(tag) + ub64(size)

    private fun ub64(value: Long): ByteArray = ByteArray(8) { i -> (value ushr (56 - 8 * i)).toByte() }

    @Test fun `mdat with a 64 bit extended size is video`() {
        // 真机语料里 mdat 就是这个形态：vivo 那条 5,976,771 字节的 mdat 前 4 字节读出来是 1。
        // 按 32 位长度走盒子的实现会把它判成"不是盒子"，于是往一段真视频后面再拼一段视频。
        assertEquals(TrailerKind.VIDEO, kind(header64("mdat", 5_976_771L)))
        // 超过 32 位无符号上界的长度也必须读全：截成 int 会让 4 GB 的 mdat 变成负数。
        assertEquals(TrailerKind.VIDEO, kind(header64("moov", 4_000_000_000L)))
    }

    @Test fun `a 64 bit padding box that covers the window is other`() {
        // free 用扩展长度盖住了整个窗口：后面的盒子看不见，只能按"不是视频"处理——
        // 与 32 位长度那条同样的诚实下界，不猜。
        assertEquals(TrailerKind.OTHER, kind(header64("free", 4_000_000L)))
    }

    @Test fun `a truncated largesize is other`() {
        // 12 字节的尾巴连 16 字节的扩展盒头都装不下：这个盒子自己就不成立。
        assertEquals(TrailerKind.OTHER, kind(header64("mdat", 5_976_771L).copyOf(12)))
    }

    // ---------- OTHER：尾巴不是视频，原样保留 ----------

    @Test fun `gain map second jpeg is other`() {
        // 真 HDR gain map 的开头：SOI + APP2/urn:iso:std... —— 前 4 字节按大端是 4292260861，
        // 比尾巴长得多，长度检查先把它挡在 VIDEO 之外。
        val head = Fixtures.gainMapImage().copyOf(16)
        assertEquals(0xFF.toByte(), head[0])
        assertEquals(0xD8.toByte(), head[1])
        assertEquals(TrailerKind.OTHER, kind(head))
    }

    @Test fun `vendor json trailer is other`() {
        val head = Fixtures.vendorTrailer().copyOf(16)
        assertEquals("vivo", head.copyOfRange(0, 4).decodeToString())
        assertEquals(TrailerKind.OTHER, kind(head))
    }

    @Test fun `zero filled trailer is other`() {
        assertEquals(TrailerKind.OTHER, kind(Fixtures.zeroFill(16)))
        assertEquals(TrailerKind.OTHER, kind(Fixtures.zeroFill(47).copyOf(16)))
        // 只有 4 个零字节时长度字段是 0，仍然不是盒子
        assertEquals(TrailerKind.OTHER, kind(Fixtures.zeroFill(4)))
    }

    @Test fun `trailer shorter than a box header is other`() {
        // 8 字节以下的尾巴连一个 box 头都装不下；长度自洽检查也无从谈起。
        assertEquals(TrailerKind.OTHER, kind(byteArrayOf(0x00, 0x00)))
        assertEquals(TrailerKind.OTHER, kind(byteArrayOf(0, 0, 0, 8, 'x'.code.toByte(), 'x'.code.toByte(), 'x'.code.toByte(), 'x'.code.toByte()).copyOf(6)))
    }

    @Test fun `declared length beyond the fully visible trailer is other`() {
        // head 没装满 16 字节 = 整条尾巴都在手里，此时 N 必须 <= 尾巴长度才算自洽。
        // 这条专门盯"声明 4096 字节、实际只有 8 字节"的假盒子。
        val head = Fixtures.box("moov", 4096).copyOf(8)
        assertEquals(TrailerKind.OTHER, kind(head))
    }

    // ---------- NONE：文件正好停在 EOI ----------

    @Test fun `empty head means no trailer`() {
        assertEquals(TrailerKind.NONE, kind(ByteArray(0)))
        assertEquals(TrailerKind.NONE, kind(ByteArray(0), null))
    }

    // ---------- 源 XMP 的信号不看尾巴就能定 VIDEO ----------

    @Test fun `micro video offset in xmp with no trailer is video`() {
        val xmp = """<rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MicroVideoOffset="123"/>"""
        assertEquals(TrailerKind.VIDEO, kind(ByteArray(0), xmp))
    }

    @Test fun `motion photo semantic item in xmp is video even when the trailer is a jpeg`() {
        val xmp = "<Item:Semantic>MotionPhoto</Item:Semantic>"
        assertEquals(TrailerKind.VIDEO, kind(Fixtures.gainMapImage().copyOf(16), xmp))
    }

    @Test fun `video mime item inside the container is video`() {
        val xmp = Fixtures.gainMapXmp(441963L) +
            "\n     <rdf:li><Item:Mime>video/mp4</Item:Mime><Item:Semantic>Unknown</Item:Semantic></rdf:li>"
        assertEquals(TrailerKind.VIDEO, kind(Fixtures.gainMapImage().copyOf(16), xmp))
    }

    @Test fun `gain map declaration alone is other`() {
        // 这正是让用户全部真实输入被旧规则跳掉的那一张：hdrgm:Version + 只有 GainMap 项，
        // 尾巴是副图 JPEG。它必须放行。
        val xmp = Fixtures.gainMapXmp(441963L)
        assertEquals(TrailerKind.OTHER, kind(Fixtures.gainMapImage().copyOf(16), xmp))
        // 同一段 XMP、根本没有尾巴：还是"不是动态照片"，只是没尾巴可搬
        assertEquals(TrailerKind.NONE, kind(ByteArray(0), xmp))
    }

    @Test fun `plain photo xmp with no trailer is none`() {
        val xmp = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><dc:description xmlns:dc="http://purl.org/dc/elements/1.1/">sunset</dc:description></x:xmpmeta>"""
        assertEquals(TrailerKind.NONE, kind(ByteArray(0), xmp))
    }

    @Test fun `bare words in unrelated namespaces do not fake a motion photo`() {
        // 与 MotionDetector 同一条顾虑：裸词、或别的命名空间的同名元素都不是 Google 的标记。
        val xmp = "<MicroVideoOffset>123</MicroVideoOffset><tiff:MotionPhoto>1</tiff:MotionPhoto>"
        assertEquals(TrailerKind.OTHER, kind(Fixtures.zeroFill(16), xmp))
        assertEquals(TrailerKind.OTHER, kind(Fixtures.zeroFill(16), "<dc:description>video/mp4 in prose</dc:description>"))
    }
}
