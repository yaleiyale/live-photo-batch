package dev.zayn.vlive

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.zayn.vlive.browse.DirBrowser
import dev.zayn.vlive.browse.DirItem
import dev.zayn.vlive.browse.displayPath
import dev.zayn.vlive.browse.mergedRootEntries
import dev.zayn.vlive.ui.BackRow
import dev.zayn.vlive.ui.DetailRow
import dev.zayn.vlive.ui.FilterChipRow
import dev.zayn.vlive.ui.FolderRow
import dev.zayn.vlive.ui.IosButton
import dev.zayn.vlive.ui.IosButtonStyle
import dev.zayn.vlive.ui.IosEntrance
import dev.zayn.vlive.ui.IosEntranceFallback
import dev.zayn.vlive.ui.IosMetrics
import dev.zayn.vlive.ui.IosMotion
import dev.zayn.vlive.ui.IosSheet
import dev.zayn.vlive.ui.IosStyle
import dev.zayn.vlive.ui.LocalIosStyle
import dev.zayn.vlive.ui.ReportCard
import dev.zayn.vlive.ui.ReportPager
import dev.zayn.vlive.ui.SectionCard
import dev.zayn.vlive.ui.StatusRow
import dev.zayn.vlive.ui.SwitchRow
import dev.zayn.vlive.ui.ThinProgressBar
import dev.zayn.vlive.ui.iosEnter
import dev.zayn.vlive.ui.iosGlass
import dev.zayn.vlive.ui.iosGlassBar
import dev.zayn.vlive.ui.titleBarAlpha
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
 * 1. **报告列表不用 `LazyColumn`，改用整表折叠 + 分页。** 外层已经是一条 `verticalScroll` 的 `Column`，
 *    在里面再嵌一个纵向 `LazyColumn` 会让它带着无限高度去测量，Compose 直接抛
 *    `"Vertically scrollable component was measured with an infinity maximum height constraints"`。
 *    于是组合数量由 [reportPage] 的页大小钉住（一次只画一页），而不是由这批有多少项决定 ——
 *    要限的本来就是"组合 + 测量 + 排版多少行"，`state.items` 那几条字符串不值钱。
 * 2. **权限不在组合过程里读。** 授权状态由 [BatchViewModel] 构造时、`MainActivity.onResume`
 *    和这里的 `LaunchedEffect` 推进；组合期每次重组都调一次
 *    `Environment.isExternalStorageManager()`（一次 binder 调用）会在批次逐项回报时拖慢主线程。
 * 3. **路径只能来自 [DirectoryPicker]，画出来的是它的显示形式，且不许被截断。** 值一栏走
 *    [displayPath]：存储根那一段换成 `folder_internal_storage`，与弹窗副标题同一个形式
 *    （`/storage/emulated/0/DCIM/Camera` 读作 `内部存储/DCIM/Camera`）。**批次真正用的仍是
 *    `state.sourceDir` 那条绝对路径**，这里只是显示层，所以 [DetailRow] 的值一栏不设
 *    `maxLines`，宁可换行也要把整条路径画全 —— 别名可以省前缀，截断不行。
 *
 * ## 这一屏没有一个字面文案
 * 全部走 `stringResource`：默认（未限定）语言是英文，中文在 `values-zh`，跟随系统语言。
 * [BatchViewModel] 那边同理——[UiState.globalError] 带的是资源 id 与参数，不是拼好的中文串，
 * 所以纯 JVM 单测不必把资源系统拖进来，UI 侧才按当前语言解析。
 */
@Composable
fun BatchScreen(
    vm: BatchViewModel,
    entrance: IosEntrance = remember { IosEntrance() },
    request: () -> Unit,
) {
    val s = LocalIosStyle.current
    val state by vm.state.collectAsState()
    var picking by remember { mutableStateOf<Picking?>(null) }
    val rootLabel = stringResource(R.string.folder_internal_storage)
    val aliases = folderAliases(vm.root)

    LaunchedEffect(Unit) { vm.refreshPermission() }
    IosEntranceFallback(entrance)

    val scroll = rememberScrollState()
    val haze = rememberHazeState()
    val glass = iosGlass()
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    val collapsePx = with(LocalDensity.current) { IosMetrics.collapseRange.toPx() }.toInt()
    // 「玻璃边缘 == 卡片边缘」这条不变量只在这一处算：内容层吃的是裸 inset（那一段视口留白），
    // 卡片自己再补 screenMargin，两条 bar 用的就是这份已经加好 screenMargin 的横向留白 ——
    // 三处相加必须落到同一条线上，所以它得是一个值，而不是各算各的、慢慢飘开。
    val sideGutter = PaddingValues(
        start = insets.calculateLeftPadding(layoutDirection) + IosMetrics.screenMargin,
        end = insets.calculateRightPadding(layoutDirection) + IosMetrics.screenMargin,
    )
    // 底部栏的落点 = 导航栏 inset + 8dp 间距。内容层下沿那段是在这半段之上再加
    // barContentHeight + 16dp；两处共用同一个表达式，这个等式就是最后一张卡片不被玻璃压住的原因。
    val bottomGutter = insets.calculateBottomPadding() + IosMetrics.barBottomGap
    // `scroll.value` 带 @FrequentlyChangingValue，组合期直接读 = 整屏每帧重组一次；lint 给的解法
    // 就是 derivedStateOf，而且语义正好对上：alpha 只在 0..collapseRange 这一段里变，顶到 1f 或回到
    // 0f 之后再多滚都不发布新值，所以 [GlassTitleBar] 里那道门只在"越过 0"那一刻翻一次。
    // 不套 animateFloatAsState —— spec 要求 bar 跟着手指走，加动画等于让它落后于滚动。
    // 这里刻意**不**用 `by` 委托：委托点就是这个 composable 的顶层，读一次就把装着整个内容层与
    // 底部栏的那个 Box lambda 拖进重组（每帧重跑报告的 groupingBy/count/reportPage）。传 State 下去，
    // 让 [GlassTitleBar] 自己读，无效化范围就只有那一小块玻璃。
    val titleAlpha = remember { derivedStateOf { titleBarAlpha(scroll.value, collapsePx) } }

    Box(
        // targetSdk 35+ 起窗口强制 edge-to-edge，底色必须铺到状态栏底下，否则顶部是一条系统给的接缝。
        Modifier
            .fillMaxSize()
            .background(s.groupedBackground),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                // 折叠屏内屏约 780dp 宽：内容封顶后居中，卡片不会被拉成一条横杠。
                .widthIn(max = IosMetrics.contentMaxWidth)
                .fillMaxSize()
                // 这一层是主屏窗口里唯一的模糊源，两条 bar 都从它取位图。
                .hazeSource(haze)
                // inset 的归属变了：windowInsetsPadding 会把视口本身缩小，内容永远划不到 bar 底下，
                // 玻璃就糊不出东西；留白必须挂在 verticalScroll **之后** —— 那条链上 verticalScroll
                // 之后的是滚动内容自己的 padding（首尾留白、视口满屏），之前的仍然是缩视口。
                // compose-foundation 1.12.1 的 verticalScroll 没有 contentPadding 形参（javap 实测只有
                // enabled / flingBehavior / reverseScrolling / overscrollEffect），所以这份 PaddingValues
                // 由 Modifier.padding(paddingValues = …) 来吃，效果与计划的 contentPadding 同形。
                .verticalScroll(state = scroll)
                .padding(
                    paddingValues = PaddingValues(
                        start = insets.calculateLeftPadding(layoutDirection),
                        top = insets.calculateTopPadding(),
                        end = insets.calculateRightPadding(layoutDirection),
                        // 底部栏的总占位 = 导航栏 inset + 8dp 间距 + 74dp 内容 + 16dp 余量。
                        bottom = bottomGutter + IosMetrics.barContentHeight + 16.dp,
                    ),
                ),
        ) {
            Column(Modifier.iosEnter(0, entrance)) {
                Text(
                    stringResource(R.string.app_name),
                    style = s.largeTitle,
                    modifier = Modifier.padding(
                        start = IosMetrics.screenMargin,
                        end = IosMetrics.screenMargin,
                        top = 6.dp,
                    ),
                )
                // 品牌名占大标题，功能说明退到副标题：这一屏以后还会放别的入口。
                Text(
                    stringResource(R.string.screen_subtitle),
                    style = s.footnote.copy(color = s.secondaryLabel),
                    modifier = Modifier.padding(
                        start = IosMetrics.screenMargin,
                        end = IosMetrics.screenMargin,
                        bottom = 14.dp,
                    ),
                )
            }

            if (!state.permissionGranted) {
                SectionCard(modifier = Modifier.iosEnter(1, entrance), color = s.noticeBackground) {
                    Text(
                        stringResource(R.string.perm_missing),
                        style = s.footnote.copy(color = s.red),
                        modifier = Modifier.padding(16.dp),
                    )
                    IosButton(
                        text = stringResource(R.string.perm_grant),
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
                    title = stringResource(R.string.dir_source),
                    value = displayPath(state.sourceDir, vm.root, rootLabel),
                    enabled = !state.running,
                    onClick = { picking = Picking.Source },
                )
                DetailRow(
                    title = stringResource(R.string.dir_target),
                    value = displayPath(state.targetDir, vm.root, rootLabel),
                    enabled = !state.running,
                    divider = false,
                    onClick = { picking = Picking.Target },
                )
            }

            Spacer(Modifier.height(IosMetrics.sectionGap))

            SectionCard(Modifier.iosEnter(3, entrance)) {
                SwitchRow(
                    title = stringResource(R.string.del_switch),
                    checked = state.deleteOriginals,
                    enabled = !state.running,
                    // 破坏性开关用系统红而不是 iOS 默认的绿。
                    onColor = s.red,
                    onCheckedChange = vm::setDeleteOriginals,
                )
            }
            if (state.deleteOriginals) {
                Text(
                    stringResource(R.string.del_switch_hint),
                    style = s.footnote.copy(color = s.red),
                    modifier = Modifier
                        .iosEnter(4, entrance)
                        .padding(horizontal = IosMetrics.screenMargin)
                        .padding(top = 8.dp),
                )
            }

            state.globalError?.let { message ->
                Spacer(Modifier.height(IosMetrics.sectionGap))
                SectionCard(modifier = Modifier.iosEnter(6, entrance), color = s.noticeBackground) {
                    Text(
                        stringResource(message.id, *message.args.toTypedArray()),
                        style = s.body.copy(color = s.red),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(IosMetrics.sectionGap))

            SectionCard(Modifier.iosEnter(7, entrance)) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(
                            R.string.progress_line,
                            state.done,
                            state.total,
                            state.bytes / 1024 / 1024,
                        ),
                        style = s.body,
                    )
                    Spacer(Modifier.height(10.dp))
                    ThinProgressBar(if (state.total == 0) 0f else state.done.toFloat() / state.total)
                }
            }

            if (state.items.isNotEmpty()) {
                Spacer(Modifier.height(IosMetrics.sectionGap))
                // 整张报告默认收成一行：折叠时下面那个 content 连组合都不发生，这才是"别占太多"的落点。
                var open by rememberSaveable { mutableStateOf(false) }
                var filter by rememberSaveable { mutableStateOf(ReportFilter.All) }
                var page by rememberSaveable { mutableIntStateOf(0) }
                // spec §9：单项失败不中断批次，但 UI 要在末尾汇总计数（磁盘写满就是逐项 IO_ERROR）。
                val counts = state.items.groupingBy { it.status }.eachCount()
                val warned = state.items.count { it.deleteFailures.isNotEmpty() }
                val shown = reportPage(state.items, filter, page)
                ReportCard(
                    title = stringResource(R.string.report_title),
                    summary = stringResource(
                        R.string.report_summary,
                        counts[ItemStatus.SUCCESS] ?: 0,
                        counts[ItemStatus.SKIPPED] ?: 0,
                        counts[ItemStatus.FAILED] ?: 0,
                    ) + if (warned > 0)
                        pluralStringResource(R.plurals.report_summary_warning, warned, warned)
                    else "",
                    expanded = open,
                    modifier = Modifier.iosEnter(8, entrance),
                    onToggle = { open = !open },
                ) {
                    FilterChipRow(
                        labels = listOf(
                            stringResource(R.string.filter_all),
                            stringResource(R.string.filter_success),
                            stringResource(R.string.filter_skipped),
                            stringResource(R.string.filter_failed),
                        ),
                        selected = filter.ordinal,
                        modifier = Modifier
                            .padding(start = IosMetrics.rowPadding, end = IosMetrics.rowPadding)
                            .padding(top = 10.dp, bottom = 4.dp),
                        onSelect = { index ->
                            filter = ReportFilter.entries[index]
                            page = 0
                        },
                    )
                    shown.rows.forEachIndexed { index, item ->
                        StatusRow(
                            dot = dotColor(item, s),
                            text = itemLine(item),
                            divider = index < shown.rows.lastIndex,
                        )
                    }
                    if (shown.rows.isEmpty()) {
                        Text(
                            stringResource(R.string.report_empty_filter),
                            style = s.footnote,
                            modifier = Modifier.padding(IosMetrics.rowPadding),
                        )
                    }
                    // 只有一页时不画分页行：没有可去的地方的按钮不该占地方。
                    if (shown.pages > 1) {
                        ReportPager(
                            label = stringResource(R.string.page_indicator, shown.page + 1, shown.pages),
                            prev = stringResource(R.string.page_prev),
                            next = stringResource(R.string.page_next),
                            canPrev = shown.page > 0,
                            canNext = shown.page < shown.pages - 1,
                            onPrev = { page = shown.page - 1 },
                            onNext = { page = shown.page + 1 },
                            modifier = Modifier
                                .padding(start = IosMetrics.rowPadding, end = IosMetrics.rowPadding)
                                .padding(top = 6.dp, bottom = 10.dp),
                        )
                    }
                }
            }
        }

        // 大标题留在滚动流里，玻璃栏画的是折叠后的紧凑标题；alpha 由滚动位置直接决定，
        // 不做动画（惯性滚动时 animateFloatAsState 只会让 bar 落后于手指）。
        // 这一整块（含 `alpha > 0` 那道门）都在 [GlassTitleBar] 里，只因为它读 alpha 的那一层
        // 必须尽可能小 —— 见那个函数的 KDoc。
        GlassTitleBar(
            alpha = titleAlpha,
            haze = haze,
            insets = insets,
            sideGutter = sideGutter,
        )

        // 开始 / 取消从滚动流里搬到这条常驻栏：位置不能跟着内容跳，主操作要一直够得着；
        // 整条 bar 是这一区域唯一的那块玻璃，两颗按钮只加自己的 tint，绝不再各糊一层（spec §1.1）。
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 与内容层同一道封顶：折叠屏上 bar 不会比卡片宽出一截。
                .widthIn(max = IosMetrics.contentMaxWidth)
                .padding(paddingValues = sideGutter)
                // bar 自己的落点：只抬「导航 inset + 8dp 间距」，与内容层预留的下沿共用 bottomGutter
                // 这同一个值，两处数字才不会再各算各的、慢慢飘开。
                .padding(bottom = bottomGutter)
                .iosEnter(5, entrance)
                // 圆角必须显式传 glassRadius：贴边的两条 bar 走默认 0dp 直角，底部这条要读作连续圆角。
                .iosGlassBar(glass = glass, state = haze, radius = IosMetrics.glassRadius)
                // bar 的高度钉在 IosMetrics.barContentHeight 上，不靠「上下各 12dp + 50dp 按钮」三个数
                // 刚好凑出 74dp：那种等式只活在注释里，改任一数字 bar 上沿就飘出内容层预留的那段。
                // 现在两侧共用同一个 token，padding 之后剩下的 74-12-12 正好是按钮的 50dp。
                .height(IosMetrics.barContentHeight)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IosButton(
                text = stringResource(R.string.action_start),
                // 没授权就点不动：spec §9 把权限列为全局前置条件，别让人先按下开始再收一条错误。
                enabled = state.permissionGranted && !state.running,
                onClick = vm::start,
                // 宽度靠 weight 分，不写死 dp：主操作占大头，随语言/字号自动伸缩。
                modifier = Modifier.weight(1f),
            )
            // 两颗按钮之间只用这 12dp 撑开，不给按钮定死宽度。
            Box(Modifier.width(12.dp))
            IosButton(
                text = stringResource(R.string.action_cancel),
                // 未运行时禁用而不是隐藏：用户要一直看得见「现在能不能取消」（沿用原实现语义）。
                enabled = state.running,
                style = IosButtonStyle.Secondary,
                onClick = vm::cancel,
                // 取消比开始窄，但两种语言的字宽不同，所以同样只给比例不给 dp。
                modifier = Modifier.weight(0.45f),
            )
        }
    }

    picking?.let { which ->
        DirectoryPicker(
            browser = vm.browser,
            root = vm.root,
            rootLabel = rootLabel,
            aliases = aliases,
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
 * 主屏顶部那条玻璃标题栏：画的是折叠后的紧凑标题，大标题本身留在滚动流里。
 *
 * 单独成一个 `BoxScope` 私有函数只为一件事 —— **`alpha` 以 [State] 传进来、只在这里读 `.value`**。
 * 委托点如果放在 [BatchScreen] 顶层，折叠区间内每翻一次值都会去无效化那个装着整个内容层与底部栏的
 * `Box(content = …)` lambda（它是捕获型的），于是每帧重跑报告那段逐项加工（`groupingBy{}.eachCount()`
 * / `count{}` / `reportPage(…)`）并把每个 `iosEnter` 的入参重建一遍。无效化范围收到这一小块玻璃上，
 * 代价就只有这条 bar。
 *
 * 三条约束原样保留：
 * - `alpha > 0f` 才进组合（不可见时不画，也就不付模糊的代价，同时不会有"读得到一个看不见的标题"）；
 * - **不做动画**：值直接来自滚动位置，`animateFloatAsState` 只会让 bar 落后于手指（spec §2.2）；
 * - 顺序仍是**先 `iosGlassBar` 再 `padding(top = inset)`**，反过来会在状态栏那一条和玻璃之间留一条
 *   接缝 —— 与冷启动时已经被投诉过的那条是同一类。
 *
 * 传进来的是 `alpha`/`haze`/`insets`/`sideGutter`，底部栏与内容层读的是同一份；`glass` 不在其中，它出自 `LocalIosGlass`，子组合 `iosGlass()` 现取。
 */
@Composable
private fun BoxScope.GlassTitleBar(
    alpha: State<Float>,
    haze: HazeState,
    insets: PaddingValues,
    sideGutter: PaddingValues,
) {
    val titleAlpha = alpha.value
    if (titleAlpha <= 0f) return
    val s = LocalIosStyle.current
    val glass = iosGlass()
    // 主屏顶栏两侧没有按钮，不需要弹窗那种 action reserve；标题只按「横向缺口 + 页边距」内缩，
    // 这样既躲得开刘海/挖孔，又不把标题凭空挤窄。这道留白与底部栏、卡片边缘是同一个 [sideGutter]。
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .iosGlassBar(glass = glass, state = haze, barAlpha = titleAlpha),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = insets.calculateTopPadding())
                .height(IosMetrics.topBarHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.app_name),
                style = s.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(paddingValues = sideGutter),
            )
        }
    }
}

/**
 * 存储根下几个常用目录的**显示名别名**（真实名 → 当前语言的说法）。
 *
 * 键是 `<root>/<真实目录名>`：拼串用 `/`，因为 [mergedRootEntries] 两侧都过 `File.absoluteFile`
 * 规范化，而设备上的 root 本来就是 POSIX 串。
 *
 * 英文那一份的值**与磁盘真实名相同**（`Movies → Movies`），不是偷懒：这一屏主界面的路径行画的是
 * 真名（`Internal storage/Movies`），弹窗里把同一个目录叫成别的名字就会出现两个说法。中文里
 * `视频 / 图片 / 下载` 是系统文件管理器自己在用的叫法，跟着它才有一致性；换语言时别名自然退化成
 * 恒等映射，列表内容一个字节都没变。
 */
@Composable
private fun folderAliases(root: String): Map<String, String> {
    val movies = stringResource(R.string.folder_movies)
    val pictures = stringResource(R.string.folder_pictures)
    val download = stringResource(R.string.folder_download)
    return remember(root, movies, pictures, download) {
        mapOf("$root/Movies" to movies, "$root/Pictures" to pictures, "$root/Download" to download)
    }
}

/**
 * 目录浏览器，呈现为全屏模态卡片。起点是 [startPath]（不可用时退回 [root]），一级一级往下走。
 *
 * **只有一条列表**：`上一级`（父级还在 root 之内时）排在最前，后面就是这一层要显示的目录，
 * 共用一张卡片与同一套分隔线规则。根那一级由 [mergedRootEntries] 决定内容：真实子目录，命中
 * [aliases] 的换成本地显示名（`Pictures → 图片`）。这里**没有任何快捷方式**——画出来和真实目录
 * 无法区分、文件管理里又根本没有那个条目（曾经的 `相机 = DCIM/Camera` 就是这个问题），所以到相机
 * 目录老老实实两跳：`DCIM → Camera`。
 *
 * **最顶层就是 root 的内容**（DCIM、下载、图片…），root 自己不再作为一个条目出现，
 * 也不给"从 root 再往上一级"的入口。这不是保守，是真机测出来的死角：`/storage/emulated`
 * 通常读不出来（`listFiles()` 给 null → 空列表），而 root 作为它的子项只显示成一个 `0`，
 * 退进去之后既看不到 DCIM 也回不到原来那层——一条没有回头路的分支。
 * [selectable] 为假的父级因此一律不列出来（同一判定也继续挡住"选这里"：那种串喂给
 * `FileStorage.resolve()` 会被重新拼到 root 之下，选了就等于把批次指向另一个目录）。
 *
 * **列目录绝不发生在组合期。** `subdirs()` / `File.parentFile` 背后是 `listFiles()` + 多次
 * `isDirectory` stat——都是磁盘调用。`remember` 只是缓存，不是副作用护栏：槽位恢复、被丢弃的
 * 重组都会重新执行它的 lambda，批次逐项回报的频繁重组里随时可能把 stat 打到 UI 线程上。所以列表
 * 读取挪进 [produceState] + [Dispatchers.IO]：组合只消费"已载入"的结果。展示内容不变，只是加载
 * 瞬间先渲染空列表。（[aliases] 与 [rootLabel] 是资源查表，不碰磁盘，组合期取用没问题。）
 */
@Composable
private fun DirectoryPicker(
    browser: DirBrowser,
    root: String,
    rootLabel: String,
    aliases: Map<String, String>,
    selectable: (String) -> Boolean,
    startPath: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val s = LocalIosStyle.current
    val upLabel = stringResource(R.string.picker_up)
    var path by remember(root, startPath) { mutableStateOf(if (selectable(startPath)) startPath else root) }
    // 上一级只能自己算：DirItem.parent 的定义是"这批条目出自哪个目录"，当不了从 path 往上走的串。
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
    // `shown` 是委托属性，智能转换不了，先取进局部变量再判空。
    val done = shown
    val view = done ?: Listing(path, emptyList(), "")
    // 还没读出来（首帧，或点了但新层未到）：这时不画"没有子目录"，那是在骗人。
    val settled = done != null && done.path == path

    // 别名与"上一级"的判定都按**画出来的那一层**算，所以返回手势、返回行、列表说的是同一件事。
    fun rowsFor(level: Listing): List<NavRow> {
        val atRoot = File(level.path).absoluteFile == File(root).absoluteFile
        val items = if (atRoot) mergedRootEntries(aliases, level.subdirs, level.path) else level.subdirs
        return buildList {
            // 父级出不了 root 才有"上一级"：退到 /storage/emulated 就读不出子目录、root 只剩一个
            // "0" 条目，是一条回不来的死路（见函数 KDoc）。root 这一级因此就是最顶层。
            level.parent.takeIf { it.isNotEmpty() && selectable(it) }
                ?.let { add(NavRow(upLabel, it, isBack = true)) }
            items.forEach { add(NavRow(it.name, it.absolutePath, isBack = false)) }
        }
    }
    val upPath = view.parent.takeIf { it.isNotEmpty() && selectable(it) }
    val atRoot = File(view.path).absoluteFile == File(root).absoluteFile
    // 标题是最后一级目录名，副标题是整条路径的**显示形式**：root 那一级读作 [rootLabel]，
    // 往下就是「内部存储/DCIM/Camera」。这里刻意不把 "/storage/emulated/0" 摊给用户看——
    // 那串既没人读得懂，root 又确实是可以选作源/目标的，所以用别名而不是藏掉。
    val leaf = if (atRoot) rootLabel else view.path.trimEnd('/').substringAfterLast('/').ifEmpty { view.path }
    val subtitle = displayPath(view.path, root, rootLabel)
    val canPick = selectable(view.path)

    IosSheet(
        title = leaf,
        subtitle = subtitle,
        cancelText = stringResource(R.string.picker_close),
        confirmText = stringResource(R.string.picker_pick),
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
                stringResource(R.string.picker_out_of_root, root),
                style = s.footnote.copy(color = s.red),
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
                        stringResource(R.string.picker_empty),
                        style = s.footnote,
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
private fun dotColor(item: ItemResult, s: IosStyle): Color = when (item.status) {
    ItemStatus.SUCCESS -> if (item.deleteFailures.isEmpty()) s.green else s.orange
    ItemStatus.SKIPPED -> s.tertiaryLabel
    ItemStatus.FAILED -> s.red
}

/**
 * 一行报告文案（摘要行永远只有一行；整张列表的展开/收起在 [ReportCard] 那一层）。
 * `when (item.status)` 的三个分支是穷尽的，核心层加状态时这里会编译不过。
 *
 * 两个助手都是 `@Composable`，因为文案要从资源取——它们在 `shown.rows.forEachIndexed` 里逐项调用，
 * 但 `stringResource` 只是一次查表（`Context` 已在组合里），没有磁盘或 binder 调用。
 */
@Composable
private fun itemLine(item: ItemResult): String {
    val reason = reasonLabel(item.reason)
    val head = when (item.status) {
        ItemStatus.SUCCESS -> stringResource(R.string.row_success, item.stem, item.outputName)
        ItemStatus.SKIPPED -> stringResource(R.string.row_skipped, item.stem, reason)
        ItemStatus.FAILED -> stringResource(R.string.row_failed, item.stem, reason)
    }
    // deleteFailures 只会挂在 SUCCESS 上：Task 8 规定改名成功后状态不再被回改写，
    // 删不掉的源文件是警告。所以警告必须跟着 SUCCESS 一起显示，不能只在失败分支里拼。
    val warning = if (item.deleteFailures.isEmpty()) ""
    else stringResource(R.string.row_delete_warning, item.deleteFailures.joinToString())
    return head + warning
}

/**
 * 原因的本地化短语，后面跟着机器可读的枚举名（spec §9 要求面向用户给短语，同时留着能贴进
 * issue 的那个名字）。`reason == null` 时只有"未说明"，不拼括号里的代码。
 */
@Composable
private fun reasonLabel(reason: FailureReason?): String {
    val text = stringResource(reasonResource(reason))
    return if (reason == null) text else stringResource(R.string.reason_code, text, reason.name)
}

/**
 * [FailureReason] → 资源 id。分支是穷尽的：核心层新增枚举值时这里编译不过，
 * 而不是悄悄显示出"未说明"。
 */
@StringRes
private fun reasonResource(reason: FailureReason?): Int = when (reason) {
    null -> R.string.reason_unspecified
    FailureReason.NOT_JPEG -> R.string.reason_not_jpeg
    FailureReason.MALFORMED_JPEG -> R.string.reason_malformed_jpeg
    FailureReason.ALREADY_MOTION_PHOTO -> R.string.reason_already_motion_photo
    FailureReason.EXTENDED_XMP_UNSUPPORTED -> R.string.reason_extended_xmp
    FailureReason.NOT_MP4 -> R.string.reason_not_mp4
    FailureReason.IO_ERROR -> R.string.reason_io_error
    FailureReason.NO_PAIR -> R.string.reason_no_pair
}
