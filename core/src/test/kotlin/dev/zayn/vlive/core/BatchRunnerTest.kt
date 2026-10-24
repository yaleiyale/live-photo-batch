package dev.zayn.vlive.core

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

    /** 字节层判据要用到段位置：产物与源片都重扫一遍，谁也不许硬编码。 */
    private fun scan(bytes: ByteArray): JpegStructure =
        (JpegScanner.scan(java.nio.channels.Channels.newChannel(java.io.ByteArrayInputStream(bytes)))
            as JpegScanResult.Parsed).structure

    private fun options(deleteOriginals: Boolean = false) =
        BatchOptions("/s", "/t", deleteOriginals)

    @Test fun `happy path writes output and reports success`() {
        val (runner, storage) = runnerWith("/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video)
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) {}
        val item = report.items.single()
        assertEquals(ItemStatus.SUCCESS, item.status)
        assertNull(item.reason, "成功项不该带失败原因")
        // item.bytes 是**真正写出的产物字节**（源图 + 新 APP1 + 视频），不是视频长度：
        // "已写出 N MB" 累加的就是它，只算视频会少掉整张图片。
        assertEquals(outputBytes.toLong(), item.bytes)
        assertEquals(storage.bytesOf("/t/IMG_a_MP.jpg").size.toLong(), item.bytes)
        assertEquals(outputBytes.toLong(), report.composedBytes)
        assertEquals("IMG_a_MP.jpg", item.outputName)
        assertEquals("IMG_a", item.stem)
        assertTrue(storage.exists("/t/IMG_a_MP.jpg"))
        assertFalse(storage.tmpFiles.isNotEmpty())
    }

    @Test fun `deleting originals only happens after commit`() {
        val (runner, storage) = runnerWith("/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video)
        runner.run(runner.plan(options(true)), options(true), CancelToken { false }) { }
        assertFalse(storage.exists("/s/IMG_a.jpg"))
        assertFalse(storage.exists("/s/IMG_a.mp4"))
        assertTrue(storage.exists("/t/IMG_a_MP.jpg"))
    }

    @Test fun `a refused source keeps both of its originals`() {
        val two = Fixtures.jpeg(
            headSegments = listOf(
                Fixtures.app1(Fixtures.XAP, Fixtures.xmpPacket),
                Fixtures.app1(Fixtures.XAP, Fixtures.ascii("<x:xmpmeta/>")),
                Fixtures.sof0(),
            ),
        )
        val (runner, storage) = runnerWith("/s/IMG_a.jpg" to two, "/s/IMG_a.mp4" to video)
        val report = runner.run(runner.plan(options(true)), options(true), CancelToken { false }) {}
        val item = report.items.single()
        assertEquals(ItemStatus.FAILED, item.status)
        assertEquals(FailureReason.DUPLICATE_XMP_UNSUPPORTED, item.reason)
        assertTrue(storage.exists("/s/IMG_a.jpg"), "拒绝处理不等于删除")
        assertTrue(storage.exists("/s/IMG_a.mp4"), "拒绝处理不等于删除")
        assertFalse(storage.exists("/t/IMG_a_MP.jpg"))
        assertFalse(storage.tmpFiles.isNotEmpty())
    }

    /**
     * 复检是删除前最后一道门：写下去的那份要**读回来**验过才许 move、才许碰原文件。
     * 下面的篡改只作用于"读回来的 .part"，写进去的字节是好的——绕过前置校验的 composer
     * 回归必须在这里被拦住，而不是变成一次不可逆的删除。
     */
    private class Tampering(val base: InMemoryStorage, val rewrite: (ByteArray) -> ByteArray) :
        BatchStorage by base {
        override fun open(filePath: String): ChannelSource {
            if (!filePath.endsWith(".part")) return base.open(filePath)
            return ByteArraySource(rewrite(base.bytesOf(filePath)))
        }
    }

    @Test fun `a product carrying a second xmp segment is not committed`() {
        val extra = Fixtures.app1(Fixtures.XAP, Fixtures.ascii("<x:xmpmeta/>"))
        val base = InMemoryStorage(mapOf("/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video))
        val runner = BatchRunner(Tampering(base) { it.copyOfRange(0, 2) + extra + it.copyOfRange(2, it.size) })
        val item = runner.run(runner.plan(options(true)), options(true), CancelToken { false }) {}.items.single()
        assertEquals(ItemStatus.FAILED, item.status)
        assertEquals(FailureReason.OUTPUT_NOT_VERIFIED, item.reason)
        assertTrue(base.exists("/s/IMG_a.jpg"), "复检没过不等于删除")
        assertTrue(base.exists("/s/IMG_a.mp4"), "复检没过不等于删除")
        assertFalse(base.exists("/t/IMG_a_MP.jpg"))
        assertFalse(base.tmpFiles.isNotEmpty())
    }

    @Test fun `a product shorter than the reported byte count is not committed`() {
        val base = InMemoryStorage(mapOf("/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video))
        val runner = BatchRunner(Tampering(base) { it.copyOf(it.size - 10) })
        val item = runner.run(runner.plan(options(true)), options(true), CancelToken { false }) {}.items.single()
        assertEquals(ItemStatus.FAILED, item.status)
        assertEquals(FailureReason.OUTPUT_NOT_VERIFIED, item.reason)
        assertTrue(base.exists("/s/IMG_a.jpg"))
        assertTrue(base.exists("/s/IMG_a.mp4"))
        assertFalse(base.exists("/t/IMG_a_MP.jpg"))
    }

    @Test fun `tmp write then move then delete is the exact operation order`() {
        val storage = RecordingStorage(InMemoryStorage(mapOf("/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video)))
        val runner = BatchRunner(storage)
        val report = runner.run(runner.plan(options(true)), options(true), NoCancel) { }
        assertEquals(ItemStatus.SUCCESS, report.items.single().status)
        // 时序：先建两个目录，写 .tmp/<name>.part，rename 到最终名，
        // 只有改名成功之后才允许删原文件（图先视频后）。整个时序就这一条测试钉死。
        assertEquals(
            listOf(
                "ensureDir:/t",
                "ensureDir:/t/.tmp",
                "openWrite:/t/.tmp/IMG_a_MP.jpg.part",
                "move:/t/.tmp/IMG_a_MP.jpg.part->/t/IMG_a_MP.jpg=true",
                "delete:/s/IMG_a.jpg=true",
                "delete:/s/IMG_a.mp4=true",
            ),
            storage.trace,
        )
    }

    /**
     * 运行被 force-stop / 低内存回收掀掉时，`catch` 与清理都来不及跑，半截 `.part` 就永久躺在
     * `<target>/.tmp/` 里占空间（`list` 只列一层，`.tmp` 是目录，既不会被配对也永远不会被删）。
     * 下一次开工先清扫：这些名字只可能是死掉的运行留下的，本次运行的 `.part` 此刻还不存在。
     */
    @Test fun `a stale part file left by a dead batch is swept before anything is written`() {
        val stale = "/t/.tmp/IMG_old_MP.jpg.part"
        val storage = RecordingStorage(InMemoryStorage(mapOf(
            stale to ByteArray(7), "/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video,
        )))
        val runner = BatchRunner(storage)
        val report = runner.run(runner.plan(options()), options(), NoCancel) { }
        assertEquals(ItemStatus.SUCCESS, report.items.single().status)
        assertFalse(storage.memory.exists(stale), "上一辈子留下的半截文件还在：${storage.memory.tmpFiles}")
        val swept = storage.calls.indexOfFirst { it == "delete:$stale=true" }
        val wrote = storage.calls.indexOfFirst { it.startsWith("openWrite") }
        assertTrue(swept in 0 until wrote, "清扫没有走在第一次写入之前：${storage.calls}")
    }

    /** 清扫范围就是"`.tmp` 里以 `.part` 结尾的名字"：同目录其他文件与 `.tmp` 之外的 `.part` 都是用户数据。 */
    @Test fun `the sweep deletes only part files inside the tmp directory`() {
        val stale = "/t/.tmp/IMG_old_MP.jpg.part"
        val otherInTmp = "/t/.tmp/notes.txt"
        val partInTarget = "/t/IMG_borrowed_MP.jpg.part"
        val partInSource = "/s/IMG_user.jpg.part"
        val (runner, storage) = runnerWith(
            stale to ByteArray(3), otherInTmp to ByteArray(3),
            partInTarget to ByteArray(3), partInSource to ByteArray(3),
            "/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video,
        )
        val report = runner.run(runner.plan(options()), options(), NoCancel) { }
        assertEquals(ItemStatus.SUCCESS, report.items.single().status)
        assertFalse(storage.exists(stale), "该扫的没扫掉")
        assertTrue(storage.exists(otherInTmp), "清扫越界：删掉了 .tmp 里不是 .part 的文件")
        assertTrue(storage.exists(partInTarget), "清扫越界：删掉了 .tmp 之外的 .part")
        assertTrue(storage.exists(partInSource), "清扫越界：删掉了来源目录里的文件")
    }

    /** 没有任何项要写时 `run` 对磁盘一个字节都不碰（NO_PAIR 行不建目录），清扫也守这条。 */
    @Test fun `a batch with nothing to compose sweeps nothing`() {
        val stale = "/t/.tmp/IMG_old_MP.jpg.part"
        val (runner, storage) = runnerWith(stale to ByteArray(7), "/s/IMG_a.mp4" to video)
        val report = runner.run(runner.plan(options()), options(), NoCancel) { }
        assertEquals(FailureReason.NO_PAIR, report.items.single().reason)
        assertTrue(storage.exists(stale), "计划里没有要合成的项，就不该发生删除")
    }

    @Test fun `already motion photo items are skipped and keep originals`() {
        val embedded = Fixtures.app1(Fixtures.XAP, XmpPacket.motionPhoto(10L))
        val dirty = Fixtures.jpeg(headSegments = listOf(embedded, Fixtures.sof0()))
        val (runner, storage) = runnerWith("/s/IMG_a.jpg" to dirty, "/s/IMG_a.mp4" to video)
        val report = runner.run(runner.plan(options(true)), options(true), CancelToken { false }) { }
        val item = report.items.single()
        assertEquals(ItemStatus.SKIPPED, item.status)
        assertEquals(FailureReason.ALREADY_MOTION_PHOTO, item.reason)
        // 跳过项一个字节也没写：bytes 必须是 0，否则 composedBytes 与 UI 进度都会虚高。
        assertEquals(0L, item.bytes)
        assertEquals(0L, report.composedBytes)
        assertTrue(storage.exists("/s/IMG_a.jpg"))
        assertFalse(storage.exists("/t/IMG_a_MP.jpg"))
        assertFalse(storage.tmpFiles.isNotEmpty())
    }

    @Test fun `unpaired inputs are reported as skipped`() {
        val (runner, _) = runnerWith("/s/IMG_a.mp4" to video)
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) { }
        val item = report.items.single()
        assertEquals(ItemStatus.SKIPPED, item.status)
        assertEquals(FailureReason.NO_PAIR, item.reason)
        assertEquals("IMG_a", item.stem)
        assertEquals(0L, item.bytes)
        assertEquals(0L, report.composedBytes)
        assertFalse(report.cancelled)
    }

    @Test fun `overwrite replaces the existing output`() {
        val (runner, storage) = runnerWith(
            "/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video, "/t/IMG_a_MP.jpg" to ByteArray(5)
        )
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) { }
        assertEquals(ItemStatus.SUCCESS, report.items.single().status)
        assertEquals(outputBytes, storage.bytesOf("/t/IMG_a_MP.jpg").size)
        assertEquals(outputBytes.toLong(), report.composedBytes)
        assertFalse(storage.tmpFiles.isNotEmpty())
    }

    @Test fun `cancel stops before the next item and keeps what is done`() {
        val (runner, storage) = runnerWith(
            "/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video, "/s/IMG_b.jpg" to image, "/s/IMG_b.mp4" to video
        )
        var seen = 0
        val report = runner.run(runner.plan(options()), options(), CancelToken { seen >= 1 }) { seen++ }
        assertTrue(report.cancelled)
        assertEquals(1, report.items.size)
        assertEquals(1, seen)
        assertTrue(storage.exists("/t/IMG_a_MP.jpg"))
        assertFalse(storage.exists("/t/IMG_b_MP.jpg"))
        assertFalse(storage.tmpFiles.isNotEmpty())
    }

    @Test fun `progress callback sees every item exactly once`() {
        val (runner, _) = runnerWith(
            "/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video, "/s/IMG_b.jpg" to image, "/s/IMG_b.mp4" to video
        )
        val seen = mutableListOf<String>()
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) { seen += it.stem }
        assertEquals(listOf("IMG_a", "IMG_b"), seen)
        assertEquals(2, report.items.size)
    }

    @Test fun `target dir is created before writing`() {
        val storage = RecordingStorage(InMemoryStorage(mapOf("/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video)))
        val runner = BatchRunner(storage)
        runner.run(runner.plan(options()), options(), NoCancel) { }
        assertTrue(storage.exists("/t/IMG_a_MP.jpg"))
        val writeIndex = storage.calls.indexOfFirst { it.startsWith("openWrite") }
        // 两个目录都必须在这一次写入之前建好，且顺序是目标目录、再它的 .tmp。
        assertEquals(listOf("ensureDir:/t", "ensureDir:/t/.tmp"), storage.calls.subList(0, 2))
        assertTrue(writeIndex >= 2, "ensureDir 没有走在 openWrite 之前，或根本没有写入：${storage.calls}")
    }

    @Test fun `an in-place batch still goes through the tmp file`() {
        // 目标目录 == 源目录是允许的场景（PairPlanner 支持原地生成）。
        // .part 必须落在 targetDir/.tmp 而不是源目录根，否则原地批次会把半截文件当输入。
        val storage = RecordingStorage(InMemoryStorage(mapOf("/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video)))
        val runner = BatchRunner(storage)
        val inplace = BatchOptions("/s", "/s", true)
        val report = runner.run(runner.plan(inplace), inplace, NoCancel) { }
        assertEquals(ItemStatus.SUCCESS, report.items.single().status)
        assertEquals(outputBytes.toLong(), report.composedBytes)
        // 原地批次结束后源目录里只剩产物：原图与视频被删，`.part` 从不落在源目录根上。
        assertEquals(listOf(DirEntry("IMG_a_MP.jpg", outputBytes.toLong())), storage.list("/s"))
        assertEquals(
            listOf(
                "ensureDir:/s",
                "ensureDir:/s/.tmp",
                "openWrite:/s/.tmp/IMG_a_MP.jpg.part",
                "move:/s/.tmp/IMG_a_MP.jpg.part->/s/IMG_a_MP.jpg=true",
                "delete:/s/IMG_a.jpg=true",
                "delete:/s/IMG_a.mp4=true",
            ),
            storage.trace,
        )
    }

    @Test fun `unpaired item does not abort the batch`() {
        val (runner, _) = runnerWith("/s/IMG_a.mp4" to video, "/s/IMG_b.jpg" to image, "/s/IMG_b.mp4" to video)
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) { }
        assertEquals(ItemStatus.SKIPPED, report.items.first { it.stem == "IMG_a" }.status)
        assertEquals(ItemStatus.SUCCESS, report.items.first { it.stem == "IMG_b" }.status)
        assertEquals(2, report.items.size)
        assertEquals(outputBytes.toLong(), report.composedBytes)
    }

    @Test fun `failed composition does not abort the batch and leaves no tmp file`() {
        val (runner, storage) = runnerWith(
            "/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to ByteArray(2048),
            "/s/IMG_b.jpg" to image, "/s/IMG_b.mp4" to video,
        )
        val seen = mutableListOf<ItemResult>()
        val report = runner.run(runner.plan(options()), options(), CancelToken { false }) { seen += it }
        assertEquals(ItemStatus.FAILED, report.items.first { it.stem == "IMG_a" }.status)
        assertEquals(ItemStatus.SUCCESS, report.items.first { it.stem == "IMG_b" }.status)
        assertEquals(2, seen.size)
        assertEquals(listOf("IMG_a", "IMG_b"), seen.map { it.stem })
        // 失败项不计入 composedBytes，也不留下 .part：清理不看字节数，无条件删。
        assertEquals(outputBytes.toLong(), report.composedBytes)
        assertFalse(storage.tmpFiles.isNotEmpty())
        // 坏视频这一对没被删：删除只属于成功项。
        assertTrue(storage.exists("/s/IMG_a.jpg"))
        assertTrue(storage.exists("/s/IMG_a.mp4"))
    }

    @Test fun `only already-motion-photo counts as skipped`() {
        // 只有 ALREADY_MOTION_PHOTO 算跳过：NOT_MP4 与 MALFORMED_JPEG 都必须报 FAILED——
        // MALFORMED_JPEG 也可能来自真实的磁盘故障，绝不能被分进"跳过"。两条一起跑还顺带
        // 证明连续失败也不中断批次。
        val (runner, storage) = runnerWith(
            "/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to ByteArray(2048),
            "/s/IMG_b.jpg" to image.copyOf(9), "/s/IMG_b.mp4" to video,
        )
        val report = runner.run(runner.plan(options(true)), options(true), CancelToken { false }) { }
        val failed = report.items.sortedBy { it.stem }
        assertEquals(listOf(ItemStatus.FAILED, ItemStatus.FAILED), failed.map { it.status })
        assertEquals(
            listOf(FailureReason.NOT_MP4, FailureReason.MALFORMED_JPEG),
            failed.map { it.reason },
        )
        assertEquals(0L, report.composedBytes)
        assertTrue(storage.exists("/s/IMG_a.mp4"))
        assertTrue(storage.exists("/s/IMG_b.jpg"))
        assertFalse(storage.tmpFiles.isNotEmpty())
    }

    @Test fun `a jpeg whose source xmp nobody can parse still composes`() {
        // 写路径**不解析**源 XMP：畸形包因此既不是失败理由，也不需要那套安全解析。
        // 它的下场和别的源声明一样——被清空白掉。
        val broken = Fixtures.app1(Fixtures.XAP, "<x:xmpmeta><unclosed>".toByteArray(Charsets.UTF_8))
        val dirty = Fixtures.jpeg(headSegments = listOf(broken, Fixtures.sof0()))
        val (runner, storage) = runnerWith("/s/IMG_a.jpg" to dirty, "/s/IMG_a.mp4" to video)
        val report = runner.run(runner.plan(options(true)), options(true), CancelToken { false }) { }
        val item = report.items.single()
        assertEquals(ItemStatus.SUCCESS, item.status)
        assertNull(item.reason, "成功项不该带失败原因")
        val out = storage.bytesOf("/t/IMG_a_MP.jpg")
        assertEquals(out.size.toLong(), item.bytes)
        // 字节账本：源文件整份（旧 XMP 换成新段）+ 视频。
        assertEquals(
            out.size.toLong(),
            dirty.size - scan(dirty).oldXmp!!.length +
                XmpPacket.app1Segment(video.size.toLong()).size + video.size,
        )
        val text = out.toString(Charsets.ISO_8859_1)
        assertFalse(text.contains("<unclosed"), "旧 XMP 的字节还留在产物里")
        assertTrue(text.contains("GCamera:MotionPhoto=\"1\""), "产物没写成动态照片")
        assertTrue(storage.exists("/t/IMG_a_MP.jpg"))
        assertFalse(storage.tmpFiles.isNotEmpty())
        // 成功项才删原文件：这里删得掉，正因为合成成功了。
        assertFalse(storage.exists("/s/IMG_a.jpg"))
    }

    @Test fun `a gain map jpeg composes through the batch and reports real byte counts`() {
        // 端到端：最常见的那张输入（XMP 声明 GainMap + EOI 后躺着副图）必须报成功，
        // 而 bytes 等于**源文件整份 + 视频**的产物长度——UI 的"已写出 N MB"读的就是它。
        val dirty = Fixtures.gainMapJpeg()
        val (runner, storage) = runnerWith("/s/IMG_a.jpg" to dirty, "/s/IMG_a.mp4" to video)
        val report = runner.run(runner.plan(options()), options(), NoCancel) { }
        val item = report.items.single()
        assertEquals(ItemStatus.SUCCESS, item.status)
        val out = storage.bytesOf("/t/IMG_a_MP.jpg")
        assertEquals(out.size.toLong(), item.bytes)
        assertEquals(out.size.toLong(), report.composedBytes)
        val source = scan(dirty)
        // 尾巴是 gain map 副图 + 387 字节厂商记录 → 目录用 GainMap 项声明**整条尾巴**。
        val gainMapBytes = dirty.size - source.imageEnd
        val app1 = XmpPacket.app1Segment(video.size.toLong(), gainMapBytes = gainMapBytes)
        assertEquals(dirty.size - source.oldXmp!!.length + app1.size + video.size, out.size.toLong())
        // 尾巴在位：视频起点 = 产物的 imageEnd **再加上那条副图尾巴**。截掉尾巴的算式在这里会红。
        val structure = scan(out)
        val text = out.toString(Charsets.ISO_8859_1)
        val offset = Regex("""GCamera:MicroVideoOffset="(\d+)"""").find(text)!!.groupValues[1].toLong()
        assertEquals(structure.imageEnd + gainMapBytes, out.size - offset)
        assertEquals(video.size.toLong(), offset)
        assertFalse(text.contains("hdrgm:GainMapHeadroom"), "源包的 hdrgm 属性被掺进了产物")
        // 有副图时**我们自己清空白写**的 hdrgm:Version 必须在场且只有一条；
        // 出现两条 = 源包没被丢干净。
        assertEquals(1, Regex(Regex.escape("hdrgm:Version=\"1.0\"")).findAll(text).count(),
            "批次产物没带（或掺重了）hdrgm:Version=\"1.0\"")
        assertEquals(
            listOf("Primary", "GainMap", "MotionPhoto"),
            Regex("""Item:Semantic="(\w+)"""").findAll(text).map { it.groupValues[1] }.toList(),
            "批次产物的目录项顺序不是 Primary → GainMap → MotionPhoto",
        )
        assertFalse(text.contains("<Item:Semantic>GainMap</Item:Semantic>"), "源片的容器项还在产物里")
        assertTrue(text.contains("GCamera:MotionPhoto=\"1\""), "批次产物没写成动态照片")
    }

    @Test fun `move failure is reported as io error and keeps the originals`() {
        val storage = RecordingStorage(InMemoryStorage(mapOf("/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video)))
        storage.moveResult = false
        val runner = BatchRunner(storage)
        val report = runner.run(runner.plan(options(true)), options(true), NoCancel) { }
        val item = report.items.single()
        assertEquals(ItemStatus.FAILED, item.status)
        assertEquals(FailureReason.IO_ERROR, item.reason)
        assertEquals(0L, item.bytes)
        assertEquals(0L, report.composedBytes)
        // rename 没成功就等于什么都没发生：原文件留着，最终名上没有产物，.part 被清掉。
        assertTrue(storage.exists("/s/IMG_a.jpg"))
        assertTrue(storage.exists("/s/IMG_a.mp4"))
        assertFalse(storage.exists("/t/IMG_a_MP.jpg"))
        assertFalse(storage.calls.any { it.startsWith("delete:/s/") }, "改名失败却删了原文件")
        assertFalse(storage.memory.tmpFiles.isNotEmpty())
    }

    @Test fun `a source that survives deletion becomes a delete failure warning`() {
        val storage = RecordingStorage(InMemoryStorage(mapOf("/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video)))
        storage.deleteRefused = setOf("/s/IMG_a.mp4")
        val runner = BatchRunner(storage)
        val item = runner.run(runner.plan(options(true)), options(true), NoCancel) { }.items.single()
        // 删除失败不改变本项的成功状态，但要分别记下
        // "没删掉的名字"（deleteFailures，给用户看警告）和"真删掉的绝对路径"
        // （deletedSources，要交给 MediaStore 收敛索引）。
        assertEquals(ItemStatus.SUCCESS, item.status)
        assertEquals(listOf("IMG_a.mp4"), item.deleteFailures)
        assertEquals(listOf("/s/IMG_a.jpg"), item.deletedSources)
        assertEquals(outputBytes.toLong(), item.bytes)
        assertTrue(storage.exists("/s/IMG_a.mp4"))
        assertFalse(storage.exists("/s/IMG_a.jpg"))
    }

    @Test fun `a source that vanished after the plan is reported as io error and does not abort the batch`() {
        // UI 先 plan() 预览、再把同一个 PlanResult 交给 run()。这中间另一个进程（相册同步、
        // 用户自己在文件管理器里删）可以把源文件移走：BatchStorage.open 的契约就是此时抛
        // FileNotFoundException，composeOne 的 catch 分支就是为它写的。
        val (runner, storage) = runnerWith(
            "/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video, "/s/IMG_b.jpg" to image, "/s/IMG_b.mp4" to video,
        )
        val plan = runner.plan(options())
        // 夹具前提：a 在删掉之前确实配成了对（否则它只是另一条 NO_PAIR 行，catch 分支根本没跑）。
        assertEquals(listOf("IMG_a", "IMG_b"), plan.jobs.map { it.stem })
        assertTrue(storage.delete("/s/IMG_a.jpg"), "夹具没能把源图删掉")
        val seen = mutableListOf<ItemResult>()
        val report = runner.run(plan, options(true), NoCancel) { seen += it }
        val a = report.items.first { it.stem == "IMG_a" }
        assertEquals(ItemStatus.FAILED, a.status)
        assertEquals(FailureReason.IO_ERROR, a.reason)
        assertEquals(0L, a.bytes)
        assertEquals(emptyList<String>(), a.deleteFailures)
        assertEquals(emptyList<String>(), a.deletedSources)
        // 一项炸掉不算批次炸掉。b 照常提交，连删除记账都不受影响。
        assertEquals(ItemStatus.SUCCESS, report.items.first { it.stem == "IMG_b" }.status)
        assertEquals(listOf("/s/IMG_b.jpg", "/s/IMG_b.mp4"), report.items.first { it.stem == "IMG_b" }.deletedSources)
        assertEquals(listOf("IMG_a", "IMG_b"), seen.map { it.stem })
        assertEquals(2, report.items.size)
        assertEquals(outputBytes.toLong(), report.composedBytes)
        assertTrue(storage.exists("/t/IMG_b_MP.jpg"))
        assertFalse(storage.exists("/t/IMG_a_MP.jpg"))
        // 没提交的项不许删原文件：视频还躺在源目录（图是被"别的进程"删的，不算我们删的）。
        assertTrue(storage.exists("/s/IMG_a.mp4"))
        // open 抛之前 openWrite 已经把 0 字节的 .part 落进了目录（内存实现的 close 语义），
        // 所以这条断言真的在检查 catch 分支执行了半截文件的清理。
        assertFalse(storage.tmpFiles.isNotEmpty(), "catch 分支留下了半截 .part：${storage.tmpFiles}")
    }

    @Test fun `a throwing tmp cleanup cannot escape the run`() {
        // 清理自己也是文件操作：源消失（open 抛）之后 catch 要删 .part，而这一次删除也抛。
        // run 的契约是**必定**返回 BatchReport，绝不把异常递给 UI。
        val storage = RecordingStorage(InMemoryStorage(mapOf(
            "/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video, "/s/IMG_b.jpg" to image, "/s/IMG_b.mp4" to video,
        )))
        val runner = BatchRunner(storage)
        val plan = runner.plan(options())
        // 夹具前提：删图之前 a 确实是个作业（删完之后它才会变成 NO_PAIR，那就不是本测试要跑的路径了）。
        assertEquals(listOf("IMG_a", "IMG_b"), plan.jobs.map { it.stem })
        assertTrue(storage.memory.delete("/s/IMG_a.jpg"), "夹具没能把源图删掉")
        storage.deleteThrows = setOf("/t/.tmp/IMG_a_MP.jpg.part")
        val report = runner.run(plan, options(), NoCancel) { }
        val a = report.items.first { it.stem == "IMG_a" }
        assertEquals(ItemStatus.FAILED, a.status)
        assertEquals(FailureReason.IO_ERROR, a.reason)
        assertEquals(0L, a.bytes)
        assertEquals(
            listOf("delete:/t/.tmp/IMG_a_MP.jpg.part=throw"),
            storage.calls.filter { it.startsWith("delete:/t/.tmp/") },
        )
        assertEquals(ItemStatus.SUCCESS, report.items.first { it.stem == "IMG_b" }.status)
        assertEquals(outputBytes.toLong(), report.composedBytes)
        // 清不掉的半截文件留在 .tmp 里——这是"批次继续"的代价（下次批次会覆盖它），
        // 不是新故障，也不许反过来把整批掀翻。
        assertEquals(listOf("/t/.tmp/IMG_a_MP.jpg.part"), storage.memory.tmpFiles)
    }

    @Test fun `a throwing tmp cleanup does not rewrite a skipped item into a failure`() {
        // 同一个守卫也管校验被拒的那条出路：ALREADY_MOTION_PHOTO 是唯一的"跳过"，
        // 清 .part 时抛出故障绝不能借道外层 catch 把它改写成 FAILED(IO_ERROR)——
        // 那是把用户已经完成的文件说成失败。
        val embedded = Fixtures.app1(Fixtures.XAP, XmpPacket.motionPhoto(10L))
        val dirty = Fixtures.jpeg(headSegments = listOf(embedded, Fixtures.sof0()))
        val storage = RecordingStorage(InMemoryStorage(mapOf(
            "/s/IMG_a.jpg" to dirty, "/s/IMG_a.mp4" to video, "/s/IMG_b.jpg" to image, "/s/IMG_b.mp4" to video,
        )))
        storage.deleteThrows = setOf("/t/.tmp/IMG_a_MP.jpg.part")
        val runner = BatchRunner(storage)
        val report = runner.run(runner.plan(options(true)), options(true), NoCancel) { }
        val a = report.items.first { it.stem == "IMG_a" }
        assertEquals(ItemStatus.SKIPPED, a.status, "清理半截 .part 抛出来，把跳过改写成失败")
        assertEquals(FailureReason.ALREADY_MOTION_PHOTO, a.reason)
        assertEquals(0L, a.bytes)
        // a 的原文件一个都不许动（跳过项没提交），b 照常提交并删干净。
        assertTrue(storage.exists("/s/IMG_a.jpg"))
        assertTrue(storage.exists("/s/IMG_a.mp4"))
        assertEquals(emptyList<String>(), a.deletedSources)
        assertEquals(emptyList<String>(), a.deleteFailures)
        val b = report.items.first { it.stem == "IMG_b" }
        assertEquals(ItemStatus.SUCCESS, b.status)
        assertEquals(listOf("/s/IMG_b.jpg", "/s/IMG_b.mp4"), b.deletedSources)
        assertEquals(outputBytes.toLong(), report.composedBytes)
        assertEquals(listOf("/t/.tmp/IMG_a_MP.jpg.part"), storage.memory.tmpFiles)
    }

    @Test fun `a throwing source delete keeps the committed success and only adds a warning`() {
        // rename 已经落地 = 本项提交完成。此后删除源文件抛出来（文件被别的进程占着），
        // 只允许多一条 deleteFailures 警告，绝不允许把 SUCCESS 改写成 FAILED(IO_ERROR)、
        // bytes 归零、deletedSources 清空——那等于报告说"什么都没写出来"，而产物明明在盘上。
        val storage = RecordingStorage(InMemoryStorage(mapOf(
            "/s/IMG_a.jpg" to image, "/s/IMG_a.mp4" to video, "/s/IMG_b.jpg" to image, "/s/IMG_b.mp4" to video,
        )))
        storage.deleteThrows = setOf("/s/IMG_a.mp4")
        val runner = BatchRunner(storage)
        val report = runner.run(runner.plan(options(true)), options(true), NoCancel) { }
        val a = report.items.first { it.stem == "IMG_a" }
        assertEquals(ItemStatus.SUCCESS, a.status, "删除抛出把已提交的本项改写成 FAILED")
        assertNull(a.reason)
        assertEquals(outputBytes.toLong(), a.bytes)
        assertEquals(listOf("IMG_a.mp4"), a.deleteFailures)
        assertEquals(listOf("/s/IMG_a.jpg"), a.deletedSources)
        assertTrue(storage.exists("/s/IMG_a.mp4"))
        assertFalse(storage.exists("/s/IMG_a.jpg"))
        assertTrue(storage.exists("/t/IMG_a_MP.jpg"))
        // 抛出故障同样不截断批次：b 照常提交、照常删干净。
        val b = report.items.first { it.stem == "IMG_b" }
        assertEquals(ItemStatus.SUCCESS, b.status)
        assertEquals(listOf("/s/IMG_b.jpg", "/s/IMG_b.mp4"), b.deletedSources)
        assertEquals(2, report.items.size)
        assertEquals(outputBytes * 2L, report.composedBytes)
        assertFalse(storage.memory.tmpFiles.isNotEmpty())
    }

    @Test fun `cancel stops the unpaired pre-loop instead of emitting every no-pair row`() {
        // 两个视频都找不到图像 → jobs 为空，唯一会跑的循环就是 NO_PAIR 预扫描。
        // 全局约束"取消标志在每一项之前检查"管的也是这些行：它们同样进 items、同样推给 UI。
        val (runner, storage) = runnerWith("/s/IMG_a.mp4" to video, "/s/IMG_b.mp4" to video)
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
            "/s/IMG_a.jpg" to image, "/s/IMG_b.jpg" to image, "/s/IMG_b.mp4" to video,
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
         * 这些路径上的 delete 直接抛 `IOException`：[BatchStorage.delete] 的契约只返回 true/false，
         * 但 `run` "必定返回报告" 不该把安全性押在实现方守约上（真实文件系统实现同样会抛）。
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
