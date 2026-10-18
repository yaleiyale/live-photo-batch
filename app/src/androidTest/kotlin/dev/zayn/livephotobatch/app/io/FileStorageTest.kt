package dev.zayn.livephotobatch.app.io

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zayn.livephotobatch.core.DirEntry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/**
 * 真机/模拟器上跑的契约测试（Task 12 用 vivo V2419A 跑）；CI 只编译不执行。
 *
 * 写在这里的用例都是 `InMemoryStorageTest` 里已经钉过的同一条 `BatchStorage` 契约——
 * 两个实现只在「目录」「可见性」这两处分叉，而 Task 6 的取舍和 Task 8 的删除原文件
 * 恰好都踩在这两处分叉上，所以每条都值得在真实文件系统上再钉一次。
 *
 * 注意 `WritableByteChannel` 只有 `write`，没有 `put`（`put` 是 `ByteChannel` 侧的读方法），
 * 而 `:core` 的 `writeAll` 是 `internal`，`:app` 用不到，所以下面直接 `write` 单块小缓冲。
 */
@RunWith(AndroidJUnit4::class)
class FileStorageTest {
    private val rootDir: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.filesDir

    private fun storage() = FileStorage(rootDir.path)

    /** 走公开读路径把整个文件取回来；验证「move 之后 to 的内容就是 from 原来的内容」只能靠读字节。 */
    private fun readAll(s: FileStorage, path: String): ByteArray =
        s.open(path).open().use { ch ->
            val buf = ByteBuffer.allocate(8)
            while (buf.hasRemaining() && ch.read(buf) > 0) { }
            buf.flip()
            buf.array().copyOfRange(0, buf.remaining())
        }

    @Test fun writeMoveReadDeleteRoundTrip() {
        val s = storage()
        s.ensureDir("out")
        s.openWrite("out/a.jpg").use { it.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3))) }
        assertTrue(s.move("out/a.jpg", "out/b.jpg"))
        assertFalse(s.exists("out/a.jpg"))
        val channel = s.open("out/b.jpg").open()
        val buf = ByteBuffer.allocate(3)
        while (buf.hasRemaining() && channel.read(buf) > 0) { }
        assertEquals(3, buf.position())
        channel.close()
        assertEquals(3L, s.open("out/b.jpg").size())
        assertArrayEquals(byteArrayOf(1, 2, 3), readAll(s, "out/b.jpg"))
        assertTrue(s.delete("out/b.jpg"))
        assertFalse(s.exists("out/b.jpg"))
    }

    @Test fun listReturnsOnlyDirectChildrenSorted() {
        val s = storage()
        s.ensureDir("d")
        s.openWrite("d/z.txt").use { it.write(ByteBuffer.wrap(byteArrayOf(1))) }
        s.openWrite("d/a.txt").use { it.write(ByteBuffer.wrap(byteArrayOf(1, 2))) }
        s.ensureDir("d/sub")
        s.openWrite("d/sub/hidden.txt").use { it.write(ByteBuffer.wrap(byteArrayOf(1))) }
        assertEquals(listOf("a.txt", "z.txt"), s.list("d").map { it.name })
        assertEquals(2L, s.list("d").first { it.name == "a.txt" }.size)
    }

    /**
     * 排序是契约的一部分：`File.listFiles()` 的顺序在设备上不可预测，
     * 而 Task 6 的 `PairPlanner` 对同 stem 的两张候选取「列表里第一个」。
     */
    @Test fun listSortsByNaturalNameOrder() {
        val s = storage()
        s.ensureDir("sort")
        s.openWrite("sort/b.jpg").use { it.write(ByteBuffer.wrap(ByteArray(2))) }
        s.openWrite("sort/a.jpg").use { it.write(ByteBuffer.wrap(ByteArray(3))) }
        s.openWrite("sort/a.jpeg").use { it.write(ByteBuffer.wrap(ByteArray(4))) }
        assertEquals(
            listOf(
                DirEntry("a.jpeg", 4L),
                DirEntry("a.jpg", 3L),
                DirEntry("b.jpg", 2L),
            ),
            s.list("sort"),
        )
    }

    /**
     * 目录不是文件：`exists` 必须为 false，`delete` 必须返回 false 且不碰它，
     * `list` 对不存在/不可读的目录给空列表。`BatchRunner` 从不把 `exists` 用在目录上，
     * 但一旦有人这么用，这两条就是内存实现与真实文件系统最容易说谎的地方。
     */
    @Test fun directoriesAreNotFilesAndMissingPathsSayFalse() {
        val s = storage()
        s.ensureDir("dircase")
        s.ensureDir("dircase/sub")
        s.openWrite("dircase/a.txt").use { it.write(ByteBuffer.wrap(byteArrayOf(1))) }

        assertFalse(s.exists("dircase/sub"))
        assertFalse(s.delete("dircase/sub"))
        assertTrue(File(rootDir, "dircase/sub").isDirectory)

        assertFalse(s.exists("dircase/nope.txt"))
        assertFalse(s.delete("dircase/nope.txt"))

        assertTrue(s.list("dircase-not-there-at-all").isEmpty())
    }

    /**
     * 覆盖已存在的目标：`move` 返回 true 之后 `to` 换成了 `from` 的内容、`from` 消失，
     * 而源不存在时 `move` 返回 false 且目标保持原样——Task 8 只在 true 时才删原文件。
     */
    @Test fun moveReplacesExistingTargetAndIgnoresMissingSource() {
        val s = storage()
        s.ensureDir("mv")
        s.openWrite("mv/old.jpg").use { it.write(ByteBuffer.wrap(byteArrayOf(9))) }
        s.openWrite("mv/new.jpg").use { it.write(ByteBuffer.wrap(byteArrayOf(7))) }

        assertTrue(s.move("mv/new.jpg", "mv/old.jpg"))
        assertFalse(s.exists("mv/new.jpg"))
        assertTrue(s.exists("mv/old.jpg"))
        assertArrayEquals(byteArrayOf(7), readAll(s, "mv/old.jpg"))

        assertFalse(s.move("mv/nope.jpg", "mv/old.jpg"))
        assertTrue(s.exists("mv/old.jpg"))
        assertArrayEquals(byteArrayOf(7), readAll(s, "mv/old.jpg"))
    }

    /** 每次打开都从 0 字节开始（截断式），旧内容不会跟在后面。 */
    @Test fun openWriteTruncatesExistingFile() {
        val s = storage()
        s.ensureDir("trunc")
        s.openWrite("trunc/a.jpg").use { it.write(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))) }
        s.openWrite("trunc/a.jpg").use { it.write(ByteBuffer.wrap(byteArrayOf(5))) }
        assertArrayEquals(byteArrayOf(5), readAll(s, "trunc/a.jpg"))
    }

    /**
     * 落盘对读者的最坏情况：真实文件实现里，未 close 的写入在磁盘上是可见的半截文件
     * （契约因此只承诺 close 之后的结果，见 [FileStorage] 的类注释）。
     * 这里钉的是反面——close 返回后内容必须完整，因为 Task 8 就靠这一步发布产物。
     */
    @Test fun writtenBytesAreCompleteAfterClose() {
        val s = storage()
        s.ensureDir("vis")
        val channel = s.openWrite("vis/a.jpg")
        channel.write(ByteBuffer.wrap(byteArrayOf(1, 2)))
        channel.close()
        assertArrayEquals(byteArrayOf(1, 2), readAll(s, "vis/a.jpg"))
        // 重复 close 是幂等 no-op，不许追加或损坏已落盘的数据
        channel.close()
        assertArrayEquals(byteArrayOf(1, 2), readAll(s, "vis/a.jpg"))
    }
}
