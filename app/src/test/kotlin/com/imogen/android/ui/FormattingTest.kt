package com.imogen.android.ui

import com.imogen.android.backup.isoInstant
import com.imogen.android.ui.timeline.columnsFor
import com.imogen.android.ui.viewer.formatBytes
import com.imogen.android.ui.viewer.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
