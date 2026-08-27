package com.imogen.android.ui

import com.imogen.android.ui.timeline.TimelineIndex
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
