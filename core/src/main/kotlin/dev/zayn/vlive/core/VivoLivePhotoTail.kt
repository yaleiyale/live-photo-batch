package dev.zayn.vlive.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SeekableByteChannel

/**
 * 把 vivo 相机 mp4 末尾那个 `uuid` + 伪 UUID `vivoMediaExtInfo` 盒里的
 * `com.android.camera.livephoto` 值改成以 `motionphoto` 开头。
 *
 * vivo 相册只认值以 `motionphoto` 开头的这份末盒：盒尺寸、JSON 里其余的键、`version` 的数字、
 * `cameralbum!` 那条尾记录都不影响判定，只有这个前缀影响。相机自己写进去的编号是数字开头的 id，
 * 所以**原样追加必然不被认**。
 *
 * 只改这一个字段，其余字节原样：末盒排在 `moov` 之后，视频里所有 `stco`/`co64` 的 chunk 偏移
 * 都指向它之前，重写它不会让任何偏移失效。但**盒会变长**（多了 11 字节前缀），所以三处自洽数据
 * 必须同步：盒头的 `size`、JSON 之后那个描述 JSON 字节数的 u32BE、以及 XMP 里的
 * `Item:Length`/`GCamera:MicroVideoOffset`（后者由调用方负责，用 [Rewrite.totalBytes]）。
 *
 * 看不懂就不动：没有这个盒、标记落在读窗口之外、盒的 `size` 没正好走到文件末尾、JSON 花括号不
 * 配对、长度字段与实际 JSON 字节数不符、没有 `livephoto` 键、值已经合规——一律返回 null，
 * 调用方原样搬运。我们不改自己读不懂的字节，也不替非 vivo 的视频伪造厂商结构。
 */
object VivoLivePhotoTail {

    /** 视频的前 [verbatimBytes] 字节原样搬运，后面接 [replacement]（重写过的末盒）。 */
    data class Rewrite(val verbatimBytes: Long, val replacement: ByteArray) {
        /** 实际会写进产物的视频字节数——XMP 里两处长度必须用它，不能用 `video.size()`。 */
        val totalBytes: Long get() = verbatimBytes + replacement.size
    }

    /** 读窗口：末盒再大也不会超过这个尺寸，超出就说明我们读不全，宁可不改。 */
    private const val WINDOW = 64 * 1024

    /** 盒内布局：[u32 size]['uuid'][16 B 伪 UUID]['vivo'][JSON][u32BE=JSON 字节数][尾记录] */
    private const val HEADER = 8
    private const val PSEUDO_LEN = 16
    private const val MAGIC_LEN = 4
    private const val BODY_START = HEADER + PSEUDO_LEN            // 'vivo' 魔数
    private const val JSON_START = BODY_START + MAGIC_LEN
    private const val MIN_BOX = JSON_START + 2 + 2 + 4            // 至少容得下 {} 与长度字段

    private const val PREFIX = "motionphoto"
    private val TAG_UUID = ascii("uuid")
    private val PSEUDO = ascii("vivoMediaExtInfo")
    private val MAGIC = ascii("vivo")
    private val KEY = ascii("\"com.android.camera.livephoto\":\"")

    /** I/O 故障不是这里的判断：返回 null，调用方随后的搬运阶段会照旧报 IO_ERROR。 */
    fun plan(video: ChannelSource): Rewrite? = try {
        inspect(video)
    } catch (e: IOException) {
        null
    }

    private fun inspect(video: ChannelSource): Rewrite? {
        val size = video.size()
        if (size < MIN_BOX) return null
        val windowLen = minOf(size, WINDOW.toLong()).toInt()
        val windowStart = size - windowLen
        val window = readAt(video, windowStart, windowLen)
        val pseudoAt = lastIndexOf(window, PSEUDO)
        if (pseudoAt < HEADER) return null                       // 前面放不下盒头
        val at = pseudoAt - HEADER                               // 盒头在窗口内的下标
        val boxSize = be32(window, at)
        if (boxSize < MIN_BOX || boxSize != (windowLen - at).toLong()) return null
        if (compare(window, at + 4, TAG_UUID) != 0) return null
        val box = window.copyOfRange(at, windowLen)
        if (compare(box, BODY_START, MAGIC) != 0) return null
        val jsonEnd = jsonEndOf(box) ?: return null
        // JSON 之后还必须有 4 个字节放长度字段：读越界会抛 IndexOutOfBounds，
        // 而 plan 只兜 IOException，那种异常会炸穿整批任务而不是被降级成"不改"。
        if (jsonEnd + 4 > box.size) return null
        if (jsonEnd - JSON_START < 2 || be32(box, jsonEnd) != (jsonEnd - JSON_START).toLong()) return null
        val keyAt = indexOf(box, KEY, JSON_START, jsonEnd)
        if (keyAt < 0) return null
        val valueStart = keyAt + KEY.size
        val valueEnd = indexOfByte(box, '"'.code.toByte(), valueStart, jsonEnd)
        if (valueEnd < 0) return null
        val value = box.copyOfRange(valueStart, valueEnd).toString(Charsets.ISO_8859_1)
        if (value.isEmpty() || value.startsWith(PREFIX)) return null

        val newJson = box.copyOfRange(JSON_START, valueStart) + ascii(PREFIX + value) +
            box.copyOfRange(valueEnd, jsonEnd)
        // body 从 [HEADER] 起抄：box 的头 8 字节是"总长 + tag"，它属于下面新写的盒头，
        // 从 0 起抄会把旧盒头当成 body 的一部分，产物里凭空多一层盒头（size 字段也跟着错 8 字节）。
        val body = box.copyOfRange(HEADER, JSON_START) + newJson + be32Bytes(newJson.size.toLong()) +
            box.copyOfRange(jsonEnd + 4, box.size)
        return Rewrite(windowStart + at, be32Bytes((HEADER + body.size).toLong()) + TAG_UUID + body)
    }

    /** 从 [JSON_START] 起数到配对的那个 `}`，返回 JSON 末之后的第一个字节下标。 */
    private fun jsonEndOf(box: ByteArray): Int? {
        var depth = 0
        for (i in JSON_START until box.size) {
            when (box[i]) {
                '{'.code.toByte() -> depth++
                '}'.code.toByte() -> if (--depth == 0) return i + 1
                0.toByte() -> if (depth == 0) return null
            }
        }
        return null
    }

    private fun readAt(src: ChannelSource, from: Long, n: Int): ByteArray {
        val out = ByteArray(n)
        src.open().use { ch ->
            if (ch is SeekableByteChannel) ch.position(from) else skip(ch, from)
            val dst = ByteBuffer.wrap(out)
            while (dst.hasRemaining()) {
                if (ch.read(dst) <= 0) throw IOException("读视频尾部时提前结束")
            }
        }
        return out
    }

    private fun skip(ch: ReadableByteChannel, n: Long) {
        var left = n
        val scratch = ByteBuffer.allocate(8 * 1024)
        while (left > 0) {
            scratch.clear()
            scratch.limit(minOf(left, scratch.capacity().toLong()).toInt())
            val read = ch.read(scratch)
            if (read <= 0) throw IOException("跳过 $n 字节时提前结束")
            left -= read.toLong()
        }
    }

    private fun be32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun be32Bytes(value: Long): ByteArray = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
    )

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    /** 与 [ByteArray.compareTo] 同义，但不要求两侧等长——只需比较 [other.size] 个字节。 */
    private fun compare(b: ByteArray, at: Int, other: ByteArray): Int {
        if (at < 0 || b.size - at < other.size) return -1
        for (i in other.indices) {
            val d = (b[at + i].toInt() and 0xFF) - (other[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return 0
    }

    private fun indexOf(b: ByteArray, pattern: ByteArray, from: Int, to: Int): Int {
        var i = from
        while (i + pattern.size <= to) {
            if (compare(b, i, pattern) == 0) return i
            i++
        }
        return -1
    }

    private fun lastIndexOf(b: ByteArray, pattern: ByteArray): Int {
        var i = b.size - pattern.size
        while (i >= 0) {
            if (compare(b, i, pattern) == 0) return i
            i--
        }
        return -1
    }

    private fun indexOfByte(b: ByteArray, value: Byte, from: Int, to: Int): Int {
        for (i in from until to) if (b[i] == value) return i
        return -1
    }
}
