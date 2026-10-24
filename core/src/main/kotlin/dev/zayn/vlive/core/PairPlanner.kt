package dev.zayn.vlive.core

data class DirEntry(val name: String)

data class PairJob(
    val stem: String,
    val imageName: String,
    val videoName: String,
    val outputName: String,
)

data class Unpaired(val name: String)

data class PlanResult(val jobs: List<PairJob>, val unpaired: List<Unpaired>)

/**
 * 一层目录的同名配对，**驱动集是视频**：只有 `.mp4` 会被拿去配同名 JPEG。上一轮产物
 * （stem 以 `_MP` 结尾的图像）不作为图像候选。
 *
 * 以视频驱动而不是以 JPEG 驱动：相机目录里绝大多数 JPEG 是普通照片，根本没有配对视频。
 * 以 JPEG 驱动时它们每个都要产一条"缺视频"的跳过记录，既刷长报告也说明不了任何问题——
 * 没视频就不是动态照片，这是常识不是异常。以视频驱动之后这些文件连候选都不是，报告里只剩下
 * 真值得看一眼的情况：有 `IMG_*.mp4` 却找不到同名图像。
 *
 * 输入约定：
 * - `source` 是**单层**目录名列表，由 [BatchStorage.list] 给出。本对象不递归，
 *   也不解释路径分隔符——名字里带 `/` 只会被当成 stem 的一部分。
 * - 图像候选：`.jpg` / `.jpeg`。视频候选：扩展名 `.mp4` **且以 [VIDEO_PREFIX] 开头**（大小写
 *   不敏感）。前缀这一刀是必要的：相册目录里还躺着录屏、下载的短视频和聊天工具存下的视频，
 *   它们不是动态照片素材。其它扩展名（`.heic` / `.txt` / …）一律静默忽略，既不配对也不进 unpaired。
 * - "无可用扩展名"同样静默忽略，判定来自 `extension()`：只看**最后一个点**，它必须既不在 index 0
 *   也不是末字符。于是 `README`（无点）、`a.`（点在末尾）与 `.nomedia` / `.hidden`（唯一的点在
 *   index 0 的裸点开头名）都不是候选。**反面要注意**：点开头**且带扩展名**的名字走同一套规则——
 *   `.hidden.jpg` 会进图像候选。本对象不特殊处理点开头名字，媒体扫描器那套 `.nomedia` 语义与此无关。
 * - stem 以 `_MP` 结尾（大小写不敏感）的**图像**不作为输入候选。视频侧不需要这条保护：本工具从不
 *   产出 `_MP.mp4`，真有人手写出一个，它会作为"缺图"出现在 unpaired 里——看得见比被静默吞掉好。
 * - 同一 stem 上出现两张候选（`a.jpg` 与 `a.jpeg`）时取**列表中第一个**，另一个静默丢弃，
 *   不报错也不进 unpaired。
 * - 源目录与目标目录可以是同一个：产物名带 `_MP` 后缀且不作为图像候选，原地生成安全。
 *   上一轮的产物再遇到同名视频时，由尾巴分类判成「已是动态照片」并跳过。
 *
 * 排序：`jobs` 与 `unpaired` 都按 stem 的**自然顺序**（`String.compareTo`，逐 16 位码元）升序，
 * 不走 locale collator，所以结果与设备语言无关。两点后果：
 * - ASCII 大写在符号之前、小写之前：`'B'(0x42) < '_'(0x5F) < 'a'(0x61)`。UI 的稳定顺序依赖这条，
 *   不要改成大小写无关排序。
 * - 数字按字典序而非数值序：`IMG_10` 排在 `IMG_2` 之前。相册顺序因此不等同于拍摄顺序。
 */
object PairPlanner {
    /**
     * 产物名 = `<stem>_MP.jpg`。Motion Photo 1.0 建议作者的文件名匹配
     * `^([^\s/\][^/\]*MP)\.(JPG|jpg|JPEG|jpeg|HEIC|heic|AVIF|avif)`，即扩展名前以 `MP` 收尾，
     * 与 Pixel 自己的 `IMG_xxxxMP.jpg` 同形。
     */
    const val OUTPUT_SUFFIX = "_MP"

    /**
     * 驱动集的视频文件名前缀。相机目录里躺着录屏、下载的短视频与聊天工具存下来的 mp4，
     * 它们不是动态照片；相机产出的动态照片一律是 `IMG_<时间戳>.mp4`。
     */
    const val VIDEO_PREFIX = "IMG_"

    fun plan(source: List<DirEntry>): PlanResult {
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

        val jobs = ArrayList<PairJob>()
        val unpaired = ArrayList<Unpaired>()
        for (stem in videos.keys.sorted()) {
            val video = videos.getValue(stem)
            val image = images[stem]
            if (image == null) {
                unpaired += Unpaired(video.name)
                continue
            }
            jobs += PairJob(
                stem = stem,
                imageName = image.name,
                videoName = video.name,
                outputName = stem + OUTPUT_SUFFIX + ".jpg",
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
