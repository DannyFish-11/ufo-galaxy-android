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

        hooks.recordConnection(ScenarioOutcomeStatus.PASSED)
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
        hooks.recordLocalRuntimeBehavior(RuntimeStartResult.Success)
        hooks.recordDiagnostics(ScenarioOutcomeStatus.PASSED)
        hooks.recordReconnectRecovery(ScenarioOutcomeStatus.PASSED)
        hooks.recordOwnershipTransfer(ScenarioOutcomeStatus.PASSED)
        hooks.recordMesh(ScenarioOutcomeStatus.PASSED)

        val snapshot = hooks.buildSnapshot(nowMs = 1234L)

        assertTrue(snapshot.isDualRuntimeRegressionReady)
        assertTrue(snapshot.e2eReport.isRuntimeClosedEvidence)
        assertTrue(snapshot.e2eReport.hasCanonicalRoundTripHooks)
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            snapshot.flowOutcomes[AndroidCrossRepoRegressionFlow.CONNECTION]
        )
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
            snapshot.flowOutcomes[AndroidCrossRepoRegressionFlow.LOCAL_RUNTIME]
        )
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            snapshot.flowOutcomes[AndroidCrossRepoRegressionFlow.DIAGNOSTICS]
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
        hooks.recordOwnershipTransfer(ScenarioOutcomeStatus.PASSED)

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
        hooks.recordConnection(ScenarioOutcomeStatus.PASSED)
        hooks.recordDeviceRegisterSent()
        hooks.recordCapabilityReportSent()
        hooks.recordLocalRuntimeBehavior(RuntimeStartResult.Degraded("planner_only"))
        hooks.recordDiagnostics(ScenarioOutcomeStatus.PASSED)

        val snapshot = hooks.buildSnapshot(nowMs = 42L)
        val wire = snapshot.toWireMap()

        assertEquals("1.0", wire["schema_version"])
        assertTrue(wire.containsKey("flow_outcomes"))
        assertTrue(wire.containsKey("dual_repo_e2e"))
    }

    @Test
    fun `wire map exposes stage chain reason and mesh lifecycle state`() {
        val hooks = AndroidCrossRepoRegressionRuntimeHooks(
            deviceId = "device-pr11b",
            participantId = "participant-pr11b",
            verificationKind = RealDeviceVerificationKind.REAL_DEVICE
        )
        hooks.recordConnection(ScenarioOutcomeStatus.FAILED, "ws_disconnected")
        hooks.recordMeshLifecycle(
            AndroidMeshLifecycleEmissionChain.onLeave(
                state = AndroidMeshLifecycleEmissionChain.onResult(
                    state = AndroidMeshLifecycleEmissionChain.onJoin(
                        state = AndroidMeshLifecycleEmissionChain.create(
                            meshId = "mesh-pr11b",
                            taskId = "task-pr11b"
                        ),
                        emitted = true
                    ),
                    emitted = false
                ),
                emitted = false,
                reason = "error"
            )
        )

        val wire = hooks.buildSnapshot(nowMs = 77L).toWireMap()
        val flowReasons = wire["flow_reasons"] as Map<*, *>
        val stageChain = wire["stage_chain"] as Map<*, *>
        val connectionStage = stageChain["connection"] as Map<*, *>
        val meshStage = stageChain["mesh_lifecycle"] as Map<*, *>
        val meshLifecycleState = wire["mesh_lifecycle_state"] as Map<*, *>

        assertEquals("ws_disconnected", flowReasons["connection"])
        assertEquals("failed", connectionStage["status"])
        assertEquals("failed", meshStage["status"])
        assertEquals("error", (meshStage["observed_reasons"] as Map<*, *>)["mesh_leave"])
        assertEquals("leave_attempted", meshLifecycleState["mesh_lifecycle_phase"])
        assertEquals(false, meshLifecycleState["mesh_leave_emitted"])
    }

    @Test
    fun `failing local runtime and diagnostics keep snapshot not ready`() {
        val hooks = AndroidCrossRepoRegressionRuntimeHooks(
            deviceId = "device-pr11b",
            participantId = "participant-pr11b",
            verificationKind = RealDeviceVerificationKind.REAL_DEVICE
        )
        hooks.recordLocalRuntimeBehavior(
            RuntimeStartResult.Failure(
                stage = RuntimeStartResult.StartStage.HEALTH_CHECK,
                message = "planner unavailable"
            )
        )
        hooks.recordDiagnostics(
            status = ScenarioOutcomeStatus.FAILED,
            reason = "runtime_diagnostics_send_failed"
        )

        val snapshot = hooks.buildSnapshot(nowMs = 99L)
        assertEquals(
            ScenarioOutcomeStatus.FAILED,
            snapshot.flowOutcomes[AndroidCrossRepoRegressionFlow.LOCAL_RUNTIME]
        )
        assertEquals(
            ScenarioOutcomeStatus.FAILED,
            snapshot.flowOutcomes[AndroidCrossRepoRegressionFlow.DIAGNOSTICS]
        )
        assertFalse(snapshot.isDualRuntimeRegressionReady)
    }
}
