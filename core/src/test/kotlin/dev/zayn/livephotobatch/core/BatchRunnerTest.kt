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

    @Test fun `malformed xmp is a failure not a skip and keeps the originals`() {
        // Task 13 新增的失败原因：源 XMP 读不动。它绝不能进 SKIPPABLE——"跳过"在 UI 上是
        // "这张已经是动态照片了"，而真实情况是用户的元数据我们没能保住，必须报失败。
        val broken = Fixtures.app1(Fixtures.XAP, "<x:xmpmeta><unclosed>".toByteArray(Charsets.UTF_8))
        val dirty = Fixtures.jpeg(headSegments = listOf(broken, Fixtures.sof0()))
        val (runner, storage) = runnerWith("/s/a.jpg" to dirty, "/s/a.mp4" to video)
        val report = runner.run(runner.plan(options(true)), options(true), CancelToken { false }) { }
        val item = report.items.single()
        assertEquals(ItemStatus.FAILED, item.status)
        assertEquals(FailureReason.MALFORMED_XMP, item.reason)
        assertEquals(0L, item.bytes)
        assertEquals(0L, report.composedBytes)
        assertFalse(storage.exists("/t/a_live.jpg"))
        assertFalse(storage.tmpFiles.isNotEmpty())
        // 失败项不删原文件：图还在，用户换个工具还能重来
        assertTrue(storage.exists("/s/a.jpg"))
        assertTrue(storage.exists("/s/a.mp4"))
    }

    @Test fun `a gain map jpeg composes through the batch and reports real byte counts`() {
        // 端到端：真机最常见的那张输入（XMP 声明 GainMap + EOI 后躺着副图）必须报成功，
        // 而 bytes 要等于**含尾巴**的产物长度——UI 的"已写出 N MB"读的就是它。
        val dirty = Fixtures.gainMapJpeg()
        val (runner, storage) = runnerWith("/s/a.jpg" to dirty, "/s/a.mp4" to video)
        val report = runner.run(runner.plan(options()), options(), NoCancel) { }
        val item = report.items.single()
        assertEquals(ItemStatus.SUCCESS, item.status)
        val out = storage.bytesOf("/t/a_live.jpg")
        assertEquals(out.size.toLong(), item.bytes)
        assertEquals(out.size.toLong(), report.composedBytes)
        val text = out.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("<Item:Semantic>GainMap</Item:Semantic>"), "批次产物把副图声明弄丢了")
        assertTrue(text.contains("<Item:Semantic>MotionPhoto</Item:Semantic>"), "批次产物没写成动态照片")
        assertTrue(text.contains("hdrgm:Version=\"1.0\""), "HDR 版本声明被丢了")
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

    @Test fun `a source that vanished after the plan is reported as io error and does not abort the batch`() {
        // UI 先 plan() 预览、再把同一个 PlanResult 交给 run()。这中间另一个进程（相册同步、
        // 用户自己在文件管理器里删）可以把源文件移走：BatchStorage.open 的契约就是此时抛
        // FileNotFoundException（Task 7），composeOne 的 catch 分支就是为它写的。
        val (runner, storage) = runnerWith(
            "/s/a.jpg" to image, "/s/a.mp4" to video, "/s/b.jpg" to image, "/s/b.mp4" to video,
        )
        val plan = runner.plan(options())
        // 夹具前提：a 在删掉之前确实配成了对（否则它只是另一条 NO_PAIR 行，catch 分支根本没跑）。
        assertEquals(listOf("a", "b"), plan.jobs.map { it.stem })
        assertTrue(storage.delete("/s/a.jpg"), "夹具没能把源图删掉")
        val seen = mutableListOf<ItemResult>()
        val report = runner.run(plan, options(true), NoCancel) { seen += it }
        val a = report.items.first { it.stem == "a" }
        assertEquals(ItemStatus.FAILED, a.status)
        assertEquals(FailureReason.IO_ERROR, a.reason)
        assertEquals(0L, a.bytes)
        assertEquals(emptyList<String>(), a.deleteFailures)
        assertEquals(emptyList<String>(), a.deletedSources)
        // spec §9：一项炸掉不算批次炸掉。b 照常提交，连删除记账都不受影响。
        assertEquals(ItemStatus.SUCCESS, report.items.first { it.stem == "b" }.status)
        assertEquals(listOf("/s/b.jpg", "/s/b.mp4"), report.items.first { it.stem == "b" }.deletedSources)
        assertEquals(listOf("a", "b"), seen.map { it.stem })
        assertEquals(2, report.items.size)
        assertEquals(outputBytes.toLong(), report.composedBytes)
        assertTrue(storage.exists("/t/b_live.jpg"))
        assertFalse(storage.exists("/t/a_live.jpg"))
        // 没提交的项不许删原文件：视频还躺在源目录（图是被"别的进程"删的，不算我们删的）。
        assertTrue(storage.exists("/s/a.mp4"))
        // open 抛之前 openWrite 已经把 0 字节的 .part 落进了目录（内存实现的 close 语义），
        // 所以这条断言真的在检查 catch 分支执行了 §7.5 的清理。
        assertFalse(storage.tmpFiles.isNotEmpty(), "catch 分支留下了半截 .part：${storage.tmpFiles}")
    }

    @Test fun `a throwing tmp cleanup cannot escape the run`() {
        // 清理自己也是文件操作：源消失（open 抛）之后 catch 要删 .part，而这一次删除也抛。
        // run 的契约是**必定**返回 BatchReport，绝不把异常递给 UI（spec §9）。
        val storage = RecordingStorage(InMemoryStorage(mapOf(
            "/s/a.jpg" to image, "/s/a.mp4" to video, "/s/b.jpg" to image, "/s/b.mp4" to video,
        )))
        val runner = BatchRunner(storage)
        val plan = runner.plan(options())
        // 夹具前提：删图之前 a 确实是个作业（删完之后它才会变成 NO_PAIR，那就不是本测试要跑的路径了）。
        assertEquals(listOf("a", "b"), plan.jobs.map { it.stem })
        assertTrue(storage.memory.delete("/s/a.jpg"), "夹具没能把源图删掉")
        storage.deleteThrows = setOf("/t/.tmp/a_live.jpg.part")
        val report = runner.run(plan, options(), NoCancel) { }
        val a = report.items.first { it.stem == "a" }
        assertEquals(ItemStatus.FAILED, a.status)
        assertEquals(FailureReason.IO_ERROR, a.reason)
        assertEquals(0L, a.bytes)
        assertEquals(
            listOf("delete:/t/.tmp/a_live.jpg.part=throw"),
            storage.calls.filter { it.startsWith("delete:/t/.tmp/") },
        )
        // 后面的项照跑，报告照发。
        assertEquals(ItemStatus.SUCCESS, report.items.first { it.stem == "b" }.status)
        assertEquals(outputBytes.toLong(), report.composedBytes)
        // 清不掉的半截文件留在 .tmp 里——这是"批次继续"的代价（下次批次会覆盖它），
        // 不是新故障，也不许反过来把整批掀翻。
        assertEquals(listOf("/t/.tmp/a_live.jpg.part"), storage.memory.tmpFiles)
    }

    @Test fun `a throwing tmp cleanup does not rewrite a skipped item into a failure`() {
        // 同一个守卫也管校验被拒的那条出路：ALREADY_MOTION_PHOTO 是 ruling 1 里唯一的"跳过"，
        // 清 .part 时抛出故障绝不能借道外层 catch 把它改写成 FAILED(IO_ERROR)——
        // 那是把用户已经完成的文件说成失败。
        val embedded = Fixtures.app1(Fixtures.XAP, XmpPacket.motionPhoto(10L))
        val dirty = Fixtures.jpeg(headSegments = listOf(embedded, Fixtures.sof0()))
        val storage = RecordingStorage(InMemoryStorage(mapOf(
            "/s/a.jpg" to dirty, "/s/a.mp4" to video, "/s/b.jpg" to image, "/s/b.mp4" to video,
        )))
        storage.deleteThrows = setOf("/t/.tmp/a_live.jpg.part")
        val runner = BatchRunner(storage)
        val report = runner.run(runner.plan(options(true)), options(true), NoCancel) { }
        val a = report.items.first { it.stem == "a" }
        assertEquals(ItemStatus.SKIPPED, a.status, "清理半截 .part 抛出来，把跳过改写成失败")
        assertEquals(FailureReason.ALREADY_MOTION_PHOTO, a.reason)
        assertEquals(0L, a.bytes)
        // a 的原文件一个都不许动（跳过项没提交），b 照常提交并删干净。
        assertTrue(storage.exists("/s/a.jpg"))
        assertTrue(storage.exists("/s/a.mp4"))
        assertEquals(emptyList<String>(), a.deletedSources)
        assertEquals(emptyList<String>(), a.deleteFailures)
        val b = report.items.first { it.stem == "b" }
        assertEquals(ItemStatus.SUCCESS, b.status)
        assertEquals(listOf("/s/b.jpg", "/s/b.mp4"), b.deletedSources)
        assertEquals(outputBytes.toLong(), report.composedBytes)
        assertEquals(listOf("/t/.tmp/a_live.jpg.part"), storage.memory.tmpFiles)
    }

    @Test fun `a throwing source delete keeps the committed success and only adds a warning`() {
        // rename 已经落地 = 本项提交完成。此后删除源文件抛出来（文件被别的进程占着），
        // 只允许多一条 deleteFailures 警告，绝不允许把 SUCCESS 改写成 FAILED(IO_ERROR)、
        // bytes 归零、deletedSources 清空——那等于报告说"什么都没写出来"，而产物明明在盘上。
        val storage = RecordingStorage(InMemoryStorage(mapOf(
            "/s/a.jpg" to image, "/s/a.mp4" to video, "/s/b.jpg" to image, "/s/b.mp4" to video,
        )))
        storage.deleteThrows = setOf("/s/a.mp4")
        val runner = BatchRunner(storage)
        val report = runner.run(runner.plan(options(true)), options(true), NoCancel) { }
        val a = report.items.first { it.stem == "a" }
        assertEquals(ItemStatus.SUCCESS, a.status, "删除抛出把已提交的本项改写成 FAILED")
        assertNull(a.reason)
        assertEquals(outputBytes.toLong(), a.bytes)
        assertEquals(listOf("a.mp4"), a.deleteFailures)
        assertEquals(listOf("/s/a.jpg"), a.deletedSources)
        assertTrue(storage.exists("/s/a.mp4"))
        assertFalse(storage.exists("/s/a.jpg"))
        assertTrue(storage.exists("/t/a_live.jpg"))
        // 抛出故障同样不截断批次：b 照常提交、照常删干净。
        val b = report.items.first { it.stem == "b" }
        assertEquals(ItemStatus.SUCCESS, b.status)
        assertEquals(listOf("/s/b.jpg", "/s/b.mp4"), b.deletedSources)
        assertEquals(2, report.items.size)
        assertEquals(outputBytes * 2L, report.composedBytes)
        assertFalse(storage.memory.tmpFiles.isNotEmpty())
    }

    @Test fun `cancel stops the unpaired pre-loop instead of emitting every no-pair row`() {
        // 两张图都没配对 → jobs 为空，唯一会跑的循环就是 NO_PAIR 预扫描。
        // 全局约束"取消标志在每一项之前检查"管的也是这些行：它们同样进 items、同样推给 UI。
        val (runner, storage) = runnerWith("/s/a.jpg" to image, "/s/b.jpg" to image)
        val plan = runner.plan(options())
        assertEquals(2, plan.unpaired.size)
        assertEquals(0, plan.jobs.size)
        val seen = mutableListOf<ItemResult>()
        val report = runner.run(plan, options(), CancelToken { true }) { seen += it }
        assertTrue(report.cancelled, "取消在 NO_PAIR 预扫描里没生效，报告谎称整批跑完")
        assertEquals(emptyList<ItemResult>(), report.items)
        assertEquals(0, seen.size)
        assertEquals(0L, report.composedBytes)
        assertFalse(storage.tmpFiles.isNotEmpty())
    }

    @Test fun `a batch cancelled before the first item reports nothing and writes nothing`() {
        // 混合计划（NO_PAIR 行 + 作业）开局就取消：一行都不许报，一个文件都不许写。
        val storage = RecordingStorage(InMemoryStorage(mapOf(
            "/s/a.jpg" to image, "/s/b.jpg" to image, "/s/b.mp4" to video,
        )))
        val runner = BatchRunner(storage)
        val seen = mutableListOf<ItemResult>()
        val report = runner.run(runner.plan(options()), options(), CancelToken { true }) { seen += it }
        assertTrue(report.cancelled)
        assertEquals(emptyList<ItemResult>(), report.items, "取消之后还吐出了 NO_PAIR 行")
        assertEquals(0, seen.size)
        assertEquals(0L, report.composedBytes)
        assertFalse(storage.calls.any { it.startsWith("openWrite") }, "取消之后还在写文件：${storage.calls}")
        assertFalse(storage.memory.tmpFiles.isNotEmpty())
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

        /**
         * 这些路径上的 delete 直接抛 `IOException`。[BatchStorage.delete] 的契约只返回 true/false，
         * 但 `run` "必定返回报告" 的总契约不该把安全性押在实现方守约上（Task 8 报告 Concern 1 正是
         * 这个分歧点，Task 9 的 FileStorage 也照样要求它返回 false）：所以这里偏偏抛，
         * 证明异常递不到调用方手里。
         */
        var deleteThrows: Set<String> = emptySet()

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
            if (filePath in deleteThrows) {
                calls += "delete:$filePath=throw"
                throw java.io.IOException(filePath)
            }
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
