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
    fun `invariants explicitly prevent passive endpoint and parallel center drift`() {
        val invariants = AndroidBoundedSubjectRuntimeContract.BOUNDED_SUBJECT_RUNTIME_INVARIANTS
        assertEquals(true, invariants["android_is_not_passive_endpoint"])
        assertEquals(true, invariants["android_is_not_parallel_canonical_center"])
        assertEquals(true, invariants["android_is_local_ai_consumer_host"])
        assertEquals(true, invariants["android_does_not_finalize_global_truth"])
        assertEquals(true, invariants["android_does_not_own_global_dispatch_authority"])
    }
}
