package dev.zayn.livephotobatch.core

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel

/**
 * 写出 `源图[0, 旧XMP起点) + 新 APP1(XMP) + 源图[旧XMP终点, trailerEnd)`：
 * 新段回到**旧段原来的位置**，`imageEnd` 之后的尾巴按调用方点名的 `trailerEnd` 一起搬走
 * （合成器给的是文件大小，即源 JPEG **整文件**进产物，spec 增补（2026-10-18c）§1）。
 * 只做区间搬运，不解释 JPEG 语义——语义已经由 JpegScanner 固化成 JpegStructure。
 */
object JpegXmpPatcher {
    private const val COPY_BUFFER = 64 * 1024

    /**
     * 产物布局（增补 2026-10-18c §1）：源 JPEG 从 SOI 到文件末**一个字节都不改**，只把旧 XMP `APP1`
     * 换成新的那一份；视频跟在 `trailerEnd` 之后。
     *
     * 为什么 `imageEnd` 之后的尾巴必须留：真机单变量对照里，同一个 XMP 包、同一个视频，
     * 保留源片尾巴（那张 442,350 字节的 gain map 副图）的候选被 vivo 相册认作动态照片，
     * 截到 EOI 的那份和重编码过的主图都不认。副图留在产物里还顺带保住了 HDR。
     *
     * 为什么新段必须写回旧段的原位而不是插在 SOI 之后：vivo 相册与 Exiv2 按 `EXIF APP1 在前、
     * XMP APP1 在后` 的形状读元数据，参考语料（vivo 自己认可的合成结果）就保持源片的段序
     * （`SOI → APP0 → EXIF → XMP → …`）。插在 SOI 之后会把 XMP 挤到 EXIF 前面，实测产物就不被
     * 认作动态照片。源里没有 XMP 段时没有"原位"可回，按 §7.3 插在 SOI 之后——此时新段会把
     * 紧跟 SOI 的 APP0 推到后面（语料的源片都带 XMP，这种形状没有参考实现可比）。
     *
     * @param trailerEnd `imageEnd` 之后还要连带搬走的字节位置（含），默认就是 [JpegStructure.imageEnd]，
     * 即"一个越过 EOI 的字节都不搬"。合成器传源文件大小。
     * @return 从源图**实际写入 [sink]** 的字节数：不含新 APP1，不含被丢弃的旧 XMP，
     * **含 `imageEnd` 之后搬进来的尾巴**——有旧 XMP 时就是
     * `imageEnd - 旧XMP.length + (trailerEnd - imageEnd)`。
     */
    fun patch(
        structure: JpegStructure,
        image: ChannelSource,
        sink: WritableByteChannel,
        app1: ByteArray,
        trailerEnd: Long = structure.imageEnd,
    ): Long {
        // 只准"多搬"，不准"少搬"：少搬产出一个悄悄缺尾巴的文件，没人能发现。
        require(trailerEnd >= structure.imageEnd) {
            "trailerEnd=$trailerEnd 落在 imageEnd=${structure.imageEnd} 之前"
        }
        // 写出与丢弃走同一个搬运循环，唯一的区别是拿到分块之后做什么。
        val emit: (ByteBuffer) -> Unit = { sink.writeAll(it) }
        val discard: (ByteBuffer) -> Unit = { /* 读掉即等于丢弃：旧 XMP 在这一步消失 */ }
        // 每次调用一个定长缓冲区：常数内存，绝不整图 readBytes()。
        val buf = ByteArray(COPY_BUFFER)
        var copied = 0L
        image.open().use { ch ->
            val input = Channels.newInputStream(ch)
            val oldXmp = structure.oldXmp
            if (oldXmp == null) {
                // 源里没有 XMP 段：没有"原位"可回，就按 §7.3 插在 SOI 之后。
                copied += transfer(input, buf, 2L, emit)
                sink.writeAll(ByteBuffer.wrap(app1))
                copied += transfer(input, buf, structure.imageEnd - 2L, emit)
            } else {
                // 旧段之前的段（EXIF、ICC、APP0…）必须留在新 XMP 之前——参考语料就是这个顺序。
                copied += transfer(input, buf, oldXmp.start, emit)         // SOI + [2, 旧段起点)
                sink.writeAll(ByteBuffer.wrap(app1))
                // 旧段必须被**读掉**（不写、不计数），否则下一步会从旧段开头读起：
                // 输出的既不是新 XMP 之后的原图，也丢掉了真正的图片尾部（EOI 会整个消失）。
                transfer(input, buf, oldXmp.length, discard)
                copied += transfer(input, buf, structure.imageEnd - oldXmp.endExclusive, emit)
            }
            // EOI 之后的尾巴：调用方点名要多少搬多少，字节原样、顺序不变。
            copied += transfer(input, buf, trailerEnd - structure.imageEnd, emit)
        }
        return copied
    }

    /**
     * 从 [input] 精确取 [n] 字节，逐块经共享缓冲区交给 [chunk]：写出去还是原地丢弃由调用方决定。
     * 不够就抛 [java.io.EOFException]（IOException 子类）——绝不静默写出半截 JPEG；
     * 也绝不多读一个字节，所以所有 [n] 之和恰好等于 `trailerEnd - 被丢弃的旧 XMP`，
     * 越过 [trailerEnd] 的字节（上一轮追加的视频）一律读不到、也就写不出去。
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
