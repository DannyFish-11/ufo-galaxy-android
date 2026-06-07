package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidOperatorActionGovernanceContractTest {

    private fun baselineContext() =
        AndroidOperatorActionGovernanceContract.EligibilityContext(
            runtimeStateWire = "active",
            dispatchEligible = true,
            hasAttachedSession = true,
            reconnectRecoveryStateWire = "recovered",
            activeTaskId = "task-1",
            hasActiveTakeover = true,
            crossDeviceEnabled = true,
            operatorSuspendedOrIsolated = false
        )

    @Test
    fun `maps wire action kinds to taxonomy`() {
        assertEquals(
            AndroidOperatorActionGovernanceContract.ActionKind.TRIGGER_RECOVERY,
            AndroidOperatorActionGovernanceContract.ActionKind.fromWire("trigger_recovery")
        )
    }

    @Test
    fun `rejects retry delegated execution when no active delegated flow`() {
        val decision = AndroidOperatorActionGovernanceContract.evaluateEligibility(
            action = AndroidOperatorActionGovernanceContract.ActionKind.RETRY_DELEGATED_EXECUTION,
            context = baselineContext().copy(activeTaskId = null, hasActiveTakeover = false),
            taskId = null
        )
        assertTrue(decision is AndroidOperatorActionGovernanceContract.EligibilityDecision.Rejected)
    }

    @Test
    fun `rejects closure actions without task id`() {
        val decision = AndroidOperatorActionGovernanceContract.evaluateEligibility(
            action = AndroidOperatorActionGovernanceContract.ActionKind.FINALIZE_CLOSURE,
            context = baselineContext(),
            taskId = null
        )
        assertTrue(decision is AndroidOperatorActionGovernanceContract.EligibilityDecision.Rejected)
    }

    @Test
    fun `accepts suspend isolate action even with no active session`() {
        val decision = AndroidOperatorActionGovernanceContract.evaluateEligibility(
            action = AndroidOperatorActionGovernanceContract.ActionKind.SUSPEND_ISOLATE_DEVICE,
            context = baselineContext().copy(hasAttachedSession = false, crossDeviceEnabled = false),
            taskId = null
        )
        assertTrue(decision is AndroidOperatorActionGovernanceContract.EligibilityDecision.Accepted)
    }
}
