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
 * 一条 modifier 把一块玻璃画完，画法只在这一个地方定义。
 *
 * 顺序就是这条的全部内容，不能随意调整：
 * 1. `shadow` 必须落在 `clip` 之前——先有形状（shape）才打得出贴着轮廓的阴影，裁完再打就糊成方块；
 * 2. `hazeBlur` 落在 `clip` 之内——背景糊完再按 shape 裁，才有那条干净的玻璃边缘；
 * 3. 高光、描边是在糊好的背景**之上**叠出来的内容，不参与模糊本身。
 *
 * 阴影、裁剪、描边共用同一个 `shape` 变量：三处各算一次会让高光/描边漏在圆角之外。
 * `radius = 0.dp`（当前两套预设）时取 [RectangleShape]，此时 barAlpha/阴影仍在，只是不带圆角。
 */
fun Modifier.iosGlassBar(
    glass: GlassMaterial,
    state: HazeState,
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
