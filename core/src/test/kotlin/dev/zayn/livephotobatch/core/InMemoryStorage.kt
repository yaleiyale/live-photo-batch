package dev.zayn.livephotobatch.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.WritableByteChannel

/**
 * 单测用；key 是绝对路径，目录由 key 的前缀隐含。
 *
 * `openWrite` 严格遵守 BatchStorage 的可见性约定：写进通道的字节只在 `close()` 返回后
 * 才出现在目录里，重复 `close()` 是 no-op，关闭后再写抛 `IllegalStateException`。
 */
class InMemoryStorage(initial: Map<String, ByteArray> = emptyMap()) : BatchStorage {
    private val files = LinkedHashMap<String, ByteArray>(initial)

    override fun list(dirPath: String): List<DirEntry> {
        val prefix = dirPath.trimEnd('/') + "/"
        return files.entries
            .filter { it.key.startsWith(prefix) && !it.key.substring(prefix.length).contains('/') }
            .map { DirEntry(it.key.substring(prefix.length), it.value.size.toLong()) }
            .sortedBy { it.name }
    }

    override fun open(filePath: String): ChannelSource {
        val bytes = files[filePath] ?: throw java.io.FileNotFoundException(filePath)
        return ByteArraySource(bytes)
    }

    /** map 里只有文件，目录不是 key，所以对目录路径天然是 false——正是接口契约要的。 */
    override fun exists(filePath: String): Boolean = files.containsKey(filePath)

    override fun ensureDir(dirPath: String) { /* 目录隐含在 key 里，无需操作 */ }

    override fun openWrite(filePath: String): WritableByteChannel {
        val buffer = ByteArrayOutputStream()
        val sink = Channels.newChannel(buffer)
        return object : WritableByteChannel {
            private var open = true
            override fun write(src: ByteBuffer): Int {
                check(open) { "写过的通道已经关闭：$filePath" }
                return sink.write(src)
            }
            override fun close() {
                if (!open) return
                open = false
                sink.close()
                files[filePath] = buffer.toByteArray()
            }
            override fun isOpen(): Boolean = open
        }
    }

    override fun move(from: String, to: String): Boolean {
        val bytes = files.remove(from) ?: return false
        files[to] = bytes
        return true
    }

    override fun delete(filePath: String): Boolean = files.remove(filePath) != null

    /**
     * 测试专用（Task 8）：还留在某个 `.tmp` 目录里的半截文件路径。
     * 生产代码没有读者——`BatchRunner` 只负责删它，接口也不可能长出"列半截产物"这种成员，
     * 所以它属于夹具而不是 [BatchStorage] API。用来证明 §7.5 的清理真的发生了。
     */
    val tmpFiles: List<String>
        get() = files.keys.filter { it.contains("/.tmp/") }.sorted()

    /** 测试专用（Task 8）：读出某条路径现在的字节；不存在就抛，绝不静默给空数组。 */
    fun bytesOf(filePath: String): ByteArray =
        files[filePath] ?: throw IllegalArgumentException("内存目录里没有这条路径：$filePath")
}
