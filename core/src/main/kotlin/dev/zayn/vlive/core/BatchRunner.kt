package dev.zayn.vlive.core

data class BatchOptions(
    val sourceDir: String,
    val targetDir: String,
    val deleteOriginals: Boolean,
)

enum class ItemStatus { SUCCESS, SKIPPED, FAILED }

data class ItemResult(
    val stem: String,
    val outputName: String,
    val status: ItemStatus,
    val reason: FailureReason?,
    val bytes: Long,
    val deleteFailures: List<String> = emptyList(),
    val deletedSources: List<String> = emptyList(),
)

data class BatchReport(
    val items: List<ItemResult>,
    val composedBytes: Long,
    val cancelled: Boolean,
)

fun interface CancelToken { fun cancelled(): Boolean }

object NoCancel : CancelToken { override fun cancelled() = false }

/**
 * 一批的顺序执行器：先写 `.part`，force 后 rename，rename 成功才允许删除原文件。
 * 单项失败不中断批次；取消在当前项之后生效，并且**每一项之前**都查一次标志
 * （配不上对的 NO_PAIR 行也算一项，见 [run] 的两个循环）。
 *
 * 每一项的生命周期只有三条出路，三条都执行 `.part` 清理（清理自己万一抛出，只留一个垃圾文件，
 * 见下面讲 `catch` 的那一段）：
 * - 校验/合成被拒（[ComposeResult.Failure]）→ 无条件删 `.part` → 原因在 [SKIPPABLE] 里
 *   记 SKIPPED，否则记 FAILED；
 * - 合成成功但 rename 失败（[BatchStorage.move] 返回 false）→ 删 `.part` → FAILED(IO_ERROR)，
 *   最终名上什么都没有，原文件一律不动；
 * - rename 成功 → **本项当场提交**，先建出 SUCCESS，然后才（在 `deleteOriginals` 下）依次
 *   删图、删视频；删除无论是 false 还是抛出来都只进 [ItemResult.deleteFailures]，
 *   没有一条路径能回头把 SUCCESS 改写成 FAILED——一个源文件没删掉是警告。
 *
 * 上面三条的前提是那次运行还活着。进程被 force-stop 或被系统回收是第四种结局：三条出路一条都
 * 走不到，`.part` 留在原地，而那一次没人收尾。收尾由**下一次**运行的 [sweepStaleParts] 补做。
 *
 * [run] 的契约是必定返回一份 [BatchReport]：运行时故障折叠成 IO_ERROR，清理失败最多留一个
 * 垃圾文件，两者都不许穿出 [run]。
 *
 * [ItemResult.bytes] 是产物全部字节（[ComposeResult.Success.totalBytes]），跳过/失败项恒为 0，
 * [BatchReport.composedBytes] 只累加成功项。[ItemResult.deletedSources] 是**已成功删除的绝对路径**，
 * 调用方把它们连同产物一起交给媒体扫描收敛索引；一个批次里只有走完 rename 的项可能出现在里面。
 *
 * 路径全部拼成 `"$dir/$name"` 字符串交给 [BatchStorage]：本类不知道 java.io.File 的存在，
 * 也不重新推导计划（调用方先 `plan()` 预览，再把这个 PlanResult 原样交给 [run]）。
 */
class BatchRunner(private val storage: BatchStorage) {

    fun plan(options: BatchOptions): PlanResult = PairPlanner.plan(storage.list(options.sourceDir))

    fun run(
        plan: PlanResult,
        options: BatchOptions,
        cancel: CancelToken,
        onItem: (ItemResult) -> Unit,
    ): BatchReport {
        val items = ArrayList<ItemResult>()
        var composed = 0L
        var cancelled = false

        // 配不上对是计划结果而不是合成失败：走 NO_PAIR + SKIPPED，不碰任何文件，因此也不参与
        // composedBytes，更不该让 ensureDir 为它凭空建目录。取消标志同样要在每一行之前查——
        // 这些行也是用户看得见的项。
        for (missing in plan.unpaired) {
            if (cancel.cancelled()) { cancelled = true; break }
            val result = ItemResult(
                stem = missing.name.substringBeforeLast('.'),
                outputName = "",
                status = ItemStatus.SKIPPED,
                reason = FailureReason.NO_PAIR,
                bytes = 0,
                deleteFailures = emptyList(),
            )
            items += result
            onItem(result)
        }

        if (plan.jobs.isNotEmpty()) {
            storage.ensureDir(options.targetDir)
            storage.ensureDir("${options.targetDir}/.tmp")
            sweepStaleParts(options.targetDir)
        }

        for (job in plan.jobs) {
            // 取消只在两项之间生效：正在写的项一定走完 rename 或删除，绝不留下半截 .part。
            if (cancel.cancelled()) { cancelled = true; break }
            val result = composeOne(job, options)
            items += result
            if (result.status == ItemStatus.SUCCESS) composed += result.bytes
            onItem(result)
        }
        return BatchReport(items, composed, cancelled)
    }

    private fun composeOne(job: PairJob, options: BatchOptions): ItemResult {
        val imagePath = "${options.sourceDir}/${job.imageName}"
        val videoPath = "${options.sourceDir}/${job.videoName}"
        val finalPath = "${options.targetDir}/${job.outputName}"
        val tmpPath = "${options.targetDir}/.tmp/${job.outputName}$PART_SUFFIX"
        return try {
            val result = storage.openWrite(tmpPath).use { sink ->
                MotionPhotoComposer.compose(storage.open(imagePath), storage.open(videoPath), sink)
            }
            when (result) {
                is ComposeResult.Failure -> {
                    // 清理无条件，且不看字节数：Failure 上没有计数字段——校验阶段一个字节不写，
                    // 写入阶段可能已经留下半截文件，只有删掉才安全。
                    deleteQuietly(tmpPath)
                    ItemResult(job.stem, job.outputName,
                        status = if (result.reason in SKIPPABLE) ItemStatus.SKIPPED else ItemStatus.FAILED,
                        reason = result.reason, bytes = 0, deleteFailures = emptyList())
                }
                is ComposeResult.Success -> {
                    if (!outputVerified(tmpPath, result.totalBytes)) {
                        deleteQuietly(tmpPath)
                        ItemResult(job.stem, job.outputName, ItemStatus.FAILED,
                            reason = FailureReason.OUTPUT_NOT_VERIFIED, bytes = 0,
                            deleteFailures = emptyList())
                    } else if (!storage.move(tmpPath, finalPath)) {
                        deleteQuietly(tmpPath)
                        ioFailure(job)
                    } else {
                        // rename 成功的一刻本项就已经提交了，交出去的是提交后的收尾，不再回来。
                        commit(job, options, imagePath, videoPath, result.totalBytes)
                    }
                }
            }
        } catch (e: Exception) {
            // 来源打不开、通道写坏、目录建不起来……一切运行时故障都归 IO_ERROR，批次继续，
            // 但 .part 仍然要清掉。
            deleteQuietly(tmpPath)
            ioFailure(job)
        }
    }

    /**
     * 删除之前最后一道门：把写下去的 `.part` **读回来**重扫一遍。
     *
     * [ComposeResult.Success] 只说明 composer 认为自己产出了这样一个文件，不说明磁盘上真是那样：
     * 写入通道半途失败、XMP 段被复制成两份、长度对不上，任何一种下一步都会变成不可逆的删除。
     * 判据只认产物自己的字节：长度等于上报的 `totalBytes`、EOI 扫得回来、XAP 段恰好一份且在原位、
     * 那一份里写着 `MicroVideo`。代价是 JPEG 那一段被多读一遍（视频部分不用碰），换掉的是"猜"。
     * 任何异常都算没过——复检不许把故障读成成功。
     */
    private fun outputVerified(tmpPath: String, totalBytes: Long): Boolean = try {
        val source = storage.open(tmpPath)
        if (source.size() != totalBytes) {
            false
        } else {
            val scan = source.open().use { JpegScanner.scan(it) }
            val s = (scan as? JpegScanResult.Parsed)?.structure
            s != null && s.xmpSegments == 1 && s.oldXmp != null &&
                s.oldXmpText?.contains("MicroVideo") == true
        }
    } catch (e: Exception) {
        false
    }

    /**
     * rename 成功之后的收尾：SUCCESS 与字节数在函数第一行就定死，下面的删除只准往
     * [ItemResult.deletedSources]（绝对路径）与 [ItemResult.deleteFailures]（文件名）两条列表里
     * 补记录，**永远不许改写状态**——产物已经在最终名上了，一个源文件没删掉是警告，不是失败。
     */
    private fun commit(
        job: PairJob,
        options: BatchOptions,
        imagePath: String,
        videoPath: String,
        totalBytes: Long,
    ): ItemResult {
        val committed = ItemResult(job.stem, job.outputName, ItemStatus.SUCCESS, null, totalBytes)
        if (!options.deleteOriginals) return committed
        // 删除只可能发生在成功 rename 之后，且各自带守卫：抛出故障与返回 false 是同一种结果
        // ——文件还在原地，记警告。
        val deleted = ArrayList<String>()
        val deleteFailures = ArrayList<String>()
        if (deleteQuietly(imagePath)) deleted += imagePath
        else deleteFailures += job.imageName
        if (deleteQuietly(videoPath)) deleted += videoPath
        else deleteFailures += job.videoName
        return committed.copy(deleteFailures = deleteFailures, deletedSources = deleted)
    }

    private fun ioFailure(job: PairJob): ItemResult =
        ItemResult(job.stem, job.outputName, ItemStatus.FAILED, FailureReason.IO_ERROR, 0, emptyList())

    /**
     * 扫掉上一次运行留下的孤儿 `.part`。[composeOne] 的三条出路都删 `.part`，但前提是那次运行
     * 活到了分支上；被强杀或回收时清理来不及跑，半截文件就永久留在 `<target>/.tmp/`。
     * [BatchStorage.list] 只列一层，`.tmp` 是目录，之后没有任何东西会再看它一眼——占的就是用户
     * 存动态照片的那点空间。
     *
     * 判据只有名字：`<target>/.tmp` 里以 [PART_SUFFIX] 结尾的普通文件。这个目录由本类独占写入，
     * 所以里面的 `.part` 只可能是死掉的运行留下的；本次运行的 `.part` 在清扫之后才被创建，
     * 不可能被误删。目录里**不是** `.part` 的东西一律不碰，`.tmp` 之外的 `.part` 更不碰——
     * 那可能是用户自己的文件。
     *
     * 只在真要写东西时执行（`plan.jobs` 非空），和它上面的两次 `ensureDir` 同一条不变量：
     * 一次"无事可做"的批次不许对磁盘有任何影响。读目录失败就当没得扫——清扫是回收空间，
     * 不是正确性前提，没有资格掀翻批次。
     */
    private fun sweepStaleParts(targetDir: String) {
        val tmpDir = "$targetDir/.tmp"
        val stale = try {
            storage.list(tmpDir).mapNotNull { if (it.name.endsWith(PART_SUFFIX)) "$tmpDir/${it.name}" else null }
        } catch (e: Exception) {
            return
        }
        for (path in stale) deleteQuietly(path)
    }

    /**
     * [BatchStorage.delete] 只约定返回 true/false，而 [run] 的契约是**必定返回一份 [BatchReport]**：
     * 清理失败最多留一个垃圾 `.part`，没资格把整批掀翻。返回 false 的意思是
     * "到这里为止没能确认它没了"。
     */
    private fun deleteQuietly(path: String): Boolean =
        try {
            storage.delete(path)
        } catch (e: Exception) {
            false
        }

    private companion object {
        // 只有「已是动态照片」算跳过，其余拒绝都是 FAILED —— 尤其 MALFORMED_JPEG：真实的磁盘
        // 故障也会走到这个原因码，算作跳过就等于把它埋进"坏文件"里。
        val SKIPPABLE = setOf(FailureReason.ALREADY_MOTION_PHOTO)

        /**
         * `.part` 后缀只有一个定义处：写它的人（[composeOne]）和扫它的人（[sweepStaleParts]）
         * 必须认同一个字面量，否则清扫会安静地什么都不知道删。
         */
        const val PART_SUFFIX = ".part"
    }
}
