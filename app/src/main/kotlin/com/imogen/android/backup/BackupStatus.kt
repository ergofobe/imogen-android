package com.imogen.android.backup

import androidx.work.WorkInfo

/** Why a pass is sitting in the queue rather than running. */
enum class WaitingReason { Network, Wifi, Charging, Soon }

/** Why a pass gave up. */
enum class FailureReason { MediaAccess, SignedOut, Unknown }

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

            WorkInfo.State.FAILED -> Failed(
                when (signals.failureReason) {
                    BackupWorker.REASON_MEDIA_ACCESS -> FailureReason.MediaAccess
                    BackupWorker.REASON_SIGNED_OUT -> FailureReason.SignedOut
                    else -> FailureReason.Unknown
                },
            )

            // Succeeded, cancelled, or never run at all. The counts say the rest.
            WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED, null -> Idle
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
    val accountId: String,
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
data class WorkFacet(val state: WorkInfo.State, val oneShot: Boolean)

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
