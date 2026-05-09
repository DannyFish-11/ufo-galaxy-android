package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-8 (Android) — Regression tests for mesh/runtime proof quality semantics.
 *
 * Validates that Android producer-side readiness signals carry an explicit
 * [MeshRuntimeProofQuality] grade so V2 cannot mistake partial, stale, missing, or
 * structurally-inferred proof for a live-healthy mesh node.
 *
 * ## Test domains
 *
 * ### 1. MeshRuntimeProofQuality — wire values and round-trips
 *  - All wire values are distinct and non-empty.
 *  - fromWireValue round-trips all entries.
 *  - fromWireValue returns null for unknown input.
 *
 * ### 2. MeshRuntimeProofQuality.derive — canonical derivation rules
 *  - LIVE when health=HEALTHY, participation=ACTIVE, barrier=NOT_APPLICABLE, fallback=false,
 *    crossDevice=true, delegatedExecution=true.
 *  - PARTIAL when health=DEGRADED.
 *  - PARTIAL when fallbackActive=true.
 *  - PARTIAL when delegatedExecutionAllowed=false (constraint active).
 *  - PARTIAL when barrier=WAITING.
 *  - PARTIAL when barrier=TIMED_OUT.
 *  - STALE when health=RECOVERING.
 *  - STALE when health=STARTING.
 *  - MISSING when crossDeviceAllowed=false.
 *  - MISSING when participation=INACTIVE.
 *  - MISSING when health=FAILED.
 *  - MISSING when health=UNKNOWN.
 *  - STRUCTURALLY_INFERRED when health=HEALTHY, crossDevice=true, delegated=true,
 *    participation=STANDBY, barrier=NOT_APPLICABLE, fallback=false.
 *
 * ### 3. AndroidMeshParticipationRuntimeContract — proofQuality in derive()
 *  - derive() with fully live inputs produces proofQuality=LIVE.
 *  - derive() with DEGRADED health produces proofQuality=PARTIAL.
 *  - derive() with fallbackActive produces proofQuality=PARTIAL.
 *  - derive() with delegated execution disabled produces proofQuality=PARTIAL.
 *  - derive() with RECOVERING health produces proofQuality=STALE.
 *  - derive() with crossDevice disabled produces proofQuality=MISSING.
 *  - derive() with INACTIVE participation produces proofQuality=MISSING.
 *  - derive() with FAILED health produces proofQuality=MISSING.
 *  - derive() with BARRIER_WAITING produces proofQuality=PARTIAL.
 *
 * ### 4. AndroidMeshParticipationRuntimeContract — isParticipationReady gating on proof quality
 *  - isParticipationReady is false when proofQuality=MISSING even if lifecycle=ACTIVE.
 *  - isParticipationReady is true for ACTIVE + LIVE proof.
 *  - isParticipationReady is true for DEGRADED + PARTIAL proof.
 *
 * ### 5. AndroidMeshParticipationRuntimeContract — toWireMap includes proof quality
 *  - toWireMap contains KEY_PROOF_QUALITY with correct wire value.
 *  - toWireMap proof_quality=live for healthy unconstrained active participant.
 *  - toWireMap proof_quality=partial for degraded participant.
 *  - toWireMap proof_quality=stale for recovering participant.
 *  - toWireMap proof_quality=missing when cross-device disabled.
 *
 * ### 6. AndroidMeshParticipationContract — proofQuality in evaluate()
 *  - evaluate() with healthy ACTIVE orchestration produces proofQuality=LIVE (or PARTIAL
 *    if full mesh capabilities deferred; health determines quality not deferred caps).
 *  - evaluate() with RECOVERING orchestration produces proofQuality=STALE.
 *  - evaluate() with DISCONNECTED orchestration produces proofQuality=MISSING.
 *  - evaluate() with DEGRADED orchestration produces proofQuality=PARTIAL.
 *  - evaluate() toWireMap contains KEY_PROOF_QUALITY.
 *
 * ### 7. Producer contract invariants
 *  - proof_quality wire value is always one of the known stable values.
 *  - LIVE proof_quality is never combined with DEGRADED/CONSTRAINED/FALLBACK lifecycle.
 *  - MISSING proof_quality never produces isParticipationReady=true.
 */
class Pr8AndroidMeshRuntimeProofQualityTest {

    // ── 1. Wire values and round-trips ────────────────────────────────────────

    @Test
    fun `all MeshRuntimeProofQuality wire values are distinct and non-empty`() {
        val wireValues = MeshRuntimeProofQuality.ALL_WIRE_VALUES
        assertEquals(MeshRuntimeProofQuality.entries.size, wireValues.size)
        wireValues.forEach { assertTrue("Wire value must be non-empty", it.isNotEmpty()) }
    }

    @Test
    fun `fromWireValue round-trips all MeshRuntimeProofQuality entries`() {
        MeshRuntimeProofQuality.entries.forEach { grade ->
            assertEquals(grade, MeshRuntimeProofQuality.fromWireValue(grade.wireValue))
        }
    }

    @Test
    fun `fromWireValue returns null for unknown wire value`() {
        assertEquals(null, MeshRuntimeProofQuality.fromWireValue("not_a_real_grade"))
    }

    @Test
    fun `fromWireValue returns null for null input`() {
        assertEquals(null, MeshRuntimeProofQuality.fromWireValue(null))
    }

    @Test
    fun `WIRE_KEY is the stable canonical key mesh_runtime_proof_quality`() {
        assertEquals("mesh_runtime_proof_quality", MeshRuntimeProofQuality.WIRE_KEY)
    }

    @Test
    fun `all five canonical grades have expected wire values`() {
        assertEquals("live", MeshRuntimeProofQuality.LIVE.wireValue)
        assertEquals("partial", MeshRuntimeProofQuality.PARTIAL.wireValue)
        assertEquals("stale", MeshRuntimeProofQuality.STALE.wireValue)
        assertEquals("missing", MeshRuntimeProofQuality.MISSING.wireValue)
        assertEquals("structurally_inferred", MeshRuntimeProofQuality.STRUCTURALLY_INFERRED.wireValue)
    }

    // ── 2. MeshRuntimeProofQuality.derive — canonical derivation rules ─────────

    @Test
    fun `derive returns LIVE when all live-proof conditions are satisfied`() {
        assertEquals(
            MeshRuntimeProofQuality.LIVE,
            MeshRuntimeProofQuality.derive(
                healthState = ParticipantHealthState.HEALTHY,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
                barrierState = BarrierParticipationState.NOT_APPLICABLE,
                fallbackActive = false,
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = true
            )
        )
    }

    @Test
    fun `derive returns LIVE when barrier is RELEASED and all other conditions are live`() {
        assertEquals(
            MeshRuntimeProofQuality.LIVE,
            MeshRuntimeProofQuality.derive(
                healthState = ParticipantHealthState.HEALTHY,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
                barrierState = BarrierParticipationState.RELEASED,
                fallbackActive = false,
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = true
            )
        )
    }

    @Test
    fun `derive returns PARTIAL when health is DEGRADED`() {
        assertEquals(
            MeshRuntimeProofQuality.PARTIAL,
            MeshRuntimeProofQuality.derive(
                healthState = ParticipantHealthState.DEGRADED,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
                barrierState = BarrierParticipationState.NOT_APPLICABLE,
                fallbackActive = false,
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = true
            )
        )
    }

    @Test
    fun `derive returns PARTIAL when fallback is active`() {
        assertEquals(
            MeshRuntimeProofQuality.PARTIAL,
            MeshRuntimeProofQuality.derive(
                healthState = ParticipantHealthState.HEALTHY,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
                barrierState = BarrierParticipationState.NOT_APPLICABLE,
                fallbackActive = true,
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = true
            )
        )
    }

    @Test
    fun `derive returns PARTIAL when delegated execution is not allowed`() {
        assertEquals(
            MeshRuntimeProofQuality.PARTIAL,
            MeshRuntimeProofQuality.derive(
                healthState = ParticipantHealthState.HEALTHY,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
                barrierState = BarrierParticipationState.NOT_APPLICABLE,
                fallbackActive = false,
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = false
            )
        )
    }

    @Test
    fun `derive returns PARTIAL when barrier is WAITING`() {
        assertEquals(
            MeshRuntimeProofQuality.PARTIAL,
            MeshRuntimeProofQuality.derive(
                healthState = ParticipantHealthState.HEALTHY,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
                barrierState = BarrierParticipationState.WAITING,
                fallbackActive = false,
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = true
            )
        )
    }

    @Test
    fun `derive returns PARTIAL when barrier is TIMED_OUT`() {
        assertEquals(
            MeshRuntimeProofQuality.PARTIAL,
            MeshRuntimeProofQuality.derive(
                healthState = ParticipantHealthState.HEALTHY,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
                barrierState = BarrierParticipationState.TIMED_OUT,
                fallbackActive = false,
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = true
            )
        )
    }

    @Test
    fun `derive returns STALE when health is RECOVERING`() {
        assertEquals(
            MeshRuntimeProofQuality.STALE,
            MeshRuntimeProofQuality.derive(
                healthState = ParticipantHealthState.RECOVERING,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
                barrierState = BarrierParticipationState.NOT_APPLICABLE,
                fallbackActive = false,
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = true
            )
        )
    }

    @Test
    fun `derive returns STALE when health is STARTING`() {
        assertEquals(
            MeshRuntimeProofQuality.STALE,
            MeshRuntimeProofQuality.derive(
                healthState = ParticipantHealthState.STARTING,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
                barrierState = BarrierParticipationState.NOT_APPLICABLE,
                fallbackActive = false,
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = true
            )
        )
    }

    @Test
    fun `derive returns MISSING when cross-device is disabled`() {
        assertEquals(
            MeshRuntimeProofQuality.MISSING,
            MeshRuntimeProofQuality.derive(
                healthState = ParticipantHealthState.HEALTHY,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
                barrierState = BarrierParticipationState.NOT_APPLICABLE,
                fallbackActive = false,
                crossDeviceAllowed = false,
                delegatedExecutionAllowed = true
            )
        )
    }

    @Test
    fun `derive returns MISSING when participation state is INACTIVE`() {
        assertEquals(
            MeshRuntimeProofQuality.MISSING,
            MeshRuntimeProofQuality.derive(
                healthState = ParticipantHealthState.HEALTHY,
                participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE,
                barrierState = BarrierParticipationState.NOT_APPLICABLE,
                fallbackActive = false,
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = true
            )
        )
    }

    @Test
    fun `derive returns MISSING when health is FAILED`() {
        assertEquals(
            MeshRuntimeProofQuality.MISSING,
            MeshRuntimeProofQuality.derive(
                healthState = ParticipantHealthState.FAILED,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
                barrierState = BarrierParticipationState.NOT_APPLICABLE,
                fallbackActive = false,
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = true
            )
        )
    }

    @Test
    fun `derive returns MISSING when health is UNKNOWN`() {
        assertEquals(
            MeshRuntimeProofQuality.MISSING,
            MeshRuntimeProofQuality.derive(
                healthState = ParticipantHealthState.UNKNOWN,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
                barrierState = BarrierParticipationState.NOT_APPLICABLE,
                fallbackActive = false,
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = true
            )
        )
    }

    @Test
    fun `derive returns STRUCTURALLY_INFERRED when participation is STANDBY with all gates open`() {
        assertEquals(
            MeshRuntimeProofQuality.STRUCTURALLY_INFERRED,
            MeshRuntimeProofQuality.derive(
                healthState = ParticipantHealthState.HEALTHY,
                participationState = RuntimeHostDescriptor.HostParticipationState.STANDBY,
                barrierState = BarrierParticipationState.NOT_APPLICABLE,
                fallbackActive = false,
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = true
            )
        )
    }

    @Test
    fun `derive returns STRUCTURALLY_INFERRED when participation is DRAINING with all gates open`() {
        assertEquals(
            MeshRuntimeProofQuality.STRUCTURALLY_INFERRED,
            MeshRuntimeProofQuality.derive(
                healthState = ParticipantHealthState.HEALTHY,
                participationState = RuntimeHostDescriptor.HostParticipationState.DRAINING,
                barrierState = BarrierParticipationState.NOT_APPLICABLE,
                fallbackActive = false,
                crossDeviceAllowed = true,
                delegatedExecutionAllowed = true
            )
        )
    }

    // ── 3. AndroidMeshParticipationRuntimeContract — proofQuality in derive() ─

    @Test
    fun `RuntimeContract derive produces LIVE proof for fully healthy active unconstrained participant`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshRuntimeProofQuality.LIVE, report.proofQuality)
    }

    @Test
    fun `RuntimeContract derive produces PARTIAL proof when health is DEGRADED`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.DEGRADED,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshRuntimeProofQuality.PARTIAL, report.proofQuality)
    }

    @Test
    fun `RuntimeContract derive produces PARTIAL proof when fallback is active`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = true,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshRuntimeProofQuality.PARTIAL, report.proofQuality)
    }

    @Test
    fun `RuntimeContract derive produces PARTIAL proof when delegated execution disabled`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = false),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshRuntimeProofQuality.PARTIAL, report.proofQuality)
    }

    @Test
    fun `RuntimeContract derive produces PARTIAL proof when barrier is WAITING`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.WAITING,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshRuntimeProofQuality.PARTIAL, report.proofQuality)
    }

    @Test
    fun `RuntimeContract derive produces STALE proof when health is RECOVERING`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.RECOVERING,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshRuntimeProofQuality.STALE, report.proofQuality)
    }

    @Test
    fun `RuntimeContract derive produces MISSING proof when cross-device disabled`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = false, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshRuntimeProofQuality.MISSING, report.proofQuality)
    }

    @Test
    fun `RuntimeContract derive produces MISSING proof when participation is INACTIVE`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
        )
        assertEquals(MeshRuntimeProofQuality.MISSING, report.proofQuality)
    }

    @Test
    fun `RuntimeContract derive produces MISSING proof when health is FAILED`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.FAILED,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshRuntimeProofQuality.MISSING, report.proofQuality)
    }

    // ── 4. isParticipationReady gating on proof quality ───────────────────────

    @Test
    fun `isParticipationReady is false when proofQuality is MISSING even if lifecycle is ACTIVE`() {
        val report = AndroidMeshParticipationRuntimeContract.MeshRuntimeStateReport(
            participationLifecycle = MeshParticipationLifecycleState.ACTIVE,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationLifecycle = CollaborationLifecycleState.IDLE,
            constrainedReasons = emptyList(),
            proofQuality = MeshRuntimeProofQuality.MISSING
        )
        assertFalse(
            "isParticipationReady must be false when proof quality is MISSING regardless of lifecycle state",
            report.isParticipationReady
        )
    }

    @Test
    fun `isParticipationReady is true when proofQuality is LIVE and lifecycle is ACTIVE`() {
        val report = AndroidMeshParticipationRuntimeContract.MeshRuntimeStateReport(
            participationLifecycle = MeshParticipationLifecycleState.ACTIVE,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationLifecycle = CollaborationLifecycleState.IDLE,
            constrainedReasons = emptyList(),
            proofQuality = MeshRuntimeProofQuality.LIVE
        )
        assertTrue(report.isParticipationReady)
    }

    @Test
    fun `isParticipationReady is true when proofQuality is PARTIAL and lifecycle is DEGRADED`() {
        val report = AndroidMeshParticipationRuntimeContract.MeshRuntimeStateReport(
            participationLifecycle = MeshParticipationLifecycleState.DEGRADED,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationLifecycle = CollaborationLifecycleState.IDLE,
            constrainedReasons = listOf(AndroidMeshParticipationRuntimeContract.REASON_HEALTH_DEGRADED),
            proofQuality = MeshRuntimeProofQuality.PARTIAL
        )
        assertTrue(report.isParticipationReady)
    }

    @Test
    fun `isParticipationReady is false when proofQuality is STALE and lifecycle is INACTIVE`() {
        val report = AndroidMeshParticipationRuntimeContract.MeshRuntimeStateReport(
            participationLifecycle = MeshParticipationLifecycleState.INACTIVE,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationLifecycle = CollaborationLifecycleState.IDLE,
            constrainedReasons = emptyList(),
            proofQuality = MeshRuntimeProofQuality.STALE
        )
        assertFalse(report.isParticipationReady)
    }

    @Test
    fun `isParticipationReady is false when proofQuality is STRUCTURALLY_INFERRED and lifecycle is INACTIVE`() {
        val report = AndroidMeshParticipationRuntimeContract.MeshRuntimeStateReport(
            participationLifecycle = MeshParticipationLifecycleState.INACTIVE,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationLifecycle = CollaborationLifecycleState.IDLE,
            constrainedReasons = emptyList(),
            proofQuality = MeshRuntimeProofQuality.STRUCTURALLY_INFERRED
        )
        assertFalse(report.isParticipationReady)
    }

    // ── 5. toWireMap includes proof quality ───────────────────────────────────

    @Test
    fun `toWireMap contains KEY_PROOF_QUALITY for healthy active participant`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        val wireMap = report.toWireMap()
        assertNotNull(wireMap[AndroidMeshParticipationRuntimeContract.KEY_PROOF_QUALITY])
        assertEquals("live", wireMap[AndroidMeshParticipationRuntimeContract.KEY_PROOF_QUALITY])
    }

    @Test
    fun `toWireMap proof_quality is partial when participant health is DEGRADED`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.DEGRADED,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        val wireMap = report.toWireMap()
        assertEquals("partial", wireMap[AndroidMeshParticipationRuntimeContract.KEY_PROOF_QUALITY])
    }

    @Test
    fun `toWireMap proof_quality is stale when participant is RECOVERING`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.RECOVERING,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        val wireMap = report.toWireMap()
        assertEquals("stale", wireMap[AndroidMeshParticipationRuntimeContract.KEY_PROOF_QUALITY])
    }

    @Test
    fun `toWireMap proof_quality is missing when cross-device is disabled`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = false, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        val wireMap = report.toWireMap()
        assertEquals("missing", wireMap[AndroidMeshParticipationRuntimeContract.KEY_PROOF_QUALITY])
    }

    @Test
    fun `toWireMap proof_quality wire value is always one of the known stable values`() {
        val scenarios = listOf(
            Triple(ParticipantHealthState.HEALTHY, RuntimeHostDescriptor.HostParticipationState.ACTIVE, false),
            Triple(ParticipantHealthState.DEGRADED, RuntimeHostDescriptor.HostParticipationState.ACTIVE, false),
            Triple(ParticipantHealthState.RECOVERING, RuntimeHostDescriptor.HostParticipationState.ACTIVE, false),
            Triple(ParticipantHealthState.FAILED, RuntimeHostDescriptor.HostParticipationState.ACTIVE, false),
            Triple(ParticipantHealthState.UNKNOWN, RuntimeHostDescriptor.HostParticipationState.INACTIVE, false),
            Triple(ParticipantHealthState.HEALTHY, RuntimeHostDescriptor.HostParticipationState.ACTIVE, true)
        )
        scenarios.forEach { (health, participation, fallback) ->
            val report = AndroidMeshParticipationRuntimeContract.derive(
                rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
                healthState = health,
                barrierState = BarrierParticipationState.NOT_APPLICABLE,
                collaborationState = CollaborationLifecycleState.IDLE,
                fallbackActive = fallback,
                participationState = participation
            )
            val proofQualityWireValue = report.toWireMap()[AndroidMeshParticipationRuntimeContract.KEY_PROOF_QUALITY] as String
            assertTrue(
                "Unknown proof_quality wire value '$proofQualityWireValue' for health=$health participation=$participation fallback=$fallback",
                MeshRuntimeProofQuality.ALL_WIRE_VALUES.contains(proofQualityWireValue)
            )
        }
    }

    // ── 6. AndroidMeshParticipationContract — proofQuality in evaluate() ──────

    @Test
    fun `evaluate with healthy ACTIVE orchestration produces non-MISSING proof quality`() {
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = healthyOrchestration(),
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true)
        )
        // Healthy ACTIVE orchestration: capabilities are deferred (PARTIAL/LIVE) but never MISSING
        assertTrue(
            "Healthy active orchestration must produce LIVE or PARTIAL proof, got ${report.proofQuality}",
            report.proofQuality == MeshRuntimeProofQuality.LIVE ||
                report.proofQuality == MeshRuntimeProofQuality.PARTIAL
        )
    }

    @Test
    fun `evaluate with RECOVERING orchestration produces STALE proof quality`() {
        val recovering = MultiDeviceParticipantOrchestrationState.from(
            healthState = ParticipantHealthState.RECOVERING,
            reconnectState = ReconnectRecoveryState.RECOVERING,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = recovering,
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true)
        )
        assertEquals(MeshRuntimeProofQuality.STALE, report.proofQuality)
    }

    @Test
    fun `evaluate with DISCONNECTED orchestration produces MISSING proof quality`() {
        val disconnected = MultiDeviceParticipantOrchestrationState.from(
            healthState = ParticipantHealthState.UNKNOWN,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.NOT_READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
        )
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = disconnected,
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true)
        )
        assertEquals(MeshRuntimeProofQuality.MISSING, report.proofQuality)
    }

    @Test
    fun `evaluate with DEGRADED orchestration produces PARTIAL proof quality`() {
        val degraded = MultiDeviceParticipantOrchestrationState.from(
            healthState = ParticipantHealthState.DEGRADED,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY_WITH_FALLBACK,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = degraded,
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true)
        )
        assertEquals(MeshRuntimeProofQuality.PARTIAL, report.proofQuality)
    }

    @Test
    fun `evaluate with cross-device disabled produces MISSING proof quality`() {
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = healthyOrchestration(),
            rollout = rollout(crossDeviceAllowed = false, delegatedExecutionAllowed = true)
        )
        assertEquals(MeshRuntimeProofQuality.MISSING, report.proofQuality)
    }

    @Test
    fun `evaluate toWireMap contains KEY_PROOF_QUALITY`() {
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = healthyOrchestration(),
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true)
        )
        val wireMap = report.toWireMap()
        assertNotNull(wireMap[AndroidMeshParticipationContract.KEY_PROOF_QUALITY])
        assertTrue(
            MeshRuntimeProofQuality.ALL_WIRE_VALUES.contains(
                wireMap[AndroidMeshParticipationContract.KEY_PROOF_QUALITY] as String
            )
        )
    }

    @Test
    fun `evaluate toWireMap KEY_PROOF_QUALITY is missing when cross-device is disabled`() {
        val report = AndroidMeshParticipationContract.evaluate(
            orchestration = healthyOrchestration(),
            rollout = rollout(crossDeviceAllowed = false, delegatedExecutionAllowed = true)
        )
        assertEquals("missing", report.toWireMap()[AndroidMeshParticipationContract.KEY_PROOF_QUALITY])
    }

    // ── 7. Producer contract invariants ──────────────────────────────────────

    @Test
    fun `LIVE proof is never emitted alongside DEGRADED lifecycle in RuntimeContract derive`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.DEGRADED,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.DEGRADED, report.participationLifecycle)
        assertFalse(
            "LIVE proof must not be emitted when lifecycle is DEGRADED",
            report.proofQuality == MeshRuntimeProofQuality.LIVE
        )
    }

    @Test
    fun `LIVE proof is never emitted alongside CONSTRAINED lifecycle in RuntimeContract derive`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = false),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.CONSTRAINED, report.participationLifecycle)
        assertFalse(
            "LIVE proof must not be emitted when lifecycle is CONSTRAINED",
            report.proofQuality == MeshRuntimeProofQuality.LIVE
        )
    }

    @Test
    fun `LIVE proof is never emitted alongside FALLBACK lifecycle in RuntimeContract derive`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = true,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.FALLBACK, report.participationLifecycle)
        assertFalse(
            "LIVE proof must not be emitted when lifecycle is FALLBACK",
            report.proofQuality == MeshRuntimeProofQuality.LIVE
        )
    }

    @Test
    fun `MISSING proof quality never produces isParticipationReady=true in RuntimeContract derive`() {
        // All scenarios that produce MISSING proof must also produce isParticipationReady=false
        val missingScenarios = listOf(
            rollout(crossDeviceAllowed = false, delegatedExecutionAllowed = true) to ParticipantHealthState.HEALTHY,
            rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true) to ParticipantHealthState.FAILED,
            rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true) to ParticipantHealthState.UNKNOWN
        )
        missingScenarios.forEach { (rollout, health) ->
            val report = AndroidMeshParticipationRuntimeContract.derive(
                rollout = rollout,
                healthState = health,
                barrierState = BarrierParticipationState.NOT_APPLICABLE,
                collaborationState = CollaborationLifecycleState.IDLE,
                fallbackActive = false,
                participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
            )
            if (report.proofQuality == MeshRuntimeProofQuality.MISSING) {
                assertFalse(
                    "MISSING proof must never produce isParticipationReady=true (health=$health)",
                    report.isParticipationReady
                )
            }
        }
    }

    @Test
    fun `MISSING proof quality never produces isParticipationReady=true for INACTIVE participation`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
        )
        assertEquals(MeshRuntimeProofQuality.MISSING, report.proofQuality)
        assertFalse(report.isParticipationReady)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun rollout(
        crossDeviceAllowed: Boolean,
        delegatedExecutionAllowed: Boolean
    ): RolloutControlSnapshot = RolloutControlSnapshot(
        crossDeviceAllowed = crossDeviceAllowed,
        delegatedExecutionAllowed = delegatedExecutionAllowed,
        fallbackToLocalAllowed = true,
        goalExecutionAllowed = true
    )

    private fun healthyOrchestration(): MultiDeviceParticipantOrchestrationState.StateRecord =
        MultiDeviceParticipantOrchestrationState.from(
            healthState = ParticipantHealthState.HEALTHY,
            reconnectState = ReconnectRecoveryState.IDLE,
            readinessState = ParticipantReadinessState.READY,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
}
