package com.imogen.android.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * When backup runs.
 *
 * Two requests, not one. The periodic one is the safety net — it catches whatever the app
 * missed while it was closed, and Android will not run it more than every fifteen minutes
 * however politely it is asked. The one-shot is what actually makes new photographs appear
 * quickly: it is enqueued when the app comes to the front, when the settings change, and
 * when somebody presses the button.
 */
object BackupScheduler {

    private const val PERIODIC = "backup-periodic"
    const val ONE_SHOT = "backup-now"

    fun sync(context: Context, preferences: BackupPreferences) {
        val manager = WorkManager.getInstance(context)
        if (!preferences.enabled) {
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelUniqueWork(ONE_SHOT)
            return
        }

        manager.enqueueUniquePeriodicWork(
            PERIODIC,
            // The constraints are part of the request, so a change to them has to replace
            // it. Keeping the existing one would leave yesterday's rules in force.
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints(preferences))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** Runs now, subject to the same constraints. Ignored if a pass is already going. */
    fun runNow(context: Context, preferences: BackupPreferences) {
        if (!preferences.enabled) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(constraints(preferences))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }

    /** What the settings screen shows while a pass is running. */
    fun progress(context: Context): Flow<BackupProgress?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(ONE_SHOT)
            .map { infos ->
                val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    ?: return@map null
                val total = running.progress.getInt(BackupWorker.PROGRESS_TOTAL, 0)
                if (total == 0) return@map null
                BackupProgress(
                    completed = running.progress.getInt(BackupWorker.PROGRESS_COMPLETED, 0),
                    total = total,
                    filename = running.progress.getString(BackupWorker.PROGRESS_FILENAME),
                )
            }

    private fun constraints(preferences: BackupPreferences) = Constraints.Builder()
        .setRequiredNetworkType(
            if (preferences.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
        )
        .setRequiresCharging(preferences.whileChargingOnly)
        // Not while storage is critically low: the fallback path copies a file to the
        // cache before sending it, and a phone with no room would fail every one.
        .setRequiresStorageNotLow(true)
        .build()
}

data class BackupProgress(val completed: Int, val total: Int, val filename: String?)
