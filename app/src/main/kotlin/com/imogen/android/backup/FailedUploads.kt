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
    val accountId: String,
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
