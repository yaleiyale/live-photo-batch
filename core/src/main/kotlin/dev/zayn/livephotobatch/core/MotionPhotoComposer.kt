package dev.zayn.livephotobatch.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

enum class FailureReason {
    NOT_JPEG,
    MALFORMED_JPEG,
    ALREADY_MOTION_PHOTO,
    EXTENDED_XMP_UNSUPPORTED,
    NOT_MP4,
    IO_ERROR,
    NO_PAIR,
}

sealed interface ComposeResult {
    data class Success(val videoSize: Long, val totalBytes: Long) : ComposeResult
    data class Failure(val reason: FailureReason, val bytesWritten: Long) : ComposeResult
}

/**
 * JPEG + MP4 → Google Motion Photo：换掉 XMP 段，再把 MP4 原始字节整段追加到 EOI 之后。
 * 不解码、不转码、不重编码，纯字节拼接 + 元数据。
 *
 * 校验顺序按 spec §7.4，且是**可观测的**：先把最便宜的视频头探测放最前（坏视频绝不让后面
 * 白扫一遍 JPEG），再判 SOI、段遍历、扩展 XMP、嵌套防护，全部通过后才开始写 sink。
 * 被拒绝的项对 sink 一个字节都不写，所以每条 [ComposeResult.Failure] 都是 bytesWritten=0；
 * 写入阶段本身出 I/O 故障时确实已经吐过字节，但那是半途的废文件——spec §7.5 要求调用方直接删掉
 * .part，所以不计数（Task 8 的失败分支正是 bytes = 0）。
 *
 * sink 由调用方拥有并负责关闭：本函数绝不关它（Task 8 用 `use { }` 包着它）。
 * 全程流式、定长 64 KiB 缓冲，常数内存；同一个来源要读两遍（扫描 + 搬运）靠 [ChannelSource]
 * 可重开这一契约实现，:core 因此不需要真实的文件路径抽象。
 */
object MotionPhotoComposer {
    private const val COPY_BUFFER = 64 * 1024

    /** spec §7.4 第 4 条只看起始 12 字节；多取 4 字节留给 ftyp 的位移余量。 */
    private const val PROBE_BYTES = 16

    fun compose(
        image: ChannelSource,
        video: ChannelSource,
        sink: WritableByteChannel,
    ): ComposeResult {
        val videoSize = video.size()
        val head = try {
            readHead(video, minOf(PROBE_BYTES.toLong(), videoSize).toInt())
        } catch (e: IOException) {
            return ComposeResult.Failure(FailureReason.IO_ERROR, 0)
        }
        if (!containsFtyp(head)) return ComposeResult.Failure(FailureReason.NOT_MP4, 0)

        val scan = try {
            image.open().use { ch -> JpegScanner.scan(ch) }
        } catch (e: IOException) {
            return ComposeResult.Failure(FailureReason.IO_ERROR, 0)
        }
        val structure = when (scan) {
            is JpegScanResult.NotJpeg -> return ComposeResult.Failure(FailureReason.NOT_JPEG, 0)
            is JpegScanResult.Malformed -> return ComposeResult.Failure(FailureReason.MALFORMED_JPEG, 0)
            is JpegScanResult.Parsed -> scan.structure
        }
        if (structure.hasExtendedXmp) {
            return ComposeResult.Failure(FailureReason.EXTENDED_XMP_UNSUPPORTED, 0)
        }
        // 嵌套防护两个信号缺一不可：EOI 后还有字节 = 上一轮已经追加过视频；
        // 源 XMP 自带 Motion Photo 语义 = 同一张图已经标记过偏移。
        if (structure.imageEnd < image.size() ||
            MotionDetector.looksLikeMotionPhoto(structure.oldXmpText)
        ) {
            return ComposeResult.Failure(FailureReason.ALREADY_MOTION_PHOTO, 0)
        }

        val app1 = XmpPacket.app1Segment(videoSize)
        return try {
            // patch 的返回值是"从源图写出的字节数"，既不含新 APP1，也不含被丢弃的旧 XMP。
            val patched = JpegXmpPatcher.patch(structure, image, sink, app1)
            copyToEnd(video, sink)
            ComposeResult.Success(videoSize, patched + app1.size + videoSize)
        } catch (e: IOException) {
            // 全部校验都已通过，到这里只剩 I/O 故障；字节已经落到 sink 上，调用方须丢弃半成品。
            ComposeResult.Failure(FailureReason.IO_ERROR, 0)
        }
    }

    /** 定长读视频头；不足就留零，[containsFtyp] 自己会拒绝过短的头部。 */
    private fun readHead(video: ChannelSource, n: Int): ByteArray {
        val buf = ByteArray(n)
        if (n == 0) return buf
        video.open().use { ch ->
            val dst = ByteBuffer.wrap(buf)
            while (dst.hasRemaining()) {
                if (ch.read(dst) <= 0) break
            }
        }
        return buf
    }

    /** 视频原样追加：定长缓冲一路读到 EOF，交给同一个 [writeAll] 排空，绝不整段进内存。 */
    private fun copyToEnd(video: ChannelSource, sink: WritableByteChannel) {
        video.open().use { ch ->
            val buf = ByteBuffer.allocate(COPY_BUFFER)
            while (true) {
                buf.clear()
                if (ch.read(buf) <= 0) break
                buf.flip()
                sink.writeAll(buf)
            }
        }
    }

    /** spec §7.4 第 4 条：起始 12 字节内出现 ftyp。 */
    private fun containsFtyp(head: ByteArray): Boolean {
        if (head.size < MIN_MP4_HEAD) return false
        // 盒子 size 字段占 4 字节，所以 tag 起点最晚是 12-4=8：再往后就已越出"前 12 字节"。
        for (i in 0..(MIN_MP4_HEAD - FTYP.size)) {
            if (head.matchesAt(i, FTYP)) return true
        }
        return false
    }

    /** 越界只返回 false：短头与空模式都不会抛。 */
    private fun ByteArray.matchesAt(index: Int, pattern: ByteArray): Boolean {
        if (index < 0 || size - index < pattern.size) return false
        for (j in pattern.indices) if (this[index + j] != pattern[j]) return false
        return true
    }

    private const val MIN_MP4_HEAD = 12

    private val FTYP = byteArrayOf('f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
}
