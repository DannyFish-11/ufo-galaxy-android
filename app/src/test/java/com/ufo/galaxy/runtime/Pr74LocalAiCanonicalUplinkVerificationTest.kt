package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr74LocalAiCanonicalUplinkVerificationTest {

    @Test
    fun `recordLocalAiCanonicalUplinkFlow keeps local inference authority bounded and chain verified`() {
        val harness = DualRepoE2EVerificationHarness(
            deviceId = "device-pr74",
            participantId = "participant-pr74",
            verificationKind = RealDeviceVerificationKind.REAL_DEVICE
        )

        harness.recordLocalAiCanonicalUplinkFlow(
            activationInputs = LocalIntelligenceActivationPolicy.ActivationInputs(
                formalLifecycleState = FormalParticipantLifecycleState.READY,
                inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE,
                plannerModelLoaded = true,
                groundingModelLoaded = true,
                runtimeHostActive = true
            ),
            managerState = LocalInferenceRuntimeManager.ManagerState.Running(
                RuntimeHealthSnapshot(
                    plannerHealth = RuntimeHealthSnapshot.ComponentHealth.HEALTHY,
                    groundingHealth = RuntimeHealthSnapshot.ComponentHealth.HEALTHY
                )
            ),
            localResultProduced = true,
            runtimeDiagnosticsVisible = true,
            resultUplinkStatus = ScenarioOutcomeStatus.PASSED,
            v2CanonicalIngressStatus = ScenarioOutcomeStatus.PASSED,
            v2TruthReconciliationStatus = ScenarioOutcomeStatus.PASSED,
            v2ClosureOutwardStatus = ScenarioOutcomeStatus.PASSED
        )

        val report = harness.buildReport()
        val evidence = report.localAiCanonicalFlowEvidence

        assertNotNull(evidence)
        assertTrue(report.isLocalAiCanonicalChainVerified)
        assertEquals(
            LocalAiResultAuthorityBoundaryClass.LOCAL_RUNTIME_CONTRIBUTION_ONLY,
            evidence!!.authorityBoundaryClass
        )
        assertEquals(
            ScenarioOutcomeStatus.PASSED,
            evidence.stepOutcomes[LocalAiCanonicalVerificationStep.V2_TRUTH_RECONCILIATION]
        )
    }

    @Test
    fun `local ai chain is not verified when canonical closure stage fails`() {
        val harness = DualRepoE2EVerificationHarness(
            deviceId = "device-pr74",
            participantId = "participant-pr74",
            verificationKind = RealDeviceVerificationKind.REAL_DEVICE
        )

        harness.recordLocalAiCanonicalUplinkFlow(
            activationInputs = LocalIntelligenceActivationPolicy.ActivationInputs(
                formalLifecycleState = FormalParticipantLifecycleState.READY,
                inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE,
                plannerModelLoaded = true,
                groundingModelLoaded = true,
                runtimeHostActive = true
            ),
            managerState = LocalInferenceRuntimeManager.ManagerState.Running(RuntimeHealthSnapshot.unknown()),
            localResultProduced = true,
            runtimeDiagnosticsVisible = true,
            resultUplinkStatus = ScenarioOutcomeStatus.PASSED,
            v2CanonicalIngressStatus = ScenarioOutcomeStatus.PASSED,
            v2TruthReconciliationStatus = ScenarioOutcomeStatus.PASSED,
            v2ClosureOutwardStatus = ScenarioOutcomeStatus.FAILED
        )

        val report = harness.buildReport()
        assertFalse(report.isLocalAiCanonicalChainVerified)
    }

    @Test
    fun `clearAllOutcomes clears local ai canonical evidence`() {
        val harness = DualRepoE2EVerificationHarness(
            deviceId = "device-pr74",
            participantId = "participant-pr74",
            verificationKind = RealDeviceVerificationKind.REAL_DEVICE
        )
        harness.recordLocalAiCanonicalUplinkFlow(
            activationInputs = LocalIntelligenceActivationPolicy.ActivationInputs(
                formalLifecycleState = FormalParticipantLifecycleState.READY,
                inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE,
                plannerModelLoaded = true,
                groundingModelLoaded = true,
                runtimeHostActive = true
            ),
            managerState = LocalInferenceRuntimeManager.ManagerState.Running(RuntimeHealthSnapshot.unknown()),
            localResultProduced = true,
            runtimeDiagnosticsVisible = true,
            resultUplinkStatus = ScenarioOutcomeStatus.PASSED,
            v2CanonicalIngressStatus = ScenarioOutcomeStatus.PASSED,
            v2TruthReconciliationStatus = ScenarioOutcomeStatus.PASSED,
            v2ClosureOutwardStatus = ScenarioOutcomeStatus.PASSED
        )

        harness.clearAllOutcomes()
        assertFalse(harness.buildReport().isLocalAiCanonicalChainVerified)
    }

    @Test
    fun `toWireMap includes canonical uplink map and local ai canonical flow`() {
        val harness = DualRepoE2EVerificationHarness(
            deviceId = "device-pr74",
            participantId = "participant-pr74",
            verificationKind = RealDeviceVerificationKind.REAL_DEVICE
        )
        harness.recordLocalAiCanonicalUplinkFlow(
            activationInputs = LocalIntelligenceActivationPolicy.ActivationInputs(
                formalLifecycleState = FormalParticipantLifecycleState.READY,
                inferenceStatus = LocalIntelligenceCapabilityStatus.ACTIVE,
                plannerModelLoaded = true,
                groundingModelLoaded = true,
                runtimeHostActive = true
            ),
            managerState = LocalInferenceRuntimeManager.ManagerState.Running(RuntimeHealthSnapshot.unknown()),
            localResultProduced = true,
            runtimeDiagnosticsVisible = true,
            resultUplinkStatus = ScenarioOutcomeStatus.PASSED,
            v2CanonicalIngressStatus = ScenarioOutcomeStatus.PASSED,
            v2TruthReconciliationStatus = ScenarioOutcomeStatus.PASSED,
            v2ClosureOutwardStatus = ScenarioOutcomeStatus.PASSED
        )

        val wire = harness.buildReport().toWireMap()
        val canonicalPathMap = wire["canonical_uplink_path_map"] as Map<*, *>
        val localAiFlow = wire["local_ai_canonical_flow"] as Map<*, *>

        assertEquals("core/unified_result_ingress.py", canonicalPathMap["v2_result_ingress"])
        assertEquals("core/v2_android_truth_ssot.py", canonicalPathMap["v2_android_truth_ssot"])
        assertEquals(true, localAiFlow["is_canonical_chain_verified"])
        assertEquals(
            "local_runtime_contribution_only",
            localAiFlow["authority_boundary_class"]
        )
    }
}
