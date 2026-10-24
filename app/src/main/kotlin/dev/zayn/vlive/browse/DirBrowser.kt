package dev.zayn.vlive.browse

import java.io.File

/**
 * 一个目录条目。[absolutePath] 是可以直接喂回 [subdirs] 的绝对路径；只有在存储 root 之内
 * （`path == root || path.startsWith("$root/")`，见 `FileStorage.resolve()`）时它才能原样喂给
 * `FileStorage`——root 之外的条目（例如 `subdirs("/sdcard")`）会被 `FileStorage` 重新拼到 root
 * 之下，语义不同，不要当同一个函数用。[name] 是给人看的名字；[parent] 是回上一级用的绝对路径，
 * 顶层无父时为空串。
 */
data class DirItem(val absolutePath: String, val name: String, val parent: String)

/**
 * 弹窗副标题的显示形式：把存储根换成 [rootLabel]，往下的层级照原样留着。
 *
 * - `/storage/emulated/0` → `内部存储`
 * - `/storage/emulated/0/DCIM/Camera` → `内部存储/DCIM/Camera`
 * - root **之外**的串（`/sdcard`、`/storage/emulated`）原样给出规范化后的绝对路径：
 *   那种地方选不了（`isSelectable` 为假），换成"内部存储/…"反而是在骗人。
 *
 * 这是给看的，不是给用的：真正喂给批次的一直是 `absolutePath` 那条真路径，主界面两行
 * （`DetailRow`）也照旧画整条真路径、不许截断。
 *
 * [rootLabel] 由调用方从字符串资源取，而不是这里写死一个中文常量：本文件要保持"能在本地 JVM 上
 * 直接测"这个性质，一旦去摸 `R.string` 就得把资源系统一起拖进来。
 *
 * 分隔符：比较与裁剪都走 [java.io.File] 的绝对化，显示时才把主机分隔符换成 `/`——
 * 设备上本来就是 `/`，而这条规则要在 Windows 的临时目录里也能被单测钉住。
 */
internal fun displayPath(path: String, root: String, rootLabel: String): String {
    val here = File(root).absoluteFile.path
    val full = File(path).absoluteFile.path
    if (full == here) return rootLabel
    val prefix = here + File.separator
    if (!full.startsWith(prefix)) return full
    return "$rootLabel/${full.removePrefix(prefix).replace(File.separatorChar, '/')}"
}

/**
 * 真·文件系统目录浏览器：只列出可读的子目录，绝不抛异常。
 *
 * ## root 契约（与 `FileStorage` 同一个字符串）
 * [root] 必须是**未规范化**的 `"/storage/emulated/0"`——不带尾斜杠、绝不空，且与传给
 * `FileStorage(root)` 的是同一个串。原因和 `FileStorage.resolve()` 一致：那里对
 * `path == root || path.startsWith("$root/")` 才走"原样使用"分支，root 带尾斜杠或为空会让
 * `"/storage/emulated/0"` 被当相对路径二次拼接成 `/storage/emulated/0/storage/emulated/0`，
 * 目录浏览随即静默变空。[subdirs] 里对相对路径做 `File(File(root), relative)` 拼接，而生产环境
 * 调用方传进来的都是绝对路径（`startsWith("/")` 分支直接 `File(path)`），只有测试才传相对串——
 * 无论哪种，两个类共享同一个 root 字符串，不能各写各的。
 *
 * **"是不是绝对路径"的判定规则和 `FileStorage.resolve()` 并不是同一个函数**：本类只看
 * `startsWith("/")`，root 之外的绝对路径（`/sdcard`、`/data/local/tmp`）同样可以浏览，
 * 而 `FileStorage` 只对 root 之内（含 root 本身）原样使用、其余一律重新拼到 root 之下。
 * 后果写在 [DirItem] 的契约里：浏览器给出的路径不保证能喂给存储层，别当同一条路径解析用。
 *
 * ## 绝不抛，只给空
 * [subdirs] 走 `File.listFiles()`，它对"不存在 / 不是目录 / 读不出来 / I/O 出错"统一返回
 * **null 而不抛**，这里一律映射成空列表——缺目录是常态（用户刚跳进一个空分支），不能因此崩。
 * 只保留 `isDirectory && canRead()` 且**名字不以 `.` 开头**的条目（过滤 `.nomedia`、`.trash`
 * 之类），再按 `name.lowercase()` 升序，保证两次列出顺序一致（`listFiles()` 的顺序在 POSIX 上
 * 未定义，排序不能省）。
 */
class DirBrowser(private val root: String) {
    /**
     * 列出 [relative] 下的可读子目录。以 `/` 开头视为绝对路径直接用（[root] 不参与），
     * 否则拼在 [root] 之下。缺目录 / 非目录 / 不可读都返回空列表，绝不抛。
     */
    fun subdirs(relative: String): List<DirItem> {
        val dir = if (relative.startsWith("/")) File(relative) else File(File(root), relative)
        // listFiles() 对"不存在 / 不是目录 / 没权限 / I/O 出错"统一返回 null，正好是"绝不抛"。
        val children = dir.listFiles() ?: return emptyList()
        return children.filter { it.isDirectory && it.canRead() && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
            .map { DirItem(it.absolutePath, it.name, dir.absolutePath) }
    }
}

/**
 * 存储根那一级要画出来的**一条**列表：这一层的真实子目录，命中 [aliases] 的换成别名显示。
 *
 * 纯函数，不碰磁盘也不碰资源，所以能在本地 JVM 单测里钉住；别名表由调用方（`BatchScreen`）
 * 用字符串资源拼出来。
 *
 * 规则（返回值顺序即画出来的顺序）：
 * - 列出来的**只有 `currentPath` 的真实子目录**，不补任何快捷方式——凭空多出来的条目会让人
 *   以为磁盘上真有；
 * - `currentPath` 自己不出现（别名表里根自身那一条也会被丢掉）；
 * - 命中某个真实子目录的别名，替换它的显示名（`<root>/Pictures → 图片`）；
 * - 没被别名覆盖的真实子目录（`Documents`、`Music`……）一律保留，不因为"不在别名里"就够不着；
 * - 按显示名 `lowercase()` 码元自然序，与 [DirBrowser.subdirs] 同一条规则，不给常用项开小灶。
 *
 * 键的比较走 [java.io.File] 的绝对化，**不靠手写分隔符拼接**，所以注入临时目录的测试主机
 * （含 Windows）也成立。注意它**大小写敏感**，而共享存储挂载大小写不敏感：别名拼写与磁盘
 * 真实名不一致时（例如磁盘上是 `download` 而别名写 `Download`）别名不命中，会露出真实名。
 */
internal fun mergedRootEntries(
    aliases: Map<String, String>,
    children: List<DirItem>,
    currentPath: String,
): List<DirItem> {
    val here = canonical(currentPath)
    val named = aliases.filterKeys { canonical(it) != here }
    return children
        .filter { canonical(it.absolutePath) != here }
        .map { child -> named[canonical(child.absolutePath)]?.let { child.copy(name = it) } ?: child }
        .distinctBy { canonical(it.absolutePath) }
        .sortedBy { it.name.lowercase() }
}

private fun canonical(path: String): String = File(path).absoluteFile.path
