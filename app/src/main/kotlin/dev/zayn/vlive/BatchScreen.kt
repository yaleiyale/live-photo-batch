package dev.zayn.vlive

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.zayn.vlive.browse.DirBrowser
import dev.zayn.vlive.browse.DirItem
import dev.zayn.vlive.browse.STORAGE_ROOT_LABEL
import dev.zayn.vlive.browse.displayPath
import dev.zayn.vlive.browse.mergedRootEntries
import dev.zayn.vlive.ui.BackRow
import dev.zayn.vlive.ui.DetailRow
import dev.zayn.vlive.ui.FolderRow
import dev.zayn.vlive.ui.IosButton
import dev.zayn.vlive.ui.IosButtonStyle
import dev.zayn.vlive.ui.IosColor
import dev.zayn.vlive.ui.IosEntrance
import dev.zayn.vlive.ui.IosEntranceFallback
import dev.zayn.vlive.ui.IosMetrics
import dev.zayn.vlive.ui.IosMotion
import dev.zayn.vlive.ui.IosSheet
import dev.zayn.vlive.ui.IosType
import dev.zayn.vlive.ui.SectionCard
import dev.zayn.vlive.ui.StatusRow
import dev.zayn.vlive.ui.SwitchRow
import dev.zayn.vlive.ui.ThinProgressBar
import dev.zayn.vlive.ui.iosEnter
import dev.zayn.vlive.core.FailureReason
import dev.zayn.vlive.core.ItemResult
import dev.zayn.vlive.core.ItemStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 目录浏览弹窗现在在给哪个字段选路径；null 表示没打开。 */
private enum class Picking { Source, Target }

/**
 * 一屏装下全部功能：权限、两个目录、危险开关、进度与报告。
 *
 * 外观按 iOS「设置」的分组卡片重排（令牌与控件在 [dev.zayn.vlive.ui]），
 * 三个刻意的设计点一条没动：
 * 1. **报告列表不用 `LazyColumn`。** 外层已经是一条 `verticalScroll` 的 `Column`，
 *    在里面再嵌一个纵向 `LazyColumn` 会让它带着无限高度去测量，Compose 直接抛
 *    `"Vertically scrollable component was measured with an infinity maximum height constraints"`。
 *    报告最多几百行，普通 `Column` + `forEach` 足够，也不需要滚动嵌套。
 * 2. **权限不在组合过程里读。** 授权状态由 [BatchViewModel] 构造时、`MainActivity.onResume`
 *    和这里的 `LaunchedEffect` 推进；组合期每次重组都调一次
 *    `Environment.isExternalStorageManager()`（一次 binder 调用）会在批次逐项回报时拖慢主线程。
 * 3. **路径只能来自 [DirectoryPicker]，画出来的是它的显示形式，且不许被截断。** 值一栏走
 *    [displayPath]：存储根那一段换成「内部存储」，与弹窗副标题同一个形式（`/storage/emulated/0/DCIM/Camera`
 *    读作 `内部存储/DCIM/Camera`）。**批次真正用的仍是 `state.sourceDir` 那条绝对路径**，这里只是显示层，
 *    所以 [DetailRow] 的值一栏不设 `maxLines`，宁可换行也要把整条路径画全 —— 别名可以省前缀，截断不行。
 */
@Composable
fun BatchScreen(
    vm: BatchViewModel,
    entrance: IosEntrance = remember { IosEntrance() },
    request: () -> Unit,
) {
    val state by vm.state.collectAsState()
    var picking by remember { mutableStateOf<Picking?>(null) }

    LaunchedEffect(Unit) { vm.refreshPermission() }
    IosEntranceFallback(entrance)

    Box(
        // targetSdk 35+ 起窗口强制 edge-to-edge，底色必须铺到状态栏底下，否则顶部是一条系统给的接缝。
        Modifier
            .fillMaxSize()
            .background(IosColor.groupedBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                // 折叠屏内屏约 780dp 宽：内容封顶后居中，卡片不会被拉成一条横杠。
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            Column(Modifier.iosEnter(0, entrance)) {
                Text(
                    "vlive",
                    style = IosType.largeTitle,
                    modifier = Modifier.padding(
                        start = IosMetrics.screenMargin,
                        end = IosMetrics.screenMargin,
                        top = 6.dp,
                    ),
                )
                // 品牌名占大标题，功能说明退到副标题：这一屏以后还会放别的入口。
                Text(
                    "live photo 批量合成",
                    style = IosType.footnote.copy(color = IosColor.secondaryLabel),
                    modifier = Modifier.padding(
                        start = IosMetrics.screenMargin,
                        end = IosMetrics.screenMargin,
                        bottom = 14.dp,
                    ),
                )
            }

            if (!state.permissionGranted) {
                SectionCard(modifier = Modifier.iosEnter(1, entrance), color = IosColor.noticeBackground) {
                    Text(
                        "还没有“所有文件访问”权限，读写公共目录会被系统拒绝，批次不会开始。",
                        style = IosType.footnote.copy(color = IosColor.red),
                        modifier = Modifier.padding(16.dp),
                    )
                    IosButton(
                        text = "授予所有文件访问权限",
                        enabled = true,
                        modifier = Modifier
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        onClick = request,
                    )
                }
                Spacer(Modifier.height(IosMetrics.sectionGap))
            }

            SectionCard(Modifier.iosEnter(2, entrance)) {
                DetailRow(
                    title = "源目录",
                    value = displayPath(state.sourceDir, vm.root),
                    enabled = !state.running,
                    onClick = { picking = Picking.Source },
                )
                DetailRow(
                    title = "目标目录",
                    value = displayPath(state.targetDir, vm.root),
                    enabled = !state.running,
                    divider = false,
                    onClick = { picking = Picking.Target },
                )
            }

            Spacer(Modifier.height(IosMetrics.sectionGap))

            SectionCard(Modifier.iosEnter(3, entrance)) {
                SwitchRow(
                    title = "合成后删除原文件（不可撤销）",
                    checked = state.deleteOriginals,
                    enabled = !state.running,
                    // 破坏性开关用系统红而不是 iOS 默认的绿。
                    onColor = IosColor.red,
                    onCheckedChange = vm::setDeleteOriginals,
                )
            }
            if (state.deleteOriginals) {
                Text(
                    "只有改名成功的项目才会删源文件；删不掉的会在报告里标成警告，产物不会被回退成失败。",
                    style = IosType.footnote.copy(color = IosColor.red),
                    modifier = Modifier
                        .iosEnter(4, entrance)
                        .padding(horizontal = IosMetrics.screenMargin)
                        .padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(IosMetrics.sectionGap))

            // 没授权就点不动：spec §9 把权限列为全局前置条件，别让人先按下开始再收一条错误。
            Column(Modifier.iosEnter(5, entrance).padding(horizontal = IosMetrics.screenMargin)) {
                IosButton(text = "开始合成", enabled = state.permissionGranted && !state.running, onClick = vm::start)
                Spacer(Modifier.height(12.dp))
                IosButton(
                    text = "取消",
                    enabled = state.running,
                    style = IosButtonStyle.Secondary,
                    onClick = vm::cancel,
                )
            }

            state.globalError?.let {
                Spacer(Modifier.height(IosMetrics.sectionGap))
                SectionCard(modifier = Modifier.iosEnter(6, entrance), color = IosColor.noticeBackground) {
                    Text(
                        it,
                        style = IosType.body.copy(color = IosColor.red),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(IosMetrics.sectionGap))

            SectionCard(Modifier.iosEnter(7, entrance)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "进度 ${state.done}/${state.total}，已写出 ${state.bytes / 1024 / 1024} MB",
                        style = IosType.body,
                    )
                    Spacer(Modifier.height(10.dp))
                    ThinProgressBar(if (state.total == 0) 0f else state.done.toFloat() / state.total)
                }
            }

            if (state.items.isNotEmpty()) {
                Spacer(Modifier.height(IosMetrics.sectionGap))
                Text(
                    "报告",
                    style = IosType.title,
                    modifier = Modifier.iosEnter(8, entrance).padding(horizontal = IosMetrics.screenMargin),
                )
                // spec §9：单项失败不中断批次，但 UI 要在末尾汇总计数（磁盘写满就是逐项 IO_ERROR）。
                val counts = state.items.groupingBy { it.status }.eachCount()
                val warned = state.items.count { it.deleteFailures.isNotEmpty() }
                Text(
                    buildString {
                        append("成功 ${counts[ItemStatus.SUCCESS] ?: 0}")
                        append(" · 跳过 ${counts[ItemStatus.SKIPPED] ?: 0}")
                        append(" · 失败 ${counts[ItemStatus.FAILED] ?: 0}")
                        if (warned > 0) append(" · 原文件没删掉 $warned")
                    },
                    style = IosType.footnote,
                    modifier = Modifier
                        .iosEnter(8, entrance)
                        .padding(horizontal = IosMetrics.screenMargin)
                        .padding(top = 6.dp, bottom = 8.dp),
                )
                SectionCard(Modifier.iosEnter(9, entrance)) {
                    state.items.forEachIndexed { index, item ->
                        StatusRow(
                            dot = dotColor(item),
                            text = itemLine(item),
                            divider = index < state.items.lastIndex,
                        )
                    }
                }
            }
        }
    }

    picking?.let { which ->
        DirectoryPicker(
            browser = vm.browser,
            root = vm.root,
            selectable = vm::isSelectable,
            startPath = if (which == Picking.Source) state.sourceDir else state.targetDir,
            onDismiss = { picking = null },
            onPick = { path ->
                when (which) {
                    Picking.Source -> vm.setSource(path)
                    Picking.Target -> vm.setTarget(path)
                }
                picking = null
            },
        )
    }
}

/**
 * 目录浏览器，呈现为全屏模态卡片。起点是 [startPath]（不可用时退回 [root]），一级一级往下走。
 *
 * **只有一条列表**：`上一级`（父级还在 root 之内时）排在最前，后面就是这一层要显示的目录，
 * 共用一张卡片与同一套分隔线规则。根那一级由 [mergedRootEntries] 决定内容：真实子目录，命中
 * 预设的换成中文显示名（`Pictures → 图片`）。这里**没有任何快捷方式**——画出来和真实目录无法
 * 区分、文件管理里又根本没有那个条目（曾经的 `相机 = DCIM/Camera` 就是这个问题），所以到相机
 * 目录老老实实两跳：`DCIM → Camera`。
 *
 * **最顶层就是 root 的内容**（DCIM、下载、图片…），root 自己不再作为一个条目出现，
 * 也不给"从 root 再往上一级"的入口。这不是保守，是真机测出来的死角：`/storage/emulated`
 * 通常读不出来（`listFiles()` 给 null → 空列表），而 root 作为它的子项只显示成一个 `0`，
 * 退进去之后既看不到 DCIM 也回不到原来那层——一条没有回头路的分支。
 * [selectable] 为假的父级因此一律不列出来（同一判定也继续挡住"选这里"：那种串喂给
 * `FileStorage.resolve()` 会被重新拼到 root 之下，选了就等于把批次指向另一个目录）。
 *
 * **列目录绝不发生在组合期。** `presets()` / `subdirs()` / `File.parentFile` 背后是
 * `listFiles()` + 多次 `isDirectory` stat——都是磁盘调用。`remember` 只是缓存，不是副作用护栏：
 * 槽位恢复、被丢弃的重组都会重新执行它的 lambda，批次逐项回报的频繁重组里随时可能把 stat 打到
 * UI 线程上。所以列表读取挪进 [produceState] + [Dispatchers.IO]：组合只消费"已载入"的结果。
 * 展示内容不变，只是加载瞬间先渲染空列表。
 */
@Composable
private fun DirectoryPicker(
    browser: DirBrowser,
    root: String,
    selectable: (String) -> Boolean,
    startPath: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var path by remember(root, startPath) { mutableStateOf(if (selectable(startPath)) startPath else root) }
    val presets by produceState<List<DirItem>?>(initialValue = null, browser) {
        value = withContext(Dispatchers.IO) { browser.presets() }
    }
    // 上一级只能自己算：DirItem.parent 的定义是"这批条目出自哪个目录"（subdirs 里就是被列的
    // 目录本身，presets 里才是该项的父级），当不了从 path 往上走的串。
    // 这里用 java.io.File 的 parent 语义——和 DirBrowser 内部拼路径用的是同一套 API，不手写 "/"。
    val loaded by produceState<Listing?>(initialValue = null, browser, path) {
        value = withContext(Dispatchers.IO) {
            Listing(
                path = path,
                subdirs = browser.subdirs(path),
                parent = File(path).parentFile?.absolutePath ?: "",
            )
        }
    }

    // **画出来的是已经读完的那一层**，不是刚点下去的那一层：新目录读出来之前继续画旧的一层，
    // 读完了才整层换过去。三件事因此同时对齐——标题/副标题、列表、"选这里"要提交的路径；
    // 也不会出现"标题已经变成 Camera、列表还是 DCIM"或者加载那一瞬闪出空列表提示。
    var shown by remember(root, startPath) { mutableStateOf<Listing?>(null) }
    // 推入还是弹出：新层是当前层的子级就向左推，否则（回上一级）向右弹。
    var forward by remember(root, startPath) { mutableStateOf(true) }
    LaunchedEffect(loaded) {
        val fresh = loaded ?: return@LaunchedEffect
        forward = fresh.path.startsWith("${shown?.path?.trimEnd('/')}/")
        shown = fresh
    }
    val quick = presets
    // `shown` 是委托属性，智能转换不了，先取进局部变量再判空。
    val done = shown
    val view = done ?: Listing(path, emptyList(), "")
    // 还没读出来（首帧，或点了但新层未到）：这时不画"没有子目录"，那是在骗人。
    val settled = done != null && done.path == path

    // 别名与"上一级"的判定都按**画出来的那一层**算，所以返回手势、返回行、列表说的是同一件事。
    fun rowsFor(level: Listing): List<NavRow> {
        val atRoot = File(level.path).absoluteFile == File(root).absoluteFile
        val items = if (atRoot && quick != null) mergedRootEntries(quick, level.subdirs, level.path) else level.subdirs
        return buildList {
            // 父级出不了 root 才有"上一级"：退到 /storage/emulated 就读不出子目录、root 只剩一个
            // "0" 条目，是一条回不来的死路（见函数 KDoc）。root 这一级因此就是最顶层。
            level.parent.takeIf { it.isNotEmpty() && selectable(it) }?.let { add(NavRow("上一级", it, isBack = true)) }
            items.forEach { add(NavRow(it.name, it.absolutePath, isBack = false)) }
        }
    }
    val upPath = view.parent.takeIf { it.isNotEmpty() && selectable(it) }
    val atRoot = File(view.path).absoluteFile == File(root).absoluteFile
    // 标题是最后一级目录名，副标题是整条路径的**显示形式**：root 那一级读作「内部存储」，
    // 往下就是「内部存储/DCIM/Camera」。这里刻意不把 "/storage/emulated/0" 摊给用户看——
    // 那串既没人读得懂，root 又确实是可以选作源/目标的，所以用别名而不是藏掉。
    val leaf = if (atRoot) STORAGE_ROOT_LABEL else view.path.trimEnd('/').substringAfterLast('/').ifEmpty { view.path }
    val subtitle = displayPath(view.path, root)
    val canPick = selectable(view.path)

    IosSheet(
        title = leaf,
        subtitle = subtitle,
        cancelText = "关闭",
        confirmText = "选这里",
        confirmEnabled = canPick,
        onDismiss = onDismiss,
        onConfirm = { onPick(view.path) },
    ) {
        // 返回手势**先当"上一级"用**，到 root（没有可去的父级）才让弹窗关掉。
        // BackHandler 必须在 IosSheet 的 content 里——那是 Dialog 自己的组合，返回键先落到它的
        // OnBackPressedDispatcher 上；放在外面这层（Activity 组合）拦不到。
        val up = upPath
        BackHandler(enabled = up != null) { up?.let { path = it } }

        if (!canPick) {
            Text(
                "这个目录在存储根 $root 之外，只能看，不能当源/目标。",
                style = IosType.footnote.copy(color = IosColor.red),
                modifier = Modifier.padding(horizontal = IosMetrics.screenMargin, vertical = 8.dp),
            )
        }
        // 换层时整层推入/弹出 + 淡入淡出：点进去向左推、返回向右弹，和标题的改动同一时刻发生。
        AnimatedContent(
            targetState = view,
            transitionSpec = {
                val slide = tween<IntOffset>(IosMotion.ENTER_MS, easing = IosMotion.EaseOut)
                val fade = tween<Float>(IosMotion.ENTER_MS, easing = IosMotion.EaseOut)
                // +1：新层从右边推进来（进子目录）；-1：从左边推进来（回上一级）。
                // 用 slideIn/OutHorizontally 而不是 AnimatedContentScope 的 slideIntoContainer——
                // 后者是作用域里的成员扩展，在这里这个位置上取不到。
                val sign = if (forward) 1 else -1
                (slideInHorizontally(slide) { sign * it } + fadeIn(fade))
                    .togetherWith(slideOutHorizontally(slide) { -sign * it } + fadeOut(tween(IosMotion.ENTER_MS / 2)))
            },
            label = "folderLevel",
        ) { level ->
            val rows = rowsFor(level)
            SectionCard {
                rows.forEachIndexed { index, row ->
                    if (row.isBack) {
                        BackRow(title = row.label, divider = index < rows.lastIndex) { path = row.path }
                    } else {
                        FolderRow(title = row.label, divider = index < rows.lastIndex) { path = row.path }
                    }
                }
                if (settled && rows.none { !it.isBack }) {
                    Text(
                        "（没有可进入的子目录）",
                        style = IosType.footnote,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

/** 一层目录读完的结果（[produceState] 只能发布一个值，子目录和上一级合并成一条）。 */
private data class Listing(val path: String, val subdirs: List<DirItem>, val parent: String)

/** 卡片里的一行导航项；[isBack] 决定用返回样式还是文件夹样式。 */
private data class NavRow(val label: String, val path: String, val isBack: Boolean)

/** 报告行左侧的状态点颜色。原文件删不掉是"成功但带警告"，所以走橙色。 */
private fun dotColor(item: ItemResult): Color = when (item.status) {
    ItemStatus.SUCCESS -> if (item.deleteFailures.isEmpty()) IosColor.green else IosColor.orange
    ItemStatus.SKIPPED -> IosColor.tertiaryLabel
    ItemStatus.FAILED -> IosColor.red
}

/** 一行报告文案。`when (item.status)` 的三个分支是穷尽的，核心层加状态时这里会编译不过。 */
private fun itemLine(item: ItemResult): String {
    val head = when (item.status) {
        ItemStatus.SUCCESS -> "成功 ${item.stem} → ${item.outputName}"
        ItemStatus.SKIPPED -> "跳过 ${item.stem}：${reasonText(item.reason)}"
        ItemStatus.FAILED -> "失败 ${item.stem}：${reasonText(item.reason)}"
    }
    // deleteFailures 只会挂在 SUCCESS 上：Task 8 规定改名成功后状态不再被回改写，
    // 删不掉的源文件是警告。所以警告必须跟着 SUCCESS 一起显示，不能只在失败分支里拼。
    val warning = if (item.deleteFailures.isEmpty()) ""
    else "（原文件删除失败：${item.deleteFailures.joinToString()}）"
    return head + warning
}

/** `FailureReason` 的中文说法；spec §9 要求面向用户给中文短语，同时保留机器可读的枚举名。 */
private fun reasonText(reason: FailureReason?): String {
    val zh = when (reason) {
        null -> "未说明"
        FailureReason.NOT_JPEG -> "不是 JPEG"
        FailureReason.MALFORMED_JPEG -> "JPEG 结构损坏"
        FailureReason.ALREADY_MOTION_PHOTO -> "已是动态照片"
        FailureReason.EXTENDED_XMP_UNSUPPORTED -> "扩展 XMP 不支持"
        FailureReason.NOT_MP4 -> "不是 MP4"
        FailureReason.IO_ERROR -> "读写故障"
        FailureReason.NO_PAIR -> "没有配对的文件"
    }
    return if (reason == null) zh else "$zh（${reason.name}）"
}
