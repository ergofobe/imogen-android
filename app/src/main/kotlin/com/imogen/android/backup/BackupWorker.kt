package com.imogen.android.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.imogen.android.ImogenApplication
import com.imogen.android.R
import com.imogen.android.data.Account
import com.imogen.android.data.Session
import com.imogen.sdk.AssetUploadMetadata
import com.imogen.sdk.ImogenException
import com.imogen.sdk.UploadOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Copying the camera roll to every account that asked for it.
 *
 * One worker for all of them rather than one each: the expensive part is reading a
 * two-gigabyte video off storage, and doing that once per destination instead of once
 * would make a second account cost twice as much battery as the first.
 *
 * Failures are per file and per account. A video the server rejects should not stop the
 * four thousand photographs behind it, and a server that is simply down should not burn
 * through the retry budget of files that were never tried.
 */
class BackupWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ImogenApplication
        val preferences = app.backupSettings.current()
        if (!preferences.enabled) return Result.success()

        val destinations = app.accountStore.current().backingUpTo
        if (destinations.isEmpty()) return Result.success()

        val ledger = BackupLedger.get(applicationContext).uploads()
        val media = withContext(Dispatchers.IO) {
            MediaScanner(applicationContext).scan(
                includeVideos = preferences.includeVideos,
                cameraOnly = preferences.cameraOnly,
            )
        }
        if (media.isEmpty()) return Result.success()

        // Oldest first. A backup that starts today and works backwards leaves somebody
        // watching the count go up with no idea whether it will ever reach the bottom.
        val ordered = media.sortedBy { it.takenAtMillis }

        // Sets, because this is asked once per file per account: a list membership test
        // in there turns a four-thousand-photo roll into sixteen million comparisons.
        val outstanding = destinations.associate { account ->
            val settled = ledger.doneFor(account.id).toMutableSet()
            ledger.failuresFor(account.id)
                .filter { it.attempts >= MAX_UPLOAD_ATTEMPTS }
                .forEach { settled += it.deviceAssetId }
            account.id to ordered.mapNotNull { it.deviceAssetId.takeIf { id -> id !in settled } }
                .toSet()
        }

        val total = outstanding.values.sumOf { it.size }
        if (total == 0) return Result.success()

        var completed = 0
        var retryable = false

        for (item in ordered) {
            for (account in destinations) {
                if (item.deviceAssetId !in outstanding.getValue(account.id)) continue
                if (isStopped) return Result.retry()

                setProgress(
                    workDataOf(
                        PROGRESS_COMPLETED to completed,
                        PROGRESS_TOTAL to total,
                        PROGRESS_FILENAME to item.displayName,
                    ),
                )
                setForegroundSafely(completed, total)

                when (val outcome = upload(app.sessions.sessionFor(account), item, account)) {
                    Outcome.Uploaded -> completed += 1
                    Outcome.Rejected -> completed += 1
                    // The server or the network is having a bad day. Stop pushing at it
                    // and let WorkManager's backoff decide when to come back.
                    Outcome.Unavailable -> {
                        retryable = true
                        break
                    }
                }
            }
            if (retryable) break
        }

        return if (retryable) Result.retry() else Result.success()
    }

    private enum class Outcome { Uploaded, Rejected, Unavailable }

    private suspend fun upload(session: Session, item: LocalMedia, account: Account): Outcome {
        val ledger = BackupLedger.get(applicationContext).uploads()
        val existing = ledger.failuresFor(account.id).firstOrNull {
            it.deviceAssetId == item.deviceAssetId
        }

        var scratch: File? = null
        return try {
            val file = item.path?.let(::File)?.takeIf { it.canRead() }
                ?: copyToCache(item).also { scratch = it }

            val result = session.client.assets.upload(
                file,
                UploadOptions(
                    metadata = AssetUploadMetadata(
                        deviceAssetId = item.deviceAssetId,
                        capturedAt = isoInstant(item.takenAtMillis),
                        filename = item.displayName,
                    ),
                ),
            )

            ledger.put(
                UploadRecord(
                    accountId = account.id,
                    deviceAssetId = item.deviceAssetId,
                    assetId = result.asset.id,
                    uploadedAt = System.currentTimeMillis(),
                ),
            )
            Outcome.Uploaded
        } catch (error: ImogenException) {
            // A rejection the server will keep making — a file type it will not take, a
            // quota that is full — is recorded against this file. Anything transient is
            // the server's problem, not this file's, and must not spend its attempts.
            if (error.isRetryable || error.status == 0) {
                Outcome.Unavailable
            } else {
                ledger.put(failure(account, item, existing, error.message))
                Outcome.Rejected
            }
        } catch (error: Exception) {
            val unreadable = error is java.io.IOException && item.path == null
            ledger.put(failure(account, item, existing, error.message ?: error.toString()))
            if (unreadable) Outcome.Rejected else Outcome.Unavailable
        } finally {
            scratch?.delete()
        }
    }

    private fun failure(
        account: Account,
        item: LocalMedia,
        existing: UploadRecord?,
        message: String?,
    ) = UploadRecord(
        accountId = account.id,
        deviceAssetId = item.deviceAssetId,
        assetId = null,
        uploadedAt = System.currentTimeMillis(),
        attempts = (existing?.attempts ?: 0) + 1,
        lastError = message,
    )

    /**
     * For media whose real path MediaStore will not give up — anything on a volume the
     * app cannot read directly. Costs a copy, which is why it is the fallback.
     */
    private suspend fun copyToCache(item: LocalMedia): File = withContext(Dispatchers.IO) {
        val target = File(applicationContext.cacheDir, "upload/${item.displayName}").apply {
            parentFile?.mkdirs()
        }
        applicationContext.contentResolver.openInputStream(item.uri).use { input ->
            requireNotNull(input) { "could not open ${item.uri}" }
            target.outputStream().use(input::copyTo)
        }
        target
    }

    private suspend fun setForegroundSafely(completed: Int, total: Int) {
        // Foreground promotion is refused in more situations with every release, and a
        // refusal must not take the upload down with it — the work is still worth doing
        // quietly.
        runCatching { setForeground(notification(completed, total)) }
    }

    private fun notification(completed: Int, total: Int): ForegroundInfo {
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    applicationContext.getString(R.string.backup_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle(applicationContext.getString(R.string.backup_running))
            .setContentText("$completed / $total")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(total, completed, false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val PROGRESS_COMPLETED = "completed"
        const val PROGRESS_TOTAL = "total"
        const val PROGRESS_FILENAME = "filename"

        private const val CHANNEL = "backup"
        private const val NOTIFICATION_ID = 4201
    }
}
