package dev.zayn.vlive.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 驱动集是视频：只有 `IMG_*.mp4` 会被拿去配同名 JPEG。
 * 因此"普通照片缺视频"不是报告内容，报告里只剩"有视频找不到图"这一种异常。
 */
class PairPlannerTest {
    private fun plan(vararg names: String) = PairPlanner.plan(names.map { DirEntry(it) })

    @Test fun `same stem pairs and gets the MP suffix`() {
        val result = plan("IMG_001.jpg", "IMG_001.mp4")
        assertEquals(1, result.jobs.size)
        val job = result.jobs[0]
        assertEquals("IMG_001", job.stem)
        assertEquals("IMG_001_MP.jpg", job.outputName)
        assertEquals("IMG_001.mp4", job.videoName)
    }

    @Test fun `extension case is ignored, stem case is not`() {
        assertEquals(1, plan("IMG_a.JPG", "IMG_a.Mp4").jobs.size)
        assertTrue(plan("IMG_a.jpg", "IMG_A.mp4").jobs.isEmpty())
    }

    @Test fun `the video prefix is matched case insensitively`() {
        assertEquals(1, plan("img_002.jpg", "img_002.mp4").jobs.size)
        // 前缀忽略大小写，stem 匹配仍然严格区分大小写（Android 文件系统语义）：
        // IMG_003.jpg 与 img_003.mp4 是两个不同的 stem，配不成对，视频报缺图。
        val mixed = plan("IMG_003.jpg", "img_003.mp4")
        assertTrue(mixed.jobs.isEmpty())
        assertEquals(listOf("img_003.mp4"), mixed.unpaired.map { it.name })
    }

    // 录屏、下载的短视频、聊天工具存进来的 mp4 都不是动态照片。
    @Test fun `a video without the IMG prefix is not a driver`() {
        val result = plan("screenshot.jpg", "screenshot.mp4", "video.mp4")
        assertTrue(result.jobs.isEmpty(), "非 IMG_ 开头的视频不该成对：${result.jobs}")
        assertTrue(result.unpaired.isEmpty(), "它也不该出现在报告里：${result.unpaired}")
    }

    // 普通照片没有配对视频时不该刷"缺视频"——那是纯噪声。
    @Test fun `a plain photo without a video is not reported at all`() {
        val result = plan("IMG_only.jpg", "IMG_second.jpg", "IMG_third.jpeg")
        assertTrue(result.jobs.isEmpty())
        assertTrue(
            result.unpaired.isEmpty(),
            "没有视频的 JPEG 连候选都不是，不该产生任何跳过记录：${result.unpaired}",
        )
    }

    @Test fun `a video whose image is missing is reported`() {
        val result = plan("IMG_vid.mp4")
        assertEquals(1, result.unpaired.size)
        assertEquals("IMG_vid.mp4", result.unpaired[0].name)
    }

    @Test fun `previous outputs are not treated as inputs`() {
        val result = plan("IMG_a.jpg", "IMG_a.mp4", "IMG_a_MP.jpg", "IMG_a_MP.mp4")
        assertEquals(listOf("IMG_a"), result.jobs.map { it.stem })
        // 图像侧被 _MP 规则挡掉，所以那对"上一轮产物"只剩视频，报成缺图。
        assertEquals(listOf("IMG_a_MP.mp4"), result.unpaired.map { it.name })
    }

    @Test fun `the output guard ignores case`() {
        // 产物叫 IMG_a_MP.jpg，被谁改成 IMG_a_mp.jpeg 也还是上一轮的东西，不许当新输入。
        val result = plan("IMG_a.jpg", "IMG_a.mp4", "IMG_a_mp.jpeg", "IMG_a_mp.mp4")
        assertEquals(listOf("IMG_a"), result.jobs.map { it.stem })
        assertEquals(listOf("IMG_a_mp.mp4"), result.unpaired.map { it.name })
    }

    @Test fun `other extensions are ignored entirely`() {
        val result = plan("IMG_a.jpg", "IMG_a.mp4", "IMG_note.txt", "IMG_b.heic", "IMG_b.mp4")
        assertEquals(1, result.jobs.size)
        assertTrue(result.unpaired.none { it.name == "IMG_note.txt" || it.name == "IMG_b.heic" })
        // IMG_b.mp4 有视频没图像（.heic 不是候选），所以它报缺图。
        assertEquals(listOf("IMG_b.mp4"), result.unpaired.map { it.name })
    }

    @Test fun `jobs are ordered by stem for stable UI`() {
        val result = plan("IMG_b.jpg", "IMG_b.mp4", "IMG_a.jpg", "IMG_a.mp4")
        assertEquals(listOf("IMG_a", "IMG_b"), result.jobs.map { it.stem })
    }

    @Test fun `job names are the listing names, not the stem or each other`() {
        val result = PairPlanner.plan(
            listOf(
                DirEntry("IMG_001.JPG"),
                DirEntry("IMG_001.Mp4"),
                DirEntry("IMG_holiday.jpeg"),
                DirEntry("IMG_holiday.mp4"),
            ),
        )
        assertEquals(2, result.jobs.size)
        assertEquals(listOf("IMG_001", "IMG_holiday"), result.jobs.map { it.stem })
        val upper = result.jobs[0]
        assertEquals("IMG_001.JPG", upper.imageName)
        assertEquals("IMG_001.Mp4", upper.videoName)
        assertEquals("IMG_001_MP.jpg", upper.outputName)
        val lower = result.jobs[1]
        assertEquals("IMG_holiday.jpeg", lower.imageName)
        assertEquals("IMG_holiday.mp4", lower.videoName)
        assertEquals("IMG_holiday_MP.jpg", lower.outputName)
    }
}
