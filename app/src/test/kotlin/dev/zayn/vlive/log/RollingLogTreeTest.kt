package dev.zayn.vlive.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import timber.log.Timber

/**
 * Local JVM test for [RollingLogTree] 的那半件"要碰磁盘"的事：**接着往哪个文件里写**。
 *
 * 不需要 Robolectric：这个类只用 `Log` 的几个 `static final int` 常量（编译期内联），其余全是
 * `java.io.File` + 一条后台线程，`dir` 与 `clock` 都是构造参数。唯一要绕的是 `log()` 是
 * `protected`——所以走公共入口 `Timber.plant` + `Timber.i`，这也正是生产路径。
 */
class RollingLogTreeTest {

    @JvmField
    @Rule
    val tmp = TemporaryFolder()

    @After
    fun uproot() {
        Timber.uprootAll()
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }

    /**
     * "最新"必须只有一个键。轮转决策（[planLogCleanup]）按 `lastModified` 认最新，
     * 而这个类挑"接着写哪个"时按**名字**认最新 —— 两个键一分叉，被保住"永不删"的文件
     * 和正在被写的文件就不是同一个：清理可以把当前文件从目录里抽走，之后的日志全写进
     * 一个没人认识的 inode。
     *
     * 下面两个文件的两个键互相矛盾：`log-9000` 名字大但 mtime 旧，`log-1000` 名字小但
     * mtime 新（设备时间被往回拨过就是这个样子：名字来自被拨过的时钟，mtime 是文件系统自己的）。
     * 两边都必须认 `log-1000`。
     */
    @Test
    fun `the tree keeps writing into the file the cleaner also protects`() {
        val dir = tmp.newFolder("logs")
        val byMtime = File(dir, "log-1000.txt").apply {
            writeBytes(ByteArray(10))
            assertTrue(setLastModified(9_000L))
        }
        val byName = File(dir, "log-9000.txt").apply {
            writeBytes(ByteArray(10))
            assertTrue(setLastModified(1_000L))
        }
        val tree = RollingLogTree(dir, maxFileBytes = 100_000L, clock = { 20_000L })
        Timber.plant(tree)

        Timber.i("one")

        assertTrue(
            "没写进 mtime 最新的那个文件：${dir.listFiles()?.map { "${it.name}=${it.length()}" } ?: emptyList<Unit>()}",
            awaitUntil { byMtime.length() > 10L },
        )
        assertEquals("名字最大的那个文件被当成当前文件写了：写进它就等于写进一个清理随时会抽走的名字",
            10L, byName.length())
    }
}
