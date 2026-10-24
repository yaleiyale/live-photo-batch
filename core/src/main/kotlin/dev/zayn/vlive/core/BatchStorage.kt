package dev.zayn.vlive.core

import java.nio.channels.WritableByteChannel

/**
 * BatchRunner 需要的全部文件动作。:app 用 java.io.File 实现，测试用内存实现。
 *
 * 路径一律是绝对路径字符串，分隔符固定为 `/`；实现方不解释平台差异。
 * 下面每条方法级约定都是**接口契约**：任何一侧自行放宽，批次就会静默产出半截文件，
 * 而没有任何测试会变红。
 */
interface BatchStorage {
    /**
     * 只列一层，返回普通文件。目录与特殊文件不出现。
     *
     * 顺序是契约的一部分：按 `name` 的自然升序（`String.compareTo`，逐 16 位码元、与 locale 无关，
     * 所以 ASCII 大写排在小写之前、`a.jpeg` 排在 `a.jpg` 之前）。同一 stem 上出现两张候选时取
     * **列表中第一个**，删除原文件也要求同一目录两次列出得到同一个列表，所以每个实现都必须自己排序，
     * 不能依赖目录的天然顺序。
     *
     * 目录不存在时返回**空列表**，绝不抛异常：计划阶段在任何 `ensureDir` 之前就要
     * `list(targetDir)`，输出目录此时往往还不存在。
     */
    fun list(dirPath: String): List<DirEntry>

    /**
     * 打开只读来源。来源必须可以反复 `open()`：一个产物要经过扫描、搬运、尾巴探测三次读。
     * 文件不存在时抛 `java.io.FileNotFoundException`。
     */
    fun open(filePath: String): ChannelSource

    /**
     * **只对「当前存在的普通文件」为 true**；目录以及任何非普通文件一律 false。
     * 想知道一个目录在不在用 `list`，不要用 `exists`。
     *
     * `java.io.File.exists()` 对目录返回 true，所以基于它的实现必须先判 `isFile`，
     * 否则「产物是否已存在」这类判断会在设备上对着目录说谎。
     */
    fun exists(filePath: String): Boolean

    /** 幂等创建目录；已存在不算失败。除创建多级父目录之外不保证别的语义。 */
    fun ensureDir(dirPath: String)

    /**
     * 打开写入通道；父目录必须已存在。调用方负责 close。每次打开都从 0 字节开始（截断式）。
     *
     * 可见性：`close()` 成功返回后，写进这个通道的全部字节在 `filePath` 上完整可见；
     * `close()` 之前 `filePath` 上的内容**不作任何保证**。所以调用方必须先 `close`、再 move、再读。
     *
     * 关闭：重复 `close()` 是幂等 no-op，不得重复落盘、追加或损坏已落盘的数据；
     * 关闭之后再 `write` 必须失败。
     */
    fun openWrite(filePath: String): WritableByteChannel

    /**
     * 原子改名，允许覆盖已存在的 to。
     *
     * 返回 true 之后唯一可观察的结果：`to` 的内容就是 `from` 原来的内容，`from` 不再存在，
     * 原先已存在的 `to` 被整体替换；此时 `exists(to)` 必须为 true——BatchRunner 只在 true 时
     * 才删原文件。`from` 不存在时返回 false，且 `from`、`to` 都保持调用前的状态。
     * 怎么做到替换是实现的私事。
     *
     * **不得在改名之前先删除 `to`**：`rename(2)` 在同一文件系统内对已存在目标本来就是原子替换，
     * 先删会开出一个「旧产物已经没了、新产物还没就位」的窗口，中途崩溃或被媒体扫描撞上就永久
     * 丢掉了上一版产物。
     */
    fun move(from: String, to: String): Boolean

    /**
     * 只删普通文件：删掉一个存在的普通文件返回 true；路径不存在，或者是个目录，返回 false
     * 且不碰它。语义与 `exists` 对齐（只对普通文件成立）。
     * `java.io.File.delete()` 对**空目录**返回 true，所以基于它的实现必须先判 `isFile`。
     */
    fun delete(filePath: String): Boolean
}
