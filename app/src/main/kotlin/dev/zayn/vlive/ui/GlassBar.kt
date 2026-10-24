package dev.zayn.vlive.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
 * 一条 modifier 把一块**带模糊的玻璃面**画完 —— "参与模糊的层怎么叠"只在这一个地方定义；高光渐变与
 * hairline 描边不在其中，[IosButton] 内联重画它们，因为按钮不许再糊一层。
 *
 * 顺序不能调整：`clip` 必须落在 `hazeBlur` **之前**（糊完再按 shape 裁，才有干净的玻璃边缘），高光与描边
 * 叠在糊好的背景**之上**、不参与模糊本身；裁剪与描边共用同一个 `shape` 变量，各算一次会让两者漏在圆角外。
 *
 * 两条顶栏贴边 full-bleed，`GlassMaterial.radius` 因此都是 `0.dp`；要圆角的浮动件必须显式传 [radius]
 * （[IosMetrics.glassRadius]），否则只会得到一条方角 bar，编译器、测试和 lint 都不会吱声。
 *
 * **这条链上没有 `shadow`，也不许加回来**：`Modifier.shadow` 在层内**预留**一圈扩散区（宽度跟着 elevation
 * 走），排在它后面的 `hazeBlur`／高光／描边连子节点一起被缩进、裁进那一圈以内 —— 贴边的方角条会被切成
 * "玻璃背后压着一块白色色块"。下沿的分隔感本来就由 hairline 描边承担。
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

/**
 * 底部这条用平涂 tint 而不是模糊：三条玻璃栏同时开 `hazeBlur` 会掉帧。层序与 [iosGlassBar] 逐条对齐，
 * 只把 `hazeBlur` 换成一条 `.background(glass.tint)`，高光与 hairline 描边照旧。
 *
 * 代价是 tint 压在本层自己的底色上、而不是压在被糊过的内容上：内容从 bar 底下经过时**不再被抹平**，
 * 报告行的字会直接透出来和 bar 上的字打架；底部这条因此读起来比保留模糊的两条顶栏更薄、更透。
 * 同样不带 `shadow`，而且比顶栏更不能带：预留的那一圈把子节点（两颗按钮）一起裁了，会在按钮下沿切出
 * 一道矩形补丁。浮动感与下沿分隔由 hairline 描边 + 与页面底色的色差承担。
 */
fun Modifier.iosGlassTray(glass: GlassMaterial, radius: Dp): Modifier {
    val shape: Shape = if (radius > 0.dp) RoundedCornerShape(radius) else RectangleShape
    return this
        .clip(shape)
        .background(glass.tint)
        .background(Brush.verticalGradient(0f to glass.highlight, 0.45f to Color.Transparent))
        .border(width = IosMetrics.hairline, color = glass.stroke, shape = shape)
}
