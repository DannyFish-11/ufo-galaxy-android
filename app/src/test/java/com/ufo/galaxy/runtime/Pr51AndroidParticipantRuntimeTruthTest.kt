package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-51 — Android Participant Runtime Truth and Reconciliation Signal.
 *
 * Regression and acceptance test suite for all PR-51 additions:
 *
 *  1. [AndroidParticipantRuntimeTruth] data class — consolidated participant-level runtime
 *     truth snapshot with stable wire-key constants, [ActiveTaskStatus] enum, [toMap]
 *     serialization, [isFullyReconcilable], [isAttachedAndEligible], [hasActiveTask] helpers,
 *     and [AndroidParticipantRuntimeTruth.from] factory.
 *
 *  2. [ActiveTaskStatus] enum — in-flight task status (RUNNING, PENDING, CANCELLING,
 *     FAILING) with stable wire values, [fromValue] parser, and [ALL_WIRE_VALUES] set.
 *
 *  3. [ReconciliationSignal] data class — structured Android→V2 reconciliation signal
 *     wrapper covering all task and participant lifecycle phases, with [Kind] enum,
 *     stable wire-key constants, factory helpers ([taskAccepted], [taskCancelled],
 *     [taskFailed], [taskResult], [runtimeTruthSnapshot]), and [isTerminal] /
 *     [hasRuntimeTruth] helpers.
 *
 *  4. [StabilizationBaseline] entries for three PR-51 surfaces.
 *
 * ## Test matrix
 *
 * ### ActiveTaskStatus — wire values
 *  - RUNNING wireValue is "running"
 *  - PENDING wireValue is "pending"
 *  - CANCELLING wireValue is "cancelling"
 *  - FAILING wireValue is "failing"
 *  - ALL_WIRE_VALUES has exactly four entries
 *  - all four wire values are distinct
 *  - fromValue returns correct enum for each wire value
 *  - fromValue returns null for unknown value
 *  - fromValue returns null for null input
 *
 * ### AndroidParticipantRuntimeTruth — construction and fields
 *  - participantId is preserved
 *  - deviceId is preserved
 *  - hostId is preserved
 *  - participationState is preserved
 *  - coordinationRole is preserved
 *  - sourceRuntimePosture is preserved
 *  - sessionId may be null
 *  - sessionState may be null
 *  - delegatedExecutionCount is preserved
 *  - healthState is preserved
 *  - readinessState is preserved
 *  - activeTaskId may be null
 *  - activeTaskStatus may be null
 *  - reportedAtMs is preserved
 *  - reconciliationEpoch is preserved
 *
 * ### AndroidParticipantRuntimeTruth — isFullyReconcilable
 *  - ACTIVE + HEALTHY + READY + non-blank participantId → isFullyReconcilable true
 *  - INACTIVE participationState → isFullyReconcilable false
 *  - UNKNOWN healthState → isFullyReconcilable false
 *  - UNKNOWN readinessState → isFullyReconcilable false
 *  - blank participantId → isFullyReconcilable false
 *
 * ### AndroidParticipantRuntimeTruth — isAttachedAndEligible
 *  - ATTACHED session + ACTIVE + HEALTHY → isAttachedAndEligible true
 *  - DETACHED session → isAttachedAndEligible false
 *  - ACTIVE + HEALTHY but sessionState null → isAttachedAndEligible false
 *  - STANDBY participationState → isAttachedAndEligible false
 *  - DEGRADED healthState → isAttachedAndEligible false
 *
 * ### AndroidParticipantRuntimeTruth — hasActiveTask
 *  - activeTaskId non-null and activeTaskStatus non-null → hasActiveTask true
 *  - activeTaskId null → hasActiveTask false
 *  - activeTaskStatus null → hasActiveTask false
 *
 * ### AndroidParticipantRuntimeTruth — toMap
 *  - always-present keys are included
 *  - participantId value matches key
 *  - sessionId absent when null
 *  - sessionId present when non-null
 *  - sessionState absent when null
 *  - sessionState present when non-null
 *  - activeTaskId absent when null
 *  - activeTaskId present when non-null
 *  - activeTaskStatus absent when null
 *  - activeTaskStatus present when non-null
 *  - isFullyReconcilable pre-computed boolean is in map
 *
 * ### AndroidParticipantRuntimeTruth — from factory
 *  - participantId built from deviceId and hostId
 *  - coordinationRole PRIMARY → COORDINATOR
 *  - coordinationRole SECONDARY → PARTICIPANT
 *  - coordinationRole SATELLITE → PARTICIPANT
 *  - sourceRuntimePosture echoed from sessionSnapshot posture
 *  - sourceRuntimePosture defaults to CONTROL_ONLY when no sessionSnapshot
 *  - sessionId echoed from sessionSnapshot
 *  - sessionId null when no sessionSnapshot
 *  - sessionState echoed from sessionSnapshot attachmentState
 *  - delegatedExecutionCount echoed from sessionSnapshot
 *
 * ### ReconciliationSignal.Kind — wire values
 *  - TASK_ACCEPTED wireValue is "task_accepted"
 *  - TASK_STATUS_UPDATE wireValue is "task_status_update"
 *  - TASK_RESULT wireValue is "task_result"
 *  - TASK_CANCELLED wireValue is "task_cancelled"
 *  - TASK_FAILED wireValue is "task_failed"
 *  - PARTICIPANT_STATE wireValue is "participant_state"
 *  - RUNTIME_TRUTH_SNAPSHOT wireValue is "runtime_truth_snapshot"
 *  - ALL_WIRE_VALUES has exactly seven entries
 *  - all seven wire values are distinct
 *  - fromValue returns correct Kind for each wire value
 *  - fromValue returns null for unknown value
 *
 * ### ReconciliationSignal — isTerminal
 *  - TASK_RESULT → isTerminal true
 *  - TASK_CANCELLED → isTerminal true
 *  - TASK_FAILED → isTerminal true
 *  - TASK_ACCEPTED → isTerminal false
 *  - TASK_STATUS_UPDATE → isTerminal false
 *  - PARTICIPANT_STATE → isTerminal false
 *  - RUNTIME_TRUTH_SNAPSHOT → isTerminal false
 *
 * ### ReconciliationSignal — hasRuntimeTruth
 *  - RUNTIME_TRUTH_SNAPSHOT with non-null runtimeTruth → hasRuntimeTruth true
 *  - RUNTIME_TRUTH_SNAPSHOT with null runtimeTruth → hasRuntimeTruth false
 *  - TASK_RESULT → hasRuntimeTruth false
 *
 * ### ReconciliationSignal — factory helpers
 *  - taskAccepted produces Kind.TASK_ACCEPTED
 *  - taskAccepted status is STATUS_RUNNING
 *  - taskCancelled produces Kind.TASK_CANCELLED
 *  - taskCancelled status is STATUS_CANCELLED
 *  - taskFailed produces Kind.TASK_FAILED
 *  - taskFailed status is STATUS_FAILED
 *  - taskFailed payload contains error_detail when provided
 *  - taskResult produces Kind.TASK_RESULT
 *  - taskResult status is STATUS_SUCCESS
 *  - runtimeTruthSnapshot produces Kind.RUNTIME_TRUTH_SNAPSHOT
 *  - runtimeTruthSnapshot status is STATUS_SNAPSHOT
 *  - runtimeTruthSnapshot runtimeTruth is set
 *  - runtimeTruthSnapshot participantId matches truth participantId
 *
 * ### ReconciliationSignal — wire key constants
 *  - KEY_KIND is "reconciliation_signal_kind"
 *  - KEY_PARTICIPANT_ID is "reconciliation_participant_id"
 *  - KEY_TASK_ID is "reconciliation_task_id"
 *  - KEY_STATUS is "reconciliation_status"
 *  - KEY_SIGNAL_ID is "reconciliation_signal_id"
 *  - KEY_EMITTED_AT_MS is "reconciliation_emitted_at_ms"
 *  - KEY_RECONCILIATION_EPOCH is "reconciliation_epoch"
 *  - all key constants are distinct
 *
 * ### AndroidParticipantRuntimeTruth — wire key constants
 *  - KEY_PARTICIPANT_ID is "participant_id"
 *  - KEY_DEVICE_ID is "device_id"
 *  - KEY_HOST_ID is "host_id"
 *  - KEY_PARTICIPATION_STATE is "participation_state"
 *  - KEY_COORDINATION_ROLE is "coordination_role"
 *  - KEY_SOURCE_RUNTIME_POSTURE is "source_runtime_posture"
 *  - KEY_SESSION_ID is "session_id"
 *  - KEY_SESSION_STATE is "session_state"
 *  - KEY_DELEGATED_EXECUTION_COUNT is "delegated_execution_count"
 *  - KEY_HEALTH_STATE is "health_state"
 *  - KEY_READINESS_STATE is "readiness_state"
 *  - KEY_ACTIVE_TASK_ID is "active_task_id"
 *  - KEY_ACTIVE_TASK_STATUS is "active_task_status"
 *  - KEY_REPORTED_AT_MS is "reported_at_ms"
 *  - KEY_RECONCILIATION_EPOCH is "reconciliation_epoch"
 *  - KEY_IS_FULLY_RECONCILABLE is "is_fully_reconcilable"
 *  - all key constants are distinct
 *
 * ### StabilizationBaseline — PR-51 entries
 *  - exactly three entries with introducedPr == 51
 *  - android-participant-runtime-truth is CANONICAL_STABLE with EXTEND guidance
 *  - active-task-status is CANONICAL_STABLE with EXTEND guidance
 *  - reconciliation-signal is CANONICAL_STABLE with EXTEND guidance
 *  - all PR-51 entries have non-blank rationale
 *  - all PR-51 entries reference com.ufo.galaxy packagePath
 */
class Pr51AndroidParticipantRuntimeTruthTest {

    // ── Test helpers ──────────────────────────────────────────────────────────

    private fun activeDescriptor(
        deviceId: String = "Pixel_8",
        hostId: String = "host-001",
        role: RuntimeHostDescriptor.FormationRole = RuntimeHostDescriptor.FormationRole.PRIMARY
    ): RuntimeHostDescriptor = RuntimeHostDescriptor(
        hostId = hostId,
        deviceId = deviceId,
        deviceRole = "phone",
        formationRole = role,
        participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
    )

    private fun inactiveDescriptor(): RuntimeHostDescriptor = RuntimeHostDescriptor(
        hostId = "host-002",
        deviceId = "Pixel_7",
        deviceRole = "phone",
        formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
    )

    private fun attachedSnapshot(
        sessionId: String = "sess-001",
        deviceId: String = "Pixel_8",
        posture: String = SourceRuntimePosture.JOIN_RUNTIME,
        delegatedCount: Int = 3
    ): AttachedRuntimeHostSessionSnapshot = AttachedRuntimeHostSessionSnapshot(
        sessionId = sessionId,
        deviceId = deviceId,
        runtimeSessionId = "rt-001",
        attachmentState = AttachedRuntimeSession.State.ATTACHED.wireValue,
        isReuseValid = true,
        delegatedExecutionCount = delegatedCount,
        invalidationReason = null,
        hostRole = RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue,
        posture = posture
    )

    private fun fullyReconcilableTruth(): AndroidParticipantRuntimeTruth =
        AndroidParticipantRuntimeTruth(
            participantId = "Pixel_8:host-001",
            deviceId = "Pixel_8",
            hostId = "host-001",
            deviceRole = "phone",
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
            coordinationRole = ParticipantCoordinationRole.COORDINATOR,
            sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
            sessionId = "sess-001",
            sessionState = AttachedRuntimeSession.State.ATTACHED,
            delegatedExecutionCount = 2,
            healthState = ParticipantHealthState.HEALTHY,
            readinessState = ParticipantReadinessState.READY,
            activeTaskId = null,
            activeTaskStatus = null,
            reportedAtMs = 1000L,
            reconciliationEpoch = 5
        )

    // ── ActiveTaskStatus — wire values ────────────────────────────────────────

    @Test
    fun `ActiveTaskStatus RUNNING wireValue is running`() {
        assertEquals("running", ActiveTaskStatus.RUNNING.wireValue)
    }

    @Test
    fun `ActiveTaskStatus PENDING wireValue is pending`() {
        assertEquals("pending", ActiveTaskStatus.PENDING.wireValue)
    }

    @Test
    fun `ActiveTaskStatus CANCELLING wireValue is cancelling`() {
        assertEquals("cancelling", ActiveTaskStatus.CANCELLING.wireValue)
    }

    @Test
    fun `ActiveTaskStatus FAILING wireValue is failing`() {
        assertEquals("failing", ActiveTaskStatus.FAILING.wireValue)
    }

    @Test
    fun `ActiveTaskStatus ALL_WIRE_VALUES has exactly four entries`() {
        assertEquals(4, ActiveTaskStatus.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `ActiveTaskStatus all four wire values are distinct`() {
        assertEquals(4, ActiveTaskStatus.ALL_WIRE_VALUES.distinct().size)
    }

    @Test
    fun `ActiveTaskStatus fromValue returns RUNNING for running`() {
        assertEquals(ActiveTaskStatus.RUNNING, ActiveTaskStatus.fromValue("running"))
    }

    @Test
    fun `ActiveTaskStatus fromValue returns PENDING for pending`() {
        assertEquals(ActiveTaskStatus.PENDING, ActiveTaskStatus.fromValue("pending"))
    }

    @Test
    fun `ActiveTaskStatus fromValue returns CANCELLING for cancelling`() {
        assertEquals(ActiveTaskStatus.CANCELLING, ActiveTaskStatus.fromValue("cancelling"))
    }

    @Test
    fun `ActiveTaskStatus fromValue returns FAILING for failing`() {
        assertEquals(ActiveTaskStatus.FAILING, ActiveTaskStatus.fromValue("failing"))
    }

    @Test
    fun `ActiveTaskStatus fromValue returns null for unknown value`() {
        assertNull(ActiveTaskStatus.fromValue("unknown_status"))
    }

    @Test
    fun `ActiveTaskStatus fromValue returns null for null input`() {
        assertNull(ActiveTaskStatus.fromValue(null))
    }

    // ── AndroidParticipantRuntimeTruth — construction and fields ─────────────

    @Test
    fun `AndroidParticipantRuntimeTruth participantId is preserved`() {
        val truth = fullyReconcilableTruth()
        assertEquals("Pixel_8:host-001", truth.participantId)
    }

    @Test
    fun `AndroidParticipantRuntimeTruth deviceId is preserved`() {
        val truth = fullyReconcilableTruth()
        assertEquals("Pixel_8", truth.deviceId)
    }

    @Test
    fun `AndroidParticipantRuntimeTruth hostId is preserved`() {
        val truth = fullyReconcilableTruth()
        assertEquals("host-001", truth.hostId)
    }

    @Test
    fun `AndroidParticipantRuntimeTruth participationState is preserved`() {
        val truth = fullyReconcilableTruth()
        assertEquals(RuntimeHostDescriptor.HostParticipationState.ACTIVE, truth.participationState)
    }

    @Test
    fun `AndroidParticipantRuntimeTruth coordinationRole is preserved`() {
        val truth = fullyReconcilableTruth()
        assertEquals(ParticipantCoordinationRole.COORDINATOR, truth.coordinationRole)
    }

    @Test
    fun `AndroidParticipantRuntimeTruth sourceRuntimePosture is preserved`() {
        val truth = fullyReconcilableTruth()
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, truth.sourceRuntimePosture)
    }

    @Test
    fun `AndroidParticipantRuntimeTruth sessionId may be null`() {
        val truth = fullyReconcilableTruth().copy(sessionId = null)
        assertNull(truth.sessionId)
    }

    @Test
    fun `AndroidParticipantRuntimeTruth sessionState may be null`() {
        val truth = fullyReconcilableTruth().copy(sessionState = null)
        assertNull(truth.sessionState)
    }

    @Test
    fun `AndroidParticipantRuntimeTruth delegatedExecutionCount is preserved`() {
        val truth = fullyReconcilableTruth()
        assertEquals(2, truth.delegatedExecutionCount)
    }

    @Test
    fun `AndroidParticipantRuntimeTruth healthState is preserved`() {
        val truth = fullyReconcilableTruth()
        assertEquals(ParticipantHealthState.HEALTHY, truth.healthState)
    }

    @Test
    fun `AndroidParticipantRuntimeTruth readinessState is preserved`() {
        val truth = fullyReconcilableTruth()
        assertEquals(ParticipantReadinessState.READY, truth.readinessState)
    }

    @Test
    fun `AndroidParticipantRuntimeTruth activeTaskId may be null`() {
        val truth = fullyReconcilableTruth()
        assertNull(truth.activeTaskId)
    }

    @Test
    fun `AndroidParticipantRuntimeTruth activeTaskStatus may be null`() {
        val truth = fullyReconcilableTruth()
        assertNull(truth.activeTaskStatus)
    }

    @Test
    fun `AndroidParticipantRuntimeTruth reportedAtMs is preserved`() {
        val truth = fullyReconcilableTruth()
        assertEquals(1000L, truth.reportedAtMs)
    }

    @Test
    fun `AndroidParticipantRuntimeTruth reconciliationEpoch is preserved`() {
        val truth = fullyReconcilableTruth()
        assertEquals(5, truth.reconciliationEpoch)
    }

    // ── AndroidParticipantRuntimeTruth — isFullyReconcilable ─────────────────

    @Test
    fun `isFullyReconcilable is true when ACTIVE HEALTHY READY and non-blank participantId`() {
        assertTrue(fullyReconcilableTruth().isFullyReconcilable)
    }

    @Test
    fun `isFullyReconcilable is false when participationState is INACTIVE`() {
        val truth = fullyReconcilableTruth().copy(
            participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
        )
        assertFalse(truth.isFullyReconcilable)
    }

    @Test
    fun `isFullyReconcilable is false when healthState is UNKNOWN`() {
        val truth = fullyReconcilableTruth().copy(healthState = ParticipantHealthState.UNKNOWN)
        assertFalse(truth.isFullyReconcilable)
    }

    @Test
    fun `isFullyReconcilable is false when readinessState is UNKNOWN`() {
        val truth = fullyReconcilableTruth().copy(readinessState = ParticipantReadinessState.UNKNOWN)
        assertFalse(truth.isFullyReconcilable)
    }

    @Test
    fun `isFullyReconcilable is false when participantId is blank`() {
        val truth = fullyReconcilableTruth().copy(participantId = "")
        assertFalse(truth.isFullyReconcilable)
    }

    // ── AndroidParticipantRuntimeTruth — isAttachedAndEligible ───────────────

    @Test
    fun `isAttachedAndEligible is true when ATTACHED ACTIVE HEALTHY`() {
        val truth = fullyReconcilableTruth()
        assertTrue(truth.isAttachedAndEligible)
    }

    @Test
    fun `isAttachedAndEligible is false when sessionState is DETACHED`() {
        val truth = fullyReconcilableTruth().copy(sessionState = AttachedRuntimeSession.State.DETACHED)
        assertFalse(truth.isAttachedAndEligible)
    }

    @Test
    fun `isAttachedAndEligible is false when sessionState is null`() {
        val truth = fullyReconcilableTruth().copy(sessionState = null)
        assertFalse(truth.isAttachedAndEligible)
    }

    @Test
    fun `isAttachedAndEligible is false when participationState is STANDBY`() {
        val truth = fullyReconcilableTruth().copy(
            participationState = RuntimeHostDescriptor.HostParticipationState.STANDBY
        )
        assertFalse(truth.isAttachedAndEligible)
    }

    @Test
    fun `isAttachedAndEligible is false when healthState is DEGRADED`() {
        val truth = fullyReconcilableTruth().copy(healthState = ParticipantHealthState.DEGRADED)
        assertFalse(truth.isAttachedAndEligible)
    }

    // ── AndroidParticipantRuntimeTruth — hasActiveTask ────────────────────────

    @Test
    fun `hasActiveTask is true when activeTaskId and activeTaskStatus are non-null`() {
        val truth = fullyReconcilableTruth().copy(
            activeTaskId = "task-001",
            activeTaskStatus = ActiveTaskStatus.RUNNING
        )
        assertTrue(truth.hasActiveTask)
    }

    @Test
    fun `hasActiveTask is false when activeTaskId is null`() {
        val truth = fullyReconcilableTruth().copy(
            activeTaskId = null,
            activeTaskStatus = ActiveTaskStatus.RUNNING
        )
        assertFalse(truth.hasActiveTask)
    }

    @Test
    fun `hasActiveTask is false when activeTaskStatus is null`() {
        val truth = fullyReconcilableTruth().copy(
            activeTaskId = "task-001",
            activeTaskStatus = null
        )
        assertFalse(truth.hasActiveTask)
    }

    // ── AndroidParticipantRuntimeTruth — toMap ────────────────────────────────

    @Test
    fun `toMap always-present keys are included`() {
        val map = fullyReconcilableTruth().toMap()
        val keys = AndroidParticipantRuntimeTruth
        assertTrue(map.containsKey(keys.KEY_PARTICIPANT_ID))
        assertTrue(map.containsKey(keys.KEY_DEVICE_ID))
        assertTrue(map.containsKey(keys.KEY_HOST_ID))
        assertTrue(map.containsKey(keys.KEY_DEVICE_ROLE))
        assertTrue(map.containsKey(keys.KEY_PARTICIPATION_STATE))
        assertTrue(map.containsKey(keys.KEY_COORDINATION_ROLE))
        assertTrue(map.containsKey(keys.KEY_SOURCE_RUNTIME_POSTURE))
        assertTrue(map.containsKey(keys.KEY_DELEGATED_EXECUTION_COUNT))
        assertTrue(map.containsKey(keys.KEY_HEALTH_STATE))
        assertTrue(map.containsKey(keys.KEY_READINESS_STATE))
        assertTrue(map.containsKey(keys.KEY_REPORTED_AT_MS))
        assertTrue(map.containsKey(keys.KEY_RECONCILIATION_EPOCH))
        assertTrue(map.containsKey(keys.KEY_IS_FULLY_RECONCILABLE))
    }

    @Test
    fun `toMap participantId value matches`() {
        val truth = fullyReconcilableTruth()
        assertEquals(truth.participantId, truth.toMap()[AndroidParticipantRuntimeTruth.KEY_PARTICIPANT_ID])
    }

    @Test
    fun `toMap sessionId absent when null`() {
        val map = fullyReconcilableTruth().copy(sessionId = null).toMap()
        assertFalse(map.containsKey(AndroidParticipantRuntimeTruth.KEY_SESSION_ID))
    }

    @Test
    fun `toMap sessionId present when non-null`() {
        val map = fullyReconcilableTruth().copy(sessionId = "sess-X").toMap()
        assertEquals("sess-X", map[AndroidParticipantRuntimeTruth.KEY_SESSION_ID])
    }

    @Test
    fun `toMap sessionState absent when null`() {
        val map = fullyReconcilableTruth().copy(sessionState = null).toMap()
        assertFalse(map.containsKey(AndroidParticipantRuntimeTruth.KEY_SESSION_STATE))
    }

    @Test
    fun `toMap sessionState present when non-null`() {
        val map = fullyReconcilableTruth().copy(
            sessionState = AttachedRuntimeSession.State.ATTACHED
        ).toMap()
        assertEquals(
            AttachedRuntimeSession.State.ATTACHED.wireValue,
            map[AndroidParticipantRuntimeTruth.KEY_SESSION_STATE]
        )
    }

    @Test
    fun `toMap activeTaskId absent when null`() {
        val map = fullyReconcilableTruth().copy(activeTaskId = null).toMap()
        assertFalse(map.containsKey(AndroidParticipantRuntimeTruth.KEY_ACTIVE_TASK_ID))
    }

    @Test
    fun `toMap activeTaskId present when non-null`() {
        val map = fullyReconcilableTruth().copy(activeTaskId = "task-abc").toMap()
        assertEquals("task-abc", map[AndroidParticipantRuntimeTruth.KEY_ACTIVE_TASK_ID])
    }

    @Test
    fun `toMap activeTaskStatus absent when null`() {
        val map = fullyReconcilableTruth().copy(activeTaskStatus = null).toMap()
        assertFalse(map.containsKey(AndroidParticipantRuntimeTruth.KEY_ACTIVE_TASK_STATUS))
    }

    @Test
    fun `toMap activeTaskStatus present when non-null`() {
        val map = fullyReconcilableTruth().copy(activeTaskStatus = ActiveTaskStatus.RUNNING).toMap()
        assertEquals(ActiveTaskStatus.RUNNING.wireValue, map[AndroidParticipantRuntimeTruth.KEY_ACTIVE_TASK_STATUS])
    }

    @Test
    fun `toMap isFullyReconcilable pre-computed boolean is in map`() {
        val truth = fullyReconcilableTruth()
        val map = truth.toMap()
        assertEquals(truth.isFullyReconcilable, map[AndroidParticipantRuntimeTruth.KEY_IS_FULLY_RECONCILABLE])
    }

    // ── AndroidParticipantRuntimeTruth — from factory ─────────────────────────

    @Test
    fun `from factory participantId built from deviceId and hostId`() {
        val descriptor = activeDescriptor(deviceId = "Pixel_8", hostId = "host-xyz")
        val truth = AndroidParticipantRuntimeTruth.from(descriptor)
        assertEquals("Pixel_8:host-xyz", truth.participantId)
    }

    @Test
    fun `from factory coordinationRole PRIMARY produces COORDINATOR`() {
        val descriptor = activeDescriptor(role = RuntimeHostDescriptor.FormationRole.PRIMARY)
        val truth = AndroidParticipantRuntimeTruth.from(descriptor)
        assertEquals(ParticipantCoordinationRole.COORDINATOR, truth.coordinationRole)
    }

    @Test
    fun `from factory coordinationRole SECONDARY produces PARTICIPANT`() {
        val descriptor = activeDescriptor(role = RuntimeHostDescriptor.FormationRole.SECONDARY)
        val truth = AndroidParticipantRuntimeTruth.from(descriptor)
        assertEquals(ParticipantCoordinationRole.PARTICIPANT, truth.coordinationRole)
    }

    @Test
    fun `from factory coordinationRole SATELLITE produces PARTICIPANT`() {
        val descriptor = activeDescriptor(role = RuntimeHostDescriptor.FormationRole.SATELLITE)
        val truth = AndroidParticipantRuntimeTruth.from(descriptor)
        assertEquals(ParticipantCoordinationRole.PARTICIPANT, truth.coordinationRole)
    }

    @Test
    fun `from factory sourceRuntimePosture echoed from sessionSnapshot posture`() {
        val descriptor = activeDescriptor()
        val snapshot = attachedSnapshot(posture = SourceRuntimePosture.JOIN_RUNTIME)
        val truth = AndroidParticipantRuntimeTruth.from(descriptor, sessionSnapshot = snapshot)
        assertEquals(SourceRuntimePosture.JOIN_RUNTIME, truth.sourceRuntimePosture)
    }

    @Test
    fun `from factory sourceRuntimePosture defaults to CONTROL_ONLY when no sessionSnapshot`() {
        val descriptor = activeDescriptor()
        val truth = AndroidParticipantRuntimeTruth.from(descriptor)
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, truth.sourceRuntimePosture)
    }

    @Test
    fun `from factory sessionId echoed from sessionSnapshot`() {
        val descriptor = activeDescriptor()
        val snapshot = attachedSnapshot(sessionId = "sess-from-snapshot")
        val truth = AndroidParticipantRuntimeTruth.from(descriptor, sessionSnapshot = snapshot)
        assertEquals("sess-from-snapshot", truth.sessionId)
    }

    @Test
    fun `from factory sessionId null when no sessionSnapshot`() {
        val descriptor = activeDescriptor()
        val truth = AndroidParticipantRuntimeTruth.from(descriptor)
        assertNull(truth.sessionId)
    }

    @Test
    fun `from factory sessionState echoed from sessionSnapshot attachmentState`() {
        val descriptor = activeDescriptor()
        val snapshot = attachedSnapshot()
        val truth = AndroidParticipantRuntimeTruth.from(descriptor, sessionSnapshot = snapshot)
        assertEquals(AttachedRuntimeSession.State.ATTACHED, truth.sessionState)
    }

    @Test
    fun `from factory delegatedExecutionCount echoed from sessionSnapshot`() {
        val descriptor = activeDescriptor()
        val snapshot = attachedSnapshot(delegatedCount = 7)
        val truth = AndroidParticipantRuntimeTruth.from(descriptor, sessionSnapshot = snapshot)
        assertEquals(7, truth.delegatedExecutionCount)
    }

    // ── ReconciliationSignal.Kind — wire values ───────────────────────────────

    @Test
    fun `ReconciliationSignal Kind TASK_ACCEPTED wireValue is task_accepted`() {
        assertEquals("task_accepted", ReconciliationSignal.Kind.TASK_ACCEPTED.wireValue)
    }

    @Test
    fun `ReconciliationSignal Kind TASK_STATUS_UPDATE wireValue is task_status_update`() {
        assertEquals("task_status_update", ReconciliationSignal.Kind.TASK_STATUS_UPDATE.wireValue)
    }

    @Test
    fun `ReconciliationSignal Kind TASK_RESULT wireValue is task_result`() {
        assertEquals("task_result", ReconciliationSignal.Kind.TASK_RESULT.wireValue)
    }

    @Test
    fun `ReconciliationSignal Kind TASK_CANCELLED wireValue is task_cancelled`() {
        assertEquals("task_cancelled", ReconciliationSignal.Kind.TASK_CANCELLED.wireValue)
    }

    @Test
    fun `ReconciliationSignal Kind TASK_FAILED wireValue is task_failed`() {
        assertEquals("task_failed", ReconciliationSignal.Kind.TASK_FAILED.wireValue)
    }

    @Test
    fun `ReconciliationSignal Kind PARTICIPANT_STATE wireValue is participant_state`() {
        assertEquals("participant_state", ReconciliationSignal.Kind.PARTICIPANT_STATE.wireValue)
    }

    @Test
    fun `ReconciliationSignal Kind RUNTIME_TRUTH_SNAPSHOT wireValue is runtime_truth_snapshot`() {
        assertEquals("runtime_truth_snapshot", ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT.wireValue)
    }

    @Test
    fun `ReconciliationSignal Kind ALL_WIRE_VALUES has exactly seven entries`() {
        assertEquals(7, ReconciliationSignal.Kind.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `ReconciliationSignal Kind all seven wire values are distinct`() {
        assertEquals(7, ReconciliationSignal.Kind.ALL_WIRE_VALUES.distinct().size)
    }

    @Test
    fun `ReconciliationSignal Kind fromValue returns correct Kind for each wire value`() {
        ReconciliationSignal.Kind.entries.forEach { kind ->
            assertEquals(kind, ReconciliationSignal.Kind.fromValue(kind.wireValue))
        }
    }

    @Test
    fun `ReconciliationSignal Kind fromValue returns null for unknown value`() {
        assertNull(ReconciliationSignal.Kind.fromValue("unknown_kind"))
    }

    // ── ReconciliationSignal — isTerminal ─────────────────────────────────────

    @Test
    fun `isTerminal is true for TASK_RESULT`() {
        val signal = ReconciliationSignal.taskResult("pid", "tid")
        assertTrue(signal.isTerminal)
    }

    @Test
    fun `isTerminal is true for TASK_CANCELLED`() {
        val signal = ReconciliationSignal.taskCancelled("pid", "tid")
        assertTrue(signal.isTerminal)
    }

    @Test
    fun `isTerminal is true for TASK_FAILED`() {
        val signal = ReconciliationSignal.taskFailed("pid", "tid")
        assertTrue(signal.isTerminal)
    }

    @Test
    fun `isTerminal is false for TASK_ACCEPTED`() {
        val signal = ReconciliationSignal.taskAccepted("pid", "tid")
        assertFalse(signal.isTerminal)
    }

    @Test
    fun `isTerminal is false for TASK_STATUS_UPDATE`() {
        val signal = ReconciliationSignal(
            kind = ReconciliationSignal.Kind.TASK_STATUS_UPDATE,
            participantId = "pid",
            taskId = "tid",
            correlationId = null,
            status = ReconciliationSignal.STATUS_IN_PROGRESS,
            signalId = "sig-1",
            emittedAtMs = 1000L,
            reconciliationEpoch = 0
        )
        assertFalse(signal.isTerminal)
    }

    @Test
    fun `isTerminal is false for PARTICIPANT_STATE`() {
        val signal = ReconciliationSignal(
            kind = ReconciliationSignal.Kind.PARTICIPANT_STATE,
            participantId = "pid",
            taskId = null,
            correlationId = null,
            status = ReconciliationSignal.STATUS_STATE_CHANGED,
            signalId = "sig-2",
            emittedAtMs = 1000L,
            reconciliationEpoch = 0
        )
        assertFalse(signal.isTerminal)
    }

    @Test
    fun `isTerminal is false for RUNTIME_TRUTH_SNAPSHOT`() {
        val signal = ReconciliationSignal.runtimeTruthSnapshot(fullyReconcilableTruth())
        assertFalse(signal.isTerminal)
    }

    // ── ReconciliationSignal — hasRuntimeTruth ────────────────────────────────

    @Test
    fun `hasRuntimeTruth is true for RUNTIME_TRUTH_SNAPSHOT with non-null runtimeTruth`() {
        val signal = ReconciliationSignal.runtimeTruthSnapshot(fullyReconcilableTruth())
        assertTrue(signal.hasRuntimeTruth)
    }

    @Test
    fun `hasRuntimeTruth is false for RUNTIME_TRUTH_SNAPSHOT with null runtimeTruth`() {
        val signal = ReconciliationSignal(
            kind = ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT,
            participantId = "pid",
            taskId = null,
            correlationId = null,
            status = ReconciliationSignal.STATUS_SNAPSHOT,
            runtimeTruth = null,
            signalId = "sig-3",
            emittedAtMs = 1000L,
            reconciliationEpoch = 0
        )
        assertFalse(signal.hasRuntimeTruth)
    }

    @Test
    fun `hasRuntimeTruth is false for TASK_RESULT`() {
        val signal = ReconciliationSignal.taskResult("pid", "tid")
        assertFalse(signal.hasRuntimeTruth)
    }

    // ── ReconciliationSignal — factory helpers ────────────────────────────────

    @Test
    fun `taskAccepted produces Kind TASK_ACCEPTED`() {
        val signal = ReconciliationSignal.taskAccepted("pid", "tid")
        assertEquals(ReconciliationSignal.Kind.TASK_ACCEPTED, signal.kind)
    }

    @Test
    fun `taskAccepted status is STATUS_RUNNING`() {
        val signal = ReconciliationSignal.taskAccepted("pid", "tid")
        assertEquals(ReconciliationSignal.STATUS_RUNNING, signal.status)
    }

    @Test
    fun `taskAccepted participantId and taskId are set`() {
        val signal = ReconciliationSignal.taskAccepted("my-pid", "my-tid")
        assertEquals("my-pid", signal.participantId)
        assertEquals("my-tid", signal.taskId)
    }

    @Test
    fun `taskCancelled produces Kind TASK_CANCELLED`() {
        val signal = ReconciliationSignal.taskCancelled("pid", "tid")
        assertEquals(ReconciliationSignal.Kind.TASK_CANCELLED, signal.kind)
    }

    @Test
    fun `taskCancelled status is STATUS_CANCELLED`() {
        val signal = ReconciliationSignal.taskCancelled("pid", "tid")
        assertEquals(ReconciliationSignal.STATUS_CANCELLED, signal.status)
    }

    @Test
    fun `taskFailed produces Kind TASK_FAILED`() {
        val signal = ReconciliationSignal.taskFailed("pid", "tid")
        assertEquals(ReconciliationSignal.Kind.TASK_FAILED, signal.kind)
    }

    @Test
    fun `taskFailed status is STATUS_FAILED`() {
        val signal = ReconciliationSignal.taskFailed("pid", "tid")
        assertEquals(ReconciliationSignal.STATUS_FAILED, signal.status)
    }

    @Test
    fun `taskFailed payload contains error_detail when provided`() {
        val signal = ReconciliationSignal.taskFailed("pid", "tid", errorDetail = "pipeline_fault")
        assertEquals("pipeline_fault", signal.payload["error_detail"])
    }

    @Test
    fun `taskResult produces Kind TASK_RESULT`() {
        val signal = ReconciliationSignal.taskResult("pid", "tid")
        assertEquals(ReconciliationSignal.Kind.TASK_RESULT, signal.kind)
    }

    @Test
    fun `taskResult status is STATUS_SUCCESS`() {
        val signal = ReconciliationSignal.taskResult("pid", "tid")
        assertEquals(ReconciliationSignal.STATUS_SUCCESS, signal.status)
    }

    @Test
    fun `runtimeTruthSnapshot produces Kind RUNTIME_TRUTH_SNAPSHOT`() {
        val signal = ReconciliationSignal.runtimeTruthSnapshot(fullyReconcilableTruth())
        assertEquals(ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT, signal.kind)
    }

    @Test
    fun `runtimeTruthSnapshot status is STATUS_SNAPSHOT`() {
        val signal = ReconciliationSignal.runtimeTruthSnapshot(fullyReconcilableTruth())
        assertEquals(ReconciliationSignal.STATUS_SNAPSHOT, signal.status)
    }

    @Test
    fun `runtimeTruthSnapshot runtimeTruth is set`() {
        val truth = fullyReconcilableTruth()
        val signal = ReconciliationSignal.runtimeTruthSnapshot(truth)
        assertNotNull(signal.runtimeTruth)
        assertEquals(truth, signal.runtimeTruth)
    }

    @Test
    fun `runtimeTruthSnapshot participantId matches truth participantId`() {
        val truth = fullyReconcilableTruth()
        val signal = ReconciliationSignal.runtimeTruthSnapshot(truth)
        assertEquals(truth.participantId, signal.participantId)
    }

    // ── ReconciliationSignal — wire key constants ─────────────────────────────

    @Test
    fun `ReconciliationSignal KEY_KIND is reconciliation_signal_kind`() {
        assertEquals("reconciliation_signal_kind", ReconciliationSignal.KEY_KIND)
    }

    @Test
    fun `ReconciliationSignal KEY_PARTICIPANT_ID is reconciliation_participant_id`() {
        assertEquals("reconciliation_participant_id", ReconciliationSignal.KEY_PARTICIPANT_ID)
    }

    @Test
    fun `ReconciliationSignal KEY_TASK_ID is reconciliation_task_id`() {
        assertEquals("reconciliation_task_id", ReconciliationSignal.KEY_TASK_ID)
    }

    @Test
    fun `ReconciliationSignal KEY_STATUS is reconciliation_status`() {
        assertEquals("reconciliation_status", ReconciliationSignal.KEY_STATUS)
    }

    @Test
    fun `ReconciliationSignal KEY_SIGNAL_ID is reconciliation_signal_id`() {
        assertEquals("reconciliation_signal_id", ReconciliationSignal.KEY_SIGNAL_ID)
    }

    @Test
    fun `ReconciliationSignal KEY_EMITTED_AT_MS is reconciliation_emitted_at_ms`() {
        assertEquals("reconciliation_emitted_at_ms", ReconciliationSignal.KEY_EMITTED_AT_MS)
    }

    @Test
    fun `ReconciliationSignal KEY_RECONCILIATION_EPOCH is reconciliation_epoch`() {
        assertEquals("reconciliation_epoch", ReconciliationSignal.KEY_RECONCILIATION_EPOCH)
    }

    @Test
    fun `ReconciliationSignal all key constants are distinct`() {
        val keys = listOf(
            ReconciliationSignal.KEY_KIND,
            ReconciliationSignal.KEY_PARTICIPANT_ID,
            ReconciliationSignal.KEY_TASK_ID,
            ReconciliationSignal.KEY_CORRELATION_ID,
            ReconciliationSignal.KEY_STATUS,
            ReconciliationSignal.KEY_SIGNAL_ID,
            ReconciliationSignal.KEY_EMITTED_AT_MS,
            ReconciliationSignal.KEY_RECONCILIATION_EPOCH
        )
        assertEquals(keys.size, keys.toSet().size)
    }

    // ── AndroidParticipantRuntimeTruth — wire key constants ───────────────────

    @Test
    fun `KEY_PARTICIPANT_ID is participant_id`() {
        assertEquals("participant_id", AndroidParticipantRuntimeTruth.KEY_PARTICIPANT_ID)
    }

    @Test
    fun `KEY_DEVICE_ID is device_id`() {
        assertEquals("device_id", AndroidParticipantRuntimeTruth.KEY_DEVICE_ID)
    }

    @Test
    fun `KEY_HOST_ID is host_id`() {
        assertEquals("host_id", AndroidParticipantRuntimeTruth.KEY_HOST_ID)
    }

    @Test
    fun `KEY_PARTICIPATION_STATE is participation_state`() {
        assertEquals("participation_state", AndroidParticipantRuntimeTruth.KEY_PARTICIPATION_STATE)
    }

    @Test
    fun `KEY_COORDINATION_ROLE is coordination_role`() {
        assertEquals("coordination_role", AndroidParticipantRuntimeTruth.KEY_COORDINATION_ROLE)
    }

    @Test
    fun `KEY_SOURCE_RUNTIME_POSTURE is source_runtime_posture`() {
        assertEquals("source_runtime_posture", AndroidParticipantRuntimeTruth.KEY_SOURCE_RUNTIME_POSTURE)
    }

    @Test
    fun `KEY_SESSION_ID is session_id`() {
        assertEquals("session_id", AndroidParticipantRuntimeTruth.KEY_SESSION_ID)
    }

    @Test
    fun `KEY_SESSION_STATE is session_state`() {
        assertEquals("session_state", AndroidParticipantRuntimeTruth.KEY_SESSION_STATE)
    }

    @Test
    fun `KEY_DELEGATED_EXECUTION_COUNT is delegated_execution_count`() {
        assertEquals("delegated_execution_count", AndroidParticipantRuntimeTruth.KEY_DELEGATED_EXECUTION_COUNT)
    }

    @Test
    fun `KEY_HEALTH_STATE is health_state`() {
        assertEquals("health_state", AndroidParticipantRuntimeTruth.KEY_HEALTH_STATE)
    }

    @Test
    fun `KEY_READINESS_STATE is readiness_state`() {
        assertEquals("readiness_state", AndroidParticipantRuntimeTruth.KEY_READINESS_STATE)
    }

    @Test
    fun `KEY_ACTIVE_TASK_ID is active_task_id`() {
        assertEquals("active_task_id", AndroidParticipantRuntimeTruth.KEY_ACTIVE_TASK_ID)
    }

    @Test
    fun `KEY_ACTIVE_TASK_STATUS is active_task_status`() {
        assertEquals("active_task_status", AndroidParticipantRuntimeTruth.KEY_ACTIVE_TASK_STATUS)
    }

    @Test
    fun `KEY_REPORTED_AT_MS is reported_at_ms`() {
        assertEquals("reported_at_ms", AndroidParticipantRuntimeTruth.KEY_REPORTED_AT_MS)
    }

    @Test
    fun `KEY_RECONCILIATION_EPOCH is reconciliation_epoch`() {
        assertEquals("reconciliation_epoch", AndroidParticipantRuntimeTruth.KEY_RECONCILIATION_EPOCH)
    }

    @Test
    fun `KEY_IS_FULLY_RECONCILABLE is is_fully_reconcilable`() {
        assertEquals("is_fully_reconcilable", AndroidParticipantRuntimeTruth.KEY_IS_FULLY_RECONCILABLE)
    }

    @Test
    fun `AndroidParticipantRuntimeTruth all key constants are distinct`() {
        val keys = listOf(
            AndroidParticipantRuntimeTruth.KEY_PARTICIPANT_ID,
            AndroidParticipantRuntimeTruth.KEY_DEVICE_ID,
            AndroidParticipantRuntimeTruth.KEY_HOST_ID,
            AndroidParticipantRuntimeTruth.KEY_DEVICE_ROLE,
            AndroidParticipantRuntimeTruth.KEY_PARTICIPATION_STATE,
            AndroidParticipantRuntimeTruth.KEY_COORDINATION_ROLE,
            AndroidParticipantRuntimeTruth.KEY_SOURCE_RUNTIME_POSTURE,
            AndroidParticipantRuntimeTruth.KEY_SESSION_ID,
            AndroidParticipantRuntimeTruth.KEY_SESSION_STATE,
            AndroidParticipantRuntimeTruth.KEY_DELEGATED_EXECUTION_COUNT,
            AndroidParticipantRuntimeTruth.KEY_HEALTH_STATE,
            AndroidParticipantRuntimeTruth.KEY_READINESS_STATE,
            AndroidParticipantRuntimeTruth.KEY_ACTIVE_TASK_ID,
            AndroidParticipantRuntimeTruth.KEY_ACTIVE_TASK_STATUS,
            AndroidParticipantRuntimeTruth.KEY_REPORTED_AT_MS,
            AndroidParticipantRuntimeTruth.KEY_RECONCILIATION_EPOCH,
            AndroidParticipantRuntimeTruth.KEY_IS_FULLY_RECONCILABLE
        )
        assertEquals(keys.size, keys.toSet().size)
    }

    // ── StabilizationBaseline — PR-51 entries ─────────────────────────────────

    @Test
    fun `StabilizationBaseline has exactly three entries with introducedPr == 51`() {
        val pr51Entries = StabilizationBaseline.entries.filter { it.introducedPr == 51 }
        assertEquals(3, pr51Entries.size)
    }

    @Test
    fun `android-participant-runtime-truth is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("android-participant-runtime-truth")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `active-task-status is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("active-task-status")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `reconciliation-signal is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("reconciliation-signal")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `all PR-51 entries have non-blank rationale`() {
        val pr51Entries = StabilizationBaseline.entries.filter { it.introducedPr == 51 }
        pr51Entries.forEach { entry ->
            assertTrue(
                "Entry ${entry.surfaceId} must have non-blank rationale",
                entry.rationale.isNotBlank()
            )
        }
    }

    @Test
    fun `all PR-51 entries reference com ufo galaxy packagePath`() {
        val pr51Entries = StabilizationBaseline.entries.filter { it.introducedPr == 51 }
        pr51Entries.forEach { entry ->
            assertTrue(
                "Entry ${entry.surfaceId} packagePath must start with com.ufo.galaxy",
                entry.packagePath.startsWith("com.ufo.galaxy")
            )
        }
    }
}
