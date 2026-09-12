package com.imogen.android.backup

import com.imogen.sdk.ImogenException

/** What one upload attempt means for the file, and so for the pass. */
enum class UploadOutcome {
    Uploaded,

    /** A refusal the server will keep making. Recorded against the file. */
    Rejected,

    /** The server or the network is having a bad day. The file is blameless. */
    Unavailable,

    /**
     * The grant is gone, or this token is not accepted. No file is at fault, and no amount
     * of retrying reaches a server that will not have us — only signing in again does.
     */
    Unauthorized,
}

/**
 * Which of those a failed upload was.
 *
 * Auth is asked about first and separately, because it is the one failure that is about the
 * account rather than the file. Folding it in with the other non-retryable statuses is what
 * let a revoked grant write "Authentication required" against a hundred photographs, spend
 * three attempts on each, and then skip them for ever — with nothing anywhere saying the
 * account had been signed out.
 */
fun outcomeOf(error: ImogenException): UploadOutcome = when {
    error.isAuthError -> UploadOutcome.Unauthorized
    // Status 0 is the SDK's "never reached the server at all".
    error.isRetryable || error.status == 0 -> UploadOutcome.Unavailable
    else -> UploadOutcome.Rejected
}
