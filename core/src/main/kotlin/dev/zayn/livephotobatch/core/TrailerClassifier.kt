package dev.zayn.livephotobatch.core

/**
 * 源 JPEG 在 `imageEnd` 之后那段字节的身份。见 spec「增补（2026-10-18）」§1。
 *
 * - [VIDEO]：真有一段追加的视频（或源 XMP 已经声明过视频）——这才是"上一轮已经合成过"，
 *   调用方据此返回 `ALREADY_MOTION_PHOTO`。
 * - [OTHER]：gain map 副图、厂商 JSON 记录、零填充……**一律放行并原样保留**。
 *   旧规则"EOI 后有任何字节就跳过"在 V2419A 上会把用户 100% 的真实输入判成已合成过，
 *   因为那台机器上每张 JPEG 都有尾巴。
 * - [NONE]：文件正好停在 EOI，没有尾巴要搬。
 */
enum class TrailerKind { NONE, VIDEO, OTHER }

/**
 * 只靠**最多 16 字节** + 源 XMP 文本来定身份，绝不整文件读入：尾巴可能是一整个几 MB 的
 * 视频，而分类需要信息量只有盒子头那 8 字节。
 *
 * `head` 必须是**从 imageEnd 起、长度为 `minOf(16, fileSize - imageEnd)`** 的那段字节：
 * 短于 16 就代表整条尾巴都已经在手里（长度自洽检查才有上界可验），装满 16 则说明尾巴比窗口长，
 * 此时只能验下界，判据落在 tag 集合与 `ftyp` 兜底规则上。传错长度会让分类结果失去意义，
 * 所以 [MotionPhotoComposer] 用 `image.size() - imageEnd` 现算，不猜。
 */
object TrailerClassifier {
    /** 探测窗口：与 spec §7.4.4 的视频头探测同一个宽度。 */
    const val PROBE_BYTES = 16

    /** 出现即认定"这是一段视频"的盒子。`ftyp`/`moov`/`mdat` 是 ISO-BMFF 的三种必备盒。 */
    private val VIDEO_TAGS = setOf("ftyp", "moov", "mdat")

    /** 只有填充作用的盒子：跨过它继续看下一个，本身不说明尾巴是视频。 */
    private val PADDING_TAGS = setOf("free", "skip", "wide", "pnot")

    /**
     * spec 增补 §1 第二条的 XMP 信号：`Container` 里存在 `Item:Mime` 以 `video/` 打头的项。
     * 与 [MotionDetector] 同样要求命名空间前缀——裸的 `video/mp4` 出现在说明文字里不算。
     */
    private val VIDEO_MIME = Regex("""<Item:Mime[^>]*>\s*video/""")

    private val FTYP = byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())

    fun classify(head: ByteArray, xmpText: String?): TrailerKind {
        // XMP 的信号排在最前：上一轮的产物可能因为写入被打断而没有尾巴，但偏移量已经写进元数据了。
        if (xmpDeclaresVideo(xmpText)) return TrailerKind.VIDEO
        if (head.isEmpty()) return TrailerKind.NONE
        if (isIsoBmff(head)) return TrailerKind.VIDEO
        return TrailerKind.OTHER
    }

    private fun xmpDeclaresVideo(xmpText: String?): Boolean =
        MotionDetector.looksLikeMotionPhoto(xmpText) ||
            (xmpText != null && VIDEO_MIME.containsMatchIn(xmpText))

    /**
     * 按 ISO-BMFF 一路走盒子：前 4 字节是大端总长度 `N`，第 5-8 字节是 tag。
     * `N` 的下界按 spec 取 4（盒子头自身 8 字节，但 `N==4` 表示后面跟 64 位扩展长度，
     * 那种尾巴不可能是我们要认的形态，交给 tag 检查把它挡掉）。
     */
    private fun isIsoBmff(head: ByteArray): Boolean {
        if (hasFtypNearHead(head)) return true
        val wholeTrailerInView = head.size < PROBE_BYTES
        var off = 0
        while (off + 8 <= head.size) {
            val n = bigEndian32(head, off)
            if (n < 4L) return false
            val visible = (head.size - off).toLong()
            val tag = tag(head, off)
            if (tag in VIDEO_TAGS) {
                // 窗口没装满时 N 超过可见长度就是不自洽：那只是个恰好长得像盒子的厂商数据。
                return !wholeTrailerInView || n <= visible
            }
            if (tag !in PADDING_TAGS) return false
            // 填充盒盖住了窗口：后面的盒子看不见，只能按"不是视频"处理（真机追加的 mp4
            // 一律以 ftyp 打头，永远走不到这一支的拒绝分支）。
            if (n > visible) return false
            off += n.toInt()
        }
        return false
    }

    /** spec 增补 §1：`imageEnd` 起前 12 字节内出现 `ftyp` 也算视频。 */
    private fun hasFtypNearHead(head: ByteArray): Boolean {
        val limit = minOf(head.size, FTYP_WINDOW) - FTYP.size
        for (i in 0..limit) {
            var hit = true
            for (j in FTYP.indices) if (head[i + j] != FTYP[j]) { hit = false; break }
            if (hit) return true
        }
        return false
    }

    private fun bigEndian32(head: ByteArray, off: Int): Long =
        ((head[off].toLong() and 0xFF) shl 24) or
            ((head[off + 1].toLong() and 0xFF) shl 16) or
            ((head[off + 2].toLong() and 0xFF) shl 8) or
            (head[off + 3].toLong() and 0xFF)

    /** box tag 是 4 字节 ASCII；用 ISO-8859-1 解码，任何字节都映射成一个字符，绝不抛。 */
    private fun tag(head: ByteArray, off: Int): String =
        String(head, off + 4, 4, Charsets.ISO_8859_1)

    /** 盒子长度字段占 4 字节，所以 tag 最晚从第 8 字节开始；spec 说的"前 12 字节"就是这里。 */
    private const val FTYP_WINDOW = 12
}
