package dev.zayn.vlive

import dev.zayn.vlive.core.ItemResult
import dev.zayn.vlive.core.ItemStatus

/**
 * 报告列表的筛选档位。[All] 不是"没选"，而是一个真档位 —— 切回它要能看到全部，
 * 所以它和三个状态档并列，而不是用 `null` 表示。
 */
enum class ReportFilter { All, Success, Skipped, Failed;

    /** 这一档要不要收进这条状态。分支按档位穷尽，加档位时这里编译不过。 */
    fun accepts(status: ItemStatus): Boolean = when (this) {
        All -> true
        Success -> status == ItemStatus.SUCCESS
        Skipped -> status == ItemStatus.SKIPPED
        Failed -> status == ItemStatus.FAILED
    }
}

/** 一页画多少行。真机（1216×2640）上一行报告约 44dp，20 行足够铺满两屏再多给一点余量。 */
const val REPORT_PAGE_SIZE = 20

/** 一页报告：要画的行、当前页（0 基）、总页数、以及**筛选后**的总行数。 */
data class ReportPage(
    val rows: List<ItemResult>,
    val page: Int,
    val pages: Int,
    val matched: Int,
)

/**
 * 纯函数：按 [ReportFilter.accepts] 过滤，再切出第 [page] 页。
 *
 * 存在的理由有两条，都跟"这条改动到底在解决什么"有关：
 * 1. **省的是组合，不是数据。** `state.items` 每条只有几个字符串，几百条也就几十 KB；真正贵的是
 *    外层那条 `verticalScroll` 的 `Column` 把**每一行都组合 + 测量 + 排版**一遍（[BatchScreen] 的
 *    KDoc 写了为什么这里不能换 `LazyColumn`）。一次只画一页，组合出来的节点数才真的被限住。
 * 2. 分页与筛选的规则要能在本地 JVM 上钉死，所以不留在 composable 里。
 *
 * 三条规则都有用例守着：
 * - **空结果也是一页**（`pages == 1`）：这时画"这个筛选下没有条目"，而不是一个 0/0 的页码；
 * - **页码夹回合法区间**：批次还在追加时 `pages` 会变大，取消筛选时会变小，越界的入参不许产出
 *   空白页（`page` 返回值就是夹好之后的，UI 直接用它画页码）；
 * - **顺序就是回报顺序**：不排序、不去重，报告读起来必须和磁盘上发生的先后一致。
 */
fun reportPage(
    items: List<ItemResult>,
    filter: ReportFilter,
    page: Int,
    size: Int = REPORT_PAGE_SIZE,
): ReportPage {
    val width = size.coerceAtLeast(1)
    val matched = items.filter { filter.accepts(it.status) }
    val pages = ((matched.size + width - 1) / width).coerceAtLeast(1)
    val current = page.coerceIn(0, pages - 1)
    val from = current * width
    return ReportPage(
        rows = matched.subList(from, minOf(from + width, matched.size)),
        page = current,
        pages = pages,
        matched = matched.size,
    )
}
