package dev.zayn.livephotobatch.app.browse

import java.io.File

/**
 * 一个目录条目。[absolutePath] 是可以直接喂回 [subdirs] 或 `FileStorage` 的绝对路径；
 * [name] 是给人看的名字（[presets] 里是中文别名）；[parent] 是回上一级用的绝对路径，
 * 顶层无父时为空串。
 */
data class DirItem(val absolutePath: String, val name: String, val parent: String)

/**
 * 真·文件系统目录浏览器：只列出可读的子目录，绝不抛异常。
 *
 * ## root 契约（与 `FileStorage` 同一个字符串）
 * [root] 必须是**未规范化**的 `"/storage/emulated/0"`——不带尾斜杠、绝不空，且与传给
 * `FileStorage(root)` 的是同一个串。原因和 `FileStorage.resolve()` 一致：那里对
 * `path == root || path.startsWith("$root/")` 才走"原样使用"分支，root 带尾斜杠或为空会让
 * `"/storage/emulated/0"` 被当相对路径二次拼接成 `/storage/emulated/0/storage/emulated/0`，
 * 目录浏览随即静默变空。[subdirs] 里对相对路径做 `File(File(root), relative)` 拼接，
 * 生产环境调用方传进来的都是绝对路径（`startsWith("/")` 分支直接 `File(path)`），
 * 只有测试才会传相对串，所以这个 root 只在测试语境下参与拼接；无论哪种，root 字符串都保持
 * 同一约定，两个类共享它、不能各写各的。
 *
 * ## 绝不抛，只给空
 * [subdirs] 走 `File.listFiles()`，它对"不存在 / 不是目录 / 读不出来 / I/O 出错"统一返回
 * **null 而不抛**，这里一律映射成空列表——缺目录是常态（用户刚跳进一个空分支），不能因此崩。
 * 只保留 `isDirectory && canRead()` 且**名字不以 `.` 开头**的条目（过滤 `.nomedia`、
 * `.trash` 之类），再按 `name.lowercase()` 升序，保证两次列出顺序一致（`listFiles()` 的顺序
 * 在 POSIX 上未定义，排序不能省）。
 */
class DirBrowser(private val root: String) {
    /**
     * 常用入口；**不存在的一律过滤掉**——JVM/未挂载存储时返回空列表而不是抛，
     * 真机上只列出实际挂载的目录（[presets] 自身用绝对路径探测 `/storage/emulated/0`，
     * 与 [root] 是否可达无关）。
     */
    fun presets(): List<DirItem> {
        val storageRoot = File("/storage/emulated/0")
        val candidates = listOf(
            storageRoot to "内部存储",
            File(storageRoot, "DCIM") to "DCIM",
            File(storageRoot, "DCIM/Camera") to "相机",
            File(storageRoot, "Movies") to "视频",
            File(storageRoot, "Pictures") to "图片",
            File(storageRoot, "Download") to "下载",
        )
        return candidates.filter { it.first.isDirectory }
            .map { DirItem(it.first.absolutePath, it.second, it.first.parentFile?.absolutePath ?: "") }
    }

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
