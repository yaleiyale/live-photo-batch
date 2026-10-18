package dev.zayn.livephotobatch.core

object Fixtures {
    const val XAP = "http://ns.adobe.com/xap/1.0/"
    const val XMP_EXTENSION = "http://ns.adobe.com/xmp/extension/"

    fun uint16(value: Int): ByteArray =
        byteArrayOf((value shr 8).toByte(), value.toByte())

    /** marker + 2 字节长度字段（含长度字段自身）+ payload */
    fun segment(marker: Int, payload: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), marker.toByte()) + uint16(payload.size + 2) + payload

    fun app1(identifier: String, payload: ByteArray): ByteArray =
        segment(0xE1, identifier.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + payload)

    fun sof0(): ByteArray =
        segment(0xC0, byteArrayOf(0x08, 0x00, 0x11, 0x01, 0x01, 0x00, 0x01, 0x11, 0x00))

    fun sos(): ByteArray =
        segment(0xDA, byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x3F, 0x00))

    /** SOI + 头部段 + SOS + 熵数据 + EOI (+ 尾部追加字节) */
    fun jpeg(
        headSegments: List<ByteArray> = listOf(sof0()),
        entropy: ByteArray = byteArrayOf(0x11, 0x22, 0x33, 0x44),
        tail: ByteArray = ByteArray(0),
    ): ByteArray {
        val body = headSegments.fold(byteArrayOf()) { acc, seg -> acc + seg }
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + body + sos() + entropy +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte()) + tail
    }

    /**
     * 标准 ftyp 头 + filler，总长 size。
     *
     * filler 里从 box 头之后第一个字节起每 16 字节埋一对真 `FF D9`：spec §7.2 说得很明白，
     * 追加到 JPEG 尾部的视频字节里合法地会出现 FFD9（NAL 数据、时长字段都可能凑出来）。
     * 紧跟 EOI 的视频里要有它，"按字节对搜 FFD9"或"读完整段再找最后一个 FFD9"的偷懒实现
     * 才会被测试抓住。box 头 12 字节保持原样，Task 5 的 ftyp 探测仍看得懂这个盒子。
     */
    fun mp4(size: Long = 4096): ByteArray {
        require(size >= 12)
        val out = ByteArray(size.toInt())
        // 前 4 字节 = 32 位大端 box size
        val boxSize = size.toInt()
        out[0] = (boxSize ushr 24).toByte()
        out[1] = (boxSize ushr 16).toByte()
        out[2] = (boxSize ushr 8).toByte()
        out[3] = boxSize.toByte()
        "ftyp".toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        "isom".toByteArray(Charsets.ISO_8859_1).copyInto(out, 8)
        var i = 12
        while (i + 1 < out.size) {
            out[i] = 0xFF.toByte()
            out[i + 1] = 0xD9.toByte()
            i += 16
        }
        return out
    }

    val xmpPacket: ByteArray =
        "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">OLD</x:xmpmeta>".toByteArray(Charsets.UTF_8)

    /** ISO-8859-1 才允许出现在 APP1 标识符里；这里统一用它，避免依赖源码文件编码。 */
    fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    /** 32 位大端整数（ISO-BMFF 的 box 长度字段就是这个字节序）。 */
    fun uint32(value: Long): ByteArray = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte()
    )

    /**
     * 长度自洽的 ISO-BMFF box：4 字节大端总长度 + 4 字节 tag + payload（不足 [size] 补零）。
     * Task 13 的尾巴分类只看得懂这种"前 4 字节说是多长、尾巴里就真有这么长"的盒子。
     */
    fun box(tag: String, size: Int = 8, payload: ByteArray = ByteArray(0)): ByteArray {
        require(tag.length == 4) { "box tag 必须正好 4 字节: $tag" }
        require(size >= 8 + payload.size) { "box size $size 装不下 ${payload.size} 字节 payload" }
        return uint32(size.toLong()) + ascii(tag) + payload + ByteArray(size - 8 - payload.size)
    }

    /** 真 ftyp 盒（major/compatible brand 齐全），尾巴分类器的 VIDEO 正例就用它。 */
    fun ftypBox(size: Int = 24): ByteArray =
        box("ftyp", size, ascii("isom") + uint32(512) + ascii("isom") + ascii("mp41"))

    /** 尾巴只有 [n] 个零字节的那种（抽样里 vivo 也产出过 47 字节全零）。 */
    fun zeroFill(n: Int): ByteArray = ByteArray(n)

    /**
     * 真机抽样到的厂商记录：`vivo{...json...}` + `cameralbum!<UUID>`，实测 399 字节。
     * 尾巴开头是 ASCII `vivo`——前 4 字节按大端读是 1986653807，远大于尾巴长度，
     * 所以它**不是**任何合法的 ISO-BMFF 盒子；这正是"长度自洽"检查存在的理由。
     */
    fun vendorTrailer(): ByteArray {
        val record = ascii("cameralbum!3f2b7c1d-00aa-4b1e-9c77-2f19a7bd0001")
        val head = ascii(
            "vivo{\"module\":\"v2419a\",\"width\":4032,\"height\":3024,\"rotation\":0," +
                "\"timestamp\":1729234567,\"face\":[],\"pad\":\""
        )
        val tail = ascii("\"}")
        val pad = TARGET - head.size - record.size - tail.size
        require(pad >= 0) { "厂商尾巴夹具装不进 $TARGET 字节: pad=$pad" }
        val out = head + ascii("p".repeat(pad)) + tail + record
        require(out.size == TARGET) { "厂商尾巴夹具应当正好 $TARGET 字节，实测 ${out.size}" }
        return out
    }

    /**
     * vivo 已识别语料里 191555 与 093558 两片**共用**的那一型源包：正文取自
     * `IMG_20261015_191555.jpg` 的 APP1 XMP 段（标识符之后 924 字节；结尾那个换行由 `trim()`
     * 去掉，夹具因此是 923 字节）。三个要点都是 Task 13 的实现被真机推翻的地方：
     * - 没有 `<?xpacket?>` 头，根元素直接以 `<x:xmpmeta>` 起；
     * - `rdf:RDF` 用的是**标准** RDF 命名空间 `http://www.w3.org/1999/02/22-rdf-syntax-ns#`
     *   （[XmpPacket.RDF_NS] 必须与它一致，否则我们在源包之外另起一个 RDF，相册读不到声明）；
     * - 容器项写成 `<Container:Item Item:Semantic="…" Item:Mime="…"/>`：语义与 MIME 是**子元素的属性**，
     *   不是 `Item:Semantic` 子元素的文本。
     */
    fun vivoSourcePacket(): String = """
<x:xmpmeta
  xmlns:x="adobe:ns:meta/"
  x:xmptk="Adobe XMP Core 5.1.2">
  <rdf:RDF
    xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description
      xmlns:Container="http://ns.google.com/photos/1.0/container/"
      xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
      xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/"
      hdrgm:Version="1.0">
      <Container:Directory>
        <rdf:Seq>
          <rdf:li
            rdf:parseType="Resource">
            <Container:Item
              Item:Semantic="Primary"
              Item:Mime="image/jpeg"/>
          </rdf:li>
          <rdf:li
            rdf:parseType="Resource">
            <Container:Item
              Item:Semantic="GainMap"
              Item:Mime="image/jpeg"
              Item:Length="441963"/>
          </rdf:li>
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>
""".trim()

    /**
     * vivo HDR gain map 的源 XMP：`hdrgm:Version` + `Container:Directory` 里 Primary 与 GainMap 两项。
     * 形状取自真机抽样（GainMap 项带 `Item:Mime=image/jpeg` 与 `Item:Length`，
     * 那个 Length 就是副图的实际字节数，见 [gainMapJpeg]）。
     * 这一型把语义写成**子元素文本**（`<Item:Semantic>GainMap</Item:Semantic>`），与
     * [vivoSourcePacket] 的属性式写法是两种并存的形状。
     */
    fun gainMapXmp(gainMapLength: Long): String = buildString {
        append(XmpPacket.PACKET_BEGIN).append('\n')
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"vivo XMP Toolkit 1.0\">\n")
        append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        append("  <rdf:Description rdf:about=\"\"\n")
        append("      xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n")
        append("      xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"\n")
        append("      GCamera:BoostCorrection=\"1.3600\"\n")
        append("      hdrgm:Version=\"1.0\"\n")
        append("      hdrgm:GainMapHeadroom=\"2.0000\"/>\n")
        append("  <rdf:Description rdf:about=\"\" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\">\n")
        append("   <Container:Directory>\n")
        append("    <rdf:Seq>\n")
        append("     <rdf:li xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" rdf:parseType=\"Resource\">\n")
        append("      <Item:Mime>image/jpeg</Item:Mime>\n")
        append("      <Item:Semantic>Primary</Item:Semantic>\n")
        append("     </rdf:li>\n")
        append("     <rdf:li xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" rdf:parseType=\"Resource\">\n")
        append("      <Item:Mime>image/jpeg</Item:Mime>\n")
        append("      <Item:Semantic>GainMap</Item:Semantic>\n")
        append("      <Item:Length>").append(gainMapLength).append("</Item:Length>\n")
        append("     </rdf:li>\n")
        append("    </rdf:Seq>\n")
        append("   </Container:Directory>\n")
        append("  </rdf:Description>\n")
        append(" </rdf:RDF>\n")
        append("</x:xmpmeta>\n")
        append(XmpPacket.PACKET_END)
    }

    /**
     * 真 HDR gain map 副图：完整 JPEG（SOI + APP2/`hdrg` 盒 + SOF0 + SOS + 熵数据 + EOI）。
     * APP2 标识符与盒子名按 ISO 21496-1（Android 14+ 的 gain map 就是这一段），
     * 熵数据里刻意不出现 0xFF——它是主图 EOI **之后**的字节，主图扫描器根本走不到那儿，
     * 但保持"合法 JPEG"这个性质，产物的副图段才能被 HDR 查看器解码。
     */
    fun gainMapImage(entropySize: Int = 4096): ByteArray {
        // hdrg 盒：长度自洽的 4+4 字节头 + version(4) + min/max/headroom(3×4) + 标志
        val hdrg = box("hdrg", 28, byteArrayOf(0, 0, 0, 1) + uint32(1024) + uint32(2048) + uint32(1))
        val app2 = segment(0xE2, ascii("urn:iso:std:iso:ts:21496:-1\u0000") + hdrg)
        return jpeg(
            headSegments = listOf(app2, sof0()),
            entropy = ByteArray(entropySize) { (it % 251).toByte() },
        )
    }

    /**
     * 真机最常见的输入：XMP 声明 GainMap 的主图 + EOI 之后原样躺着的副图 JPEG。
     * 返回 `主图（含尾巴）`，尾巴长度等于 [gainMapXmp] 里声明的 `Item:Length`。
     */
    fun gainMapJpeg(entropySize: Int = 4096): ByteArray {
        val gainMap = gainMapImage(entropySize)
        val xmp = gainMapXmp(gainMap.size.toLong()).toByteArray(Charsets.UTF_8)
        return jpeg(headSegments = listOf(app1(XAP, xmp), sof0()), entropy = ByteArray(2048) { (it % 251).toByte() }) +
            gainMap
    }

    /** 尾巴是厂商 JSON 记录的输入（无 XMP，抽样里那 1 张就是这个形状）。 */
    fun vendorTrailerJpeg(): ByteArray = jpeg(tail = vendorTrailer())

    /** 尾巴是 47 个零字节的输入（抽样里那 1 张零填充）。 */
    fun zeroFilledJpeg(): ByteArray = jpeg(tail = zeroFill(47))

    /** 尾巴是追加视频、且 XMP 已经声明 MotionPhoto 的输入——上一轮产物，必须跳过。 */
    fun composedMotionPhotoJpeg(videoSize: Long = 300L): ByteArray {
        val video = mp4(videoSize)
        return jpeg(
            headSegments = listOf(app1(XAP, XmpPacket.motionPhoto(videoSize)), sof0()),
            tail = video,
        )
    }

    private const val TARGET = 399
}
