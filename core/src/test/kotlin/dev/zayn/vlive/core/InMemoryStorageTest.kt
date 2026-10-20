package dev.zayn.vlive.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class InMemoryStorageTest {
    /** 走公开读路径把整个文件取回来；不用它就没法验证「关闭才可见」的具体内容。 */
    private fun readAll(storage: InMemoryStorage, path: String): ByteArray =
        storage.open(path).open().use { ch ->
            val buf = ByteBuffer.allocate(64)
            while (buf.hasRemaining() && ch.read(buf) > 0) { }
            buf.flip()
            buf.array().copyOfRange(0, buf.remaining())
        }

    @Test fun `write move delete round trip`() {
        val storage = InMemoryStorage()
        storage.ensureDir("/t")
        storage.openWrite("/t/a.jpg").use { it.writeAll(ByteBuffer.wrap(byteArrayOf(1, 2))) }
        assertTrue(storage.exists("/t/a.jpg"))
        assertTrue(storage.move("/t/a.jpg", "/t/b.jpg"))
        assertFalse(storage.exists("/t/a.jpg"))
        storage.open("/t/b.jpg").open().use { ch ->
            val buf = ByteBuffer.allocate(4)
            while (buf.hasRemaining() && ch.read(buf) > 0) { }
            buf.flip()
            assertEquals(2, buf.remaining())
        }
        assertTrue(storage.delete("/t/b.jpg"))
    }

    @Test fun `move overwrites existing target`() {
        val storage = InMemoryStorage()
        storage.ensureDir("/t")
        storage.openWrite("/t/x").use { it.writeAll(ByteBuffer.wrap(byteArrayOf(9))) }
        storage.openWrite("/t/y").use { it.writeAll(ByteBuffer.wrap(byteArrayOf(7))) }
        assertTrue(storage.move("/t/y", "/t/x"))
        assertFalse(storage.exists("/t/y"))
        assertTrue(storage.exists("/t/x"))
        assertArrayEquals(byteArrayOf(7), readAll(storage, "/t/x"))
    }

    @Test fun `list skips other directories`() {
        val storage = InMemoryStorage()
        storage.ensureDir("/a")
        storage.openWrite("/a/one.jpg").use { it.writeAll(ByteBuffer.wrap(ByteArray(3))) }
        storage.openWrite("/a/sub/two.jpg").use { it.writeAll(ByteBuffer.wrap(ByteArray(5))) }
        assertEquals(listOf("one.jpg"), storage.list("/a").map { it.name })
        // Task 8 在任何 ensureDir 之前就先 list(targetDir)：目录不存在只能返回空列表，不许抛。
        assertTrue(InMemoryStorage().list("/nope").isEmpty())
    }

    @Test fun `list is sorted by name and carries sizes`() {
        val storage = InMemoryStorage()
        storage.ensureDir("/d")
        storage.openWrite("/d/b.jpg").use { it.writeAll(ByteBuffer.wrap(ByteArray(2))) }
        storage.openWrite("/d/a.jpg").use { it.writeAll(ByteBuffer.wrap(ByteArray(3))) }
        storage.openWrite("/d/a.jpeg").use { it.writeAll(ByteBuffer.wrap(ByteArray(4))) }
        storage.openWrite("/d/a.mp4").use { it.writeAll(ByteBuffer.wrap(ByteArray(5))) }
        // 自然升序：'e'(0x65) < 'g'(0x67)，所以 a.jpeg 在 a.jpg 之前——
        // PairPlanner 的「同 stem 取第一个」因此拿到 a.jpeg，这个顺序不能漂。
        assertEquals(
            listOf(DirEntry("a.jpeg", 4L), DirEntry("a.jpg", 3L), DirEntry("a.mp4", 5L), DirEntry("b.jpg", 2L)),
            storage.list("/d"),
        )
    }

    @Test fun `openWrite publishes bytes only when the channel closes`() {
        val storage = InMemoryStorage()
        storage.ensureDir("/t")
        val channel = storage.openWrite("/t/a.jpg")
        channel.writeAll(ByteBuffer.wrap(byteArrayOf(1, 2)))
        assertFalse(storage.exists("/t/a.jpg"))
        channel.close()
        assertTrue(storage.exists("/t/a.jpg"))
        assertEquals(2L, storage.open("/t/a.jpg").size())
        assertArrayEquals(byteArrayOf(1, 2), readAll(storage, "/t/a.jpg"))
    }

    @Test fun `closing twice does not duplicate or corrupt`() {
        val storage = InMemoryStorage()
        storage.ensureDir("/t")
        val channel = storage.openWrite("/t/a.jpg")
        channel.use { it.writeAll(ByteBuffer.wrap(byteArrayOf(3, 4))) }
        channel.close()
        channel.close()
        assertFalse(channel.isOpen)
        assertEquals(2L, storage.open("/t/a.jpg").size())
        assertArrayEquals(byteArrayOf(3, 4), readAll(storage, "/t/a.jpg"))
    }

    @Test fun `writing after close fails`() {
        val storage = InMemoryStorage()
        storage.ensureDir("/t")
        val channel = storage.openWrite("/t/a.jpg")
        channel.use { it.writeAll(ByteBuffer.wrap(byteArrayOf(5))) }
        assertThrows(IllegalStateException::class.java) {
            channel.writeAll(ByteBuffer.wrap(byteArrayOf(6)))
        }
        assertArrayEquals(byteArrayOf(5), readAll(storage, "/t/a.jpg"))
    }

    @Test fun `move and delete report false for missing sources`() {
        val storage = InMemoryStorage()
        storage.ensureDir("/t")
        assertFalse(storage.move("/t/nope", "/t/a_MP.jpg"))
        assertFalse(storage.exists("/t/a_MP.jpg"))
        assertFalse(storage.delete("/t/nope"))
        assertFalse(storage.exists("/t/nope"))
    }

    @Test fun `initial map seeds readable files`() {
        val storage = InMemoryStorage(mapOf("/s/a.jpg" to byteArrayOf(7, 7), "/s/a.mp4" to ByteArray(9)))
        assertEquals(listOf(DirEntry("a.jpg", 2L), DirEntry("a.mp4", 9L)), storage.list("/s"))
        assertArrayEquals(byteArrayOf(7, 7), readAll(storage, "/s/a.jpg"))
        // 重写同一个路径是截断式的：旧内容不会跟在后面
        storage.openWrite("/s/a.jpg").use { it.writeAll(ByteBuffer.wrap(byteArrayOf(1))) }
        assertArrayEquals(byteArrayOf(1), readAll(storage, "/s/a.jpg"))
    }

    @Test fun `opening a missing file throws`() {
        val storage = InMemoryStorage()
        assertThrows(java.io.FileNotFoundException::class.java) { storage.open("/t/nope.jpg") }
    }
}
