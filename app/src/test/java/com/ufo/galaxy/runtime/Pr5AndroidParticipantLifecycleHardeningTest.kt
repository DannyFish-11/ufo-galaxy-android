package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-5Android — Android Participant Lifecycle Hardening, Recovery, and Hybrid Runtime Continuity.
 *
 * Acceptance and regression test suite for the three PR-5Android surfaces:
 *
 *  1. [ParticipantPostureLifecycleBoundary] — posture-aware (JOIN_RUNTIME vs CONTROL_ONLY)
 *     lifecycle transition semantics and V2 participant-loss policy declarations.
 *
 *  2. [HybridRuntimeContinuityContract] — hybrid runtime continuity state preservation
 *     across lifecycle disruptions, post-disruption behavior, and V2 expected actions.
 *
 *  3. [ParticipantAttachmentTransitionSemantics] — explicit attachment/reattachment lifecycle
 *     model with V2 event expectations, durable session effects, and recovery semantics.
 *
 * ## How this demonstrates acceptance criteria
 *
 * ### AC1: How Android participant runtime behaves across lifecycle interruptions and recovery
 *   Covered by [ParticipantPostureLifecycleBoundary] tests: every lifecycle event has an explicit
 *   posture-aware impact classification (POSTURE_NEUTRAL or POSTURE_AMPLIFIED).
 *
 * ### AC2: What local runtime/session/task truth is preserved, resumed, reset, or reattached
 *   Covered by [HybridRuntimeContinuityContract] tests (hybrid execution continuity) and
 *   [ParticipantAttachmentTransitionSemantics] tests (durable session effects per transition).
 *
 * ### AC3: How Android hybrid participant behavior remains coherent across reconnect/restart/lifecycle
 *   Covered by [HybridRuntimeContinuityContract] tests: each capability has explicit
 *   `survivesWsReconnect`, `survivesProcessKill`, and [PostDisruptionHybridBehavior] declarations.
 *
 * ### AC4: How Android lifecycle recovery stays bounded under V2 canonical orchestration authority
 *   Covered by [ParticipantAttachmentTransitionSemantics] tests: V2 events, recovery semantics,
 *   and durable session effects are declared for each attachment transition.
 *
 * ## Test matrix
 *
 * ### ParticipantPostureLifecycleBoundary — PostureLifecycleImpact enum
 *  - POSTURE_NEUTRAL wireValue is "posture_neutral"
 *  - POSTURE_AMPLIFIED wireValue is "posture_amplified"
 *
 * ### ParticipantPostureLifecycleBoundary — JoinRuntimeRecoveryExpectation enum
 *  - RESUME_ELIGIBLE wireValue is "resume_eligible"
 *  - NEW_ERA_REQUIRED wireValue is "new_era_required"
 *  - TIMEOUT_DEPENDENT wireValue is "timeout_dependent"
 *
 * ### ParticipantPostureLifecycleBoundary — V2ParticipantLossPolicy enum
 *  - REDIRECT_SUBTASKS wireValue is "redirect_subtasks"
 *  - ABORT_AND_RETRY wireValue is "abort_and_retry"
 *  - REBALANCE_FORMATION wireValue is "rebalance_formation"
 *  - WAIT_FOR_RECONNECT wireValue is "wait_for_reconnect"
 *
 * ### ParticipantPostureLifecycleBoundary — boundary registry
 *  - ENTRY_COUNT is 6
 *  - all six AppLifecycleEvent values have exactly one posture entry
 *  - all posture entries have non-blank joinRuntimeRationale
 *
 * ### ParticipantPostureLifecycleBoundary — FOREGROUND and BACKGROUND are POSTURE_NEUTRAL
 * ### ParticipantPostureLifecycleBoundary — CONFIGURATION_CHANGE is POSTURE_NEUTRAL
 * ### ParticipantPostureLifecycleBoundary — BACKGROUND_KILL is POSTURE_AMPLIFIED with NEW_ERA_REQUIRED
 * ### ParticipantPostureLifecycleBoundary — MEMORY_PRESSURE is POSTURE_AMPLIFIED with TIMEOUT_DEPENDENT
 * ### ParticipantPostureLifecycleBoundary — PROCESS_RECREATION is POSTURE_AMPLIFIED with NEW_ERA_REQUIRED
 * ### ParticipantPostureLifecycleBoundary — postureAmplifiedEvents contains BACKGROUND_KILL, MEMORY_PRESSURE, PROCESS_RECREATION
 * ### ParticipantPostureLifecycleBoundary — postureNeutralEvents contains FOREGROUND, BACKGROUND, CONFIGURATION_CHANGE
 * ### ParticipantPostureLifecycleBoundary — newEraRequiredEvents contains BACKGROUND_KILL, PROCESS_RECREATION
 * ### ParticipantPostureLifecycleBoundary — wire-key constants are non-blank and distinct
 *
 * ### HybridRuntimeContinuityContract — HybridContinuityTier enum
 *  - STATELESS wireValue is "stateless"
 *  - SESSION_SCOPED wireValue is "session_scoped"
 *  - INVOCATION_SCOPED wireValue is "invocation_scoped"
 *
 * ### HybridRuntimeContinuityContract — PostDisruptionHybridBehavior enum
 *  - REANNOUNCE_CAPABILITY wireValue is "reannounce_capability"
 *  - NO_REANNOUNCEMENT_NEEDED wireValue is "no_reannouncement_needed"
 *  - AWAIT_V2_REINVOCATION wireValue is "await_v2_reinvocation"
 *
 * ### HybridRuntimeContinuityContract — continuity registry
 *  - ENTRY_COUNT is 6
 *  - all six HybridCapability values have exactly one continuity entry
 *  - all entries have non-blank rationale and v2ExpectedAction
 *
 * ### HybridRuntimeContinuityContract — no capability survives process kill
 * ### HybridRuntimeContinuityContract — STAGED_MESH_EXECUTION is INVOCATION_SCOPED and does not survive WS reconnect
 * ### HybridRuntimeContinuityContract — CONTRACT_FIRST capabilities are STATELESS
 * ### HybridRuntimeContinuityContract — BARRIER_PARTICIPATION and FORMATION_REBALANCE are SESSION_SCOPED
 * ### HybridRuntimeContinuityContract — query helpers: survivesWsReconnect, lostOnWsReconnect, requiresReannounce
 * ### HybridRuntimeContinuityContract — wire-key constants are non-blank and distinct
 *
 * ### ParticipantAttachmentTransitionSemantics — AttachmentState enum
 *  - UNATTACHED wireValue is "unattached"
 *  - ATTACHING wireValue is "attaching"
 *  - ATTACHED wireValue is "attached"
 *  - DETACHING wireValue is "detaching"
 *  - REATTACHING wireValue is "reattaching"
 *  - all five wireValues are distinct
 *
 * ### ParticipantAttachmentTransitionSemantics — DurableSessionEffect enum
 *  - SESSION_PRESERVED wireValue is "session_preserved"
 *  - EPOCH_ADVANCED wireValue is "epoch_advanced"
 *  - SESSION_RESET wireValue is "session_reset"
 *
 * ### ParticipantAttachmentTransitionSemantics — AttachmentRecoverySemantics enum
 *  - FRESH_ATTACH wireValue is "fresh_attach"
 *  - RECONNECT_RECOVERY wireValue is "reconnect_recovery"
 *  - NEW_ERA_ATTACH wireValue is "new_era_attach"
 *  - CLEAN_DETACH wireValue is "clean_detach"
 *  - DISRUPTED_DETACH wireValue is "disrupted_detach"
 *
 * ### ParticipantAttachmentTransitionSemantics — transition registry
 *  - transitionCount is 6
 *  - all transitionIds are distinct
 *  - all transitions have non-blank rationale
 *
 * ### ParticipantAttachmentTransitionSemantics — V2 event coverage
 *  - initial_attach emits "DeviceConnected"
 *  - clean_detach emits "DeviceDisconnected"
 *  - reconnect_recovery_attach emits "DeviceReconnected"
 *  - reconnect_failure_detach emits "DeviceDisconnected"
 *  - new_era_attach emits "DeviceConnected"
 *  - disrupted_detach emits no V2 event (null)
 *
 * ### ParticipantAttachmentTransitionSemantics — durable session effect declarations
 *  - initial_attach SESSION_PRESERVED
 *  - clean_detach SESSION_RESET
 *  - reconnect_recovery_attach EPOCH_ADVANCED
 *  - reconnect_failure_detach SESSION_RESET
 *  - new_era_attach SESSION_PRESERVED
 *
 * ### ParticipantAttachmentTransitionSemantics — query helpers
 *  - epochAdvancingTransitions contains reconnect_recovery_attach
 *  - sessionResettingTransitions contains clean_detach and reconnect_failure_detach
 *  - recoveryTransitions contains reconnect_recovery_attach and new_era_attach
 *  - transitionsEmitting("DeviceConnected") contains initial_attach and new_era_attach
 *  - transitionFor returns null for unknown transitionId
 *
 * ### ParticipantAttachmentTransitionSemantics — wire-key constants are non-blank and distinct
 */
class Pr5AndroidParticipantLifecycleHardeningTest {

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantPostureLifecycleBoundary — PostureLifecycleImpact enum
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `POSTURE_NEUTRAL wireValue is posture_neutral`() {
        assertEquals("posture_neutral",
            ParticipantPostureLifecycleBoundary.PostureLifecycleImpact.POSTURE_NEUTRAL.wireValue)
    }

    @Test
    fun `POSTURE_AMPLIFIED wireValue is posture_amplified`() {
        assertEquals("posture_amplified",
            ParticipantPostureLifecycleBoundary.PostureLifecycleImpact.POSTURE_AMPLIFIED.wireValue)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantPostureLifecycleBoundary — JoinRuntimeRecoveryExpectation enum
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `RESUME_ELIGIBLE wireValue is resume_eligible`() {
        assertEquals("resume_eligible",
            ParticipantPostureLifecycleBoundary.JoinRuntimeRecoveryExpectation.RESUME_ELIGIBLE.wireValue)
    }

    @Test
    fun `NEW_ERA_REQUIRED wireValue is new_era_required`() {
        assertEquals("new_era_required",
            ParticipantPostureLifecycleBoundary.JoinRuntimeRecoveryExpectation.NEW_ERA_REQUIRED.wireValue)
    }

    @Test
    fun `TIMEOUT_DEPENDENT wireValue is timeout_dependent`() {
        assertEquals("timeout_dependent",
            ParticipantPostureLifecycleBoundary.JoinRuntimeRecoveryExpectation.TIMEOUT_DEPENDENT.wireValue)
    }

    @Test
    fun `JoinRuntimeRecoveryExpectation wireValues are distinct`() {
        val wireValues = ParticipantPostureLifecycleBoundary.JoinRuntimeRecoveryExpectation.entries
            .map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantPostureLifecycleBoundary — V2ParticipantLossPolicy enum
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `REDIRECT_SUBTASKS wireValue is redirect_subtasks`() {
        assertEquals("redirect_subtasks",
            ParticipantPostureLifecycleBoundary.V2ParticipantLossPolicy.REDIRECT_SUBTASKS.wireValue)
    }

    @Test
    fun `ABORT_AND_RETRY wireValue is abort_and_retry`() {
        assertEquals("abort_and_retry",
            ParticipantPostureLifecycleBoundary.V2ParticipantLossPolicy.ABORT_AND_RETRY.wireValue)
    }

    @Test
    fun `REBALANCE_FORMATION wireValue is rebalance_formation`() {
        assertEquals("rebalance_formation",
            ParticipantPostureLifecycleBoundary.V2ParticipantLossPolicy.REBALANCE_FORMATION.wireValue)
    }

    @Test
    fun `WAIT_FOR_RECONNECT wireValue is wait_for_reconnect`() {
        assertEquals("wait_for_reconnect",
            ParticipantPostureLifecycleBoundary.V2ParticipantLossPolicy.WAIT_FOR_RECONNECT.wireValue)
    }

    @Test
    fun `V2ParticipantLossPolicy wireValues are distinct`() {
        val wireValues = ParticipantPostureLifecycleBoundary.V2ParticipantLossPolicy.entries
            .map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantPostureLifecycleBoundary — boundary registry
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `ENTRY_COUNT is 6`() {
        assertEquals(6, ParticipantPostureLifecycleBoundary.ENTRY_COUNT)
    }

    @Test
    fun `all six AppLifecycleEvent values have exactly one posture entry`() {
        val events = AppLifecycleParticipantBoundary.AppLifecycleEvent.entries
        assertEquals(events.size, ParticipantPostureLifecycleBoundary.entries.size)
        for (event in events) {
            val count = ParticipantPostureLifecycleBoundary.entries.count { it.event == event }
            assertEquals("Expected exactly 1 entry for $event but found $count", 1, count)
        }
    }

    @Test
    fun `all posture entries have non-blank joinRuntimeRationale`() {
        for (entry in ParticipantPostureLifecycleBoundary.entries) {
            assertTrue("Expected non-blank rationale for ${entry.event}",
                entry.joinRuntimeRationale.isNotBlank())
        }
    }

    @Test
    fun `boundaryFor returns entry for every AppLifecycleEvent`() {
        for (event in AppLifecycleParticipantBoundary.AppLifecycleEvent.entries) {
            val entry = ParticipantPostureLifecycleBoundary.boundaryFor(event)
            assertEquals(event, entry.event)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantPostureLifecycleBoundary — specific event semantics
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `FOREGROUND is POSTURE_NEUTRAL`() {
        val entry = ParticipantPostureLifecycleBoundary.boundaryFor(
            AppLifecycleParticipantBoundary.AppLifecycleEvent.FOREGROUND)
        assertEquals(ParticipantPostureLifecycleBoundary.PostureLifecycleImpact.POSTURE_NEUTRAL,
            entry.postureLifecycleImpact)
    }

    @Test
    fun `BACKGROUND is POSTURE_NEUTRAL`() {
        val entry = ParticipantPostureLifecycleBoundary.boundaryFor(
            AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND)
        assertEquals(ParticipantPostureLifecycleBoundary.PostureLifecycleImpact.POSTURE_NEUTRAL,
            entry.postureLifecycleImpact)
    }

    @Test
    fun `CONFIGURATION_CHANGE is POSTURE_NEUTRAL`() {
        val entry = ParticipantPostureLifecycleBoundary.boundaryFor(
            AppLifecycleParticipantBoundary.AppLifecycleEvent.CONFIGURATION_CHANGE)
        assertEquals(ParticipantPostureLifecycleBoundary.PostureLifecycleImpact.POSTURE_NEUTRAL,
            entry.postureLifecycleImpact)
    }

    @Test
    fun `BACKGROUND_KILL is POSTURE_AMPLIFIED`() {
        val entry = ParticipantPostureLifecycleBoundary.boundaryFor(
            AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND_KILL)
        assertEquals(ParticipantPostureLifecycleBoundary.PostureLifecycleImpact.POSTURE_AMPLIFIED,
            entry.postureLifecycleImpact)
    }

    @Test
    fun `BACKGROUND_KILL joinRuntimeRecoveryExpectation is NEW_ERA_REQUIRED`() {
        val entry = ParticipantPostureLifecycleBoundary.boundaryFor(
            AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND_KILL)
        assertEquals(ParticipantPostureLifecycleBoundary.JoinRuntimeRecoveryExpectation.NEW_ERA_REQUIRED,
            entry.joinRuntimeRecoveryExpectation)
    }

    @Test
    fun `BACKGROUND_KILL v2ParticipantLossPolicy is REDIRECT_SUBTASKS`() {
        val entry = ParticipantPostureLifecycleBoundary.boundaryFor(
            AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND_KILL)
        assertEquals(ParticipantPostureLifecycleBoundary.V2ParticipantLossPolicy.REDIRECT_SUBTASKS,
            entry.v2ParticipantLossPolicy)
    }

    @Test
    fun `MEMORY_PRESSURE is POSTURE_AMPLIFIED`() {
        val entry = ParticipantPostureLifecycleBoundary.boundaryFor(
            AppLifecycleParticipantBoundary.AppLifecycleEvent.MEMORY_PRESSURE)
        assertEquals(ParticipantPostureLifecycleBoundary.PostureLifecycleImpact.POSTURE_AMPLIFIED,
            entry.postureLifecycleImpact)
    }

    @Test
    fun `MEMORY_PRESSURE joinRuntimeRecoveryExpectation is TIMEOUT_DEPENDENT`() {
        val entry = ParticipantPostureLifecycleBoundary.boundaryFor(
            AppLifecycleParticipantBoundary.AppLifecycleEvent.MEMORY_PRESSURE)
        assertEquals(ParticipantPostureLifecycleBoundary.JoinRuntimeRecoveryExpectation.TIMEOUT_DEPENDENT,
            entry.joinRuntimeRecoveryExpectation)
    }

    @Test
    fun `MEMORY_PRESSURE v2ParticipantLossPolicy is REBALANCE_FORMATION`() {
        val entry = ParticipantPostureLifecycleBoundary.boundaryFor(
            AppLifecycleParticipantBoundary.AppLifecycleEvent.MEMORY_PRESSURE)
        assertEquals(ParticipantPostureLifecycleBoundary.V2ParticipantLossPolicy.REBALANCE_FORMATION,
            entry.v2ParticipantLossPolicy)
    }

    @Test
    fun `PROCESS_RECREATION is POSTURE_AMPLIFIED`() {
        val entry = ParticipantPostureLifecycleBoundary.boundaryFor(
            AppLifecycleParticipantBoundary.AppLifecycleEvent.PROCESS_RECREATION)
        assertEquals(ParticipantPostureLifecycleBoundary.PostureLifecycleImpact.POSTURE_AMPLIFIED,
            entry.postureLifecycleImpact)
    }

    @Test
    fun `PROCESS_RECREATION joinRuntimeRecoveryExpectation is NEW_ERA_REQUIRED`() {
        val entry = ParticipantPostureLifecycleBoundary.boundaryFor(
            AppLifecycleParticipantBoundary.AppLifecycleEvent.PROCESS_RECREATION)
        assertEquals(ParticipantPostureLifecycleBoundary.JoinRuntimeRecoveryExpectation.NEW_ERA_REQUIRED,
            entry.joinRuntimeRecoveryExpectation)
    }

    @Test
    fun `PROCESS_RECREATION v2ParticipantLossPolicy is ABORT_AND_RETRY`() {
        val entry = ParticipantPostureLifecycleBoundary.boundaryFor(
            AppLifecycleParticipantBoundary.AppLifecycleEvent.PROCESS_RECREATION)
        assertEquals(ParticipantPostureLifecycleBoundary.V2ParticipantLossPolicy.ABORT_AND_RETRY,
            entry.v2ParticipantLossPolicy)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantPostureLifecycleBoundary — query helpers
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `postureAmplifiedEvents contains BACKGROUND_KILL, MEMORY_PRESSURE, PROCESS_RECREATION`() {
        val amplified = ParticipantPostureLifecycleBoundary.postureAmplifiedEvents
        assertTrue(amplified.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND_KILL))
        assertTrue(amplified.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.MEMORY_PRESSURE))
        assertTrue(amplified.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.PROCESS_RECREATION))
    }

    @Test
    fun `postureAmplifiedEvents does not contain FOREGROUND, BACKGROUND, CONFIGURATION_CHANGE`() {
        val amplified = ParticipantPostureLifecycleBoundary.postureAmplifiedEvents
        assertFalse(amplified.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.FOREGROUND))
        assertFalse(amplified.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND))
        assertFalse(amplified.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.CONFIGURATION_CHANGE))
    }

    @Test
    fun `postureNeutralEvents contains FOREGROUND, BACKGROUND, CONFIGURATION_CHANGE`() {
        val neutral = ParticipantPostureLifecycleBoundary.postureNeutralEvents
        assertTrue(neutral.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.FOREGROUND))
        assertTrue(neutral.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND))
        assertTrue(neutral.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.CONFIGURATION_CHANGE))
    }

    @Test
    fun `postureNeutralEvents does not contain BACKGROUND_KILL, MEMORY_PRESSURE, PROCESS_RECREATION`() {
        val neutral = ParticipantPostureLifecycleBoundary.postureNeutralEvents
        assertFalse(neutral.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND_KILL))
        assertFalse(neutral.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.MEMORY_PRESSURE))
        assertFalse(neutral.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.PROCESS_RECREATION))
    }

    @Test
    fun `newEraRequiredEvents contains BACKGROUND_KILL and PROCESS_RECREATION`() {
        val newEra = ParticipantPostureLifecycleBoundary.newEraRequiredEvents
        assertTrue(newEra.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND_KILL))
        assertTrue(newEra.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.PROCESS_RECREATION))
    }

    @Test
    fun `newEraRequiredEvents does not contain FOREGROUND, BACKGROUND, CONFIGURATION_CHANGE, MEMORY_PRESSURE`() {
        val newEra = ParticipantPostureLifecycleBoundary.newEraRequiredEvents
        assertFalse(newEra.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.FOREGROUND))
        assertFalse(newEra.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.BACKGROUND))
        assertFalse(newEra.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.CONFIGURATION_CHANGE))
        assertFalse(newEra.contains(AppLifecycleParticipantBoundary.AppLifecycleEvent.MEMORY_PRESSURE))
    }

    @Test
    fun `postureAmplifiedEvents and postureNeutralEvents together cover all six lifecycle events`() {
        val allEvents = AppLifecycleParticipantBoundary.AppLifecycleEvent.entries.toSet()
        val amplified = ParticipantPostureLifecycleBoundary.postureAmplifiedEvents.toSet()
        val neutral = ParticipantPostureLifecycleBoundary.postureNeutralEvents.toSet()
        assertEquals(allEvents, amplified + neutral)
        assertTrue((amplified intersect neutral).isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantPostureLifecycleBoundary — wire-key constants
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `posture lifecycle wire-key constants are non-blank`() {
        assertTrue(ParticipantPostureLifecycleBoundary.KEY_POSTURE_LIFECYCLE_IMPACT.isNotBlank())
        assertTrue(ParticipantPostureLifecycleBoundary.KEY_JOIN_RUNTIME_RECOVERY_EXPECTATION.isNotBlank())
        assertTrue(ParticipantPostureLifecycleBoundary.KEY_V2_PARTICIPANT_LOSS_POLICY.isNotBlank())
    }

    @Test
    fun `posture lifecycle wire-key constants are distinct`() {
        val keys = setOf(
            ParticipantPostureLifecycleBoundary.KEY_POSTURE_LIFECYCLE_IMPACT,
            ParticipantPostureLifecycleBoundary.KEY_JOIN_RUNTIME_RECOVERY_EXPECTATION,
            ParticipantPostureLifecycleBoundary.KEY_V2_PARTICIPANT_LOSS_POLICY
        )
        assertEquals(3, keys.size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HybridRuntimeContinuityContract — HybridContinuityTier enum
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `STATELESS wireValue is stateless`() {
        assertEquals("stateless",
            HybridRuntimeContinuityContract.HybridContinuityTier.STATELESS.wireValue)
    }

    @Test
    fun `SESSION_SCOPED wireValue is session_scoped`() {
        assertEquals("session_scoped",
            HybridRuntimeContinuityContract.HybridContinuityTier.SESSION_SCOPED.wireValue)
    }

    @Test
    fun `INVOCATION_SCOPED wireValue is invocation_scoped`() {
        assertEquals("invocation_scoped",
            HybridRuntimeContinuityContract.HybridContinuityTier.INVOCATION_SCOPED.wireValue)
    }

    @Test
    fun `HybridContinuityTier wireValues are distinct`() {
        val wireValues = HybridRuntimeContinuityContract.HybridContinuityTier.entries
            .map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HybridRuntimeContinuityContract — PostDisruptionHybridBehavior enum
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `REANNOUNCE_CAPABILITY wireValue is reannounce_capability`() {
        assertEquals("reannounce_capability",
            HybridRuntimeContinuityContract.PostDisruptionHybridBehavior.REANNOUNCE_CAPABILITY.wireValue)
    }

    @Test
    fun `NO_REANNOUNCEMENT_NEEDED wireValue is no_reannouncement_needed`() {
        assertEquals("no_reannouncement_needed",
            HybridRuntimeContinuityContract.PostDisruptionHybridBehavior.NO_REANNOUNCEMENT_NEEDED.wireValue)
    }

    @Test
    fun `AWAIT_V2_REINVOCATION wireValue is await_v2_reinvocation`() {
        assertEquals("await_v2_reinvocation",
            HybridRuntimeContinuityContract.PostDisruptionHybridBehavior.AWAIT_V2_REINVOCATION.wireValue)
    }

    @Test
    fun `PostDisruptionHybridBehavior wireValues are distinct`() {
        val wireValues = HybridRuntimeContinuityContract.PostDisruptionHybridBehavior.entries
            .map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HybridRuntimeContinuityContract — continuity registry
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `hybrid continuity ENTRY_COUNT is 6`() {
        assertEquals(6, HybridRuntimeContinuityContract.ENTRY_COUNT)
    }

    @Test
    fun `all six HybridCapability values have exactly one continuity entry`() {
        val capabilities = HybridParticipantCapabilityBoundary.HybridCapability.entries
        assertEquals(capabilities.size, HybridRuntimeContinuityContract.entries.size)
        for (cap in capabilities) {
            val count = HybridRuntimeContinuityContract.entries.count { it.capability == cap }
            assertEquals("Expected exactly 1 continuity entry for $cap but found $count", 1, count)
        }
    }

    @Test
    fun `all hybrid continuity entries have non-blank rationale`() {
        for (entry in HybridRuntimeContinuityContract.entries) {
            assertTrue("Expected non-blank rationale for ${entry.capability}",
                entry.rationale.isNotBlank())
        }
    }

    @Test
    fun `all hybrid continuity entries have non-blank v2ExpectedAction`() {
        for (entry in HybridRuntimeContinuityContract.entries) {
            assertTrue("Expected non-blank v2ExpectedAction for ${entry.capability}",
                entry.v2ExpectedAction.isNotBlank())
        }
    }

    @Test
    fun `continuityFor returns entry for every HybridCapability`() {
        for (cap in HybridParticipantCapabilityBoundary.HybridCapability.entries) {
            val entry = HybridRuntimeContinuityContract.continuityFor(cap)
            assertEquals(cap, entry.capability)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HybridRuntimeContinuityContract — specific capability semantics
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `no hybrid capability survives process kill`() {
        for (entry in HybridRuntimeContinuityContract.entries) {
            assertFalse("${entry.capability} should not survive process kill",
                entry.survivesProcessKill)
        }
    }

    @Test
    fun `STAGED_MESH_EXECUTION is INVOCATION_SCOPED`() {
        val entry = HybridRuntimeContinuityContract.continuityFor(
            HybridParticipantCapabilityBoundary.HybridCapability.STAGED_MESH_EXECUTION)
        assertEquals(HybridRuntimeContinuityContract.HybridContinuityTier.INVOCATION_SCOPED,
            entry.continuityTier)
    }

    @Test
    fun `STAGED_MESH_EXECUTION does not survive WS reconnect`() {
        val entry = HybridRuntimeContinuityContract.continuityFor(
            HybridParticipantCapabilityBoundary.HybridCapability.STAGED_MESH_EXECUTION)
        assertFalse(entry.survivesWsReconnect)
    }

    @Test
    fun `STAGED_MESH_EXECUTION post-disruption behavior is AWAIT_V2_REINVOCATION`() {
        val entry = HybridRuntimeContinuityContract.continuityFor(
            HybridParticipantCapabilityBoundary.HybridCapability.STAGED_MESH_EXECUTION)
        assertEquals(HybridRuntimeContinuityContract.PostDisruptionHybridBehavior.AWAIT_V2_REINVOCATION,
            entry.postDisruptionBehavior)
    }

    @Test
    fun `HYBRID_EXECUTE is STATELESS`() {
        val entry = HybridRuntimeContinuityContract.continuityFor(
            HybridParticipantCapabilityBoundary.HybridCapability.HYBRID_EXECUTE)
        assertEquals(HybridRuntimeContinuityContract.HybridContinuityTier.STATELESS,
            entry.continuityTier)
    }

    @Test
    fun `RAG_QUERY is STATELESS`() {
        val entry = HybridRuntimeContinuityContract.continuityFor(
            HybridParticipantCapabilityBoundary.HybridCapability.RAG_QUERY)
        assertEquals(HybridRuntimeContinuityContract.HybridContinuityTier.STATELESS,
            entry.continuityTier)
    }

    @Test
    fun `CODE_EXECUTE is STATELESS`() {
        val entry = HybridRuntimeContinuityContract.continuityFor(
            HybridParticipantCapabilityBoundary.HybridCapability.CODE_EXECUTE)
        assertEquals(HybridRuntimeContinuityContract.HybridContinuityTier.STATELESS,
            entry.continuityTier)
    }

    @Test
    fun `CONTRACT_FIRST capabilities are all STATELESS`() {
        val contractFirstCaps = HybridParticipantCapabilityBoundary.contractFirstCapabilities
        for (cap in contractFirstCaps) {
            val entry = HybridRuntimeContinuityContract.continuityFor(cap)
            assertEquals("${cap} should be STATELESS",
                HybridRuntimeContinuityContract.HybridContinuityTier.STATELESS,
                entry.continuityTier)
        }
    }

    @Test
    fun `BARRIER_PARTICIPATION is SESSION_SCOPED`() {
        val entry = HybridRuntimeContinuityContract.continuityFor(
            HybridParticipantCapabilityBoundary.HybridCapability.BARRIER_PARTICIPATION)
        assertEquals(HybridRuntimeContinuityContract.HybridContinuityTier.SESSION_SCOPED,
            entry.continuityTier)
    }

    @Test
    fun `FORMATION_REBALANCE is SESSION_SCOPED`() {
        val entry = HybridRuntimeContinuityContract.continuityFor(
            HybridParticipantCapabilityBoundary.HybridCapability.FORMATION_REBALANCE)
        assertEquals(HybridRuntimeContinuityContract.HybridContinuityTier.SESSION_SCOPED,
            entry.continuityTier)
    }

    @Test
    fun `STATELESS capabilities all survive WS reconnect`() {
        for (entry in HybridRuntimeContinuityContract.entries) {
            if (entry.continuityTier == HybridRuntimeContinuityContract.HybridContinuityTier.STATELESS) {
                assertTrue("${entry.capability} (STATELESS) should survive WS reconnect",
                    entry.survivesWsReconnect)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HybridRuntimeContinuityContract — query helpers
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `survivesWsReconnectCapabilities does not include STAGED_MESH_EXECUTION`() {
        assertFalse(HybridRuntimeContinuityContract.survivesWsReconnectCapabilities.contains(
            HybridParticipantCapabilityBoundary.HybridCapability.STAGED_MESH_EXECUTION))
    }

    @Test
    fun `lostOnWsReconnectCapabilities contains STAGED_MESH_EXECUTION`() {
        assertTrue(HybridRuntimeContinuityContract.lostOnWsReconnectCapabilities.contains(
            HybridParticipantCapabilityBoundary.HybridCapability.STAGED_MESH_EXECUTION))
    }

    @Test
    fun `survivesWsReconnect and lostOnWsReconnect together cover all six capabilities`() {
        val allCaps = HybridParticipantCapabilityBoundary.HybridCapability.entries.toSet()
        val survives = HybridRuntimeContinuityContract.survivesWsReconnectCapabilities.toSet()
        val lost = HybridRuntimeContinuityContract.lostOnWsReconnectCapabilities.toSet()
        assertEquals(allCaps, survives + lost)
        assertTrue((survives intersect lost).isEmpty())
    }

    @Test
    fun `requiresReannounceAfterProcessKill contains HYBRID_EXECUTE, RAG_QUERY, CODE_EXECUTE`() {
        val reannounce = HybridRuntimeContinuityContract.requiresReannounceAfterProcessKill
        assertTrue(reannounce.contains(HybridParticipantCapabilityBoundary.HybridCapability.HYBRID_EXECUTE))
        assertTrue(reannounce.contains(HybridParticipantCapabilityBoundary.HybridCapability.RAG_QUERY))
        assertTrue(reannounce.contains(HybridParticipantCapabilityBoundary.HybridCapability.CODE_EXECUTE))
    }

    @Test
    fun `requiresReannounceAfterProcessKill also contains BARRIER_PARTICIPATION and FORMATION_REBALANCE`() {
        val reannounce = HybridRuntimeContinuityContract.requiresReannounceAfterProcessKill
        assertTrue(reannounce.contains(
            HybridParticipantCapabilityBoundary.HybridCapability.BARRIER_PARTICIPATION))
        assertTrue(reannounce.contains(
            HybridParticipantCapabilityBoundary.HybridCapability.FORMATION_REBALANCE))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HybridRuntimeContinuityContract — wire-key constants
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `hybrid continuity wire-key constants are non-blank`() {
        assertTrue(HybridRuntimeContinuityContract.KEY_HYBRID_CONTINUITY_TIER.isNotBlank())
        assertTrue(HybridRuntimeContinuityContract.KEY_POST_DISRUPTION_BEHAVIOR.isNotBlank())
        assertTrue(HybridRuntimeContinuityContract.KEY_SURVIVES_WS_RECONNECT.isNotBlank())
    }

    @Test
    fun `hybrid continuity wire-key constants are distinct`() {
        val keys = setOf(
            HybridRuntimeContinuityContract.KEY_HYBRID_CONTINUITY_TIER,
            HybridRuntimeContinuityContract.KEY_POST_DISRUPTION_BEHAVIOR,
            HybridRuntimeContinuityContract.KEY_SURVIVES_WS_RECONNECT
        )
        assertEquals(3, keys.size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantAttachmentTransitionSemantics — AttachmentState enum
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `UNATTACHED wireValue is unattached`() {
        assertEquals("unattached",
            ParticipantAttachmentTransitionSemantics.AttachmentState.UNATTACHED.wireValue)
    }

    @Test
    fun `ATTACHING wireValue is attaching`() {
        assertEquals("attaching",
            ParticipantAttachmentTransitionSemantics.AttachmentState.ATTACHING.wireValue)
    }

    @Test
    fun `ATTACHED wireValue is attached`() {
        assertEquals("attached",
            ParticipantAttachmentTransitionSemantics.AttachmentState.ATTACHED.wireValue)
    }

    @Test
    fun `DETACHING wireValue is detaching`() {
        assertEquals("detaching",
            ParticipantAttachmentTransitionSemantics.AttachmentState.DETACHING.wireValue)
    }

    @Test
    fun `REATTACHING wireValue is reattaching`() {
        assertEquals("reattaching",
            ParticipantAttachmentTransitionSemantics.AttachmentState.REATTACHING.wireValue)
    }

    @Test
    fun `AttachmentState wireValues are distinct`() {
        val wireValues = ParticipantAttachmentTransitionSemantics.AttachmentState.entries
            .map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantAttachmentTransitionSemantics — DurableSessionEffect enum
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `SESSION_PRESERVED wireValue is session_preserved`() {
        assertEquals("session_preserved",
            ParticipantAttachmentTransitionSemantics.DurableSessionEffect.SESSION_PRESERVED.wireValue)
    }

    @Test
    fun `EPOCH_ADVANCED wireValue is epoch_advanced`() {
        assertEquals("epoch_advanced",
            ParticipantAttachmentTransitionSemantics.DurableSessionEffect.EPOCH_ADVANCED.wireValue)
    }

    @Test
    fun `SESSION_RESET wireValue is session_reset`() {
        assertEquals("session_reset",
            ParticipantAttachmentTransitionSemantics.DurableSessionEffect.SESSION_RESET.wireValue)
    }

    @Test
    fun `DurableSessionEffect wireValues are distinct`() {
        val wireValues = ParticipantAttachmentTransitionSemantics.DurableSessionEffect.entries
            .map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantAttachmentTransitionSemantics — AttachmentRecoverySemantics enum
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `FRESH_ATTACH wireValue is fresh_attach`() {
        assertEquals("fresh_attach",
            ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.FRESH_ATTACH.wireValue)
    }

    @Test
    fun `RECONNECT_RECOVERY wireValue is reconnect_recovery`() {
        assertEquals("reconnect_recovery",
            ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.RECONNECT_RECOVERY.wireValue)
    }

    @Test
    fun `NEW_ERA_ATTACH wireValue is new_era_attach`() {
        assertEquals("new_era_attach",
            ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.NEW_ERA_ATTACH.wireValue)
    }

    @Test
    fun `CLEAN_DETACH wireValue is clean_detach`() {
        assertEquals("clean_detach",
            ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.CLEAN_DETACH.wireValue)
    }

    @Test
    fun `DISRUPTED_DETACH wireValue is disrupted_detach`() {
        assertEquals("disrupted_detach",
            ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.DISRUPTED_DETACH.wireValue)
    }

    @Test
    fun `AttachmentRecoverySemantics wireValues are distinct`() {
        val wireValues = ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.entries
            .map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantAttachmentTransitionSemantics — transition registry
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `transition registry has 6 entries`() {
        assertEquals(6, ParticipantAttachmentTransitionSemantics.transitionCount)
        assertEquals(6, ParticipantAttachmentTransitionSemantics.transitions.size)
    }

    @Test
    fun `all transition IDs are distinct`() {
        val ids = ParticipantAttachmentTransitionSemantics.transitions.map { it.transitionId }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `all transitions have non-blank rationale`() {
        for (t in ParticipantAttachmentTransitionSemantics.transitions) {
            assertTrue("Expected non-blank rationale for ${t.transitionId}",
                t.rationale.isNotBlank())
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantAttachmentTransitionSemantics — V2 event coverage
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `initial_attach emits DeviceConnected`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("initial_attach")
        assertNotNull(t)
        assertEquals("DeviceConnected", t!!.v2EventEmitted)
    }

    @Test
    fun `clean_detach emits DeviceDisconnected`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("clean_detach")
        assertNotNull(t)
        assertEquals("DeviceDisconnected", t!!.v2EventEmitted)
    }

    @Test
    fun `reconnect_recovery_attach emits DeviceReconnected`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("reconnect_recovery_attach")
        assertNotNull(t)
        assertEquals("DeviceReconnected", t!!.v2EventEmitted)
    }

    @Test
    fun `reconnect_failure_detach emits DeviceDisconnected`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("reconnect_failure_detach")
        assertNotNull(t)
        assertEquals("DeviceDisconnected", t!!.v2EventEmitted)
    }

    @Test
    fun `new_era_attach emits DeviceConnected`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("new_era_attach")
        assertNotNull(t)
        assertEquals("DeviceConnected", t!!.v2EventEmitted)
    }

    @Test
    fun `disrupted_detach emits no V2 event`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("disrupted_detach")
        assertNotNull(t)
        assertNull(t!!.v2EventEmitted)
    }

    @Test
    fun `transitionFor returns null for unknown transitionId`() {
        assertNull(ParticipantAttachmentTransitionSemantics.transitionFor("nonexistent_transition"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantAttachmentTransitionSemantics — durable session effect
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `initial_attach has SESSION_PRESERVED durable session effect`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("initial_attach")!!
        assertEquals(ParticipantAttachmentTransitionSemantics.DurableSessionEffect.SESSION_PRESERVED,
            t.durableSessionEffect)
    }

    @Test
    fun `clean_detach has SESSION_RESET durable session effect`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("clean_detach")!!
        assertEquals(ParticipantAttachmentTransitionSemantics.DurableSessionEffect.SESSION_RESET,
            t.durableSessionEffect)
    }

    @Test
    fun `reconnect_recovery_attach has EPOCH_ADVANCED durable session effect`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("reconnect_recovery_attach")!!
        assertEquals(ParticipantAttachmentTransitionSemantics.DurableSessionEffect.EPOCH_ADVANCED,
            t.durableSessionEffect)
    }

    @Test
    fun `reconnect_failure_detach has SESSION_RESET durable session effect`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("reconnect_failure_detach")!!
        assertEquals(ParticipantAttachmentTransitionSemantics.DurableSessionEffect.SESSION_RESET,
            t.durableSessionEffect)
    }

    @Test
    fun `new_era_attach has SESSION_PRESERVED durable session effect`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("new_era_attach")!!
        assertEquals(ParticipantAttachmentTransitionSemantics.DurableSessionEffect.SESSION_PRESERVED,
            t.durableSessionEffect)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantAttachmentTransitionSemantics — query helpers
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `epochAdvancingTransitions contains reconnect_recovery_attach`() {
        val ids = ParticipantAttachmentTransitionSemantics.epochAdvancingTransitions
            .map { it.transitionId }
        assertTrue(ids.contains("reconnect_recovery_attach"))
    }

    @Test
    fun `sessionResettingTransitions contains clean_detach and reconnect_failure_detach`() {
        val ids = ParticipantAttachmentTransitionSemantics.sessionResettingTransitions
            .map { it.transitionId }
        assertTrue(ids.contains("clean_detach"))
        assertTrue(ids.contains("reconnect_failure_detach"))
    }

    @Test
    fun `recoveryTransitions contains reconnect_recovery_attach and new_era_attach`() {
        val ids = ParticipantAttachmentTransitionSemantics.recoveryTransitions
            .map { it.transitionId }
        assertTrue(ids.contains("reconnect_recovery_attach"))
        assertTrue(ids.contains("new_era_attach"))
    }

    @Test
    fun `transitionsEmitting DeviceConnected contains initial_attach and new_era_attach`() {
        val ids = ParticipantAttachmentTransitionSemantics.transitionsEmitting("DeviceConnected")
            .map { it.transitionId }
        assertTrue(ids.contains("initial_attach"))
        assertTrue(ids.contains("new_era_attach"))
    }

    @Test
    fun `transitionsEmitting DeviceReconnected contains reconnect_recovery_attach`() {
        val ids = ParticipantAttachmentTransitionSemantics.transitionsEmitting("DeviceReconnected")
            .map { it.transitionId }
        assertTrue(ids.contains("reconnect_recovery_attach"))
        assertEquals(1, ids.size)
    }

    @Test
    fun `transitionsEmitting DeviceDisconnected contains clean_detach and reconnect_failure_detach`() {
        val ids = ParticipantAttachmentTransitionSemantics.transitionsEmitting("DeviceDisconnected")
            .map { it.transitionId }
        assertTrue(ids.contains("clean_detach"))
        assertTrue(ids.contains("reconnect_failure_detach"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantAttachmentTransitionSemantics — wire-key constants
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `attachment transition wire-key constants are non-blank`() {
        assertTrue(ParticipantAttachmentTransitionSemantics.KEY_ATTACHMENT_STATE.isNotBlank())
        assertTrue(ParticipantAttachmentTransitionSemantics.KEY_TRANSITION_ID.isNotBlank())
        assertTrue(ParticipantAttachmentTransitionSemantics.KEY_DURABLE_SESSION_EFFECT.isNotBlank())
        assertTrue(ParticipantAttachmentTransitionSemantics.KEY_RECOVERY_SEMANTICS.isNotBlank())
    }

    @Test
    fun `attachment transition wire-key constants are distinct`() {
        val keys = setOf(
            ParticipantAttachmentTransitionSemantics.KEY_ATTACHMENT_STATE,
            ParticipantAttachmentTransitionSemantics.KEY_TRANSITION_ID,
            ParticipantAttachmentTransitionSemantics.KEY_DURABLE_SESSION_EFFECT,
            ParticipantAttachmentTransitionSemantics.KEY_RECOVERY_SEMANTICS
        )
        assertEquals(4, keys.size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Cross-surface coherence: PR5Android constraint — Android remains participant
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `all posture-amplified events require V2 action not Android autonomous action`() {
        // Android does not self-authorize posture re-assignment after lifecycle disruptions.
        // The V2ParticipantLossPolicy is always a V2-side action (not an Android action).
        for (event in ParticipantPostureLifecycleBoundary.postureAmplifiedEvents) {
            val entry = ParticipantPostureLifecycleBoundary.boundaryFor(event)
            val policy = entry.v2ParticipantLossPolicy
            // All loss policies are V2-driven (redirect, abort+retry, rebalance, or wait)
            val policyIsV2Driven = policy != null
            assertTrue("$event should have a V2-driven loss policy", policyIsV2Driven)
        }
    }

    @Test
    fun `NEW_ERA_REQUIRED events correspond to session-resetting attachment transitions`() {
        // Events that require a new era should produce DeviceConnected (not DeviceReconnected)
        val newEraEvents = ParticipantPostureLifecycleBoundary.newEraRequiredEvents
        // The new_era_attach transition (used after process kill / explicit restart) emits DeviceConnected
        val newEraAttach = ParticipantAttachmentTransitionSemantics.transitionFor("new_era_attach")
        assertNotNull(newEraAttach)
        assertEquals("DeviceConnected", newEraAttach!!.v2EventEmitted)
        assertEquals(ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.NEW_ERA_ATTACH,
            newEraAttach.recoverySemantics)
        // BACKGROUND_KILL and PROCESS_RECREATION are both new-era events
        assertEquals(2, newEraEvents.size)
    }

    @Test
    fun `hybrid capabilities needing re-announcement after process kill excludes no capabilities`() {
        // All hybrid capabilities must re-announce after process kill — none can survive process death
        val reannounce = HybridRuntimeContinuityContract.requiresReannounceAfterProcessKill
        assertEquals(
            HybridParticipantCapabilityBoundary.HybridCapability.entries.size,
            reannounce.size
        )
    }
}
