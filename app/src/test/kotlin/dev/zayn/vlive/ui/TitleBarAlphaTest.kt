package dev.zayn.vlive.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 玻璃标题栏的 alpha 是这一轮唯一值得单测的新逻辑：它决定"划过去才看得见"这件事，
 * 错了只会表现为"标题栏一直挡着"或"永远不出现"，肉眼看一次未必复现得出来。
 *
 * 单位是 px，不是 dp —— 换算发生在调用点（那里有 LocalDensity），函数保持纯。
 */
class TitleBarAlphaTest {

    private fun assertAlpha(expected: Float, scrollY: Int, range: Int) =
        assertEquals(expected, titleBarAlpha(scrollY, range), 1e-6f)

    @Test
    fun `hidden at the very top`() = assertAlpha(0f, scrollY = 0, range = 56)

    @Test
    fun `fully visible once past the collapse range`() = assertAlpha(1f, scrollY = 56, range = 56)

    @Test
    fun `linear in between`() = assertAlpha(0.5f, scrollY = 28, range = 56)

    @Test
    fun `clamped past the range`() = assertAlpha(1f, scrollY = 4000, range = 56)

    @Test
    fun `overscroll does not go negative`() = assertAlpha(0f, scrollY = -120, range = 56)

    @Test
    fun `a non-positive range never hides the bar forever`() {
        assertAlpha(1f, scrollY = 0, range = 0)
        assertAlpha(1f, scrollY = 12, range = -5)
    }
}
