package dev.zayn.vlive

import dev.zayn.vlive.core.FailureReason
import dev.zayn.vlive.core.ItemResult
import dev.zayn.vlive.core.ItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Local JVM test (`app/src/test`) for the report's filter + paging rules — the pure half of
 * `ReportPage.kt`. The composables are not covered here (no Compose UI-test dependency in this
 * module, same as `BatchScreen` itself); what is covered is exactly the arithmetic that decides
 * which rows get composed at all, because that is the part a wrong answer silently breaks.
 */
class ReportPageTest {

    private fun item(stem: String, status: ItemStatus, reason: FailureReason? = null) =
        ItemResult(stem, "${stem}_MP.jpg", status, reason, 1024L * 1024L)

    private fun mixed(): List<ItemResult> = listOf(
        item("a", ItemStatus.SUCCESS),
        item("b", ItemStatus.SKIPPED, FailureReason.NO_PAIR),
        item("c", ItemStatus.SUCCESS),
        item("d", ItemStatus.FAILED, FailureReason.IO_ERROR),
        item("e", ItemStatus.SKIPPED, FailureReason.NOT_JPEG),
    )

    @Test fun `all keeps every row in report order`() {
        val page = reportPage(mixed(), ReportFilter.All, 0, size = 10)
        assertEquals(listOf("a", "b", "c", "d", "e"), page.rows.map { it.item.stem })
        assertEquals(listOf(0, 1, 2, 3, 4), page.rows.map { it.index })
        assertEquals(5, page.matched)
        assertEquals(1, page.pages)
    }

    @Test fun `each status filter keeps only its own rows`() {
        val byFilter = mapOf(
            ReportFilter.Success to ItemStatus.SUCCESS,
            ReportFilter.Skipped to ItemStatus.SKIPPED,
            ReportFilter.Failed to ItemStatus.FAILED,
        )
        for ((filter, status) in byFilter) {
            val page = reportPage(mixed(), filter, 0, size = 10)
            assertTrue(filter.name, page.rows.isNotEmpty())
            assertEquals(filter.name, listOf(status), page.rows.map { it.item.status }.distinct())
            assertEquals(filter.name, page.matched, page.rows.size)
        }
        assertEquals(listOf("b", "e"), reportPage(mixed(), ReportFilter.Skipped, 0, size = 10).rows.map { it.item.stem })
    }

    /**
     * The index is the key the UI stores per-row expansion under. If filtering renumbered rows
     * within the page, expanding one row would light up a different one after the filter changes.
     */
    @Test fun `indices stay pointing at the full list after filtering`() {
        val page = reportPage(mixed(), ReportFilter.Success, 0, size = 10)
        assertEquals(listOf(0, 2), page.rows.map { it.index })
        assertEquals(listOf("a", "c"), page.rows.map { it.item.stem })
    }

    @Test fun `pages slice in order and the tail page is short`() {
        val items = (0 until 25).map { item("IMG_%02d".format(it), ItemStatus.SUCCESS) }
        val first = reportPage(items, ReportFilter.All, 0, size = 10)
        val second = reportPage(items, ReportFilter.All, 1, size = 10)
        val third = reportPage(items, ReportFilter.All, 2, size = 10)
        assertEquals(3, first.pages)
        assertEquals(listOf("IMG_00", "IMG_09"), listOf(first.rows.first().item.stem, first.rows.last().item.stem))
        assertEquals(listOf("IMG_10", "IMG_19"), listOf(second.rows.first().item.stem, second.rows.last().item.stem))
        assertEquals(5, third.rows.size)
        assertEquals("IMG_24", third.rows.last().item.stem)
        assertEquals(2, third.page)
    }

    @Test fun `an out-of-range page is clamped instead of rendering blank`() {
        val items = (0 until 25).map { item("IMG_%02d".format(it), ItemStatus.SUCCESS) }
        // Shrinking the filter (or finishing a batch) can leave the stored page beyond the end.
        val over = reportPage(items, ReportFilter.All, 99, size = 10)
        assertEquals(2, over.page)
        assertTrue(over.rows.isNotEmpty())
        assertEquals(0, reportPage(items, ReportFilter.All, -3, size = 10).page)
    }

    @Test fun `a filter matching nothing is still one empty page`() {
        val onlyFailures = listOf(item("x", ItemStatus.FAILED, FailureReason.NOT_MP4))
        val page = reportPage(onlyFailures, ReportFilter.Success, 0, size = 10)
        assertEquals(0, page.matched)
        assertEquals(1, page.pages)
        assertEquals(0, page.page)
        assertTrue(page.rows.isEmpty())
    }

    @Test fun `an empty batch is one empty page`() {
        val page = reportPage(emptyList(), ReportFilter.All, 0, size = 10)
        assertEquals(1, page.pages)
        assertTrue(page.rows.isEmpty())
    }

    /** A zero/negative page size would loop forever or divide to a nonsense page count. */
    @Test fun `a degenerate page size still terminates`() {
        val page = reportPage(mixed(), ReportFilter.All, 0, size = 0)
        assertEquals(mixed().size, page.pages)
        assertEquals(1, page.rows.size)
    }
}
