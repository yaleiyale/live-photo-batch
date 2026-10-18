package dev.zayn.livephotobatch.app

import android.content.Context
import android.media.MediaScannerConnection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 产物要能被相册立刻看到，被删的原文件要从索引里消失（spec §7.6）。
 *
 * ## 为什么是"一次性传全部路径 + 最多等 2 秒"
 * `MediaScannerConnection.scanFile` 对每个路径各回调一次 `onScanCompleted`，而这里只要
 * "别再往相册里留幽灵条目"这个最终效果，不关心逐条确认。所以整个批次结束后把产物路径和
 * `ItemResult.deletedSources`（Task 8 给的**绝对路径**，正是扫描器要的串）合成一次调用，
 * 让系统扫一遍；回调哪怕只回来一次、哪怕一次都不回来，`await` 也会在 2 秒后放行——
 * 收敛是尽力而为，不能变成卡死 UI 的同步等待。
 *
 * ## 为什么必须从非主线程调用（Task 11 的调用点在 `Dispatchers.IO` 上）
 * `latch.await()` 阻塞的是**调用线程**。`scanFile` 的静态入口不传 Handler，
 * 其内部连接客户端的回调 Handler 是在绑定服务回调线程上建的（拿不到 Looper 时退到主 Looper），
 * 也就是说回调投递到**主线程**。于是：
 * - 在 `Dispatchers.IO` 线程上等：主线程空着，回调能跑，latch 正常放行，最多阻塞这条 IO 线程 2 秒
 *   （一次性、在批次末尾，且 `Dispatchers.IO` 是可扩的 64 线程池，不会饿死别的协程）；
 * - 万一在主线程上等：`onServiceConnected`/`onScanCompleted` 排在同一条被堵住的消息队列后面，
 *   必然等满 2 秒超时，还顺手 ANR。
 * 所以 [dev.zayn.livephotobatch.app.BatchViewModel] 只在 IO 调度器里调它，别把它挪到
 * `setContent` / `onClick` / `onResume` 里。
 *
 * ## 绝不抛
 * 绑定失败、`SecurityException`、线程被中断——任何一种都不该让已经跑完的批次在收尾时崩掉，
 * 也不该让 `UiState.running` 永远卡在 true（扫描是尽力而为的收尾动作，不是成功条件）。
 */
object MediaStoreSync {

    /** 提交 [paths] 给媒体扫描器，最多等 [TIMEOUT_MS] 毫秒；空列表直接返回。 */
    fun scan(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        val latch = CountDownLatch(1)
        try {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null) { _, _ ->
                latch.countDown()
            }
            latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // 扫描器不可用（ROM 裁掉了 MediaScanner 服务、无权限、被中断）只影响相册什么时候刷新，
            // 磁盘上的产物和已删除的原文件都是既成事实。超时同理：`await` 返回 false 就继续往下走。
        }
    }

    private const val TIMEOUT_MS = 2_000L
}
