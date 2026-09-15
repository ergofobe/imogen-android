package com.imogen.android.backup

import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.workDataOf

/** Why a pass is sitting in the queue rather than running. */
enum class WaitingReason { Network, Wifi, Charging, Soon }

/** Why a pass gave up. */
enum class FailureReason { MediaAccess, SignedOut, Unknown }

/**
 * What a pass that gave up hands back to WorkManager.
 *
 * Never `Result.failure()`, whatever the reason. For a `PeriodicWorkRequest` failure is
 * terminal: WorkManager cancels the schedule, and the six-hourly backup then never runs
 * again until somebody happens to foreground the app and `BackupScheduler.sync` revives
 * it. One Room hiccup overnight was enough, and the shade said "it will try again".
 *
 * Lives beside [PassState.of] because the two are the same mapping in opposite
 * directions: what a halted pass writes into its output, and what the screen reads back.
 */
fun verdictFor(reason: FailureReason): ListenableWorker.Result = when (reason) {
    // Something went wrong in here rather than out there, and may well not next time.
    // This is the branch whose notice has always promised another attempt.
    FailureReason.Unknown -> ListenableWorker.Result.retry()

    // Waiting on a person — granting access, signing in again. Backing off does not reach
    // them, and neither notice promises it will: the pass ends, says why in its output so
    // the screen can report it, and leaves the schedule standing for the next one.
    FailureReason.MediaAccess -> halted(BackupWorker.REASON_MEDIA_ACCESS)
    FailureReason.SignedOut -> halted(BackupWorker.REASON_SIGNED_OUT)
}

private fun halted(code: String): ListenableWorker.Result =
    ListenableWorker.Result.success(workDataOf(BackupWorker.RESULT_REASON to code))

/**
 * What the pass as a whole is doing, as opposed to how far each destination has got.
 *
 * A pass is one worker covering every account, so waiting and failing are properties of
 * the pass. Only the counts are per account.
 */
sealed interface PassState {
    data object Idle : PassState
    data object Scanning : PassState
    data object Running : PassState
    data class Waiting(val reason: WaitingReason) : PassState
    data class Failed(val reason: FailureReason) : PassState

    companion object {
        fun of(signals: PassSignals): PassState = when (signals.state) {
            WorkInfo.State.RUNNING ->
                if (signals.scanning) Scanning else Running

            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                Waiting(signals.waitingReason())

            // A pass that ran to its end and still could not do its job says so in its
            // output rather than in its state — see [verdictFor]. With no reason there,
            // it simply worked, and the counts say the rest.
            WorkInfo.State.SUCCEEDED -> reasonOf(signals.failureReason)?.let(::Failed) ?: Idle

            // Only reached now when the worker died in a way it could not catch, which is
            // also the one case that really has taken the schedule down with it.
            WorkInfo.State.FAILED ->
                Failed(reasonOf(signals.failureReason) ?: FailureReason.Unknown)

            // Cancelled, or never run at all.
            WorkInfo.State.CANCELLED, null -> Idle
        }

        private fun reasonOf(code: String?): FailureReason? = when (code) {
            null -> null
            BackupWorker.REASON_MEDIA_ACCESS -> FailureReason.MediaAccess
            BackupWorker.REASON_SIGNED_OUT -> FailureReason.SignedOut
            else -> FailureReason.Unknown
        }
    }
}

/**
 * Everything the answer depends on, gathered where it can be read rather than consulted
 * from inside the decision. Keeps `of` above testable on the JVM.
 */
data class PassSignals(
    val state: WorkInfo.State?,
    val scanning: Boolean,
    val unmeteredOnly: Boolean,
    val whileChargingOnly: Boolean,
    val onUnmetered: Boolean,
    val connected: Boolean,
    val charging: Boolean,
    val failureReason: String?,
)

/**
 * Most specific first. Naming the Wi-Fi preference while the phone has no network at all
 * sends somebody hunting for a network that is not the problem.
 */
private fun PassSignals.waitingReason(): WaitingReason = when {
    !connected -> WaitingReason.Network
    unmeteredOnly && !onUnmetered -> WaitingReason.Wifi
    whileChargingOnly && !charging -> WaitingReason.Charging
    else -> WaitingReason.Soon
}

/** How far one destination has got, and how much is already there. */
data class AccountProgress(
    /** `Account.backupKey`, so a row survives the account being signed out and back in. */
    val backupKey: String,
    /** Files sent to this account during the pass now running, if one is. */
    val completed: Int,
    /** Files this account is owed by the pass now running, if one is. */
    val total: Int,
    /** Everything ever backed up to this account, from the ledger. */
    val backedUp: Int,
    val lastCompletedAt: Long?,
    val filename: String? = null,
) {
    val isActive: Boolean get() = total > 0 && completed < total
}

/** The whole screen's worth of answer. */
data class BackupStatus(
    val pass: PassState,
    val accounts: List<AccountProgress>,
)

/** One scheduled request, reduced to what choosing between them depends on. */
data class WorkFacet(
    val state: WorkInfo.State,
    val oneShot: Boolean,
    /** The reason code on a pass that ran to its end and gave up, if it did. */
    val haltReason: String? = null,
)

/**
 * Which request the screen should speak for, or none.
 *
 * The periodic request is ENQUEUED for every minute of the six hours between passes, so
 * treating "enqueued" as "waiting" without asking which request it belongs to would leave
 * the screen permanently claiming to be waiting for something. Only a one-shot — something
 * actually asked for — is a wait worth reporting.
 */
fun chooseReported(facets: List<WorkFacet>): WorkFacet? =
    facets.firstOrNull { it.state == WorkInfo.State.RUNNING }
        ?: facets.firstOrNull {
            it.oneShot && (it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED)
        }
        ?: facets.firstOrNull { it.state == WorkInfo.State.FAILED }
        // Last, because anything still moving is better news than why the last one gave
        // up. Asked at all because a pass that gave up now succeeds — it has to, or it
        // takes the periodic schedule with it — so the verdict is in the output and not
        // in the state.
        ?: facets.firstOrNull { it.haltReason != null }
