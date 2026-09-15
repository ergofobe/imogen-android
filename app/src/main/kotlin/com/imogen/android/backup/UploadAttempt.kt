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
        outcomeOf(error).recordedAgainst(ledger, account, item, existing, describe(error))
    } catch (error: Exception) {
        // A file MediaStore will not hand over does not become readable by waiting, so
        // that one really is about the file. Everything else arriving here is the
        // network: the SDK cannot replay a multipart body to retry it, so it rethrows the
        // transport error untouched, and a dropped connection mid-upload turns up as a
        // plain IOException rather than as an ImogenException. Charging that to the
        // photograph spent an attempt per blip — three blips and it was settled out of
        // the backup for good, which is the same hole as the one above by another door.
        val outcome = if (error is IOException && item.path == null) {
            UploadOutcome.Rejected
        } else {
            UploadOutcome.Unavailable
        }
        outcome.recordedAgainst(
            ledger,
            account,
            item,
            existing,
            error.message ?: error.toString(),
        )
    }
}

/**
 * The one rule about what a failure costs a photograph, in one place so the two arms
 * above cannot disagree about it.
 *
 * Only a refusal the server will keep making — a file type it will not take, a quota that
 * is full — may spend an attempt, because it is the only outcome that will not come right
 * on its own. Anything transient is the server's or the network's, and anything about the
 * account is the account's; a hundred photographs once carried "Authentication required"
 * three times each and were then skipped for ever.
 */
private suspend fun UploadOutcome.recordedAgainst(
    ledger: UploadDao,
    account: Account,
    item: LocalMedia,
    existing: UploadRecord?,
    message: String,
): UploadOutcome {
    if (this != UploadOutcome.Rejected) return this
    ledger.put(failureRecord(account, item, existing, message))
    return this
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
