package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-53 — Android Lifecycle, Recovery, and Hybrid-Participant Hardening.
 *
 * Acceptance and regression test suite for all three PR-53 surfaces:
 *
 *  1. [AppLifecycleParticipantBoundary] — canonical mapping from Android app lifecycle events
 *     to participant state effects, session continuity behavior, and recovery ownership.
 *
 *  2. [HybridParticipantCapabilityBoundary] — hybrid/distributed capability maturity registry
 *     (FULLY_WIRED vs CONTRACT_FIRST), no-silent-degrade rule, wire-tag constants.
 *
 *  3. [ParticipantRecoveryReadinessSnapshot] — durability tier and V2-resync requirement
 *     registry for all runtime-critical local state fields.
 *
 *  4. [StabilizationBaseline] — three PR-53 entries registered as CANONICAL_STABLE.
 *
 * ## Test matrix
 *
 * ### AppLifecycleParticipantBoundary — AppLifecycleEvent enum
 *  - FOREGROUND wireValue is "foreground"
 *  - BACKGROUND wireValue is "background"
 *  - BACKGROUND_KILL wireValue is "background_kill"
 *  - CONFIGURATION_CHANGE wireValue is "configuration_change"
 *  - MEMORY_PRESSURE wireValue is "memory_pressure"
 *  - PROCESS_RECREATION wireValue is "process_recreation"
 *  - ALL_WIRE_VALUES has exactly six entries
 *  - all six wireValues are distinct
 *  - fromValue returns correct enum for each wire value
 *  - fromValue returns null for unknown wire value
 *  - fromValue returns null for null input
 *
 * ### AppLifecycleParticipantBoundary — ParticipantStateEffect enum
 *  - NO_CHANGE wireValue is "no_change"
 *  - READINESS_REDUCED wireValue is "readiness_reduced"
 *  - SESSION_DETACHED wireValue is "session_detached"
 *  - SESSION_TERMINATED wireValue is "session_terminated"
 *  - EXECUTION_PAUSED wireValue is "execution_paused"
 *
 * ### AppLifecycleParticipantBoundary — SessionContinuityBehavior enum
 *  - DURABLE_SESSION_PRESERVED wireValue is "durable_session_preserved"
 *  - DURABLE_SESSION_ENDED wireValue is "durable_session_ended"
 *  - RECONNECT_DEPENDENT wireValue is "reconnect_dependent"
 *
 * ### AppLifecycleParticipantBoundary — boundary registry
 *  - ENTRY_COUNT is 6
 *  - all six AppLifecycleEvent values have exactly one entry
 *  - boundaryFor returns non-null for every AppLifecycleEvent
 *
 * ### AppLifecycleParticipantBoundary — FOREGROUND boundary semantics
 *  - FOREGROUND participantStateEffect is NO_CHANGE
 *  - FOREGROUND sessionContinuityBehavior is DURABLE_SESSION_PRESERVED
 *  - FOREGROUND androidCanRecoverLocally is true
 *  - FOREGROUND requiresV2Resync is false
 *
 * ### AppLifecycleParticipantBoundary — BACKGROUND boundary semantics
 *  - BACKGROUND participantStateEffect is NO_CHANGE
 *  - BACKGROUND sessionContinuityBehavior is DURABLE_SESSION_PRESERVED
 *  - BACKGROUND androidCanRecoverLocally is true
 *  - BACKGROUND requiresV2Resync is false
 *
 * ### AppLifecycleParticipantBoundary — BACKGROUND_KILL boundary semantics
 *  - BACKGROUND_KILL participantStateEffect is SESSION_TERMINATED
 *  - BACKGROUND_KILL sessionContinuityBehavior is DURABLE_SESSION_ENDED
 *  - BACKGROUND_KILL androidCanRecoverLocally is false
 *  - BACKGROUND_KILL requiresV2Resync is true
 *
 * ### AppLifecycleParticipantBoundary — CONFIGURATION_CHANGE boundary semantics
 *  - CONFIGURATION_CHANGE participantStateEffect is NO_CHANGE
 *  - CONFIGURATION_CHANGE sessionContinuityBehavior is DURABLE_SESSION_PRESERVED
 *  - CONFIGURATION_CHANGE androidCanRecoverLocally is true
 *  - CONFIGURATION_CHANGE requiresV2Resync is false
 *
 * ### AppLifecycleParticipantBoundary — MEMORY_PRESSURE boundary semantics
 *  - MEMORY_PRESSURE participantStateEffect is EXECUTION_PAUSED
 *  - MEMORY_PRESSURE sessionContinuityBehavior is RECONNECT_DEPENDENT
 *  - MEMORY_PRESSURE androidCanRecoverLocally is false
 *  - MEMORY_PRESSURE requiresV2Resync is true
 *
 * ### AppLifecycleParticipantBoundary — PROCESS_RECREATION boundary semantics
 *  - PROCESS_RECREATION participantStateEffect is SESSION_TERMINATED
 *  - PROCESS_RECREATION sessionContinuityBehavior is DURABLE_SESSION_ENDED
 *  - PROCESS_RECREATION androidCanRecoverLocally is false
 *  - PROCESS_RECREATION requiresV2Resync is true
 *
 * ### AppLifecycleParticipantBoundary — query helpers
 *  - locallyRecoverableEvents contains FOREGROUND, BACKGROUND, CONFIGURATION_CHANGE
 *  - locallyRecoverableEvents does not contain BACKGROUND_KILL, MEMORY_PRESSURE, PROCESS_RECREATION
 *  - v2ResyncRequiredEvents contains BACKGROUND_KILL, MEMORY_PRESSURE, PROCESS_RECREATION
 *  - v2ResyncRequiredEvents does not contain FOREGROUND, BACKGROUND, CONFIGURATION_CHANGE
 *  - sessionTerminatingEvents contains BACKGROUND_KILL, PROCESS_RECREATION
 *  - sessionTerminatingEvents does not contain FOREGROUND, BACKGROUND, CONFIGURATION_CHANGE, MEMORY_PRESSURE
 *
 * ### AppLifecycleParticipantBoundary — wire-key constants
 *  - KEY_APP_LIFECYCLE_EVENT is "app_lifecycle_event"
 *  - KEY_PARTICIPANT_STATE_EFFECT is "participant_state_effect"
 *  - KEY_SESSION_CONTINUITY_BEHAVIOR is "session_continuity_behavior"
 *  - KEY_ANDROID_CAN_RECOVER_LOCALLY is "android_can_recover_locally"
 *  - KEY_REQUIRES_V2_RESYNC is "requires_v2_resync"
 *  - all five wire-key constants are distinct
 *
 * ### HybridParticipantCapabilityBoundary — HybridCapability enum
 *  - STAGED_MESH_EXECUTION wireValue is "staged_mesh_execution"
 *  - HYBRID_EXECUTE wireValue is "hybrid_execute"
 *  - RAG_QUERY wireValue is "rag_query"
 *  - CODE_EXECUTE wireValue is "code_execute"
 *  - BARRIER_PARTICIPATION wireValue is "barrier_participation"
 *  - FORMATION_REBALANCE wireValue is "formation_rebalance"
 *  - ALL_WIRE_VALUES has exactly six entries
 *  - all six wireValues are distinct
 *  - fromValue returns correct enum for each wire value
 *  - fromValue returns null for unknown wire value
 *  - fromValue returns null for null input
 *
 * ### HybridParticipantCapabilityBoundary — CapabilityMaturity enum
 *  - FULLY_WIRED wireValue is "fully_wired"
 *  - CONTRACT_FIRST wireValue is "contract_first"
 *
 * ### HybridParticipantCapabilityBoundary — capability boundary registry
 *  - ENTRY_COUNT is 6
 *  - all six HybridCapability values have exactly one entry
 *  - boundaryFor returns non-null for every HybridCapability
 *
 * ### HybridParticipantCapabilityBoundary — FULLY_WIRED capabilities
 *  - STAGED_MESH_EXECUTION is FULLY_WIRED
 *  - BARRIER_PARTICIPATION is FULLY_WIRED
 *  - FORMATION_REBALANCE is FULLY_WIRED
 *  - fullyWiredCapabilities contains exactly STAGED_MESH_EXECUTION, BARRIER_PARTICIPATION, FORMATION_REBALANCE
 *
 * ### HybridParticipantCapabilityBoundary — CONTRACT_FIRST capabilities
 *  - HYBRID_EXECUTE is CONTRACT_FIRST
 *  - RAG_QUERY is CONTRACT_FIRST
 *  - CODE_EXECUTE is CONTRACT_FIRST
 *  - contractFirstCapabilities contains exactly HYBRID_EXECUTE, RAG_QUERY, CODE_EXECUTE
 *
 * ### HybridParticipantCapabilityBoundary — no-silent-degrade: CONTRACT_FIRST response contracts
 *  - all CONTRACT_FIRST capabilities have non-blank responseContract referencing RESULT_CAPABILITY_MATURITY_TAG
 *  - all CONTRACT_FIRST capabilities have non-blank responseContract referencing RESULT_MATURITY_CONTRACT_FIRST
 *  - all CONTRACT_FIRST capabilities have non-blank rationale
 *
 * ### HybridParticipantCapabilityBoundary — wire-tag constants
 *  - RESULT_CAPABILITY_MATURITY_TAG is "capability_maturity"
 *  - RESULT_MATURITY_CONTRACT_FIRST is "contract_first"
 *  - RESULT_MATURITY_FULLY_WIRED is "fully_wired"
 *  - REASON_HYBRID_EXECUTOR_NOT_IMPLEMENTED is "hybrid_executor_not_implemented"
 *  - REASON_RAG_PIPELINE_NOT_IMPLEMENTED is "rag_pipeline_not_implemented"
 *  - REASON_SANDBOX_NOT_IMPLEMENTED is "sandbox_not_implemented"
 *  - RESULT_MATURITY_CONTRACT_FIRST is distinct from RESULT_MATURITY_FULLY_WIRED
 *  - RESULT_CAPABILITY_MATURITY_TAG matches CapabilityMaturity.CONTRACT_FIRST.wireValue for contract-first values
 *
 * ### ParticipantRecoveryReadinessSnapshot — DurabilityTier enum
 *  - DURABLE_IN_MEMORY wireValue is "durable_in_memory"
 *  - SETTINGS_PERSISTED wireValue is "settings_persisted"
 *  - EPHEMERAL wireValue is "ephemeral"
 *  - V2_CANONICAL wireValue is "v2_canonical"
 *  - all four wireValues are distinct
 *
 * ### ParticipantRecoveryReadinessSnapshot — field registry
 *  - fieldCount matches fields list size
 *  - all fieldIds are distinct
 *  - fieldFor returns correct entry for each known fieldId
 *  - fieldFor returns null for unknown fieldId
 *
 * ### ParticipantRecoveryReadinessSnapshot — cross_device_enabled_flag semantics
 *  - cross_device_enabled_flag durabilityTier is SETTINGS_PERSISTED
 *  - cross_device_enabled_flag survivesProcessKill is true
 *  - cross_device_enabled_flag survivesWsReconnect is true
 *  - cross_device_enabled_flag requiresV2ResyncAfterProcessKill is false
 *  - cross_device_enabled_flag requiresV2ResyncAfterWsReconnect is false
 *
 * ### ParticipantRecoveryReadinessSnapshot — durable_session_id semantics
 *  - durable_session_id durabilityTier is DURABLE_IN_MEMORY
 *  - durable_session_id survivesProcessKill is false
 *  - durable_session_id survivesWsReconnect is true
 *  - durable_session_id requiresV2ResyncAfterProcessKill is true
 *  - durable_session_id requiresV2ResyncAfterWsReconnect is false
 *
 * ### ParticipantRecoveryReadinessSnapshot — active_task_state semantics
 *  - active_task_state durabilityTier is EPHEMERAL
 *  - active_task_state survivesProcessKill is false
 *  - active_task_state survivesWsReconnect is false
 *  - active_task_state requiresV2ResyncAfterProcessKill is true
 *  - active_task_state requiresV2ResyncAfterWsReconnect is true
 *
 * ### ParticipantRecoveryReadinessSnapshot — global_session_assignment semantics
 *  - global_session_assignment durabilityTier is V2_CANONICAL
 *  - global_session_assignment survivesProcessKill is false
 *  - global_session_assignment requiresV2ResyncAfterProcessKill is true
 *  - global_session_assignment requiresV2ResyncAfterWsReconnect is true
 *
 * ### ParticipantRecoveryReadinessSnapshot — query helpers
 *  - locallyRecoverableAfterWsReconnect contains cross_device_enabled_flag
 *  - locallyRecoverableAfterWsReconnect contains durable_session_id
 *  - locallyRecoverableAfterWsReconnect does not contain active_task_state
 *  - locallyRecoverableAfterWsReconnect does not contain global_session_assignment
 *  - requiresV2ResyncAfterProcessKillList contains durable_session_id
 *  - requiresV2ResyncAfterProcessKillList contains active_task_state
 *  - requiresV2ResyncAfterProcessKillList does not contain cross_device_enabled_flag
 *  - requiresV2ResyncAfterWsReconnectList contains active_task_state
 *  - requiresV2ResyncAfterWsReconnectList contains global_session_assignment
 *  - settingsPersistedFields contains exactly cross_device_enabled_flag
 *  - v2CanonicalFields contains global_session_assignment and barrier_merge_tracking
 *
 * ### ParticipantRecoveryReadinessSnapshot — wire-key constants
 *  - KEY_FIELD_ID is "recovery_field_id"
 *  - KEY_DURABILITY_TIER is "recovery_durability_tier"
 *  - KEY_REQUIRES_V2_RESYNC is "requires_v2_resync"
 *  - KEY_SURVIVES_PROCESS_KILL is "survives_process_kill"
 *  - KEY_SURVIVES_WS_RECONNECT is "survives_ws_reconnect"
 *  - all five wire-key constants are distinct
 *
 * ### StabilizationBaseline — PR-53 entries
 *  - exactly three entries with introducedPr == 53
 *  - app-lifecycle-participant-boundary is CANONICAL_STABLE with EXTEND guidance
 *  - hybrid-participant-capability-boundary is CANONICAL_STABLE with EXTEND guidance
 *  - participant-recovery-readiness-snapshot is CANONICAL_STABLE with EXTEND guidance
 *  - all PR-53 entries have non-blank rationale
 *  - all PR-53 entries reference com.ufo.galaxy packagePath
 *  - INTRODUCED_PR constants on all three surfaces equal 53
 */
class Pr53AndroidLifecycleRecoveryHybridHardeningTest {

    // ── AppLifecycleParticipantBoundary — AppLifecycleEvent enum ──────────────

    @Test
    fun `FOREGROUND wireValue is foreground`() {
        assertEquals("foreground", AppLifecycleParticipantBoundary.AppLifecycleEvent.FOREGROUND.wireValue)
    }

    @Test
    fun `BACKGROUND wireValue is background`() {
        assertEquals("background", AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND.wireValue)
    }

    @Test
    fun `BACKGROUND_KILL wireValue is background_kill`() {
        assertEquals("background_kill", AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND_KILL.wireValue)
    }

    @Test
    fun `CONFIGURATION_CHANGE wireValue is configuration_change`() {
        assertEquals("configuration_change", AppLifecycleParticipantBoundary.AppLifecycleEvent.CONFIGURATION_CHANGE.wireValue)
    }

    @Test
    fun `MEMORY_PRESSURE wireValue is memory_pressure`() {
        assertEquals("memory_pressure", AppLifecycleParticipantBoundary.AppLifecycleEvent.MEMORY_PRESSURE.wireValue)
    }

    @Test
    fun `PROCESS_RECREATION wireValue is process_recreation`() {
        assertEquals("process_recreation", AppLifecycleParticipantBoundary.AppLifecycleEvent.PROCESS_RECREATION.wireValue)
    }

    @Test
    fun `AppLifecycleEvent ALL_WIRE_VALUES has exactly six entries`() {
        assertEquals(6, AppLifecycleParticipantBoundary.AppLifecycleEvent.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `all six AppLifecycleEvent wireValues are distinct`() {
        val values = AppLifecycleParticipantBoundary.AppLifecycleEvent.entries.map { it.wireValue }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `AppLifecycleEvent fromValue returns correct enum for each wire value`() {
        AppLifecycleParticipantBoundary.AppLifecycleEvent.entries.forEach { event ->
            assertEquals(event, AppLifecycleParticipantBoundary.AppLifecycleEvent.fromValue(event.wireValue))
        }
    }

    @Test
    fun `AppLifecycleEvent fromValue returns null for unknown wire value`() {
        assertNull(AppLifecycleParticipantBoundary.AppLifecycleEvent.fromValue("unknown_event"))
    }

    @Test
    fun `AppLifecycleEvent fromValue returns null for null input`() {
        assertNull(AppLifecycleParticipantBoundary.AppLifecycleEvent.fromValue(null))
    }

    // ── AppLifecycleParticipantBoundary — ParticipantStateEffect enum ─────────

    @Test
    fun `NO_CHANGE wireValue is no_change`() {
        assertEquals("no_change", AppLifecycleParticipantBoundary.ParticipantStateEffect.NO_CHANGE.wireValue)
    }

    @Test
    fun `READINESS_REDUCED wireValue is readiness_reduced`() {
        assertEquals("readiness_reduced", AppLifecycleParticipantBoundary.ParticipantStateEffect.READINESS_REDUCED.wireValue)
    }

    @Test
    fun `SESSION_DETACHED wireValue is session_detached`() {
        assertEquals("session_detached", AppLifecycleParticipantBoundary.ParticipantStateEffect.SESSION_DETACHED.wireValue)
    }

    @Test
    fun `SESSION_TERMINATED wireValue is session_terminated`() {
        assertEquals("session_terminated", AppLifecycleParticipantBoundary.ParticipantStateEffect.SESSION_TERMINATED.wireValue)
    }

    @Test
    fun `EXECUTION_PAUSED wireValue is execution_paused`() {
        assertEquals("execution_paused", AppLifecycleParticipantBoundary.ParticipantStateEffect.EXECUTION_PAUSED.wireValue)
    }

    // ── AppLifecycleParticipantBoundary — SessionContinuityBehavior enum ──────

    @Test
    fun `DURABLE_SESSION_PRESERVED wireValue is durable_session_preserved`() {
        assertEquals("durable_session_preserved", AppLifecycleParticipantBoundary.SessionContinuityBehavior.DURABLE_SESSION_PRESERVED.wireValue)
    }

    @Test
    fun `DURABLE_SESSION_ENDED wireValue is durable_session_ended`() {
        assertEquals("durable_session_ended", AppLifecycleParticipantBoundary.SessionContinuityBehavior.DURABLE_SESSION_ENDED.wireValue)
    }

    @Test
    fun `RECONNECT_DEPENDENT wireValue is reconnect_dependent`() {
        assertEquals("reconnect_dependent", AppLifecycleParticipantBoundary.SessionContinuityBehavior.RECONNECT_DEPENDENT.wireValue)
    }

    // ── AppLifecycleParticipantBoundary — boundary registry ───────────────────

    @Test
    fun `ENTRY_COUNT is 6`() {
        assertEquals(6, AppLifecycleParticipantBoundary.ENTRY_COUNT)
    }

    @Test
    fun `all six AppLifecycleEvent values have exactly one entry`() {
        AppLifecycleParticipantBoundary.AppLifecycleEvent.entries.forEach { event ->
            val count = AppLifecycleParticipantBoundary.entries.count { it.event == event }
            assertEquals("Event $event should have exactly one boundary entry", 1, count)
        }
    }

    @Test
    fun `boundaryFor returns non-null for every AppLifecycleEvent`() {
        AppLifecycleParticipantBoundary.AppLifecycleEvent.entries.forEach { event ->
            assertNotNull(AppLifecycleParticipantBoundary.boundaryFor(event))
        }
    }

    @Test
    fun `entries list size matches ENTRY_COUNT`() {
        assertEquals(AppLifecycleParticipantBoundary.ENTRY_COUNT, AppLifecycleParticipantBoundary.entries.size)
    }

    // ── AppLifecycleParticipantBoundary — FOREGROUND semantics ────────────────

    @Test
    fun `FOREGROUND participantStateEffect is NO_CHANGE`() {
        assertEquals(
            AppLifecycleParticipantBoundary.ParticipantStateEffect.NO_CHANGE,
            AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.FOREGROUND).participantStateEffect
        )
    }

    @Test
    fun `FOREGROUND sessionContinuityBehavior is DURABLE_SESSION_PRESERVED`() {
        assertEquals(
            AppLifecycleParticipantBoundary.SessionContinuityBehavior.DURABLE_SESSION_PRESERVED,
            AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.FOREGROUND).sessionContinuityBehavior
        )
    }

    @Test
    fun `FOREGROUND androidCanRecoverLocally is true`() {
        assertTrue(AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.FOREGROUND).androidCanRecoverLocally)
    }

    @Test
    fun `FOREGROUND requiresV2Resync is false`() {
        assertFalse(AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.FOREGROUND).requiresV2Resync)
    }

    // ── AppLifecycleParticipantBoundary — BACKGROUND semantics ────────────────

    @Test
    fun `BACKGROUND participantStateEffect is NO_CHANGE`() {
        assertEquals(
            AppLifecycleParticipantBoundary.ParticipantStateEffect.NO_CHANGE,
            AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND).participantStateEffect
        )
    }

    @Test
    fun `BACKGROUND sessionContinuityBehavior is DURABLE_SESSION_PRESERVED`() {
        assertEquals(
            AppLifecycleParticipantBoundary.SessionContinuityBehavior.DURABLE_SESSION_PRESERVED,
            AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND).sessionContinuityBehavior
        )
    }

    @Test
    fun `BACKGROUND androidCanRecoverLocally is true`() {
        assertTrue(AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND).androidCanRecoverLocally)
    }

    @Test
    fun `BACKGROUND requiresV2Resync is false`() {
        assertFalse(AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND).requiresV2Resync)
    }

    // ── AppLifecycleParticipantBoundary — BACKGROUND_KILL semantics ───────────

    @Test
    fun `BACKGROUND_KILL participantStateEffect is SESSION_TERMINATED`() {
        assertEquals(
            AppLifecycleParticipantBoundary.ParticipantStateEffect.SESSION_TERMINATED,
            AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND_KILL).participantStateEffect
        )
    }

    @Test
    fun `BACKGROUND_KILL sessionContinuityBehavior is DURABLE_SESSION_ENDED`() {
        assertEquals(
            AppLifecycleParticipantBoundary.SessionContinuityBehavior.DURABLE_SESSION_ENDED,
            AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND_KILL).sessionContinuityBehavior
        )
    }

    @Test
    fun `BACKGROUND_KILL androidCanRecoverLocally is false`() {
        assertFalse(AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND_KILL).androidCanRecoverLocally)
    }

    @Test
    fun `BACKGROUND_KILL requiresV2Resync is true`() {
        assertTrue(AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND_KILL).requiresV2Resync)
    }

    // ── AppLifecycleParticipantBoundary — CONFIGURATION_CHANGE semantics ──────

    @Test
    fun `CONFIGURATION_CHANGE participantStateEffect is NO_CHANGE`() {
        assertEquals(
            AppLifecycleParticipantBoundary.ParticipantStateEffect.NO_CHANGE,
            AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.CONFIGURATION_CHANGE).participantStateEffect
        )
    }

    @Test
    fun `CONFIGURATION_CHANGE sessionContinuityBehavior is DURABLE_SESSION_PRESERVED`() {
        assertEquals(
            AppLifecycleParticipantBoundary.SessionContinuityBehavior.DURABLE_SESSION_PRESERVED,
            AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.CONFIGURATION_CHANGE).sessionContinuityBehavior
        )
    }

    @Test
    fun `CONFIGURATION_CHANGE androidCanRecoverLocally is true`() {
        assertTrue(AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.CONFIGURATION_CHANGE).androidCanRecoverLocally)
    }

    @Test
    fun `CONFIGURATION_CHANGE requiresV2Resync is false`() {
        assertFalse(AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.CONFIGURATION_CHANGE).requiresV2Resync)
    }

    // ── AppLifecycleParticipantBoundary — MEMORY_PRESSURE semantics ───────────

    @Test
    fun `MEMORY_PRESSURE participantStateEffect is EXECUTION_PAUSED`() {
        assertEquals(
            AppLifecycleParticipantBoundary.ParticipantStateEffect.EXECUTION_PAUSED,
            AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.MEMORY_PRESSURE).participantStateEffect
        )
    }

    @Test
    fun `MEMORY_PRESSURE sessionContinuityBehavior is RECONNECT_DEPENDENT`() {
        assertEquals(
            AppLifecycleParticipantBoundary.SessionContinuityBehavior.RECONNECT_DEPENDENT,
            AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.MEMORY_PRESSURE).sessionContinuityBehavior
        )
    }

    @Test
    fun `MEMORY_PRESSURE androidCanRecoverLocally is false`() {
        assertFalse(AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.MEMORY_PRESSURE).androidCanRecoverLocally)
    }

    @Test
    fun `MEMORY_PRESSURE requiresV2Resync is true`() {
        assertTrue(AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.MEMORY_PRESSURE).requiresV2Resync)
    }

    // ── AppLifecycleParticipantBoundary — PROCESS_RECREATION semantics ────────

    @Test
    fun `PROCESS_RECREATION participantStateEffect is SESSION_TERMINATED`() {
        assertEquals(
            AppLifecycleParticipantBoundary.ParticipantStateEffect.SESSION_TERMINATED,
            AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.PROCESS_RECREATION).participantStateEffect
        )
    }

    @Test
    fun `PROCESS_RECREATION sessionContinuityBehavior is DURABLE_SESSION_ENDED`() {
        assertEquals(
            AppLifecycleParticipantBoundary.SessionContinuityBehavior.DURABLE_SESSION_ENDED,
            AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.PROCESS_RECREATION).sessionContinuityBehavior
        )
    }

    @Test
    fun `PROCESS_RECREATION androidCanRecoverLocally is false`() {
        assertFalse(AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.PROCESS_RECREATION).androidCanRecoverLocally)
    }

    @Test
    fun `PROCESS_RECREATION requiresV2Resync is true`() {
        assertTrue(AppLifecycleParticipantBoundary.boundaryFor(AppLifecycleParticipantBoundary.AppLifecycleEvent.PROCESS_RECREATION).requiresV2Resync)
    }

    // ── AppLifecycleParticipantBoundary — query helpers ───────────────────────

    @Test
    fun `locallyRecoverableEvents contains FOREGROUND BACKGROUND CONFIGURATION_CHANGE`() {
        val recoverable = AppLifecycleParticipantBoundary.locallyRecoverableEvents
        assertTrue(recoverable.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.FOREGROUND))
        assertTrue(recoverable.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND))
        assertTrue(recoverable.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.CONFIGURATION_CHANGE))
    }

    @Test
    fun `locallyRecoverableEvents does not contain BACKGROUND_KILL MEMORY_PRESSURE PROCESS_RECREATION`() {
        val recoverable = AppLifecycleParticipantBoundary.locallyRecoverableEvents
        assertFalse(recoverable.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND_KILL))
        assertFalse(recoverable.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.MEMORY_PRESSURE))
        assertFalse(recoverable.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.PROCESS_RECREATION))
    }

    @Test
    fun `v2ResyncRequiredEvents contains BACKGROUND_KILL MEMORY_PRESSURE PROCESS_RECREATION`() {
        val resync = AppLifecycleParticipantBoundary.v2ResyncRequiredEvents
        assertTrue(resync.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND_KILL))
        assertTrue(resync.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.MEMORY_PRESSURE))
        assertTrue(resync.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.PROCESS_RECREATION))
    }

    @Test
    fun `v2ResyncRequiredEvents does not contain FOREGROUND BACKGROUND CONFIGURATION_CHANGE`() {
        val resync = AppLifecycleParticipantBoundary.v2ResyncRequiredEvents
        assertFalse(resync.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.FOREGROUND))
        assertFalse(resync.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND))
        assertFalse(resync.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.CONFIGURATION_CHANGE))
    }

    @Test
    fun `sessionTerminatingEvents contains BACKGROUND_KILL and PROCESS_RECREATION`() {
        val terminating = AppLifecycleParticipantBoundary.sessionTerminatingEvents
        assertTrue(terminating.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND_KILL))
        assertTrue(terminating.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.PROCESS_RECREATION))
    }

    @Test
    fun `sessionTerminatingEvents does not contain FOREGROUND BACKGROUND CONFIGURATION_CHANGE MEMORY_PRESSURE`() {
        val terminating = AppLifecycleParticipantBoundary.sessionTerminatingEvents
        assertFalse(terminating.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.FOREGROUND))
        assertFalse(terminating.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND))
        assertFalse(terminating.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.CONFIGURATION_CHANGE))
        assertFalse(terminating.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.MEMORY_PRESSURE))
    }

    // ── AppLifecycleParticipantBoundary — wire-key constants ──────────────────

    @Test
    fun `KEY_APP_LIFECYCLE_EVENT is app_lifecycle_event`() {
        assertEquals("app_lifecycle_event", AppLifecycleParticipantBoundary.KEY_APP_LIFECYCLE_EVENT)
    }

    @Test
    fun `KEY_PARTICIPANT_STATE_EFFECT is participant_state_effect`() {
        assertEquals("participant_state_effect", AppLifecycleParticipantBoundary.KEY_PARTICIPANT_STATE_EFFECT)
    }

    @Test
    fun `KEY_SESSION_CONTINUITY_BEHAVIOR is session_continuity_behavior`() {
        assertEquals("session_continuity_behavior", AppLifecycleParticipantBoundary.KEY_SESSION_CONTINUITY_BEHAVIOR)
    }

    @Test
    fun `KEY_ANDROID_CAN_RECOVER_LOCALLY is android_can_recover_locally`() {
        assertEquals("android_can_recover_locally", AppLifecycleParticipantBoundary.KEY_ANDROID_CAN_RECOVER_LOCALLY)
    }

    @Test
    fun `KEY_REQUIRES_V2_RESYNC boundary is requires_v2_resync`() {
        assertEquals("requires_v2_resync", AppLifecycleParticipantBoundary.KEY_REQUIRES_V2_RESYNC)
    }

    @Test
    fun `all five AppLifecycleParticipantBoundary wire-key constants are distinct`() {
        val keys = setOf(
            AppLifecycleParticipantBoundary.KEY_APP_LIFECYCLE_EVENT,
            AppLifecycleParticipantBoundary.KEY_PARTICIPANT_STATE_EFFECT,
            AppLifecycleParticipantBoundary.KEY_SESSION_CONTINUITY_BEHAVIOR,
            AppLifecycleParticipantBoundary.KEY_ANDROID_CAN_RECOVER_LOCALLY,
            AppLifecycleParticipantBoundary.KEY_REQUIRES_V2_RESYNC
        )
        assertEquals(5, keys.size)
    }

    // ── HybridParticipantCapabilityBoundary — HybridCapability enum ───────────

    @Test
    fun `STAGED_MESH_EXECUTION wireValue is staged_mesh_execution`() {
        assertEquals("staged_mesh_execution", HybridParticipantCapabilityBoundary.HybridCapability.STAGED_MESH_EXECUTION.wireValue)
    }

    @Test
    fun `HYBRID_EXECUTE wireValue is hybrid_execute`() {
        assertEquals("hybrid_execute", HybridParticipantCapabilityBoundary.HybridCapability.HYBRID_EXECUTE.wireValue)
    }

    @Test
    fun `RAG_QUERY wireValue is rag_query`() {
        assertEquals("rag_query", HybridParticipantCapabilityBoundary.HybridCapability.RAG_QUERY.wireValue)
    }

    @Test
    fun `CODE_EXECUTE wireValue is code_execute`() {
        assertEquals("code_execute", HybridParticipantCapabilityBoundary.HybridCapability.CODE_EXECUTE.wireValue)
    }

    @Test
    fun `BARRIER_PARTICIPATION wireValue is barrier_participation`() {
        assertEquals("barrier_participation", HybridParticipantCapabilityBoundary.HybridCapability.BARRIER_PARTICIPATION.wireValue)
    }

    @Test
    fun `FORMATION_REBALANCE wireValue is formation_rebalance`() {
        assertEquals("formation_rebalance", HybridParticipantCapabilityBoundary.HybridCapability.FORMATION_REBALANCE.wireValue)
    }

    @Test
    fun `HybridCapability ALL_WIRE_VALUES has exactly six entries`() {
        assertEquals(6, HybridParticipantCapabilityBoundary.HybridCapability.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `all six HybridCapability wireValues are distinct`() {
        val values = HybridParticipantCapabilityBoundary.HybridCapability.entries.map { it.wireValue }
        assertEquals(values.size, values.toSet().size)
    }

    @Test
    fun `HybridCapability fromValue returns correct enum for each wire value`() {
        HybridParticipantCapabilityBoundary.HybridCapability.entries.forEach { cap ->
            assertEquals(cap, HybridParticipantCapabilityBoundary.HybridCapability.fromValue(cap.wireValue))
        }
    }

    @Test
    fun `HybridCapability fromValue returns null for unknown wire value`() {
        assertNull(HybridParticipantCapabilityBoundary.HybridCapability.fromValue("unknown_capability"))
    }

    @Test
    fun `HybridCapability fromValue returns null for null input`() {
        assertNull(HybridParticipantCapabilityBoundary.HybridCapability.fromValue(null))
    }

    // ── HybridParticipantCapabilityBoundary — CapabilityMaturity enum ─────────

    @Test
    fun `FULLY_WIRED wireValue is fully_wired`() {
        assertEquals("fully_wired", HybridParticipantCapabilityBoundary.CapabilityMaturity.FULLY_WIRED.wireValue)
    }

    @Test
    fun `CONTRACT_FIRST wireValue is contract_first`() {
        assertEquals("contract_first", HybridParticipantCapabilityBoundary.CapabilityMaturity.CONTRACT_FIRST.wireValue)
    }

    // ── HybridParticipantCapabilityBoundary — capability boundary registry ────

    @Test
    fun `HybridCapabilityBoundary ENTRY_COUNT is 6`() {
        assertEquals(6, HybridParticipantCapabilityBoundary.ENTRY_COUNT)
    }

    @Test
    fun `all six HybridCapability values have exactly one entry`() {
        HybridParticipantCapabilityBoundary.HybridCapability.entries.forEach { cap ->
            val count = HybridParticipantCapabilityBoundary.entries.count { it.capability == cap }
            assertEquals("Capability $cap should have exactly one boundary entry", 1, count)
        }
    }

    @Test
    fun `boundaryFor returns non-null for every HybridCapability`() {
        HybridParticipantCapabilityBoundary.HybridCapability.entries.forEach { cap ->
            assertNotNull(HybridParticipantCapabilityBoundary.boundaryFor(cap))
        }
    }

    // ── HybridParticipantCapabilityBoundary — FULLY_WIRED capabilities ────────

    @Test
    fun `STAGED_MESH_EXECUTION is FULLY_WIRED`() {
        assertTrue(HybridParticipantCapabilityBoundary.isFullyWired(HybridParticipantCapabilityBoundary.HybridCapability.STAGED_MESH_EXECUTION))
        assertFalse(HybridParticipantCapabilityBoundary.isContractFirst(HybridParticipantCapabilityBoundary.HybridCapability.STAGED_MESH_EXECUTION))
    }

    @Test
    fun `BARRIER_PARTICIPATION is FULLY_WIRED`() {
        assertTrue(HybridParticipantCapabilityBoundary.isFullyWired(HybridParticipantCapabilityBoundary.HybridCapability.BARRIER_PARTICIPATION))
        assertFalse(HybridParticipantCapabilityBoundary.isContractFirst(HybridParticipantCapabilityBoundary.HybridCapability.BARRIER_PARTICIPATION))
    }

    @Test
    fun `FORMATION_REBALANCE is FULLY_WIRED`() {
        assertTrue(HybridParticipantCapabilityBoundary.isFullyWired(HybridParticipantCapabilityBoundary.HybridCapability.FORMATION_REBALANCE))
        assertFalse(HybridParticipantCapabilityBoundary.isContractFirst(HybridParticipantCapabilityBoundary.HybridCapability.FORMATION_REBALANCE))
    }

    @Test
    fun `fullyWiredCapabilities contains exactly STAGED_MESH_EXECUTION BARRIER_PARTICIPATION FORMATION_REBALANCE`() {
        val fullyWired = HybridParticipantCapabilityBoundary.fullyWiredCapabilities.toSet()
        assertEquals(3, fullyWired.size)
        assertTrue(fullyWired.contains(HybridParticipantCapabilityBoundary.HybridCapability.STAGED_MESH_EXECUTION))
        assertTrue(fullyWired.contains(HybridParticipantCapabilityBoundary.HybridCapability.BARRIER_PARTICIPATION))
        assertTrue(fullyWired.contains(HybridParticipantCapabilityBoundary.HybridCapability.FORMATION_REBALANCE))
    }

    // ── HybridParticipantCapabilityBoundary — CONTRACT_FIRST capabilities ─────

    @Test
    fun `HYBRID_EXECUTE is CONTRACT_FIRST`() {
        assertTrue(HybridParticipantCapabilityBoundary.isContractFirst(HybridParticipantCapabilityBoundary.HybridCapability.HYBRID_EXECUTE))
        assertFalse(HybridParticipantCapabilityBoundary.isFullyWired(HybridParticipantCapabilityBoundary.HybridCapability.HYBRID_EXECUTE))
    }

    @Test
    fun `RAG_QUERY is CONTRACT_FIRST`() {
        assertTrue(HybridParticipantCapabilityBoundary.isContractFirst(HybridParticipantCapabilityBoundary.HybridCapability.RAG_QUERY))
        assertFalse(HybridParticipantCapabilityBoundary.isFullyWired(HybridParticipantCapabilityBoundary.HybridCapability.RAG_QUERY))
    }

    @Test
    fun `CODE_EXECUTE is CONTRACT_FIRST`() {
        assertTrue(HybridParticipantCapabilityBoundary.isContractFirst(HybridParticipantCapabilityBoundary.HybridCapability.CODE_EXECUTE))
        assertFalse(HybridParticipantCapabilityBoundary.isFullyWired(HybridParticipantCapabilityBoundary.HybridCapability.CODE_EXECUTE))
    }

    @Test
    fun `contractFirstCapabilities contains exactly HYBRID_EXECUTE RAG_QUERY CODE_EXECUTE`() {
        val contractFirst = HybridParticipantCapabilityBoundary.contractFirstCapabilities.toSet()
        assertEquals(3, contractFirst.size)
        assertTrue(contractFirst.contains(HybridParticipantCapabilityBoundary.HybridCapability.HYBRID_EXECUTE))
        assertTrue(contractFirst.contains(HybridParticipantCapabilityBoundary.HybridCapability.RAG_QUERY))
        assertTrue(contractFirst.contains(HybridParticipantCapabilityBoundary.HybridCapability.CODE_EXECUTE))
    }

    // ── HybridParticipantCapabilityBoundary — no-silent-degrade rule ──────────

    @Test
    fun `all CONTRACT_FIRST capabilities have non-blank responseContract referencing RESULT_CAPABILITY_MATURITY_TAG`() {
        HybridParticipantCapabilityBoundary.contractFirstCapabilities.forEach { cap ->
            val entry = HybridParticipantCapabilityBoundary.boundaryFor(cap)
            assertTrue(
                "Capability $cap responseContract must reference RESULT_CAPABILITY_MATURITY_TAG",
                entry.responseContract.contains(HybridParticipantCapabilityBoundary.RESULT_CAPABILITY_MATURITY_TAG)
            )
        }
    }

    @Test
    fun `all CONTRACT_FIRST capabilities have non-blank responseContract referencing RESULT_MATURITY_CONTRACT_FIRST`() {
        HybridParticipantCapabilityBoundary.contractFirstCapabilities.forEach { cap ->
            val entry = HybridParticipantCapabilityBoundary.boundaryFor(cap)
            assertTrue(
                "Capability $cap responseContract must reference RESULT_MATURITY_CONTRACT_FIRST",
                entry.responseContract.contains(HybridParticipantCapabilityBoundary.RESULT_MATURITY_CONTRACT_FIRST)
            )
        }
    }

    @Test
    fun `all CONTRACT_FIRST capabilities have non-blank rationale`() {
        HybridParticipantCapabilityBoundary.contractFirstCapabilities.forEach { cap ->
            val entry = HybridParticipantCapabilityBoundary.boundaryFor(cap)
            assertTrue("Capability $cap rationale must be non-blank", entry.rationale.isNotBlank())
        }
    }

    // ── HybridParticipantCapabilityBoundary — wire-tag constants ─────────────

    @Test
    fun `RESULT_CAPABILITY_MATURITY_TAG is capability_maturity`() {
        assertEquals("capability_maturity", HybridParticipantCapabilityBoundary.RESULT_CAPABILITY_MATURITY_TAG)
    }

    @Test
    fun `RESULT_MATURITY_CONTRACT_FIRST is contract_first`() {
        assertEquals("contract_first", HybridParticipantCapabilityBoundary.RESULT_MATURITY_CONTRACT_FIRST)
    }

    @Test
    fun `RESULT_MATURITY_FULLY_WIRED is fully_wired`() {
        assertEquals("fully_wired", HybridParticipantCapabilityBoundary.RESULT_MATURITY_FULLY_WIRED)
    }

    @Test
    fun `REASON_HYBRID_EXECUTOR_NOT_IMPLEMENTED is hybrid_executor_not_implemented`() {
        assertEquals("hybrid_executor_not_implemented", HybridParticipantCapabilityBoundary.REASON_HYBRID_EXECUTOR_NOT_IMPLEMENTED)
    }

    @Test
    fun `REASON_RAG_PIPELINE_NOT_IMPLEMENTED is rag_pipeline_not_implemented`() {
        assertEquals("rag_pipeline_not_implemented", HybridParticipantCapabilityBoundary.REASON_RAG_PIPELINE_NOT_IMPLEMENTED)
    }

    @Test
    fun `REASON_SANDBOX_NOT_IMPLEMENTED is sandbox_not_implemented`() {
        assertEquals("sandbox_not_implemented", HybridParticipantCapabilityBoundary.REASON_SANDBOX_NOT_IMPLEMENTED)
    }

    @Test
    fun `RESULT_MATURITY_CONTRACT_FIRST is distinct from RESULT_MATURITY_FULLY_WIRED`() {
        assertFalse(
            HybridParticipantCapabilityBoundary.RESULT_MATURITY_CONTRACT_FIRST ==
                HybridParticipantCapabilityBoundary.RESULT_MATURITY_FULLY_WIRED
        )
    }

    @Test
    fun `RESULT_MATURITY_CONTRACT_FIRST matches CapabilityMaturity CONTRACT_FIRST wireValue`() {
        assertEquals(
            HybridParticipantCapabilityBoundary.CapabilityMaturity.CONTRACT_FIRST.wireValue,
            HybridParticipantCapabilityBoundary.RESULT_MATURITY_CONTRACT_FIRST
        )
    }

    // ── ParticipantRecoveryReadinessSnapshot — DurabilityTier enum ───────────

    @Test
    fun `DURABLE_IN_MEMORY wireValue is durable_in_memory`() {
        assertEquals("durable_in_memory", ParticipantRecoveryReadinessSnapshot.DurabilityTier.DURABLE_IN_MEMORY.wireValue)
    }

    @Test
    fun `SETTINGS_PERSISTED wireValue is settings_persisted`() {
        assertEquals("settings_persisted", ParticipantRecoveryReadinessSnapshot.DurabilityTier.SETTINGS_PERSISTED.wireValue)
    }

    @Test
    fun `EPHEMERAL wireValue is ephemeral`() {
        assertEquals("ephemeral", ParticipantRecoveryReadinessSnapshot.DurabilityTier.EPHEMERAL.wireValue)
    }

    @Test
    fun `V2_CANONICAL wireValue is v2_canonical`() {
        assertEquals("v2_canonical", ParticipantRecoveryReadinessSnapshot.DurabilityTier.V2_CANONICAL.wireValue)
    }

    @Test
    fun `all four DurabilityTier wireValues are distinct`() {
        val values = ParticipantRecoveryReadinessSnapshot.DurabilityTier.entries.map { it.wireValue }
        assertEquals(values.size, values.toSet().size)
    }

    // ── ParticipantRecoveryReadinessSnapshot — field registry ─────────────────

    @Test
    fun `fieldCount matches fields list size`() {
        assertEquals(ParticipantRecoveryReadinessSnapshot.fields.size, ParticipantRecoveryReadinessSnapshot.fieldCount)
    }

    @Test
    fun `all fieldIds are distinct`() {
        val ids = ParticipantRecoveryReadinessSnapshot.fields.map { it.fieldId }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `fieldFor returns correct entry for each known fieldId`() {
        ParticipantRecoveryReadinessSnapshot.fields.forEach { entry ->
            assertEquals(entry, ParticipantRecoveryReadinessSnapshot.fieldFor(entry.fieldId))
        }
    }

    @Test
    fun `fieldFor returns null for unknown fieldId`() {
        assertNull(ParticipantRecoveryReadinessSnapshot.fieldFor("unknown_field_id"))
    }

    // ── ParticipantRecoveryReadinessSnapshot — cross_device_enabled_flag ─────

    @Test
    fun `cross_device_enabled_flag durabilityTier is SETTINGS_PERSISTED`() {
        val entry = ParticipantRecoveryReadinessSnapshot.fieldFor("cross_device_enabled_flag")
        assertNotNull(entry)
        assertEquals(ParticipantRecoveryReadinessSnapshot.DurabilityTier.SETTINGS_PERSISTED, entry!!.durabilityTier)
    }

    @Test
    fun `cross_device_enabled_flag survivesProcessKill is true`() {
        assertTrue(ParticipantRecoveryReadinessSnapshot.fieldFor("cross_device_enabled_flag")!!.survivesProcessKill)
    }

    @Test
    fun `cross_device_enabled_flag survivesWsReconnect is true`() {
        assertTrue(ParticipantRecoveryReadinessSnapshot.fieldFor("cross_device_enabled_flag")!!.survivesWsReconnect)
    }

    @Test
    fun `cross_device_enabled_flag requiresV2ResyncAfterProcessKill is false`() {
        assertFalse(ParticipantRecoveryReadinessSnapshot.fieldFor("cross_device_enabled_flag")!!.requiresV2ResyncAfterProcessKill)
    }

    @Test
    fun `cross_device_enabled_flag requiresV2ResyncAfterWsReconnect is false`() {
        assertFalse(ParticipantRecoveryReadinessSnapshot.fieldFor("cross_device_enabled_flag")!!.requiresV2ResyncAfterWsReconnect)
    }

    // ── ParticipantRecoveryReadinessSnapshot — durable_session_id ────────────

    @Test
    fun `durable_session_id durabilityTier is DURABLE_IN_MEMORY`() {
        val entry = ParticipantRecoveryReadinessSnapshot.fieldFor("durable_session_id")
        assertNotNull(entry)
        assertEquals(ParticipantRecoveryReadinessSnapshot.DurabilityTier.DURABLE_IN_MEMORY, entry!!.durabilityTier)
    }

    @Test
    fun `durable_session_id survivesProcessKill is false`() {
        assertFalse(ParticipantRecoveryReadinessSnapshot.fieldFor("durable_session_id")!!.survivesProcessKill)
    }

    @Test
    fun `durable_session_id survivesWsReconnect is true`() {
        assertTrue(ParticipantRecoveryReadinessSnapshot.fieldFor("durable_session_id")!!.survivesWsReconnect)
    }

    @Test
    fun `durable_session_id requiresV2ResyncAfterProcessKill is true`() {
        assertTrue(ParticipantRecoveryReadinessSnapshot.fieldFor("durable_session_id")!!.requiresV2ResyncAfterProcessKill)
    }

    @Test
    fun `durable_session_id requiresV2ResyncAfterWsReconnect is false`() {
        assertFalse(ParticipantRecoveryReadinessSnapshot.fieldFor("durable_session_id")!!.requiresV2ResyncAfterWsReconnect)
    }

    // ── ParticipantRecoveryReadinessSnapshot — active_task_state ─────────────

    @Test
    fun `active_task_state durabilityTier is EPHEMERAL`() {
        val entry = ParticipantRecoveryReadinessSnapshot.fieldFor("active_task_state")
        assertNotNull(entry)
        assertEquals(ParticipantRecoveryReadinessSnapshot.DurabilityTier.EPHEMERAL, entry!!.durabilityTier)
    }

    @Test
    fun `active_task_state survivesProcessKill is false`() {
        assertFalse(ParticipantRecoveryReadinessSnapshot.fieldFor("active_task_state")!!.survivesProcessKill)
    }

    @Test
    fun `active_task_state survivesWsReconnect is false`() {
        assertFalse(ParticipantRecoveryReadinessSnapshot.fieldFor("active_task_state")!!.survivesWsReconnect)
    }

    @Test
    fun `active_task_state requiresV2ResyncAfterProcessKill is true`() {
        assertTrue(ParticipantRecoveryReadinessSnapshot.fieldFor("active_task_state")!!.requiresV2ResyncAfterProcessKill)
    }

    @Test
    fun `active_task_state requiresV2ResyncAfterWsReconnect is true`() {
        assertTrue(ParticipantRecoveryReadinessSnapshot.fieldFor("active_task_state")!!.requiresV2ResyncAfterWsReconnect)
    }

    // ── ParticipantRecoveryReadinessSnapshot — global_session_assignment ──────

    @Test
    fun `global_session_assignment durabilityTier is V2_CANONICAL`() {
        val entry = ParticipantRecoveryReadinessSnapshot.fieldFor("global_session_assignment")
        assertNotNull(entry)
        assertEquals(ParticipantRecoveryReadinessSnapshot.DurabilityTier.V2_CANONICAL, entry!!.durabilityTier)
    }

    @Test
    fun `global_session_assignment survivesProcessKill is false`() {
        assertFalse(ParticipantRecoveryReadinessSnapshot.fieldFor("global_session_assignment")!!.survivesProcessKill)
    }

    @Test
    fun `global_session_assignment requiresV2ResyncAfterProcessKill is true`() {
        assertTrue(ParticipantRecoveryReadinessSnapshot.fieldFor("global_session_assignment")!!.requiresV2ResyncAfterProcessKill)
    }

    @Test
    fun `global_session_assignment requiresV2ResyncAfterWsReconnect is true`() {
        assertTrue(ParticipantRecoveryReadinessSnapshot.fieldFor("global_session_assignment")!!.requiresV2ResyncAfterWsReconnect)
    }

    // ── ParticipantRecoveryReadinessSnapshot — query helpers ──────────────────

    @Test
    fun `locallyRecoverableAfterWsReconnect contains cross_device_enabled_flag`() {
        assertTrue(
            ParticipantRecoveryReadinessSnapshot.locallyRecoverableAfterWsReconnect.any { it.fieldId == "cross_device_enabled_flag" }
        )
    }

    @Test
    fun `locallyRecoverableAfterWsReconnect contains durable_session_id`() {
        assertTrue(
            ParticipantRecoveryReadinessSnapshot.locallyRecoverableAfterWsReconnect.any { it.fieldId == "durable_session_id" }
        )
    }

    @Test
    fun `locallyRecoverableAfterWsReconnect does not contain active_task_state`() {
        assertFalse(
            ParticipantRecoveryReadinessSnapshot.locallyRecoverableAfterWsReconnect.any { it.fieldId == "active_task_state" }
        )
    }

    @Test
    fun `locallyRecoverableAfterWsReconnect does not contain global_session_assignment`() {
        assertFalse(
            ParticipantRecoveryReadinessSnapshot.locallyRecoverableAfterWsReconnect.any { it.fieldId == "global_session_assignment" }
        )
    }

    @Test
    fun `requiresV2ResyncAfterProcessKillList contains durable_session_id`() {
        assertTrue(
            ParticipantRecoveryReadinessSnapshot.requiresV2ResyncAfterProcessKillList.any { it.fieldId == "durable_session_id" }
        )
    }

    @Test
    fun `requiresV2ResyncAfterProcessKillList contains active_task_state`() {
        assertTrue(
            ParticipantRecoveryReadinessSnapshot.requiresV2ResyncAfterProcessKillList.any { it.fieldId == "active_task_state" }
        )
    }

    @Test
    fun `requiresV2ResyncAfterProcessKillList does not contain cross_device_enabled_flag`() {
        assertFalse(
            ParticipantRecoveryReadinessSnapshot.requiresV2ResyncAfterProcessKillList.any { it.fieldId == "cross_device_enabled_flag" }
        )
    }

    @Test
    fun `requiresV2ResyncAfterWsReconnectList contains active_task_state`() {
        assertTrue(
            ParticipantRecoveryReadinessSnapshot.requiresV2ResyncAfterWsReconnectList.any { it.fieldId == "active_task_state" }
        )
    }

    @Test
    fun `requiresV2ResyncAfterWsReconnectList contains global_session_assignment`() {
        assertTrue(
            ParticipantRecoveryReadinessSnapshot.requiresV2ResyncAfterWsReconnectList.any { it.fieldId == "global_session_assignment" }
        )
    }

    @Test
    fun `settingsPersistedFields contains exactly cross_device_enabled_flag`() {
        val settingsPersisted = ParticipantRecoveryReadinessSnapshot.settingsPersistedFields
        assertEquals(1, settingsPersisted.size)
        assertEquals("cross_device_enabled_flag", settingsPersisted[0].fieldId)
    }

    @Test
    fun `v2CanonicalFields contains global_session_assignment`() {
        assertTrue(ParticipantRecoveryReadinessSnapshot.v2CanonicalFields.any { it.fieldId == "global_session_assignment" })
    }

    @Test
    fun `v2CanonicalFields contains barrier_merge_tracking`() {
        assertTrue(ParticipantRecoveryReadinessSnapshot.v2CanonicalFields.any { it.fieldId == "barrier_merge_tracking" })
    }

    // ── ParticipantRecoveryReadinessSnapshot — wire-key constants ────────────

    @Test
    fun `KEY_FIELD_ID is recovery_field_id`() {
        assertEquals("recovery_field_id", ParticipantRecoveryReadinessSnapshot.KEY_FIELD_ID)
    }

    @Test
    fun `KEY_DURABILITY_TIER is recovery_durability_tier`() {
        assertEquals("recovery_durability_tier", ParticipantRecoveryReadinessSnapshot.KEY_DURABILITY_TIER)
    }

    @Test
    fun `ParticipantRecovery KEY_REQUIRES_V2_RESYNC is requires_v2_resync`() {
        assertEquals("requires_v2_resync", ParticipantRecoveryReadinessSnapshot.KEY_REQUIRES_V2_RESYNC)
    }

    @Test
    fun `KEY_SURVIVES_PROCESS_KILL is survives_process_kill`() {
        assertEquals("survives_process_kill", ParticipantRecoveryReadinessSnapshot.KEY_SURVIVES_PROCESS_KILL)
    }

    @Test
    fun `KEY_SURVIVES_WS_RECONNECT is survives_ws_reconnect`() {
        assertEquals("survives_ws_reconnect", ParticipantRecoveryReadinessSnapshot.KEY_SURVIVES_WS_RECONNECT)
    }

    @Test
    fun `all five ParticipantRecovery wire-key constants are distinct`() {
        val keys = setOf(
            ParticipantRecoveryReadinessSnapshot.KEY_FIELD_ID,
            ParticipantRecoveryReadinessSnapshot.KEY_DURABILITY_TIER,
            ParticipantRecoveryReadinessSnapshot.KEY_REQUIRES_V2_RESYNC,
            ParticipantRecoveryReadinessSnapshot.KEY_SURVIVES_PROCESS_KILL,
            ParticipantRecoveryReadinessSnapshot.KEY_SURVIVES_WS_RECONNECT
        )
        assertEquals(5, keys.size)
    }

    // ── StabilizationBaseline — PR-53 entries ─────────────────────────────────

    @Test
    fun `exactly three entries with introducedPr equal to 53`() {
        val pr53Entries = StabilizationBaseline.entries.filter { it.introducedPr == 53 }
        assertEquals(3, pr53Entries.size)
    }

    @Test
    fun `app-lifecycle-participant-boundary is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("app-lifecycle-participant-boundary")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `hybrid-participant-capability-boundary is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("hybrid-participant-capability-boundary")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `participant-recovery-readiness-snapshot is CANONICAL_STABLE with EXTEND guidance`() {
        val entry = StabilizationBaseline.forId("participant-recovery-readiness-snapshot")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, entry.extensionGuidance)
    }

    @Test
    fun `all PR-53 entries have non-blank rationale`() {
        StabilizationBaseline.entries.filter { it.introducedPr == 53 }.forEach { entry ->
            assertTrue("PR-53 entry ${entry.surfaceId} must have non-blank rationale", entry.rationale.isNotBlank())
        }
    }

    @Test
    fun `all PR-53 entries reference com dot ufo dot galaxy packagePath`() {
        StabilizationBaseline.entries.filter { it.introducedPr == 53 }.forEach { entry ->
            assertTrue(
                "PR-53 entry ${entry.surfaceId} packagePath must reference com.ufo.galaxy",
                entry.packagePath.startsWith("com.ufo.galaxy")
            )
        }
    }

    @Test
    fun `INTRODUCED_PR constant on AppLifecycleParticipantBoundary is 53`() {
        assertEquals(53, AppLifecycleParticipantBoundary.INTRODUCED_PR)
    }

    @Test
    fun `INTRODUCED_PR constant on HybridParticipantCapabilityBoundary is 53`() {
        assertEquals(53, HybridParticipantCapabilityBoundary.INTRODUCED_PR)
    }

    @Test
    fun `INTRODUCED_PR constant on ParticipantRecoveryReadinessSnapshot is 53`() {
        assertEquals(53, ParticipantRecoveryReadinessSnapshot.INTRODUCED_PR)
    }
}
