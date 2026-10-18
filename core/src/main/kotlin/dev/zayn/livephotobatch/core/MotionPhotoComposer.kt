package dev.zayn.livephotobatch.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SeekableByteChannel
import java.nio.channels.WritableByteChannel

enum class FailureReason {
    NOT_JPEG,
    MALFORMED_JPEG,
    ALREADY_MOTION_PHOTO,
    EXTENDED_XMP_UNSUPPORTED,
    MALFORMED_XMP,
    NOT_MP4,
    IO_ERROR,
    NO_PAIR,
}

sealed interface ComposeResult {
    data class Success(val videoSize: Long, val totalBytes: Long) : ComposeResult
    data class Failure(val reason: FailureReason) : ComposeResult
}

/**
 * JPEG + MP4 → Google Motion Photo：以源 XMP 为基底合并出动照片声明，再把 MP4 原始字节整段
 * 追加到 JPEG 的 `EOI` 之后。不解码、不转码、不重编码，纯字节拼接 + 元数据。
 *
 * 产物布局（spec「增补（2026-10-18b）」§2）：
 * `SOI` + `源图[2, 旧XMP起点)` + 新 XMP APP1 + `源图[旧XMP终点, imageEnd)` + 视频字节。
 * `imageEnd` 之后的源字节（gain map 副图、厂商记录、零填充）**一律丢掉**，EOI 与视频之间零字节；
 * 新 XMP 写回旧段的原位，所以源片里排在 XMP 之前的 EXIF 仍然在它之前。
 * `GCamera:MicroVideoOffset` 等于**追加的视频字节数**，`文件大小 - MicroVideoOffset == imageEnd`
 * 是产物自身必须成立的可断言等式（偏移量与文件布局之间没有循环依赖）。
 *
 * 校验顺序按 spec §7.4（增补 §1 取代其中第 3 条），且是**可观测的**：先把最便宜的视频头探测放最前
 * （坏视频绝不让后面白扫一遍 JPEG），再判 SOI、段遍历、扩展 XMP、尾巴分类、XMP 可否解析，
 * 全部通过后才开始写 sink。被校验拒掉的项对 sink 一个字节都不写；**写入阶段**的 I/O 故障就不一定了——
 * 它可能在 patch 之后、甚至视频搬了一半时才炸，sink 里实打实留着半截文件。两种失败都只报 [FailureReason]、
 * 不报字节数：spec §7.5 的清理是无条件的（失败就删 .part），计数没有读者，而一个恒为 0 的字段只会被误读成
 * "0 表示什么都没写过"，那在半途失败这条路上恰恰是假的。
 *
 * sink 由调用方拥有并负责关闭：本函数绝不关它（Task 8 用 `use { }` 包着它）。
 * 全程流式、定长 64 KiB 缓冲，常数内存；同一个来源要读两遍（扫描 + 搬运）靠 [ChannelSource]
 * 可重开这一契约实现，:core 因此不需要真实的文件路径抽象。尾巴探测另开第三次读，但**只取 16 字节**：
 * 来源可定位（Task 9 的 `RandomAccessFile.channel`）时是一次 seek，不可定位时才退化成定长缓冲跳过。
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
            readAt(video, 0L, minOf(PROBE_BYTES.toLong(), videoSize).toInt())
        } catch (e: IOException) {
            return ComposeResult.Failure(FailureReason.IO_ERROR)
        }
        if (!containsFtyp(head)) return ComposeResult.Failure(FailureReason.NOT_MP4)

        val scan = try {
            image.open().use { ch -> JpegScanner.scan(ch) }
        } catch (e: IOException) {
            return ComposeResult.Failure(FailureReason.IO_ERROR)
        }
        val structure = when (scan) {
            is JpegScanResult.NotJpeg -> return ComposeResult.Failure(FailureReason.NOT_JPEG)
            is JpegScanResult.Malformed -> return ComposeResult.Failure(FailureReason.MALFORMED_JPEG)
            is JpegScanResult.Parsed -> scan.structure
        }
        if (structure.hasExtendedXmp) {
            return ComposeResult.Failure(FailureReason.EXTENDED_XMP_UNSUPPORTED)
        }

        val fileSize = image.size()
        // fileSize - imageEnd 万一为负（源文件在扫描之后被别的进程截短），按"没有尾巴"处理：
        // ByteArray(负数) 会直接把批次炸成 NegativeArraySizeException，那是运行故障不是输入形状。
        val trailerBytes = maxOf(0L, fileSize - structure.imageEnd)
        val trailerHead = try {
            readAt(
                image,
                structure.imageEnd,
                // 精确的窗口长度：短于 PROBE_BYTES 就代表整条尾巴都在手里，分类器靠这个区分上界。
                minOf(PROBE_BYTES.toLong(), trailerBytes).toInt(),
            )
        } catch (e: IOException) {
            return ComposeResult.Failure(FailureReason.IO_ERROR)
        }
        // 增补 §1：只有**视频形状**的尾巴（或源 XMP 已经声明过视频）才算"上一轮已经合成过"。
        // gain map 副图、厂商 JSON、零填充在这里放行——vivo 上每张 JPEG 都有这种尾巴。
        // 放行的下场是"丢掉"：增补 2026-10-18b §2，EOI 之后只留视频，尾巴不再搬进产物。
        if (TrailerClassifier.classify(trailerHead, structure.oldXmpText) == TrailerKind.VIDEO) {
            return ComposeResult.Failure(FailureReason.ALREADY_MOTION_PHOTO)
        }

        val app1 = try {
            // 增补 §3：以源包为基底合并，产物形状照参考语料（单 RDF、单 Description、空 Directory）；
            // 畸形包报 MALFORMED_XMP，绝不退化成"没有 XMP"然后静默把用户的元数据换掉。
            XmpPacket.app1SegmentFor(XmpMerger.merge(structure.oldXmpText, videoSize))
        } catch (e: MalformedXmpException) {
            return ComposeResult.Failure(FailureReason.MALFORMED_XMP)
        } catch (e: IllegalArgumentException) {
            // 包体大到一个 APP1 的 16 位长度字段装不下——和分块扩展 XMP 同一个下场：不做处理。
            return ComposeResult.Failure(FailureReason.EXTENDED_XMP_UNSUPPORTED)
        }
        return try {
            // patch 的返回值是"从源图写出的字节数"：不含新 APP1，不含被丢弃的旧 XMP，
            // 也不含 imageEnd 之后被丢掉的尾巴。
            val patched = JpegXmpPatcher.patch(structure, image, sink, app1)
            copyToEnd(video, sink)
            ComposeResult.Success(videoSize, patched + app1.size + videoSize)
        } catch (e: IOException) {
            // 全部校验都已通过，到这里只剩 I/O 故障：sink 里可能已经留下一半内容，
            // 调用方按 §7.5 无条件丢弃半成品，所以这里只报原因，不报"写了几个字节"。
            ComposeResult.Failure(FailureReason.IO_ERROR)
        }
    }

    /**
     * 定长读 [from] 处起的 [n] 字节；不足就留零，[containsFtyp] 与分类器自己会拒绝过短的头部。
     * 来源可定位就是 seek + 一次小读；管道一类不可定位的来源退化成用同一个 64 KiB 缓冲一路读掉，
     * 内存仍是常数（生产路径给的是 `RandomAccessFile.channel`，不会走到这一支）。
     */
    private fun readAt(src: ChannelSource, from: Long, n: Int): ByteArray {
        val buf = ByteArray(n)
        if (n == 0) return buf
        src.open().use { ch ->
            skipTo(ch, from, ByteArray(COPY_BUFFER))
            val dst = ByteBuffer.wrap(buf)
            while (dst.hasRemaining()) {
                if (ch.read(dst) <= 0) break
            }
        }
        return buf
    }

    private fun skipTo(ch: ReadableByteChannel, from: Long, scratch: ByteArray) {
        if (from <= 0) return
        if (ch is SeekableByteChannel) {
            ch.position(from)
            return
        }
        // 不可定位：只能一路读过去。每次最多要 `left` 个字节——一次大读会把 [from] 之后的字节
        // 一并吞进缓冲区，探测到的就成了零填充而不是真尾巴。
        var left = from
        while (left > 0) {
            val want = minOf(left, scratch.size.toLong()).toInt()
            val read = ch.read(ByteBuffer.wrap(scratch, 0, want))
            if (read <= 0) return
            left -= read.toLong()
        }
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
