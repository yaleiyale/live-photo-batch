package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.channels.WritableByteChannel

class BatchRunnerTest {
    private val video = Fixtures.mp4(300)
    private val image = Fixtures.jpeg()

    /**
     * 一次成功合成的产物长度：源图 + 新插入的 APP1 + 视频全部字节（这张夹具图没有旧 XMP 可丢）。
     * 与 MotionPhotoComposerTest 的 totalBytes 算式同一条，两个文件各自独立算，谁改了谁红。
     */
    private val outputBytes: Int =
        image.size + XmpPacket.app1Segment(video.size.toLong()).size + video.size

    private fun runnerWith(vararg entries: Pair<String, ByteArray>): Pair<BatchRunner, InMemoryStorage> {
        val storage = InMemoryStorage(entries.toMap())
        return BatchRunner(storage) to storage
    }

    private fun options(deleteOriginals: Boolean = false) =
        BatchOptions("/s", "/t", deleteOriginals)

    @Test fun `happy path writes output and reports success`() {
        val (runner, storage) = runnerWith("/s/a.jpg" to image, "/s/a.mp4" to video)
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) {}
        val item = report.items.single()
        assertEquals(ItemStatus.SUCCESS, item.status)
        assertNull(item.reason, "成功项不该带失败原因")
        // 计划 Step 1 这里写的是 300L（= 视频长度），可 Step 3 的实现填的是
        // ComposeResult.totalBytes，计划前言 T5→T8 那一行也裁定"runner 只用 totalBytes"。
        // 两者不可能同时成立，取实现一侧：bytes 是**真正写出的产物字节**，Task 11 的
        // "已写出 N MB" 累加的就是它，只算视频会少掉整张图片。断言不删，改成同一量的
        // 完整表达式：既等于算式，也等于落盘文件的实际长度。
        assertEquals(outputBytes.toLong(), item.bytes)
        assertEquals(storage.bytesOf("/t/a_live.jpg").size.toLong(), item.bytes)
        assertEquals(outputBytes.toLong(), report.composedBytes)
        assertEquals("a_live.jpg", item.outputName)
        assertEquals("a", item.stem)
        assertTrue(storage.exists("/t/a_live.jpg"))
        assertFalse(storage.tmpFiles.isNotEmpty())
    }

    @Test fun `deleting originals only happens after commit`() {
        val (runner, storage) = runnerWith("/s/a.jpg" to image, "/s/a.mp4" to video)
        runner.run(runner.plan(options(true)), options(true), CancelToken { false }) { }
        assertFalse(storage.exists("/s/a.jpg"))
        assertFalse(storage.exists("/s/a.mp4"))
        assertTrue(storage.exists("/t/a_live.jpg"))
    }

    @Test fun `tmp write then move then delete is the exact operation order`() {
        val storage = RecordingStorage(InMemoryStorage(mapOf("/s/a.jpg" to image, "/s/a.mp4" to video)))
        val runner = BatchRunner(storage)
        val report = runner.run(runner.plan(options(true)), options(true), NoCancel) { }
        assertEquals(ItemStatus.SUCCESS, report.items.single().status)
        // §7.5 + ruling 3：先建两个目录，写 .tmp/<name>.part，rename 到最终名，
        // 只有改名成功之后才允许删原文件（图先视频后）。整个时序就这一条测试钉死。
        assertEquals(
            listOf(
                "ensureDir:/t",
                "ensureDir:/t/.tmp",
                "openWrite:/t/.tmp/a_live.jpg.part",
                "move:/t/.tmp/a_live.jpg.part->/t/a_live.jpg=true",
                "delete:/s/a.jpg=true",
                "delete:/s/a.mp4=true",
            ),
            storage.trace,
        )
    }

    @Test fun `already motion photo items are skipped and keep originals`() {
        val embedded = Fixtures.app1(Fixtures.XAP, XmpPacket.motionPhoto(10L))
        val dirty = Fixtures.jpeg(headSegments = listOf(embedded, Fixtures.sof0()))
        val (runner, storage) = runnerWith("/s/a.jpg" to dirty, "/s/a.mp4" to video)
        val report = runner.run(runner.plan(options(true)), options(true), CancelToken { false }) { }
        val item = report.items.single()
        assertEquals(ItemStatus.SKIPPED, item.status)
        assertEquals(FailureReason.ALREADY_MOTION_PHOTO, item.reason)
        // 跳过项一个字节也没写：bytes 必须是 0，否则 composedBytes 与 UI 进度都会虚高。
        assertEquals(0L, item.bytes)
        assertEquals(0L, report.composedBytes)
        assertTrue(storage.exists("/s/a.jpg"))
        assertFalse(storage.exists("/t/a_live.jpg"))
        assertFalse(storage.tmpFiles.isNotEmpty())
    }

    @Test fun `unpaired inputs are reported as skipped`() {
        val (runner, _) = runnerWith("/s/a.jpg" to image)
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) { }
        val item = report.items.single()
        assertEquals(ItemStatus.SKIPPED, item.status)
        assertEquals(FailureReason.NO_PAIR, item.reason)
        assertEquals("a", item.stem)
        assertEquals(0L, item.bytes)
        assertEquals(0L, report.composedBytes)
        assertFalse(report.cancelled)
    }

    @Test fun `overwrite replaces the existing output`() {
        val (runner, storage) = runnerWith(
            "/s/a.jpg" to image, "/s/a.mp4" to video, "/t/a_live.jpg" to ByteArray(5)
        )
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) { }
        assertEquals(ItemStatus.SUCCESS, report.items.single().status)
        assertEquals(outputBytes, storage.bytesOf("/t/a_live.jpg").size)
        assertEquals(outputBytes.toLong(), report.composedBytes)
        assertFalse(storage.tmpFiles.isNotEmpty())
    }

    @Test fun `cancel stops before the next item and keeps what is done`() {
        val (runner, storage) = runnerWith(
            "/s/a.jpg" to image, "/s/a.mp4" to video, "/s/b.jpg" to image, "/s/b.mp4" to video
        )
        var seen = 0
        val report = runner.run(runner.plan(options()), options(), CancelToken { seen >= 1 }) { seen++ }
        assertTrue(report.cancelled)
        assertEquals(1, report.items.size)
        assertEquals(1, seen)
        assertTrue(storage.exists("/t/a_live.jpg"))
        assertFalse(storage.exists("/t/b_live.jpg"))
        assertFalse(storage.tmpFiles.isNotEmpty())
    }

    @Test fun `progress callback sees every item exactly once`() {
        val (runner, _) = runnerWith(
            "/s/a.jpg" to image, "/s/a.mp4" to video, "/s/b.jpg" to image, "/s/b.mp4" to video
        )
        val seen = mutableListOf<String>()
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) { seen += it.stem }
        assertEquals(listOf("a", "b"), seen)
        assertEquals(2, report.items.size)
    }

    @Test fun `target dir is created before writing`() {
        val storage = RecordingStorage(InMemoryStorage(mapOf("/s/a.jpg" to image, "/s/a.mp4" to video)))
        val runner = BatchRunner(storage)
        runner.run(runner.plan(options()), options(), NoCancel) { }
        assertTrue(storage.exists("/t/a_live.jpg"))
        val writeIndex = storage.calls.indexOfFirst { it.startsWith("openWrite") }
        // 两个目录都必须在这一次写入之前建好，且顺序是目标目录、再它的 .tmp。
        assertEquals(listOf("ensureDir:/t", "ensureDir:/t/.tmp"), storage.calls.subList(0, 2))
        assertTrue(writeIndex >= 2, "ensureDir 没有走在 openWrite 之前，或根本没有写入：${storage.calls}")
    }

    @Test fun `an in-place batch still goes through the tmp file`() {
        // 目标目录 == 源目录是 spec 允许的场景（PairPlanner KDoc 明确支持原地生成）。
        // .part 必须落在 targetDir/.tmp 而不是源目录根，否则原地批次会把半截文件当输入。
        val storage = RecordingStorage(InMemoryStorage(mapOf("/s/a.jpg" to image, "/s/a.mp4" to video)))
        val runner = BatchRunner(storage)
        val inplace = BatchOptions("/s", "/s", true)
        val report = runner.run(runner.plan(inplace), inplace, NoCancel) { }
        assertEquals(ItemStatus.SUCCESS, report.items.single().status)
        assertEquals(outputBytes.toLong(), report.composedBytes)
        // 原地批次结束后源目录里只剩产物：原图与视频被删，`.part` 从不落在源目录根上。
        assertEquals(listOf(DirEntry("a_live.jpg", outputBytes.toLong())), storage.list("/s"))
        assertEquals(
            listOf(
                "ensureDir:/s",
                "ensureDir:/s/.tmp",
                "openWrite:/s/.tmp/a_live.jpg.part",
                "move:/s/.tmp/a_live.jpg.part->/s/a_live.jpg=true",
                "delete:/s/a.jpg=true",
                "delete:/s/a.mp4=true",
            ),
            storage.trace,
        )
    }

    @Test fun `unpaired item does not abort the batch`() {
        val (runner, _) = runnerWith("/s/a.jpg" to image, "/s/b.jpg" to image, "/s/b.mp4" to video)
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) { }
        assertEquals(ItemStatus.SKIPPED, report.items.first { it.stem == "a" }.status)
        assertEquals(ItemStatus.SUCCESS, report.items.first { it.stem == "b" }.status)
        assertEquals(2, report.items.size)
        assertEquals(outputBytes.toLong(), report.composedBytes)
    }

    @Test fun `failed composition does not abort the batch and leaves no tmp file`() {
        val (runner, storage) = runnerWith(
            "/s/a.jpg" to image, "/s/a.mp4" to ByteArray(2048),
            "/s/b.jpg" to image, "/s/b.mp4" to video,
        )
        val seen = mutableListOf<ItemResult>()
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) { seen += it }
        assertEquals(ItemStatus.FAILED, report.items.first { it.stem == "a" }.status)
        assertEquals(ItemStatus.SUCCESS, report.items.first { it.stem == "b" }.status)
        assertEquals(2, seen.size)
        assertEquals(listOf("a", "b"), seen.map { it.stem })
        // 失败项不计入 composedBytes，也不留下 .part：§7.5 的清理不看字节数，无条件删。
        assertEquals(outputBytes.toLong(), report.composedBytes)
        assertFalse(storage.tmpFiles.isNotEmpty())
        // 坏视频这一对没被删：删除只属于成功项。
        assertTrue(storage.exists("/s/a.jpg"))
        assertTrue(storage.exists("/s/a.mp4"))
    }

    @Test fun `only already-motion-photo counts as skipped`() {
        // ruling 1：SKIPPABLE 只剩 ALREADY_MOTION_PHOTO。NOT_MP4 与 MALFORMED_JPEG 都必须
        // 报 FAILED —— Task 2 的 Cursor 把 IOException 折成 Malformed，真磁盘故障绝不能
        // 被分进"跳过"里。两条都放进阶梯里跑，还能顺带证明连续失败也不中断批次。
        val (runner, storage) = runnerWith(
            "/s/a.jpg" to image, "/s/a.mp4" to ByteArray(2048),
            "/s/b.jpg" to image.copyOf(9), "/s/b.mp4" to video,
        )
        val report = runner.run(runner.plan(options(true)), options(true), CancelToken { false }) { }
        val failed = report.items.sortedBy { it.stem }
        assertEquals(listOf(ItemStatus.FAILED, ItemStatus.FAILED), failed.map { it.status })
        assertEquals(
            listOf(FailureReason.NOT_MP4, FailureReason.MALFORMED_JPEG),
            failed.map { it.reason },
        )
        assertEquals(0L, report.composedBytes)
        assertTrue(storage.exists("/s/a.mp4"))
        assertTrue(storage.exists("/s/b.jpg"))
        assertFalse(storage.tmpFiles.isNotEmpty())
    }

    @Test fun `move failure is reported as io error and keeps the originals`() {
        val storage = RecordingStorage(InMemoryStorage(mapOf("/s/a.jpg" to image, "/s/a.mp4" to video)))
        storage.moveResult = false
        val runner = BatchRunner(storage)
        val report = runner.run(runner.plan(options(true)), options(true), NoCancel) { }
        val item = report.items.single()
        assertEquals(ItemStatus.FAILED, item.status)
        assertEquals(FailureReason.IO_ERROR, item.reason)
        assertEquals(0L, item.bytes)
        assertEquals(0L, report.composedBytes)
        // rename 没成功就等于什么都没发生：原文件留着，最终名上没有产物，.part 被清掉。
        assertTrue(storage.exists("/s/a.jpg"))
        assertTrue(storage.exists("/s/a.mp4"))
        assertFalse(storage.exists("/t/a_live.jpg"))
        assertFalse(storage.calls.any { it.startsWith("delete:/s/") }, "改名失败却删了原文件")
        assertFalse(storage.memory.tmpFiles.isNotEmpty())
    }

    @Test fun `a source that survives deletion becomes a delete failure warning`() {
        val storage = RecordingStorage(InMemoryStorage(mapOf("/s/a.jpg" to image, "/s/a.mp4" to video)))
        storage.deleteRefused = setOf("/s/a.mp4")
        val runner = BatchRunner(storage)
        val item = runner.run(runner.plan(options(true)), options(true), NoCancel) { }.items.single()
        // spec §7.5：删除失败不改变本项的成功状态，但要分别记下
        // "没删掉的名字"（deleteFailures，给用户看警告）和"真删掉的绝对路径"
        // （deletedSources，ruling 4：Task 11 要把它交给 MediaStore 收敛索引）。
        assertEquals(ItemStatus.SUCCESS, item.status)
        assertEquals(listOf("a.mp4"), item.deleteFailures)
        assertEquals(listOf("/s/a.jpg"), item.deletedSources)
        assertEquals(outputBytes.toLong(), item.bytes)
        assertTrue(storage.exists("/s/a.mp4"))
        assertFalse(storage.exists("/s/a.jpg"))
    }

    /**
     * 测试专用装饰器：把 runner 用到的每一步目录操作记成字符串，其余全部透传给内存实现。
     * `BatchRunner` 只认 [BatchStorage]，所以这里能观察到的也就是它真的能做的事。
     */
    private class RecordingStorage(val memory: InMemoryStorage) : BatchStorage by memory {
        val calls = ArrayList<String>()
        var moveResult = true

        /** 这些路径上的 delete 一律报告失败，且不真删——模拟"文件被别的进程占着"。 */
        var deleteRefused: Set<String> = emptySet()

        /** 只保留结构性动作，去掉 open 噪声，用来断言精确时序。 */
        val trace: List<String> get() = calls.filter { !it.startsWith("open:") }

        override fun ensureDir(dirPath: String) {
            calls += "ensureDir:$dirPath"
            memory.ensureDir(dirPath)
        }

        override fun open(filePath: String): ChannelSource {
            calls += "open:$filePath"
            return memory.open(filePath)
        }

        override fun openWrite(filePath: String): WritableByteChannel {
            calls += "openWrite:$filePath"
            return memory.openWrite(filePath)
        }

        override fun move(from: String, to: String): Boolean {
            val ok = if (moveResult) memory.move(from, to) else false
            calls += "move:$from->$to=$ok"
            return ok
        }

        override fun delete(filePath: String): Boolean {
            if (filePath in deleteRefused) {
                calls += "delete:$filePath=false"
                return false
            }
            val ok = memory.delete(filePath)
            calls += "delete:$filePath=$ok"
            return ok
        }
    }
}
