package com.imogen.android.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the shade is told, decided away from any of the machinery that shows it.
 *
 * The bug being fixed is a sentence — a bare "3 / 40" summed over every destination — so
 * the sentence is what these assert.
 */
class BackupNoticeTest {

    private fun to(label: String, completed: Int, total: Int = completed) =
        DestinationProgress(label, completed, total)

    @Test
    fun `one destination is named rather than counted`() {
        val text = progressText(listOf(to("photos.example.com", 3, 40)))
        assertEquals("photos.example.com · 3 of 40", text)
        assertNull(progressDetail(listOf(to("photos.example.com", 3, 40))))
    }

    @Test
    fun `several destinations each get a line of their own`() {
        val destinations = listOf(to("photos.example.com", 3, 40), to("family.example.org", 1, 12))

        assertEquals(
            listOf("photos.example.com · 3 of 40", "family.example.org · 1 of 12"),
            progressDetail(destinations)?.lines(),
        )
        // The collapsed line has room for one thing, and 4 of 52 is at least true of the
        // pass as a whole. The names are one swipe away.
        assertEquals("4 of 52 · 2 servers", progressText(destinations))
    }

    @Test
    fun `a destination with nothing owed is not a row`() {
        val destinations = listOf(to("photos.example.com", 3, 40), to("family.example.org", 0, 0))
        assertEquals("photos.example.com · 3 of 40", progressText(destinations))
        assertNull(progressDetail(destinations))
    }

    @Test
    fun `a pass that sent nothing says nothing`() {
        assertNull(finishedNotice(listOf(to("photos.example.com", 0, 0))))
        assertNull(finishedNotice(emptyList()))
    }

    @Test
    fun `finishing says how many went where`() {
        val notice = finishedNotice(listOf(to("photos.example.com", 484)))!!

        assertEquals("Backup finished", noticeTitle(notice))
        assertEquals("photos.example.com · 484 backed up", noticeText(notice))
        assertNull(noticeDetail(notice))
    }

    @Test
    fun `a destination that received nothing is left out of the finished notice`() {
        val notice = finishedNotice(listOf(to("photos.example.com", 484), to("family.example.org", 0)))!!

        assertEquals("photos.example.com · 484 backed up", noticeText(notice))
        assertFalse(noticeText(notice).contains("family.example.org"))
    }

    @Test
    fun `two destinations that received something both appear`() {
        val notice = finishedNotice(listOf(to("photos.example.com", 484), to("family.example.org", 12)))!!

        assertEquals("496 backed up · 2 servers", noticeText(notice))
        assertEquals(
            listOf("photos.example.com · 484 backed up", "family.example.org · 12 backed up"),
            noticeDetail(notice)?.lines(),
        )
    }

    @Test
    fun `a signed-out destination is named, because signing in again is where to go`() {
        val notice = PassNotice.Failed(FailureReason.SignedOut, listOf("family.example.org"))

        assertEquals("Backup stopped", noticeTitle(notice))
        assertTrue(noticeText(notice).contains("family.example.org"))
    }

    @Test
    fun `losing access to the camera roll is about the phone, not a server`() {
        val notice = PassNotice.Failed(FailureReason.MediaAccess, emptyList())

        assertTrue(noticeText(notice).contains("photographs"))
        assertNull(noticeDetail(notice))
    }

    @Test
    fun `a failure with no reason still says the pass gave up`() {
        assertTrue(noticeText(PassNotice.Failed(FailureReason.Unknown, emptyList())).isNotEmpty())
    }
}
