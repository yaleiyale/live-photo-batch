package dev.zayn.vlive.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MotionDetectorTest {
    @Test fun `namespace qualified tags are caught`() {
        assertTrue(MotionDetector.looksLikeMotionPhoto("""<x GCamera:MicroVideoOffset="123"/>"""))
        assertTrue(MotionDetector.looksLikeMotionPhoto("""<GCamera:MotionPhoto>1</>"""))
        assertTrue(MotionDetector.looksLikeMotionPhoto("""Item:Semantic>MotionPhoto"""))
    }

    @Test fun `unrelated xmp is not mistaken`() {
        assertFalse(MotionDetector.looksLikeMotionPhoto("<x:xmpmeta>MicroVideoOffsetish</x:xmpmeta>"))
        assertFalse(MotionDetector.looksLikeMotionPhoto(null))
        // 简报原来只有上一条：去掉 "GCamera:" 前缀的裸子串实现照样能过（"MicroVideoOffsetish"
        // 后面跟的是 i，不在 ["\s=>] 里）。这条才是命名空间要求本身——别的命名空间或无前缀的
        // 同名元素不是 Google Motion Photo 标记，误判会让整批照片被 SKIPPED。
        assertFalse(MotionDetector.looksLikeMotionPhoto("<MicroVideoOffset>123</MicroVideoOffset>"))
        assertFalse(MotionDetector.looksLikeMotionPhoto("<tiff:MotionPhoto>0</tiff:MotionPhoto>"))
        assertFalse(MotionDetector.looksLikeMotionPhoto("<dc:description>MotionPhoto</dc:description>"))
    }
}
