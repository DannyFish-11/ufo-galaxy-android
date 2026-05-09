package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-04 — Android mesh participation runtime closure tests.
 *
 * Covers:
 *  1. Participation lifecycle state transitions.
 *  2. Barrier wait/release/timeout state transitions.
 *  3. Collaboration lifecycle state reporting.
 *  4. Degraded / fallback / constrained participation reporting.
 *  5. Semantic alignment with center-side mesh state contract.
 *  6. Wire-map correctness for all new fields.
 */
class Pr4AndroidMeshParticipationRuntimeClosureTest {

    // ── MeshParticipationLifecycleState tests ─────────────────────────────────

    @Test
    fun `derive returns ACTIVE for healthy active participant with cross-device allowed`() {
        val state = MeshParticipationLifecycleState.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.ACTIVE, state)
    }

    @Test
    fun `derive returns INACTIVE when cross-device not allowed`() {
        val state = MeshParticipationLifecycleState.derive(
            rollout = rollout(crossDeviceAllowed = false, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.INACTIVE, state)
    }

    @Test
    fun `derive returns INACTIVE when participation state is INACTIVE`() {
        val state = MeshParticipationLifecycleState.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.INACTIVE, state)
    }

    @Test
    fun `derive returns INACTIVE when health is FAILED`() {
        val state = MeshParticipationLifecycleState.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.FAILED,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.INACTIVE, state)
    }

    @Test
    fun `derive returns BARRIER_WAITING when barrier state is WAITING`() {
        val state = MeshParticipationLifecycleState.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.WAITING,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.BARRIER_WAITING, state)
    }

    @Test
    fun `derive returns BARRIER_RELEASED when barrier state is RELEASED`() {
        val state = MeshParticipationLifecycleState.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.RELEASED,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.BARRIER_RELEASED, state)
    }

    @Test
    fun `derive returns DEGRADED when health is DEGRADED`() {
        val state = MeshParticipationLifecycleState.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.DEGRADED,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.DEGRADED, state)
    }

    @Test
    fun `derive returns CONSTRAINED when delegated execution not allowed`() {
        val state = MeshParticipationLifecycleState.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = false),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.CONSTRAINED, state)
    }

    @Test
    fun `derive returns FALLBACK when fallback is active`() {
        val state = MeshParticipationLifecycleState.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            fallbackActive = true,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.FALLBACK, state)
    }

    // ── Barrier state predicate tests ─────────────────────────────────────────

    @Test
    fun `isBarrierState is true only for BARRIER_WAITING and BARRIER_RELEASED`() {
        assertTrue(MeshParticipationLifecycleState.isBarrierState(MeshParticipationLifecycleState.BARRIER_WAITING))
        assertTrue(MeshParticipationLifecycleState.isBarrierState(MeshParticipationLifecycleState.BARRIER_RELEASED))
        assertFalse(MeshParticipationLifecycleState.isBarrierState(MeshParticipationLifecycleState.ACTIVE))
        assertFalse(MeshParticipationLifecycleState.isBarrierState(MeshParticipationLifecycleState.INACTIVE))
        assertFalse(MeshParticipationLifecycleState.isBarrierState(MeshParticipationLifecycleState.DEGRADED))
    }

    @Test
    fun `isLimitedParticipation is true for DEGRADED CONSTRAINED FALLBACK`() {
        assertTrue(MeshParticipationLifecycleState.isLimitedParticipation(MeshParticipationLifecycleState.DEGRADED))
        assertTrue(MeshParticipationLifecycleState.isLimitedParticipation(MeshParticipationLifecycleState.CONSTRAINED))
        assertTrue(MeshParticipationLifecycleState.isLimitedParticipation(MeshParticipationLifecycleState.FALLBACK))
        assertFalse(MeshParticipationLifecycleState.isLimitedParticipation(MeshParticipationLifecycleState.ACTIVE))
        assertFalse(MeshParticipationLifecycleState.isLimitedParticipation(MeshParticipationLifecycleState.INACTIVE))
    }

    @Test
    fun `all wire values are stable and distinct`() {
        val wireValues = MeshParticipationLifecycleState.ALL_WIRE_VALUES
        assertEquals(MeshParticipationLifecycleState.entries.size, wireValues.size)
        assertTrue(wireValues.contains("inactive"))
        assertTrue(wireValues.contains("active"))
        assertTrue(wireValues.contains("barrier_waiting"))
        assertTrue(wireValues.contains("barrier_released"))
        assertTrue(wireValues.contains("degraded"))
        assertTrue(wireValues.contains("constrained"))
        assertTrue(wireValues.contains("fallback"))
    }

    // ── BarrierParticipationState tests ───────────────────────────────────────

    @Test
    fun `onBarrierWait transitions ACTIVE to BARRIER_WAITING`() {
        val result = AndroidMeshParticipationRuntimeContract.onBarrierWait(
            MeshParticipationLifecycleState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.BARRIER_WAITING, result)
    }

    @Test
    fun `onBarrierWait transitions DEGRADED to BARRIER_WAITING`() {
        val result = AndroidMeshParticipationRuntimeContract.onBarrierWait(
            MeshParticipationLifecycleState.DEGRADED
        )
        assertEquals(MeshParticipationLifecycleState.BARRIER_WAITING, result)
    }

    @Test
    fun `onBarrierWait does not change INACTIVE state`() {
        val result = AndroidMeshParticipationRuntimeContract.onBarrierWait(
            MeshParticipationLifecycleState.INACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.INACTIVE, result)
    }

    @Test
    fun `onBarrierRelease transitions BARRIER_WAITING to BARRIER_RELEASED`() {
        val result = AndroidMeshParticipationRuntimeContract.onBarrierRelease(
            MeshParticipationLifecycleState.BARRIER_WAITING
        )
        assertEquals(MeshParticipationLifecycleState.BARRIER_RELEASED, result)
    }

    @Test
    fun `onBarrierRelease is no-op for ACTIVE state`() {
        val result = AndroidMeshParticipationRuntimeContract.onBarrierRelease(
            MeshParticipationLifecycleState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.ACTIVE, result)
    }

    @Test
    fun `onBarrierTimeout transitions BARRIER_WAITING to FALLBACK`() {
        val result = AndroidMeshParticipationRuntimeContract.onBarrierTimeout(
            MeshParticipationLifecycleState.BARRIER_WAITING
        )
        assertEquals(MeshParticipationLifecycleState.FALLBACK, result)
    }

    @Test
    fun `onBarrierTimeout is no-op for non-waiting state`() {
        val result = AndroidMeshParticipationRuntimeContract.onBarrierTimeout(
            MeshParticipationLifecycleState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.ACTIVE, result)
    }

    @Test
    fun `BarrierParticipationState wire values are stable`() {
        val wireValues = BarrierParticipationState.ALL_WIRE_VALUES
        assertEquals(BarrierParticipationState.entries.size, wireValues.size)
        assertTrue(wireValues.contains("not_applicable"))
        assertTrue(wireValues.contains("waiting"))
        assertTrue(wireValues.contains("released"))
        assertTrue(wireValues.contains("timed_out"))
    }

    @Test
    fun `BarrierParticipationState isActive returns true for WAITING and RELEASED only`() {
        assertTrue(BarrierParticipationState.isActive(BarrierParticipationState.WAITING))
        assertTrue(BarrierParticipationState.isActive(BarrierParticipationState.RELEASED))
        assertFalse(BarrierParticipationState.isActive(BarrierParticipationState.NOT_APPLICABLE))
        assertFalse(BarrierParticipationState.isActive(BarrierParticipationState.TIMED_OUT))
    }

    // ── CollaborationLifecycleState tests ─────────────────────────────────────

    @Test
    fun `CollaborationLifecycleState wire values are stable`() {
        val wireValues = CollaborationLifecycleState.ALL_WIRE_VALUES
        assertEquals(CollaborationLifecycleState.entries.size, wireValues.size)
        assertTrue(wireValues.contains("idle"))
        assertTrue(wireValues.contains("subtask_assigned"))
        assertTrue(wireValues.contains("executing"))
        assertTrue(wireValues.contains("completed"))
        assertTrue(wireValues.contains("failed"))
        assertTrue(wireValues.contains("cancelled"))
    }

    @Test
    fun `isActivelyProcessing returns true for SUBTASK_ASSIGNED and EXECUTING only`() {
        assertTrue(CollaborationLifecycleState.isActivelyProcessing(CollaborationLifecycleState.SUBTASK_ASSIGNED))
        assertTrue(CollaborationLifecycleState.isActivelyProcessing(CollaborationLifecycleState.EXECUTING))
        assertFalse(CollaborationLifecycleState.isActivelyProcessing(CollaborationLifecycleState.IDLE))
        assertFalse(CollaborationLifecycleState.isActivelyProcessing(CollaborationLifecycleState.COMPLETED))
        assertFalse(CollaborationLifecycleState.isActivelyProcessing(CollaborationLifecycleState.FAILED))
        assertFalse(CollaborationLifecycleState.isActivelyProcessing(CollaborationLifecycleState.CANCELLED))
    }

    @Test
    fun `isTerminal returns true for COMPLETED FAILED CANCELLED`() {
        assertTrue(CollaborationLifecycleState.isTerminal(CollaborationLifecycleState.COMPLETED))
        assertTrue(CollaborationLifecycleState.isTerminal(CollaborationLifecycleState.FAILED))
        assertTrue(CollaborationLifecycleState.isTerminal(CollaborationLifecycleState.CANCELLED))
        assertFalse(CollaborationLifecycleState.isTerminal(CollaborationLifecycleState.IDLE))
        assertFalse(CollaborationLifecycleState.isTerminal(CollaborationLifecycleState.SUBTASK_ASSIGNED))
        assertFalse(CollaborationLifecycleState.isTerminal(CollaborationLifecycleState.EXECUTING))
    }

    @Test
    fun `fromExecutionStatus maps SUCCESS to COMPLETED FAILURE to FAILED CANCELLED to CANCELLED BLOCKED to CANCELLED`() {
        assertEquals(
            CollaborationLifecycleState.COMPLETED,
            CollaborationLifecycleState.fromExecutionStatus(StagedMeshParticipationResult.ExecutionStatus.SUCCESS)
        )
        assertEquals(
            CollaborationLifecycleState.FAILED,
            CollaborationLifecycleState.fromExecutionStatus(StagedMeshParticipationResult.ExecutionStatus.FAILURE)
        )
        assertEquals(
            CollaborationLifecycleState.CANCELLED,
            CollaborationLifecycleState.fromExecutionStatus(StagedMeshParticipationResult.ExecutionStatus.CANCELLED)
        )
        assertEquals(
            CollaborationLifecycleState.CANCELLED,
            CollaborationLifecycleState.fromExecutionStatus(StagedMeshParticipationResult.ExecutionStatus.BLOCKED)
        )
    }

    // ── AndroidMeshParticipationRuntimeContract.derive tests ─────────────────

    @Test
    fun `derive returns ACTIVE report for healthy unconstrained participant`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.ACTIVE, report.participationLifecycle)
        assertEquals(BarrierParticipationState.NOT_APPLICABLE, report.barrierState)
        assertEquals(CollaborationLifecycleState.IDLE, report.collaborationLifecycle)
        assertTrue(report.constrainedReasons.isEmpty())
        assertTrue(report.isParticipationReady)
        assertTrue(report.isRuntimeEngaged.not())
        assertTrue(report.isRuntimeClosed.not())
    }

    @Test
    fun `derive returns DEGRADED report with HEALTH_DEGRADED reason`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.DEGRADED,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.DEGRADED, report.participationLifecycle)
        assertTrue(report.constrainedReasons.contains(AndroidMeshParticipationRuntimeContract.REASON_HEALTH_DEGRADED))
    }

    @Test
    fun `derive returns CONSTRAINED report with DELEGATED_EXECUTION_NOT_ALLOWED reason`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = false),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.CONSTRAINED, report.participationLifecycle)
        assertTrue(report.constrainedReasons.contains(AndroidMeshParticipationRuntimeContract.REASON_DELEGATED_EXECUTION_NOT_ALLOWED))
    }

    @Test
    fun `derive returns FALLBACK report with FALLBACK_ACTIVE reason`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = true,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.FALLBACK, report.participationLifecycle)
        assertTrue(report.constrainedReasons.contains(AndroidMeshParticipationRuntimeContract.REASON_FALLBACK_ACTIVE))
    }

    @Test
    fun `derive returns INACTIVE report with CROSS_DEVICE_NOT_ALLOWED reason`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = false, delegatedExecutionAllowed = false),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(MeshParticipationLifecycleState.INACTIVE, report.participationLifecycle)
        assertTrue(report.constrainedReasons.contains(AndroidMeshParticipationRuntimeContract.REASON_CROSS_DEVICE_NOT_ALLOWED))
    }

    @Test
    fun `derive captures barrier timed out reason in constrained reasons`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.TIMED_OUT,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertTrue(report.constrainedReasons.contains(AndroidMeshParticipationRuntimeContract.REASON_BARRIER_TIMED_OUT))
    }

    @Test
    fun `derive preserves collaboration lifecycle state in report`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.EXECUTING,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals(CollaborationLifecycleState.EXECUTING, report.collaborationLifecycle)
        assertTrue(report.isRuntimeEngaged)
        assertTrue(report.isRuntimeClosed.not())
    }

    @Test
    fun `report distinguishes participation-ready from runtime-closed`() {
        val ready = AndroidMeshParticipationRuntimeContract.MeshRuntimeStateReport(
            participationLifecycle = MeshParticipationLifecycleState.ACTIVE,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationLifecycle = CollaborationLifecycleState.SUBTASK_ASSIGNED,
            constrainedReasons = emptyList(),
            proofQuality = MeshRuntimeProofQuality.LIVE
        )
        assertTrue(ready.isParticipationReady)
        assertTrue(ready.isRuntimeClosed.not())

        val closed = AndroidMeshParticipationRuntimeContract.MeshRuntimeStateReport(
            participationLifecycle = MeshParticipationLifecycleState.LEAVING,
            barrierState = BarrierParticipationState.RELEASED,
            collaborationLifecycle = CollaborationLifecycleState.COMPLETED,
            constrainedReasons = emptyList()
        )
        assertTrue(closed.isParticipationReady.not())
        assertTrue(closed.isRuntimeClosed)
    }

    @Test
    fun `fallback and barrier-timeout path is runtime-closed after terminal collaboration`() {
        val report = AndroidMeshParticipationRuntimeContract.MeshRuntimeStateReport(
            participationLifecycle = MeshParticipationLifecycleState.FALLBACK,
            barrierState = BarrierParticipationState.TIMED_OUT,
            collaborationLifecycle = CollaborationLifecycleState.FAILED,
            constrainedReasons = listOf(
                AndroidMeshParticipationRuntimeContract.REASON_FALLBACK_ACTIVE,
                AndroidMeshParticipationRuntimeContract.REASON_BARRIER_TIMED_OUT
            )
        )
        assertTrue(report.isParticipationReady.not())
        assertTrue(report.isRuntimeEngaged.not())
        assertTrue(report.isRuntimeClosed)
    }

    // ── Wire-map correctness ──────────────────────────────────────────────────

    @Test
    fun `toWireMap contains all required keys with correct wire values`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.HEALTHY,
            barrierState = BarrierParticipationState.NOT_APPLICABLE,
            collaborationState = CollaborationLifecycleState.IDLE,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        val wireMap = report.toWireMap()

        assertEquals("active", wireMap[AndroidMeshParticipationRuntimeContract.KEY_PARTICIPATION_LIFECYCLE])
        assertEquals("not_applicable", wireMap[AndroidMeshParticipationRuntimeContract.KEY_BARRIER_STATE])
        assertEquals("idle", wireMap[AndroidMeshParticipationRuntimeContract.KEY_COLLABORATION_LIFECYCLE])
        assertNotNull(wireMap[AndroidMeshParticipationRuntimeContract.KEY_CONSTRAINED_REASONS])
        assertEquals(true, wireMap[AndroidMeshParticipationRuntimeContract.KEY_PARTICIPATION_READY])
        assertEquals(false, wireMap[AndroidMeshParticipationRuntimeContract.KEY_RUNTIME_ENGAGED])
        assertEquals(false, wireMap[AndroidMeshParticipationRuntimeContract.KEY_RUNTIME_CLOSED])
        val reasons = wireMap[AndroidMeshParticipationRuntimeContract.KEY_CONSTRAINED_REASONS] as List<*>
        assertTrue(reasons.isEmpty())
    }

    @Test
    fun `toWireMap for barrier-waiting degraded report carries both barrier and degraded semantics`() {
        val report = AndroidMeshParticipationRuntimeContract.derive(
            rollout = rollout(crossDeviceAllowed = true, delegatedExecutionAllowed = true),
            healthState = ParticipantHealthState.DEGRADED,
            barrierState = BarrierParticipationState.WAITING,
            collaborationState = CollaborationLifecycleState.EXECUTING,
            fallbackActive = false,
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        val wireMap = report.toWireMap()

        // Degraded health → BARRIER_WAITING (barrier takes precedence in derive())
        assertEquals("barrier_waiting", wireMap[AndroidMeshParticipationRuntimeContract.KEY_PARTICIPATION_LIFECYCLE])
        assertEquals("waiting", wireMap[AndroidMeshParticipationRuntimeContract.KEY_BARRIER_STATE])
        assertEquals("executing", wireMap[AndroidMeshParticipationRuntimeContract.KEY_COLLABORATION_LIFECYCLE])
        assertEquals(false, wireMap[AndroidMeshParticipationRuntimeContract.KEY_PARTICIPATION_READY])
        assertEquals(true, wireMap[AndroidMeshParticipationRuntimeContract.KEY_RUNTIME_ENGAGED])
        assertEquals(false, wireMap[AndroidMeshParticipationRuntimeContract.KEY_RUNTIME_CLOSED])
        val reasons = wireMap[AndroidMeshParticipationRuntimeContract.KEY_CONSTRAINED_REASONS] as List<*>
        assertTrue(reasons.contains(AndroidMeshParticipationRuntimeContract.REASON_HEALTH_DEGRADED))
    }

    // ── Center-side alignment ─────────────────────────────────────────────────

    @Test
    fun `CENTER_SIDE_ALIGNMENT covers all MeshParticipationLifecycleState entries`() {
        val alignment = AndroidMeshParticipationRuntimeContract.CENTER_SIDE_ALIGNMENT
        MeshParticipationLifecycleState.entries.forEach { state ->
            assertTrue(
                "Missing center-side alignment for state: ${state.wireValue}",
                alignment.containsKey(state.wireValue)
            )
        }
    }

    @Test
    fun `CENTER_SIDE_ALIGNMENT values reference v2_mesh_node prefix for V2 contract alignment`() {
        val alignment = AndroidMeshParticipationRuntimeContract.CENTER_SIDE_ALIGNMENT
        alignment.values.forEach { semanticValue ->
            assertTrue(
                "Center-side alignment value should start with v2_mesh_node: $semanticValue",
                semanticValue.startsWith("v2_mesh_node_")
            )
        }
    }

    @Test
    fun `fromWireValue round-trips all MeshParticipationLifecycleState entries`() {
        MeshParticipationLifecycleState.entries.forEach { state ->
            assertEquals(state, MeshParticipationLifecycleState.fromWireValue(state.wireValue))
        }
    }

    @Test
    fun `fromWireValue returns null for unknown wire value`() {
        assertEquals(null, MeshParticipationLifecycleState.fromWireValue("unknown_xyz"))
    }

    @Test
    fun `fromWireValue round-trips all BarrierParticipationState entries`() {
        BarrierParticipationState.entries.forEach { state ->
            assertEquals(state, BarrierParticipationState.fromWireValue(state.wireValue))
        }
    }

    @Test
    fun `fromWireValue round-trips all CollaborationLifecycleState entries`() {
        CollaborationLifecycleState.entries.forEach { state ->
            assertEquals(state, CollaborationLifecycleState.fromWireValue(state.wireValue))
        }
    }

    // ── Helper builders ───────────────────────────────────────────────────────

    private fun rollout(
        crossDeviceAllowed: Boolean,
        delegatedExecutionAllowed: Boolean
    ) = RolloutControlSnapshot(
        crossDeviceAllowed = crossDeviceAllowed,
        delegatedExecutionAllowed = delegatedExecutionAllowed,
        fallbackToLocalAllowed = true,
        goalExecutionAllowed = true
    )
}
