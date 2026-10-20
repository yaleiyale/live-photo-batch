package dev.zayn.vlive.core

import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel

data class ByteRange(val start: Long, val endExclusive: Long) {
    val length: Long get() = endExclusive - start

    init {
        require(endExclusive >= start) { "endExclusive=$endExclusive before start=$start" }
    }
}

/** 可反复打开的只读字节来源；:core 用它表达"同一文件要读两遍"而不依赖 java.io.File。 */
interface ChannelSource {
    fun open(): ReadableByteChannel
    fun size(): Long
}

class ByteArraySource(private val bytes: ByteArray) : ChannelSource {
    override fun open(): ReadableByteChannel = object : ReadableByteChannel {
        private var pos = 0
        private var closed = false
        override fun read(dst: ByteBuffer): Int {
            if (closed) throw java.nio.channels.ClosedChannelException()
            if (pos >= bytes.size) return -1
            val n = minOf(dst.remaining(), bytes.size - pos)
            dst.put(bytes, pos, n)
            pos += n
            return n
        }
        override fun close() { closed = true }
        override fun isOpen() = !closed
    }
    override fun size(): Long = bytes.size.toLong()
}
