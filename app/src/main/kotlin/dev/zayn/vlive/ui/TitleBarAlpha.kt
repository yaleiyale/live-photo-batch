package dev.zayn.vlive.ui

/**
 * 滚动 [scrollY]（px）时玻璃标题栏的不透明度；[collapseRange] 用同一个单位（px）量同一段距离，
 * 换算发生在调用点（那里有 `LocalDensity`），函数本身只收 Int，保持可测。
 *
 * **返回契约：恒在 `0f..1f`，线性、两端饱和。** 这不是一句风格说明 —— `BatchScreen` 的
 * `GlassTitleBar` 把"要不要画这条 bar"那道门写在 `alpha > 0f` 上，靠的就是它不会越界、也不会
 * 在 `scrollY > collapseRange` 之后继续发布新值：顶到 `1f` 之后再多滚都不重组，回到 `0f` 时整条
 * bar 退出组合。契约一破（返回值能超 1f 或在区间外继续动），那道门要么一直开着、要么每帧翻一次。
 *
 * 两条边界不是防御性摆设：
 * - `range <= 0` 返回 `1f`：布局在某种窗口尺寸下算出 0 时，宁可让 bar 一直可见，也不能让标题永久消失；
 * - 负 `scrollY`（overscroll 回弹）夹到 `0f`，否则 alpha 变负、`graphicsLayer` 上会出现无意义的值。
 */
fun titleBarAlpha(scrollY: Int, collapseRange: Int): Float =
    if (collapseRange <= 0) 1f else (scrollY.toFloat() / collapseRange).coerceIn(0f, 1f)
