package com.imogen.android.backup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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

    /**
     * Everything the backup screen shows, per destination.
     *
     * Both requests are watched, not just the one-shot: a periodic pass uploading in the
     * background used to be invisible here however long it ran. Which of the two speaks
     * is `chooseReported`, because the periodic one is enqueued around the clock and
     * saying so would be permanent noise.
     */
    fun status(
        context: Context,
        accountIds: List<String>,
        preferences: BackupPreferences,
    ): Flow<BackupStatus> {
        val manager = WorkManager.getInstance(context)
        val ledger = BackupLedger.get(context).uploads()

        val reported = combine(
            manager.getWorkInfosForUniqueWorkFlow(ONE_SHOT),
            manager.getWorkInfosForUniqueWorkFlow(PERIODIC),
        ) { oneShot, periodic ->
            val pairs = oneShot.map { WorkFacet(it.state, oneShot = true) to it } +
                periodic.map { WorkFacet(it.state, oneShot = false) to it }
            chooseReported(pairs.map { it.first })
                ?.let { chosen -> pairs.first { it.first == chosen }.second }
        }

        val backedUp: Flow<Map<String, Int>> =
            if (accountIds.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(accountIds.map { id -> ledger.countFor(id).map { id to it } }) { it.toMap() }
            }

        return combine(reported, backedUp, BackupState(context).lastCompleted) { info, counts, lastRun ->
            val progress = info?.progress
            BackupStatus(
                pass = PassState.of(
                    PassSignals(
                        state = info?.state,
                        scanning = progress?.getBoolean(BackupWorker.PROGRESS_SCANNING, false) == true,
                        unmeteredOnly = preferences.unmeteredOnly,
                        whileChargingOnly = preferences.whileChargingOnly,
                        // Read here rather than inside the decision, so the decision stays
                        // arithmetic and testable off a device.
                        onUnmetered = isUnmetered(context),
                        connected = isConnected(context),
                        charging = isCharging(context),
                        failureReason = info?.outputData?.getString(BackupWorker.RESULT_REASON),
                    ),
                ),
                accounts = accountIds.map { id ->
                    val slot = progress?.getStringArray(BackupWorker.PROGRESS_ACCOUNTS)
                        ?.indexOf(id)?.takeIf { it >= 0 }
                    AccountProgress(
                        accountId = id,
                        completed = slot?.let {
                            progress.getIntArray(BackupWorker.PROGRESS_PER_ACCOUNT)?.getOrNull(it)
                        } ?: 0,
                        total = slot?.let {
                            progress.getIntArray(BackupWorker.PROGRESS_TOTALS)?.getOrNull(it)
                        } ?: 0,
                        backedUp = counts[id] ?: 0,
                        lastCompletedAt = lastRun[id],
                        // The filename belongs to whichever destination it is being sent
                        // to; showing it against all of them would be a lie about two.
                        filename = progress?.getString(BackupWorker.PROGRESS_FILENAME)
                            ?.takeIf { progress.getString(BackupWorker.PROGRESS_ACCOUNT) == id },
                    )
                },
            )
        }
    }

    private fun capabilities(context: Context): NetworkCapabilities? =
        context.getSystemService(ConnectivityManager::class.java)
            ?.let { it.getNetworkCapabilities(it.activeNetwork) }

    private fun isConnected(context: Context): Boolean =
        capabilities(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun isUnmetered(context: Context): Boolean =
        capabilities(context)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    private fun isCharging(context: Context): Boolean =
        context.getSystemService(BatteryManager::class.java)?.isCharging == true

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
