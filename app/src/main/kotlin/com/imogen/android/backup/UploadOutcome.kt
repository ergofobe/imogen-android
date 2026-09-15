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
 *
 * 401 only, not the SDK's `isAuthError`, which is 401 or 403. A 401 has already been past
 * the SDK's refresh hook, so one arriving here means the grant really is gone. A 403 is a
 * token the server accepted and still will not act on — a scope the grant never had, a
 * resource-bound token pointed at another library. Signing in again mints the same token
 * and earns the same refusal, so answering [Unauthorized] gave advice that cannot work,
 * dropped every other file bound for that server, and wrote nothing down.
 */
fun outcomeOf(error: ImogenException): UploadOutcome = when {
    error.status == 401 -> UploadOutcome.Unauthorized
    // Status 0 is the SDK's "never reached the server at all".
    error.isRetryable || error.status == 0 -> UploadOutcome.Unavailable
    else -> UploadOutcome.Rejected
}
