package dev.zayn.vlive.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS 浅色（AA 组）系统色。
 *
 * 按设计决定只做浅色：深色不接，`res` 里也没有 night 资源，切深色时这套色板仍是浅色，
 * 属于预期行为而不是缺陷。
 */
object IosColor {
    val groupedBackground = Color(0xFFF2F2F7)
    val card = Color(0xFFFFFFFF)

    /** 行按下时的底色——iOS 的行高亮是整行变灰，不是水波纹。 */
    val highlight = Color(0xFFE4E4EA)
    val label = Color(0xFF000000)
    val secondaryLabel = Color(0xFF8A8A8E)
    val tertiaryLabel = Color(0xFFC5C5CA)
    val separator = Color(0xFFD8D8DC)
    val trackOff = Color(0xFFE5E5EA)
    val blue = Color(0xFF007AFF)
    val green = Color(0xFF34C759)
    val red = Color(0xFFFF3B30)
    val orange = Color(0xFFFF9500)
    val noticeBackground = Color(0xFFFFF1F0)
}

/** 字号对齐 iOS 文本样式（pt≈sp）：大标题 26 / 行标题 17 / 值与脚注 13–14。 */
object IosType {
    /**
     * iOS 的 large title 是 34pt，但那是英文 + 系统字重的尺寸；中文 32pt 粗体在 6.7 吋屏上
     * 一行「Live Photo 批量合成」就吃掉近 1/8 屏高，真机看着明显过大。降到 26sp：仍是全页
     * 最大的字，行标题 17sp 与它的层级差还在。
     */
    val largeTitle = TextStyle(
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp,
        color = IosColor.label,
    )
    val body = TextStyle(fontSize = 17.sp, color = IosColor.label)
    val rowValue = TextStyle(fontSize = 14.sp, color = IosColor.secondaryLabel)
    val footnote = TextStyle(fontSize = 13.sp, color = IosColor.secondaryLabel)
    val title = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IosColor.label)
    val button = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val navAction = TextStyle(fontSize = 17.sp, color = IosColor.blue)
    val report = TextStyle(fontSize = 15.sp, color = IosColor.label)
}

object IosMetrics {
    val screenMargin: Dp = 16.dp
    val cardRadius: Dp = 12.dp
    val rowMinHeight: Dp = 44.dp
    val rowPadding: Dp = 16.dp
    val sectionGap: Dp = 22.dp

    /** 小于 1dp：分隔线靠抗锯齿画成若隐若现的细线，比整数 1dp 更接近 iOS 的 0.5pt hairline。 */
    val hairline: Dp = 0.6.dp
    val buttonHeight: Dp = 50.dp
}

/**
 * 动效令牌。时长与缓动集中一处，调用点不许再写散装毫秒数。
 *
 * `ENTER_MS` 故意等于 `IosSwitch` 里那个 220ms：启动入场、开关拨动、进度条推进应该是同一种
 * 手感，而不是各调各的曲线。（那两处字面量还散在控件层里，见 decisions.md 的技术债一节。）
 */
object IosMotion {
    /** splash logo 淡出与卡片入场用同一个时长，两段动画才接得上。 */
    const val ENTER_MS = 220
    const val EXIT_MS = 220

    /** 相邻两段内容的起跑差。 */
    const val STAGGER_MS = 40

    /** 入场位移：12dp 上浮，够看见但不拖沓。 */
    const val RISE_DP = 12f

    /**
     * 兜底放行时长：splash 退出回调万一不回叫（OEM 分支不少），超过这个时间也必须把内容放出来。
     * 看不见动画可以接受，看不见界面不行。
     */
    const val RELEASE_TIMEOUT_MS = 600L

    /** iOS 观感对应的减速曲线（不是 Material 的 emphasized，是标准 ease-out）。 */
    val EaseOut = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
}

/** 兜住仍走 MaterialTheme 默认色的东西（Text 的兜底 color、Dialog 表面色等）。 */
val IosColorScheme = lightColorScheme(
    primary = IosColor.blue,
    onPrimary = Color.White,
    secondary = IosColor.blue,
    background = IosColor.groupedBackground,
    onBackground = IosColor.label,
    surface = IosColor.card,
    onSurface = IosColor.label,
    surfaceVariant = IosColor.groupedBackground,
    onSurfaceVariant = IosColor.secondaryLabel,
    error = IosColor.red,
    onError = Color.White,
    outline = IosColor.separator,
)
