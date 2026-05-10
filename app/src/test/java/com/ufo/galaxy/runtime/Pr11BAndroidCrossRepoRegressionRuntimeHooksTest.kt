package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr11BAndroidCrossRepoRegressionRuntimeHooksTest {

    @Test
    fun `all required flows and runtime stages produce dual-runtime-ready snapshot`() {
        val hooks = AndroidCrossRepoRegressionRuntimeHooks(
            deviceId = "device-pr11b",
            participantId = "participant-pr11b",
            verificationKind = RealDeviceVerificationKind.REAL_DEVICE
        )

        hooks.recordDeviceRegisterSent()
        hooks.recordCapabilityReportSent()
        hooks.recordExecutionReceipt(
            taskId = "task-pr11b",
            traceId = "trace-pr11b",
            runtimeSessionId = "runtime-pr11b",
            status = ScenarioOutcomeStatus.PASSED
        )
        hooks.recordExecutionSignal(
            taskId = "task-pr11b",
            traceId = "trace-pr11b",
            runtimeSessionId = "runtime-pr11b",
            signalKind = "execution_started",
            status = ScenarioOutcomeStatus.PASSED
        )
        hooks.recordGoalResultFeedback(
            taskId = "task-pr11b",
            traceId = "trace-pr11b",
            runtimeSessionId = "runtime-pr11b",
            status = ScenarioOutcomeStatus.PASSED
        )
        hooks.recordReconnectRecovery(ScenarioOutcomeStatus.PASSED)
        hooks.recordTakeover(ScenarioOutcomeStatus.PASSED)
        hooks.recordMesh(ScenarioOutcomeStatus.PASSED)

        val snapshot = hooks.buildSnapshot(nowMs = 1234L)

        assertTrue(snapshot.isDualRuntimeRegressionReady)
        assertTrue(snapshot.e2eReport.isRuntimeClosedEvidence)
        assertTrue(snapshot.e2eReport.hasCanonicalRoundTripHooks)
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            snapshot.flowOutcomes[AndroidCrossRepoRegressionFlow.CAPABILITY]
        )
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            snapshot.flowOutcomes[AndroidCrossRepoRegressionFlow.EXECUTION]
        )
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            snapshot.flowOutcomes[AndroidCrossRepoRegressionFlow.RECOVERY]
        )
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            snapshot.flowOutcomes[AndroidCrossRepoRegressionFlow.TAKEOVER]
        )
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            snapshot.flowOutcomes[AndroidCrossRepoRegressionFlow.MESH]
        )
    }

    @Test
    fun `failed flow outcome is sticky until next regression run`() {
        val hooks = AndroidCrossRepoRegressionRuntimeHooks(
            deviceId = "device-pr11b",
            participantId = "participant-pr11b",
            verificationKind = RealDeviceVerificationKind.REAL_DEVICE
        )

        hooks.recordTakeover(ScenarioOutcomeStatus.FAILED, "timeout")
        hooks.recordTakeover(ScenarioOutcomeStatus.PASSED)

        val snapshot = hooks.buildSnapshot(nowMs = 5678L)
        assertEquals(
            ScenarioOutcomeStatus.FAILED,
            snapshot.flowOutcomes[AndroidCrossRepoRegressionFlow.TAKEOVER]
        )
        assertFalse(snapshot.isDualRuntimeRegressionReady)
    }

    @Test
    fun `wire map contains flow outcomes and nested e2e report`() {
        val hooks = AndroidCrossRepoRegressionRuntimeHooks(
            deviceId = "device-pr11b",
            participantId = "participant-pr11b",
            verificationKind = RealDeviceVerificationKind.EMULATOR
        )
        hooks.recordDeviceRegisterSent()
        hooks.recordCapabilityReportSent()

        val snapshot = hooks.buildSnapshot(nowMs = 42L)
        val wire = snapshot.toWireMap()

        assertEquals("1.0", wire["schema_version"])
        assertTrue(wire.containsKey("flow_outcomes"))
        assertTrue(wire.containsKey("dual_repo_e2e"))
    }
}
