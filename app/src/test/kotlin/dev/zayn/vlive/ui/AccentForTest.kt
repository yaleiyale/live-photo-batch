package dev.zayn.vlive.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * accent 必须一次给四个值（浅/深 × 常规/高对比），这是 spec §1.5 的硬要求。
 * 这里只测"取值函数"，不测 Compose 的渲染 —— 后者在 JVM 单测里取不到。
 */
class AccentForTest {

    @Test
    fun `four canonical values`() {
        assertEquals(Color(0xFF007AFF), accentFor(dark = false, highContrast = false))
        assertEquals(Color(0xFF0040DD), accentFor(dark = false, highContrast = true))
        assertEquals(Color(0xFF0A84FF), accentFor(dark = true, highContrast = false))
        assertEquals(Color(0xFF66B3FF), accentFor(dark = true, highContrast = true))
    }

    @Test
    fun `high contrast differs from regular in both appearances`() {
        assertNotEquals(accentFor(false, false), accentFor(false, true))
        assertNotEquals(accentFor(true, false), accentFor(true, true))
    }

    @Test
    fun `regular values match the palettes the rest of the UI already uses`() {
        // 抬到四个值不能顺手改掉现状：常规态必须与 IosLightPalette/IosDarkPalette 的 blue 同值。
        assertEquals(IosLightPalette.blue, accentFor(dark = false, highContrast = false))
        assertEquals(IosDarkPalette.blue, accentFor(dark = true, highContrast = false))
    }
}
