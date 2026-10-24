package dev.zayn.vlive.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.channels.Channels

class XmpPacketTest {

    /**
     * 判据是**字节**，不是"实现现在吐了什么"：产物里那份包必须与
     * [Fixtures.specPacket] 一字不差，实现吐出的字节一变这里就得红。
     * 尾巴既非副图也无需 Padding 时（两个参数都不给），这份包**一个字节都不许多**——形状不许漂移。
     */
    @Test fun `the packet is byte for byte the one the phone accepted`() {
        val accepted = Fixtures.specPacket(3_863_927L)
        assertEquals(1_057, accepted.size, "夹具自己就不是那份 1,057 字节的包：抄漏了字符")
        assertArrayEquals(accepted, XmpPacket.motionPhoto(3_863_927L))
    }

    @Test fun `both video size slots carry the argument and the packet stays far below the segment limit`() {
        val size = 3_863_927L
        val packet = XmpPacket.motionPhoto(size)
        val text = packet.decodeToString()
        assertTrue(text.contains("GCamera:MicroVideoOffset=\"$size\""), "偏移量那一格没填视频长度")
        assertTrue(text.contains("Item:Length=\"$size\""), "容器项的 Item:Length 没填视频长度")
        assertEquals(2, Regex("\"$size\"").findAll(text).count(), "视频长度出现的地方不止这两处")
        assertTrue(packet.size < 64 * 1024, "整包 ${packet.size} 字节，逼近 APP1 的 16 位长度字段")
    }

    @Test fun `the packet has one rdf RDF block, seven attributes and two items in order`() {
        val text = XmpPacket.motionPhoto(1L).decodeToString()
        assertEquals(1, Regex("<rdf:RDF").findAll(text).count(), "又起了第二个 rdf:RDF")
        assertEquals(1, Regex("<rdf:Description").findAll(text).count(), "又起了第二个 rdf:Description")
        assertEquals(7, Regex("GCamera:(MicroVideo|MicroVideoVersion|MicroVideoOffset|" +
            "MicroVideoPresentationTimestampUs|MotionPhoto|MotionPhotoVersion|" +
            "MotionPhotoPresentationTimestampUs)=").findAll(text).count(), "动照片属性不止七个")
        assertEquals(listOf("Primary", "MotionPhoto"), Regex("""Item:Semantic="(\w+)"""").findAll(text)
            .map { it.groupValues[1] }.toList(), "容器项的顺序或写法变了")
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

    // ---------- 目录必须声明每一段字节 ----------

    @Test fun `a gain map trailer gets a GainMap item ordered before the video`() {
        val text = XmpPacket.motionPhoto(5_032_601L, gainMapBytes = 371_928L).decodeToString()
        assertTrue(
            text.contains(
                """<Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="371928"/>"""
            ),
            "GainMap 项缺失或属性拼写/顺序变了（Length 必须是**整条尾巴**，含副图之后的厂商记录）",
        )
        assertEquals(
            listOf("Primary", "GainMap", "MotionPhoto"),
            Regex("""Item:Semantic="(\w+)"""").findAll(text).map { it.groupValues[1] }.toList(),
            "项顺序固定 Primary → GainMap → MotionPhoto，视频项必须最后",
        )
        assertEquals(1, Regex("hdrgm:Version=\"1.0\"").findAll(text).count(), "主图 XMP 必须恰好一条 hdrgm:Version=\"1.0\"")
        assertTrue(
            text.contains("xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\""),
            "hdrgm 前缀没有声明命名空间，URI 对不上就等于没写",
        )
        assertEquals(1, Regex("<rdf:Description").findAll(text).count(), "hdrgm 属性必须住在宿主 Description 上")
    }

    @Test fun `a padding trailer declares Padding on the Primary item only`() {
        val text = XmpPacket.motionPhoto(5_032_601L, paddingBytes = 387L).decodeToString()
        val items = Regex("<Container:Item[^>]*>").findAll(text).map { it.value }.toList()
        assertEquals(2, items.size, "非副图尾巴只许两项：Primary 与 MotionPhoto")
        assertEquals(1, items.count { it.contains("Item:Padding=\"387\"") }, "Padding 必须恰好出现一次")
        assertTrue(items.first().contains("Item:Semantic=\"Primary\"") && items.first().contains("Item:Padding=\"387\""),
            "Padding 写在 Primary 之外去了——规范只允许第一项有 Padding")
        assertTrue(!text.contains("GainMap"), "尾巴不是 gain map，就不许声明 GainMap 项")
        assertTrue(!text.contains("hdrgm"), "没有副图就没有 hdrgm，Version 也不许出现")
    }

    /**
     * 主图项**带** `Length`，但只能是 `0`（规范：主图项的 Length 若写就应为 0）。
     * `Padding` 只许出现在第一项——OPPO 在每一项上都写 `Padding="0"`，那是它自己违反
     * "Padding 只允许第一项"，不抄。
     */
    @Test fun `Primary carries Length 0 and is the only item allowed to carry Padding`() {
        for (bytes in listOf(
            XmpPacket.motionPhoto(9_999L, gainMapBytes = 371_928L),
            XmpPacket.motionPhoto(9_999L, paddingBytes = 387L),
            XmpPacket.motionPhoto(9_999L),
        )) {
            val text = bytes.decodeToString()
            val items = Regex("<Container:Item[^>]*>").findAll(text).map { it.value }.toList()
            val primary = items.single { it.contains("Item:Semantic=\"Primary\"") }
            assertTrue("Item:Length=\"0\"" in primary, "主图项的 Length 必须恰好写成 0：$primary")
            for (item in items.filter { "Item:Semantic=\"Primary\"" !in it }) {
                assertTrue("Item:Padding" !in item, "Padding 漏到了非首项上（规范只允许第一项有）：$item")
            }
            for (item in items.filter { "Item:Length" in it && "Item:Length=\"0\"" !in it }) {
                assertTrue(
                    item.contains("Item:Semantic=\"GainMap\"") || item.contains("Item:Semantic=\"MotionPhoto\""),
                    "非零 Length 落在了 GainMap/MotionPhoto 之外的项上：$item",
                )
            }
        }
    }

    /**
     * 序列化形状**不是规范性变量**（Motion Photo 1.0 整页只有属性表与散文，没有示例 XMP 包），
     * 所以这里钉的是"我们自己不许漂"，不是"规范如此"：`<rdf:li>` 不带 `parseType`、
     * `Semantic` 排在 `Mime` 前。改它没有任何合规收益，只会让字节钉桩重走一遍装机验收。
     */
    @Test fun `serialization shape stays ours, not borrowed from a vendor sample`() {
        val text = XmpPacket.motionPhoto(5_032_601L, gainMapBytes = 371_928L).decodeToString()
        val items = Regex("<Container:Item[^>]*>").findAll(text).map { it.value }.toList()
        assertEquals(3, items.size)
        assertTrue(!text.contains("rdf:parseType"), "带上了 parseType：规范没要求，别从厂商样本抄形状")
        for (body in items) {
            assertTrue(body.indexOf("Item:Semantic") < body.indexOf("Item:Mime"), "Semantic 不再排在 Mime 前：$body")
            if ("Item:Length" in body) {
                assertTrue(body.indexOf("Item:Semantic") < body.indexOf("Item:Length"), "Length 排到了 Semantic 前：$body")
            }
        }
    }

    @Test fun `a trailer cannot be GainMap and Padding at the same time`() {
        // 上游定性出错才会两个都给；静默挑一个会产出一个骗人的目录。
        // 类型必须是 IllegalStateException 而不是 IllegalArgumentException：合成器把后者折成
        // EXTENDED_XMP_UNSUPPORTED（真·输入上限），不变量被违反若也走那条，报告就指向错误的方向。
        val thrown: Throwable = assertThrows(IllegalStateException::class.java) {
            XmpPacket.motionPhoto(1L, gainMapBytes = 2L, paddingBytes = 3L)
        }
        assertFalse(
            thrown is IllegalArgumentException,
            "两种失败必须分属两种类型：合成器只折 IllegalArgumentException，混在一起会把上游 bug 报成输入不支持",
        )
    }

    @Test fun `the app1 segment carries the trailer declaration too`() {
        val withGainMap = XmpPacket.app1Segment(5_032_601L, gainMapBytes = 371_928L)
        val packet = withGainMap.copyOfRange(4 + XmpPacket.XAP_IDENTIFIER.length + 1, withGainMap.size)
        assertArrayEquals(XmpPacket.motionPhoto(5_032_601L, gainMapBytes = 371_928L), packet)
    }

    /**
     * "紧密封装"的可执行形式：主图编码长度 + Padding + GainMap.Length +
     * MotionPhoto.Length == 产物文件大小。数字**全部从产物里读回来**（目录属性 + 重扫 EOI +
     * 落盘字节数），任何一项 Length 写小、写大或漏写都会让等式先红，不是拿常量硬凑。
     */
    @Test fun `every byte between the primary image and the video is declared by the directory`() {
        val video = Fixtures.mp4(300)
        for (image in listOf(
            Fixtures.jpeg(),                    // 无尾巴：等式退化为主图 + 视频
            Fixtures.gainMapJpeg(),             // gain map + 387 字节厂商记录 → GainMap 声明整条
            Fixtures.vendorTrailerJpeg(),       // 厂商 JSON → Primary 的 Padding
            Fixtures.zeroFilledJpeg(),          // 零填充 → Primary 的 Padding
        )) {
            val out = composeOut(image, video)
            assertEveryByteDeclared(out)
        }
    }

    private fun composeOut(image: ByteArray, video: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val result = MotionPhotoComposer.compose(
            ByteArraySource(image), ByteArraySource(video), Channels.newChannel(out)
        )
        assertTrue(result is ComposeResult.Success, "合成失败，等式无从谈起：$result")
        return out.toByteArray()
    }

    private fun scan(image: ByteArray): JpegStructure =
        (JpegScanner.scan(Channels.newChannel(java.io.ByteArrayInputStream(image)))
            as JpegScanResult.Parsed).structure

    private fun assertEveryByteDeclared(out: ByteArray) {
        val text = out.toString(Charsets.ISO_8859_1)
        val padding = Regex("""Item:Padding="(\d+)"""").find(text)?.groupValues?.get(1)?.toLong() ?: 0L
        val gainMap = Regex("""Item:Semantic="GainMap"[^>]*Item:Length="(\d+)"""")
            .find(text)?.groupValues?.get(1)?.toLong() ?: 0L
        val videoMatch = Regex("""Item:Semantic="MotionPhoto"[^>]*Item:Length="(\d+)"""").find(text)
        assertNotNull(videoMatch, "目录里没有带 Length 的 MotionPhoto 项：等式的视频段没人声明")
        val video = videoMatch!!.groupValues[1].toLong()
        val primary = scan(out).imageEnd
        assertEquals(out.size.toLong(), primary + padding + gainMap + video, "等式：主图+Padding+GainMap+视频 == 文件大小")
    }

    private fun ByteArray.startsWith(other: ByteArray, at: Int): Boolean {
        if (size - at < other.size) return false
        for (i in other.indices) if (this[at + i] != other[i]) return false
        return true
    }
}
