package com.imogen.android.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        assertEquals(noticeText(notice), noticeDetail(notice))
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
    fun `what did get through survives the server that did not`() {
        val notice = PassNotice.Failed(
            FailureReason.SignedOut,
            listOf("family.example.org"),
            listOf(to("photos.example.com", 400), to("family.example.org", 0)),
        )

        val detail = noticeDetail(notice)
        // Expanding must not lose the half that needs acting on: `bigText` replaces the
        // collapsed line, it does not follow it.
        assertTrue(detail.startsWith(noticeText(notice)))
        assertTrue(detail.endsWith("photos.example.com · 400 backed up"))
    }

    @Test
    fun `losing access to the camera roll is about the phone, not a server`() {
        val notice = PassNotice.Failed(FailureReason.MediaAccess, emptyList())

        assertTrue(noticeText(notice).contains("photographs"))
        // The collapsed line truncates at about thirty-five characters, so a failure with
        // nothing to add still needs somewhere its own sentence can be read in full.
        assertEquals(noticeText(notice), noticeDetail(notice))
    }

    @Test
    fun `the same verdict twice over is the same verdict`() {
        val signedOut = PassNotice.Failed(FailureReason.SignedOut, listOf("family.example.org"))

        assertEquals(verdictKey(signedOut), verdictKey(signedOut.copy(sent = listOf(to("a", 3)))))
        assertEquals(
            verdictKey(finishedNotice(listOf(to("photos.example.com", 484)))!!),
            verdictKey(finishedNotice(listOf(to("photos.example.com", 490)))!!),
        )
    }

    @Test
    fun `finishing and failing are not`() {
        assertNotEquals(
            verdictKey(finishedNotice(listOf(to("photos.example.com", 484)))!!),
            verdictKey(PassNotice.Failed(FailureReason.SignedOut, listOf("photos.example.com"))),
        )
        assertNotEquals(
            verdictKey(PassNotice.Failed(FailureReason.SignedOut, listOf("photos.example.com"))),
            verdictKey(PassNotice.Failed(FailureReason.SignedOut, listOf("family.example.org"))),
        )
    }

    @Test
    fun `two accounts on one server are told apart`() {
        assertEquals(
            listOf("photos.example.com · jim@example.org", "photos.example.com · ada@example.org"),
            distinctLabels(
                listOf("photos.example.com", "photos.example.com"),
                listOf("jim@example.org", "ada@example.org"),
            ),
        )
    }

    @Test
    fun `an unambiguous address is left alone`() {
        assertEquals(
            listOf("photos.example.com", "family.example.org"),
            distinctLabels(
                listOf("photos.example.com", "family.example.org"),
                listOf("jim@example.org", "jim@example.org"),
            ),
        )
    }

    // A verdict already in the shade outliving the condition that produced it is the whole
    // of #45. A retrying pass never ends, so it cannot post a verdict of its own — all it
    // can do is retire the parts of the standing one it has disproved.

    @Test
    fun `an upload that got through disproves that account's sign-out`() {
        val stopped = PassNotice.Failed(FailureReason.SignedOut, listOf("photos.example.com"))

        val disproved = disprovedByRetry(listOf(to("photos.example.com", 12, 40)))

        assertTrue(retiredBy(verdictClaims(stopped), disproved))
    }

    @Test
    fun `a pass that sent nothing disproves no sign-out`() {
        val stopped = PassNotice.Failed(FailureReason.SignedOut, listOf("photos.example.com"))

        // The server was unreachable on the first file. Being unable to reach a server is
        // not evidence that it still knows us.
        val disproved = disprovedByRetry(listOf(to("photos.example.com", 0, 40)))

        assertFalse(retiredBy(verdictClaims(stopped), disproved))
    }

    @Test
    fun `one server signed in does not retire a verdict that blames two`() {
        val stopped = PassNotice.Failed(
            FailureReason.SignedOut,
            listOf("photos.example.com", "family.example.org"),
        )

        val disproved = disprovedByRetry(
            listOf(to("photos.example.com", 3, 40), to("family.example.org", 0, 12)),
        )

        // Still true of one of them, and it is still the thing to do something about.
        assertFalse(retiredBy(verdictClaims(stopped), disproved))
    }

    @Test
    fun `a pass that reached the upload loop disproves a media-access failure`() {
        val stopped = PassNotice.Failed(FailureReason.MediaAccess, emptyList())

        assertTrue(retiredBy(verdictClaims(stopped), disprovedByRetry(emptyList())))
    }

    @Test
    fun `a failure with no reason is not disproved by anything`() {
        val stopped = PassNotice.Failed(FailureReason.Unknown, emptyList())

        val disproved = disprovedByRetry(listOf(to("photos.example.com", 9, 9)))

        assertFalse(retiredBy(verdictClaims(stopped), disproved))
    }

    @Test
    fun `a sign-out that names no server is not retired by guesswork`() {
        val stopped = PassNotice.Failed(FailureReason.SignedOut, emptyList())

        val disproved = disprovedByRetry(listOf(to("photos.example.com", 9, 9)))

        assertFalse(retiredBy(verdictClaims(stopped), disproved))
    }

    @Test
    fun `a finished verdict is nobody's to retire`() {
        val finished = finishedNotice(listOf(to("photos.example.com", 484)))!!

        val disproved = disprovedByRetry(listOf(to("photos.example.com", 9, 9)))

        assertFalse(retiredBy(verdictClaims(finished), disproved))
    }

    @Test
    fun `a failure with no reason still says the pass gave up`() {
        assertTrue(noticeText(PassNotice.Failed(FailureReason.Unknown, emptyList())).isNotEmpty())
    }
}
