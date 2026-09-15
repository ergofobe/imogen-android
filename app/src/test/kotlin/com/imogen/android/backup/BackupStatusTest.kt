package com.imogen.android.backup

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `WorkInfo.State` is an ordinary enum from the WorkManager artifact rather than an
 * android.jar stub, so the whole of this reads on the JVM.
 */
class BackupStatusTest {

    private fun signals(
        state: WorkInfo.State? = null,
        scanning: Boolean = false,
        unmeteredOnly: Boolean = false,
        whileChargingOnly: Boolean = false,
        onUnmetered: Boolean = true,
        connected: Boolean = true,
        charging: Boolean = true,
        failureReason: String? = null,
    ) = PassSignals(
        state = state,
        scanning = scanning,
        unmeteredOnly = unmeteredOnly,
        whileChargingOnly = whileChargingOnly,
        onUnmetered = onUnmetered,
        connected = connected,
        charging = charging,
        failureReason = failureReason,
    )

    @Test
    fun `a pass that has never run is idle`() {
        assertEquals(PassState.Idle, PassState.of(signals(state = null)))
    }

    @Test
    fun `a finished pass is idle, not running`() {
        // The old surface showed nothing here and nothing while waiting, which is what
        // made the two indistinguishable.
        assertEquals(PassState.Idle, PassState.of(signals(state = WorkInfo.State.SUCCEEDED)))
    }

    @Test
    fun `reading the camera roll is its own phase`() {
        // Thousands of files are scanned before the first count exists. Reporting nothing
        // for that whole stretch is what makes the button look dead.
        assertEquals(
            PassState.Scanning,
            PassState.of(signals(state = WorkInfo.State.RUNNING, scanning = true)),
        )
    }

    @Test
    fun `a running pass is running`() {
        assertEquals(
            PassState.Running,
            PassState.of(signals(state = WorkInfo.State.RUNNING, scanning = false)),
        )
    }

    @Test
    fun `waiting on wi-fi says so`() {
        assertEquals(
            PassState.Waiting(WaitingReason.Wifi),
            PassState.of(
                signals(
                    state = WorkInfo.State.ENQUEUED,
                    unmeteredOnly = true,
                    onUnmetered = false,
                ),
            ),
        )
    }

    @Test
    fun `waiting to charge says so`() {
        assertEquals(
            PassState.Waiting(WaitingReason.Charging),
            PassState.of(
                signals(
                    state = WorkInfo.State.ENQUEUED,
                    whileChargingOnly = true,
                    charging = false,
                ),
            ),
        )
    }

    @Test
    fun `no network at all outranks the wi-fi preference`() {
        // "Waiting for Wi-Fi" while in flight mode sends somebody hunting for a network
        // that is not the problem.
        assertEquals(
            PassState.Waiting(WaitingReason.Network),
            PassState.of(
                signals(
                    state = WorkInfo.State.ENQUEUED,
                    unmeteredOnly = true,
                    onUnmetered = false,
                    connected = false,
                ),
            ),
        )
    }

    @Test
    fun `a metered connection is not a wait when wi-fi only is off`() {
        assertEquals(
            PassState.Waiting(WaitingReason.Soon),
            PassState.of(
                signals(
                    state = WorkInfo.State.ENQUEUED,
                    unmeteredOnly = false,
                    onUnmetered = false,
                ),
            ),
        )
    }

    @Test
    fun `enqueued with every constraint met is about to start`() {
        assertEquals(
            PassState.Waiting(WaitingReason.Soon),
            PassState.of(signals(state = WorkInfo.State.ENQUEUED)),
        )
    }

    @Test
    fun `blocked work is waiting, not idle`() {
        assertEquals(
            PassState.Waiting(WaitingReason.Soon),
            PassState.of(signals(state = WorkInfo.State.BLOCKED)),
        )
    }

    @Test
    fun `a pass that could not read the camera roll says which failure it was`() {
        assertEquals(
            PassState.Failed(FailureReason.MediaAccess),
            PassState.of(
                signals(
                    state = WorkInfo.State.FAILED,
                    failureReason = BackupWorker.REASON_MEDIA_ACCESS,
                ),
            ),
        )
    }

    /**
     * Kept apart from [FailureReason.Unknown] because the two want opposite words: one
     * promises another attempt, and this one is the case where no attempt can help.
     */
    @Test
    fun `a pass stopped by a dead grant says the account is signed out`() {
        assertEquals(
            PassState.Failed(FailureReason.SignedOut),
            PassState.of(
                signals(
                    state = WorkInfo.State.FAILED,
                    failureReason = BackupWorker.REASON_SIGNED_OUT,
                ),
            ),
        )
    }

    @Test
    fun `a failure with no reason recorded is still reported`() {
        assertEquals(
            PassState.Failed(FailureReason.Unknown),
            PassState.of(signals(state = WorkInfo.State.FAILED)),
        )
    }

    @Test
    fun `a cancelled pass is idle`() {
        assertEquals(PassState.Idle, PassState.of(signals(state = WorkInfo.State.CANCELLED)))
    }

    @Test
    fun `wi-fi outranks charging when both are unmet`() {
        // It has to be one of them. Wi-Fi, because a phone that is off charge is usually
        // in a hand and about to be put back on one anyway.
        assertEquals(
            PassState.Waiting(WaitingReason.Wifi),
            PassState.of(
                signals(
                    state = WorkInfo.State.ENQUEUED,
                    unmeteredOnly = true,
                    onUnmetered = false,
                    whileChargingOnly = true,
                    charging = false,
                ),
            ),
        )
    }

    // --- which of the two work requests the screen should report on ---

    private fun oneShot(state: WorkInfo.State) = WorkFacet(state, oneShot = true)
    private fun periodic(state: WorkInfo.State) = WorkFacet(state, oneShot = false)

    @Test
    fun `nothing scheduled reports nothing`() {
        assertEquals(null, chooseReported(emptyList()))
    }

    @Test
    fun `the periodic pass sitting between runs is not a wait`() {
        // It is ENQUEUED every minute of the six hours between passes. Reporting that as
        // "waiting" would leave the screen saying so permanently, which is the same lie
        // as saying nothing, only wordier.
        assertEquals(null, chooseReported(listOf(periodic(WorkInfo.State.ENQUEUED))))
    }

    @Test
    fun `a background pass is reported while it runs`() {
        // The old surface watched only the one-shot, so a periodic pass was invisible
        // even mid-upload.
        val facets = listOf(periodic(WorkInfo.State.RUNNING))
        assertEquals(facets[0], chooseReported(facets))
    }

    @Test
    fun `a requested pass that has not started is a wait`() {
        val facets = listOf(oneShot(WorkInfo.State.ENQUEUED), periodic(WorkInfo.State.ENQUEUED))
        assertEquals(facets[0], chooseReported(facets))
    }

    @Test
    fun `running outranks everything`() {
        val facets = listOf(oneShot(WorkInfo.State.FAILED), periodic(WorkInfo.State.RUNNING))
        assertEquals(facets[1], chooseReported(facets))
    }

    @Test
    fun `a finished pass reports nothing, leaving the counts to speak`() {
        val facets = listOf(oneShot(WorkInfo.State.SUCCEEDED), periodic(WorkInfo.State.ENQUEUED))
        assertEquals(null, chooseReported(facets))
    }

    @Test
    fun `a failure is reported over the periodic resting state`() {
        val facets = listOf(oneShot(WorkInfo.State.FAILED), periodic(WorkInfo.State.ENQUEUED))
        assertEquals(facets[0], chooseReported(facets))
    }

    @Test
    fun `a waiting one-shot outranks an earlier failure`() {
        // Something is queued now; what went wrong last time matters less than that.
        val facets = listOf(periodic(WorkInfo.State.FAILED), oneShot(WorkInfo.State.ENQUEUED))
        assertEquals(facets[1], chooseReported(facets))
    }
}
