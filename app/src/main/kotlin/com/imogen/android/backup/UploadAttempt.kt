package com.imogen.android.backup

import com.imogen.android.data.Account
import com.imogen.sdk.ImogenException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * One file to one account, and what the attempt leaves behind in the ledger.
 *
 * A plain function rather than a method on [BackupWorker], because which failures belong
 * against a photograph and which do not is the decision the whole backup turns on, and a
 * `CoroutineWorker` cannot be driven from a unit test.
 *
 * [send] does the sending and answers with the server's id for the asset.
 */
suspend fun recordUpload(
    ledger: UploadDao,
    account: Account,
    item: LocalMedia,
    send: suspend () -> String,
): UploadOutcome {
    val existing = ledger.failuresFor(account.backupKey).firstOrNull {
        it.deviceAssetId == item.deviceAssetId
    }

    return try {
        val assetId = send()
        ledger.put(
            UploadRecord(
                backupKey = account.backupKey,
                deviceAssetId = item.deviceAssetId,
                assetId = assetId,
                uploadedAt = System.currentTimeMillis(),
            ),
        )
        UploadOutcome.Uploaded
    } catch (cancelled: CancellationException) {
        // Rethrown before the ledger is touched. `CancellationException` extends
        // `Exception` in Kotlin, so without this arm the broad one below catches
        // WorkManager stopping the pass and files it against the photograph: doze or a
        // lost network three times mid-upload would spend all three attempts and drop the
        // file out of every future pass. Being stopped is not a verdict on anything.
        throw cancelled
    } catch (error: ImogenException) {
        // Only a rejection the server will keep making — a file type it will not take, a
        // quota that is full — belongs against this file. Anything transient is the
        // server's problem and anything about the account is the account's, and neither
        // must spend a photograph's attempts or its place in the backup.
        val outcome = outcomeOf(error)
        if (outcome == UploadOutcome.Rejected) {
            ledger.put(failureRecord(account, item, existing, describe(error)))
        }
        outcome
    } catch (error: Exception) {
        ledger.put(failureRecord(account, item, existing, error.message ?: error.toString()))
        // A file MediaStore will not hand over does not become readable by waiting.
        if (error is IOException && item.path == null) {
            UploadOutcome.Rejected
        } else {
            UploadOutcome.Unavailable
        }
    }
}

/**
 * The server names the offending fields in `details`; the sentence on its own says only
 * that something was wrong. A ledger full of "the request did not match what this endpoint
 * expects" identifies nothing, which is how every upload came to be failing without
 * anybody being able to say why.
 */
private fun describe(error: ImogenException): String {
    val fields = error.details.orEmpty()
        .entries
        .joinToString("; ") { (field, messages) -> "$field: ${messages.joinToString(", ")}" }
    return if (fields.isEmpty()) error.message else "${error.message} ($fields)"
}

private fun failureRecord(
    account: Account,
    item: LocalMedia,
    existing: UploadRecord?,
    message: String?,
) = UploadRecord(
    backupKey = account.backupKey,
    deviceAssetId = item.deviceAssetId,
    assetId = null,
    uploadedAt = System.currentTimeMillis(),
    attempts = (existing?.attempts ?: 0) + 1,
    lastError = message,
    displayName = item.displayName,
)
