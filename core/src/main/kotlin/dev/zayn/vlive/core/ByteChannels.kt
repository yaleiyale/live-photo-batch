package dev.zayn.vlive.core

import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

/** WritableByteChannel.write 允许只消费部分缓冲；单次调用会写出短文件。 */
internal fun WritableByteChannel.writeAll(src: ByteBuffer) {
    while (src.hasRemaining()) {
        if (write(src) <= 0) throw java.io.IOException("sink 拒绝写入")
    }
}
