package dev.zayn.livephotobatch.core

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
 * spec §7.5：先写 `.part`，force 后 rename，rename 成功才允许删除原文件。
 * spec §9：单项失败不中断批次。取消在当前项之后生效。
 *
 * 每一项的生命周期只有三条出路，且都保证 `.part` 不残留：
 * - 校验/合成被拒（[ComposeResult.Failure]）→ 无条件删 `.part` → 原因在 [SKIPPABLE] 里
 *   记 SKIPPED，否则记 FAILED；
 * - 合成成功但 rename 失败（[BatchStorage.move] 返回 false）→ 删 `.part` → FAILED(IO_ERROR)，
 *   最终名上什么都没有，原文件一律不动；
 * - rename 成功 → SUCCESS，此时才（在 `deleteOriginals` 下）依次删图、删视频；删除失败
 *   只进 [ItemResult.deleteFailures]，不改变 SUCCESS（spec §7.5 把它当警告）。
 *
 * [ItemResult.bytes] 是产物全部字节（[ComposeResult.Success.totalBytes]），跳过/失败项恒为 0，
 * [BatchReport.composedBytes] 只累加成功项——Task 11 的"已写出 N MB"直接读它。
 * [ItemResult.deletedSources] 是**已成功删除的绝对路径**，Task 11 把它们连同产物一起交给
 * MediaStore 收敛索引；一个批次里只有走完 rename 的项可能出现在里面。
 *
 * 路径全部拼成 `"$dir/$name"` 字符串交给 [BatchStorage]：本类不知道 java.io.File 的存在，
 * 也不重新推导计划（UI 先 `plan()` 给用户预览，再把这个 PlanResult 原样交给 `run`）。
 */
class BatchRunner(private val storage: BatchStorage) {

    fun plan(options: BatchOptions): PlanResult =
        PairPlanner.plan(storage.list(options.sourceDir), storage.list(options.targetDir))

    fun run(
        plan: PlanResult,
        options: BatchOptions,
        cancel: CancelToken,
        onItem: (ItemResult) -> Unit,
    ): BatchReport {
        val items = ArrayList<ItemResult>()
        var composed = 0L
        var cancelled = false

        // 配不上对是计划结果而不是合成失败：走 NO_PAIR + SKIPPED，不碰任何文件，
        // 因此也不参与 composedBytes，更不该让 ensureDir 为它凭空建目录。
        for (missing in plan.unpaired) {
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
        val tmpPath = "${options.targetDir}/.tmp/${job.outputName}.part"
        return try {
            val result = storage.openWrite(tmpPath).use { sink ->
                MotionPhotoComposer.compose(storage.open(imagePath), storage.open(videoPath), sink)
            }
            when (result) {
                is ComposeResult.Failure -> {
                    // §7.5 的清理无条件，且不看字节数：Failure 上没有计数（Task 5 的裁定），
                    // 校验阶段一个字节不写，写入阶段可能已经留下半截文件——只有删掉才安全。
                    storage.delete(tmpPath)
                    ItemResult(job.stem, job.outputName,
                        status = if (result.reason in SKIPPABLE) ItemStatus.SKIPPED else ItemStatus.FAILED,
                        reason = result.reason, bytes = 0, deleteFailures = emptyList())
                }
                is ComposeResult.Success -> {
                    if (!storage.move(tmpPath, finalPath)) {
                        storage.delete(tmpPath)
                        ItemResult(job.stem, job.outputName, ItemStatus.FAILED,
                            FailureReason.IO_ERROR, 0, emptyList())
                    } else {
                        val deleteFailures = ArrayList<String>()
                        val deleted = ArrayList<String>()
                        if (options.deleteOriginals) {
                            if (storage.delete(imagePath)) deleted += imagePath
                            else deleteFailures += job.imageName
                            if (storage.delete(videoPath)) deleted += videoPath
                            else deleteFailures += job.videoName
                        }
                        ItemResult(job.stem, job.outputName, ItemStatus.SUCCESS, null,
                            result.totalBytes, deleteFailures, deleted)
                    }
                }
            }
        } catch (e: Exception) {
            // 来源打不开、通道写坏、目录建不起来……一切运行时故障都归 IO_ERROR，
            // 批次继续（spec §9），但 .part 仍然要按 §7.5 清掉。
            storage.delete(tmpPath)
            ItemResult(job.stem, job.outputName, ItemStatus.FAILED, FailureReason.IO_ERROR, 0, emptyList())
        }
    }

    private companion object {
        // spec 只在「已是动态照片」与「配不上对」两种情况下说"跳过"。其余拒绝都是
        // FAILED —— 尤其 MALFORMED_JPEG：Task 2 的 Cursor 会把 IOException 折叠成
        // Malformed，若把它算作跳过，真实磁盘故障会被埋进"坏文件"里
        val SKIPPABLE = setOf(FailureReason.ALREADY_MOTION_PHOTO)
    }
}
