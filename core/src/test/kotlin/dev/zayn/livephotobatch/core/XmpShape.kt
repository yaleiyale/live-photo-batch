package dev.zayn.livephotobatch.core

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * spec「增补（2026-10-18b）」§4 的形状判据：产物形态**重解析 DOM 后按节点数断言，不许比对字符串**。
 * 模板路径与合并路径共用这一套判据——两条路径必须同形，所以判据也只能有一份。
 *
 * `RDF`/`Description`/`Seq`/`li` 一律按**本地名**匹配而不看命名空间：语料里读这些节点的工具
 * （Exiv2、相册）认的就是 `rdf:` 前缀的那几个名字，我们的宿主 RDF 与源片宿主 RDF 只要名字对就得
 * 是同一个——多出来一个哪怕命名空间拼错的兄弟 RDF，同样是"两个 RDF"这个真机故障。
 */
object XmpShape {

    /** §7.3 的七个动态照片属性（本地名）：参考语料两组都写，两组都保留。 */
    val CAMERA_ATTRIBUTES = listOf(
        "MicroVideo",
        "MicroVideoVersion",
        "MicroVideoOffset",
        "MicroVideoPresentationTimestampUs",
        "MotionPhoto",
        "MotionPhotoVersion",
        "MotionPhotoPresentationTimestampUs",
    )

    /** 与生产解析同一套安全开关：测试这边也证明产物是能被人安全再解析一次的。 */
    private fun safeFactory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }

    fun parse(packet: String): Document =
        safeFactory().newDocumentBuilder()
            .parse(ByteArrayInputStream(packet.toByteArray(Charsets.UTF_8)))

    /** [parent] 名下**直接**子元素里本地名对得上的那些。 */
    fun childrenNamed(parent: Element, localName: String): List<Element> =
        children(parent).filter { local(it) == localName }

    /** [root] 名下（含 [root] 自身）所有本地名对得上的元素，先序。 */
    fun descendantsNamed(root: Element, localName: String): List<Element> =
        walk(root).filter { local(it) == localName }

    /** 全树里带 [ns] 命名空间且本地名为 [localName] 的元素。 */
    fun descendantsNamed(root: Element, ns: String, localName: String): List<Element> =
        walk(root).filter { it.namespaceURI == ns && local(it) == localName }

    fun allElements(root: Element): List<Element> = walk(root)

    private fun walk(el: Element): List<Element> = listOf(el) + children(el).flatMap(::walk)

    private fun children(parent: Node): List<Element> =
        (0 until parent.childNodes.length).mapNotNull { parent.childNodes.item(it) as? Element }

    private fun local(el: Element): String = el.localName ?: el.tagName.substringAfter(':')

    /** 元素上 `GCamera` 命名空间属性的本地名集合（`xmlns:*` 声明不算属性）。 */
    fun cameraAttributes(el: Element): Set<String> =
        (0 until el.attributes.length)
            .map { el.attributes.item(it)!! }
            .filter { it.namespaceURI == XmpPacket.CAMERA_NS }
            .map { it.localName ?: it.nodeName.substringAfter(':') }
            .toSet()

    /**
     * 全树里那**七个**动照片属性出现的总次数：多一个就说明有两组声明。
     * 只数这七个名字——BoostCorrection 一类的其他 GCamera 属性是源内容，§3.4 要求原样留着，
     * 把它们算进"只能有七个 GCamera 属性"里就是把剔除规则写成了黑名单。
     */
    fun motionPhotoAttributeCount(root: Element): Int =
        walk(root).sumOf { cameraAttributes(it).count { name -> name in CAMERA_ATTRIBUTES } }

    /** 全树所有 `rdf:Description`。 */
    fun descriptions(root: Element): List<Element> = descendantsNamed(root, "Description")

    /** 全树所有 `Container:Directory`。 */
    fun directories(root: Element): List<Element> =
        descendantsNamed(root, XmpPacket.CONTAINER_NS, "Directory")

    /**
     * §4 (a)(b)(c)(d)：恰好一个 `rdf:RDF`；七属性只落在一个 `rdf:Description` 上、且它是唯一
     * `Container:Directory` 的父节点；那个 Directory 的 `rdf:Seq` 里没有 `rdf:li`；
     * `GCamera:MicroVideoOffset` 等于 [videoSize]。
     * @return 承载七属性与 Directory 的宿主 Description。
     */
    fun assertVivoShape(packet: String, videoSize: Long): Element {
        val doc = parse(packet)
        val root = doc.documentElement
        assertEquals(1, childrenNamed(root, "RDF").size, "x:xmpmeta 名下不止一个 rdf:RDF")

        val withAllSeven = descriptions(root).filter { cameraAttributes(it).containsAll(CAMERA_ATTRIBUTES) }
        assertEquals(1, withAllSeven.size, "七属性没有集中在恰好一个 rdf:Description 上")
        val host = withAllSeven.single()
        // 别处一组都不许有：躲在第二个 RDF 或第二个 Description 里的声明，读取方看不见（§3.1 的实测教训）。
        assertEquals(CAMERA_ATTRIBUTES.size, motionPhotoAttributeCount(root), "全树的动照片属性多于一组")

        val found = directories(root)
        assertEquals(1, found.size, "Container:Directory 不止一个")
        val directory = found.single()
        assertEquals(host, directory.parentNode, "Directory 不在承载七属性那个 Description 名下")
        val seqs = childrenNamed(directory, "Seq")
        assertEquals(1, seqs.size, "Directory 里恰好一个 rdf:Seq")
        assertEquals(0, childrenNamed(seqs.single(), "li").size, "rdf:Seq 不是空的")

        assertEquals(
            videoSize.toString(),
            host.getAttributeNS(XmpPacket.CAMERA_NS, "MicroVideoOffset"),
            "GCamera:MicroVideoOffset 不是传进去的视频长度",
        )
        return host
    }
}
