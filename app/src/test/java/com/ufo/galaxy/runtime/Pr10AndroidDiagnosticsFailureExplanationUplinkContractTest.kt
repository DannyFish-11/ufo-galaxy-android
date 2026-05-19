package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr10AndroidDiagnosticsFailureExplanationUplinkContractTest {

    @Test
    fun `goal result uses artifact boundary with summary projection`() {
        val snapshot = AndroidDiagnosticsFailureExplanationUplinkContract.forGoalResult(
            resultSummary = "task done",
            result = "task done",
            details = null,
            error = null
        )
        assertEquals(
            AndroidDiagnosticsFailureExplanationUplinkContract.UplinkSemanticBoundaryClass.ARTIFACT_RESULT_SIGNAL,
            snapshot.uplinkSemanticBoundaryClass
        )
        assertEquals(
            AndroidDiagnosticsFailureExplanationUplinkContract.OperatorProjectionClass.OPERATOR_VISIBLE_SUMMARY,
            snapshot.operatorProjectionClass
        )
    }

    @Test
    fun `goal result explanation stays projection only`() {
        val snapshot = AndroidDiagnosticsFailureExplanationUplinkContract.forGoalResult(
            resultSummary = "failed",
            result = null,
            details = "executor timeout",
            error = "timeout"
        )
        assertEquals(
            AndroidDiagnosticsFailureExplanationUplinkContract.UplinkSemanticBoundaryClass.ARTIFACT_RESULT_SIGNAL,
            snapshot.uplinkSemanticBoundaryClass
        )
        assertEquals(
            AndroidDiagnosticsFailureExplanationUplinkContract.OperatorProjectionClass.POST_RUN_EXPLANATION,
            snapshot.operatorProjectionClass
        )
    }

    @Test
    fun `terminal authority execution event maps to authority runtime signal`() {
        val snapshot = AndroidDiagnosticsFailureExplanationUplinkContract.forExecutionEvent(
            lifecycleTerminalPhase = true,
            resultSignalClass = AndroidResultUplinkBoundaryContract.ResultSignalClass.AUTHORITY_RESULT
        )
        assertEquals(
            AndroidDiagnosticsFailureExplanationUplinkContract.UplinkSemanticBoundaryClass.AUTHORITY_RUNTIME_SIGNAL,
            snapshot.uplinkSemanticBoundaryClass
        )
        assertEquals(
            AndroidDiagnosticsFailureExplanationUplinkContract.OperatorProjectionClass.NONE,
            snapshot.operatorProjectionClass
        )
    }

    @Test
    fun `non authority execution event maps to diagnostics signal`() {
        val snapshot = AndroidDiagnosticsFailureExplanationUplinkContract.forExecutionEvent(
            lifecycleTerminalPhase = false,
            resultSignalClass = AndroidResultUplinkBoundaryContract.ResultSignalClass.DIAGNOSTICS_INFORMATIONAL
        )
        assertEquals(
            AndroidDiagnosticsFailureExplanationUplinkContract.UplinkSemanticBoundaryClass.FAILURE_DIAGNOSTICS_SIGNAL,
            snapshot.uplinkSemanticBoundaryClass
        )
    }

    @Test
    fun `device state snapshot is diagnostics signal with no projection`() {
        val snapshot = AndroidDiagnosticsFailureExplanationUplinkContract.forDeviceStateSnapshot()
        assertEquals(
            AndroidDiagnosticsFailureExplanationUplinkContract.UplinkSemanticBoundaryClass.FAILURE_DIAGNOSTICS_SIGNAL,
            snapshot.uplinkSemanticBoundaryClass
        )
        assertEquals(
            AndroidDiagnosticsFailureExplanationUplinkContract.OperatorProjectionClass.NONE,
            snapshot.operatorProjectionClass
        )
    }

    @Test
    fun `diagnostics payload is failure diagnostics plus local interpretation`() {
        val snapshot = AndroidDiagnosticsFailureExplanationUplinkContract.forDiagnosticsPayload()
        assertEquals(
            AndroidDiagnosticsFailureExplanationUplinkContract.UplinkSemanticBoundaryClass.FAILURE_DIAGNOSTICS_SIGNAL,
            snapshot.uplinkSemanticBoundaryClass
        )
        assertEquals(
            AndroidDiagnosticsFailureExplanationUplinkContract.OperatorProjectionClass.LOCAL_INTERPRETATION,
            snapshot.operatorProjectionClass
        )
    }

    @Test
    fun `wire map contains schema and semantic boundary keys`() {
        val wire = AndroidDiagnosticsFailureExplanationUplinkContract.forDiagnosticsPayload().toWireMap()
        assertEquals(
            AndroidDiagnosticsFailureExplanationUplinkContract.SCHEMA_VERSION,
            wire[AndroidDiagnosticsFailureExplanationUplinkContract.KEY_DIAGNOSTICS_FAILURE_EXPLANATION_SCHEMA_VERSION]
        )
        assertTrue(wire.containsKey(AndroidDiagnosticsFailureExplanationUplinkContract.KEY_UPLINK_SEMANTIC_BOUNDARY_CLASS))
        assertTrue(wire.containsKey(AndroidDiagnosticsFailureExplanationUplinkContract.KEY_OPERATOR_PROJECTION_CLASS))
    }

    @Test
    fun `v2 path map covers every semantic boundary class`() {
        val map = AndroidDiagnosticsFailureExplanationUplinkContract.V2_CONSUMPTION_PATH_MAP
        assertEquals(
            AndroidDiagnosticsFailureExplanationUplinkContract.UplinkSemanticBoundaryClass.values().size,
            map.size
        )
        AndroidDiagnosticsFailureExplanationUplinkContract.UplinkSemanticBoundaryClass.values().forEach {
            assertTrue(map[it].isNullOrBlank().not())
        }
    }

    @Test
    fun `stabilization baseline registers diagnostics failure explanation contract`() {
        val entry = StabilizationBaseline
            .forId("android-diagnostics-failure-explanation-uplink-contract")
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry?.stability
        )
        assertEquals(
            StabilizationBaseline.ExtensionGuidance.EXTEND,
            entry?.extensionGuidance
        )
    }
}
