package dev.zayn.vlive.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class DeleteJournalTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 24L * 60 * 60 * 1000

    private class Clock(val now: AtomicLong = AtomicLong(1_700_000_000_000)) {
        operator fun invoke(): Long = now.get()
    }

    private fun journal(dir: File, clock: Clock, maxAgeMs: Long = 100 * day) =
        DeleteJournal(dir, maxFiles = 5, maxAgeMs = maxAgeMs, maxTotalBytes = 1_000_000, clock = clock::invoke)

    @Test
    fun `recorded paths come back in the order they were deleted`() {
        val j = journal(tmp.root, Clock())
        j.record(1000L, "IMG_a_MP.jpg", listOf("/s/IMG_a.jpg", "/s/IMG_a.mp4"))
        j.record(1000L, "IMG_b_MP.jpg", listOf("/s/IMG_b.jpg", "/s/IMG_b.mp4"))

        assertEquals(
            listOf("/s/IMG_a.jpg", "/s/IMG_a.mp4", "/s/IMG_b.jpg", "/s/IMG_b.mp4"),
            j.deletedPaths(1000L),
        )
    }

    @Test
    fun `a batch that deleted nothing leaves no file behind`() {
        val j = journal(tmp.root, Clock())
        j.record(2000L, "IMG_a_MP.jpg", emptyList())

        assertEquals(emptyList<String>(), j.deletedPaths(2000L))
        assertFalse("没删东西的批次不该在凭据里占一个文件", DeleteJournal.nameOf(2000L).let { File(tmp.root, it).exists() })
    }

    @Test
    fun `each batch is its own file and reads back only its own rows`() {
        val j = journal(tmp.root, Clock())
        j.record(3000L, "IMG_a_MP.jpg", listOf("/s/IMG_a.jpg"))
        j.record(4000L, "IMG_b_MP.jpg", listOf("/s/IMG_b.jpg"))

        assertEquals(listOf("/s/IMG_a.jpg"), j.deletedPaths(3000L))
        assertEquals(listOf("/s/IMG_b.jpg"), j.deletedPaths(4000L))
        assertTrue(File(tmp.root, DeleteJournal.nameOf(3000L)).isFile)
        assertTrue(File(tmp.root, DeleteJournal.nameOf(4000L)).isFile)
    }

    /**
     * 时间上限是选定过的策略，不是疏漏——但它按**文件整体**淘汰，不能把一批劈成半截。
     *
     * 两条真实语义一起决定了这个测试为什么需要三批：清理发生在写入**之前**，而
     * `planLogCleanup` 永远放过最新的那个文件。所以只写两批时，旧的那一批恰恰是"此刻最新"，
     * 它要等到第三批落盘的那次清理才轮到被淘汰。
     */
    @Test
    fun `an expired batch file is pruned whole`() {
        val clock = Clock()
        val j = journal(tmp.root, clock, maxAgeMs = 30 * day)
        j.record(5000L, "IMG_old_MP.jpg", listOf("/s/IMG_old.jpg"))
        // prune 比的是文件系统 mtime，不是注入时钟，所以这里真的去拨文件的时间戳。
        val old = File(tmp.root, DeleteJournal.nameOf(5000L))
        assertTrue(old.setLastModified(clock.now.get() - 40 * day))

        clock.now.addAndGet(40 * day)
        j.record(6000L, "IMG_mid_MP.jpg", listOf("/s/IMG_mid.jpg"))
        assertEquals("旧文件此刻是最新的那个，按规则放过", listOf("/s/IMG_old.jpg"), j.deletedPaths(5000L))

        j.record(7000L, "IMG_new_MP.jpg", listOf("/s/IMG_new.jpg"))

        assertEquals(emptyList<String>(), j.deletedPaths(5000L))
        assertEquals(listOf("/s/IMG_mid.jpg"), j.deletedPaths(6000L))
        assertEquals(listOf("/s/IMG_new.jpg"), j.deletedPaths(7000L))
    }

    @Test
    fun `too many batch files drops the oldest first`() {
        val clock = Clock()
        val j = journal(tmp.root, clock)
        for (i in 1..8) {
            clock.now.addAndGet(1000)
            j.record(1_000_000L + i, "IMG_$i", listOf("/s/IMG_$i.jpg"))
        }
        assertEquals("最旧的两批先被淘汰", emptyList<String>(), j.deletedPaths(1_000_001L))
        assertEquals(emptyList<String>(), j.deletedPaths(1_000_002L))
        assertEquals("最新那批永远不许被清掉", listOf("/s/IMG_8.jpg"), j.deletedPaths(1_000_008L))
        val kept = (1..8).count { j.deletedPaths(1_000_000L + it).isNotEmpty() }
        assertTrue("清理在写入之前跑一轮，所以留住的不会超过 maxFiles + 1：$kept", kept <= 6)
    }

    @Test
    fun `a torn line is skipped instead of throwing`() {
        File(tmp.root, DeleteJournal.nameOf(7000L)).apply {
            parentFile?.mkdirs()
            writeText("1\t7000\tIMG_a_MP.jpg\t2\t/s/IMG_a.jpg\n")   // 记 2 条只写了 1 条
            appendText("garbage\n")
            appendText("1\t7000\tIMG_b_MP.jpg\t1\t/s/IMG_b.jpg\n")
        }

        assertEquals(listOf("/s/IMG_a.jpg", "/s/IMG_b.jpg"), journal(tmp.root, Clock()).deletedPaths(7000L))
    }

    /** 写不进只是丢记录：目录位置被一个普通文件占了，`mkdirs` 必然失败。 */
    @Test
    fun `an unwritable directory swallows the failure`() {
        val blocker = tmp.newFile("not-a-dir")

        journal(blocker, Clock()).record(8000L, "IMG_a_MP.jpg", listOf("/s/IMG_a.jpg"))

        assertEquals(emptyList<String>(), journal(blocker, Clock()).deletedPaths(8000L))
    }
}
