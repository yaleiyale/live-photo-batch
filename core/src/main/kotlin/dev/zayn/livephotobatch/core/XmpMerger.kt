package dev.zayn.livephotobatch.core

import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NamedNodeMap
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException

/** 源 JPEG 里的 XMP 包读不出来：这项必须报失败，绝不能"当没有 XMP"处理掉用户的元数据。 */
class MalformedXmpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * spec「增补（2026-10-18b）」§3：产物 XMP 必须与 **vivo 相册已经认可**的那批合成结果同形。
 * 逐字节比对的语料（17 张，其中 3 张验到字节）里那张宿主描述是：
 * 一个 `rdf:RDF`、七个动照片属性挤在**同一个** `rdf:Description` 上、
 * `<Container:Directory>` 里是**空的** `<rdf:Seq/>`——既不声明 gain map 项，也不声明视频项，
 * 读取方靠 `GCamera:MicroVideoOffset` 定位视频。
 *
 * 四条规则（§3.1-§3.4）：
 * 1. 只用 `<x:xmpmeta>` 下**第一个** `rdf:RDF` 作宿主；一个都没有才新建并追加。
 *    Task 13 的实现产出过两个兄弟 RDF：源包被掏空只剩 `hdrgm:Version` 留在第一个 RDF 里，我们的声明
 *    挂在第二个 RDF 上。XML 仍然合法所以解析不报错，但按 `xmpmeta/rdf:RDF/rdf:Description` 取属性的
 *    读取方（相册、Exiv2）只看得见那个空壳——等于没声明，真机就是这么判我们不是动态照片的。
 * 2. 七属性只写在一个 Description 上：宿主 = 带 `Container:Directory` 的那个，没有 Directory 就用第一个
 *    Description，一个都没有才新建；其余 Description 上的七属性（[MOTION_PHOTO_ATTRIBUTES]）一律剔掉。
 * 3. `Container:Directory` 只有一个、且 `rdf:Seq` 为空：全树任何位置的 Directory 连同项一起摘掉，
 *    再往宿主上追加一份空的。vivo 把项写成 `<Container:Item Item:Semantic="…" Item:Mime="…"/>`，
 *    语义与 MIME 是**子元素的属性**，逐个识别语义既做不到也不需要——产物不声明项。
 * 4. 其余内容一概不动：`hdrgm:*`、`dc:*`、厂商命名空间、`GCamera:BoostCorrection` 这类非动照片属性、
 *    根上的 `x:xmptk`（源值优先）全部原样留着。
 *
 * 源包为 null 或整段空白时走 [newDocument]，与合并路径共用同一个构造器：模板与合并产物同形，
 * 形状不可能在两条路径上各自漂移（§3 末段）。
 *
 * ## 为什么解析与序列化都自己组织
 * 解析、增删全部走 `javax.xml` DOM（不对 XML 文本动一次正则）。输出没有用 `Transformer`：它的缩进
 * 与命名空间声明的落点不由我们决定，而"摘掉一个节点再插回来"之后残留的空白文本节点归谁，会让二次
 * 合并无法回到同一串字节（实测 identity `Transformer` 自身 parse→serialize 是稳定的，不稳定的是
 * **结构增删之后**的空白归属）。这里写的输出把空白变成"树深度的纯函数"：纯空白文本节点、注释与
 * 处理指令一律丢弃，缩进按层级补空格，属性按 DOM 顺序输出，元素用到而祖先未声明的前缀按字典序补在
 * 属性表末尾。于是"字节幂等"只取决于树是不是不动点——而上面那对剔除/追加规则本身就互逆。
 */
object XmpMerger {
    /** `xmlns:prefix="uri"` 这类声明在 DOM 里就住在这个命名空间下。 */
    private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"

    /**
     * 剔除白名单：**只有**这七个动态照片属性会被剔掉重写，按 GCamera 命名空间 + 本地名精确匹配。
     * 其余任何属性——`GCamera:BoostCorrection`（HDR 增益记录）、`GCamera:ImageEmbeddings`、`GCamera:Burst*`、
     * `hdrgm:*`、厂商命名空间——一律原样保留（§3.4）。这不是"其他都删"的黑名单：规则就是七个名字。
     */
    private val MOTION_PHOTO_ATTRIBUTES = setOf(
        "MicroVideo",
        "MicroVideoVersion",
        "MicroVideoOffset",
        "MicroVideoPresentationTimestampUs",
        "MotionPhoto",
        "MotionPhotoVersion",
        "MotionPhotoPresentationTimestampUs",
    )

    /** 语料里那七属性与它们的值，次序照参考语料的宿主描述（MicroVideo 组在前、MotionPhoto 组在后）。 */
    private fun MOTION_PHOTO_VALUES(videoSize: Long): List<Pair<String, String>> {
        val size = videoSize.toString()
        return listOf(
            "GCamera:MicroVideo" to "1",
            "GCamera:MicroVideoVersion" to "1",
            "GCamera:MicroVideoOffset" to size,
            "GCamera:MicroVideoPresentationTimestampUs" to "-1",
            "GCamera:MotionPhoto" to "1",
            "GCamera:MotionPhotoVersion" to "1",
            "GCamera:MotionPhotoPresentationTimestampUs" to "-1",
        )
    }

    fun merge(sourcePacket: String?, videoSize: Long): ByteArray {
        val doc = if (sourcePacket.isNullOrBlank()) newDocument() else parse(sourcePacket)
        val root = doc.documentElement
            ?: throw MalformedXmpException("XMP 包没有根元素，无法合并")
        rejectDeepNesting(root)
        if (local(root) != "xmpmeta") {
            throw MalformedXmpException("XMP 包的根元素不是 x:xmpmeta，而是 <${root.tagName}>")
        }
        // §3.1 单根：有则用第一个，没有才新建并追加。绝不另起第二个 rdf:RDF 挂我们的声明。
        // 这一步成不成立全靠 [XmpPacket.RDF_NS] 与源包的 RDF 命名空间是同一个 URI：对不上号时
        // childElements 找不到宿主的 RDF，我们就在源 RDF 旁边又起了一个——真机的两个兄弟 RDF 就是这么来的。
        val rdf = childElements(root, XmpPacket.RDF_NS, "RDF").firstOrNull()
            ?: newElement(doc, XmpPacket.RDF_NS, "rdf", "RDF")
                .declare("rdf", XmpPacket.RDF_NS).also { root.appendChild(it) }
        // §3.2 宿主 = 带 Directory 的 Description；没有则第一个 Description；再没有才新建（带 rdf:about=""）。
        // 只认这个 RDF 里的 Description：x:xmpmeta/rdf:RDF/rdf:Description 是读取方走的形状，
        // 躲在别处的 Description 不是宿主候选。
        val descriptions = descendants(rdf).filter { local(it) == "Description" && it.namespaceURI == XmpPacket.RDF_NS }
        val host = descriptions.firstOrNull { hasDirectory(it) }
            ?: descriptions.firstOrNull()
            ?: newElement(doc, XmpPacket.RDF_NS, "rdf", "Description")
                .also { it.setAttributeNS(XmpPacket.RDF_NS, "rdf:about", "") }
                .also { rdf.appendChild(it) }
        // 先剔干净：全树七属性 + 全树 Directory（vivo 的项是 Container:Item 的属性形状，
        // 逐个识别语义既做不到也不需要——产物不声明项）。宿主的旧 Directory 也在这里被摘掉，
        // 随后重新追加空的一份，所以"只有一个 Directory 且 Seq 为空"是构造出来的性质。
        stripCameraAttributes(root)
        descendants(root).forEach { dropDirectories(it) }
        host.declare("GCamera", XmpPacket.CAMERA_NS)
        MOTION_PHOTO_VALUES(videoSize).forEach { (name, value) ->
            host.setAttributeNS(XmpPacket.CAMERA_NS, name, value)
        }
        host.appendChild(emptyDirectory(doc))
        return packet(doc)
    }

    // ---------- 解析（不可信输入） ----------

    private fun parse(text: String): Document = try {
        val builder = docBuilder().newDocumentBuilder()
        // 任何外部实体一律给一个空文档：既不联网，也不读盘。
        builder.setEntityResolver(EntityResolver { _, _ -> InputSource(StringReader("")) })
        builder.setErrorHandler(Reject)
        val doc = builder.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        if (doc.doctype != null) throw MalformedXmpException("XMP 包带 DTD，已拒绝解析")
        doc
    } catch (e: MalformedXmpException) {
        throw e
    } catch (e: Exception) {
        throw MalformedXmpException("源 XMP 无法解析：${e.message}", e)
    }

    /**
     * 安全解析的开关集中一处：新建空文档与解析源包共用它。逐个 try——JVM 的 Xerces 全都支持，
     * 但厂商 ROM 万一对某个开关说"不认识"，也不能让整条安全策略崩掉：下面还有 EntityResolver
     * 与 doctype 复查两道兜底。
     */
    private fun docBuilder(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        feature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        feature("http://apache.org/xml/features/disallow-doctype-decl", true)
        feature("http://xml.org/sax/features/external-general-entities", false)
        feature("http://xml.org/sax/features/external-parameter-entities", false)
        feature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    }

    /** 没有源包时的文档：与合并路径同一个构造器，两条路径不可能各自漂出一种形状。 */
    private fun newDocument(): Document {
        val doc = docBuilder().newDocumentBuilder().newDocument()
        doc.appendChild(
            newElement(doc, XmpPacket.XMPMETA_NS, "x", "xmpmeta").declare("x", XmpPacket.XMPMETA_NS)
        )
        // 根上不写 x:xmptk：那是别人的写入方签名，我们不冒充它。
        return doc
    }

    /** warning 也不放过：需要警告才能读进去的包，不值得替用户猜。 */
    private object Reject : ErrorHandler {
        override fun warning(e: SAXParseException) = throw e
        override fun error(e: SAXParseException) = throw e
        override fun fatalError(e: SAXParseException) = throw e
    }

    private fun DocumentBuilderFactory.feature(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (e: Exception) {
            // 平台不认这个开关：由 EntityResolver 与 doctype 复查兜底，绝不让合并整条崩掉。
        }
    }

    /**
     * 递归的树遍历（[descendants]、序列化）对深嵌套输入会走成栈溢出，而 `StackOverflowError` 是
     * [Error] 不是 [Exception]：它会穿过本任务的 `catch`、穿过 BatchRunner 的 `catch (Exception)`，
     * 把整批任务掀翻。源 XMP 是不可信输入，所以这里用**迭代**的先序遍历把深度卡死：
     * 真实 XMP 最深也就 `xmpmeta/RDF/Description/Directory/Seq/li/字段` 七层，64 层已经宽到没边。
     */
    private const val MAX_DEPTH = 64

    private fun rejectDeepNesting(root: Element) {
        val stack = ArrayDeque<Pair<Element, Int>>()
        stack.addLast(root to 1)
        while (stack.isNotEmpty()) {
            val (el, depth) = stack.removeLast()
            if (depth > MAX_DEPTH) throw MalformedXmpException("XMP 嵌套超过 $MAX_DEPTH 层，已拒绝解析")
            childElementsOf(el).forEach { stack.addLast(it to depth + 1) }
        }
    }

    // ---------- 剔除与写回 ----------

    private fun stripCameraAttributes(root: Element) {
        for (el in descendants(root)) {
            val names = attributesOf(el).filter {
                it.namespaceURI == XmpPacket.CAMERA_NS && localNameOf(it) in MOTION_PHOTO_ATTRIBUTES
            }
            names.forEach { el.removeAttributeNS(XmpPacket.CAMERA_NS, localNameOf(it)) }
        }
    }

    private fun hasDirectory(description: Element): Boolean =
        childElements(description, XmpPacket.CONTAINER_NS, "Directory").isNotEmpty()

    /**
     * 摘掉 [parent] 名下**直接**的 `Container:Directory`。对全树每个元素各调一次，等价于"任何深度、
     * 任何归属"的 Directory 都被摘光（语料里它是 Description 的直接子元素）。项住在 Directory 里面，
     * 所以跟着一起消失，不需要单独认语义。
     */
    private fun dropDirectories(parent: Element) {
        childElements(parent, XmpPacket.CONTAINER_NS, "Directory").forEach { parent.removeChild(it) }
    }

    /** §3.3 的产物形态：`<Container:Directory><rdf:Seq/></Container:Directory>`，一个项都不声明。 */
    private fun emptyDirectory(doc: Document): Element =
        newElement(doc, XmpPacket.CONTAINER_NS, "Container", "Directory")
            .declare("Container", XmpPacket.CONTAINER_NS)
            .also { it.appendChild(newElement(doc, XmpPacket.RDF_NS, "rdf", "Seq")) }

    // ---------- 输出 ----------

    private fun packet(doc: Document): ByteArray {
        val body = StringBuilder()
        Writer.write(doc.documentElement!!, body)
        val text = XmpPacket.PACKET_BEGIN + "\n" + body + "\n" + XmpPacket.PACKET_END
        return XmpPacket.requireFitsInSegment(text.toByteArray(Charsets.UTF_8))
    }

    // ---------- DOM 小工具（一律按命名空间 + 本地名走，不碰 XML 文本） ----------

    private fun local(el: Element): String = el.localName ?: el.tagName.substringAfter(':')

    private fun localNameOf(node: Node): String = node.localName ?: node.nodeName.substringAfter(':', node.nodeName)

    private fun attributesOf(el: Element): List<Node> = el.attributes.list()

    private fun NamedNodeMap.list(): List<Node> = (0 until length).map { item(it)!! }

    private fun childElements(parent: Node, ns: String, localName: String): List<Element> =
        childElementsOf(parent).filter { it.namespaceURI == ns && local(it) == localName }

    private fun childElementsOf(parent: Node): List<Element> =
        (0 until parent.childNodes.length).mapNotNull { parent.childNodes.item(it) as? Element }

    private fun descendants(root: Element): List<Element> {
        val out = ArrayList<Element>()
        fun walk(el: Element) {
            out += el
            childElementsOf(el).forEach(::walk)
        }
        walk(root)
        return out
    }

    private fun isNamespaceDeclaration(node: Node): Boolean =
        node.namespaceURI == XMLNS_NS || node.nodeName == "xmlns" || node.prefix == "xmlns"

    private fun newElement(doc: Document, ns: String, prefix: String, localName: String): Element =
        doc.createElementNS(ns, "$prefix:$localName")

    private fun Element.declare(prefix: String, uri: String): Element = apply {
        setAttributeNS(XMLNS_NS, "xmlns:$prefix", uri)
    }

    /**
     * 确定性序列化：见类头 KDoc 最后一段。缩进 = 层数（与 §7.3 模板每层一个空格一致）。
     * 三种形态：空元素自闭合、纯文本元素内联、纯元素子节点逐行；一旦混排（文本与元素同现）
     * 就整棵子树内联输出——内联模式不往文本旁边添空白，所以下一次解析读到的文本节点一字不差，
     * 这是"混排内容也能字节幂等"的唯一办法。
     */
    private object Writer {
        fun write(root: Element, out: StringBuilder) = emit(root, 0, emptyMap(), out, indented = true)

        private fun emit(
            el: Element,
            depth: Int,
            declared: Map<String, String>,
            out: StringBuilder,
            indented: Boolean,
        ) {
            val attrs = attributesOf(el)
            val seen = declared.toMutableMap()
            attrs.forEach { if (isNamespaceDeclaration(it)) seen[declaredPrefix(it)] = it.nodeValue }

            if (indented) out.append(" ".repeat(depth))
            out.append('<').append(el.tagName)
            attrs.forEach { a ->
                out.append(' ').append(a.nodeName).append("=\"").append(escapeValue(a.nodeValue)).append('"')
            }
            // 元素自身与前缀用到的、祖先没声明的，按字典序补齐；下一次解析时它们已经躺在属性表末尾，
            // 顺序因此不再变化。
            usedPrefixes(el, attrs).filter { it !in seen }.sorted().forEach { p ->
                out.append(" xmlns:").append(p).append("=\"")
                    .append(escapeValue(uriForPrefix(el, attrs, p) ?: ""))
                    .append('"')
            }

            val kids = children(el)
            val texts = kids.filterIsInstance<TextChild>()
            val elements = kids.filterIsInstance<ElementChild>()
            if (kids.isEmpty() || (elements.isEmpty() && texts.joinToString("") { it.value }.isBlank())) {
                out.append("/>")
                if (indented) out.append('\n')
                return
            }
            out.append('>')
            if (elements.isEmpty()) {
                out.append(escapeText(texts.joinToString("") { it.value }))
            } else if (texts.any { it.value.isNotBlank() }) {
                // 混排：文本与元素按文档顺序原样吐出，子树一律内联。
                kids.forEach { child ->
                    when (child) {
                        is TextChild -> out.append(escapeText(child.value))
                        is ElementChild -> emit(child.element, depth, seen, out, indented = false)
                    }
                }
            } else {
                out.append('\n')
                elements.forEach { emit(it.element, depth + 1, seen, out, indented = true) }
                if (indented) out.append(" ".repeat(depth))
            }
            out.append("</").append(el.tagName).append(">")
            if (indented) out.append('\n')
        }

        private sealed interface Child

        private class ElementChild(val element: Element) : Child
        private class TextChild(val value: String) : Child

        private fun children(el: Element): List<Child> {
            val out = ArrayList<Child>()
            for (i in 0 until el.childNodes.length) {
                val n = el.childNodes.item(i) ?: continue
                when {
                    n is Element -> out += ElementChild(n)
                    // CDATA 段按普通文本输出：二次解析后它就是文本，字节因此仍然稳定。
                    n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE ->
                        out += TextChild(n.nodeValue)
                    // 注释与处理指令不参与输出：XMP 的语义只在元素与属性里。
                    else -> Unit
                }
            }
            return out
        }

        private fun usedPrefixes(el: Element, attrs: List<Node>): List<String> {
            val out = LinkedHashSet<String>()
            el.prefix?.takeIf { it.isNotEmpty() }?.let { out += it }
            attrs.forEach { a ->
                if (!isNamespaceDeclaration(a)) a.prefix?.takeIf { it.isNotEmpty() }?.let { out += it }
            }
            return out.toList()
        }

        private fun uriForPrefix(el: Element, attrs: List<Node>, prefix: String): String? {
            // 前缀可能属于元素本身，也可能属于某个属性：两种都要认。
            if (el.namespaceURI != null && el.prefix == prefix) return el.namespaceURI
            return attrs.firstOrNull { !isNamespaceDeclaration(it) && it.prefix == prefix && it.namespaceURI != null }
                ?.namespaceURI
        }

        private fun declaredPrefix(node: Node): String =
            if (node.nodeName == "xmlns") "" else node.nodeName.substringAfter(':')

        private fun escapeText(text: String): String = buildString(text.length) {
            text.forEach { c ->
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    else -> append(c)
                }
            }
        }

        private fun escapeValue(text: String): String = buildString(text.length) {
            text.forEach { c ->
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '"' -> append("&quot;")
                    '\t' -> append("&#9;")
                    '\n' -> append("&#10;")
                    '\r' -> append("&#13;")
                    else -> append(c)
                }
            }
        }
    }
}
