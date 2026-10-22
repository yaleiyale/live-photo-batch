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
 * 一条 modifier 把一块**带模糊的玻璃面**画完 —— "参与模糊的层怎么叠"这一份画法只在这一个地方定义。
 * 高光渐变与 hairline 描边不在这条"独享"之列：[IosButton] 刻意把它们内联重画了一遍，
 * 因为按钮不许走模糊那条路（spec §1.1"一层玻璃只模糊一次"）。
 *
 * 顺序就是这条的全部内容，不能随意调整：
 * 1. `clip` 落在 `hazeBlur` 之前——背景糊完再按 shape 裁，才有那条干净的玻璃边缘；
 * 2. 高光、描边是在糊好的背景**之上**叠出来的内容，不参与模糊本身。
 *
 * 裁剪、描边共用同一个 `shape` 变量：两处各算一次会让高光/描边漏在圆角之外。
 *
 * 两套预设的 `GlassMaterial.radius` 都是 `0.dp`（→ [RectangleShape]），这是刻意的：主屏顶栏和弹窗顶栏
 * 是贴到边的 full-bleed 条，本来就不带圆角。[radius] 这条圆角路径是给**将来**要落在玻璃上的圆角浮动件
 * 留的——走默认值只会得到一条方角 bar，编译器、测试和 lint 都不会吱声。底部操作栏在 2026-10-22 的
 * 设备实测之后改用了 [iosGlassTray]（同一套层序、少一层模糊），它才是 `glassRadius` 现在的消费方。
 *
 * **这条链上没有 `shadow`，也不许再加回来**（2026-10-22，emulator-5554 / API 36 / 420dpi / 1080x2400
 * 上七台构建 A..G 逐台装机量出来；V2419A 当天没连着，机上没有复量过）：`Modifier.shadow` 不往本层外面
 * 画阴影，而是在层内**预留**一圈阴影扩散区，链子上排在它后面的 `hazeBlur` / 高光 / 描边一起被缩进那一圈
 * 以内，连子节点也一起裁进那一圈以内。预留宽度跟着 elevation 走：`8.dp` + [RectangleShape] 时顶栏画面
 * 从屏幕边缘内缩 26px（`24.dp` 时 229px，`clip = false` 不改变结果，去掉 `shadow` 后铺满 0..1079）。
 * 贴边的方角条上，这一圈把一条 full-bleed 玻璃切成了"玻璃背后压着一块白色方形色块"。顶栏的下沿分隔感
 * 本来就由 hairline 描边承担，且它贴着左、右、上三条屏幕边，那三个方向的投影落在屏外，去掉的不是一处
 * 可见效果。[iosGlassTray] 因为同一条机制去掉了 `shadow`，那边记了它自己的实测数字。
 * 九个读数与判据见 decisions.md §5.21。
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
 * [iosGlassBar] 的**少一层模糊**版本 —— spec §3.4 预先约定的那条回退，只在设备实测超预算时启用，
 * 不是一种备选风格。2026-10-22 00:00–00:07 在 V2419A 上量到（每台三次采样取中位数，数字见
 * decisions.md §5.19）：三条 bar 都开模糊时帧时间 50 分位从基线的 6ms 抬到 8–9ms、超过 8ms 的帧
 * 从 12.5% 涨到 46.7%，`Janky frames (legacy)` 中位数 10.13% → 13.62%，同时越过"基线 +3 个百分点"
 * 和"≤10%"两条线；`Janky frames` 那一行三台构建九个样本全是 0.00%（这台机器的
 * presentationDeadline 有 12.3ms，SurfaceFlinger 侧根本没记到 miss），所以判据只能落在 legacy
 * 那一行与直方图上。只把底部这一条换成 [iosGlassTray] 之后 >8ms 回到 30.2% —— 收回了这段右移的
 * 一部分，没有全部收回，legacy 仍读 13.20%，这条要如实写在这里。
 *
 * 层序与 [iosGlassBar] 逐条对齐，只把 `hazeBlur` 换成一条 `.background(glass.tint)`，高光与 hairline
 * 描边照旧。代价是 tint 从此压在本层自己的底色上、而不是压在被糊过的内容上：内容从 bar 底下经过时
 * **不再被抹平**，报告行的字会直接透出来和 bar 上的字打架（浅色深色两种外观都拍到了，见 §5.19）。
 * 顶栏与弹窗顶栏保留模糊，所以这两条还是"玻璃"，底部这条读起来比它们更薄、更透。
 *
 * **`shadow` 也去掉了**（2026-10-22，同一条 [iosGlassBar] 里记的那组实测）。这一条比顶栏更值得去掉：
 * `Modifier.shadow` 预留的那一圈不只吃掉本层自己的画面，**它把子节点一起裁了** —— 底部这条的子节点
 * 是两颗按钮，于是 6.dp 的阴影在按钮下沿切出一道矩形补丁（实测：Cancel 那颗的填充在 y=2242 从 180
 * 跳到 193、在 x=960 从 193 掉到 178；去掉 `shadow` 后这一路是 208→196 的平滑渐变，两处台阶都没有了）。
 * 浮动感和下沿分隔改由 hairline 描边 + 与页面底色的色差承担。
 */
fun Modifier.iosGlassTray(glass: GlassMaterial, radius: Dp): Modifier {
    val shape: Shape = if (radius > 0.dp) RoundedCornerShape(radius) else RectangleShape
    return this
        .clip(shape)
        .background(glass.tint)
        .background(Brush.verticalGradient(0f to glass.highlight, 0.45f to Color.Transparent))
        .border(width = IosMetrics.hairline, color = glass.stroke, shape = shape)
}
