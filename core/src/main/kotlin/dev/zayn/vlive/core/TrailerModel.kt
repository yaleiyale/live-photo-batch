package dev.zayn.vlive.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SeekableByteChannel

/**
 * `imageEnd` 之后那段字节该被目录声明成什么。
 *
 * Google Motion Photo 1.0 要求媒体项**紧密封装**、项间每一段字节都被目录声明：
 * 尾巴为空就什么都不用声明；是一张 gain map 副图就整条声明成 `GainMap` 项（含副图之后的
 * 厂商记录——规范只给第一项 `Padding`，副图与视频之间没有别的合法字段，诚实内长会留下
 * 没人声明的字节，二选一取合规的那个）；其余一切形状——不是 JPEG、是非副图的第二张 JPEG、
 * 厂商 JSON、零填充、**遍历不收敛的畸形副图**——整条声明成主图的 `Padding`。
 *
 * 降级而不是报错：尾巴声明成 Padding 永远合法，而把一张坏图谎称 GainMap 才是骗目录。
 * 因此本对象的 [describe] 对任何输入都不抛：I/O 故障与畸形一律走 `paddingBytes`。
 *
 * 与 [TrailerClassifier] 是两件事，不许合并：那个只问"这尾巴是不是上一轮追加的视频"
 * （16 字节探针就够），这个问"这段字节该被目录声明成什么"（要把副图自己的 marker 走完）。
 */
object TrailerModel {

    /**
     * 尾巴的定性结果，三个字段里**至多一个**非 null（[gainMapBytes] 与 [paddingBytes] 互斥）。
     *
     * @param gainMapBytes 声明成 GainMap 项的字节数——**整条尾巴**，含副图 JPEG 之后的厂商记录。
     * @param paddingBytes 声明成主图 `Item:Padding` 的字节数——同样是整条尾巴。
     * @param innerJpegBytes 副图 JPEG 自身的诚实长度（SOI 到它自己的 EOI）。只为诊断与测试留档，
     *                       目录里的 `Length` 用的**不是**它。
     */
    data class Description(
        val gainMapBytes: Long?,
        val paddingBytes: Long?,
        val innerJpegBytes: Long?,
    )

    private const val BUFFER_SIZE = 64 * 1024

    /**
     * gain map 的声明串。ISO 21496-1 有**连字符**与**冒号**两种拼法在流通（Android 的 APP2
     * 标识符写的就是 `urn:iso:std:iso:ts:21496:-1version:100`），Adobe 旧版命名空间是第三条；
     * 三种都要认。
     */
    private val GAIN_MAP_DECLARATIONS = listOf(
        "urn:iso:std:iso:ts:21496-1",
        "urn:iso:std:iso:ts:21496:-1",
        "http://ns.adobe.com/hdr-gain-map/1.0/",
    ).map { it.toByteArray(Charsets.ISO_8859_1) }

    /**
     * 从 [imageEnd] 起**流式**遍历尾巴：定长缓冲、物理读取总量以 [fileSize] 为上限，常数内存——
     * 既不会把整图也不会把整条尾巴 `readBytes()` 进内存。来源可定位就一次 seek，
     * 不可定位才退化定长缓冲跳过。
     *
     * 代价要写明：判定要走完副图自己的 marker 才能确认它收敛（半路停下就没法区分"是副图"与
     * "坏图"，而坏图必须降级成 Padding），所以**同一条尾巴会被读两遍**——这里一遍，
     * [JpegXmpPatcher.patch] 搬它时一遍。每项多读的是尾巴大小（相机 JPEG 的尾巴在几十万到上百万
     * 字节），相对主图几 MB 的搬运是小头，但别以为只读了一次。
     */
    fun describe(image: ChannelSource, imageEnd: Long, fileSize: Long): Description {
        val trailerBytes = fileSize - imageEnd
        if (trailerBytes <= 0L) return Description(null, null, null)
        val padding = Description(null, trailerBytes, null)
        return try {
            image.open().use { ch -> walk(ch, imageEnd, trailerBytes) } ?: padding
        } catch (e: IOException) {
            // 读到一半源坏了：声明成 Padding 依旧合法，产物照写。真正的 I/O 故障
            // 由调用方随后的搬运阶段报 IO_ERROR，这里不抢那个分类。
            padding
        }
    }

    /** null = 应当降级成 padding（畸形、不收敛、或根本不是 JPEG）。 */
    private fun walk(ch: ReadableByteChannel, imageEnd: Long, trailerBytes: Long): Description? {
        val cur = BoundedCursor(ch, imageEnd + trailerBytes)
        if (!cur.position(imageEnd)) return null
        val soi = cur.read(2) ?: return null
        if (soi[0] != 0xFF.toByte() || soi[1] != 0xD8.toByte()) return null

        var declared = false
        var pending: Int? = null
        while (true) {
            val marker: Int
            if (pending != null) {
                marker = pending
                pending = null
            } else {
                marker = cur.readMarker() ?: return null
            }
            if (marker == 0xD9) {
                val inner = cur.consumed
                return if (declared) Description(trailerBytes, null, inner) else Description(null, trailerBytes, inner)
            }
            if (marker == 0x01 || marker in 0xD0..0xD7) continue // TEM / RSTn：无长度字段
            val lenBytes = cur.read(2) ?: return null
            val len = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
            if (len < 2) return null
            val payload = cur.read(len - 2) ?: return null
            if (marker in 0xE0..0xEF && !declared) {
                declared = GAIN_MAP_DECLARATIONS.any { payload.containsSlice(it) }
            }
            if (marker == 0xDA) pending = cur.scanEntropy() ?: return null
        }
    }

    /** 可定位就一次 seek 到 `imageEnd`；不可定位（管道）用定长缓冲跳过去，读不满 [imageEnd] 就失败降级。 */
    private fun BoundedCursor.position(imageEnd: Long): Boolean {
        if (imageEnd <= 0L) return true
        if (ch is SeekableByteChannel) {
            ch.position(imageEnd)
            return true
        }
        return skip(imageEnd)
    }

    private fun ByteArray.containsSlice(other: ByteArray): Boolean {
        if (other.size > size) return false
        outer@ for (i in 0..size - other.size) {
            for (j in other.indices) if (this[i + j] != other[j]) continue@outer
            return true
        }
        return false
    }

    /**
     * JPEG 的 marker 遍历规则（FF 填充串、FF00 转义、RSTn 一个都不能少），外加一条这里专属的
     * 硬边界：物理读取总量不超过文件大小——尾巴之后的字节（哪怕真在流里）也不许碰，
     * 段长度越界会在这里变成"读不到"，遍历因此**必然收敛或失败**，绝不越界也绝不抛。
     */
    private class BoundedCursor(val ch: ReadableByteChannel, private val maxPhysical: Long) {
        /** 已消费的**尾巴内**字节数（[skip] 不计入——它是定位，不是尾巴内容）。 */
        var consumed: Long = 0L
            private set
        private var physical = 0L

        private val buf = ByteArray(BUFFER_SIZE)
        private val channelBuf = ByteBuffer.wrap(buf)
        private var filled = 0
        private var next = 0

        /** 跳过 [n] 个字节（不可定位来源的"seek"）；读到 EOF 还没跳够就返回 false。 */
        fun skip(n: Long): Boolean {
            var left = n
            while (left > 0) {
                if (next >= filled && !refill()) return false
                val take = minOf(left, (filled - next).toLong()).toInt()
                next += take
                left -= take.toLong()
            }
            return true
        }

        fun read(n: Int): ByteArray? {
            val out = ByteArray(n)
            if (n == 0) return out
            var got = 0
            while (got < n) {
                if (next >= filled && !refill()) return null
                val take = minOf(n - got, filled - next)
                buf.copyInto(out, got, next, next + take)
                next += take
                got += take
            }
            consumed += n
            return out
        }

        private fun readByte(): Int {
            if (next >= filled && !refill()) return -1
            consumed++
            return buf[next++].toInt() and 0xFF
        }

        private fun refill(): Boolean {
            next = 0
            filled = 0
            if (physical >= maxPhysical) return false
            channelBuf.clear()
            channelBuf.limit(minOf(BUFFER_SIZE.toLong(), maxPhysical - physical).toInt())
            while (channelBuf.hasRemaining()) {
                val r = try {
                    ch.read(channelBuf)
                } catch (e: IOException) {
                    break
                }
                if (r <= 0) break
            }
            filled = channelBuf.position()
            physical += filled.toLong()
            return filled > 0
        }

        /** 段边界处读 marker：要求 FF 开头，跳过 fill FF，返回第二个字节。 */
        fun readMarker(): Int? {
            val fill = readByte()
            if (fill < 0 || fill != 0xFF) return null
            var m = readByte()
            if (m < 0) return null
            while (m == 0xFF) {
                m = readByte()
                if (m < 0) return null
            }
            if (m == 0x00) return null
            return m
        }

        /** 扫描熵数据，返回其后第一个 marker 字节（已消耗 FF 与该字节）。 */
        fun scanEntropy(): Int? {
            while (true) {
                val b = readByte()
                if (b < 0) return null
                if (b != 0xFF) continue
                var m = readByte()
                if (m < 0) return null
                while (m == 0xFF) {
                    m = readByte()
                    if (m < 0) return null
                }
                if (m == 0x00) continue        // 填充过的 0xFF
                if (m in 0xD0..0xD7) continue  // RSTn 仍在熵数据内
                return m
            }
        }
    }
}
