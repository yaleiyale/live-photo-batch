package dev.zayn.vlive

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zayn.vlive.browse.DirBrowser
import dev.zayn.vlive.io.FileStorage
import dev.zayn.vlive.perm.PermissionGate
import dev.zayn.vlive.settings.BatchSettings
import dev.zayn.vlive.settings.PreferencesBatchSettings
import dev.zayn.vlive.settings.StoredPaths
import dev.zayn.vlive.core.BatchOptions
import dev.zayn.vlive.core.BatchRunner
import dev.zayn.vlive.core.CancelToken
import dev.zayn.vlive.core.ItemResult
import dev.zayn.vlive.core.ItemStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * 单屏 UI 的唯一状态。UI 只读它，不另存副本。
 *
 * - [sourceDir] / [targetDir]：**真实绝对路径**（`DirBrowser` 给什么就存什么），默认值是相机目录
 *   与产物目录。
 * - [deleteOriginals]：不可撤销的开关，UI 必须把它渲染成危险项。
 * - [permissionGranted]：`MANAGE_EXTERNAL_STORAGE` 的快照，未授权时 UI 禁止开工。
 * - [done] / [total] / [bytes]：逐项回调累加的进度；[bytes] 只来自成功项（跳过/失败项恒为 0）。
 * - [items]：已经回报过的每一项，按发生顺序追加，UI 直接当报告列表渲染。它只活到下一次
 *   [BatchViewModel.start]，进程被杀同样清零。其中的 `deletedSources` 只喂收尾扫描
 *   （见 [touchedPathsFor]），界面上不列"删了哪些原文件"的清单。
 * - [globalError]：全局异常的一行提示，三个来源：**整批被拒绝**（源目录不可读 / 目标目录建不起来 /
 *   批次意外中断）、**批次中途丢权限而提前停止**，或**收尾媒体扫描未确认**（磁盘事实已定，只是相册
 *   索引可能暂时不准）。只有一格，所以**先说的那条占着**：原因不该被自己引发的下游后果
 *   （"索引没确认"）覆盖掉。下一次 [BatchViewModel.start] 即重置。
 *   它是 [MessageText]（资源 id + 参数）而不是拼好的串——见那个类型的理由。
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
    val globalError: MessageText? = null,
)

/**
 * 一条还没解析的文案：只带资源 id 与格式化参数，UI 侧用 `stringResource(id, *args)` 按当前
 * 语言取值（默认英文，中文在 `values-zh`）。
 *
 * 不在这里就拼成 String：VM 要保住"纯 JVM 单测能跑"这个性质，一旦拼文案就得拖进 `Context`，
 * 而英文/中文两种语言里同一件事的说法不同，拼好的串会把语言焊死在状态里——系统语言切换后重建的
 * 组合读到的还是上一条语言的旧文案。
 *
 * [args] 用 `List<Any>` 而不是变长 `Any`：它要能被 data class 的 `equals` 比较（单测直接断言
 * 整条消息），而 `Array<Any>` 的相等是引用相等。
 */
data class MessageText(@StringRes val id: Int, val args: List<Any> = emptyList())

/**
 * 收尾扫描的**唯一计算处**：每一个写出的产物，加上每一个已删除的原文件，都必须到达媒体扫描
 * ——否则相册留着指向已删文件的幽灵条目。
 *
 * - 产物只认 SUCCESS 项；路径拼接 `"$targetDir/${item.outputName}"` 与核心层
 *   `BatchRunner.composeOne` 的 `"$dir/$name"` 是**逐字符同一约定**，:app 里这个拼接只许存在这一处，
 *   别再往协程里内联第二份。
 * - [ItemResult.deletedSources] 是核心层给出的**绝对路径**，原样转发、不再拼接。
 * - FAILED/SKIPPED 项的产物不存在（rename 之前一切已清理），只可能带 deletedSources——空。
 * - 取消场景不需要特判：`BatchRunner` 把取消标志查在两项之间，已提交/已回报的尾巴就在 [items] 里，
 *   逐条映射天然覆盖（`BatchViewModelTest` 钉了这一点）。
 */
internal fun touchedPathsFor(targetDir: String, items: List<ItemResult>): List<String> = buildList {
    items.forEach { item ->
        if (item.status == ItemStatus.SUCCESS) add("$targetDir/${item.outputName}")
        addAll(item.deletedSources)
    }
}

/**
 * 批次的所有权都在这里：读权限 → 校验全局前置条件 → `plan()` 出总数 → `run()` 逐项回报 →
 * 产物与被删原文件一起交给收尾扫描（[touchedPathsFor] + 注入的 [scanner]）。
 *
 * ## root 字符串契约（和 `FileStorage` / `DirBrowser` 共享同一个串）
 * 生产入口（[Application] 构造器）用的是**未规范化**的 `"/storage/emulated/0"`：不带尾斜杠、
 * 绝不空，而且同一个实例既喂 [FileStorage] 也喂 [DirBrowser]。理由见 `FileStorage.resolve()`：
 * 只有 `path == root || path.startsWith("$root/")` 才原样使用，root 一旦带尾斜杠，
 * `"/storage/emulated/0"` 本身就会被当相对路径二次拼接成 `/storage/emulated/0/storage/emulated/0`。
 * [isSelectable] 就是照抄这一条判定，用来挡住"浏览器里点到 root 之外"的路径——
 * `DirBrowser.subdirs` 只看 `startsWith("/")`，能浏览到 `/sdcard`、`/storage/emulated`，
 * 那些串喂给存储层会被重新拼到 root 之下，语义完全不同（`DirItem` 的 KDoc 写了这个分叉）。
 * [root] 允许测试注入临时目录：同一套规则换一个前缀，行为不变。
 *
 * ## 测试接缝
 * 权限供应器与收尾扫描器都是构造器参数：JVM 单元测试用假 lambda 驱动真协程（lifecycle 在没有
 * Android 主线程的环境里把 `viewModelScope` 退化为 `SupervisorJob + Dispatchers.Default`），
 * 不需要 Robolectric，也不新增依赖。
 *
 * ## 状态只在批次协程（[Dispatchers.IO]）上被逐项推进
 * 所以所有写入都走 `MutableStateFlow.update { }`（CAS 重试），而不是 `value = value.copy(...)`：
 * 后者和主线程的 [refreshPermission] / [setDeleteOriginals] 竞争时会静默丢掉一方的更新。
 * `StateFlow` 本身线程安全，且相等的新值不会重发，UI 不会被无意义的重组淹没。
 *
 * ## 取消与收尾扫描
 * [cancel] 只翻 [cancelled] 标志，由 `BatchRunner` 在**两项之间**读它；绝不靠协程取消掐断循环。
 * 收尾扫描不指望"没人取消这个协程"：扫描不变量由 [runBatch] 的 finally 里
 * `withContext(NonCancellable + Dispatchers.IO)` 兜底——外层 scope 在扫描挂起时被取消时，
 * NonCancellable 块仍然完整执行，扫描照常提交、[UiState.running] 照常落回 false
 * （`BatchViewModelTest` 的 scope-cancellation 用例钉着）。
 * 两个如实记录的残余（JVM 测不到/修不了，装机验收兜底）：
 * 1. 批次协程**还没被线程启动**就碰上 scope 已取消：整个协程体连 finally 都不执行（kotlinx 对
 *    取消后未启动的 start 直接丢弃；`BatchViewModelTest` 把"此时零文件效果、零扫描"钉成了测试）——
 *    好在此刻一个文件都没动过，相册不会因此留幽灵条目，代价只是 [UiState.running] 卡在 true
 *    （而 ViewModel 已销毁，没有活着的 UI 再读它）；
 * 2. **进程被杀**：协程、NonCancellable、扫描跟着一起死，产物与删除已既成事实，
 *    索引刷新只能等 MediaStore 自己的例行收敛。
 */
class BatchViewModel internal constructor(
    /** 权限供应器——生产路径委托 `PermissionGate.of()`，测试给假 lambda（每次现读，不缓存）。 */
    private val isGranted: () -> Boolean,
    /** 收尾扫描的提交点；返回 false = 未成功发起或未在超时内确认。 */
    private val scanner: suspend (List<String>) -> Boolean,
    /** 目录选择的持久化；生产是 DataStore，测试给内存假实现（同一个理由：VM 不碰 `Context`）。 */
    private val settings: BatchSettings,
    /** 存储根；生产入口固定为 DEFAULT_ROOT，测试注入临时目录。 */
    val root: String,
) : ViewModel() {

    constructor(app: Application) : this(
        isGranted = { PermissionGate.of().granted() },
        scanner = { paths -> MediaStoreSync.scan(app, paths) },
        settings = PreferencesBatchSettings(app),
        root = DEFAULT_ROOT,
    )

    val browser = DirBrowser(root)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    @Volatile
    private var cancelled = false

    /**
     * 用户是否已经自己选过**那一格**。`restore()` 是异步的（DataStore 第一次读要开文件），
     * 没有这两条守卫时，一次慢速恢复可以把用户刚点下去的选择**覆盖回去**。
     *
     * 分源/目标两格而不是一个总开关：合成用的源目录是"上次从哪儿合的"，改目标目录是常事，
     * 用一个标志位等于"只改了目标"就把记住的源一起丢回默认相机目录——下一批就从那儿挑图，
     * 开关打开时还包括删原文件。
     */
    @Volatile
    private var userPickedSource = false

    @Volatile
    private var userPickedTarget = false

    /**
     * 冷启动那一次目录恢复。[start] 必须等它，见那里——不等的话批次会拿 [UiState] 的默认值开工，
     * 也就是在用户没有选过的目录上合成、还可能删原文件。
     *
     * 后台读而不是同步读：DataStore 第一次访问要开文件，不该压在冷启动的同步路径上。
     * 显式给 Dispatchers.IO，理由和 runBatch 那条协程一样——viewModelScope 默认落在
     * Dispatchers.Main.immediate 上，纯 JVM 单测里没有主线程，那条 launch 会直接抛
     * "Module with the Main dispatcher had failed to initialize"。
     */
    private val restoreJob: Job = viewModelScope.launch(Dispatchers.IO) { restore() }

    init {
        refreshPermission()
    }

    /**
     * 把持久化的两个目录读回内存状态。**采纳前过一遍 [isSelectable]**：存下来的路径可能已经不再
     * 合法（换了 root 注入、或者上一次存的是 root 之外的串），那种情况静默回到默认值——让批次悄悄
     * 指向另一个目录，比丢掉一次记忆严重得多。
     *
     * 用户在这一轮里已经选过的那一格不被覆盖（[userPickedSource] / [userPickedTarget]），另一格
     * 照旧恢复。批次正在跑这一条**不是**放弃恢复的理由：[start] 会先等 [restoreJob]，所以"恢复还没
     * 落地就开始合成"这个窗口已经不存在了，用 `running` 抢在这里 return 反而只会让等完的批次拿到
     * 默认目录。
     *
     * `internal` 是为了让 JVM 测试能直接驱动它（不需要走 [viewModelScope] 的调度）。
     */
    internal suspend fun restore() {
        val stored = settings.load()
        val source = stored.sourceDir?.takeIf { isSelectable(it) }
        val target = stored.targetDir?.takeIf { isSelectable(it) }
        if (source == null && target == null) return
        _state.update {
            it.copy(
                sourceDir = if (userPickedSource) it.sourceDir else source ?: it.sourceDir,
                targetDir = if (userPickedTarget) it.targetDir else target ?: it.targetDir,
            )
        }
        Timber.i("restored directories: source=%s target=%s", source ?: "-", target ?: "-")
    }

    /**
     * 当前状态写回设置。失败由 [BatchSettings.save] 自己吞掉，这里不额外处理。
     *
     * 必须先等 [restoreJob]：`StoredPaths` 是**整份**写回的，恢复还没落地时状态里那一格仍是默认值，
     * 这时候 save 会把磁盘上记着的那一格覆盖掉——用户只是改了目标目录，下次冷启动却发现源目录
     * 回到了默认相机目录。join 之后重读状态，写的才是"恢复的结果 + 用户这次的改动"的并集。
     */
    private fun persist() {
        viewModelScope.launch(Dispatchers.IO) {
            restoreJob.join()
            val snapshot = _state.value
            settings.save(StoredPaths(snapshot.sourceDir, snapshot.targetDir))
        }
    }

    fun refreshPermission() {
        val granted = isGranted()
        _state.update { if (it.permissionGranted == granted) it else it.copy(permissionGranted = granted) }
    }

    /** root 之内（含 root 本身）的路径才能原样喂给 [FileStorage]；判定规则与 `resolve()` 一致。 */
    fun isSelectable(path: String): Boolean = path == root || path.startsWith("$root/")

    fun setSource(path: String) {
        if (!pathAccepted(path)) return
        userPickedSource = true
        _state.update { it.copy(sourceDir = path) }
        persist()
    }

    fun setTarget(path: String) {
        if (!pathAccepted(path)) return
        userPickedTarget = true
        _state.update { it.copy(targetDir = path) }
        persist()
    }

    /**
     * 路径选择的共同守卫：批次跑着的时候不许改（正在写的这批用的是开工快照，改了只会让界面说谎），
     * root 之外的路径也不许改——[DirBrowser] 的绝对路径判定比存储层宽，能点到 `/sdcard`、
     * `/storage/emulated` 这种喂给 [FileStorage] 会被二次拼接的串（见 [isSelectable] 与 `DirItem` KDoc）。
     * 两种拒绝都是静默的：UI 侧已经把浏览按钮和"选这里"禁掉了，这里只是把不变量钉死。
     *
     * 标志位只能在守卫**通过之后**置：误置一次，[restore] 就永远不再恢复那一格。
     */
    private fun pathAccepted(path: String): Boolean = !_state.value.running && isSelectable(path)

    /** 同样拒绝在运行中改动删除开关——它决定这批会不会动原文件。 */
    fun setDeleteOriginals(value: Boolean) {
        if (_state.value.running) return
        _state.update { it.copy(deleteOriginals = value) }
    }

    /**
     * 开工。同一时刻只会有一个批次：[UiState.running] 为真时直接忽略（UI 也已经把按钮禁掉了）。
     * 没授权同样静默拒绝——权限是全局前置条件，绝不带着读写去撞 SecurityException。
     */
    fun start() {
        if (_state.value.running || !isGranted()) return
        cancelled = false
        _state.update {
            it.copy(
                running = true, done = 0, total = 0, bytes = 0, items = emptyList(),
                globalError = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            // 先等冷启动那一次目录恢复落地，再取开工快照。不等就是拿 UiState 的默认值开工：
            // 在用户没有选过的目录上合成，开关打开时还包括删原文件。
            //
            // "开工快照"这条约定没有被放宽：running 在上面已经置为 true，setSource / setTarget /
            // setDeleteOriginals 在这段等待里全被同一个守卫挡住，所以 join 之后重读到的
            // 就是开工那一刻的状态，等待期间没有任何字段能动。
            restoreJob.join()
            runBatch(_state.value)
        }
    }

    /**
     * 批次主体。**生产入口只有 [start]**；internal、不含 Android 静态调用，JVM 测试用自己的协程
     * 直接驱动（`app/src/test/.../BatchViewModelTest.kt`）。
     *
     * 全局前置失败变成 [UiState.globalError]，不进报告列表：
     * - 快照路径不在 root 内：静默返回——[setSource] / [setTarget] 已保证写进 UiState 的都是可选
     *   路径，这条守卫把"UI 检查用的谓词"（DirBrowser 的 canPick）和"批次真正读写的路径"钉死成
     *   同一个 [isSelectable]，防止两条判定将来漂移分叉；
     * - 源目录不是可读目录；
     * - 目标目录建不起来——`FileStorage.ensureDir` 抛 `IOException`，`BatchRunner.run` 让它
     *   逃出来，所以必须由这里兜住，绝不能让它掀翻协程。
     */
    internal suspend fun runBatch(snapshot: UiState) {
        try {
            if (!isSelectable(snapshot.sourceDir) || !isSelectable(snapshot.targetDir)) return
            val storage = FileStorage(root)
            val source = File(snapshot.sourceDir)
            // 存在但读不出来（没授权时 stat 能过、listFiles 给 null）也算全局前置失败：
            // 只查 isDirectory 会让这一批静默变成"进度 0/0"，用户看不出为什么什么都没做。
            if (!source.isDirectory || !source.canRead()) {
                _state.update {
                    it.copy(globalError = MessageText(R.string.err_source_unreadable, listOf(snapshot.sourceDir)))
                }
                return
            }
            try {
                storage.ensureDir(snapshot.targetDir)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        globalError = MessageText(
                            R.string.err_target_uncreatable,
                            listOf(e.message ?: e.javaClass.simpleName),
                        ),
                    )
                }
                return
            }
            val runner = BatchRunner(storage)
            val options = BatchOptions(snapshot.sourceDir, snapshot.targetDir, snapshot.deleteOriginals)
            // 先 plan() 让用户立刻看到总数，再把同一份 PlanResult 原样交给 run()：不重新推导计划。
            val plan = runner.plan(options)
            _state.update { it.copy(total = plan.jobs.size + plan.unpaired.size) }
            // 日志只记"这一批的边界"和"没成功的项"：成功项已经在报告列表里，逐条再写一遍只是把
            // 文件撑大。
            Timber.i(
                "batch start: source=%s target=%s deleteOriginals=%b items=%d",
                snapshot.sourceDir, snapshot.targetDir, snapshot.deleteOriginals,
                plan.jobs.size + plan.unpaired.size,
            )
            // 回调同步、单线程：每一项在回报那一刻才进 _state.items，所以 finally 里从状态取的就是
            // "此刻已存在的全部磁盘事实"——包括取消之后跑完的那条尾巴。
            //
            // 取消通道兼做权限复查：除了外部停止，用户随时可能去系统设置里收回
            // MANAGE_EXTERNAL_STORAGE。不复查的话，剩下的每一项都会撞在一次读不出来的目录上，
            // 报告变成一整屏同质的 IO_ERROR——那堵墙既不说明原因，也把"已经提交了几个"淹掉。
            // 查在每一项之前正是取消标志所在的位置，所以停批不需要 :core 知道权限这东西。
            //
            // `permissionLost` 是普通 var：BatchRunner 与逐项回调都在这个协程的同一线程上跑，
            // 写它的只有取消令牌，读它的只有下面的收尾，没有跨线程可见性问题。
            var permissionLost = false
            runner.run(plan, options, CancelToken {
                if (cancelled) true
                else if (isGranted()) false
                else { permissionLost = true; true }
            }) { item ->
                if (item.status != ItemStatus.SUCCESS) {
                    Timber.i("item %s -> %s: %s", item.stem, item.status, item.reason)
                }
                if (item.deleteFailures.isNotEmpty()) {
                    Timber.w("item %s kept sources it could not delete: %s", item.stem, item.deleteFailures)
                }
                _state.update { it.copy(done = it.done + 1, bytes = it.bytes + item.bytes, items = it.items + item) }
            }
            if (permissionLost) {
                // 一行提示，外加把快照改成"没授权"——UI 的授权入口和禁用的开工按钮都读这一个字段，
                // 不改成 false 的话界面会继续宣称一切正常，直到用户下一次回到前台。
                // 走 update 而不是 value =：这条写发生在 IO 线程，主线程的 refreshPermission 随时可能撞上来。
                Timber.w("batch stopped: all-files access revoked after %d items", _state.value.done)
                _state.update {
                    it.copy(
                        permissionGranted = false,
                        globalError = MessageText(R.string.err_permission_lost),
                    )
                }
            }
        } catch (e: Exception) {
            // 含 CancellationException（外层 scope 取消）：报一行"批次中断"，但绝不因此跳过
            // finally 的收尾扫描——已经提交的文件必须到达媒体索引。
            _state.update {
                it.copy(
                    globalError = MessageText(R.string.err_batch_aborted, listOf(e.message ?: e.javaClass.simpleName)),
                )
            }
        } finally {
            // 收尾。外层协程已取消时，finally 里的 withContext(NonCancellable) 块会完整执行，块后的
            // 语句也会执行；running 落位写进块内，只为把"扫描 + 落位"钉成一个不再依赖外层命运的收尾。
            val paths = touchedPathsFor(snapshot.targetDir, _state.value.items)
            val settled = _state.value
            Timber.i(
                "batch end: done=%d/%d bytes=%d touched=%d cancelled=%b",
                settled.done, settled.total, settled.bytes, paths.size, cancelled,
            )
            if (paths.isEmpty()) {
                _state.update { it.copy(running = false) }
            } else {
                withContext(NonCancellable + Dispatchers.IO) {
                    val hint = try {
                        if (scanner(paths)) {
                            null
                        } else {
                            MessageText(R.string.err_scan_unconfirmed)
                        }
                    } catch (e: Exception) {
                        // MediaStoreSync 自己"绝不抛"（失败已在那一层进日志）；这里兜的是注入实现
                        // 未来的意外，不放宽到 Throwable。
                        MessageText(R.string.err_scan_failed, listOf(e.message ?: e.javaClass.simpleName))
                    }
                    if (hint != null) {
                        // 全局提示只有一格，先说的那条占着：批次已经因为丢权限/意外中断停下时，
                        // "索引没确认"只是同一个故障的下游后果，把原因换成后果等于把界面说糊涂。
                        _state.update { if (it.globalError == null) it.copy(globalError = hint) else it }
                    }
                    _state.update { it.copy(running = false) }
                }
            }
        }
    }

    /** 让批次在下一项之前停下：正在写的项一定走完 rename 或 `.part` 清理。 */
    fun cancel() {
        cancelled = true
    }

    override fun onCleared() {
        // ViewModel 被回收时翻标志让循环在项边界停；super 同时取消 viewModelScope。
        // 批次协程若已开跑，finally 的 NonCancellable 收尾照常完整执行；若还没被线程启动，
        // 整批不执行（也没动过文件），见类 KDoc 残余风险 1。
        cancelled = true
        super.onCleared()
    }

    private companion object {
        const val DEFAULT_ROOT = "/storage/emulated/0"
    }
}
