package dev.zayn.livephotobatch.core

import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SeekableByteChannel

data class JpegStructure(
    /** EOI 之后一个字节的位置，即"真 JPEG"的末尾 */
    val imageEnd: Long,
    val oldXmp: ByteRange?,
    val oldXmpText: String?,
    val hasExtendedXmp: Boolean,
)

sealed interface JpegScanResult {
    data class Parsed(val structure: JpegStructure) : JpegScanResult
    data object NotJpeg : JpegScanResult
    data object Malformed : JpegScanResult
}

/**
 * 按 marker 遍历 JPEG（spec §7.2），绝不搜索字节序列 FFD9：追加的视频字节里合法地会出现 FFD9。
 * 熵数据内的 0xFF 必定被填充成 FF00，所以"FF 后跟非 0"才是真 marker。
 *
 * 全程流式、常数内存：只有带长度字段的段按其长度缓冲（上限 65533 字节），熵数据一边预读一边丢弃。
 */
object JpegScanner {
    private val XAP = FixturesIdentifiers.XAP
    private val XMP_EXTENSION = FixturesIdentifiers.XMP_EXTENSION

    fun scan(src: ReadableByteChannel): JpegScanResult {
        val cur = Cursor(src)
        val head = cur.read(2) ?: return JpegScanResult.NotJpeg
        if (head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return JpegScanResult.NotJpeg

        var oldXmp: ByteRange? = null
        var oldXmpText: String? = null
        var hasExtendedXmp = false
        var pending: Int? = null

        while (true) {
            val marker: Int
            if (pending != null) {
                marker = pending
                pending = null
            } else {
                marker = cur.readMarker() ?: return JpegScanResult.Malformed
            }
            val markerStart = cur.position - 2

            if (marker == 0xD9) {
                // 定位结束：把预读缓冲区里没用到的字节退回给可定位的 channel（生产环境是
                // FileChannel），保证调用方拿到的 channel 恰好停在 imageEnd。
                cur.rewindUnused()
                return JpegScanResult.Parsed(
                    JpegStructure(cur.position, oldXmp, oldXmpText, hasExtendedXmp)
                )
            }
            if (marker == 0x01 || marker in 0xD0..0xD7) continue // TEM / RSTn：无长度字段

            val lenBytes = cur.read(2) ?: return JpegScanResult.Malformed
            val len = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
            if (len < 2) return JpegScanResult.Malformed
            val payload = cur.read(len - 2) ?: return JpegScanResult.Malformed

            when (marker) {
                0xDA -> pending = cur.scanEntropy() ?: return JpegScanResult.Malformed
                0xE1 -> when {
                    payload.startsWith(XAP) -> {
                        // XAP 已含结尾 NUL，标识符长度就是 XAP.size
                        val identifierEnd = XAP.size
                        if (payload.size > identifierEnd) {
                            oldXmp = ByteRange(markerStart, cur.position)
                            oldXmpText = String(
                                payload.copyOfRange(identifierEnd, payload.size), Charsets.UTF_8
                            )
                        }
                    }
                    payload.startsWith(XMP_EXTENSION) -> hasExtendedXmp = true
                }
            }
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (i in prefix.indices) if (this[i] != prefix[i]) return false
        return true
    }

    /** APP1 标识符是 ISO-8859-1 文本；放在独立对象里，避免生产代码依赖测试夹具。 */
    private object FixturesIdentifiers {
        val XAP = "http://ns.adobe.com/xap/1.0/".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0)
        val XMP_EXTENSION = "http://ns.adobe.com/xmp/extension/".toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * 定长 64 KiB 预读缓冲区。
     *
     * 逐字节 `ch.read(ByteBuffer.wrap(ByteArray(1)))` 会让熵数据里的每个字节都产生一次小数组、
     * 一个小 ByteBuffer 和一次系统调用；按 spec §2 G5 的预算（100 张约 4 MB JPEG）就是约 4 亿次，
     * 15 秒根本不可能。这里把"取下一个字节"变成"取下一个 64 KiB"，内存仍是常数。
     *
     * [position] 始终是**逻辑**已消费字节数，与缓冲区里已经读进来但还没消费的提前量无关；
     * 提前量恒不超过一个缓冲区，并且找到 EOI 后 [rewindUnused] 会把它退回可定位的 channel。
     */
    private class Cursor(private val ch: ReadableByteChannel) {
        var position: Long = 0L
            private set

        private val buf = ByteArray(BUFFER_SIZE)

        /** 与 [buf] 共享底层数组，channel 直接读进缓冲区，不做第二次拷贝。 */
        private val channelBuf = ByteBuffer.wrap(buf)
        private var filled = 0
        private var next = 0

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
            position += n
            return out
        }

        private fun readByte(): Int {
            if (next >= filled && !refill()) return -1
            position++
            return buf[next++].toInt() and 0xFF
        }

        /** 一次定长读把空缓冲区重新装满；读不到新字节（EOF 或 I/O 错）返回 false。 */
        private fun refill(): Boolean {
            next = 0
            filled = 0
            channelBuf.clear()
            while (channelBuf.hasRemaining()) {
                val r = try { ch.read(channelBuf) } catch (e: java.io.IOException) { break }
                if (r <= 0) break
            }
            filled = channelBuf.position()
            return filled > 0
        }

        /**
         * 把预读进来但没用到的字节退回 channel，让它停在 [position]（= imageEnd）。
         * 只对接入了 [SeekableByteChannel] 的来源生效（Task 9 的 `RandomAccessFile.channel`）；
         * 管道一类不可定位的来源就只能丢掉这一段有界提前量——解析结果本身不受影响。
         */
        fun rewindUnused() {
            val unused = filled - next
            if (unused <= 0) return
            val seekable = ch as? SeekableByteChannel ?: return
            try {
                seekable.position(seekable.position() - unused)
                next = filled
            } catch (e: java.io.IOException) {
                // 退不回去就维持一个缓冲区的有界提前量，本次解析已经完成。
            }
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

        companion object {
            private const val BUFFER_SIZE = 64 * 1024
        }
    }
}
