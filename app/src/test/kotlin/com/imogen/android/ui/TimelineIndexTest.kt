package com.imogen.android.ui

import com.imogen.android.ui.timeline.TimelineIndex
import com.imogen.android.ui.timeline.TimelineLayout
import com.imogen.android.ui.timeline.TimelineMetrics
import com.imogen.android.ui.timeline.dayBounds
import com.imogen.sdk.TimelineBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineIndexTest {

    // Three photographs on the 27th, one on the 26th, two on the 25th.
    private val index = TimelineIndex(
        listOf(
            TimelineBucket("2026-08-27", 3),
            TimelineBucket("2026-08-26", 1),
            TimelineBucket("2026-08-25", 2),
        ),
    )

    @Test
    fun `every day costs a heading plus its photographs`() {
        assertEquals((1 + 3) + (1 + 1) + (1 + 2), index.entryCount)
        assertEquals(6, index.photoCount)
    }

    @Test
    fun `headings sit where each day starts and nowhere else`() {
        val headers = (0 until index.entryCount).filter(index::isHeader)

        assertEquals(listOf(0, 4, 6), headers)
    }

    @Test
    fun `an entry knows which day it belongs to`() {
        assertEquals(0, index.bucketOf(0))
        assertEquals(0, index.bucketOf(3))
        assertEquals(1, index.bucketOf(4))
        assertEquals(1, index.bucketOf(5))
        assertEquals(2, index.bucketOf(6))
        assertEquals(2, index.bucketOf(8))
    }

    @Test
    fun `a photograph knows its place within its day`() {
        assertEquals(0, index.offsetInBucket(1))
        assertEquals(2, index.offsetInBucket(3))
        assertEquals(0, index.offsetInBucket(5))
        assertEquals(1, index.offsetInBucket(8))
    }

    @Test
    fun `a day can be jumped to without touching any photograph`() {
        assertEquals(0, index.entryOfBucket(0))
        assertEquals(4, index.entryOfBucket(1))
        assertEquals(6, index.entryOfBucket(2))
        assertEquals("2026-08-25", index.dateOf(index.entryOfBucket(2)))
    }

    @Test
    fun `the scrubber lands inside the list at both extremes`() {
        assertEquals(0, index.entryAtFraction(0f))
        assertEquals(index.entryCount - 1, index.entryAtFraction(1f))
        assertTrue(index.entryAtFraction(0.5f) in 0 until index.entryCount)
        // Out of range input is somebody's finger leaving the track, not a bug.
        assertEquals(0, index.entryAtFraction(-2f))
        assertEquals(index.entryCount - 1, index.entryAtFraction(9f))
    }

    @Test
    fun `trashing shortens the day rather than refetching the whole shape`() {
        val after = index.withoutPhotos(mapOf("2026-08-27" to 1))

        assertEquals(5, after.photoCount)
        assertEquals(2, after.buckets.first().count)
        assertEquals(3, after.buckets.size)
    }

    @Test
    fun `emptying a day removes the day, heading and all`() {
        val after = index.withoutPhotos(mapOf("2026-08-26" to 1))

        assertEquals(listOf("2026-08-27", "2026-08-25"), after.buckets.map { it.date })
        assertEquals(index.entryCount - 2, after.entryCount)
    }

    @Test
    fun `an empty library has nothing to scroll`() {
        val empty = TimelineIndex(emptyList())

        assertEquals(0, empty.entryCount)
        assertTrue(empty.isEmpty)
        assertEquals(0, empty.entryAtFraction(0.5f))
    }

    @Test
    fun `a single enormous day still indexes correctly`() {
        val wedding = TimelineIndex(listOf(TimelineBucket("2026-06-13", 4_000)))

        assertEquals(4_001, wedding.entryCount)
        assertTrue(wedding.isHeader(0))
        assertFalse(wedding.isHeader(4_000))
        assertEquals(3_999, wedding.offsetInBucket(4_000))
        assertEquals(0, wedding.bucketOf(4_000))
    }

    @Test
    fun `a day is fetched as the whole UTC day, both ends included`() {
        assertEquals(
            "2026-08-27T00:00:00.000Z" to "2026-08-27T23:59:59.999Z",
            dayBounds("2026-08-27"),
        )
    }
}

class TimelineLayoutTest {

    private val metrics = TimelineMetrics(
        columns = 3, rowHeight = 100f, headerHeight = 40f, viewportHeight = 0f,
    )

    private fun layout(vararg buckets: Pair<String, Long>) = TimelineLayout(
        TimelineIndex(buckets.map { TimelineBucket(it.first, it.second) }),
        metrics,
    )

    @Test
    fun `a day is a heading plus however many rows its count needs`() {
        assertEquals(140f, layout("2026-08-27" to 1L).totalHeight)
        assertEquals(140f, layout("2026-08-27" to 3L).totalHeight)
        assertEquals(240f, layout("2026-08-27" to 4L).totalHeight)
    }

    @Test
    fun `days stack, and tops are monotonic`() {
        val stacked = layout("2026-08-27" to 3L, "2026-08-26" to 1L, "2026-08-25" to 7L)

        assertEquals(0f, stacked.topOfDay(0))
        assertEquals(140f, stacked.topOfDay(1))
        assertEquals(280f, stacked.topOfDay(2))
        assertEquals(620f, stacked.totalHeight)
    }

    /**
     * The bug this exists to fix.
     *
     * Driving a scrubber from a photograph's position in the list makes a one-photograph
     * day and a twenty-five-photograph day nearly adjacent to the thumb while being nine
     * rows apart on screen. The thumb then jumps as the content scrolls smoothly, which is
     * what "janky" was.
     */
    @Test
    fun `the rail measures the same thing the grid does`() {
        val uneven = layout("2026-08-27" to 90L, "2020-01-01" to 5L, "2019-01-01" to 5L)

        // 90 photos over 3 columns is 30 rows: 40 + 3000. Each small day is 40 + 200.
        assertEquals(3520f, uneven.totalHeight)
        // By photograph count that boundary would sit at 0.9; by height it is at 0.86.
        assertEquals(3040f / 3520f, uneven.fractionOfDay(1), 0.0001f)
    }

    @Test
    fun `dragging the rail and reading it back agree`() {
        val stacked = layout("2026-08-27" to 30L, "2026-08-26" to 1L, "2026-08-25" to 12L)

        for (day in 0..2) {
            assertEquals(day, stacked.dayAtFraction(stacked.fractionOfDay(day)))
        }
    }

    @Test
    fun `the thumb reaches the bottom because the last screenful is not scrolled past`() {
        val onScreen = TimelineLayout(
            TimelineIndex(listOf(TimelineBucket("2026-08-27", 30))),
            metrics.copy(viewportHeight = 500f),
        )

        assertEquals(540f, onScreen.scrollableHeight)
        assertEquals(0, onScreen.dayAtFraction(1f))
    }

    @Test
    fun `a viewport taller than the library does not divide by zero`() {
        val tiny = TimelineLayout(
            TimelineIndex(listOf(TimelineBucket("2026-08-27", 1))),
            metrics.copy(viewportHeight = 9_000f),
        )

        assertEquals(1f, tiny.scrollableHeight)
        assertEquals(0f, tiny.fractionOfDay(0))
    }

    @Test
    fun `years are marked where their photographs are, not where their dates are`() {
        val years = layout(
            "2026-08-27" to 90L, "2026-01-01" to 3L, "2020-06-01" to 3L, "2019-06-01" to 3L,
        )

        val marks = years.yearMarks()
        assertEquals(listOf(2026, 2020, 2019), marks.map { it.year })
        assertEquals(0f, marks[0].fraction, 0.0001f)
        assertTrue(marks[1].fraction > 0.8f)
    }

    @Test
    fun `labels that would overlap are dropped, and the first is always kept`() {
        val crowded = layout(*(0..19).map { "${2026 - it}-06-01" to 1L }.toTypedArray())

        val thinned = crowded.yearMarks(minimumGapPx = 44f, railHeightPx = 400f)

        assertEquals(2026, thinned.first().year)
        assertTrue(thinned.size < 20)
        thinned.zipWithNext { earlier, later ->
            assertTrue((later.fraction - earlier.fraction) * 400f >= 44f)
        }
    }

    @Test
    fun `an empty library has no height and no marks`() {
        val empty = layout()

        assertEquals(0f, empty.totalHeight)
        assertEquals(0, empty.dayAtFraction(0.5f))
        assertTrue(empty.yearMarks().isEmpty())
    }
}
