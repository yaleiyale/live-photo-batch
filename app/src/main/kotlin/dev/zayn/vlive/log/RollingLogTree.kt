package dev.zayn.vlive.log

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import timber.log.Timber

/**
 * 把日志写进**应用私有目录**（`filesDir/logs/`）的 Timber tree，自带轮转与清理。
 *
 * ## 为什么是私有目录，不是共享存储
 * 这个 app 有 `MANAGE_EXTERNAL_STORAGE`，写到 `/storage/emulated/0` 当然做得到 —— 但那正是需求里
 * "避免垃圾过多"要防的东西：日志散在相册看得见的地方，用户既找不到也删不干净。私有目录不需要任何
 * 权限、卸载即消失，出问题时 `adb pull /data/data/dev.zayn.vlive/files/logs` 也拿得到。
 *
 * ## 三条上限，谁先撞谁生效
 * 单文件写满 [maxFileBytes] 就换一个新的；总数超过 [maxFiles] 个、或合计超过 [maxTotalBytes]、
 * 或旧过 [maxAgeMs] 的，由 [planLogCleanup] 决定删谁（**当前正在写的这个永远不删**）。
 * 默认值合起来是"最多约 2 MB、最多留一周"。
 *
 * ## 所有磁盘动作都在这一条线程上
 * `Timber.log` 是同步调用，写在调用者线程上 —— 而调用者经常是主线程。所以这里只把一行文本交给
 * 单线程 executor，`mkdirs` / `listFiles` / 写 / 删全在 `vlive-log` 那条守护线程里做。
 * 同理，连时间戳都在后台线程格式化：`SimpleDateFormat` 不是线程安全的。
 *
 * ## 日志自己不许把 app 带崩
 * 每个入口都吞 [IOException]。写不进日志只是丢日志，不值得换一个崩溃。
 */
class RollingLogTree(
    private val dir: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) : Timber.Tree() {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vlive-log").apply { isDaemon = true }
    }
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private var writer: BufferedWriter? = null
    private var written = 0L

    init {
        // 开机先清一次：上次留下的过期日志不该等到第一条新日志才被发现。
        executor.execute { prune() }
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        executor.execute {
            val line = try {
                stamp.format(Date(clock())) + " " + letter(priority) + "/" + (tag ?: "vlive") + ": " + message
            } catch (e: RuntimeException) {
                // 时间格式化失败也不能漏掉这一行，退化成没有时间戳的版本。
                message
            }
            write(line + if (t != null) "\n" + stackOf(t) else "\n")
        }
    }

    private fun write(line: String) {
        try {
            val open = writer ?: open() ?: return
            open.write(line)
            open.flush()
            written += line.toByteArray(Charsets.UTF_8).size
            if (written >= maxFileBytes) {
                // 关掉即可：下一次 write 会另起一个新文件，旧的那个交给 prune() 判断去留。
                closeWriter()
                prune()
            }
        } catch (e: IOException) {
            closeWriter()
        } catch (e: RuntimeException) {
            closeWriter()
        }
    }

    /** 打开（或续写）当前该用的那个文件。失败返回 null —— 调用方会放弃这一行而不是抛出去。 */
    private fun open(): BufferedWriter? {
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return null
            prune()
            // 这里要的是 File（取长度、续写），不是 logs() 那份喂给 planLogCleanup 的摘要。
            val candidates = dir.listFiles()?.filter { it.isFile && isLogName(it.name) }.orEmpty()
            val newest = candidates.maxByOrNull { it.name }
            val file = if (newest != null && newest.length() < maxFileBytes) newest else File(dir, nextName())
            written = file.length()
            BufferedWriter(FileWriter(file, true)).also { writer = it }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun closeWriter() {
        try {
            writer?.close()
        } catch (e: IOException) {
            // 关不上也没什么可做的；下一次 open() 会重新拿一个句柄。
        }
        writer = null
        written = 0L
    }

    /** 列出目录 → 问 [planLogCleanup] 该删谁 → 删。当前文件不在可删集合里，所以句柄不会被抽走。 */
    private fun prune() {
        try {
            val files = logs()
            for (doomed in planLogCleanup(files, clock(), maxFiles, maxAgeMs, maxTotalBytes)) {
                if (!File(dir, doomed.name).delete()) {
                    // 删不掉就下一轮再试；这里抛出去只会让日志线程死掉。
                }
            }
        } catch (e: RuntimeException) {
            // 目录被外部改掉（比如用户清了数据）：放弃这一轮清理。
        }
    }

    private fun logs(): List<LogFile> =
        dir.listFiles()
            ?.filter { it.isFile && isLogName(it.name) }
            ?.map { LogFile(it.name, it.length(), it.lastModified()) }
            ?: emptyList()

    private fun isLogName(name: String): Boolean = name.startsWith(PREFIX) && name.endsWith(SUFFIX)

    /** 名字里的毫秒数定宽，所以字典序 == 时间序；同一毫秒重启就续写同一个文件，可接受。 */
    private fun nextName(): String =
        PREFIX + String.format(Locale.US, "%013d", clock()) + SUFFIX

    private fun stackOf(t: Throwable): String {
        val out = StringWriter()
        t.printStackTrace(PrintWriter(out))
        return out.toString()
    }

    private fun letter(priority: Int): String = when (priority) {
        Log.VERBOSE -> "V"
        Log.DEBUG -> "D"
        Log.INFO -> "I"
        Log.WARN -> "W"
        Log.ERROR -> "E"
        Log.ASSERT -> "A"
        else -> "?"
    }

    companion object {
        const val PREFIX = "log-"
        const val SUFFIX = ".txt"
        const val DEFAULT_MAX_FILE_BYTES = 512L * 1024L
        const val DEFAULT_MAX_FILES = 4
        val DEFAULT_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L
        const val DEFAULT_MAX_TOTAL_BYTES = 2L * 1024L * 1024L
    }
}
