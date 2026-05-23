package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 6 / PR-123 — [AndroidV2DistributedActivationCompatibilityContract] test.
 *
 * Validates that:
 * 1. [ReconciliationSignal] task lifecycle factory methods accept and carry `dispatchPlanId`.
 * 2. [AndroidV2DistributedActivationCompatibilityContract.classify] produces the correct
 *    [AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass] for all
 *    identity-field combinations.
 * 3. The wire key constant aliases [ReconciliationSignal.KEY_DISPATCH_PLAN_ID].
 * 4. [AndroidV2DistributedActivationCompatibilityContract.DISTRIBUTED_ACTIVATION_INVARIANTS]
 *    contains the expected number of invariants.
 * 5. [StabilizationBaseline] registers the contract with the expected attributes.
 * 6. All [ActivationIdentityClass] wire values are stable and unique.
 * 7. [ReconciliationSignal.dispatchPlanId] is propagated into the signal payload
 *    under [ReconciliationSignal.KEY_DISPATCH_PLAN_ID] when non-null.
 * 8. Signals without a dispatch_plan_id do NOT include the key in their payload.
 */
class Pr123AndroidV2DistributedActivationCompatibilityContractTest {

    private val pid = "participant-123"
    private val tid = "task-abc"
    private val planId = "plan-v2-xyz"

    // ══════════════════════════════════════════════════════════════════════════
    // 1. ReconciliationSignal.taskAccepted carries dispatchPlanId
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `taskAccepted carries dispatchPlanId when provided`() {
        val signal = ReconciliationSignal.taskAccepted(
            participantId = pid,
            taskId = tid,
            dispatchPlanId = planId
        )
        assertEquals(planId, signal.dispatchPlanId)
        assertEquals(planId, signal.payload[ReconciliationSignal.KEY_DISPATCH_PLAN_ID])
    }

    @Test
    fun `taskAccepted dispatchPlanId defaults to null`() {
        val signal = ReconciliationSignal.taskAccepted(
            participantId = pid,
            taskId = tid
        )
        assertNull(signal.dispatchPlanId)
        assertFalse(signal.payload.containsKey(ReconciliationSignal.KEY_DISPATCH_PLAN_ID))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. ReconciliationSignal.taskResult carries dispatchPlanId
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `taskResult carries dispatchPlanId when provided`() {
        val signal = ReconciliationSignal.taskResult(
            participantId = pid,
            taskId = tid,
            dispatchPlanId = planId
        )
        assertEquals(planId, signal.dispatchPlanId)
        assertEquals(planId, signal.payload[ReconciliationSignal.KEY_DISPATCH_PLAN_ID])
    }

    @Test
    fun `taskResult dispatchPlanId defaults to null`() {
        val signal = ReconciliationSignal.taskResult(
            participantId = pid,
            taskId = tid
        )
        assertNull(signal.dispatchPlanId)
        assertFalse(signal.payload.containsKey(ReconciliationSignal.KEY_DISPATCH_PLAN_ID))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. ReconciliationSignal.taskCancelled carries dispatchPlanId
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `taskCancelled carries dispatchPlanId when provided`() {
        val signal = ReconciliationSignal.taskCancelled(
            participantId = pid,
            taskId = tid,
            dispatchPlanId = planId
        )
        assertEquals(planId, signal.dispatchPlanId)
        assertEquals(planId, signal.payload[ReconciliationSignal.KEY_DISPATCH_PLAN_ID])
    }

    @Test
    fun `taskCancelled dispatchPlanId defaults to null`() {
        val signal = ReconciliationSignal.taskCancelled(
            participantId = pid,
            taskId = tid
        )
        assertNull(signal.dispatchPlanId)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. ReconciliationSignal.taskFailed carries dispatchPlanId
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `taskFailed carries dispatchPlanId when provided`() {
        val signal = ReconciliationSignal.taskFailed(
            participantId = pid,
            taskId = tid,
            dispatchPlanId = planId,
            errorDetail = "something broke"
        )
        assertEquals(planId, signal.dispatchPlanId)
        assertEquals(planId, signal.payload[ReconciliationSignal.KEY_DISPATCH_PLAN_ID])
    }

    @Test
    fun `taskFailed dispatchPlanId defaults to null`() {
        val signal = ReconciliationSignal.taskFailed(
            participantId = pid,
            taskId = tid
        )
        assertNull(signal.dispatchPlanId)
        assertFalse(signal.payload.containsKey(ReconciliationSignal.KEY_DISPATCH_PLAN_ID))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. ReconciliationSignal.taskStatusUpdate carries dispatchPlanId
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `taskStatusUpdate carries dispatchPlanId when provided`() {
        val signal = ReconciliationSignal.taskStatusUpdate(
            participantId = pid,
            taskId = tid,
            dispatchPlanId = planId,
            progressDetail = "step 2 of 5"
        )
        assertEquals(planId, signal.dispatchPlanId)
        assertEquals(planId, signal.payload[ReconciliationSignal.KEY_DISPATCH_PLAN_ID])
    }

    @Test
    fun `taskStatusUpdate dispatchPlanId defaults to null`() {
        val signal = ReconciliationSignal.taskStatusUpdate(
            participantId = pid,
            taskId = tid
        )
        assertNull(signal.dispatchPlanId)
        assertFalse(signal.payload.containsKey(ReconciliationSignal.KEY_DISPATCH_PLAN_ID))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. Non-task signals do not carry dispatchPlanId
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `participantStateSignal does not carry dispatchPlanId`() {
        val signal = ReconciliationSignal.participantStateSignal(
            participantId = pid,
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY
        )
        assertNull(signal.dispatchPlanId)
        assertFalse(signal.payload.containsKey(ReconciliationSignal.KEY_DISPATCH_PLAN_ID))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. classify() — ActivationIdentityClass derivation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `classify returns FULL_PLAN_IDENTITY when all fields present`() {
        val cls = AndroidV2DistributedActivationCompatibilityContract.classify(
            kind = ReconciliationSignal.Kind.TASK_ACCEPTED,
            taskId = tid,
            participantId = pid,
            dispatchPlanId = planId
        )
        assertEquals(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.FULL_PLAN_IDENTITY,
            cls
        )
        assertTrue(cls.isFullyCorrelatable)
        assertFalse(cls.requiresInference)
    }

    @Test
    fun `classify returns TASK_AND_PARTICIPANT_IDENTITY without dispatchPlanId`() {
        val cls = AndroidV2DistributedActivationCompatibilityContract.classify(
            kind = ReconciliationSignal.Kind.TASK_RESULT,
            taskId = tid,
            participantId = pid,
            dispatchPlanId = null
        )
        assertEquals(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.TASK_AND_PARTICIPANT_IDENTITY,
            cls
        )
        assertFalse(cls.isFullyCorrelatable)
        assertTrue(cls.requiresInference)
    }

    @Test
    fun `classify returns TASK_IDENTITY_ONLY when only taskId present`() {
        val cls = AndroidV2DistributedActivationCompatibilityContract.classify(
            kind = ReconciliationSignal.Kind.TASK_CANCELLED,
            taskId = tid,
            participantId = "",
            dispatchPlanId = null
        )
        assertEquals(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.TASK_IDENTITY_ONLY,
            cls
        )
        assertFalse(cls.isFullyCorrelatable)
        assertTrue(cls.requiresInference)
    }

    @Test
    fun `classify returns INSUFFICIENT_IDENTITY when no task identity present`() {
        val cls = AndroidV2DistributedActivationCompatibilityContract.classify(
            kind = ReconciliationSignal.Kind.TASK_FAILED,
            taskId = null,
            participantId = pid,
            dispatchPlanId = null
        )
        assertEquals(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.INSUFFICIENT_IDENTITY,
            cls
        )
        assertFalse(cls.isFullyCorrelatable)
        assertFalse(cls.requiresInference)
    }

    @Test
    fun `classify returns INSUFFICIENT_IDENTITY for PARTICIPANT_STATE regardless of other fields`() {
        val cls = AndroidV2DistributedActivationCompatibilityContract.classify(
            kind = ReconciliationSignal.Kind.PARTICIPANT_STATE,
            taskId = tid,
            participantId = pid,
            dispatchPlanId = planId
        )
        assertEquals(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.INSUFFICIENT_IDENTITY,
            cls
        )
    }

    @Test
    fun `classify returns INSUFFICIENT_IDENTITY for RUNTIME_TRUTH_SNAPSHOT regardless of other fields`() {
        val cls = AndroidV2DistributedActivationCompatibilityContract.classify(
            kind = ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT,
            taskId = tid,
            participantId = pid,
            dispatchPlanId = planId
        )
        assertEquals(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.INSUFFICIENT_IDENTITY,
            cls
        )
    }

    @Test
    fun `classify signal overload matches field overload`() {
        val signal = ReconciliationSignal.taskAccepted(
            participantId = pid,
            taskId = tid,
            dispatchPlanId = planId
        )
        val fromField = AndroidV2DistributedActivationCompatibilityContract.classify(
            kind = signal.kind,
            taskId = signal.taskId,
            participantId = signal.participantId,
            dispatchPlanId = signal.dispatchPlanId
        )
        val fromSignal = AndroidV2DistributedActivationCompatibilityContract.classify(signal)
        assertEquals(fromField, fromSignal)
        assertEquals(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.FULL_PLAN_IDENTITY,
            fromSignal
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 8. Wire key alias
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `KEY_DISPATCH_PLAN_ID_IN_RECONCILIATION matches ReconciliationSignal constant`() {
        assertEquals(
            ReconciliationSignal.KEY_DISPATCH_PLAN_ID,
            AndroidV2DistributedActivationCompatibilityContract.KEY_DISPATCH_PLAN_ID_IN_RECONCILIATION
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 9. Invariants count
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DISTRIBUTED_ACTIVATION_INVARIANTS has 6 entries`() {
        assertEquals(6, AndroidV2DistributedActivationCompatibilityContract.DISTRIBUTED_ACTIVATION_INVARIANTS.size)
    }

    @Test
    fun `all DISTRIBUTED_ACTIVATION_INVARIANTS are non-blank`() {
        AndroidV2DistributedActivationCompatibilityContract.DISTRIBUTED_ACTIVATION_INVARIANTS
            .forEach { assertTrue("Invariant blank: '$it'", it.isNotBlank()) }
    }

    @Test
    fun `all DISTRIBUTED_ACTIVATION_INVARIANTS start with INV-DA prefix`() {
        AndroidV2DistributedActivationCompatibilityContract.DISTRIBUTED_ACTIVATION_INVARIANTS
            .forEach { assertTrue("Expected INV-DA prefix: '$it'", it.startsWith("INV-DA-")) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 10. ActivationIdentityClass enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ActivationIdentityClass has FULL_PLAN_IDENTITY`() {
        val values = AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass
            .entries.map { it.name }
        assertTrue(values.contains("FULL_PLAN_IDENTITY"))
    }

    @Test
    fun `ActivationIdentityClass has TASK_AND_PARTICIPANT_IDENTITY`() {
        val values = AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass
            .entries.map { it.name }
        assertTrue(values.contains("TASK_AND_PARTICIPANT_IDENTITY"))
    }

    @Test
    fun `ActivationIdentityClass has TASK_IDENTITY_ONLY`() {
        val values = AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass
            .entries.map { it.name }
        assertTrue(values.contains("TASK_IDENTITY_ONLY"))
    }

    @Test
    fun `ActivationIdentityClass has INSUFFICIENT_IDENTITY`() {
        val values = AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass
            .entries.map { it.name }
        assertTrue(values.contains("INSUFFICIENT_IDENTITY"))
    }

    @Test
    fun `all ActivationIdentityClass wire values are non-blank and unique`() {
        val wireValues = AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass
            .entries.map { it.wireValue }
        wireValues.forEach { assertTrue("Wire value blank", it.isNotBlank()) }
        assertEquals("Wire values not unique", wireValues.size, wireValues.toSet().size)
    }

    @Test
    fun `fromWireValue returns correct enum for all entries`() {
        AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.entries
            .forEach { expected ->
                val actual = AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass
                    .fromWireValue(expected.wireValue)
                assertEquals(expected, actual)
            }
    }

    @Test
    fun `fromWireValue returns null for unknown value`() {
        assertNull(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass
                .fromWireValue("definitely_not_a_real_class")
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 11. V2_DISTRIBUTED_ACTIVATION_ALIGNMENT_MAP coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `V2_DISTRIBUTED_ACTIVATION_ALIGNMENT_MAP covers all ActivationIdentityClass values`() {
        AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.entries
            .forEach { cls ->
                assertTrue(
                    "Missing alignment map entry for ${cls.wireValue}",
                    AndroidV2DistributedActivationCompatibilityContract
                        .V2_DISTRIBUTED_ACTIVATION_ALIGNMENT_MAP
                        .containsKey(cls.wireValue)
                )
            }
    }

    @Test
    fun `all V2_DISTRIBUTED_ACTIVATION_ALIGNMENT_MAP values are non-blank`() {
        AndroidV2DistributedActivationCompatibilityContract.V2_DISTRIBUTED_ACTIVATION_ALIGNMENT_MAP
            .values.forEach { assertTrue("Alignment map value blank", it.isNotBlank()) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 12. StabilizationBaseline registration
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `android-v2-distributed-activation-compatibility-contract registered correctly`() {
        val entry = StabilizationBaseline.forId("android-v2-distributed-activation-compatibility-contract")
        assertNotNull(entry)
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `android-runtime-emission-truth-semantics is registered in StabilizationBaseline`() {
        val entry = StabilizationBaseline.forId("android-runtime-emission-truth-semantics")
        assertNotNull(entry)
        assertEquals(
            StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
            entry!!.stability
        )
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 13. toWireMap helper
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `toWireMap includes activation_identity_class and schema_version`() {
        val map = AndroidV2DistributedActivationCompatibilityContract.toWireMap(
            kind = ReconciliationSignal.Kind.TASK_RESULT,
            taskId = tid,
            participantId = pid,
            dispatchPlanId = planId
        )
        assertTrue(map.containsKey(AndroidV2DistributedActivationCompatibilityContract.KEY_ACTIVATION_IDENTITY_CLASS))
        assertTrue(map.containsKey(AndroidV2DistributedActivationCompatibilityContract.KEY_ACTIVATION_IDENTITY_SCHEMA_VERSION))
        assertEquals(
            AndroidV2DistributedActivationCompatibilityContract.ActivationIdentityClass.FULL_PLAN_IDENTITY.wireValue,
            map[AndroidV2DistributedActivationCompatibilityContract.KEY_ACTIVATION_IDENTITY_CLASS]
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 14. INV-DA-05: payload and field must not diverge
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `taskAccepted payload dispatch_plan_id matches field when present`() {
        val signal = ReconciliationSignal.taskAccepted(
            participantId = pid,
            taskId = tid,
            dispatchPlanId = "plan-abc"
        )
        assertEquals(
            signal.dispatchPlanId,
            signal.payload[ReconciliationSignal.KEY_DISPATCH_PLAN_ID]
        )
    }

    @Test
    fun `taskResult payload dispatch_plan_id matches field when present`() {
        val signal = ReconciliationSignal.taskResult(
            participantId = pid,
            taskId = tid,
            dispatchPlanId = "plan-def"
        )
        assertEquals(
            signal.dispatchPlanId,
            signal.payload[ReconciliationSignal.KEY_DISPATCH_PLAN_ID]
        )
    }

    @Test
    fun `taskCancelled payload dispatch_plan_id matches field when present`() {
        val signal = ReconciliationSignal.taskCancelled(
            participantId = pid,
            taskId = tid,
            dispatchPlanId = "plan-ghi"
        )
        assertEquals(
            signal.dispatchPlanId,
            signal.payload[ReconciliationSignal.KEY_DISPATCH_PLAN_ID]
        )
    }

    @Test
    fun `taskFailed payload dispatch_plan_id matches field when present`() {
        val signal = ReconciliationSignal.taskFailed(
            participantId = pid,
            taskId = tid,
            dispatchPlanId = "plan-jkl"
        )
        assertEquals(
            signal.dispatchPlanId,
            signal.payload[ReconciliationSignal.KEY_DISPATCH_PLAN_ID]
        )
    }

    @Test
    fun `taskStatusUpdate payload dispatch_plan_id matches field when present`() {
        val signal = ReconciliationSignal.taskStatusUpdate(
            participantId = pid,
            taskId = tid,
            dispatchPlanId = "plan-mno"
        )
        assertEquals(
            signal.dispatchPlanId,
            signal.payload[ReconciliationSignal.KEY_DISPATCH_PLAN_ID]
        )
    }
}
