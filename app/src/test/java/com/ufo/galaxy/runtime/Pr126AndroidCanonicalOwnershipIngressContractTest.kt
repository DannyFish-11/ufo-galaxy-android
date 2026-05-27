package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr126AndroidCanonicalOwnershipIngressContractTest {

    @Test
    fun `canonical ownership propagation binds participant to canonical session identity`() {
        val result = AndroidCanonicalOwnershipIngressContract.derive(
            AndroidCanonicalOwnershipIngressContract.Input(
                signalKind = ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT,
                participantId = "device-1:host-1",
                controlSessionId = "control-01",
                durableSessionId = "durable-01",
                sessionContinuityEpoch = 7,
                runtimeAttachmentSessionId = "attach-01",
                deliveryDisposition = AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.DIRECT_SENT
            )
        )

        assertEquals(
            AndroidCanonicalOwnershipIngressContract.OwnershipStatus.CANONICAL_BOUND,
            result.ownershipStatus
        )
        assertEquals(
            AndroidCanonicalOwnershipIngressContract.TruthIngressClass.CANONICALIZATION_CANDIDATE,
            result.truthIngressClass
        )
        assertEquals("device-1:host-1:durable-01:7", result.canonicalSessionIdentity)
        assertTrue(result.isCanonicalizationReady)
    }

    @Test
    fun `authority boundary keeps runtime truth participant-local when canonical identity is incomplete`() {
        val result = AndroidCanonicalOwnershipIngressContract.derive(
            AndroidCanonicalOwnershipIngressContract.Input(
                signalKind = ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT,
                participantId = "device-1:host-1",
                controlSessionId = "control-01",
                durableSessionId = null,
                sessionContinuityEpoch = null,
                runtimeAttachmentSessionId = "attach-01",
                deliveryDisposition = AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.DIRECT_SENT
            )
        )

        assertEquals(
            AndroidCanonicalOwnershipIngressContract.OwnershipStatus.PARTICIPANT_LOCAL_ONLY,
            result.ownershipStatus
        )
        assertEquals(
            AndroidCanonicalOwnershipIngressContract.TruthIngressClass.PARTICIPANT_LOCAL_TRUTH,
            result.truthIngressClass
        )
        assertFalse(result.isCanonicalizationReady)
    }

    @Test
    fun `divergence marker is emitted when ownership path falls back to offline queue`() {
        val result = AndroidCanonicalOwnershipIngressContract.derive(
            AndroidCanonicalOwnershipIngressContract.Input(
                signalKind = ReconciliationSignal.Kind.TASK_RESULT,
                participantId = "device-1:host-1",
                controlSessionId = "control-01",
                durableSessionId = "durable-01",
                sessionContinuityEpoch = 2,
                runtimeAttachmentSessionId = "attach-01",
                deliveryDisposition = AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.OFFLINE_QUEUED
            )
        )

        assertEquals(
            AndroidCanonicalOwnershipIngressContract.OwnershipStatus.DIVERGED_FALLBACK,
            result.ownershipStatus
        )
        assertEquals("offline_queue_pending_canonicalization", result.divergenceMarker)
        assertFalse(result.isCanonicalizationReady)
    }

    @Test
    fun `provenance alignment wire map includes control and runtime attachment boundaries`() {
        val result = AndroidCanonicalOwnershipIngressContract.derive(
            AndroidCanonicalOwnershipIngressContract.Input(
                signalKind = ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT,
                participantId = "device-2:host-9",
                controlSessionId = "control-88",
                durableSessionId = "durable-22",
                sessionContinuityEpoch = 4,
                runtimeAttachmentSessionId = "attach-11",
                deliveryDisposition = AndroidRuntimeEmissionTruthSemantics.DeliveryDisposition.DIRECT_SENT
            )
        )

        val wire = result.toWireMap()
        assertEquals("control-88", wire[AndroidCanonicalOwnershipIngressContract.KEY_CONTROL_SESSION_ID])
        assertEquals(
            "attach-11",
            wire[AndroidCanonicalOwnershipIngressContract.KEY_RUNTIME_ATTACHMENT_SESSION_ID]
        )
        assertNotNull(wire[AndroidCanonicalOwnershipIngressContract.KEY_CANONICAL_SESSION_IDENTITY])
        assertTrue(wire[AndroidCanonicalOwnershipIngressContract.KEY_CANONICALIZATION_READY] as Boolean)
    }
}
