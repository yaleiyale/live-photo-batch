package dev.zayn.livephotobatch.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel

/**
 * 写出 `SOI + 新 APP1(XMP) + 源图 [2, imageEnd) 去掉旧 XMP`。
 * 只做区间搬运，不解释 JPEG 语义——语义已经由 JpegScanner 固化成 JpegStructure。
 */
object JpegXmpPatcher {
    private const val COPY_BUFFER = 64 * 1024

    /**
     * @return 从源图**实际写入 [sink]** 的字节数（不含新 APP1），
     * 即 `imageEnd` 扣掉被丢弃的旧 XMP 段长；没有旧 XMP 时就是 `imageEnd`。
     * [imageEnd] 之后的字节（已合成文件里追加的视频）一律不搬。
     */
    fun patch(
        structure: JpegStructure,
        image: ChannelSource,
        sink: WritableByteChannel,
        app1: ByteArray,
    ): Long {
        // 每次调用一个定长缓冲区：常数内存，绝不整图 readBytes()。
        val buf = ByteArray(COPY_BUFFER)
        var copied = 0L
        image.open().use { ch ->
            val input = Channels.newInputStream(ch)
            copied += copyExact(input, sink, buf, 2L)                  // SOI
            drain(sink, ByteBuffer.wrap(app1))                         // 新 XMP
            val oldXmp = structure.oldXmp
            val gapStart = oldXmp?.start ?: structure.imageEnd
            copied += copyExact(input, sink, buf, gapStart - 2L)       // [2, 旧 XMP)
            if (oldXmp != null) {
                // 旧段必须被**读掉**（不写、不计数），否则下一步的 29 字节会从旧段开头读起，
                // 输出的既不是新 XMP 之后的原图，也丢掉了真正的图片尾部（EOI 会整个消失）。
                skipExact(input, buf, oldXmp.length)
                // (旧 XMP, imageEnd)：旧段被新段取代，一个字节都不留。
                copied += copyExact(input, sink, buf, structure.imageEnd - oldXmp.endExclusive)
            }
        }
        return copied
    }

    /** 精确搬 n 字节；不够就抛，绝不静默写出半截 JPEG。 */
    private fun copyExact(
        input: InputStream,
        sink: WritableByteChannel,
        buf: ByteArray,
        n: Long,
    ): Long {
        require(n >= 0) { "待搬区间长度为负: $n" }
        var left = n
        while (left > 0) {
            val want = minOf(left, buf.size.toLong()).toInt()
            val read = input.read(buf, 0, want)
            if (read <= 0) throw java.io.EOFException("JPEG 数据提前结束，还差 $left 字节")
            drain(sink, ByteBuffer.wrap(buf, 0, read))
            left -= read
        }
        return n
    }

    /** 读 n 字节直接丢弃：这就是"旧 XMP 从输出里消失"的那一步。 */
    private fun skipExact(input: InputStream, buf: ByteArray, n: Long) {
        require(n >= 0) { "待丢弃区间长度为负: $n" }
        var left = n
        while (left > 0) {
            val want = minOf(left, buf.size.toLong()).toInt()
            val read = input.read(buf, 0, want)
            if (read <= 0) throw java.io.EOFException("JPEG 数据提前结束，还差 $left 字节")
            left -= read
        }
    }

    /** `WritableByteChannel.write` 允许部分写入，必须循环到缓冲区清空，否则写出短文件。 */
    private fun drain(sink: WritableByteChannel, src: ByteBuffer) {
        while (src.hasRemaining()) {
            if (sink.write(src) <= 0) throw java.io.IOException("sink 拒绝接收数据")
        }
    }
}
