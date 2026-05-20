package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.MsgType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr10v2AndroidBoundedSubjectRuntimeContractTest {

    @Test
    fun `formal role set is fixed to six bounded subject roles`() {
        val roles = AndroidBoundedSubjectRuntimeContract.FormalRole.entries.map { it.wireValue }.toSet()
        assertEquals(6, roles.size)
        assertTrue(roles.contains("bounded_relative_subject_runtime"))
        assertTrue(roles.contains("local_runtime_host"))
        assertTrue(roles.contains("local_continuity_holder"))
        assertTrue(roles.contains("local_execution_policy_participant"))
        assertTrue(roles.contains("local_ai_consumer_host"))
        assertTrue(roles.contains("distributed_participant"))
    }

    @Test
    fun `authority topics enforce bounded authority`() {
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.AuthorityVerdict.LOCAL_DECIDES,
            AndroidBoundedSubjectRuntimeContract.classifyAuthority(
                AndroidBoundedSubjectRuntimeContract.AuthorityTopic.LOCAL_CONTINUITY_ATTACH_RESUME
            )
        )
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.AuthorityVerdict.LOCAL_CONTRIBUTES_CENTER_FINALIZES,
            AndroidBoundedSubjectRuntimeContract.classifyAuthority(
                AndroidBoundedSubjectRuntimeContract.AuthorityTopic.EXECUTION_RESULT_PUBLICATION
            )
        )
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.AuthorityVerdict.CENTER_ONLY,
            AndroidBoundedSubjectRuntimeContract.classifyAuthority(
                AndroidBoundedSubjectRuntimeContract.AuthorityTopic.CANONICAL_TRUTH_FINALIZATION
            )
        )
    }

    @Test
    fun `uplink semantic layering maps canonical message types`() {
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.UplinkSemanticLayer.PARTICIPANT_TRUTH_UPLINK,
            AndroidBoundedSubjectRuntimeContract.classifyUplink(MsgType.DEVICE_REGISTER)
        )
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.UplinkSemanticLayer.RUNTIME_STATE_UPLINK,
            AndroidBoundedSubjectRuntimeContract.classifyUplink(MsgType.DEVICE_STATE_SNAPSHOT)
        )
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.UplinkSemanticLayer.EXECUTION_RESULT_UPLINK,
            AndroidBoundedSubjectRuntimeContract.classifyUplink(MsgType.GOAL_EXECUTION_RESULT)
        )
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.UplinkSemanticLayer.DIAGNOSTICS_UPLINK,
            AndroidBoundedSubjectRuntimeContract.classifyUplink(MsgType.DIAGNOSTICS_PAYLOAD)
        )
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.UplinkSemanticLayer.COMPAT_MINIMAL_UPLINK,
            AndroidBoundedSubjectRuntimeContract.classifyUplink(MsgType.RELAY)
        )
    }

    @Test
    fun `android and v2 path maps lock real modules`() {
        assertTrue(
            AndroidBoundedSubjectRuntimeContract.ANDROID_RUNTIME_CHAIN_PATHS.values.any {
                it.endsWith("RuntimeController.kt")
            }
        )
        assertTrue(
            AndroidBoundedSubjectRuntimeContract.ANDROID_RUNTIME_CHAIN_PATHS.values.any {
                it.endsWith("GalaxyConnectionService.kt")
            }
        )
        assertTrue(
            AndroidBoundedSubjectRuntimeContract.ANDROID_RUNTIME_CHAIN_PATHS.values.any {
                it.endsWith("AndroidContinuityIntegration.kt")
            }
        )

        assertTrue(
            AndroidBoundedSubjectRuntimeContract.V2_CANONICAL_GOVERNANCE_PATHS.values.any {
                it.contains("core/command_router.py")
            }
        )
        assertTrue(
            AndroidBoundedSubjectRuntimeContract.V2_CANONICAL_GOVERNANCE_PATHS.values.any {
                it.contains("core/unified_runtime_truth_ingress.py")
            }
        )
    }

    @Test
    fun `local ai consumer flow is fixed on real runtime modules with bounded arbitration`() {
        val stages = AndroidBoundedSubjectRuntimeContract.LOCAL_AI_CONSUMER_FLOW_BOUNDARIES
        assertTrue(stages.any { it.runtimeModule == "RuntimeController" })
        assertTrue(stages.any { it.runtimeModule == "LocalExecutionModeGate" })
        assertTrue(stages.any { it.runtimeModule == "GalaxyConnectionService" })
        assertTrue(stages.any { it.runtimeModule == "AutonomousExecutionPipeline" })
        assertTrue(stages.any { it.runtimeModule == "LocalLoopExecutor" })
        assertTrue(stages.any { it.runtimeModule == "GalaxyWebSocketClient" })
        assertTrue(stages.any { it.runtimeModule == "OfflineTaskQueue" })
        assertTrue(stages.any { it.runtimeModule == "AndroidContinuityIntegration" })

        val uplinkBoundary = stages.first {
            it.stage == AndroidBoundedSubjectRuntimeContract.LocalAiConsumerFlowStage.CANONICAL_UPLINK
        }
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.CanonicalArbitrationBoundary.CENTER_CANONICAL_ARBITRATION,
            uplinkBoundary.arbitrationBoundary
        )
    }

    @Test
    fun `local and canonical visibility boundaries stay explicit`() {
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.LocalVisibleClass.LOCAL_VISIBLE,
            AndroidBoundedSubjectRuntimeContract.classifyLocalSurface(
                AndroidBoundedSubjectRuntimeContract.LocalConsumptionSurface.LOCAL_RUNTIME_SURFACE
            )
        )
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.LocalVisibleClass.RUNTIME_VISIBLE,
            AndroidBoundedSubjectRuntimeContract.classifyLocalSurface(
                AndroidBoundedSubjectRuntimeContract.LocalConsumptionSurface.RUNTIME_VISIBLE_SURFACE
            )
        )
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.LocalVisibleClass.PRODUCT_VISIBLE,
            AndroidBoundedSubjectRuntimeContract.classifyLocalSurface(
                AndroidBoundedSubjectRuntimeContract.LocalConsumptionSurface.PRODUCT_SURFACE
            )
        )
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.LocalVisibleClass.DIAGNOSTICS_VISIBLE,
            AndroidBoundedSubjectRuntimeContract.classifyLocalSurface(
                AndroidBoundedSubjectRuntimeContract.LocalConsumptionSurface.DIAGNOSTICS_SURFACE
            )
        )

        assertEquals(
            AndroidBoundedSubjectRuntimeContract.CanonicalUplinkClass.PARTICIPANT_TRUTH_UPLINK,
            AndroidBoundedSubjectRuntimeContract.classifyCanonicalUplink(MsgType.DEVICE_GOVERNANCE_REPORT)
        )
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.CanonicalUplinkClass.EXECUTION_RESULT_UPLINK,
            AndroidBoundedSubjectRuntimeContract.classifyCanonicalUplink(MsgType.GOAL_EXECUTION_RESULT)
        )
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.CanonicalUplinkClass.CONTINUITY_STATE_UPLINK,
            AndroidBoundedSubjectRuntimeContract.classifyCanonicalUplink(
                MsgType.DEVICE_STATE_SNAPSHOT,
                reconciliationKind = ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT
            )
        )
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.CanonicalUplinkClass.DIAGNOSTICS_UPLINK,
            AndroidBoundedSubjectRuntimeContract.classifyCanonicalUplink(MsgType.DIAGNOSTICS_PAYLOAD)
        )
    }

    @Test
    fun `observability diagnostics evidence contract classifies local and canonical boundaries`() {
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.ObservabilityContractClass.RUNTIME_VISIBLE,
            AndroidBoundedSubjectRuntimeContract.classifyObservabilityContractClass(
                localSurface = AndroidBoundedSubjectRuntimeContract.LocalConsumptionSurface.RUNTIME_VISIBLE_SURFACE
            )
        )
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.ObservabilityContractClass.PARTICIPANT_TRUTH_UPLINK,
            AndroidBoundedSubjectRuntimeContract.classifyObservabilityContractClass(
                msgType = MsgType.DEVICE_GOVERNANCE_REPORT
            )
        )
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.ObservabilityContractClass.EXECUTION_RESULT_UPLINK,
            AndroidBoundedSubjectRuntimeContract.classifyObservabilityContractClass(
                msgType = MsgType.GOAL_EXECUTION_RESULT
            )
        )
        assertEquals(
            AndroidBoundedSubjectRuntimeContract.ObservabilityContractClass.CONTINUITY_STATE_UPLINK,
            AndroidBoundedSubjectRuntimeContract.classifyObservabilityContractClass(
                msgType = MsgType.DEVICE_STATE_SNAPSHOT,
                reconciliationKind = ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT
            )
        )
        assertEquals(
            null,
            AndroidBoundedSubjectRuntimeContract.classifyObservabilityContractClass(
                msgType = MsgType.DIAGNOSTICS_PAYLOAD
            )
        )
    }

    @Test
    fun `observability boundary entries stay bounded and tied to real modules`() {
        val entries = AndroidBoundedSubjectRuntimeContract.OBSERVABILITY_BOUNDARY_ENTRIES
        assertTrue(entries.any { it.runtimeModule == "RuntimeController" })
        assertTrue(entries.any { it.runtimeModule == "GalaxyConnectionService" })
        assertTrue(entries.any { it.runtimeModule == "GalaxyWebSocketClient" })
        assertTrue(entries.any { it.runtimeModule == "OfflineTaskQueue" })
        assertTrue(entries.any { it.runtimeModule == "AndroidContinuityIntegration" })
        assertTrue(entries.none { it.canonicalFinalAuthority })
        assertTrue(
            entries
                .filter {
                    it.contractClass == AndroidBoundedSubjectRuntimeContract.ObservabilityContractClass.PARTICIPANT_TRUTH_UPLINK ||
                        it.contractClass == AndroidBoundedSubjectRuntimeContract.ObservabilityContractClass.EXECUTION_RESULT_UPLINK ||
                        it.contractClass == AndroidBoundedSubjectRuntimeContract.ObservabilityContractClass.CONTINUITY_STATE_UPLINK
                }
                .all {
                    it.consumptionBoundary ==
                        AndroidBoundedSubjectRuntimeContract.ObservabilityConsumptionBoundary.CANONICAL_UPLINK_ONLY
                }
        )
    }

    @Test
    fun `invariants explicitly prevent passive endpoint and parallel center drift`() {
        val invariants = AndroidBoundedSubjectRuntimeContract.BOUNDED_SUBJECT_RUNTIME_INVARIANTS
        assertEquals(true, invariants["android_is_not_passive_endpoint"])
        assertEquals(true, invariants["android_is_not_parallel_canonical_center"])
        assertEquals(true, invariants["android_is_local_ai_consumer_host"])
        assertEquals(true, invariants["local_ai_consumer_flow_is_runtime_bounded_and_center_aligned"])
        assertEquals(
            true,
            invariants["participant_truth_execution_result_continuity_uplinks_feed_canonical_chain"]
        )
        assertEquals(true, invariants["runtime_visible_is_local_consumption_not_canonical_truth"])
        assertEquals(true, invariants["diagnostics_evidence_must_not_claim_canonical_final_truth"])
        assertEquals(true, invariants["outward_facing_layers_consume_only_bounded_or_canonical_outputs"])
        assertEquals(true, invariants["android_does_not_finalize_global_truth"])
        assertEquals(true, invariants["android_does_not_own_global_dispatch_authority"])
    }
}
