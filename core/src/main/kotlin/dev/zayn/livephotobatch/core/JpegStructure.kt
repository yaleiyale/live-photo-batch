package dev.zayn.livephotobatch.core

import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel

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
 * 按 marker 遍历 JPEG，绝不搜索字节序列 FFD9：追加的视频字节里合法地会出现 FFD9。
 * 熵数据内的 0xFF 必定被填充成 FF00，所以"FF 后跟非 0"才是真 marker。
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

    private class Cursor(private val ch: ReadableByteChannel) {
        var position: Long = 0L
            private set

        fun read(n: Int): ByteArray? {
            val out = ByteArray(n)
            val buf = ByteBuffer.wrap(out)
            while (buf.hasRemaining()) {
                val r = try { ch.read(buf) } catch (e: java.io.IOException) { return null }
                if (r <= 0) return null
            }
            position += n
            return out
        }

        private fun readByte(): Int {
            val b = read(1) ?: return -1
            return b[0].toInt() and 0xFF
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
