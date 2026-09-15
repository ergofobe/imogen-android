package com.imogen.android.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class FailedUploadsTest {

    private fun failure(
        attempts: Int = 1,
        displayName: String? = "PXL_1.jpg",
        error: String? = "rejected",
    ) = FailedUpload(
        backupKey = "https://photos.example.com|user-7",
        deviceAssetId = "android:external_primary:125",
        displayName = displayName,
        attempts = attempts,
        lastError = error,
    )

    @Test
    fun `a file with attempts left will be tried again`() {
        assertEquals(FailureState.WillRetry, failure(attempts = 1).state)
        assertEquals(FailureState.WillRetry, failure(attempts = MAX_UPLOAD_ATTEMPTS - 1).state)
    }

    @Test
    fun `a file that has spent its attempts has been given up on`() {
        // This is the state that stranded a hundred photographs: the worker folds these
        // into the settled set, so nothing retries them and nothing mentions them again.
        assertEquals(FailureState.GivenUp, failure(attempts = MAX_UPLOAD_ATTEMPTS).state)
    }

    @Test
    fun `an attempt count past the limit is still given up on, not wrapped around`() {
        assertEquals(FailureState.GivenUp, failure(attempts = MAX_UPLOAD_ATTEMPTS + 5).state)
    }

    @Test
    fun `a file named before the ledger recorded names falls back to its device id`() {
        // Rows written before displayName existed have none, and a screen that renders
        // nothing for them would hide exactly the backlog this is for.
        assertEquals("android:external_primary:125", failure(displayName = null).name)
    }

    @Test
    fun `a recorded name is what gets shown`() {
        assertEquals("PXL_1.jpg", failure(displayName = "PXL_1.jpg").name)
    }

    @Test
    fun `nothing failed is a summary of nothing`() {
        val summary = summarise(emptyList())
        assertEquals(0, summary.total)
        assertEquals(0, summary.givenUp)
    }

    @Test
    fun `the summary separates what will be retried from what has been abandoned`() {
        val summary = summarise(
            listOf(
                failure(attempts = 1),
                failure(attempts = 2),
                failure(attempts = MAX_UPLOAD_ATTEMPTS),
                failure(attempts = MAX_UPLOAD_ATTEMPTS),
            ),
        )
        assertEquals(4, summary.total)
        assertEquals(2, summary.willRetry)
        assertEquals(2, summary.givenUp)
    }

    /**
     * `failures()` spans every key the ledger has ever held; a pass only visits the
     * accounts in the book. A row left by an account since signed out can never be
     * retried, and it sat there counting against the backup for ever.
     */
    @Test
    fun `a row belonging to an account that is gone is not listed`() {
        val mine = failure()
        val orphan = failure().copy(backupKey = "https://old.example.com|user-9")

        assertEquals(listOf(mine), reachable(listOf(mine, orphan), setOf(mine.backupKey)))
    }

    @Test
    fun `a row an old version keyed on a device-local id is not listed`() {
        // `adoptStableKeys` only knows accounts still in the book, so one of these left by
        // an account that has gone is unreachable — and renders its raw UUID where the
        // server's name belongs.
        val stale = failure().copy(backupKey = "0f3c1a52-8e44-4e2c-9a0c-1d7f0c3b9a11")

        assertEquals(
            emptyList<FailedUpload>(),
            reachable(listOf(stale), setOf(failure().backupKey)),
        )
    }

    @Test
    fun `a row belonging to an account whose backup is switched off is not listed`() {
        // A pass visits `backingUpTo`, not every account in the book, so these carry the
        // same "Try again" that nothing would honour.
        val paused = failure()

        assertEquals(emptyList<FailedUpload>(), reachable(listOf(paused), emptySet()))
    }

    @Test
    fun `hidden rather than deleted, so signing back in brings the rows back`() {
        val row = failure()

        assertEquals(emptyList<FailedUpload>(), reachable(listOf(row), emptySet()))
        assertEquals(listOf(row), reachable(listOf(row), setOf(row.backupKey)))
    }

    @Test
    fun `retrying resets the attempt count so the worker stops skipping the file`() {
        // The worker decides what to skip from `attempts`, so anything short of zeroing it
        // leaves the file settled and the retry button a lie.
        assertEquals(0, retried(failure(attempts = MAX_UPLOAD_ATTEMPTS)).attempts)
    }

    @Test
    fun `retrying clears the recorded error rather than leaving a stale one`() {
        assertEquals(null, retried(failure(attempts = 3, error = "rejected")).lastError)
    }

    @Test
    fun `retrying keeps the identity of the file it is retrying`() {
        val original = failure(attempts = 3)
        val again = retried(original)
        assertEquals(original.backupKey, again.backupKey)
        assertEquals(original.deviceAssetId, again.deviceAssetId)
        assertEquals(original.displayName, again.displayName)
    }
}
