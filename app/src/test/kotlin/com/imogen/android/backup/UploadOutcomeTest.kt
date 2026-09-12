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
        assertEquals(UploadOutcome.Unauthorized, outcomeOf(error(403)))
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
