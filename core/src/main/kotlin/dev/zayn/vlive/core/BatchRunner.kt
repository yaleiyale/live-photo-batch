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
 * spec §7.5：先写 `.part`，force 后 rename，rename 成功才允许删除原文件。
 * spec §9：单项失败不中断批次。取消在当前项之后生效，并且**每一项之前**都查一次标志
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
 *   没有一条路径能回头把 SUCCESS 改写成 FAILED（spec §7.5 把它当警告）。
 *
 * 上面三条的前提是那次运行还活着。进程被 force-stop 或被系统回收是第四种结局：三条出路一条都
 * 走不到，`.part` 留在原地，而那一次没人收尾。收尾由**下一次**运行的 [sweepStaleParts] 补做。
 *
 * `catch` 分支与 `.part` 清理同理：清理自己万一抛出，最多留一个垃圾文件，
 * 不允许穿出 [run]——[run] 的契约是必定返回一份 [BatchReport]（spec §9：单项失败不中断批次）。
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
        // 取消标志同样要在每一行之前查：这些行也是用户看得见的项，一个开局就取消的批次
        // 不该把整屏 NO_PAIR 推给 UI，然后还报告 cancelled = false。
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
                    // §7.5 的清理无条件，且不看字节数：Failure 上没有计数（Task 5 的裁定），
                    // 校验阶段一个字节不写，写入阶段可能已经留下半截文件——只有删掉才安全。
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
            // 来源打不开、通道写坏、目录建不起来……一切运行时故障都归 IO_ERROR，
            // 批次继续（spec §9），但 .part 仍然要按 §7.5 清掉。
            deleteQuietly(tmpPath)
            ioFailure(job)
        }
    }

    /**
     * 删除之前最后一道门：把写下去的 `.part` **读回来**重扫一遍。
     *
     * [ComposeResult.Success] 只说明 composer 认为自己产出了这样一个文件，不说明磁盘上真是那样：
     * 写入通道半途失败、XMP 段被复制成两份、长度对不上，任何一种下一步都会变成不可逆的删除。
     * 判据只认产物自己的字节：长度等于上报的 `totalBytes`、EOI 扫得回来、XAP 段恰好一份且有原位、
     * 那一份里写着 `MicroVideo`。代价是 JPEG 那一段被多读一遍（真机一张约 4 MB，视频部分不用碰），
     * 换掉的是"猜"。任何异常都算没过——复检不许把故障读成成功。
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
     * rename 成功之后的收尾：SUCCESS 与字节数在函数第一行就定死，下面的删除只准往两条
     * 列表里补记录（ruling 4：绝对路径进 [ItemResult.deletedSources]、文件名进
     * [ItemResult.deleteFailures]），**永远不许改写状态**——产物已经在最终名上了，
     * 一个源文件没删掉是警告，不是失败。
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
        // 删除只可能发生在成功 rename 之后，且各自带守卫：抛出故障与返回 false 同一种结果
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
     * 扫掉上一次运行留下的孤儿 `.part`。
     *
     * 为什么需要专门一道清扫：[composeOne] 的三条出路都删 `.part`，但"三条出路"的前提是那次运行
     * 活到了分支上。真实设备上还有第四条——进程被 force-stop 或被系统回收，`catch` 与清理都来不及跑，
     * 半截文件就永久留在 `<target>/.tmp/`。之后没有任何东西会再看它一眼：[BatchStorage.list] 只列一层，
     * `.tmp` 是目录，既进不了配对计划，也不会有人来删。占的就是用户存动态照片的那点空间。
     *
     * 判据只有名字：`<target>/.tmp` 里以 [PART_SUFFIX] 结尾的普通文件。这个目录由本类独占写入
     * （[composeOne] 只往里面写 `.part`），所以里面的 `.part` 只可能是死掉的运行留下的；
     * 本次运行的 `.part` 在清扫之后才被创建，不可能被误删。
     * 目录里**不是** `.part` 的东西一律不碰，`.tmp` 之外的 `.part` 更不碰——那可能是用户自己的文件。
     *
     * 只在真要写东西时执行（`plan.jobs` 非空），和它上面的两次 `ensureDir` 同一条不变量：
     * 一次"无事可做"的批次不许对磁盘有任何影响。读目录失败就当没得扫——清扫是回收空间，
     * 不是正确性前提，没有资格掀翻批次（spec §9）。
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
     * [BatchStorage.delete] 的接口契约只返回 true/false（Task 7 定的，Task 9 落地时必须守住），
     * 但 `run` 的契约是**必定返回一份 [BatchReport]**：真实实现万一抛出，清理失败最多留一个
     * 垃圾 `.part`，没资格把整批掀翻（spec §9）。返回 false 的意思是"到这里为止没能确认它没了"。
     */
    private fun deleteQuietly(path: String): Boolean =
        try {
            storage.delete(path)
        } catch (e: Exception) {
            false
        }

    private companion object {
        // spec 只在「已是动态照片」与「配不上对」两种情况下说"跳过"。其余拒绝都是
        // FAILED —— 尤其 MALFORMED_JPEG：Task 2 的 Cursor 会把 IOException 折叠成
        // Malformed，若把它算作跳过，真实磁盘故障会被埋进"坏文件"里
        val SKIPPABLE = setOf(FailureReason.ALREADY_MOTION_PHOTO)

        /**
         * `.part` 后缀只有一个定义处：写它的人（[composeOne]）和扫它的人（[sweepStaleParts]）
         * 必须认同一个字面量，否则清扫会安静地什么都不知道删。
         */
        const val PART_SUFFIX = ".part"
    }
}
