package com.imogen.android.backup

import com.imogen.sdk.ImogenException
import org.junit.Assert.assertEquals
import org.junit.Test

class UploadOutcomeTest {

    private fun error(status: Int) = ImogenException(status, "code", "message")

    /**
     * The failure this whole issue is about: a revoked grant answered 401 for every file,
     * and each one was written against the photograph as though the photograph were at
     * fault — three attempts each, then given up on for ever.
     */
    @Test
    fun `an unauthorized upload is nobody's file's fault`() {
        assertEquals(UploadOutcome.Unauthorized, outcomeOf(error(401)))
    }

    /**
     * 403 is not 401. Only a 401 has been past the SDK's refresh hook, so a 403 reaching
     * here is a token that was accepted and still may not do this — a scope the grant
     * never had, a resource-bound token pointed somewhere else. Signing in again produces
     * the same token and the same refusal, so telling somebody to do that is advice that
     * cannot work; and treating it as a dead grant dropped the destination for the rest of
     * the pass without writing anything down, leaving the file to be retried for ever with
     * nothing on the failures screen to say why.
     */
    @Test
    fun `a forbidden upload is a refusal about this file, not a dead grant`() {
        assertEquals(UploadOutcome.Rejected, outcomeOf(error(403)))
    }

    @Test
    fun `a server having a bad day does not spend the file's attempts`() {
        assertEquals(UploadOutcome.Unavailable, outcomeOf(error(500)))
        assertEquals(UploadOutcome.Unavailable, outcomeOf(error(429)))
        // Status 0 is the SDK's "never reached the server at all".
        assertEquals(UploadOutcome.Unavailable, outcomeOf(error(0)))
    }

    /** A refusal the server will keep making belongs against the file. */
    @Test
    fun `a rejection is recorded against the file`() {
        assertEquals(UploadOutcome.Rejected, outcomeOf(error(400)))
        assertEquals(UploadOutcome.Rejected, outcomeOf(error(415)))
        assertEquals(UploadOutcome.Rejected, outcomeOf(error(422)))
    }
}
