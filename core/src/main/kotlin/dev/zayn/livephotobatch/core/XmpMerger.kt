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
 * spec「增补 §3」：XMP 从**整体替换**改成**以源包为基底合并**。
 *
 * 为什么非合并不可：真机抽样里 8 张有 6 张是 HDR gain map，副图字节就躺在主图 EOI 之后，而
 * `Container:Directory` 里的 `Item:Semantic="GainMap"` + `Item:Length` 是它唯一的声明。整体替换会
 * 留下一段"字节还在、元数据说它不存在"的副图——HDR 查看器读出来的亮度就是错的。
 *
 * 规则逐条对应 spec：
 * - 源包为空（或整段只是空白）→ 直接交 §7.3 的模板字节，与旧实现完全一致；
 * - 保留无关命名空间与属性（`hdrgm:`、`dc:`、根元素上的 `x:xmptk`…），保留既有 `Container:Directory`
 *   的 Primary/GainMap 项，`Item:Length` 一个都不改（副图字节没挪动，值就不能改）；
 * - 剔掉任何旧的 `GCamera:*` 属性与 `Item:Semantic` 为 `MotionPhoto` 的项，再按 §7.3 写回我们自己的
 *   全部 `GCamera:*` 与 MotionPhoto 项；因此合并**幂等**：拿自己的产物再合一次，字节相同；
 * - 项顺序固定 Primary → GainMap → MotionPhoto（视频在最后），spec 增补 §2；
 * - 解析失败 → [MalformedXmpException]，由 composer 折成 `MALFORMED_XMP`（spec 增补 §3 最后一条）。
 *
 * ## 为什么解析与序列化都自己组织
 * 解析、增删全部走 `javax.xml` DOM（不对 XML 文本动一次正则）。输出没有用 `Transformer`：它的缩进
 * 与命名空间声明的落点不由我们决定，而"摘掉一个节点再插回来"之后残留的空白文本节点归谁，会让二次
 * 合并无法回到同一串字节（实测 identity `Transformer` 自身 parse→serialize 是稳定的，不稳定的是
 * **结构增删之后**的空白归属）。这里写的输出把空白变成"树深度的纯函数"：纯空白文本节点、注释与
 * 处理指令一律丢弃，缩进按层级补空格，属性按 DOM 顺序输出，元素用到而祖先未声明的前缀按字典序补在
 * 属性表末尾。于是"字节幂等"只取决于树是不是不动点——而上面那对剔除/追加规则本身就互逆。
 *
 * 合并产物的换行写法与 §7.3 模板不同（模板把 `GCamera:*` 属性分三行续写）：XMP 的空白不参与语义，
 * 字段集合由 `XmpMergerTest` 逐条对着模板数；没有源包时仍然交模板字节。
 */
object XmpMerger {
    /** `xmlns:prefix="uri"` 这类声明在 DOM 里就住在这个命名空间下。 */
    private const val XMLNS_NS = "http://www.w3.org/2000/xmlns/"

    private const val MOTION_PHOTO = XmpPacket.ITEM_SEMANTIC_MOTION_PHOTO
    private const val PRIMARY = XmpPacket.ITEM_SEMANTIC_PRIMARY

    fun merge(sourcePacket: String?, videoSize: Long): ByteArray {
        val source = sourcePacket?.takeIf { it.isNotBlank() } ?: return XmpPacket.motionPhoto(videoSize)
        val doc = parse(source)
        val root = doc.documentElement
            ?: throw MalformedXmpException("XMP 包没有根元素，无法合并")
        rejectDeepNesting(root)
        if (local(root) != "xmpmeta") {
            throw MalformedXmpException("XMP 包的根元素不是 x:xmpmeta，而是 <${root.tagName}>")
        }
        val rdf = childElements(root, XmpPacket.RDF_NS, "RDF").firstOrNull()
            ?: newElement(doc, XmpPacket.RDF_NS, "rdf", "RDF").also { root.appendChild(it) }

        // §3 的剔除：GCamera 这一段整个由我们重写，MotionPhoto 项也一样。
        stripCameraAttributes(root)
        val harvested = harvestContainerItems(doc)
        installDirectory(doc, rdf, harvested, videoSize)
        installCameraDescriptions(doc, rdf, videoSize)
        dropEmptyDescriptions(doc)
        return packet(doc)
    }

    // ---------- 解析（不可信输入） ----------

    private fun parse(text: String): Document = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // 逐个 try：JVM 的 Xerces 全都支持；厂商 ROM 万一对某个开关说"不认识"，
            // 也不能让整条安全策略崩掉——下面还有 EntityResolver 与 doctype 复查两道兜底。
            feature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            feature("http://apache.org/xml/features/disallow-doctype-decl", true)
            feature("http://xml.org/sax/features/external-general-entities", false)
            feature("http://xml.org/sax/features/external-parameter-entities", false)
            feature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val builder = factory.newDocumentBuilder()
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

    // ---------- 剔除 ----------

    private fun stripCameraAttributes(root: Element) {
        for (el in descendants(root)) {
            val names = attributesOf(el).filter { it.namespaceURI == XmpPacket.CAMERA_NS }
            names.forEach { el.removeAttributeNS(XmpPacket.CAMERA_NS, localNameOf(it)) }
        }
    }

    /**
     * 摘走所有 `Container:Directory` 里的容器项（**搬动节点本身**，命名空间声明与属性跟着走），
     * 顺手删掉每一处旧的 `MotionPhoto` 项，并把第一个 Directory 的宿主 `rdf:Description` 留着复用
     * ——复用而不是重建，是"再合一次字节相同"的关键：宿主在 `rdf:RDF` 里的位置与它自有的属性
     * （`rdf:about` 一类）都原样不动。多余的宿主（源里写了两个 Directory 的情况）在只剩空壳后删掉。
     */
    private class Harvested(val items: List<Element>, val host: Element?)

    private fun harvestContainerItems(doc: Document): Harvested {
        val dirs = descendants(doc.documentElement!!)
            .filter { local(it) == "Directory" && it.namespaceURI == XmpPacket.CONTAINER_NS }
        val seen = HashSet<String>()
        val items = ArrayList<Element>()
        for (dir in dirs) {
            for (seq in childElementsOf(dir).filter { local(it) == "Seq" && it.namespaceURI == XmpPacket.RDF_NS }) {
                for (li in childElementsOf(seq).filter { local(it) == "li" && it.namespaceURI == XmpPacket.RDF_NS }) {
                    val semantic = textOfChild(li, XmpPacket.ITEM_NS, "Semantic")
                    // MotionPhoto 项整条扔掉（下面重新生成）；同一个 semantic 只留第一项，
                    // 否则重复声明会一路复制到产物里。
                    if (semantic == MOTION_PHOTO || (semantic != null && !seen.add(semantic))) {
                        li.parentNode?.removeChild(li)
                    } else {
                        items += li
                    }
                }
            }
        }
        val host = dirs.firstOrNull()?.parentNode as? Element
        val extraHosts = dirs.mapNotNull { it.parentNode as? Element }.distinct().filterNot { it === host }
        items.forEach { it.parentNode?.removeChild(it) }
        dirs.forEach { it.parentNode?.removeChild(it) }
        extraHosts.filter { childElementsOf(it).isEmpty() }.forEach { it.parentNode?.removeChild(it) }
        return Harvested(items, host.takeIf { it?.parentNode != null })
    }

    // ---------- 写回 ----------

    /**
     * 项顺序 Primary →（GainMap 及其余既有项）→ MotionPhoto：spec 增补 §2 定死的排列，
     * 读取方按 `rdf:Seq` 的顺序与 `Item:Length` 定位副图和视频。
     */
    private fun installDirectory(doc: Document, rdf: Element, harvested: Harvested, videoSize: Long) {
        val kept = harvested.items
        val ordered = ArrayList<Element>()
        val primary = kept.firstOrNull { textOfChild(it, XmpPacket.ITEM_NS, "Semantic") == PRIMARY }
        if (primary != null) ordered += primary
        ordered += kept.filterNot { it === primary }
        if (primary == null) ordered.add(0, newItem(doc, mime = "image/jpeg", semantic = PRIMARY, length = null))
        ordered.add(newItem(doc, mime = "video/mp4", semantic = MOTION_PHOTO, length = videoSize))

        val seq = newElement(doc, XmpPacket.RDF_NS, "rdf", "Seq")
        ordered.forEach { seq.appendChild(it) }
        val directory = newElement(doc, XmpPacket.CONTAINER_NS, "Container", "Directory")
        directory.appendChild(seq)

        val host = harvested.host
        if (host != null && local(host) == "Description" && host.namespaceURI == XmpPacket.RDF_NS) {
            host.declare("Container", XmpPacket.CONTAINER_NS)
            host.appendChild(directory)
        } else {
            val description = newElement(doc, XmpPacket.RDF_NS, "rdf", "Description")
            description.declare("Container", XmpPacket.CONTAINER_NS)
            description.appendChild(directory)
            rdf.appendChild(description)
        }
    }

    private fun installCameraDescriptions(doc: Document, rdf: Element, videoSize: Long) {
        val size = videoSize.toString()
        fun description(vararg pairs: Pair<String, String>): Element =
            newElement(doc, XmpPacket.RDF_NS, "rdf", "Description")
                .declare("GCamera", XmpPacket.CAMERA_NS)
                .also { el -> pairs.forEach { (name, value) -> el.setAttributeNS(XmpPacket.CAMERA_NS, name, value) } }

        val micro = description(
            "GCamera:MicroVideo" to "1",
            "GCamera:MicroVideoVersion" to "1",
            "GCamera:MicroVideoOffset" to size,
            "GCamera:MicroVideoPresentationTimestampUs" to "-1",
        )
        val motion = description(
            "GCamera:MotionPhoto" to "1",
            "GCamera:MotionPhotoVersion" to "1",
            "GCamera:MotionPhotoPresentationTimestampUs" to "-1",
        )
        // 模板的先后顺序是 MotionPhoto 组、MicroVideo 组；两次 insertBefore 正好排成这一对。
        rdf.insertBefore(micro, rdf.firstChild)
        rdf.insertBefore(motion, rdf.firstChild)
    }

    /**
     * `Item:Length` 只在合成项上写我们自己的视频长度；源里的每一项都保持原值（spec 增补 §2：
     * gain map 的字节没动，所以它的 `Item:Length` 也不动）。
     */
    private fun newItem(doc: Document, mime: String, semantic: String, length: Long?): Element {
        val li = newElement(doc, XmpPacket.RDF_NS, "rdf", "li")
            .declare("Item", XmpPacket.ITEM_NS)
            .also { it.setAttributeNS(XmpPacket.RDF_NS, "rdf:parseType", "Resource") }
        textElement(doc, XmpPacket.ITEM_NS, "Item", "Mime", mime).also { li.appendChild(it) }
        textElement(doc, XmpPacket.ITEM_NS, "Item", "Semantic", semantic).also { li.appendChild(it) }
        if (length != null) {
            textElement(doc, XmpPacket.ITEM_NS, "Item", "Length", length.toString()).also { li.appendChild(it) }
        }
        return li
    }

    /**
     * 剔除之后剩下的空壳：属性只剩命名空间声明、子元素与文本一个都不剩的 `rdf:Description`。
     * 上一轮我们写进去的那两组 `GCamera:*` 被 [stripCameraAttributes] 掏空之后就长这样。
     * 文本内容也算信息——`<rdf:Description>一句说明</rdf:Description>` 不许被当空壳删掉。
     */
    private fun dropEmptyDescriptions(doc: Document) {
        for (el in descendants(doc.documentElement!!).filter {
                local(it) == "Description" && it.namespaceURI == XmpPacket.RDF_NS
            }
        ) {
            val carriesInformation = attributesOf(el).any { !isNamespaceDeclaration(it) } ||
                childElementsOf(el).isNotEmpty() ||
                directText(el).isNotBlank()
            if (!carriesInformation) el.parentNode?.removeChild(el)
        }
    }

    private fun directText(el: Element): String {
        val out = StringBuilder()
        for (i in 0 until el.childNodes.length) {
            val n = el.childNodes.item(i) ?: continue
            if (n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE) out.append(n.nodeValue)
        }
        return out.toString()
    }

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

    private fun textOfChild(parent: Element, ns: String, localName: String): String? =
        childElements(parent, ns, localName).firstOrNull()?.textContent?.trim()

    private fun isNamespaceDeclaration(node: Node): Boolean =
        node.namespaceURI == XMLNS_NS || node.nodeName == "xmlns" || node.prefix == "xmlns"

    private fun newElement(doc: Document, ns: String, prefix: String, localName: String): Element =
        doc.createElementNS(ns, "$prefix:$localName")

    private fun textElement(
        doc: Document,
        ns: String,
        prefix: String,
        localName: String,
        value: String,
    ): Element = newElement(doc, ns, prefix, localName).also { it.textContent = value }

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
