package dev.zayn.livephotobatch.app.io

import dev.zayn.livephotobatch.core.BatchStorage
import dev.zayn.livephotobatch.core.ChannelSource
import dev.zayn.livephotobatch.core.DirEntry
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel

/**
 * 可反复打开的只读 `java.io.File` 来源。
 *
 * 每次 [ChannelSource.open] 都新建一条 `RandomAccessFile` 通道，所以同一个来源能被读两遍
 * （合成阶段要先扫 JPEG 结构、再拷尾部），各条通道彼此独立、由调用方各自关闭。
 * 文件不存在时 [ChannelSource.open] 抛 `FileNotFoundException`（契约要求），
 * 而 [ChannelSource.size] 走 `File.length()`，对不存在的路径返回 0，不抛。
 */
fun fileChannelSource(f: File): ChannelSource = object : ChannelSource {
    override fun open(): ReadableByteChannel {
        val raf = RandomAccessFile(f, "r")
        // 关掉这条通道就等于关掉 raf：FileChannel 是它唯一的持有者，不会漏句柄。
        val channel: FileChannel = raf.channel
        return channel
    }

    override fun size(): Long = f.length()
}

/**
 * 路径一律是绝对路径；root 参数只用于测试时改写到应用私有目录。
 * 传进来的绝对路径若不在 root 下，按原样使用——生产环境就是 "/"。
 *
 * ## `openWrite` 做不到「关闭才对读者可见」，这个不对称是契约写明的，不要去"修"它
 * [BatchStorage.openWrite] 只承诺 **`close()` 成功返回后**全部字节在路径上完整可见，
 * 并明确写了 `close()` 之前「不作任何保证」。内存实现能把条目攒到 close 才发布，
 * 真实文件系统做不到：`RandomAccessFile(file, "rw")` 一打开就把文件创建出来并 truncate 到 0，
 * 于是写一半的时候磁盘上就是一个半截文件，任何读者（媒体扫描、下一个批次、用户自己）都能看到。
 * 后果是分工：可见性由调用方保证，不是由这一层保证——Task 8 的 `BatchRunner` 正是
 * 先写 `xxx.live.tmp.part`、`close()`、再 [move] 成正式名字，靠 `rename(2)` 完成发布。
 * 所以本类不要再"顺手"加临时名/改名的把戏，也不要允许任何人直接拿正式路径当写入目标；
 * 两种做法都只是把这个不对称挪个地方，还会让 `move` 的替换语义变得含混。
 *
 * 关闭之后的行为和内存实现不同但同样满足契约：重复 `close()` 是幂等 no-op（只 force+close 一次），
 * 关闭之后再 `write` 抛 `ClosedChannelException`（内存实现抛 `IllegalStateException`）——
 * 契约只要求"必须失败"，不要求异常类型。
 *
 * ## `ensureDir` 抛 `IOException` 是设计，不是漏网的异常
 * 输出目录建不起来时 [ensureDir] 抛 `IOException`，而 `BatchRunner.run` **刻意让它逃出去**：
 * 见 spec §9 的错误处理原则——"目标目录不可写/不可建"是整批不开工的**全局前置条件**，
 * 不是某一条目的 `FAILED`。把它塞进报告反而会让用户以为只是几张图失败。
 * 调用方（Task 11 的 UI）因此必须按"整批被拒绝"来处理这个异常。
 *
 * ## 其余与 `BatchStorage` 契约对齐的点（每一条都是内存实现与本类的分叉风险）
 * - [list]：目录不存在 / 读不出来时 `File.listFiles()` 返回 **null 而不抛异常**，一律给空列表
 *   （Task 8 在任何 `ensureDir` 之前就要 `list(targetDir)`，那时目录往往还不存在）。
 *   必须按 `name` 自然升序排序：Task 6 的 `PairPlanner` 对同 stem 候选取"列表第一个"
 *   （`a.jpeg` 排在 `a.jpg` 前），Task 8 的删原文件也要求同一目录两次列出结果一致，
 *   而 `File.listFiles()` 的顺序在 POSIX 上是未定义的，所以 `sortedBy` 不能省。
 * - [exists]：契约只认普通文件，而 `File.exists()` 对目录返回 true，因此这里必须是 `isFile`。
 * - [delete]：`File.delete()` 对**空目录**返回 true，因此也必须先 `isFile` 挡一道；
 *   路径不存在时返回 false 而不是抛（Task 8 的 catch 清理路径依赖这条）。
 * - [move]：改名之前绝不删除目标，见方法注释。
 */
class FileStorage(private val root: String) : BatchStorage {

    private fun resolve(path: String): File =
        if (path.startsWith(root + "/")) File(path) else File(File(root), path.removePrefix("/"))

    override fun list(dirPath: String): List<DirEntry> {
        val dir = resolve(dirPath)
        // listFiles() 对"不是目录 / 没权限 / I/O 出错"统一返回 null，正好是契约要的"绝不抛"。
        val children = dir.listFiles() ?: return emptyList()
        return children.filter { it.isFile && it.canRead() }
            .map { DirEntry(it.name, it.length()) }
            .sortedBy { it.name }
    }

    override fun open(filePath: String): ChannelSource = fileChannelSource(resolve(filePath))

    // 契约要求只认普通文件，所以必须是 isFile 而不是 exists()
    override fun exists(filePath: String): Boolean = resolve(filePath).isFile

    override fun ensureDir(dirPath: String) {
        val dir = resolve(dirPath)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建目录 $dirPath")
    }

    override fun openWrite(filePath: String): WritableByteChannel {
        val file = resolve(filePath)
        file.parentFile?.mkdirs()
        val channel: FileChannel = RandomAccessFile(file, "rw").channel
        channel.truncate(0)
        return object : WritableByteChannel {
            private var open = true
            override fun write(src: ByteBuffer): Int = channel.write(src)
            override fun close() {
                if (!open) return
                open = false
                try { channel.force(true) } finally { channel.close() }
            }
            override fun isOpen(): Boolean = open
        }
    }

    override fun move(from: String, to: String): Boolean {
        val src = resolve(from)
        val dst = resolve(to)
        if (!src.isFile) return false
        dst.parentFile?.mkdirs()
        // 不先删目标：renameTo 走 rename(2)，同文件系统内对已存在目标是原子替换；先删会开一个旧文件已消失的窗口
        if (src.renameTo(dst)) return true
        // 只有改名失败（跨卷、rename 被拒）才退化成 copy+delete。这条路不是原子的：
        // File.copyTo(overwrite = true) 会先清掉目标再写，中间同样有半截窗口——
        // 所以它只在 rename 已经不可能成功时才走，契约里"原子"只覆盖正常路径。
        return try {
            src.copyTo(dst, overwrite = true)
            src.delete()
        } catch (e: IOException) {
            false
        }
    }

    // 同理：目录必须返回 false 且不动它，所以先判 isFile
    override fun delete(filePath: String): Boolean =
        resolve(filePath).let { it.isFile && it.delete() }
}
