package dev.zayn.vlive.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** iOS「设置」里的 inset group：白卡片 + 连续圆角，左右留出 [IosMetrics.screenMargin]。 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    color: Color = iosStyle().card,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .padding(horizontal = IosMetrics.screenMargin)
            .fillMaxWidth()
            .clip(RoundedCornerShape(IosMetrics.cardRadius))
            .background(color),
    ) { content() }
}

/** 卡片内的分隔线：只在左侧缩进处起笔，右边通到卡片边缘。 */
@Composable
fun IosDivider(indent: Boolean = true) {
    val s = LocalIosStyle.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = if (indent) IosMetrics.rowPadding else 0.dp)
            .height(IosMetrics.hairline)
            .background(s.separator),
    )
}

@Composable
private fun PressableRow(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val s = LocalIosStyle.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (pressed && enabled) s.highlight else Color.Transparent)
            .clickable(
                interactionSource = interaction,
                enabled = enabled,
                onClickLabel = null,
                role = Role.Button,
                onClick = onClick,
            )
            .heightIn(min = IosMetrics.rowMinHeight)
            .padding(horizontal = IosMetrics.rowPadding, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 标题 + 值的行。值另起一行且不设 maxLines：界面上必须看得见**完整绝对路径**
 * （这是 BatchScreen 里那条"显示的就是批次真正会用的串"的约定），单行加省略号会把它截没。
 */
@Composable
fun DetailRow(
    title: String,
    value: String,
    enabled: Boolean,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    val s = LocalIosStyle.current
    Column {
        PressableRow(enabled = enabled, onClick = onClick) {
            Column(Modifier.weight(1f)) {
                Text(title, style = s.body)
                Text(
                    value,
                    style = s.rowValue.copy(
                        color = if (enabled) s.secondaryLabel else s.tertiaryLabel,
                    ),
                )
            }
            ChevronRight()
        }
        if (divider) IosDivider(indent = false)
    }
}

/** 整行点标题也能切换——iOS 的开关行就是这么工作的，原实现同样如此。 */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean,
    onColor: Color,
    divider: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    val s = LocalIosStyle.current
    Column {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (pressed && enabled) s.highlight else Color.Transparent)
                .heightIn(min = IosMetrics.rowMinHeight)
                .padding(horizontal = IosMetrics.rowPadding, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                // 危险开关一勾上就把整行染成系统红，和原实现的 error 同一条规则。
                style = s.body.copy(color = if (checked) s.red else s.label),
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = interaction,
                        enabled = enabled,
                        onClickLabel = null,
                        role = Role.Switch,
                        onClick = { onCheckedChange(!checked) },
                    ),
            )
            Spacer8()
            IosSwitch(on = checked, enabled = enabled, onColor = onColor) { onCheckedChange(!checked) }
        }
        if (divider) IosDivider(indent = false)
    }
}

@Composable
private fun Spacer8() = Box(Modifier.width(8.dp))

@Composable
fun IosSwitch(on: Boolean, enabled: Boolean, onColor: Color, onToggle: () -> Unit) {
    val s = LocalIosStyle.current
    val knob by animateFloatAsState(if (on) 1f else 0f, tween(durationMillis = 220))
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .width(51.dp)
            .height(31.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(if (on) onColor else s.trackOff)
            .clickable(
                interactionSource = interaction,
                enabled = enabled,
                onClickLabel = null,
                role = Role.Switch,
                onClick = onToggle,
            ),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .padding(2.dp)
                .size(27.dp)
                .shadow(elevation = 2.dp, shape = CircleShape)
                .background(Color.White, CircleShape)
                // 行程 = 51 - 27 - 2*2
                .offset(x = 20.dp * knob),
        )
    }
}

enum class IosButtonStyle { Primary, Secondary, Destructive }

@Composable
fun IosButton(
    text: String,
    enabled: Boolean,
    style: IosButtonStyle = IosButtonStyle.Primary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val s = LocalIosStyle.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val label = when (style) {
        IosButtonStyle.Primary -> Color.White
        IosButtonStyle.Destructive -> s.red
        IosButtonStyle.Secondary -> s.blue
    }
    // 禁用态只淡文字、主按钮换成淡蓝底：整块 alpha 下去，白底次按钮会直接融进分组背景里。
    val surface = if (style == IosButtonStyle.Primary) {
        if (enabled) s.blue else s.blue.copy(alpha = 0.35f)
    } else {
        s.card
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(IosMetrics.buttonHeight)
            .alpha(if (pressed && enabled) 0.7f else 1f)
            .clip(RoundedCornerShape(percent = 50))
            .background(surface)
            .clickable(
                interactionSource = interaction,
                enabled = enabled,
                onClickLabel = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = s.button.copy(color = if (enabled) label else label.copy(alpha = 0.4f)))
    }
}

@Composable
fun ThinProgressBar(fraction: Float) {
    val s = LocalIosStyle.current
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(durationMillis = 180))
    Box(
        Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(s.trackOff),
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(percent = 50))
                .background(s.blue),
        )
    }
}

@Composable
fun StatusRow(dot: Color, text: String, divider: Boolean = true) {
    val s = LocalIosStyle.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = IosMetrics.rowPadding, end = IosMetrics.rowPadding, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Box(Modifier.width(10.dp))
            Text(text, style = s.report, modifier = Modifier.weight(1f))
        }
        if (divider) IosDivider()
    }
}

/**
 * 报告行：状态点 + 一行摘要，右侧一个展开用的 chevron。
 *
 * [detail] 为 null 表示这一行**没有可展开的内容**，于是整行不可点、也不画 chevron ——
 * "默认收起"不该骗人去点一个什么都没有的东西。展开时明细另起一行，用次级色，与摘要拉开层级。
 */
@Composable
fun ReportRow(
    dot: Color,
    head: String,
    detail: String?,
    expanded: Boolean,
    divider: Boolean = true,
    onToggle: () -> Unit,
) {
    val s = LocalIosStyle.current
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (detail == null) Modifier else Modifier.clickable(onClick = onToggle))
                .padding(start = IosMetrics.rowPadding, end = IosMetrics.rowPadding, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Box(Modifier.width(10.dp))
            Text(head, style = s.report, modifier = Modifier.weight(1f))
            if (detail != null) {
                Box(Modifier.width(10.dp))
                ExpandChevron(expanded)
            }
        }
        if (detail != null && expanded) {
            Text(
                detail,
                style = s.footnote.copy(color = s.secondaryLabel),
                modifier = Modifier
                    .padding(start = IosMetrics.rowPadding + 8.dp + 10.dp)
                    .padding(end = IosMetrics.rowPadding, bottom = 8.dp),
            )
        }
        if (divider) IosDivider()
    }
}

/** 筛选档位那排胶囊：选中的一枚是蓝底白字，其余是卡片底 + 主色字。 */
@Composable
fun FilterChipRow(
    labels: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val s = LocalIosStyle.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val on = index == selected
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (on) s.blue else s.card)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            ) {
                Text(
                    label,
                    style = s.footnote.copy(color = if (on) Color.White else s.label),
                )
            }
        }
    }
}

/** 分页那一行：左右两个文字按钮，中间是"第 x/y 页"。到边的那侧禁用（不是隐藏，位置不能跳）。 */
@Composable
fun ReportPager(
    label: String,
    prev: String,
    next: String,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalIosStyle.current
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PagerAction(prev, canPrev, onPrev)
        Text(
            label,
            style = s.footnote,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        PagerAction(next, canNext, onNext)
    }
}

@Composable
private fun PagerAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    val s = LocalIosStyle.current
    Text(
        text,
        style = s.navAction.copy(color = if (enabled) s.blue else s.tertiaryLabel),
        modifier = Modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** 展开指示：收起时朝右，展开时转到朝下，时长复用入场那套令牌。 */
@Composable
private fun ExpandChevron(expanded: Boolean) {
    val angle by animateFloatAsState(if (expanded) 90f else 0f, tween(IosMotion.ENTER_MS), label = "reportChevron")
    ChevronRight(modifier = Modifier.rotate(angle))
}

/** 目录行：左侧文件夹图形 + 名字，右侧 chevron。图形用 Canvas 画，不引入矢量资源。 */
@Composable
fun FolderRow(title: String, enabled: Boolean = true, divider: Boolean = true, onClick: () -> Unit) {
    val s = LocalIosStyle.current
    Column {
        PressableRow(enabled = enabled, onClick = onClick) {
            FolderIcon()
            Box(Modifier.width(12.dp))
            Text(
                title,
                style = s.body.copy(color = if (enabled) s.label else s.tertiaryLabel),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ChevronRight()
        }
        if (divider) IosDivider(indent = false)
    }
}

/** 回上级：iOS 导航返回样式的 chevron 放在最左。 */
@Composable
fun BackRow(title: String, divider: Boolean = true, onClick: () -> Unit) {
    val s = LocalIosStyle.current
    Column {
        PressableRow(enabled = true, onClick = onClick) {
            ChevronLeft(color = s.blue)
            Box(Modifier.width(10.dp))
            Text(title, style = s.body.copy(color = s.blue))
        }
        if (divider) IosDivider(indent = false)
    }
}

@Composable
private fun ChevronRight(modifier: Modifier = Modifier, color: Color = iosStyle().tertiaryLabel) {
    Canvas(modifier.width(8.dp).height(13.dp)) {
        val stroke = 1.7.dp.toPx()
        val mid = Offset(size.width, size.height / 2f)
        drawLine(color, Offset(0f, 0f), mid, strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, mid, Offset(0f, size.height), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun ChevronLeft(color: Color) {
    Canvas(Modifier.width(8.dp).height(13.dp)) {
        val stroke = 1.7.dp.toPx()
        val mid = Offset(0f, size.height / 2f)
        drawLine(color, Offset(size.width, 0f), mid, strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, mid, Offset(size.width, size.height), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
private fun FolderIcon() {
    val s = LocalIosStyle.current
    Canvas(Modifier.width(22.dp).height(18.dp)) {
        val tab = CornerRadius(size.width * 0.10f)
        drawRoundRect(
            color = s.blue.copy(alpha = 0.55f),
            topLeft = Offset(0f, 0f),
            size = Size(size.width * 0.45f, size.height * 0.4f),
            cornerRadius = tab,
        )
        drawRoundRect(
            color = s.blue,
            topLeft = Offset(0f, size.height * 0.25f),
            size = Size(size.width, size.height * 0.75f),
            cornerRadius = CornerRadius(size.width * 0.14f),
        )
    }
}

/**
 * 全屏模态卡片：顶栏左取消、中标题、右确认，下面是一条可滚动的分组列表。
 *
 * `decorFitsSystemWindows = false` 让窗口铺到状态栏底下，配合整屏底色就没有那条接缝；
 * 避让改由内容自己用 [WindowInsets.safeDrawing] 负责。
 */
@Composable
fun IosSheet(
    title: String,
    subtitle: String,
    cancelText: String,
    confirmText: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val s = LocalIosStyle.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(s.groupedBackground)
                .windowInsetsPadding(WindowInsets.safeDrawing),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 折叠屏内屏约 780dp 宽，内容封顶后居中，否则卡片和行会被拉成一条横杠。
            Column(
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(),
            ) {
                Box(Modifier.fillMaxWidth().height(52.dp)) {
                    BarAction(text = cancelText, align = Alignment.CenterStart, onClick = onDismiss)
                    BarAction(
                        text = confirmText,
                        align = Alignment.CenterEnd,
                        enabled = confirmEnabled,
                        emphasized = true,
                        onClick = onConfirm,
                    )
                    Column(
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth()
                            .padding(horizontal = 76.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            title,
                            style = s.title,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            subtitle,
                            style = s.footnote.copy(fontSize = 11.sp, color = s.tertiaryLabel),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IosDivider(indent = false)
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 14.dp, bottom = 28.dp),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.BarAction(
    text: String,
    align: Alignment,
    enabled: Boolean = true,
    emphasized: Boolean = false,
    onClick: () -> Unit,
) {
    val s = LocalIosStyle.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(Modifier.fillMaxHeight().align(align), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = s.navAction.copy(
                fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    !enabled -> s.tertiaryLabel
                    pressed -> s.blue.copy(alpha = 0.6f)
                    else -> s.blue
                },
            ),
            modifier = Modifier
                .clip(RoundedCornerShape(IosMetrics.cardRadius))
                .clickable(
                    interactionSource = interaction,
                    enabled = enabled,
                    onClickLabel = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
