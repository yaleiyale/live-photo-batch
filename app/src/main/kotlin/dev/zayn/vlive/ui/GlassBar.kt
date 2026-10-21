package dev.zayn.vlive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur

/**
 * 一条 modifier 把一块**带模糊的玻璃面**画完 —— "参与模糊的层怎么叠"这一份画法只在这一个地方定义。
 * 高光渐变与 hairline 描边不在这条"独享"之列：[IosButton] 刻意把它们内联重画了一遍，
 * 因为按钮不许走模糊那条路（spec §1.1"一层玻璃只模糊一次"）。
 *
 * 顺序就是这条的全部内容，不能随意调整：
 * 1. `shadow` 必须落在 `clip` 之前——`clip` 设的是 `clipToOutline`，而阴影整个画在轮廓之外，
 *    反过来写的后果不是"糊成方块"，是阴影**被裁掉、彻底看不见**（[IosButton] 用的是同一条规则）；
 * 2. `hazeBlur` 落在 `clip` 之内——背景糊完再按 shape 裁，才有那条干净的玻璃边缘；
 * 3. 高光、描边是在糊好的背景**之上**叠出来的内容，不参与模糊本身。
 *
 * 阴影、裁剪、描边共用同一个 `shape` 变量：三处各算一次会让高光/描边漏在圆角之外。
 *
 * 两套预设的 `GlassMaterial.radius` 都是 `0.dp`（→ [RectangleShape]），这是刻意的：主屏顶栏和弹窗顶栏
 * 是贴到边的 full-bleed 条，本来就不带圆角。**唯一需要圆角的消费方是底部操作栏**，它必须显式传
 * [IosMetrics.glassRadius] —— 走默认值只会得到一条方角 bar，编译器、测试和 lint 都不会吱声。
 */
fun Modifier.iosGlassBar(
    glass: GlassMaterial,
    state: HazeState,
    // 默认 0.dp 是给两条贴边条的；底部操作栏这类要圆角的必须显式传 IosMetrics.glassRadius（见上）。
    radius: Dp = glass.radius,
    barAlpha: Float = 1f,
): Modifier {
    val shape: Shape = if (radius > 0.dp) RoundedCornerShape(radius) else RectangleShape
    return this
        .alpha(barAlpha)
        .shadow(elevation = glass.shadowElevation, shape = shape)
        .clip(shape)
        .hazeBlur(
            input = HazeInput.Sources(state),
            style = HazeBlurStyle {
                blurRadius(glass.blurRadius)
                colorEffects(listOf(HazeColorEffect.tint(glass.tint)))
            },
        )
        .background(Brush.verticalGradient(0f to glass.highlight, 0.45f to Color.Transparent))
        .border(width = IosMetrics.hairline, color = glass.stroke, shape = shape)
}
