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

enum class Missing { VIDEO, IMAGE }

data class Unpaired(val name: String, val missing: Missing)

data class PlanResult(val jobs: List<PairJob>, val unpaired: List<Unpaired>)

/**
 * 一层目录的同名配对。stem 严格区分大小写（与 Android 文件系统语义一致），
 * 扩展名判定忽略大小写。上一轮产物（stem 以 _MP 结尾）不作为输入。
 *
 * 输入约定（spec §7.4.5-7.4.6、§9）：
 * - `source`/`target` 都是**单层**目录名列表，由调用方（Task 7 的 BatchStorage.list）给出，
 *   本对象不递归，也不解释路径分隔符——名字里带 `/` 只会被当成 stem 的一部分。
 * - 只有 `.jpg`/`.jpeg` 进图像候选、`.mp4` 进视频候选；其它扩展名（.heic/.txt/…）一律静默忽略，
 *   既不配对也不进 unpaired。
 * - "无可用扩展名"同样静默忽略，判定来自 `extension()`：只看**最后一个点**，它必须既不在 index 0
 *   也不是末字符。于是 `README`（无点）、`a.`（点在末尾）与 `.nomedia`/`.hidden`（唯一的点在 index 0
 *   的裸点开头名）都不是候选。**反面要注意**：点开头**且带扩展名**的名字是候选，因为判定用的是最后一个
 *   点而不是第一个——`.nomedia.mp4` 的 stem 是 `.nomedia`，照常进视频候选；`.hidden.jpg` 进图像候选
 *   （再走下面的 `_MP` 规则）。本对象不特殊处理点开头名字，媒体扫描器那套 `.nomedia` 语义与此无关。
 * - stem 以 `_MP` 结尾（大小写不敏感）的**图像**不作为输入候选（spec §7.4.6 只点名 JPEG）；
 *   同名的 `.mp4` 仍进视频候选，因此上一轮的 `a_MP.mp4` 会作为"缺图"出现在 unpaired 里——
 *   计划 Task 6 Step 4 与测试 `previous outputs are not treated as inputs` 都要求这个结果。
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

    fun plan(source: List<DirEntry>, target: List<DirEntry>): PlanResult {
        val images = LinkedHashMap<String, DirEntry>()
        val videos = LinkedHashMap<String, DirEntry>()
        for (entry in source) {
            val stem = stemOf(entry.name, extension(entry.name)) ?: continue
            when (extension(entry.name)?.lowercase()) {
                // 后缀大小写不敏感：产物自己叫 `x_MP.jpg`，别人改叫 `x_mp.jpg` 也不算新输入。
                "jpg", "jpeg" ->
                    if (!stem.lowercase().endsWith(OUTPUT_SUFFIX.lowercase())) images.putIfAbsent(stem, entry)
                "mp4" -> videos.putIfAbsent(stem, entry)
            }
        }
        val existingOutputs = target.map { it.name.lowercase() }.toHashSet()
        val stems = (images.keys + videos.keys).sorted()

        val jobs = ArrayList<PairJob>()
        val unpaired = ArrayList<Unpaired>()
        for (stem in stems) {
            val image = images[stem]
            val video = videos[stem]
            when {
                image != null && video != null -> {
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
                image != null -> unpaired += Unpaired(image.name, Missing.VIDEO)
                video != null -> unpaired += Unpaired(video.name, Missing.IMAGE)
            }
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
