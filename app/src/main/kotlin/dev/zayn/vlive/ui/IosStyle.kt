package dev.zayn.vlive.ui

import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme

/**
 * 一屏用到的全部颜色。浅深两套都是 iOS 系统色（AA 组），值取自 iOS 深色规范里对应 token。
 *
 * 颜色只能从 [LocalIosStyle] 取，不许再有 `object IosColor { val card = Color(...) }` 那种
 * 编译期常量 —— 那种写法把浅色的值烧进了调用点，跟随系统的深色就做不出来。
 */
@Immutable
data class IosPalette(
    val groupedBackground: Color,
    val card: Color,
    /** 行按下时的底色 —— iOS 的行高亮是整行变灰，不是水波纹。 */
    val highlight: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val separator: Color,
    val trackOff: Color,
    val blue: Color,
    val green: Color,
    val red: Color,
    val orange: Color,
    val noticeBackground: Color,
)

val IosLightPalette = IosPalette(
    groupedBackground = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    highlight = Color(0xFFE4E4EA),
    label = Color(0xFF000000),
    secondaryLabel = Color(0xFF8A8A8E),
    tertiaryLabel = Color(0xFFC5C5CA),
    separator = Color(0xFFD8D8DC),
    trackOff = Color(0xFFE5E5EA),
    blue = Color(0xFF007AFF),
    green = Color(0xFF34C759),
    red = Color(0xFFFF3B30),
    orange = Color(0xFFFF9500),
    noticeBackground = Color(0xFFFFF1F0),
)

/** iOS 深色：卡片是 #1C1C1E、分组背景近黑，语义色换成深色下的对应值（红更亮以保证对比度）。 */
val IosDarkPalette = IosPalette(
    groupedBackground = Color(0xFF000000),
    card = Color(0xFF1C1C1E),
    highlight = Color(0xFF2C2C2E),
    label = Color(0xFFFFFFFF),
    secondaryLabel = Color(0xFF98989F),
    tertiaryLabel = Color(0xFF48484A),
    separator = Color(0xFF38383A),
    trackOff = Color(0xFF39393B),
    blue = Color(0xFF0A84FF),
    green = Color(0xFF30D158),
    red = Color(0xFFFF453A),
    orange = Color(0xFFFF9F0A),
    noticeBackground = Color(0xFF3A1D1E),
)

/**
 * accent 的四个值：浅/深 × 常规/高对比（spec §1.5）。
 *
 * 常规两列就是色板里原本的两个值，一个都没改；高对比那两列是本轮定的**起点**，不是苹果公开数值的
 * 引用，判据是硬指标：Tinted 主按钮上的白字、玻璃 bar 上的 accent 字在各自外观下 ≥ 4.5:1。
 */
fun accentFor(dark: Boolean, highContrast: Boolean): Color = when {
    !dark && !highContrast -> Color(0xFF007AFF)
    !dark -> Color(0xFF0040DD)
    !highContrast -> Color(0xFF0A84FF)
    else -> Color(0xFF66B3FF)
}

/**
 * 颜色 + 文字样式的合一入口。合成期一次取值（`val s = LocalIosStyle.current`），后面全部走 `s.`。
 *
 * 字号对齐 iOS 文本样式（pt≈sp）：大标题 32 / 行标题 17 / 值与脚注 13–14。颜色由色板决定，
 * 所以样式表是实例而不是 object 常量。
 */
@Immutable
class IosStyle(private val p: IosPalette) {
    val groupedBackground get() = p.groupedBackground
    val card get() = p.card
    val highlight get() = p.highlight
    val label get() = p.label
    val secondaryLabel get() = p.secondaryLabel
    val tertiaryLabel get() = p.tertiaryLabel
    val separator get() = p.separator
    val trackOff get() = p.trackOff
    val blue get() = p.blue
    val green get() = p.green
    val red get() = p.red
    val orange get() = p.orange
    val noticeBackground get() = p.noticeBackground

    val largeTitle = TextStyle(
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
        color = p.label,
    )
    val body = TextStyle(fontSize = 17.sp, color = p.label)
    val rowValue = TextStyle(fontSize = 14.sp, color = p.secondaryLabel)
    val footnote = TextStyle(fontSize = 13.sp, color = p.secondaryLabel)
    val title = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = p.label)
    val button = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    val navAction = TextStyle(fontSize = 17.sp, color = p.blue)
    val report = TextStyle(fontSize = 15.sp, color = p.label)
}

/** 默认给浅色：预览与没有 [IosTheme] 包裹的测试路径用得上，不会静默变深色。 */
val LocalIosStyle = staticCompositionLocalOf { IosStyle(IosLightPalette) }

/**
 * 几何与时长不属于颜色，仍然是常量。
 */
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
 * `ENTER_MS` 与 `IosSwitch` 里那个 220ms 同值：启动入场、开关拨动、进度条推进应该是同一种
 * 手感，而不是各调各的曲线。
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

private fun colorScheme(p: IosPalette) = lightColorScheme(
    primary = p.blue,
    onPrimary = Color.White,
    secondary = p.blue,
    background = p.groupedBackground,
    onBackground = p.label,
    surface = p.card,
    onSurface = p.label,
    surfaceVariant = p.groupedBackground,
    onSurfaceVariant = p.secondaryLabel,
    error = p.red,
    onError = Color.White,
    outline = p.separator,
)

private fun darkColorSchemeOf(p: IosPalette) = darkColorScheme(
    primary = p.blue,
    onPrimary = Color.White,
    secondary = p.blue,
    background = p.groupedBackground,
    onBackground = p.label,
    surface = p.card,
    onSurface = p.label,
    surfaceVariant = p.groupedBackground,
    onSurfaceVariant = p.secondaryLabel,
    error = p.red,
    onError = Color.White,
    outline = p.separator,
)

/**
 * 主题入口：跟随系统浅/深，把同一套色板同时交给自绘控件层（[LocalIosStyle]）与仍然走
 * MaterialTheme 的兜底色（`Text` 的默认色、`Dialog` 的表面色）。
 *
 * 深浅色**只有这一处判定**；色板换了，MaterialTheme 与自绘层必须一起换，否则会出现
 * "自绘卡片是深色、Dialog 还是白"这种两套主题同时可见的接缝。
 */
@Composable
fun IosTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // 系统"高文字对比度"开关，取法见 spec §1.5。第一次读可能触发一次 IPC（之后 AccessibilityManager
    // 自己缓存），所以整段包在 remember 里、不在每次重组时读 —— BatchScreen 那条"合成期不碰
    // binder/磁盘"的约定靠这个 remember 守住，不是靠假设这个 getter 便宜。
    // `LocalContext.current` 本身是 @ReadOnlyComposable 的取值，只能待在函数体里，所以先取 context
    // 再把它当 key：Activity 重建前它是同一个对象，key 不会多读一次，读到的仍是"每个主题实例一次"。
    // 已知限制：开关运行中被翻动不会立刻重组，下一次配置变化/主题重建才生效，内部工具接受这个代价。
    val context = LocalContext.current
    val highContrast = remember(context) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        // 档位判断不是双保险而是必需：isHighContrastTextEnabled 是 API 36 才进 AccessibilityManager 的，
        // minSdk 31–35 上这个方法根本不存在，直接调不是 lint 抱怨一下而已，是启动第一帧 NoSuchMethodError。
        // 读不到开关的机型退回常规 accent，这就是高对比那两列只在 36+ 生效的原因。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) am.isHighContrastTextEnabled else false
    }
    val palette = (if (dark) IosDarkPalette else IosLightPalette).copy(blue = accentFor(dark, highContrast))
    val scheme = if (dark) darkColorSchemeOf(palette) else colorScheme(palette)
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalIosStyle provides IosStyle(palette)) {
            content()
        }
    }
}

/** 给不需要整套主题的场景（如测试里的局部组合）取色板。 */
@Composable
fun iosStyle(): IosStyle = LocalIosStyle.current
