package com.imogen.android.backup

/** Whether a failed file is still in the running. */
enum class FailureState {
    /** Attempts remain; the next pass will pick it up on its own. */
    WillRetry,

    /**
     * Attempts exhausted. `BackupWorker` folds these into the settled set, so nothing
     * will try again and nothing will mention it — which is how a client-side bug turned
     * into a hundred photographs quietly missing from a backup.
     */
    GivenUp,
}

/** One file that did not make it to one account. */
data class FailedUpload(
    /** `Account.backupKey`. The server is readable from it even once the account is gone. */
    val backupKey: String,
    val deviceAssetId: String,
    /** Null for rows written before the ledger recorded names. */
    val displayName: String?,
    val attempts: Int,
    val lastError: String?,
) {
    val state: FailureState
        get() = if (attempts >= MAX_UPLOAD_ATTEMPTS) FailureState.GivenUp else FailureState.WillRetry

    /** The device id is a poor name, and still better than a blank row. */
    val name: String get() = displayName ?: deviceAssetId
}

/**
 * The failures a backup pass can still reach.
 *
 * The ledger keeps every row it has ever written, across every key; a pass visits only
 * `AccountBook.backingUpTo`. So rows left behind by an account that has since been signed
 * out — the default, because `forgetBackups` is off and those rows are what lets signing
 * back in resume instead of re-uploading the roll — name a destination nothing owns any
 * more. Nothing retries them, "Try again" cannot reach them, and they sat on the screen
 * inflating the count for ever. Rows an old version keyed on a device-local account id
 * are the same case: `adoptStableKeys` only knows accounts still in the book, so those
 * are unreachable too, and render the bare UUID where the server's name goes.
 *
 * [backingUpTo] rather than every account in the book, because an account still signed in
 * with its backup switched off is visited by no pass either, and its rows would carry the
 * same "Try again" that does nothing.
 *
 * Hidden rather than deleted, in every case. The destination may well come back — signed
 * into again, or simply switched back on — and its rows are correct the moment it does.
 */
fun reachable(failures: List<FailedUpload>, backingUpTo: Set<String>): List<FailedUpload> =
    failures.filter { it.backupKey in backingUpTo }

data class FailureSummary(val willRetry: Int, val givenUp: Int) {
    val total: Int get() = willRetry + givenUp
}

fun summarise(failures: List<FailedUpload>): FailureSummary = FailureSummary(
    willRetry = failures.count { it.state == FailureState.WillRetry },
    givenUp = failures.count { it.state == FailureState.GivenUp },
)

/**
 * The same file, put back in the running.
 *
 * Zeroing the count is the whole operation: `BackupWorker` decides what to skip from
 * `attempts`, so anything less leaves the file settled and the retry button a lie. The
 * error goes with it rather than being left to describe a failure that is no longer the
 * current answer.
 */
fun retried(failure: FailedUpload): FailedUpload =
    failure.copy(attempts = 0, lastError = null)
