package dev.zayn.vlive

import androidx.lifecycle.viewModelScope
import dev.zayn.vlive.core.ItemStatus
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM tests (`app/src/test`)——没有 Robolectric、没有新依赖：
 * `PermissionGate.of(app)` 换成假 lambda，`MediaStoreSync.scan(app, …)` 换成注入的 [scanner]，
 * Android 侧唯一剩下的一环 `viewModelScope` 在 lifecycle 2.11 里对无主线程环境退化为
 * `SupervisorJob + Dispatchers.Default`（探针实测），所以**真的** [BatchViewModel.start] 路径
 * 在纯 JVM 上就能驱动。批次读写走 `FileStorage` 真文件系统，root 注入临时目录。
 *
 * 钉住的核心不变量（spec §7.6 / R4 + review finding 3）：
 * 每一个产物与每一个已删原文件必须到达扫描器，**外层 scope 取消也不例外**。
 *
 * 全局提示断言的是 `R.string.*` 这个 int id 与参数列表，不是解析后的文案：读一个常量不需要
 * `Resources`，所以"没有 Robolectric"这条前提没被破。真要检查中英文案本身，那是资源层的
 * 装机验收项（`docs/testing.md`），不该把整个 VM 拖进 Robolectric。
 */
class BatchViewModelTest {

    private val jpegBytes = plainJpeg()
    private val mp4Bytes = ftypMp4(300)

    /**
     * 路径串一律用 "/" 拼接——[BatchViewModel.isSelectable] / `FileStorage.resolve()` 的 root
     * 规则是 POSIX 的 `"$root/"` 前缀；宿主若是 Windows，`File(parent, child).absolutePath`
     * 会用 `\` 连接并直接被判到 root 之外。java.io.File 读串时两种分隔符都认，所以
     * "串用 / 拼、File 照常读写"在两个宿主上都是真文件系统操作（`DirBrowserTest` 同理）。
     */
    private class Tree(val root: File, val rootPath: String, val srcPath: String, val outPath: String)

    private fun tree(pairs: List<String>, unpaired: List<String> = emptyList()): Tree {
        val root = File(System.getProperty("java.io.tmpdir"), "lpb-vm-${System.nanoTime()}")
        val rootPath = root.absolutePath
        val src = File("$rootPath/src")
        src.mkdirs()
        pairs.forEach { stem ->
            File(src, "$stem.jpg").writeBytes(jpegBytes)
            File(src, "$stem.mp4").writeBytes(mp4Bytes)
        }
        unpaired.forEach { stem -> File(src, "$stem.mp4").writeBytes(mp4Bytes) }
        return Tree(root, rootPath, "$rootPath/src", "$rootPath/out")
    }

    private fun viewModel(
        root: String,
        granted: () -> Boolean = { true },
        scanner: suspend (List<String>) -> Boolean = { true },
    ): BatchViewModel = BatchViewModel(granted, scanner, root)

    /** start() 走 viewModelScope，测试线程只能轮询状态等它落位（30s 上限，正常 <1s）。 */
    private fun BatchViewModel.awaitIdle(timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (state.value.running) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(5)
        }
        return true
    }

    private fun BatchViewModel.awaitDoneAtLeast(target: Int, timeoutMs: Long = 30_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (state.value.done < target) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(2)
        }
        return true
    }

    @Test
    fun `start scans every output and every deleted original`() {
        val t = tree(pairs = listOf("IMG_a", "IMG_b"), unpaired = listOf("IMG_c"))
        val scanned = CompletableDeferred<List<String>>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val vm = viewModel(t.rootPath) { paths ->
            calls.incrementAndGet()
            scanned.complete(paths)
            release.await()
            true
        }
        vm.setSource(t.srcPath)
        vm.setTarget(t.outPath)
        vm.setDeleteOriginals(true)
        vm.start()

        val paths = runBlocking { withTimeout(30_000) { scanned.await() } }
        // 回报顺序：NO_PAIR 行先来，然后按 stem 排序的 jobs；产物用 "$targetDir/$name"
        // 拼接（与 BatchRunner.kt:109 同一约定），deletedSources 原样绝对路径转发。
        assertEquals(
            listOf(
                "${t.outPath}/IMG_a_MP.jpg",
                "${t.srcPath}/IMG_a.jpg",
                "${t.srcPath}/IMG_a.mp4",
                "${t.outPath}/IMG_b_MP.jpg",
                "${t.srcPath}/IMG_b.jpg",
                "${t.srcPath}/IMG_b.mp4",
            ),
            paths,
        )
        release.complete(Unit)
        assertTrue("batch must settle", vm.awaitIdle())
        assertEquals(1, calls.get())

        val s = vm.state.value
        assertEquals(3, s.done)
        assertEquals(3, s.total)
        assertTrue(s.bytes > 0)
        assertNull(s.globalError)
        assertEquals(listOf(ItemStatus.SKIPPED, ItemStatus.SUCCESS, ItemStatus.SUCCESS), s.items.map { it.status })
        assertTrue(File(t.outPath, "IMG_a_MP.jpg").isFile)
        assertTrue(File(t.outPath, "IMG_b_MP.jpg").isFile)
        assertFalse(File(t.srcPath, "IMG_a.jpg").exists())
        assertFalse(File(t.srcPath, "IMG_a.mp4").exists())
        assertFalse(File(t.srcPath, "IMG_b.jpg").exists())
        assertFalse(File(t.srcPath, "IMG_b.mp4").exists())
        assertTrue("原文件还在的项不许进扫描——它也没进", File(t.srcPath, "IMG_c.mp4").isFile)
        t.root.deleteRecursively()
    }

    @Test
    fun `scope cancellation during the cleanup scan still delivers every path`() {
        // finding 3 的钉子：onCleared / viewModelScope 取消落在扫描挂起的那一刻。
        // finally 里的 withContext(NonCancellable + IO) 必须让扫描提交完整列表、running 落回
        // false。把 NonCancellable 摘掉（withContext(Dispatchers.IO)），release.await() 会带着
        // CancellationException 掀掉收尾，running 永远停住，本测试立刻翻红。
        val t = tree(pairs = listOf("IMG_a"))
        val scanned = CompletableDeferred<List<String>>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val vm = viewModel(t.rootPath) { paths ->
            calls.incrementAndGet()
            scanned.complete(paths)
            release.await()
            true
        }
        vm.setSource(t.srcPath)
        vm.setTarget(t.outPath)
        vm.setDeleteOriginals(true)
        vm.start()

        val paths = runBlocking { withTimeout(30_000) { scanned.await() } }
        vm.viewModelScope.cancel() // 模拟批次收尾时 ViewModel 被回收
        release.complete(Unit)
        assertTrue("cleanup must survive the cancelled scope", vm.awaitIdle())
        assertEquals(
            listOf("${t.outPath}/IMG_a_MP.jpg", "${t.srcPath}/IMG_a.jpg", "${t.srcPath}/IMG_a.mp4"),
            paths,
        )
        assertEquals(1, calls.get())
        assertNull(vm.state.value.globalError)
        t.root.deleteRecursively()
    }

    @Test
    fun `a cancelled batch scans exactly what it committed and reported`() {
        // 取消标志查在两项之间，落点不确定——要钉的不变量与落点无关：扫描列表 ==
        // touchedPathsFor(目标目录, 回报过的全部 items)，含取消之后才回报的那条尾巴；
        // 且磁盘事实与之一致：SUCCESS 的产物在、已删的原文件没了、没轮到的项一根毛没动。
        // 先等 done 过 20 再 cancel()，保证批次一定"跑起来了才被叫停"（不是开局即空转）。
        val names = (0 until 60).map { "IMG_p%03d".format(it) }
        val t = tree(pairs = names)
        val recorded = AtomicReference<List<String>>()
        val calls = AtomicInteger()
        val vm = viewModel(t.rootPath) { paths ->
            calls.incrementAndGet()
            recorded.set(paths)
            true
        }
        vm.setSource(t.srcPath)
        vm.setTarget(t.outPath)
        vm.setDeleteOriginals(true)
        vm.start()
        assertTrue("batch must get going", vm.awaitDoneAtLeast(20))
        vm.cancel()
        assertTrue("batch must settle", vm.awaitIdle())

        val s = vm.state.value
        assertTrue(s.done >= 20)
        assertEquals(1, calls.get())
        val expected = touchedPathsFor(t.outPath, s.items)
        assertEquals(expected, recorded.get())
        assertTrue(expected.isNotEmpty())
        val succeeded = HashSet<String>()
        s.items.filter { it.status == ItemStatus.SUCCESS }.forEach { item ->
            succeeded += item.stem
            assertTrue("产物必须在：${item.outputName}", File(t.outPath, item.outputName).isFile)
            item.deletedSources.forEach { assertFalse("扫过的原文件必须真的没了：$it", File(it).exists()) }
        }
        names.filterNot { it in succeeded }.forEach { stem ->
            assertTrue("没回报过的项不许动过原文件", File(t.srcPath, "$stem.jpg").isFile)
            assertTrue(File(t.srcPath, "$stem.mp4").isFile)
        }
        t.root.deleteRecursively()
    }

    @Test
    fun `scope cancelled before the batch dispatches leaves zero file effects and zero scans`() {
        // 残余风险 1 的边界（类 KDoc）：协程体还没被线程启动就碰上 scope 已取消，整个 launch
        // （连 finally 都不算）被 kotlinx 直接丢弃——可接受的原因恰恰是"此刻什么都没动"：
        // 没有产物、没有删除、没有 ensureDir 之外的文件效果，相册自然不会有幽灵条目。
        // 这里钉的就是"零文件效果 + 零扫描"；测不掉的只剩 running 卡 true（VM 已死，无人读）。
        val t = tree(pairs = listOf("IMG_a"))
        val calls = AtomicInteger()
        val vm = viewModel(t.rootPath) { calls.incrementAndGet(); true }
        vm.setSource(t.srcPath)
        vm.setTarget(t.outPath)
        vm.setDeleteOriginals(true)
        vm.viewModelScope.cancel()
        vm.start()
        Thread.sleep(150) // 给任何"不该发生的调度"足够时间暴露
        assertEquals(0, calls.get())
        assertFalse("out 目录都不该被创建", File(t.outPath).exists())
        assertTrue(File(t.srcPath + "/IMG_a.jpg").isFile)
        assertTrue(File(t.srcPath + "/IMG_a.mp4").isFile)
        assertNull(vm.state.value.globalError)
        t.root.deleteRecursively()
    }

    @Test
    fun `start without permission is a silent refusal`() {
        val t = tree(pairs = listOf("IMG_a"))
        val calls = AtomicInteger()
        val vm = viewModel(t.rootPath, granted = { false }) { calls.incrementAndGet(); true }
        vm.setSource(t.srcPath)
        vm.setTarget(t.outPath)
        assertFalse(vm.state.value.permissionGranted)
        vm.start()
        assertFalse(vm.state.value.running)
        assertNull(vm.state.value.globalError)
        assertEquals(0, calls.get())
        t.root.deleteRecursively()
    }

    @Test
    fun `missing source dir maps to a global error and never reaches the scanner`() {
        val t = tree(pairs = listOf("IMG_a"))
        val calls = AtomicInteger()
        val vm = viewModel(t.rootPath) { calls.incrementAndGet(); true }
        val snapshot = UiState(sourceDir = "${t.rootPath}/nope", targetDir = t.outPath, running = true)
        runBlocking { vm.runBatch(snapshot) }
        assertEquals(0, calls.get())
        val err = vm.state.value.globalError!!
        // 文案是资源 id + 参数，不是拼好的串：语言由 UI 侧解析（见 MessageText 的 KDoc）。
        assertEquals(R.string.err_source_unreadable, err.id)
        assertEquals(listOf("${t.rootPath}/nope"), err.args)
        assertFalse(vm.state.value.running)
        t.root.deleteRecursively()
    }

    @Test
    fun `un-creatable target dir maps to a global error and never reaches the scanner`() {
        val t = tree(pairs = listOf("IMG_a"))
        File(t.outPath).writeText("占位的普通文件：ensureDir 必然失败")
        val calls = AtomicInteger()
        val vm = viewModel(t.rootPath) { calls.incrementAndGet(); true }
        val snapshot = UiState(sourceDir = t.srcPath, targetDir = t.outPath, running = true)
        runBlocking { vm.runBatch(snapshot) }
        assertEquals(0, calls.get())
        assertEquals(R.string.err_target_uncreatable, vm.state.value.globalError!!.id)
        assertFalse(vm.state.value.running)
        t.root.deleteRecursively()
    }

    @Test
    fun `outside-root snapshot paths are refused with the same predicate the UI used`() {
        // finding 4：被检查的路径与被读写的路径用同一个 isSelectable——root 之外的开工快照
        // 静默返回（selectPath 保证 UiState 里不会出现这种串，这条是防漂移的兜底）。
        val t = tree(pairs = listOf("IMG_a"))
        val calls = AtomicInteger()
        val vm = viewModel(t.rootPath) { calls.incrementAndGet(); true }
        runBlocking {
            vm.runBatch(UiState(sourceDir = "/outside/only", targetDir = t.outPath, running = true))
            vm.runBatch(UiState(sourceDir = t.srcPath, targetDir = "/outside/only", running = true))
        }
        assertEquals(0, calls.get())
        assertNull(vm.state.value.globalError)
        assertFalse(vm.state.value.running)
        assertTrue(File(t.srcPath, "IMG_a.jpg").isFile)
        t.root.deleteRecursively()
    }

    @Test
    fun `unconfirmed scan becomes a visible one-line hint instead of silence`() {
        // §7.6 的可观测性：MediaStoreSync 返回 false 时 globalError 必须给出提示（不崩、
        // 不改写 items、running 照常落回 false）。
        val t = tree(pairs = listOf("IMG_a"))
        val vm = viewModel(t.rootPath) { false }
        vm.setSource(t.srcPath)
        vm.setTarget(t.outPath)
        vm.setDeleteOriginals(true)
        vm.start()
        assertTrue(vm.awaitIdle())
        assertEquals(R.string.err_scan_unconfirmed, vm.state.value.globalError!!.id)
        assertEquals(1, vm.state.value.items.size)
        t.root.deleteRecursively()
    }

    @Test
    fun `a throwing scanner is logged into globalError and never strands the batch`() {
        val t = tree(pairs = listOf("IMG_a"))
        val vm = viewModel(t.rootPath) { throw IllegalStateException("boom") }
        vm.setSource(t.srcPath)
        vm.setTarget(t.outPath)
        vm.start()
        assertTrue(vm.awaitIdle())
        assertEquals(R.string.err_scan_failed, vm.state.value.globalError!!.id)
        assertEquals(listOf<Any>("boom"), vm.state.value.globalError!!.args)
        assertFalse(vm.state.value.running)
        t.root.deleteRecursively()
    }

    @Test
    fun `root selection rules stay shared with FileStorage`() {
        // 生产 root 的未规范化字符串契约（见 BatchViewModel KDoc）+ selectPath 的静默拒绝。
        val vm = viewModel("/storage/emulated/0")
        assertTrue(vm.isSelectable("/storage/emulated/0"))
        assertTrue(vm.isSelectable("/storage/emulated/0/DCIM/Camera"))
        assertFalse(vm.isSelectable("/sdcard"))
        assertFalse(vm.isSelectable("/storage/emulated"))
        assertFalse(vm.isSelectable("/storage/emulated/00"))
        assertFalse(vm.isSelectable(""))
        vm.setSource("/sdcard/DCIM")
        assertEquals("/storage/emulated/0/DCIM/Camera", vm.state.value.sourceDir)
    }

    // 以下两个字节工厂移植自 :core 的测试 Fixtures（:core 不许动、测试夹具也不对外发布，
    // 这里只需要"合法 JPEG + 合法 ftyp MP4"最小形状）。
    private fun segment(marker: Int, payload: ByteArray): ByteArray =
        byteArrayOf(
            0xFF.toByte(),
            marker.toByte(),
            ((payload.size + 2) shr 8).toByte(),
            (payload.size + 2).toByte(),
        ) + payload

    private fun plainJpeg(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            segment(0xC0, byteArrayOf(0x08, 0x00, 0x11, 0x01, 0x01, 0x00, 0x01, 0x11, 0x00)) +
            segment(0xDA, byteArrayOf(0x01, 0x01, 0x00, 0x00, 0x3F, 0x00)) +
            byteArrayOf(0x11, 0x22, 0x33, 0x44) +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun ftypMp4(size: Int): ByteArray {
        val out = ByteArray(size)
        out[0] = (size ushr 24).toByte()
        out[1] = (size ushr 16).toByte()
        out[2] = (size ushr 8).toByte()
        out[3] = size.toByte()
        "ftyp".toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        "isom".toByteArray(Charsets.ISO_8859_1).copyInto(out, 8)
        var i = 12
        while (i + 1 < out.size) {
            out[i] = 0xFF.toByte()
            out[i + 1] = 0xD9.toByte()
            i += 16
        }
        return out
    }
}
