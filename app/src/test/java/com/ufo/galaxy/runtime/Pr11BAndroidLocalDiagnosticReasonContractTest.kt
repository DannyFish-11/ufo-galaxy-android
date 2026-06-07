package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr11BAndroidLocalDiagnosticReasonContractTest {

    @Test
    fun `classify maps runtime capability recovery takeover mesh domains`() {
        assertEquals(
            AndroidLocalDiagnosticReasonContract.DiagnosticDomain.RUNTIME,
            AndroidLocalDiagnosticReasonContract.classify(
                errorType = "ws_error",
                nodeName = "ws_runtime"
            ).domain
        )
        assertEquals(
            AndroidLocalDiagnosticReasonContract.DiagnosticDomain.CAPABILITY,
            AndroidLocalDiagnosticReasonContract.classify(
                errorType = "bridge_handoff_failed",
                nodeName = "bridge_handoff"
            ).domain
        )
        assertEquals(
            AndroidLocalDiagnosticReasonContract.DiagnosticDomain.RECOVERY,
            AndroidLocalDiagnosticReasonContract.classify(
                errorType = AndroidContinuityIntegration.SEMANTIC_REJECT_STALE_IDENTITY,
                nodeName = "goal_execution_continuity_gate"
            ).domain
        )
        assertEquals(
            AndroidLocalDiagnosticReasonContract.DiagnosticDomain.TAKEOVER,
            AndroidLocalDiagnosticReasonContract.classify(
                errorType = "takeover_execution_failed",
                nodeName = "takeover_execution"
            ).domain
        )
        assertEquals(
            AndroidLocalDiagnosticReasonContract.DiagnosticDomain.MESH_PARTICIPATION,
            AndroidLocalDiagnosticReasonContract.classify(
                errorType = "parallel_subtask_timeout",
                nodeName = "parallel_subtask_pipeline"
            ).domain
        )
    }

    @Test
    fun `classify emits stable reason and local cause`() {
        val classification = AndroidLocalDiagnosticReasonContract.classify(
            errorType = "takeover_session_rejected",
            nodeName = "takeover_session_gate"
        )

        assertEquals("1", classification.schemaVersion)
        assertEquals("takeover_session_rejected", classification.reason.wireValue)
        assertEquals("takeover_session_gate", classification.localCause)
    }

    @Test
    fun `unknown error maps to unknown domain and reason`() {
        val classification = AndroidLocalDiagnosticReasonContract.classify(
            errorType = "totally_new_error",
            nodeName = ""
        )

        assertEquals(AndroidLocalDiagnosticReasonContract.DiagnosticReason.UNKNOWN, classification.reason)
        assertEquals(AndroidLocalDiagnosticReasonContract.DiagnosticDomain.UNKNOWN, classification.domain)
        assertEquals("unspecified", classification.localCause)
    }

    @Test
    fun `diagnostic reason invariants all pass`() {
        assertTrue(
            AndroidLocalDiagnosticReasonContract.DIAGNOSTIC_REASON_INVARIANTS.values.all { it }
        )
    }

    @Test
    fun `stabilization baseline registers local diagnostic reason contract`() {
        assertTrue(
            StabilizationBaseline.isRegistered("android-local-diagnostic-reason-contract")
        )
        val entry = StabilizationBaseline.forId("android-local-diagnostic-reason-contract")
        assertNotNull(entry)
        assertEquals(
            "com.ufo.galaxy.runtime.AndroidLocalDiagnosticReasonContract",
            entry?.packagePath
        )
    }
}
