package dev.zayn.livephotobatch.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairPlannerTest {
    private fun plan(vararg names: String) =
        PairPlanner.plan(names.map { DirEntry(it, 1L) }, emptyList())

    @Test fun `same stem pairs and gets the live suffix`() {
        val result = plan("IMG_001.jpg", "IMG_001.mp4")
        assertEquals(1, result.jobs.size)
        val job = result.jobs[0]
        assertEquals("IMG_001", job.stem)
        assertEquals("IMG_001_live.jpg", job.outputName)
        assertEquals("IMG_001.mp4", job.videoName)
        assertEquals(false, job.outputExists)
    }

    @Test fun `sizes come from the listing`() {
        val result = PairPlanner.plan(
            listOf(DirEntry("a.jpg", 11L), DirEntry("a.mp4", 22L)), emptyList()
        )
        assertEquals(11L, result.jobs[0].imageSize)
        assertEquals(22L, result.jobs[0].videoSize)
    }

    @Test fun `extension case is ignored, stem case is not`() {
        assertEquals(1, plan("a.JPG", "a.Mp4").jobs.size)
        assertTrue(plan("a.jpg", "A.mp4").jobs.isEmpty())
    }

    @Test fun `previous outputs are not treated as inputs`() {
        val result = plan("a.jpg", "a.mp4", "a_live.jpg", "a_live.mp4")
        assertEquals(listOf("a"), result.jobs.map { it.stem })
        assertEquals(listOf("a_live.mp4"), result.unpaired.map { it.name })
    }

    @Test fun `output existing in target is flagged for overwrite`() {
        val result = PairPlanner.plan(
            listOf(DirEntry("a.jpg", 1L), DirEntry("a.mp4", 1L)),
            listOf(DirEntry("a_live.jpg", 9L)),
        )
        assertTrue(result.jobs[0].outputExists)
    }

    // 计划夹具写的是 plan("only.jpg", "only.mp4", "vid.mp4")：前两项同 stem，必然成对，
    // 于是它自己的两条断言（jobs 为空 + unpaired 含 only 与 vid）不可能同时成立。
    // 保留断言原样，只删掉多余的 "only.mp4"，让"图像缺视频 / 视频缺图像"各来一条。
    @Test fun `unpaired entries are reported both ways`() {
        val result = plan("only.jpg", "vid.mp4")
        assertEquals(setOf("only", "vid"), (result.unpaired.map { it.name.substringBefore('.') }).toSet())
        assertTrue(result.jobs.isEmpty())
        assertEquals(
            mapOf("only.jpg" to Missing.VIDEO, "vid.mp4" to Missing.IMAGE),
            result.unpaired.associate { it.name to it.missing },
        )
    }

    @Test fun `other extensions are ignored entirely`() {
        val result = plan("a.jpg", "a.mp4", "note.txt", "b.heic", "b.mp4")
        assertEquals(1, result.jobs.size)
        assertTrue(result.unpaired.none { it.name == "note.txt" || it.name == "b.heic" })
    }

    @Test fun `jobs are ordered by stem for stable UI`() {
        val result = plan("b.jpg", "b.mp4", "a.jpg", "a.mp4")
        assertEquals(listOf("a", "b"), result.jobs.map { it.stem })
    }

    // Fix round 1（Finding 2）：上面 8 条没有任何一条读 PairJob.imageName，
    // 把 imageName 写成 videoName、stem 或 stem + ".jpg" 都能让全套保持绿色。
    // 下游（Task 8 runner）要拿 imageName 去拼 sourceDir 下的真实路径，所以这里钉死：
    // imageName/videoName 必须是列名原样，大小写与扩展名形式都不得改写。
    @Test fun `job names are the listing names, not the stem or each other`() {
        val result = PairPlanner.plan(
            listOf(
                DirEntry("IMG_001.JPG", 3L),
                DirEntry("IMG_001.Mp4", 4L),
                DirEntry("holiday.jpeg", 5L),
                DirEntry("holiday.mp4", 6L),
            ),
            emptyList(),
        )
        assertEquals(2, result.jobs.size)
        assertEquals(listOf("IMG_001", "holiday"), result.jobs.map { it.stem })
        val upper = result.jobs[0]
        assertEquals("IMG_001.JPG", upper.imageName)
        assertEquals("IMG_001.Mp4", upper.videoName)
        assertEquals("IMG_001_live.jpg", upper.outputName)
        val lower = result.jobs[1]
        assertEquals("holiday.jpeg", lower.imageName)
        assertEquals("holiday.mp4", lower.videoName)
        assertEquals("holiday_live.jpg", lower.outputName)
    }
}
