package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element

/**
 * spec「增补（2026-10-18b）」：产物 XMP 必须与 vivo 相册**已经认可**的那批合成结果同形——
 * 单个 `rdf:RDF`、动照片七属性挤在**同一个** `rdf:Description` 上、`Container:Directory` 里是
 * **空的** `<rdf:Seq/>`。Task 13 的"保留/重建 Container 项"与"另起一个 RDF 挂我们的声明"两条
 * 都被这份语料推翻，本类逐条钉住新形状。形状一律重解析 DOM 后数节点（§4），不比字符串。
 */
class XmpMergerTest {

    /** 测试自己写字面量，不从 [XmpPacket] 取：常量写错时用例必须红，不能跟着一起改。 */
    private val rdf = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private val container = "http://ns.google.com/photos/1.0/container/"
    private val item = "http://ns.google.com/photos/1.0/container/item/"
    private val camera = "http://ns.google.com/photos/1.0/camera/"
    private val dc = "http://purl.org/dc/elements/1.1/"
    private val hdrgm = "http://ns.adobe.com/hdr-gain-map/1.0/"
    private val xmlns = "http://www.w3.org/2000/xmlns/"

    private fun merge(source: String?, videoSize: Long): String =
        XmpMerger.merge(source, videoSize).decodeToString()

    private fun parse(text: String): Element = XmpShape.parse(text).documentElement

    /** 全树第一个带着 [qualifiedName] 属性的元素（源内容该留在它原来的位置上）。 */
    private fun elementWith(root: Element, qualifiedName: String): Element =
        XmpShape.allElements(root).firstOrNull { it.getAttribute(qualifiedName).isNotEmpty() }
            ?: throw IllegalArgumentException("产物里找不到属性 $qualifiedName")

    /** 把若干 `rdf:Description` 拼进一个单 RDF 的源包。 */
    private fun packet(vararg descriptions: String): String =
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
            "<rdf:RDF xmlns:rdf=\"$rdf\">" + descriptions.joinToString("") + "</rdf:RDF></x:xmpmeta>"

    /** 带 `Container:Directory` 的那个 Description——宿主判据（§3.2）的原材料。 */
    private fun directoryDescription(items: String = ""): String =
        "<rdf:Description xmlns:Container=\"$container\"><Container:Directory>" +
            "<rdf:Seq>$items</rdf:Seq></Container:Directory></rdf:Description>"

    /** 项写成子元素文本的那一型（Task 13 的实现只认得这一型）。 */
    private fun textItem(semantic: String, mime: String, length: Long? = null): String =
        "<rdf:li rdf:parseType=\"Resource\">" +
            "<Item:Mime xmlns:Item=\"$item\">$mime</Item:Mime>" +
            "<Item:Semantic xmlns:Item=\"$item\">$semantic</Item:Semantic>" +
            (length?.let { "<Item:Length xmlns:Item=\"$item\">$it</Item:Length>" } ?: "") +
            "</rdf:li>"

    /** 语料里那一型：语义与 MIME 是 `Container:Item` **子元素的属性**。 */
    private fun attrItem(semantic: String, mime: String, length: Long? = null): String =
        "<rdf:li rdf:parseType=\"Resource\"><Container:Item xmlns:Item=\"$item\" Item:Semantic=\"$semantic\"" +
            " Item:Mime=\"$mime\"" + (length?.let { " Item:Length=\"$it\"" } ?: "") + "/></rdf:li>"

    /** 产物里唯一 Directory 的 `rdf:Seq`。 */
    private fun emptySeq(root: Element): Element =
        XmpShape.childrenNamed(XmpShape.directories(root).single(), "Seq").single()

    // ---------- 真实 vivo 源包（Task 13 的实现就是在这儿翻的车） ----------

    @Test fun `vivo source packet keeps exactly one root rdf element`() {
        val root = parse(merge(Fixtures.vivoSourcePacket(), 3_863_927L))
        // 源包用的是标准 RDF 命名空间：我们的声明必须挂到**它**那个 RDF 上。另起一个 RDF 就是
        // 真机产物里那两个兄弟 RDF——按 x:xmpmeta/rdf:RDF/rdf:Description 取属性的读取方只看见空壳。
        assertEquals(1, XmpShape.childrenNamed(root, "RDF").size, "产物里不止一个 rdf:RDF")
        assertEquals(1, XmpShape.descriptions(root).size, "我们的声明新建了第二个 rdf:Description")
    }

    @Test fun `vivo source packet puts the seven attributes on the directory host`() {
        val size = 3_863_927L
        val host = XmpShape.assertVivoShape(merge(Fixtures.vivoSourcePacket(), size), size)
        assertEquals("1", host.getAttributeNS(camera, "MicroVideo"))
        assertEquals("1", host.getAttributeNS(camera, "MotionPhoto"))
        assertEquals("-1", host.getAttributeNS(camera, "MotionPhotoPresentationTimestampUs"))
        assertEquals(hdrgm, host.getAttributeNS(xmlns, "hdrgm"), "七属性没落在源片那个带 Directory 的 Description 上")
    }

    @Test fun `vivo source packet ends up with an empty container directory`() {
        val root = parse(merge(Fixtures.vivoSourcePacket(), 5_000L))
        assertEquals(0, XmpShape.childrenNamed(emptySeq(root), "li").size, "副图项或视频项还留在 Seq 里")
        // 项是 Container:Item 元素的属性：Directory 被摘掉时它们跟着消失，不需要认语义。
        assertTrue(
            XmpShape.descendantsNamed(root, container, "Item").isEmpty(),
            "源片的 Container:Item 还赖在产物里",
        )
        assertTrue(
            XmpShape.allElements(root).none { it.getAttribute("Item:Length").isNotEmpty() },
            "副图长度声明还在——产物根本不携带副图字节",
        )
    }

    @Test fun `vivo source packet keeps its own hdrgm version and xmptk`() {
        val text = merge(Fixtures.vivoSourcePacket(), 5_000L)
        val root = parse(text)
        val host = elementWith(root, "hdrgm:Version")
        assertEquals("1.0", host.getAttribute("hdrgm:Version"))
        assertEquals("Adobe XMP Core 5.1.2", root.getAttribute("x:xmptk"), "写入方签名被我们冒充了")
        // §3.4 的边界：只动 Directory 与七属性，源包连 xmlns 前缀声明都不该顺手删。
        assertEquals(item, host.getAttributeNS(xmlns, "Item"))
        assertEquals(container, host.getAttributeNS(xmlns, "Container"))
    }

    @Test fun `merging the vivo output again is byte identical`() {
        val once = XmpMerger.merge(Fixtures.vivoSourcePacket(), 3_863_927L)
        assertArrayEquals(once, XmpMerger.merge(once.decodeToString(), 3_863_927L), "第二次合改写出的字节动了")
    }

    @Test fun `blank source packet gives the same bytes as the template`() {
        for (size in listOf(0L, 1L, 987_654L)) {
            // 模板与合并共用同一个构造器（§3 末段）：两条路径同形，字节也得一字不差。
            assertArrayEquals(XmpPacket.motionPhoto(size), XmpMerger.merge(null, size))
            // 空文本 = "这一段 APP1 里其实什么都没有"，同样走构造器，绝不当成畸形
            assertArrayEquals(XmpPacket.motionPhoto(size), XmpMerger.merge("", size))
            assertArrayEquals(XmpPacket.motionPhoto(size), XmpMerger.merge("   \n\t ", size))
        }
    }

    @Test fun `packet without a source has the shape of a merged one`() {
        val size = 4_200_000L
        val text = merge(null, size)
        val host = XmpShape.assertVivoShape(text, size)
        assertEquals("", host.getAttributeNS(rdf, "about"))
        // 我们不冒充别人的写入方签名：新文档的根上没有 x:xmptk。
        assertEquals("", parse(text).getAttribute("x:xmptk"))
    }

    // ---------- §3.2 宿主归属、§3.3 Directory 只剩一个 ----------

    @Test fun `directory on a later description moves to that host`() {
        val source = packet(
            "<rdf:Description rdf:about=\"\" xmlns:dc=\"$dc\"><dc:description>夕阳，海边</dc:description></rdf:Description>",
            directoryDescription(attrItem("Primary", "image/jpeg")),
        )
        val size = 640L
        val text = merge(source, size)
        val root = parse(text)
        val host = XmpShape.assertVivoShape(text, size)
        // 宿主是**带 Directory 的那个** Description，不是第一个（§3.2）。
        assertEquals(container, host.getAttributeNS(xmlns, "Container"))
        assertEquals("夕阳，海边", XmpShape.descendantsNamed(root, "description").single().textContent)
    }

    @Test fun `several directories collapse into one empty directory on the host`() {
        val source = packet(
            directoryDescription(attrItem("Primary", "image/jpeg")),
            directoryDescription(textItem("GainMap", "image/jpeg", 441_963L)),
        )
        val size = 640L
        val text = merge(source, size)
        val root = parse(text)
        XmpShape.assertVivoShape(text, size)
        assertEquals(1, XmpShape.directories(root).size, "第二个位置的 Directory 没被摘掉")
        assertTrue(
            XmpShape.descendantsNamed(root, container, "Item").isEmpty(),
            "别处的项跟着第二个 Directory 留下了",
        )
        assertTrue(
            XmpShape.allElements(root).none { it.getAttribute("Item:Length").isNotEmpty() },
            "第二个 Directory 里的副图长度声明留下了",
        )
    }

    @Test fun `source without any directory still gets exactly one empty one`() {
        val source = packet(
            "<rdf:Description rdf:about=\"\" xmlns:dc=\"$dc\">" +
                "<dc:description>夕阳，海边</dc:description></rdf:Description>",
        )
        val size = 4096L
        val text = merge(source, size)
        val root = parse(text)
        val host = XmpShape.assertVivoShape(text, size)
        assertEquals("夕阳，海边", XmpShape.descendantsNamed(root, "description").single().textContent, "用户的说明被丢了")
        assertEquals("1", host.getAttributeNS(camera, "MotionPhotoVersion"))
        assertFalse(
            listOf("Item:Semantic", "Item:Mime", "Item:Length").any { text.contains(it) },
            "源里没有项时也不该凭空造一个出来",
        )
    }

    @Test fun `motion photo attributes on another description do not make a second group`() {
        val source = packet(
            directoryDescription(),
            "<rdf:Description xmlns:GCamera=\"$camera\" GCamera:MotionPhoto=\"1\"" +
                " GCamera:MicroVideoOffset=\"999999\"/>",
        )
        val size = 5_000L
        val text = merge(source, size)
        assertEquals(
            XmpShape.CAMERA_ATTRIBUTES.size,
            XmpShape.motionPhotoAttributeCount(parse(text)),
            "源里那一组陈旧属性没被剔干净",
        )
        assertFalse(text.contains("999999"), "上一轮的偏移量还留在产物里")
    }

    @Test fun `non directory content of the source survives untouched`() {
        // §3.4 的边界：只动 Directory 与七属性。dc:title 的 rdf:Alt 里也躺着一个 rdf:li——
        // 谁要是"把所有 rdf:li 删光"，这条就红。
        val source = packet(
            "<rdf:Description rdf:about=\"\">" +
                "<dc:title xmlns:dc=\"$dc\"><rdf:Alt><rdf:li xml:lang=\"x-default\">标题</rdf:li></rdf:Alt></dc:title>" +
                "</rdf:Description>",
            directoryDescription(attrItem("GainMap", "image/jpeg", 441_963L)),
        )
        val size = 700L
        val root = parse(merge(source, size))
        val title = XmpShape.descendantsNamed(root, "title").single()
        val altLi = XmpShape.descendantsNamed(title, "li").single()
        assertEquals("标题", altLi.textContent)
        assertEquals("x-default", altLi.getAttribute("xml:lang"))
        assertEquals(1, XmpShape.directories(root).size, "Directory 该只剩宿主上那一个")
        assertEquals(0, XmpShape.childrenNamed(emptySeq(root), "li").size, "Directory 的 Seq 该是空的")
        // 宿主 = 带 Directory 的那个 Description（这里正好是第二个），七属性只写在它身上。
        assertEquals(
            size.toString(),
            hostOfFirstDescriptionWithDirectory(root).getAttributeNS(camera, "MicroVideoOffset"),
        )
    }

    // ---------- gain map 源包（语义写成子元素文本的那一型） ----------

    @Test fun `gain map source keeps its own metadata but loses its declarations`() {
        val size = 5_000L
        val text = merge(Fixtures.gainMapXmp(441_963L), size)
        val root = parse(text)
        // 副图字节不再进产物，它的声明也不留：一个项都不许在。
        assertEquals(0, XmpShape.childrenNamed(emptySeq(root), "li").size)
        assertTrue(XmpShape.allElements(root).none { it.getAttribute("Item:Semantic").isNotEmpty() })
        // 无关命名空间与属性原样保留，位置不动。
        assertEquals("1.3600", elementWith(root, "GCamera:BoostCorrection").getAttribute("GCamera:BoostCorrection"))
        assertEquals("2.0000", elementWith(root, "hdrgm:GainMapHeadroom").getAttribute("hdrgm:GainMapHeadroom"))
        assertEquals("vivo XMP Toolkit 1.0", root.getAttribute("x:xmptk"))
        assertEquals("", XmpShape.descriptions(root).first().getAttributeNS(rdf, "about"))
        XmpShape.assertVivoShape(text, size)
    }

    @Test fun `only the seven motion photo gcamera attributes are stripped`() {
        // 剔除规则是**七个**动态照片专用属性的白名单，不是"整个 GCamera 命名空间"。抽样里 vivo 的包
        // 带着 GCamera:BoostCorrection（HDR 增益记录），删它会改变 HDR 渲染；其余命名空间照旧不动。
        val root = parse(merge(Fixtures.gainMapXmp(441_963L), 5_000L))
        assertEquals("1.3600", elementWith(root, "GCamera:BoostCorrection").getAttribute("GCamera:BoostCorrection"),
            "HDR 增益记录被剔了")
        assertEquals(7, XmpShape.motionPhotoAttributeCount(root), "动照片七属性出现了不止一组")
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
        val size = 5_000L
        val text = merge(stale, size)
        val root = parse(text)
        // 留下的：原值、原属性名，一条不多一条不少
        listOf(
            "GCamera:BoostCorrection" to "1.3600",
            "GCamera:ImageEmbeddings" to "checksum-abcdef",
            "GCamera:BurstId" to "burst7",
            "GCamera:BurstMasterPhoto" to "true",
        ).forEach { (name, value) ->
            assertEquals(value, elementWith(root, name).getAttribute(name), "保留属性丢了或改了：$name")
        }
        // 剔掉的：陈旧值一律以我们算出来的值出现，且只出现在宿主上
        val host = XmpShape.assertVivoShape(text, size)
        assertEquals(size.toString(), host.getAttributeNS(camera, "MicroVideoOffset"))
        listOf(
            "MicroVideo" to "1",
            "MicroVideoVersion" to "1",
            "MicroVideoPresentationTimestampUs" to "-1",
            "MotionPhoto" to "1",
            "MotionPhotoVersion" to "1",
            "MotionPhotoPresentationTimestampUs" to "-1",
        ).forEach { (name, value) ->
            assertEquals(value, host.getAttributeNS(camera, name), "$name 没被计算值取代")
        }
        // 陈旧值不许以**属性值**的身份留在任何一个元素上。这里不能拿字符串找 "999"：
        // 命名空间 `.../1999/02/22-rdf-syntax-ns#` 里就含 "999"，子串判据会把好产物说成坏的。
        val values = XmpShape.allElements(root).flatMap { element ->
            (0 until element.attributes.length).map { element.attributes.item(it)!!.nodeValue }
        }
        assertFalse(values.any { it == "999" }, "陈旧偏移残留")
        assertFalse(values.any { it == "424242" }, "陈旧时间戳残留")
        // 保留属性参与下的字节幂等：merge(merge(x)) 与 merge(x) 一字不差
        val bytes = XmpMerger.merge(stale, size)
        assertArrayEquals(bytes, XmpMerger.merge(String(bytes, Charsets.UTF_8), size))
    }

    @Test fun `merging an already merged packet is byte identical`() {
        for (videoSize in listOf(123L, 8_000_000L)) {
            val once = XmpMerger.merge(Fixtures.gainMapXmp(441_963L), videoSize)
            val twice = XmpMerger.merge(String(once, Charsets.UTF_8), videoSize)
            assertArrayEquals(once, twice, "第二次合改写出的字节和第一次不一样")
            // 换个视频长度再合一次：只有 MicroVideoOffset 跟着走，其余一个字节都不动
            val resized = XmpMerger.merge(String(once, Charsets.UTF_8), videoSize + 1)
            assertEquals((videoSize + 1).toString(), hostOffsetOf(parse(resized.decodeToString())))
            assertFalse(resized.decodeToString().contains(" \"$videoSize\""), "上一轮的视频长度还留在产物里")
        }
    }

    /** §3.2 的宿主：带 `Container:Directory` 的那个 Description。 */
    private fun hostOfFirstDescriptionWithDirectory(root: Element): Element =
        XmpShape.descriptions(root).first { XmpShape.directories(it).isNotEmpty() }

    private fun hostOffsetOf(root: Element): String =
        hostOfFirstDescriptionWithDirectory(root).getAttributeNS(camera, "MicroVideoOffset")

    @Test fun `merging our own template keeps one group and replaces the size`() {
        val text = merge(XmpPacket.motionPhoto(777L).decodeToString(), 900L)
        XmpShape.assertVivoShape(text, 900L)
        assertEquals(1, XmpShape.descriptions(parse(text)).size, "模板当输入时多出了一个 Description")
        assertFalse(text.contains("777"), "上一轮的视频长度还留在产物里")
        val bytes = XmpMerger.merge(XmpPacket.motionPhoto(900L).decodeToString(), 900L)
        assertArrayEquals(bytes, XmpMerger.merge(String(bytes, Charsets.UTF_8), 900L), "规范写法不是不动点")
    }

    // ---------- 输出仍然是一个能被别人安全再解析一次的包 ----------

    @Test fun `merged output is still well formed xml`() {
        val doc = XmpShape.parse(merge(Fixtures.vivoSourcePacket(), 5_000L))
        assertEquals("xmpmeta", doc.documentElement.localName)
        assertEquals(XmpPacket.XMPMETA_NS, doc.documentElement.namespaceURI)
        val text = merge(Fixtures.gainMapXmp(441_963L), 5_000L)
        // 包体仍然被 xpacket 起止对包裹，APP1 的读取方靠这两行界定边界
        assertTrue(text.startsWith(XmpPacket.PACKET_BEGIN), "开头没有 xpacket begin")
        assertTrue(text.endsWith(XmpPacket.PACKET_END), "结尾没有 xpacket end")
    }

    @Test fun `escaped characters survive one round trip`() {
        val source = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF " +
            "xmlns:rdf=\"$rdf\">" +
            "<rdf:Description xmlns:dc=\"$dc\">" +
            "<dc:title>a &amp; b &lt;svg&gt; \"c\"</dc:title></rdf:Description></rdf:RDF></x:xmpmeta>"
        val once = merge(source, 32L)
        assertEquals(
            "a & b <svg> \"c\"",
            XmpShape.descendantsNamed(parse(once), "title").single().textContent,
        )
        // 二次合并先解析再序列化，转义不会被重复叠加成 &amp;amp;
        assertArrayEquals(once.toByteArray(Charsets.UTF_8), XmpMerger.merge(once, 32L))
    }

    // ---------- 畸形输入：绝不静默丢用户的元数据 ----------

    @Test fun `unparseable packet is reported as malformed`() {
        for (bad in listOf(
            "这不是 XML",
            "<x:xmpmeta><rdf:RDF>",                     // 没闭合
            "<x:xmpmeta></wrong>",
            "<rdf:RDF xmlns:rdf=\"$rdf\"/>",            // 根不是 xmpmeta
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
            <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="$rdf">
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
            "<rdf:RDF xmlns:rdf=\"$rdf\">" +
            (0 until depth).joinToString("") { "<l$it>" } +
            (0 until depth).joinToString("") { "</l$it>" } +
            "</rdf:RDF></x:xmpmeta>"
        assertThrows(MalformedXmpException::class.java) { XmpMerger.merge(nested, 1L) }
        // 正常深度（vivo 那七层）照旧放行，证明这条卡的是嵌套而不是解析本身
        assertTrue(merge(Fixtures.vivoSourcePacket(), 1L).isNotEmpty())
    }

    @Test fun `merged packet that no longer fits one app1 is refused`() {
        // APP1 的长度字段只有 16 位；源包本身就能贴着 65533 字节，加上我们的 GCamera 组就溢出。
        val huge = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF " +
            "xmlns:rdf=\"$rdf\"><rdf:Description>" +
            "x".repeat(65_500) +
            "</rdf:Description></rdf:RDF></x:xmpmeta>"
        assertThrows(IllegalArgumentException::class.java) { XmpMerger.merge(huge, 1L) }
    }
}
