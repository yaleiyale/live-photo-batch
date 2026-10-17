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
        // 写出与丢弃走同一个搬运循环，唯一的区别是拿到分块之后做什么。
        val emit: (ByteBuffer) -> Unit = { sink.writeAll(it) }
        val discard: (ByteBuffer) -> Unit = { /* 读掉即等于丢弃：旧 XMP 在这一步消失 */ }
        // 每次调用一个定长缓冲区：常数内存，绝不整图 readBytes()。
        val buf = ByteArray(COPY_BUFFER)
        var copied = 0L
        image.open().use { ch ->
            val input = Channels.newInputStream(ch)
            copied += transfer(input, buf, 2L, emit)                     // SOI
            sink.writeAll(ByteBuffer.wrap(app1))                          // 新 XMP
            val oldXmp = structure.oldXmp
            val gapStart = oldXmp?.start ?: structure.imageEnd
            copied += transfer(input, buf, gapStart - 2L, emit)           // [2, 旧 XMP)
            if (oldXmp != null) {
                // 旧段必须被**读掉**（不写、不计数），否则下一步的 29 字节会从旧段开头读起，
                // 输出的既不是新 XMP 之后的原图，也丢掉了真正的图片尾部（EOI 会整个消失）。
                transfer(input, buf, oldXmp.length, discard)
                // (旧 XMP, imageEnd)：旧段被新段取代，一个字节都不留。
                copied += transfer(input, buf, structure.imageEnd - oldXmp.endExclusive, emit)
            }
        }
        return copied
    }

    /**
     * 从 [input] 精确取 [n] 字节，逐块经共享缓冲区交给 [chunk]：写出去还是原地丢弃由调用方决定。
     * 不够就抛 [java.io.EOFException]（IOException 子类）——绝不静默写出半截 JPEG；
     * 也绝不多读一个字节，所以 [n] 之和锁定在 imageEnd 之内，追加的视频尾部不会进 sink。
     * @return [n]，调用方直接拿它累加已搬运的源图字节数。
     */
    private fun transfer(
        input: InputStream,
        buf: ByteArray,
        n: Long,
        chunk: (ByteBuffer) -> Unit,
    ): Long {
        require(n >= 0) { "待搬区间长度为负: $n" }
        var left = n
        while (left > 0) {
            val want = minOf(left, buf.size.toLong()).toInt()
            val read = input.read(buf, 0, want)
            if (read <= 0) throw java.io.EOFException("JPEG 数据提前结束，还差 $left 字节")
            chunk(ByteBuffer.wrap(buf, 0, read))
            left -= read
        }
        return n
    }
}
