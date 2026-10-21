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

    private fun many(n: Int) = (0 until n).map { item("IMG_%02d".format(it), ItemStatus.SUCCESS) }

    @Test fun `all keeps every row in report order`() {
        val page = reportPage(mixed(), ReportFilter.All, 0, size = 10)
        assertEquals(listOf("a", "b", "c", "d", "e"), page.rows.map { it.stem })
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
            assertEquals(filter.name, listOf(status), page.rows.map { it.status }.distinct())
            assertEquals(filter.name, page.matched, page.rows.size)
        }
        assertEquals(listOf("b", "e"), reportPage(mixed(), ReportFilter.Skipped, 0, size = 10).rows.map { it.stem })
    }

    @Test fun `pages slice in order and the tail page is short`() {
        val items = many(25)
        val first = reportPage(items, ReportFilter.All, 0, size = 10)
        val second = reportPage(items, ReportFilter.All, 1, size = 10)
        val third = reportPage(items, ReportFilter.All, 2, size = 10)
        assertEquals(3, first.pages)
        assertEquals(listOf("IMG_00", "IMG_09"), listOf(first.rows.first().stem, first.rows.last().stem))
        assertEquals(listOf("IMG_10", "IMG_19"), listOf(second.rows.first().stem, second.rows.last().stem))
        assertEquals(5, third.rows.size)
        assertEquals("IMG_24", third.rows.last().stem)
        assertEquals(2, third.page)
    }

    /** The pager is the only way to see the rest, so pages must partition the list exactly once. */
    @Test fun `consecutive pages neither repeat nor drop a row`() {
        val items = many(25)
        val walked = (0 until 3).flatMap { reportPage(items, ReportFilter.All, it, size = 10).rows }
        assertEquals(items.map { it.stem }, walked.map { it.stem })
    }

    @Test fun `an out-of-range page is clamped instead of rendering blank`() {
        // A batch still appending grows the page count, and dropping a filter shrinks it; either
        // way the stored page can end up past the end, and a blank page is never the right answer.
        val items = many(25)
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
