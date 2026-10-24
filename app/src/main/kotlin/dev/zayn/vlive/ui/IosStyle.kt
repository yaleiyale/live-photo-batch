package dev.zayn.vlive.ui

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
 * 一屏用到的全部颜色，浅深两套都是 iOS 系统色（AA 组）。只能从 [LocalIosStyle] 取，不许再有
 * `object IosColor { val card = Color(...) }` 那种编译期常量 —— 它把浅色的值烧进调用点，深色就做不出来。
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
 * accent 的四个值：浅/深 × 常规/高对比。判据不是"四个值全都 ≥ 4.5:1"：常规档白字在 Tinted 底上（accent
 * @ 0.85 合成在该外观的玻璃上）读 3.3:1／4.7:1，与苹果不透明 `#007AFF` 的 4.0:1 同形，是已接受的偏离 ——
 * 别为了让哪条线过 4.5 去调常规蓝。高对比那一列必须过硬指标：浅色 `#0040DD` 合成后 5.7:1 已过，深色
 * `#66B3FF` 只有 2.9:1，未达标，已知并接受。
 */
fun accentFor(dark: Boolean, highContrast: Boolean): Color = when {
    !dark && !highContrast -> Color(0xFF007AFF)
    !dark -> Color(0xFF0040DD)
    !highContrast -> Color(0xFF0A84FF)
    else -> Color(0xFF66B3FF)
}

/**
 * 一块玻璃的全部参数。**只服务三个浮动 bar 区**（主屏顶栏、主屏底部操作栏、弹窗顶栏）：卡片不许用它，条内
 * 按钮也不许用它再糊一层。按外观自动选、没有强度开关（这一屏没有承载它的设置面）；alpha 只许改这张表。
 */
@Immutable
data class GlassMaterial(
    val tint: Color,
    val blurRadius: Dp,
    val highlight: Color,
    val stroke: Color,
    val radius: Dp,
)

val LightGlass = GlassMaterial(
    tint = Color.White.copy(alpha = 0.55f),
    blurRadius = 24.dp,
    highlight = Color.White.copy(alpha = 0.70f),
    stroke = Color.Black.copy(alpha = 0.10f),
    radius = 0.dp,
)

/**
 * 深色走"着色"档，着色层比浅色轻：卡片已经不是玻璃，只剩 bar 上的文字要保证读得清。两套预设的
 * `blurRadius` 同为 `24.dp` 不是复制粘贴漏改 —— 深浅只差着色，半径跟着外观变就等于给深色单独做一条模糊开销曲线。
 */
val DarkGlass = GlassMaterial(
    tint = Color(0xFF1C1C1E).copy(alpha = 0.62f),
    blurRadius = 24.dp,
    highlight = Color.White.copy(alpha = 0.18f),
    stroke = Color.White.copy(alpha = 0.14f),
    radius = 0.dp,
)

val LocalIosGlass = staticCompositionLocalOf { LightGlass }

@Composable
fun iosGlass(): GlassMaterial = LocalIosGlass.current

/**
 * 颜色 + 文字样式的合一入口：合成期一次取值（`val s = LocalIosStyle.current`），后面全部走 `s.`。
 * 字号对齐 iOS 文本样式（pt≈sp）：大标题 32 / 行标题 17 / 值与脚注 13–14；颜色由色板决定，所以样式表是
 * 实例而不是 object 常量。
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

object IosMetrics {
    val screenMargin: Dp = 16.dp
    val cardRadius: Dp = 12.dp
    val rowMinHeight: Dp = 44.dp
    val rowPadding: Dp = 16.dp
    val sectionGap: Dp = 22.dp

    /**
     * 内容封顶宽度：折叠屏内屏约 780dp，不封顶的话卡片会被拉成一条横杠。三处必须用同一个数（主屏内容层、
     * 主屏底部操作栏、弹窗那层 Box），各写各的之后"玻璃 bar 与卡片同宽"就不再是被声明出来的。**主屏顶栏
     * 不是消费方**：它要贴着左、右、上三条屏幕边，宽屏上多出来那段糊的是页面底色，卡片本来进不到那儿。
     */
    val contentMaxWidth: Dp = 640.dp

    /** 小于 1dp：分隔线靠抗锯齿画成若隐若现的细线，比整数 1dp 更接近 iOS 的 0.5pt hairline。 */
    val hairline: Dp = 0.6.dp
    val buttonHeight: Dp = 50.dp

    /** 底部操作栏的连续圆角：小于半高（74/2），读作苹果的连续圆角而不是胶囊。 */
    val glassRadius: Dp = 30.dp
    val topBarHeight: Dp = 44.dp
    /** 50.dp 按钮 + 上下各 12.dp：主屏内容层末尾那段留白用它。 */
    val barContentHeight: Dp = 74.dp
    val barBottomGap: Dp = 8.dp
    /** 大标题划过多远算"折叠完成"：topBarAlpha 的分母。 */
    val collapseRange: Dp = 56.dp
    /** 弹窗顶栏比主屏高一点：要装下标题与整条路径的副标题。 */
    val sheetBarHeight: Dp = 52.dp
}

/**
 * 动效令牌。时长与缓动集中一处，调用点不许再写散装毫秒数。`ENTER_MS` 与 `IosSwitch` 里那个 220ms 同值：
 * 启动入场、开关拨动、进度条推进要是同一种手感，而不是各调各的曲线。
 */
object IosMotion {
    /** splash logo 淡出与卡片入场用同一个时长，两段动画才接得上。 */
    const val ENTER_MS = 220
    const val EXIT_MS = 220

    /** 相邻两段内容的起跑差。 */
    const val STAGGER_MS = 40

    /** 入场位移：12dp 上浮，够看见但不拖沓。 */
    const val RISE_DP = 12f

    /** 兜底放行时长：splash 退出回调不回叫时也必须把内容放出来 —— 看不见动画可以，看不见界面不行。 */
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
 * 主题入口：跟随系统浅/深，把同一套色板同时交给自绘控件层（[LocalIosStyle]）与仍然走 MaterialTheme 的
 * 兜底色（`Text` 的默认色、`Dialog` 的表面色）。深浅色**只有这一处判定**；色板换了，MaterialTheme 与自绘
 * 层必须一起换，否则会出现"自绘卡片是深色、Dialog 还是白"这种两套主题同时可见的接缝。
 */
@Composable
fun IosTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // 系统"高文字对比度"开关。第一次读可能触发一次 IPC（之后 AccessibilityManager 自己缓存），所以整段包在
    // remember 里、不在每次重组时读 —— 组合期不碰 binder/磁盘。`LocalContext.current` 是 @ReadOnlyComposable
    // 的取值，只能待在函数体里，所以先取 context 再当 key：Activity 重建前它是同一个对象，读到的仍是
    // "每个主题实例一次"。已知限制：开关运行中被翻动不会立刻重组，下一次配置变化/主题重建才生效。
    val context = LocalContext.current
    val highContrast = remember(context) {
        // isHighContrastTextEnabled 是 API 36 才进 AccessibilityManager 的，minSdk 31–35 上直接调不是 lint 抱怨
        // 一下而已，是启动第一帧 NoSuchMethodError。档位判断因此必需，它同时决定要不要付那次 binder 调用：
        // 31–35 连取服务都不发生。读不到开关（含取到 null）的机型退回常规 accent。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val am = context.getSystemService(AccessibilityManager::class.java)
            am?.isHighContrastTextEnabled == true
        } else {
            false
        }
    }
    val palette = (if (dark) IosDarkPalette else IosLightPalette).copy(blue = accentFor(dark, highContrast))
    val scheme = if (dark) darkColorSchemeOf(palette) else colorScheme(palette)
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalIosStyle provides IosStyle(palette),
            LocalIosGlass provides if (dark) DarkGlass else LightGlass,
        ) {
            content()
        }
    }
}

/** 给不需要整套主题的场景（如测试里的局部组合）取色板。 */
@Composable
fun iosStyle(): IosStyle = LocalIosStyle.current
