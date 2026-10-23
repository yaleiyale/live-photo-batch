package dev.zayn.vlive.journal

import dev.zayn.vlive.log.LogFile
import dev.zayn.vlive.log.planLogCleanup
import java.io.File
import java.util.Locale

/**
 * 不可逆删除的凭据：每一批里**真的被删掉**的原文件，落进应用私有目录（`filesDir/journal/`）。
 *
 * ## 为什么非要有这份东西
 * `BatchRunner` 删原文件只看 `move()` 是否为真，删完就把绝对路径塞进 [dev.zayn.vlive.core.ItemResult.deletedSources]，
 * 而那些 `ItemResult` 只活在 `UiState.items` 里：下一次 Start 一句 `items = emptyList()` 就全清了，
 * 进程被杀同样清零。也就是说在此之前，**这个 app 做完一件不可逆的事之后不留任何记录**，
 * 用户连"刚才删了哪些"都列不出来，更别提去回收站或备份里找。
 *
 * ## 记的是事实，不是意图
 * 写入点在每一项 commit **之后**，所以行里只有已经删掉的路径与已经失败的删除。"打算删"的预记账
 * 要引入 pending 状态和双写，换到的只是把同一个窗口从"删完没记"挪到"记了没删"，不值。
 *
 * ## 一批一个文件
 * 名字是 `deleted-<13 位毫秒>.txt`，定宽所以字典序 == 时间序（与日志同一条理由）。
 * "这一批删了什么"= 读当前批次那一个文件，不必解析全部历史。
 *
 * ## 保留策略借日志那套纯函数
 * [planLogCleanup] 是 `log/LogRotation.kt` 的顶层函数，输入只是 `LogFile(name, bytes, lastModified)`，
 * 与"日志"没有任何耦合，所以这里直接调用它，不复制一份、也不去改日志。上限比日志宽松得多
 * （一行约 200 字节、36 张一批约 7 KB），但**确实带时间上限**：过期那批文件会被整体删掉，
 * 于是"半年前删了哪些"这种问题按策略本身就没有答案。这是选定过的代价，不是疏漏。
 *
 * ## 写不进只是丢记录
 * 每个入口都吞异常。此刻文件已经删了，把这一项翻成 `FAILED` 只会让报告更假 —— 失败信息反而没了。
 *
 * 路径以制表符分隔。假设文件名不含制表符（Android 外部存储的文件名不会有）；
 * 读取时用行内记的条数收口，所以真出现了也只会截在那儿，不会把字段读串。
 */
class DeleteJournal(
    private val dir: File,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * 发一个批次号，同时就是这一批那个凭据文件的名字。
     *
     * 同一毫秒里连开两批会被 `running` 挡住，但撞上一次的代价不对称（两批写进同一个文件，
     * 读回来就会把上一批也算进这一批的清单里），所以这里让一让，只要一次 stat。
     */
    fun newBatchId(): Long {
        var id = clock()
        while (File(dir, nameOf(id)).exists()) id++
        return id
    }

    /** 记下一项删掉的东西。[deleted] 为空就什么都不写 —— 没删东西的批次不该在凭据里占行。 */
    fun record(batchId: Long, outputName: String, deleted: List<String>) {
        if (deleted.isEmpty()) return
        val fields = listOf(clock().toString(), batchId.toString(), outputName, deleted.size.toString()) + deleted
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            prune()
            File(dir, nameOf(batchId)).appendText(fields.joinToString(SEP) + "\n")
        } catch (e: RuntimeException) {
            // 目录被外部改掉（清了数据等）：放弃这一行。
        } catch (e: java.io.IOException) {
            // 写不进去也只是丢记录。
        }
    }

    /** 某一批被删掉的原文件绝对路径，按写入顺序。文件不在 / 读不出 → 空列表。 */
    fun deletedPaths(batchId: Long): List<String> = try {
        val file = File(dir, nameOf(batchId))
        if (!file.isFile) return emptyList()
        file.readLines().flatMap { parseDeleted(it) }
    } catch (e: RuntimeException) {
        emptyList()
    } catch (e: java.io.IOException) {
        emptyList()
    }

    private fun parseDeleted(line: String): List<String> {
        if (line.isEmpty()) return emptyList()
        val fields = line.split(SEP)
        if (fields.size < MIN_FIELDS) return emptyList()
        val count = fields[3].toIntOrNull() ?: return emptyList()
        if (count <= 0) return emptyList()
        return fields.subList(MIN_FIELDS, fields.size).take(count)
    }

    private fun prune() {
        val files = dir.listFiles()
            ?.filter { it.isFile && isJournalName(it.name) }
            ?.map { LogFile(it.name, it.length(), it.lastModified()) }
            ?: return
        for (doomed in planLogCleanup(files, clock(), maxFiles, maxAgeMs, maxTotalBytes)) {
            // 删不掉就下一轮再试，不值得为它抛。
            File(dir, doomed.name).delete()
        }
    }

    companion object {
        private const val PREFIX = "deleted-"
        private const val SUFFIX = ".txt"
        private const val SEP = "\t"

        /** epochMillis、batchId、outputName、count —— 之后才是变长的路径字段。 */
        private const val MIN_FIELDS = 4

        const val DEFAULT_MAX_FILES = 60
        const val DEFAULT_MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000
        const val DEFAULT_MAX_TOTAL_BYTES = 2L * 1024 * 1024

        fun nameOf(batchId: Long): String = PREFIX + String.format(Locale.US, "%013d", batchId) + SUFFIX

        private fun isJournalName(name: String): Boolean = name.startsWith(PREFIX) && name.endsWith(SUFFIX)
    }
}
