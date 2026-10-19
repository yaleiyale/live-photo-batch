package dev.zayn.livephotobatch.core

data class DirEntry(val name: String, val size: Long)

data class PairJob(
    val stem: String,
    val imageName: String,
    val videoName: String,
    val outputName: String,
    val imageSize: Long,
    val videoSize: Long,
    val outputExists: Boolean,
)

/** 只有"缺图"这一种异常可报告：见 [PairPlanner] 的驱动集规则——没有视频的 JPEG 压根不进候选。 */
enum class Missing { IMAGE }

data class Unpaired(val name: String, val missing: Missing)

data class PlanResult(val jobs: List<PairJob>, val unpaired: List<Unpaired>)

/**
 * 一层目录的同名配对，**驱动集是视频**：只有 `.mp4` 会被拿去配同名 JPEG。上一轮产物
 * （stem 以 _MP 结尾的图像）不作为图像候选。
 *
 * 为什么反过来以视频驱动：相机目录里绝大多数 JPEG 是普通照片，根本没有配对视频。以 JPEG 驱动时
 * 它们每个都要产一条"缺视频"的跳过记录（真机第一批 54 项里 28 项是这种噪声），既刷长报告也说明不了
 * 任何问题——没视频就不是动态照片，这是常识不是异常。以视频驱动之后这些文件连候选都不是，
 * 报告里只剩下真值得看一眼的情况：有 `IMG_*.mp4` 却找不到同名图像。
 *
 * 输入约定（spec §7.4.5-7.4.6、§9）：
 * - `source`/`target` 都是**单层**目录名列表，由调用方（Task 7 的 BatchStorage.list）给出，
 *   本对象不递归，也不解释路径分隔符——名字里带 `/` 只会被当成 stem 的一部分。
 * - 图像候选：`.jpg`/`.jpeg`；视频候选：**必须以 [VIDEO_PREFIX] 开头**（大小写不敏感）且扩展名是
 *   `.mp4`。前缀这一刀是必要的：相册目录里还有录屏、下载的短视频、微信保存的视频，它们不是动态照片。
 *   其它扩展名（.heic/.txt/…）一律静默忽略，既不配对也不进 unpaired。
 * - "无可用扩展名"同样静默忽略，判定来自 `extension()`：只看**最后一个点**，它必须既不在 index 0
 *   也不是末字符。于是 `README`（无点）、`a.`（点在末尾）与 `.nomedia`/`.hidden`（唯一的点在 index 0
 *   的裸点开头名）都不是候选。**反面要注意**：点开头**且带扩展名**的名字是候选，因为判定用的是最后一个
 *   点而不是第一个——`.nomedia.mp4` 的 stem 是 `.nomedia`，但它不以 `IMG_` 开头，因此不是候选；
 *   `.hidden.jpg` 进图像候选（再走下面的 `_MP` 规则）。本对象不特殊处理点开头名字，媒体扫描器那套
 *   `.nomedia` 语义与此无关。
 * - stem 以 `_MP` 结尾（大小写不敏感）的**图像**不作为输入候选（spec §7.4.6 只点名 JPEG）；
 *   视频侧不需要这条保护——我们从不产出 `_MP.mp4`，而真有人手写出一个的话，它会作为"缺图"出现在
 *   unpaired 里，看得见比被静默吞掉好。
 * - 同一 stem 上出现两张候选（`a.jpg` 与 `a.jpeg`）时取**列表中第一个**，另一个静默丢弃，
 *   不报错也不进 unpaired（spec 未规定，此处定的规则）。
 * - `source` 与 `target` 可以是同一个目录：产物名带 `_MP` 后缀且不作为图像候选，
 *   原地生成安全；上一轮已写出 `a_MP.jpg` 时 `outputExists` 为 true，即 spec §9/G3 的覆盖。
 *
 * 排序：`jobs` 与 `unpaired` 都按 stem 的**自然顺序**（`String.compareTo`，逐 16 位码元）升序，
 * 不走 locale collator，所以结果与设备语言无关。两点后果要记住：
 * - ASCII 大写在符号之前、小写之前：`'B'(0x42) < '_'(0x5F) < 'a'(0x61)`，所以 stem `"B"` 的
 *   unpaired 排在 stem `"a"` 之前。这是刻意的，UI 的稳定顺序依赖它，不要改成大小写无关排序。
 * - 数字按字典序而非数值序：`IMG_10` 排在 `IMG_2` 之前。相册顺序因此不等同于拍摄顺序。
 */
object PairPlanner {
    /**
     * 产物名 = `<stem>_MP.jpg`。`_MP` 不是随手起的：Motion Photo 1.0 规范建议作者的文件名匹配
     * `^([^\s/\][^/\]*MP)\.(JPG|jpg|JPEG|jpeg|HEIC|heic|AVIF|avif)`，即扩展名前以 `MP` 收尾，
     * 与 Pixel 自己的 `IMG_xxxxMP.jpg` 同形。
     */
    const val OUTPUT_SUFFIX = "_MP"

    /**
     * 驱动集的视频文件名前缀。相机目录里躺着录屏、下载的短视频与聊天工具存下来的 mp4，
     * 它们不是动态照片；vivo/Google/小米的相机动态照片一律是 `IMG_<时间戳>.mp4`。
     */
    const val VIDEO_PREFIX = "IMG_"

    fun plan(source: List<DirEntry>, target: List<DirEntry>): PlanResult {
        val images = LinkedHashMap<String, DirEntry>()
        val videos = LinkedHashMap<String, DirEntry>()
        for (entry in source) {
            val ext = extension(entry.name)
            val stem = stemOf(entry.name, ext) ?: continue
            when (ext?.lowercase()) {
                // 后缀大小写不敏感：产物自己叫 `x_MP.jpg`，别人改叫 `x_mp.jpg` 也不算新输入。
                "jpg", "jpeg" ->
                    if (!stem.lowercase().endsWith(OUTPUT_SUFFIX.lowercase())) images.putIfAbsent(stem, entry)
                // 只有视频是驱动集：没有配对视频的普通照片连"跳过"都不该报，见类头 KDoc。
                "mp4" ->
                    if (entry.name.startsWith(VIDEO_PREFIX, ignoreCase = true)) videos.putIfAbsent(stem, entry)
            }
        }
        val existingOutputs = target.map { it.name.lowercase() }.toHashSet()

        val jobs = ArrayList<PairJob>()
        val unpaired = ArrayList<Unpaired>()
        for (stem in videos.keys.sorted()) {
            val video = videos.getValue(stem)
            val image = images[stem]
            if (image == null) {
                unpaired += Unpaired(video.name, Missing.IMAGE)
                continue
            }
            val outputName = stem + OUTPUT_SUFFIX + ".jpg"
            jobs += PairJob(
                stem = stem,
                imageName = image.name,
                videoName = video.name,
                outputName = outputName,
                imageSize = image.size,
                videoSize = video.size,
                outputExists = outputName.lowercase() in existingOutputs,
            )
        }
        return PlanResult(jobs, unpaired)
    }

    private fun extension(name: String): String? {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0 || dot == name.length - 1) null else name.substring(dot + 1)
    }

    private fun stemOf(name: String, ext: String?): String? {
        if (ext == null) return null
        return name.substring(0, name.length - ext.length - 1).ifEmpty { null }
    }
}
