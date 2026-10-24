package dev.zayn.vlive

import dev.zayn.vlive.core.FailureReason
import dev.zayn.vlive.core.ItemResult
import dev.zayn.vlive.core.ItemStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 扫描列表不变量的纯计算（无文件系统、无 Android 类）——输入就是 `BatchReport.items` 可能
 * 长出的四种真实形状：SUCCESS 带"只删掉一半"的原文件、FAILED/SKIPPED 项、取消后跑完的尾巴、
 * "产物必须在"的底线。`"$dir/$name"` 的拼接约定钉在这里，与核心层
 * `BatchRunner.composeOne` 逐字符一致，协程里不许再有第二份内联拼接。
 *
 * [touchedPathsFor] 是纯函数：这里给的"绝对路径"是字符串演算，与宿主文件系统无关。
 */
class TouchedPathsForTest {

    private val dir = "/storage/emulated/0/Pictures/LivePhoto"
    private val src = "/storage/emulated/0/DCIM/Camera"

    private fun success(
        outputName: String,
        deleted: List<String>,
        deleteFailures: List<String> = emptyList(),
    ): ItemResult = ItemResult("IMG_1", outputName, ItemStatus.SUCCESS, null, 128L, deleteFailures, deleted)

    @Test
    fun `SUCCESS with a partially deleted pair reports the output and only the deleted originals`() {
        // rename 成功、图删掉了、视频删不掉：产物 + 已删的 .mp4 进扫描，留着的 .jpg 绝不进
        // （它还活着，扫它只会把相册索引按现状重写一遍，不变量要求的是"已删的必须消失"）。
        val item = success("IMG_1_MP.jpg", deleted = listOf("$src/IMG_1.mp4"), deleteFailures = listOf("IMG_1.jpg"))
        assertEquals(listOf("$dir/IMG_1_MP.jpg", "$src/IMG_1.mp4"), touchedPathsFor(dir, listOf(item)))
    }

    @Test
    fun `FAILED and SKIPPED items contribute nothing`() {
        val failed = ItemResult("IMG_2", "IMG_2_MP.jpg", ItemStatus.FAILED, FailureReason.IO_ERROR, 0)
        val skipped = ItemResult("IMG_3", "", ItemStatus.SKIPPED, FailureReason.NO_PAIR, 0)
        // FAILED 项的 outputName 只是"计划中会叫什么"，rename 前一切已清理，产物不存在。
        assertEquals(emptyList<String>(), touchedPathsFor(dir, listOf(failed, skipped)))
    }

    @Test
    fun `the post-cancel tail is included`() {
        // cancel() 翻标志后 BatchRunner 仍在两项之间跑完当前项：IMG_2_MP.jpg 是取消之后才
        // 提交的尾巴，它同样必须到达扫描器——顺序也保持回报顺序，扫描列表不许乱。
        val items = listOf(
            success("IMG_1_MP.jpg", listOf("$src/IMG_1.jpg", "$src/IMG_1.mp4")),
            ItemResult("IMG_9", "", ItemStatus.SKIPPED, FailureReason.NO_PAIR, 0),
            success("IMG_2_MP.jpg", deleted = emptyList()),
        )
        assertEquals(
            listOf("$dir/IMG_1_MP.jpg", "$src/IMG_1.jpg", "$src/IMG_1.mp4", "$dir/IMG_2_MP.jpg"),
            touchedPathsFor(dir, items),
        )
    }

    @Test
    fun `outputs are always included whatever else happened`() {
        // deleteOriginals 关着（没删任何东西）和开着但全删失败（只有警告）两种 SUCCESS，
        // 产物路径都必须出现在扫描列表里——这是"新文件要能在相册立刻看到"的下半句。
        val plain = success("IMG_4_MP.jpg", deleted = emptyList())
        val warned = success("IMG_5_MP.jpg", deleted = emptyList(), deleteFailures = listOf("IMG_5.jpg"))
        assertEquals(
            listOf("$dir/IMG_4_MP.jpg", "$dir/IMG_5_MP.jpg"),
            touchedPathsFor(dir, listOf(plain, warned)),
        )
    }
}
