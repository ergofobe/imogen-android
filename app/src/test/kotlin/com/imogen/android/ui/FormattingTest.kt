package com.imogen.android.ui

import com.imogen.android.backup.isoInstant
import com.imogen.android.ui.timeline.columnsFor
import com.imogen.android.ui.timeline.timelineDayLabel
import com.imogen.android.ui.viewer.formatBytes
import com.imogen.android.ui.viewer.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class GridTest {

    @Test
    fun `a phone gets three or four columns, a tablet more`() {
        assertTrue(columnsFor(360) in 3..4)
        assertTrue(columnsFor(800) >= 6)
    }

    @Test
    fun `a very narrow window never drops below three, or a grid stops being one`() {
        assertEquals(3, columnsFor(120))
    }

    @Test
    fun `a very wide window is capped, so photographs do not become stamps`() {
        assertEquals(10, columnsFor(4000))
    }
}

class FormattingTest {

    @Test
    fun `bytes are shown in the unit somebody would say out loud`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 kB", formatBytes(1024))
        assertEquals("2.5 MB", formatBytes((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `durations are minutes and seconds, zero-padded`() {
        assertEquals("0:07", formatDuration(7.0))
        assertEquals("1:05", formatDuration(65.4))
        assertEquals("12:00", formatDuration(719.6))
    }

    @Test
    fun `capture times go on the wire as UTC ISO-8601, whatever the phone's timezone is`() {
        // 2021-01-01T00:00:00Z
        assertEquals("2021-01-01T00:00:00.000Z", isoInstant(1_609_459_200_000))
    }
}

class DayHeadingTest {

    /**
     * Late in the UTC day, so any timezone west of Greenwich is already on the day
     * before — which is the condition that produces the bug below.
     */
    private val lateOnTheTwentySeventh = Date(1_724_800_000_000)

    /**
     * The server groups the timeline by UTC calendar date, so a bucket key is a UTC day.
     * Reading one against a local "today" files this evening's photographs under a date
     * that will not be today for another several hours.
     */
    @Test
    fun `a day heading is the server's day, not the reader's day`() {
        assertEquals("Today", timelineDayLabel("2024-08-27", lateOnTheTwentySeventh))
        assertEquals("Yesterday", timelineDayLabel("2024-08-26", lateOnTheTwentySeventh))
        assertTrue(timelineDayLabel("2024-07-22", lateOnTheTwentySeventh).contains("22"))
    }

    @Test
    fun `this year drops the year and other years keep it`() {
        assertFalse(timelineDayLabel("2024-07-22", lateOnTheTwentySeventh).contains("2024"))
        assertTrue(timelineDayLabel("2019-07-22", lateOnTheTwentySeventh).contains("2019"))
    }

    @Test
    fun `a full timestamp is read as the day it falls on`() {
        assertEquals(
            timelineDayLabel("2024-07-22", lateOnTheTwentySeventh),
            timelineDayLabel("2024-07-22T23:59:00.000Z", lateOnTheTwentySeventh),
        )
    }

    @Test
    fun `something that is not a date is passed through rather than guessed at`() {
        assertEquals("not a date", timelineDayLabel("not a date"))
    }
}
