package dev.zayn.vlive.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM test (`app/src/test`) for the log-retention rules — the pure half of
 * [LogRotation]. [RollingLogTree] only ever lists the directory, asks this function and deletes
 * what it said, so pinning the decision is what protects the "别把存储写满" requirement.
 */
class LogRotationTest {

    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_700_000_000_000L

    private fun file(name: String, bytes: Long, ageDays: Long) =
        LogFile(name, bytes, now - ageDays * day)

    private fun caps(
        maxFiles: Int = 4,
        maxAgeDays: Long = 7,
        maxTotalBytes: Long = 2L * 1024L * 1024L,
    ): (List<LogFile>) -> List<LogFile> = { files ->
        planLogCleanup(
            files = files,
            nowMs = now,
            maxFiles = maxFiles,
            maxAgeMs = maxAgeDays * day,
            maxTotalBytes = maxTotalBytes,
        )
    }

    @Test fun `nothing over the caps is kept whole`() {
        val files = listOf(file("log-001.txt", 100, 0), file("log-002.txt", 100, 1))
        assertEquals(emptyList<LogFile>(), caps()(files))
    }

    @Test fun `an empty directory needs no cleanup`() {
        assertEquals(emptyList<LogFile>(), caps()(emptyList()))
    }

    @Test fun `expired files go first and the newest survives even when stale`() {
        val old = file("log-001.txt", 100, 30)
        val alsoOld = file("log-002.txt", 100, 20)
        val newestButStillOld = file("log-003.txt", 100, 10)
        val doomed = caps(maxAgeDays = 7)(listOf(old, alsoOld, newestButStillOld))
        // 当前文件永不删 —— 哪怕它自己也过期了，删它等于丢掉最近的上下文。
        assertEquals(listOf(old, alsoOld), doomed)
    }

    @Test fun `too many files drops the oldest`() {
        // 名字里的序号与时间同序（log-001 最旧、log-006 最新），这样"删最旧的"才看得见。
        val files = (1..6).map { file("log-00$it.txt", 100, (7 - it).toLong()) }
        val doomed = caps(maxFiles = 4)(files)
        assertEquals(listOf("log-001.txt", "log-002.txt"), doomed.map { it.name })
    }

    @Test fun `total bytes drives deletion even under the file cap`() {
        val files = listOf(
            file("log-001.txt", 900_000, 3),
            file("log-002.txt", 900_000, 2),
            file("log-003.txt", 900_000, 1),
        )
        // 2.7 MB 总量、上限 2 MB、只有 3 个文件：数量与年龄都合规，只有体积超了。
        val doomed = caps(maxFiles = 4, maxAgeDays = 7, maxTotalBytes = 2_000_000)(files)
        assertEquals(listOf("log-001.txt"), doomed.map { it.name })
    }

    @Test fun `the byte budget stops at the newest file`() {
        val only = file("log-001.txt", 10_000_000, 0)
        assertEquals(emptyList<LogFile>(), caps(maxTotalBytes = 100)(listOf(only)))
    }

    @Test fun `a zero file cap still keeps the current log`() {
        val files = listOf(file("log-001.txt", 100, 3), file("log-002.txt", 100, 1))
        val doomed = caps(maxFiles = 0)(files)
        assertEquals(listOf("log-001.txt"), doomed.map { it.name })
    }

    /** Deletion order is oldest-first, so a partial failure can never leave a hole in the middle. */
    @Test fun `doomed files come back oldest first`() {
        val files = listOf(
            file("log-003.txt", 100, 1),
            file("log-001.txt", 100, 40),
            file("log-002.txt", 100, 30),
        )
        val doomed = caps(maxFiles = 1, maxAgeDays = 7)(files)
        assertEquals(listOf("log-001.txt", "log-002.txt"), doomed.map { it.name })
        assertTrue(doomed.all { it.name != "log-003.txt" })
    }
}
