package dev.zayn.livephotobatch.app

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
import androidx.compose.ui.unit.dp
import dev.zayn.livephotobatch.app.browse.DirBrowser
import dev.zayn.livephotobatch.app.browse.DirItem
import dev.zayn.livephotobatch.app.browse.mergedRootEntries
import dev.zayn.livephotobatch.app.ui.BackRow
import dev.zayn.livephotobatch.app.ui.DetailRow
import dev.zayn.livephotobatch.app.ui.FolderRow
import dev.zayn.livephotobatch.app.ui.IosButton
import dev.zayn.livephotobatch.app.ui.IosButtonStyle
import dev.zayn.livephotobatch.app.ui.IosColor
import dev.zayn.livephotobatch.app.ui.IosMetrics
import dev.zayn.livephotobatch.app.ui.IosSheet
import dev.zayn.livephotobatch.app.ui.IosType
import dev.zayn.livephotobatch.app.ui.SectionCard
import dev.zayn.livephotobatch.app.ui.StatusRow
import dev.zayn.livephotobatch.app.ui.SwitchRow
import dev.zayn.livephotobatch.app.ui.ThinProgressBar
import dev.zayn.livephotobatch.core.FailureReason
import dev.zayn.livephotobatch.core.ItemResult
import dev.zayn.livephotobatch.core.ItemStatus
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 目录浏览弹窗现在在给哪个字段选路径；null 表示没打开。 */
private enum class Picking { Source, Target }

/**
 * 一屏装下全部功能：权限、两个目录、危险开关、进度与报告。
 *
 * 外观按 iOS「设置」的分组卡片重排（令牌与控件在 [dev.zayn.livephotobatch.app.ui]），
 * 三个刻意的设计点一条没动：
 * 1. **报告列表不用 `LazyColumn`。** 外层已经是一条 `verticalScroll` 的 `Column`，
 *    在里面再嵌一个纵向 `LazyColumn` 会让它带着无限高度去测量，Compose 直接抛
 *    `"Vertically scrollable component was measured with an infinity maximum height constraints"`。
 *    报告最多几百行，普通 `Column` + `forEach` 足够，也不需要滚动嵌套。
 * 2. **权限不在组合过程里读。** 授权状态由 [BatchViewModel] 构造时、`MainActivity.onResume`
 *    和这里的 `LaunchedEffect` 推进；组合期每次重组都调一次
 *    `Environment.isExternalStorageManager()`（一次 binder 调用）会在批次逐项回报时拖慢主线程。
 * 3. **路径只能来自 [DirectoryPicker]，且显示的串不许被截断。** 界面上显示的绝对路径就是批次
 *    真正会用的串（`DirBrowser` 给的都是真实路径，见 [BatchViewModel.isSelectable] 那条 root 之外的分叉），
 *    所以 [DetailRow] 的值一栏不设 `maxLines`，宁可换行也要把整条路径画全。
 */
@Composable
fun BatchScreen(vm: BatchViewModel, request: () -> Unit) {
    val state by vm.state.collectAsState()
    var picking by remember { mutableStateOf<Picking?>(null) }

    LaunchedEffect(Unit) { vm.refreshPermission() }

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
            Text(
                "Live Photo 批量合成",
                style = IosType.largeTitle,
                modifier = Modifier.padding(
                    start = IosMetrics.screenMargin,
                    end = IosMetrics.screenMargin,
                    top = 6.dp,
                    bottom = 14.dp,
                ),
            )

            if (!state.permissionGranted) {
                SectionCard(color = IosColor.noticeBackground) {
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

            SectionCard {
                DetailRow(
                    title = "源目录",
                    value = state.sourceDir,
                    enabled = !state.running,
                    onClick = { picking = Picking.Source },
                )
                DetailRow(
                    title = "目标目录",
                    value = state.targetDir,
                    enabled = !state.running,
                    divider = false,
                    onClick = { picking = Picking.Target },
                )
            }

            Spacer(Modifier.height(IosMetrics.sectionGap))

            SectionCard {
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
                        .padding(horizontal = IosMetrics.screenMargin)
                        .padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(IosMetrics.sectionGap))

            // 没授权就点不动：spec §9 把权限列为全局前置条件，别让人先按下开始再收一条错误。
            Column(Modifier.padding(horizontal = IosMetrics.screenMargin)) {
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
                SectionCard(color = IosColor.noticeBackground) {
                    Text(
                        it,
                        style = IosType.body.copy(color = IosColor.red),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(IosMetrics.sectionGap))

            SectionCard {
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
                    modifier = Modifier.padding(horizontal = IosMetrics.screenMargin),
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
                        .padding(horizontal = IosMetrics.screenMargin)
                        .padding(top = 6.dp, bottom = 8.dp),
                )
                SectionCard {
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
 * **只有一条列表**：`上一级`（有父级时）排在最前，后面就是这一层要显示的目录，共用一张卡片与
 * 同一套分隔线规则。在存储根那一级由 [mergedRootEntries] 把常用入口的中文别名和「相机」这种嵌套
 * 快捷方式并进真实子目录里 —— 之前"上面一坨预设、下面一坨子目录"的分段不再存在。
 *
 * [root] 之外（比如从根再点"上一级"到 `/storage/emulated`）照样能浏览——`DirBrowser.subdirs`
 * 只看 `startsWith("/")`，本来就是个宽口子；但 [selectable] 为假时会禁掉"选这里"并说明原因，
 * 因为那种串喂给 `FileStorage.resolve()` 会被重新拼到 root 之下，选了就等于把批次指向另一个目录。
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
    val listing by produceState<Listing?>(initialValue = null, browser, path) {
        value = withContext(Dispatchers.IO) {
            Listing(
                subdirs = browser.subdirs(path),
                parent = File(path).parentFile?.absolutePath ?: "",
            )
        }
    }
    val canPick = selectable(path)
    val now = listing
    val quick = presets

    // 导航栏标题放最后一级目录名，整条绝对路径留给副标题——两者都是同一个 path 的片段。
    val leaf = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
    val atRoot = File(path).absoluteFile == File(root).absoluteFile
    // 只出**一条**列表：根上把预设的中文别名与嵌套快捷方式并进真实子目录，子层就是该层的子目录。
    val entries = when {
        now == null -> emptyList()
        atRoot && quick != null -> mergedRootEntries(quick, now.subdirs, path)
        else -> now.subdirs
    }
    val rows: List<NavRow> = buildList {
        now?.parent?.takeIf { it.isNotEmpty() }?.let { add(NavRow("上一级", it, isBack = true)) }
        entries.forEach { add(NavRow(it.name, it.absolutePath, isBack = false)) }
    }

    IosSheet(
        title = leaf,
        subtitle = path,
        cancelText = "关闭",
        confirmText = "选这里",
        confirmEnabled = canPick,
        onDismiss = onDismiss,
        onConfirm = { onPick(path) },
    ) {
        if (!canPick) {
            Text(
                "这个目录在存储根 $root 之外，只能看，不能当源/目标。",
                style = IosType.footnote.copy(color = IosColor.red),
                modifier = Modifier.padding(horizontal = IosMetrics.screenMargin, vertical = 8.dp),
            )
        }
        SectionCard {
            rows.forEachIndexed { index, row ->
                if (row.isBack) {
                    BackRow(title = row.label, divider = index < rows.lastIndex) { path = row.path }
                } else {
                    FolderRow(title = row.label, divider = index < rows.lastIndex) { path = row.path }
                }
            }
            if (now != null && entries.isEmpty()) {
                Text(
                    "（没有可进入的子目录）",
                    style = IosType.footnote,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/** 一次目录读取的结果（[produceState] 只能发布一个值，子目录和上一级合并成一条）。 */
private data class Listing(val subdirs: List<DirItem>, val parent: String)

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
