package dev.zayn.livephotobatch.app

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zayn.livephotobatch.app.browse.DirBrowser
import dev.zayn.livephotobatch.app.io.FileStorage
import dev.zayn.livephotobatch.app.perm.PermissionGate
import dev.zayn.livephotobatch.core.BatchOptions
import dev.zayn.livephotobatch.core.BatchRunner
import dev.zayn.livephotobatch.core.CancelToken
import dev.zayn.livephotobatch.core.ItemResult
import dev.zayn.livephotobatch.core.ItemStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 单屏 UI 的唯一状态。字段集合与顺序就是计划钉死的，UI 只读它，不另存副本。
 *
 * - [sourceDir] / [targetDir]：**真实绝对路径**（`DirBrowser` 给什么就存什么），默认值即验收用的
 *   相机目录与产物目录。
 * - [deleteOriginals]：不可撤销的开关，UI 必须把它渲染成危险项。
 * - [permissionGranted]：`MANAGE_EXTERNAL_STORAGE` 的快照，未授权时 UI 禁止开工。
 * - [done] / [total] / [bytes]：逐项回调累加的进度；[bytes] 只来自成功项
 *   （Task 8 保证跳过/失败项 `bytes = 0`）。
 * - [items]：已经回报过的每一项，按发生顺序追加，UI 直接当报告列表渲染。
 * - [globalError]：**整批被拒绝**的原因（spec §9：源目录不可读 / 目标目录建不起来 / 权限不足），
 *   一旦非空就说明这一批一个文件都没动过。
 */
data class UiState(
    val sourceDir: String = "/storage/emulated/0/DCIM/Camera",
    val targetDir: String = "/storage/emulated/0/Pictures/LivePhoto",
    val deleteOriginals: Boolean = false,
    val permissionGranted: Boolean = false,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val bytes: Long = 0,
    val items: List<ItemResult> = emptyList(),
    val globalError: String? = null,
)

/**
 * 批次的所有权都在这里：读权限 → 校验全局前置条件 → `plan()` 出总数 → `run()` 逐项回报 →
 * 产物与被删原文件一起交给 [MediaStoreSync.scan]。
 *
 * ## root 字符串契约（和 `FileStorage` / `DirBrowser` 共享同一个串）
 * [root] 必须是**未规范化**的 `"/storage/emulated/0"`：不带尾斜杠、绝不空，而且同一个实例既
 * 喂 [FileStorage] 也喂 [DirBrowser]。理由见 `FileStorage.resolve()`：只有
 * `path == root || path.startsWith("$root/")` 才原样使用，root 一旦带尾斜杠，
 * `"/storage/emulated/0"` 本身就会被当相对路径二次拼接成 `/storage/emulated/0/storage/emulated/0`。
 * [isSelectable] 就是照抄这一条判定，用来挡住"浏览器里点到 root 之外"的路径——
 * `DirBrowser.subdirs` 只看 `startsWith("/")`，能浏览到 `/sdcard`、`/storage/emulated`，
 * 那些串喂给存储层会被重新拼到 root 之下，语义完全不同（`DirItem` 的 KDoc 写了这个分叉）。
 *
 * ## 状态只在 [Dispatchers.IO] 上被批次循环推进
 * 所以所有写入都走 `MutableStateFlow.update { }`（CAS 重试），而不是 `value = value.copy(...)`：
 * 后者和主线程的 [refreshPermission] / [setDeleteOriginals] 竞争时会静默丢掉一方的更新。
 * `StateFlow` 本身线程安全，且相等的新值不会重发，UI 不会被无意义的重组淹没。
 *
 * ## 取消只有一条路
 * [cancel] 只翻 [cancelled] 标志，由 `BatchRunner` 在**两项之间**读它。绝不靠协程取消掐断循环：
 * 那样会跳过收尾的媒体扫描，产物进了磁盘、原文件删了，相册里却还留着指向已删文件的幽灵条目。
 */
class BatchViewModel(private val app: Application) : ViewModel() {

    val root = "/storage/emulated/0"
    private val gate = PermissionGate.of(app)
    val browser = DirBrowser(root)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    @Volatile
    private var cancelled = false

    init {
        refreshPermission()
    }

    fun refreshPermission() {
        val granted = gate.granted()
        _state.update { if (it.permissionGranted == granted) it else it.copy(permissionGranted = granted) }
    }

    /** root 之内（含 root 本身）的路径才能原样喂给 [FileStorage]；判定规则与 `resolve()` 一致。 */
    fun isSelectable(path: String): Boolean = path == root || path.startsWith("$root/")

    fun setSource(path: String) = selectPath(path) { it.copy(sourceDir = path) }

    fun setTarget(path: String) = selectPath(path) { it.copy(targetDir = path) }

    /**
     * 路径选择的唯一入口：批次跑着的时候不许改（正在写的这批用的是开工快照，改了只会让界面说谎），
     * root 之外的路径也不许改——[DirBrowser] 的绝对路径判定比存储层宽，能点到 `/sdcard`、
     * `/storage/emulated` 这种喂给 [FileStorage] 会被二次拼接的串（见 [isSelectable] 与 `DirItem` KDoc）。
     * 这两种拒绝都是静默的：UI 侧已经把浏览按钮和"选这里"禁掉了，这里只是把不变量钉死。
     */
    private fun selectPath(path: String, mutate: (UiState) -> UiState) {
        if (_state.value.running || !isSelectable(path)) return
        _state.update(mutate)
    }

    /** 同样拒绝在运行中改动删除开关——它决定这批会不会动原文件。 */
    fun setDeleteOriginals(value: Boolean) {
        if (_state.value.running) return
        _state.update { it.copy(deleteOriginals = value) }
    }

    /**
     * 开工。同一时刻只会有一个批次：[UiState.running] 为真时直接忽略（UI 也已经把按钮禁掉了）。
     *
     * 全局前置失败（spec §9）在这里就变成 [UiState.globalError]，不进报告列表：
     * - 源目录不是可读目录；
     * - 目标目录建不起来——`FileStorage.ensureDir` 抛 `IOException`，`BatchRunner.run`
     *   刻意让它逃出来，所以必须由这里兜住，绝不能让它掀翻协程。
     */
    fun start() {
        val snapshot = _state.value
        if (snapshot.running || !gate.granted()) return
        cancelled = false
        _state.update {
            it.copy(running = true, done = 0, total = 0, bytes = 0, items = emptyList(), globalError = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val storage = FileStorage(root)
            try {
                val source = File(snapshot.sourceDir)
                // 存在但读不出来（没授权时 stat 能过、listFiles 给 null）也算全局前置失败：
                // 只查 isDirectory 会让这一批静默变成"进度 0/0"，用户看不出为什么什么都没做。
                if (!source.isDirectory || !source.canRead()) {
                    _state.update { it.copy(globalError = "源目录不存在或不可读：${snapshot.sourceDir}") }
                    return@launch
                }
                try {
                    storage.ensureDir(snapshot.targetDir)
                } catch (e: Exception) {
                    _state.update {
                        it.copy(globalError = "目标目录无法创建：${e.message ?: e.javaClass.simpleName}")
                    }
                    return@launch
                }
                val runner = BatchRunner(storage)
                val options = BatchOptions(snapshot.sourceDir, snapshot.targetDir, snapshot.deleteOriginals)
                // 先 plan() 让用户立刻看到总数，再把同一份 PlanResult 原样交给 run()（Task 8 的约定：
                // 不重新推导计划）。
                val plan = runner.plan(options)
                _state.update { it.copy(total = plan.jobs.size + plan.unpaired.size) }
                val report = runner.run(plan, options, CancelToken { cancelled }) { item ->
                    _state.update { it.copy(done = it.done + 1, bytes = it.bytes + item.bytes, items = it.items + item) }
                }
                // invariant：`BatchRunner` 只在 rename 成功后删原文件，并把**已删除的绝对路径**
                // 放进 ItemResult.deletedSources。产物 + 被删原文件必须一起交给扫描器，
                // 否则相册里留着指向已删文件的条目（spec §7.6、R4）。
                val touched = ArrayList<String>()
                report.items.forEach { item ->
                    if (item.status == ItemStatus.SUCCESS) touched += "${options.targetDir}/${item.outputName}"
                    touched += item.deletedSources
                }
                MediaStoreSync.scan(app, touched)
            } catch (e: Exception) {
                // 兜底：任何逃出来的意外都算"整批被拒绝"，不能掀翻协程（取消不在这里做，见 cancel()）。
                _state.update { it.copy(globalError = "批次中断：${e.message ?: e.javaClass.simpleName}") }
            } finally {
                _state.update { it.copy(running = false) }
            }
        }
    }

    /** 让批次在下一项之前停下：正在写的项一定走完 rename 或 `.part` 清理（Task 8 的契约）。 */
    fun cancel() {
        cancelled = true
    }

    override fun onCleared() {
        // ViewModel 被回收时同样只翻标志；协程取消不是停止手段（它会让收尾的媒体扫描被跳过）。
        cancelled = true
        super.onCleared()
    }
}
