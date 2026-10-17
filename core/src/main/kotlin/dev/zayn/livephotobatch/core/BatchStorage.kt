package dev.zayn.livephotobatch.core

import java.nio.channels.WritableByteChannel

/**
 * BatchRunner 需要的全部文件动作。:app 用 java.io.File 实现，测试用内存实现。
 *
 * 路径一律是绝对路径字符串，分隔符固定为 `/`；实现方不解释平台差异。
 * 下面每条方法级约定都是**接口契约**：Task 8 的 tmp-write → rename → delete-originals
 * 与 Task 9 的 java.io.File 实现各自只按这里的文字对齐，任何一侧自行放宽都会让批次
 * 静默产出半截文件而没有任何测试变红。
 */
interface BatchStorage {
    /**
     * 只列一层，返回普通文件。目录与特殊文件不出现。
     *
     * 顺序是契约的一部分：按 `name` 的自然升序（`String.compareTo`，逐 16 位码元、
     * 与 locale 无关，所以 ASCII 大写排在小写之前、`a.jpeg` 排在 `a.jpg` 之前）。
     * Task 6 的 `PairPlanner` 对同一 stem 上的两张候选取**列表中第一个**，
     * Task 8 的删除-originals 也要求同一目录两次列出得到同一个列表，
     * 因此两个实现（内存与 :app 的 FileStorage）都必须排序，不能靠目录天然顺序。
     */
    fun list(dirPath: String): List<DirEntry>

    /**
     * 打开只读来源。来源必须可以反复 `open()`（合成阶段要读两遍）。
     * 文件不存在时抛 `java.io.FileNotFoundException`。
     */
    fun open(filePath: String): ChannelSource

    fun exists(filePath: String): Boolean

    /** 幂等创建目录；已存在不算失败。实现不保证创建多级父目录之外的语义。 */
    fun ensureDir(dirPath: String)

    /**
     * 打开写入通道；父目录必须已存在。调用方负责 close。每次打开都从 0 字节开始（截断式）。
     *
     * 可见性约定：`close()` 成功返回后，写进这个通道的全部字节在 `filePath` 上完整可见；
     * `close()` 之前 `filePath` 上的内容**不作任何保证**——内存实现此刻条目还不存在，
     * 真实文件实现打开即 truncate，读到的是 0 字节或半截数据。所以调用方必须先 `close`
     * 再 move/再读，BatchRunner 正是按这个顺序写的。
     *
     * 关闭约定：重复 `close()` 是幂等 no-op，不得重复落盘、追加或损坏已落盘的数据；
     * 关闭之后再 `write` 必须失败（内存实现抛 `IllegalStateException`）。
     */
    fun openWrite(filePath: String): WritableByteChannel

    /**
     * 原子改名，允许覆盖已存在的 to。
     *
     * `to` 已存在时先删后改，成功后 `to` 的内容就是 `from` 原来的内容、`from` 消失；
     * `from` 不存在返回 false。返回 true 之后 `exists(to)` 必须为 true——
     * BatchRunner 只在 true 时才删原文件。
     */
    fun move(from: String, to: String): Boolean

    /** 只删文件；不存在返回 false。 */
    fun delete(filePath: String): Boolean
}
