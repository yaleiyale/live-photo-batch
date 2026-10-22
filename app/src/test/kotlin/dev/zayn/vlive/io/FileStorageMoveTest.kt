package dev.zayn.vlive.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `FileStorage.move` 的契约测试。
 *
 * 之前这条只有 `app/src/androidTest/.../FileStorageTest.kt` 碰过，而仪器化测试按
 * decisions.md §6.4 **只编译不执行** —— 等于零覆盖。`move` 是"删原文件之前最后一步落盘"，
 * 它说谎的代价是产物并不存在，而原文件已经没了。
 *
 * 路径一律传**相对**名字：`resolve` 只认 `/` 分隔符（`BatchStorage` 的契约），在 Windows 上
 * 跑 JVM 测试时绝对路径带的是 `\`，会被当成"不在 root 之下"再拼一次。androidTest 那份也是这么做的。
 */
class FileStorageMoveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun write(name: String, content: ByteArray): File =
        File(tmp.root, name).also { it.parentFile?.mkdirs(); it.writeBytes(content) }

    private fun storage() = FileStorage(tmp.root.absolutePath)

    @Test
    fun `move lands the bytes at the target and removes the source`() {
        write("a.part", byteArrayOf(1, 2, 3))

        assertTrue(storage().move("a.part", "a.jpg"))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(tmp.root, "a.jpg").readBytes())
        assertFalse(File(tmp.root, "a.part").exists())
    }

    @Test
    fun `move replaces an output that already exists`() {
        write("a.part", "NEW".toByteArray())
        val dst = write("a.jpg", "OLD-CONTENT".toByteArray())

        assertTrue(storage().move("a.part", "a.jpg"))
        assertTrue("目标必须还在，只是内容换掉了", dst.isFile)
        assertArrayEquals("NEW".toByteArray(), dst.readBytes())
        assertFalse(File(tmp.root, "a.part").exists())
    }

    @Test
    fun `a missing source fails the move and leaves the target alone`() {
        val dst = write("a.jpg", "KEEP".toByteArray())

        assertFalse(storage().move("gone.part", "a.jpg"))
        assertArrayEquals("KEEP".toByteArray(), dst.readBytes())
    }

    @Test
    fun `a directory as source fails the move instead of moving the tree`() {
        assertTrue(File(tmp.root, "dir.part").mkdirs())
        val dst = write("b.jpg", "KEEP".toByteArray())

        assertFalse(storage().move("dir.part", "b.jpg"))
        assertTrue(File(tmp.root, "dir.part").isDirectory)
        assertArrayEquals("KEEP".toByteArray(), dst.readBytes())
    }

    /**
     * 兜底路径失败时，正式名字上不许留下半截文件。
     *
     * 目标做成一个非空目录：改名与"把暂存改名到位"都会失败，而复制那一步是成功的。
     * 直接往正式名字上复制的实现会先把目标截断 —— 本条钉的就是它不许走到那一步。
     */
    @Test
    fun `a failed move leaves the existing target byte for byte intact`() {
        write("c.part", "PAYLOAD".toByteArray())
        val dstDir = File(tmp.root, "c.jpg").also { assertTrue(it.mkdirs()) }
        val inner = File(dstDir, "inner.txt").also { it.writeText("precious") }

        assertFalse(storage().move("c.part", "c.jpg"))
        assertArrayEquals("precious".toByteArray(), inner.readBytes())
        assertTrue("失败不许顺手把源删掉", File(tmp.root, "c.part").isFile)
    }
}
