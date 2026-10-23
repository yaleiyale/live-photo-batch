package dev.zayn.vlive.log

/** 一个日志文件的清理判断需要的三样信息（刻意不碰 `java.io.File`，好让规则能在本地 JVM 上钉死）。 */
data class LogFile(val name: String, val bytes: Long, val lastModified: Long)

/**
 * 该删哪些日志文件。**纯函数**：不碰磁盘，所以 [LogFileRotationTest] 能在无设备的 JVM 上把每条
 * 规则钉住，`RollingLogTree` 只负责"列出目录 → 调这里 → 按结果删"。
 *
 * 三条上限同时生效，任何一条超了都要往下删：
 * - [maxAgeMs]：过期就删，跟数量与体积无关 —— 一台几个月没打开的设备不该留着上季度的日志；
 * - [maxFiles]：文件数上限；
 * - [maxTotalBytes]：**总字节**上限。这条是需求里"避免垃圾过多"真正说的那件事：只限文件数会漏掉
 *   "单个文件被写爆"的情况，只限体积又会留一堆空壳文件。
 *
 * 两条刻意的规则：
 * 1. **最新的那个文件永远不删**，哪怕它自己就超过 [maxTotalBytes]。它是当前这一轮正在写的，
 *    删了等于把"刚刚发生了什么"丢掉，而轮转（写满就换文件）本来就会把它压在上限附近；
 * 2. 删除顺序是**从旧到新**（`lastModified` 升序，同分按名字），所以留下的永远是最近的上下文。
 *    这个键是**唯一**的一份：`RollingLogTree.open` 挑"接着写哪个文件"用的就是同一个比较，
 *    两边一旦各认各的（名字 vs mtime），"永不删的那个"和"正在写的那个"就不是同一个文件，
 *    清理会把手上的日志抽走。
 */
fun planLogCleanup(
    files: List<LogFile>,
    nowMs: Long,
    maxFiles: Int,
    maxAgeMs: Long,
    maxTotalBytes: Long,
): List<LogFile> {
    if (files.isEmpty()) return emptyList()
    val byAge = files.sortedWith(compareBy({ it.lastModified }, { it.name }))
    val newest = byAge.last()

    val doomed = ArrayList<LogFile>()
    var keep = ArrayList(byAge)

    // 1) 过期
    val fresh = ArrayList<LogFile>(keep.size)
    for (file in keep) {
        if (file !== newest && nowMs - file.lastModified > maxAgeMs) doomed += file else fresh += file
    }
    keep = fresh

    // 2) 数量（`keep.size > 1` 是"最新那个永不删"的另一半：maxFiles 给 0 也不许把目录清空）
    while (keep.size > maxFiles && keep.size > 1) {
        doomed += keep.removeAt(0)
    }

    // 3) 总体积（从旧到新删，且永远给当前文件留着）
    var total = keep.sumOf { it.bytes }
    while (total > maxTotalBytes && keep.size > 1) {
        val victim = keep.removeAt(0)
        doomed += victim
        total -= victim.bytes
    }

    return doomed
}
