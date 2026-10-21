package dev.zayn.vlive.ui

/**
 * 滚动 [scrollY]（px）时玻璃标题栏的不透明度，[collapseRange] 是同单位、同方向的 px。
 *
 * 两条边界不是防御性摆设：
 * - `range <= 0` 返回 `1f`：布局在某种窗口尺寸下算出 0 时，宁可让 bar 一直可见，也不能让标题永久消失；
 * - 负 `scrollY`（overscroll 回弹）夹到 `0f`，否则 alpha 变负、`graphicsLayer` 上会出现无意义的值。
 */
fun titleBarAlpha(scrollY: Int, collapseRange: Int): Float =
    if (collapseRange <= 0) 1f else (scrollY.toFloat() / collapseRange).coerceIn(0f, 1f)
