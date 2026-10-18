package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * spec「增补 §3」的合并：源包是基底，Motion Photo 是加进去的那一层，
 * 而 gain map 的声明一个都不许掉——字节还在文件里，声明掉了就会被解码器误读。
 */
class XmpMergerTest {

    private fun merge(source: String?, videoSize: Long): String =
        XmpMerger.merge(source, videoSize).decodeToString()

    private fun parse(text: String) = safeFactory().newDocumentBuilder()
        .parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

    /** 与实现同一套安全开关：测试这边也要证明"合并产物"是能被人安全再解析一次的。 */
    private fun safeFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }

    /** §7.3 要求的字段集合，逐条对着模板数。 */
    private fun assertHasFullSpecFieldSet(text: String, videoSize: Long) {
        listOf(
            "GCamera:MotionPhoto=\"1\"",
            "GCamera:MotionPhotoVersion=\"1\"",
            "GCamera:MotionPhotoPresentationTimestampUs=\"-1\"",
            "GCamera:MicroVideo=\"1\"",
            "GCamera:MicroVideoVersion=\"1\"",
            "GCamera:MicroVideoOffset=\"$videoSize\"",
            "GCamera:MicroVideoPresentationTimestampUs=\"-1\"",
            "<Item:Mime>image/jpeg</Item:Mime>",
            "<Item:Semantic>Primary</Item:Semantic>",
            "<Item:Mime>video/mp4</Item:Mime>",
            "<Item:Semantic>MotionPhoto</Item:Semantic>",
            "<Item:Length>$videoSize</Item:Length>",
        ).forEach { assertTrue(text.contains(it), "合并产物缺少：$it") }
    }

    private fun count(text: String, needle: String): Int = Regex(Regex.escape(needle)).findAll(text).count()

    /** 容器项的 `Item:Semantic` 序列：文本级断言会被缩进骗过去，这个直接读 DOM。 */
    private fun semantics(text: String): List<String> {
        val nodes = parse(text).getElementsByTagNameNS(XmpPacket.ITEM_NS, "Semantic")
        return (0 until nodes.length).map { nodes.item(it).textContent.trim() }
    }

    // ---------- 没有源包 ----------

    @Test fun `source without xmp gets the template byte for byte`() {
        for (size in listOf(0L, 1L, 987_654L)) {
            assertArrayEquals(XmpPacket.motionPhoto(size), XmpMerger.merge(null, size))
            // 空文本 = "这一段 APP1 里其实什么都没有"，同样走模板，绝不当成畸形
            assertArrayEquals(XmpPacket.motionPhoto(size), XmpMerger.merge("", size))
            assertArrayEquals(XmpPacket.motionPhoto(size), XmpMerger.merge("   \n\t ", size))
        }
    }

    // ---------- gain map 源包 ----------

    @Test fun `gain map declaration survives the merge`() {
        val text = merge(Fixtures.gainMapXmp(441_963L), 5_000L)
        // 无关命名空间与属性原样保留
        assertTrue(text.contains("hdrgm:Version=\"1.0\""), "gain map 版本号被丢了")
        assertTrue(text.contains("xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\""))
        assertTrue(text.contains("x:xmptk=\"vivo XMP Toolkit 1.0\""), "根元素上的属性也要留着")
        assertTrue(text.contains("rdf:about=\"\""))
        // 两个既有项都在，Length 还是副图自己的字节数（字节没动，值就不许改）
        assertTrue(text.contains("<Item:Semantic>Primary</Item:Semantic>"))
        assertTrue(text.contains("<Item:Semantic>GainMap</Item:Semantic>"))
        assertTrue(text.contains("<Item:Length>441963</Item:Length>"), "gain map 的 Item:Length 被改写了")
        assertHasFullSpecFieldSet(text, 5_000L)
    }

    @Test fun `item order is primary then gain map then motion photo`() {
        val text = merge(Fixtures.gainMapXmp(441_963L), 5_000L)
        val primary = text.indexOf("<Item:Semantic>Primary</Item:Semantic>")
        val gainMap = text.indexOf("<Item:Semantic>GainMap</Item:Semantic>")
        val motion = text.indexOf("<Item:Semantic>MotionPhoto</Item:Semantic>")
        assertTrue(primary in 0..<gainMap && gainMap < motion, "项顺序错了：$primary / $gainMap / $motion")
    }

    @Test fun `merged packet declares exactly one container directory`() {
        val text = merge(Fixtures.gainMapXmp(441_963L), 5_000L)
        assertEquals(1, count(text, "<Container:Directory>"))
        assertEquals(1, count(text, "</Container:Directory>"))
        assertEquals(1, count(text, "<rdf:Seq>"))
        // 解析一次，节点数也对得上：文本级断言会被重复的自闭合写法骗过去
        assertEquals(3, semantics(text).size, "容器里的项数不对")
        assertEquals(listOf("Primary", "GainMap", "MotionPhoto"), semantics(text))
    }

    // ---------- 幂等 ----------

    @Test fun `merging an already merged packet is byte identical`() {
        for (videoSize in listOf(123L, 8_000_000L)) {
            val once = XmpMerger.merge(Fixtures.gainMapXmp(441_963L), videoSize)
            val twice = XmpMerger.merge(String(once, Charsets.UTF_8), videoSize)
            assertArrayEquals(once, twice, "第二次合改写出的字节和第一次不一样")
            // 换个视频长度再合一次：两个尺寸字段跟着走，其余一个字节都不动
            val resized = String(
                XmpMerger.merge(String(once, Charsets.UTF_8), videoSize + 1), Charsets.UTF_8
            )
            assertTrue(resized.contains("GCamera:MicroVideoOffset=\"${videoSize + 1}\""))
            assertTrue(resized.contains("<Item:Length>${videoSize + 1}</Item:Length>"))
            assertFalse(resized.contains("GCamera:MicroVideoOffset=\"$videoSize\""))
        }
    }

    @Test fun `merging our own template keeps every field and replaces the sizes`() {
        // 上一轮的产物当输入：两组 GCamera 属性与 MotionPhoto 项被剔掉再原样加回来。
        // 这里不比对模板字节——模板把 GCamera:* 属性分三行续写，合并产物按 DOM 顺序单行输出，
        // 空白不参与 XMP 语义；要比的是字段集合，以及"再合一次字节不动"（下一条测试）。
        val text = merge(XmpPacket.motionPhoto(777L).decodeToString(), 900L)
        assertHasFullSpecFieldSet(text, 900L)
        assertEquals(1, count(text, "<Item:Semantic>MotionPhoto</Item:Semantic>"))
        assertEquals(1, count(text, "GCamera:MicroVideoOffset="))
        assertEquals(1, count(text, "GCamera:MotionPhoto=\"1\""))
        assertEquals(1, count(text, "<Container:Directory>"))
        assertEquals(1, count(text, "<x:xmpmeta"))
        assertFalse(text.contains("777"), "上一轮的视频长度还留在产物里")
        // 规范写法自己就是不动点
        val bytes = XmpMerger.merge(XmpPacket.motionPhoto(900L).decodeToString(), 900L)
        assertArrayEquals(bytes, XmpMerger.merge(String(bytes, Charsets.UTF_8), 900L))
    }

    @Test fun `stale motion photo markers are replaced not duplicated`() {
        // 上一轮的产物：GainMap 项之后还躺着一条 MotionPhoto 项，宿主描述上带着 GCamera:MicroVideoOffset。
        val stale = Fixtures.gainMapXmp(441_963L)
            .replace(
                """      GCamera:BoostCorrection="1.3600"""",
                """      GCamera:BoostCorrection="1.3600" GCamera:MicroVideoOffset="999999"""",
            )
            .replace(
                "    </rdf:Seq>",
                "     <rdf:li xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" rdf:parseType=\"Resource\">\n" +
                    "      <Item:Mime>video/mp4</Item:Mime>\n" +
                    "      <Item:Semantic>MotionPhoto</Item:Semantic>\n" +
                    "      <Item:Length>999999</Item:Length>\n" +
                    "     </rdf:li>\n    </rdf:Seq>",
            )
        val text = merge(stale, 5_000L)
        assertEquals(1, count(text, "<Item:Semantic>MotionPhoto</Item:Semantic>"))
        assertEquals(1, count(text, "GCamera:MicroVideoOffset="))
        assertEquals(1, count(text, "GCamera:MotionPhoto=\"1\""))
        assertFalse(text.contains("999999"), "上一轮的视频长度还留在容器项或属性里")
        assertHasFullSpecFieldSet(text, 5_000L)
        assertTrue(text.contains("<Item:Semantic>GainMap</Item:Semantic>"), "副图声明被连带删了")
    }

    @Test fun `only the seven motion photo gcamera attributes are stripped`() {
        // 修 round 1 的裁定（spec 增补 §3 改写）：剔除规则是**七个**动态照片专用属性的白名单，
        // 不是"整个 GCamera 命名空间"。抽样里 vivo 的包带着 GCamera:BoostCorrection="1.3600"
        // （HDR 增益记录，不是 Motion Photo 标记），删它会改变 HDR 渲染——与本增补"保住 gain map"
        // 的目的相反，必须原样保留。其余命名空间照旧不受影响。
        val text = merge(Fixtures.gainMapXmp(441_963L), 5_000L)
        assertEquals(1, count(text, "GCamera:BoostCorrection=\"1.3600\""), "HDR 增益记录被剔了")
        assertEquals(1, Regex("""GCamera:MicroVideoOffset="(\d+)"""").findAll(text).count())
        assertTrue(text.contains("hdrgm:GainMapHeadroom"), "同一元素上的非 GCamera 属性被连带删了")
    }

    @Test fun `stale motion photo attributes are stripped by name while every other gcamera attribute survives`() {
        // 同一个源描述里混着"该留的 HDR/厂商 GCamera 属性"与"该换掉的陈旧 Motion Photo 属性"：
        // 白名单按**属性名**精确剔除七个，其余（BoostCorrection、ImageEmbeddings、Burst*）一个都不许动。
        val stale = Fixtures.gainMapXmp(441_963L).replace(
            """      GCamera:BoostCorrection="1.3600"""",
            """      GCamera:BoostCorrection="1.3600"
      GCamera:ImageEmbeddings="checksum-abcdef"
      GCamera:BurstId="burst7"
      GCamera:BurstMasterPhoto="true"
      GCamera:MicroVideo="0"
      GCamera:MicroVideoVersion="0"
      GCamera:MicroVideoOffset="999"
      GCamera:MicroVideoPresentationTimestampUs="424242"
      GCamera:MotionPhoto="0"
      GCamera:MotionPhotoVersion="0"
      GCamera:MotionPhotoPresentationTimestampUs="424242"""",
        )
        val text = merge(stale, 5_000L)
        // 留下的：原值、原属性名，一条不多一条不少
        listOf(
            "GCamera:BoostCorrection=\"1.3600\"",
            "GCamera:ImageEmbeddings=\"checksum-abcdef\"",
            "GCamera:BurstId=\"burst7\"",
            "GCamera:BurstMasterPhoto=\"true\"",
        ).forEach { assertEquals(1, count(text, it), "保留属性丢了或重了：$it") }
        // 剔掉的：陈旧值只准以我们算出来的值出现一次
        assertEquals(1, count(text, "GCamera:MicroVideoOffset=\""), "MicroVideoOffset 没被换成单一计算值")
        assertTrue(text.contains("GCamera:MicroVideoOffset=\"5000\""), "陈旧偏移没被计算值取代")
        assertEquals(0, count(text, "GCamera:MicroVideoOffset=\"999\""))
        assertEquals(0, count(text, "424242"), "陈旧时间戳残留")
        assertEquals(0, count(text, "GCamera:MicroVideo=\"0\""))
        assertEquals(0, count(text, "GCamera:MotionPhoto=\"0\""))
        // 其余声明与我们的字段集照旧
        assertTrue(text.contains("hdrgm:Version=\"1.0\""))
        assertTrue(text.contains("<Item:Semantic>GainMap</Item:Semantic>"))
        assertHasFullSpecFieldSet(text, 5_000L)
        // 保留属性参与下的字节幂等：merge(merge(x)) 与 merge(x) 一字不差
        val bytes = XmpMerger.merge(stale, 5_000L)
        assertArrayEquals(bytes, XmpMerger.merge(String(bytes, Charsets.UTF_8), 5_000L))
    }

    // ---------- 源包的其他形状 ----------

    @Test fun `source without any container directory gets one`() {
        val source = """
            <?xml version="1.0" encoding="UTF-8"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999-02-22-rdf-syntax-ns#">
              <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
               <dc:description>夕阳，海边</dc:description>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val text = merge(source, 4096L)
        assertEquals(1, count(text, "<Container:Directory>"))
        assertHasFullSpecFieldSet(text, 4096L)
        assertTrue(text.contains("<dc:description>夕阳，海边</dc:description>"), "用户的说明被丢了")
        assertTrue(text.contains("xmlns:dc=\"http://purl.org/dc/elements/1.1/\""))
        // 没有 GainMap 项时只有两项，Primary 仍在 MotionPhoto 之前
        assertEquals(listOf("Primary", "MotionPhoto"), semantics(text))
    }

    @Test fun `several container directories collapse into one`() {
        val source = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999-02-22-rdf-syntax-ns#">
              <rdf:Description xmlns:Container="http://ns.google.com/photos/1.0/container/">
               <Container:Directory><rdf:Seq>
                <rdf:li xmlns:Item="http://ns.google.com/photos/1.0/container/item/"><Item:Mime>image/jpeg</Item:Mime><Item:Semantic>Primary</Item:Semantic></rdf:li>
               </rdf:Seq></Container:Directory>
              </rdf:Description>
              <rdf:Description xmlns:Container="http://ns.google.com/photos/1.0/container/">
               <Container:Directory><rdf:Seq>
                <rdf:li xmlns:Item="http://ns.google.com/photos/1.0/container/item/"><Item:Mime>image/jpeg</Item:Mime><Item:Semantic>GainMap</Item:Semantic><Item:Length>441963</Item:Length></rdf:li>
               </rdf:Seq></Container:Directory>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
        val text = merge(source, 640L)
        assertEquals(1, count(text, "<Container:Directory>"))
        assertEquals(1, count(text, "<Item:Semantic>MotionPhoto</Item:Semantic>"))
        assertTrue(text.contains("<Item:Length>441963</Item:Length>"))
        assertHasFullSpecFieldSet(text, 640L)
        assertEquals(listOf("Primary", "GainMap", "MotionPhoto"), semantics(text))
    }

    @Test fun `merged output is still well formed xml`() {
        val doc = parse(merge(Fixtures.gainMapXmp(441_963L), 5_000L))
        assertEquals("xmpmeta", doc.documentElement.localName)
        assertEquals(XmpPacket.XMPMETA_NS, doc.documentElement.namespaceURI)
        // 包体仍然被 xpacket 起止对包裹，APP1 的读取方靠这两行界定边界
        val text = merge(Fixtures.gainMapXmp(441_963L), 5_000L)
        assertTrue(text.startsWith(XmpPacket.PACKET_BEGIN), "开头没有 xpacket begin")
        assertTrue(text.endsWith(XmpPacket.PACKET_END), "结尾没有 xpacket end")
    }

    @Test fun `escaped characters survive one round trip`() {
        val source = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF " +
            "xmlns:rdf=\"http://www.w3.org/1999-02-22-rdf-syntax-ns#\">" +
            "<rdf:Description xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
            "<dc:title>a &amp; b &lt;svg&gt; \"c\"</dc:title></rdf:Description></rdf:RDF></x:xmpmeta>"
        val once = merge(source, 32L)
        assertEquals("a & b <svg> \"c\"", parse(once).getElementsByTagName("dc:title").item(0).textContent)
        // 二次合并先解析再序列化，转义不会被重复叠加成 &amp;amp;
        assertArrayEquals(
            once.toByteArray(Charsets.UTF_8),
            XmpMerger.merge(once, 32L),
        )
    }

    // ---------- 畸形输入：绝不静默丢用户的元数据 ----------

    @Test fun `unparseable packet is reported as malformed`() {
        for (bad in listOf(
            "这不是 XML",
            "<x:xmpmeta><rdf:RDF>",                     // 没闭合
            "<x:xmpmeta></wrong>",
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999-02-22-rdf-syntax-ns#\"/>",  // 根不是 xmpmeta
        )) {
            val error = assertThrows(MalformedXmpException::class.java) { XmpMerger.merge(bad, 100L) }
            // 原因链留给日志：BatchRunner 只报枚举，但实现里必须知道是解析炸还是形状不对
            assertTrue(error.message!!.contains("XMP"), error.message ?: "")
        }
    }

    @Test fun `doctype and external entities are refused before they are resolved`() {
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE x:xmpmeta [<!ENTITY e SYSTEM "file:///etc/passwd"]>
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999-02-22-rdf-syntax-ns#">
            <rdf:Description>&e;</rdf:Description></rdf:RDF></x:xmpmeta>
        """.trimIndent()
        assertThrows(MalformedXmpException::class.java) { XmpMerger.merge(xxe, 1L) }
        val parameterEntity = """
            <?xml version="1.0"?>
            <!DOCTYPE x [<!ENTITY % p SYSTEM "http://evil/x.dtd">%p;]>
            <x:xmpmeta xmlns:x="adobe:ns:meta/"/>
        """.trimIndent()
        assertThrows(MalformedXmpException::class.java) { XmpMerger.merge(parameterEntity, 1L) }
    }

    @Test fun `deeply nested packet is refused as malformed, not as a stack overflow`() {
        // 遍历与序列化都是递归的：不把深度卡死，StackOverflowError（Error，不是 Exception）
        // 会穿过 composer 与 BatchRunner 的 catch，把整批任务掀翻。
        val depth = 5_000
        val nested = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
            "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999-02-22-rdf-syntax-ns#\">" +
            (0 until depth).joinToString("") { "<l$it>" } +
            (0 until depth).joinToString("") { "</l$it>" } +
            "</rdf:RDF></x:xmpmeta>"
        assertThrows(MalformedXmpException::class.java) { XmpMerger.merge(nested, 1L) }
        // 正常深度（七层）照旧放行，证明这条卡的是嵌套而不是解析本身
        assertTrue(merge(Fixtures.gainMapXmp(441_963L), 1L).isNotEmpty())
    }

    @Test fun `merged packet that no longer fits one app1 is refused`() {
        // APP1 的长度字段只有 16 位；源包本身就能贴着 65533 字节，加上我们的 GCamera 组就溢出。
        val huge = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF " +
            "xmlns:rdf=\"http://www.w3.org/1999-02-22-rdf-syntax-ns#\"><rdf:Description>" +
            "x".repeat(65_500) +
            "</rdf:Description></rdf:RDF></x:xmpmeta>"
        assertThrows(IllegalArgumentException::class.java) { XmpMerger.merge(huge, 1L) }
    }

    @Test fun `merger keeps an existing primary item instead of adding a second one`() {
        val text = merge(Fixtures.gainMapXmp(441_963L), 5_000L)
        assertEquals(1, count(text, "<Item:Semantic>Primary</Item:Semantic>"))
        // Primary 与 GainMap 都是 image/jpeg，MotionPhoto 才是 video/mp4：两比一
        assertEquals(2, count(text, "<Item:Mime>image/jpeg</Item:Mime>"))
        assertEquals(1, count(text, "<Item:Mime>video/mp4</Item:Mime>"))
    }
}
