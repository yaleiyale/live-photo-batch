package dev.zayn.livephotobatch.core

/**
 * spec §7.4 第 3 条的"源 XMP 已经带 Motion Photo 语义"判定。
 *
 * 匹配必须带命名空间前缀：`MicroVideoOffset` 这种裸词在任意 XMP 里都可能出现，
 * 用它做子串会把普通照片误判成已合成过而整批跳过。
 */
object MotionDetector {
    private val MOTION_TAG =
        Regex("""GCamera:(MicroVideoOffset|MotionPhoto)["\s=>]""")
    private val MOTION_SEMANTIC = Regex("""Item:Semantic>?\s*MotionPhoto""")

    fun looksLikeMotionPhoto(xmpText: String?): Boolean {
        if (xmpText == null) return false
        return MOTION_TAG.containsMatchIn(xmpText) || MOTION_SEMANTIC.containsMatchIn(xmpText)
    }
}
