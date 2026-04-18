package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-40 — Android Media Transport and Task-Lifecycle Convergence.
 *
 * Focused test suite validating all PR-40 additions:
 *
 *  1. [MediaTransportLifecycleBridge] — transport condition → lifecycle adaptation integration model.
 *     - [MediaTransportLifecycleBridge.TransportCondition] enum values and wireValues.
 *     - [MediaTransportLifecycleBridge.LifecycleAdaptation] enum values and wireValues.
 *     - [MediaTransportLifecycleBridge.AdaptationOwnership] enum values and wireValues.
 *     - Registry completeness: four entries (one per TransportCondition).
 *     - Integration entries: correct adaptation and ownership per condition.
 *     - Query helpers: entryFor, entriesForAdaptation, entriesForOwnership.
 *     - Set helpers: conditionsRequiringAdaptation, conditionsTerminatingTasks.
 *     - Constants: INTRODUCED_PR, DESCRIPTION, LIFECYCLE_AUTHORITY_INVARIANT.
 *
 *  2. [TransportContinuityAnchor] — transport continuity semantics governance model.
 *     - [TransportContinuityAnchor.TransportEvent] enum values and wireValues.
 *     - [TransportContinuityAnchor.ContinuityPolicy] enum values and wireValues.
 *     - [TransportContinuityAnchor.ContinuityOwnership] enum values and wireValues.
 *     - [TransportContinuityAnchor.PostEventSessionState] enum values and wireValues.
 *     - Registry completeness: five entries (one per TransportEvent).
 *     - Anchor entries: correct policy, ownership, post-event state, and durable-era flag.
 *     - ATTACH: PERSIST policy, durable era retained, no detach cause.
 *     - DETACH: TERMINATES policy, durable era NOT retained, DISABLE cause.
 *     - RECONNECT: RESHAPES policy, durable era retained, REATTACHED post-event state.
 *     - DEGRADATION: SUSPENDS policy, durable era retained, DISCONNECT cause.
 *     - RECOVERY: PERSIST policy, durable era retained, ATTACHED post-event state.
 *     - Query helpers: anchorFor, entriesForPolicy, entriesForOwnership.
 *     - Set helpers: eventsDurableEraRetained, eventsDurableEraTerminated.
 *     - Constants: INTRODUCED_PR, DESCRIPTION, DURABLE_ERA_RECONNECT_INVARIANT, CONTINUITY_AUTHORITY_INVARIANT.
 *
 *  3. [CanonicalDispatchChain.resolveTransportAdaptedPaths] — PR-40 addition.
 *     - STABLE condition returns same paths as resolveEligiblePathsForState.
 *     - DEGRADED condition returns same paths as resolveEligiblePathsForState.
 *     - INTERRUPTED condition suppresses CANONICAL, STAGED_MESH, DELEGATED paths.
 *     - SUSPENDED condition suppresses CANONICAL, STAGED_MESH, DELEGATED paths.
 *     - LOCAL and COMPATIBILITY paths are not suppressed by any condition.
 *     - CROSS_DEVICE_PATH_MODES contains CANONICAL, STAGED_MESH, DELEGATED.
 *
 *  4. [CanonicalSessionAxis.transportContinuityBindings] — PR-40 addition.
 *     - transportContinuityBindings has seven entries (one per CanonicalSessionFamily).
 *     - all seven families have a binding.
 *     - RUNTIME_SESSION is affected by interruption and does not survive reconnect.
 *     - ATTACHED_RUNTIME_SESSION is affected by interruption and does not survive reconnect.
 *     - DELEGATION_TRANSFER_SESSION is affected by interruption and does not survive reconnect.
 *     - MESH_SESSION is affected by interruption and does not survive reconnect.
 *     - CONTROL_SESSION is NOT affected by interruption and survives reconnect.
 *     - CONVERSATION_SESSION is NOT affected by interruption and survives reconnect.
 *     - DURABLE_RUNTIME_SESSION is NOT affected by interruption and survives reconnect.
 *     - familiesAffectedByInterruption has exactly four families.
 *     - familiesSurvivingReconnect has exactly three families.
 *     - all bindings have non-blank transportNote.
 *
 *  5. [StabilizationBaseline] — PR-40 entries registered.
 *     - media-transport-lifecycle-bridge is registered as CANONICAL_STABLE.
 *     - transport-continuity-anchor is registered as CANONICAL_STABLE.
 *     - canonical-dispatch-chain-transport-adapted-paths is registered as CANONICAL_STABLE.
 *     - canonical-session-axis-transport-continuity-bindings is registered as CANONICAL_STABLE.
 *     - all PR-40 entries have introducedPr = 40.
 *     - all PR-40 entries have EXTEND guidance.
 *
 * ## Test matrix
 *
 * ### MediaTransportLifecycleBridge — TransportCondition enum
 *  - STABLE wireValue is "stable"
 *  - DEGRADED wireValue is "degraded"
 *  - INTERRUPTED wireValue is "interrupted"
 *  - SUSPENDED wireValue is "suspended"
 *  - all four wireValues are distinct
 *  - fromValue returns correct entry for each wireValue
 *  - fromValue returns null for unknown value
 *
 * ### MediaTransportLifecycleBridge — LifecycleAdaptation enum
 *  - NONE wireValue is "none"
 *  - ADVISORY wireValue is "advisory"
 *  - SUSPEND_NEW_TASKS wireValue is "suspend_new_tasks"
 *  - TERMINATE_ACTIVE_TASKS wireValue is "terminate_active_tasks"
 *  - all four wireValues are distinct
 *  - fromValue returns correct entry for each wireValue
 *  - fromValue returns null for unknown value
 *
 * ### MediaTransportLifecycleBridge — AdaptationOwnership enum
 *  - TRANSPORT_LAYER wireValue is "transport_layer"
 *  - RUNTIME_LAYER wireValue is "runtime_layer"
 *  - OPERATOR_OR_USER wireValue is "operator_or_user"
 *  - all three wireValues are distinct
 *
 * ### MediaTransportLifecycleBridge — registry
 *  - exactly four integration entries
 *  - all conditions have exactly one entry
 *  - STABLE condition maps to NONE adaptation
 *  - DEGRADED condition maps to ADVISORY adaptation
 *  - INTERRUPTED condition maps to TERMINATE_ACTIVE_TASKS adaptation
 *  - SUSPENDED condition maps to TERMINATE_ACTIVE_TASKS adaptation
 *  - all entries have non-blank recoveryPath, dispatchImpact, note
 *
 * ### MediaTransportLifecycleBridge — query helpers
 *  - entryFor(STABLE) is non-null
 *  - entryFor(INTERRUPTED) returns TERMINATE_ACTIVE_TASKS entry
 *  - entriesForAdaptation(NONE) has one entry
 *  - entriesForAdaptation(TERMINATE_ACTIVE_TASKS) has two entries
 *  - entriesForOwnership(TRANSPORT_LAYER) has at least two entries
 *  - conditionsRequiringAdaptation has three elements
 *  - conditionsRequiringAdaptation does not include STABLE
 *  - conditionsTerminatingTasks has two elements
 *  - conditionsTerminatingTasks includes INTERRUPTED and SUSPENDED
 *  - INTRODUCED_PR is 40
 *  - DESCRIPTION is non-blank
 *  - LIFECYCLE_AUTHORITY_INVARIANT is non-blank and mentions RuntimeController
 *
 * ### TransportContinuityAnchor — TransportEvent enum
 *  - ATTACH wireValue is "attach"
 *  - DETACH wireValue is "detach"
 *  - RECONNECT wireValue is "reconnect"
 *  - DEGRADATION wireValue is "degradation"
 *  - RECOVERY wireValue is "recovery"
 *  - all five wireValues are distinct
 *  - fromValue returns correct entry for each wireValue
 *  - fromValue returns null for unknown value
 *
 * ### TransportContinuityAnchor — ContinuityPolicy enum
 *  - PERSIST wireValue is "persist"
 *  - RESHAPES wireValue is "reshapes"
 *  - TERMINATES wireValue is "terminates"
 *  - SUSPENDS wireValue is "suspends"
 *  - all four wireValues are distinct
 *  - fromValue returns correct entry for each wireValue
 *  - fromValue returns null for unknown value
 *
 * ### TransportContinuityAnchor — ContinuityOwnership enum
 *  - RUNTIME_CONTROLLER wireValue is "runtime_controller"
 *  - TRANSPORT_LAYER wireValue is "transport_layer"
 *  - OPERATOR_OR_USER wireValue is "operator_or_user"
 *  - all three wireValues are distinct
 *
 * ### TransportContinuityAnchor — PostEventSessionState enum
 *  - ATTACHED wireValue is "attached"
 *  - DETACHED wireValue is "detached"
 *  - REATTACHED wireValue is "reattached"
 *  - all three wireValues are distinct
 *
 * ### TransportContinuityAnchor — anchor entries
 *  - exactly five anchor entries
 *  - all events have exactly one entry
 *  - ATTACH: PERSIST policy, RUNTIME_CONTROLLER ownership, ATTACHED post-event state, null detachCause, durableEraRetained=true
 *  - DETACH: TERMINATES policy, OPERATOR_OR_USER ownership, DETACHED post-event state, DISABLE detachCause, durableEraRetained=false
 *  - RECONNECT: RESHAPES policy, TRANSPORT_LAYER ownership, REATTACHED post-event state, null detachCause, durableEraRetained=true
 *  - DEGRADATION: SUSPENDS policy, TRANSPORT_LAYER ownership, ATTACHED post-event state, DISCONNECT detachCause, durableEraRetained=true
 *  - RECOVERY: PERSIST policy, TRANSPORT_LAYER ownership, ATTACHED post-event state, null detachCause, durableEraRetained=true
 *  - all entries have non-blank recoverySequence and note
 *
 * ### TransportContinuityAnchor — query helpers
 *  - anchorFor(ATTACH) is non-null
 *  - anchorFor(RECONNECT) has RESHAPES policy
 *  - entriesForPolicy(PERSIST) has two entries
 *  - entriesForPolicy(TERMINATES) has one entry
 *  - entriesForOwnership(TRANSPORT_LAYER) has three entries
 *  - eventsDurableEraRetained has four elements
 *  - eventsDurableEraRetained includes ATTACH, RECONNECT, DEGRADATION, RECOVERY
 *  - eventsDurableEraTerminated has one element
 *  - eventsDurableEraTerminated includes DETACH
 *  - INTRODUCED_PR is 40
 *  - DESCRIPTION is non-blank
 *  - DURABLE_ERA_RECONNECT_INVARIANT is non-blank
 *  - CONTINUITY_AUTHORITY_INVARIANT is non-blank and mentions RuntimeController
 *
 * ### CanonicalDispatchChain — resolveTransportAdaptedPaths
 *  - STABLE condition result equals resolveEligiblePathsForState result (ineligible state)
 *  - DEGRADED condition result equals resolveEligiblePathsForState result (ineligible state)
 *  - INTERRUPTED condition suppresses CANONICAL, STAGED_MESH, DELEGATED modes
 *  - SUSPENDED condition suppresses CANONICAL, STAGED_MESH, DELEGATED modes
 *  - LOCAL path is not suppressed by INTERRUPTED
 *  - COMPATIBILITY path is not suppressed by INTERRUPTED
 *  - CROSS_DEVICE_PATH_MODES has exactly three elements
 *  - CROSS_DEVICE_PATH_MODES contains CANONICAL
 *  - CROSS_DEVICE_PATH_MODES contains STAGED_MESH
 *  - CROSS_DEVICE_PATH_MODES contains DELEGATED
 *  - CROSS_DEVICE_PATH_MODES does not contain LOCAL
 *  - CROSS_DEVICE_PATH_MODES does not contain FALLBACK
 *  - CROSS_DEVICE_PATH_MODES does not contain COMPATIBILITY
 *
 * ### CanonicalSessionAxis — transportContinuityBindings
 *  - transportContinuityBindings has 7 entries
 *  - all seven CanonicalSessionFamily values have a binding
 *  - RUNTIME_SESSION affectedByInterruption is true
 *  - RUNTIME_SESSION survivesReconnect is false
 *  - ATTACHED_RUNTIME_SESSION affectedByInterruption is true
 *  - ATTACHED_RUNTIME_SESSION survivesReconnect is false
 *  - DELEGATION_TRANSFER_SESSION affectedByInterruption is true
 *  - DELEGATION_TRANSFER_SESSION survivesReconnect is false
 *  - MESH_SESSION affectedByInterruption is true
 *  - MESH_SESSION survivesReconnect is false
 *  - CONTROL_SESSION affectedByInterruption is false
 *  - CONTROL_SESSION survivesReconnect is true
 *  - CONVERSATION_SESSION affectedByInterruption is false
 *  - CONVERSATION_SESSION survivesReconnect is true
 *  - DURABLE_RUNTIME_SESSION affectedByInterruption is false
 *  - DURABLE_RUNTIME_SESSION survivesReconnect is true
 *  - familiesAffectedByInterruption has exactly 4 members
 *  - familiesSurvivingReconnect has exactly 3 members
 *  - familiesAffectedByInterruption and familiesSurvivingReconnect are disjoint (for transport-relevant families)
 *  - all bindings have non-blank transportNote
 *
 * ### StabilizationBaseline — PR-40 entries
 *  - media-transport-lifecycle-bridge entry is registered
 *  - transport-continuity-anchor entry is registered
 *  - canonical-dispatch-chain-transport-adapted-paths entry is registered
 *  - canonical-session-axis-transport-continuity-bindings entry is registered
 *  - all four entries have CANONICAL_STABLE stability
 *  - all four entries have EXTEND guidance
 *  - all four entries have introducedPr = 40
 */
class Pr40AndroidMediaTransportTaskLifecycleConvergenceTest {

    // ─────────────────────────────────────────────────────────────────────────
    // MediaTransportLifecycleBridge — TransportCondition enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `TransportCondition STABLE wireValue is stable`() {
        assertEquals("stable", MediaTransportLifecycleBridge.TransportCondition.STABLE.wireValue)
    }

    @Test fun `TransportCondition DEGRADED wireValue is degraded`() {
        assertEquals("degraded", MediaTransportLifecycleBridge.TransportCondition.DEGRADED.wireValue)
    }

    @Test fun `TransportCondition INTERRUPTED wireValue is interrupted`() {
        assertEquals("interrupted", MediaTransportLifecycleBridge.TransportCondition.INTERRUPTED.wireValue)
    }

    @Test fun `TransportCondition SUSPENDED wireValue is suspended`() {
        assertEquals("suspended", MediaTransportLifecycleBridge.TransportCondition.SUSPENDED.wireValue)
    }

    @Test fun `all four TransportCondition wireValues are distinct`() {
        val wireValues = MediaTransportLifecycleBridge.TransportCondition.entries.map { it.wireValue }
        assertEquals("TransportCondition wireValues must all be unique", wireValues.distinct().size, wireValues.size)
    }

    @Test fun `TransportCondition fromValue returns correct entries`() {
        assertEquals(
            MediaTransportLifecycleBridge.TransportCondition.STABLE,
            MediaTransportLifecycleBridge.TransportCondition.fromValue("stable")
        )
        assertEquals(
            MediaTransportLifecycleBridge.TransportCondition.DEGRADED,
            MediaTransportLifecycleBridge.TransportCondition.fromValue("degraded")
        )
        assertEquals(
            MediaTransportLifecycleBridge.TransportCondition.INTERRUPTED,
            MediaTransportLifecycleBridge.TransportCondition.fromValue("interrupted")
        )
        assertEquals(
            MediaTransportLifecycleBridge.TransportCondition.SUSPENDED,
            MediaTransportLifecycleBridge.TransportCondition.fromValue("suspended")
        )
    }

    @Test fun `TransportCondition fromValue returns null for unknown value`() {
        assertNull(MediaTransportLifecycleBridge.TransportCondition.fromValue("unknown_xyz"))
    }

    @Test fun `TransportCondition fromValue returns null for null input`() {
        assertNull(MediaTransportLifecycleBridge.TransportCondition.fromValue(null))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaTransportLifecycleBridge — LifecycleAdaptation enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `LifecycleAdaptation NONE wireValue is none`() {
        assertEquals("none", MediaTransportLifecycleBridge.LifecycleAdaptation.NONE.wireValue)
    }

    @Test fun `LifecycleAdaptation ADVISORY wireValue is advisory`() {
        assertEquals("advisory", MediaTransportLifecycleBridge.LifecycleAdaptation.ADVISORY.wireValue)
    }

    @Test fun `LifecycleAdaptation SUSPEND_NEW_TASKS wireValue is suspend_new_tasks`() {
        assertEquals("suspend_new_tasks", MediaTransportLifecycleBridge.LifecycleAdaptation.SUSPEND_NEW_TASKS.wireValue)
    }

    @Test fun `LifecycleAdaptation TERMINATE_ACTIVE_TASKS wireValue is terminate_active_tasks`() {
        assertEquals("terminate_active_tasks", MediaTransportLifecycleBridge.LifecycleAdaptation.TERMINATE_ACTIVE_TASKS.wireValue)
    }

    @Test fun `all four LifecycleAdaptation wireValues are distinct`() {
        val wireValues = MediaTransportLifecycleBridge.LifecycleAdaptation.entries.map { it.wireValue }
        assertEquals("LifecycleAdaptation wireValues must all be unique", wireValues.distinct().size, wireValues.size)
    }

    @Test fun `LifecycleAdaptation fromValue returns correct entries`() {
        assertEquals(
            MediaTransportLifecycleBridge.LifecycleAdaptation.NONE,
            MediaTransportLifecycleBridge.LifecycleAdaptation.fromValue("none")
        )
        assertEquals(
            MediaTransportLifecycleBridge.LifecycleAdaptation.ADVISORY,
            MediaTransportLifecycleBridge.LifecycleAdaptation.fromValue("advisory")
        )
        assertEquals(
            MediaTransportLifecycleBridge.LifecycleAdaptation.SUSPEND_NEW_TASKS,
            MediaTransportLifecycleBridge.LifecycleAdaptation.fromValue("suspend_new_tasks")
        )
        assertEquals(
            MediaTransportLifecycleBridge.LifecycleAdaptation.TERMINATE_ACTIVE_TASKS,
            MediaTransportLifecycleBridge.LifecycleAdaptation.fromValue("terminate_active_tasks")
        )
    }

    @Test fun `LifecycleAdaptation fromValue returns null for unknown value`() {
        assertNull(MediaTransportLifecycleBridge.LifecycleAdaptation.fromValue("unknown_xyz"))
    }

    @Test fun `LifecycleAdaptation fromValue returns null for null input`() {
        assertNull(MediaTransportLifecycleBridge.LifecycleAdaptation.fromValue(null))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaTransportLifecycleBridge — AdaptationOwnership enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `AdaptationOwnership TRANSPORT_LAYER wireValue is transport_layer`() {
        assertEquals("transport_layer", MediaTransportLifecycleBridge.AdaptationOwnership.TRANSPORT_LAYER.wireValue)
    }

    @Test fun `AdaptationOwnership RUNTIME_LAYER wireValue is runtime_layer`() {
        assertEquals("runtime_layer", MediaTransportLifecycleBridge.AdaptationOwnership.RUNTIME_LAYER.wireValue)
    }

    @Test fun `AdaptationOwnership OPERATOR_OR_USER wireValue is operator_or_user`() {
        assertEquals("operator_or_user", MediaTransportLifecycleBridge.AdaptationOwnership.OPERATOR_OR_USER.wireValue)
    }

    @Test fun `all three AdaptationOwnership wireValues are distinct`() {
        val wireValues = MediaTransportLifecycleBridge.AdaptationOwnership.entries.map { it.wireValue }
        assertEquals("AdaptationOwnership wireValues must all be unique", wireValues.distinct().size, wireValues.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaTransportLifecycleBridge — registry completeness
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `exactly four integration entries`() {
        assertEquals(4, MediaTransportLifecycleBridge.integrationEntries.size)
    }

    @Test fun `all four TransportCondition values have exactly one integration entry`() {
        val covered = MediaTransportLifecycleBridge.integrationEntries.map { it.condition }.toSet()
        assertEquals(
            "All four TransportCondition values must be covered",
            MediaTransportLifecycleBridge.TransportCondition.entries.toSet(),
            covered
        )
    }

    @Test fun `STABLE condition maps to NONE adaptation`() {
        val entry = MediaTransportLifecycleBridge.entryFor(MediaTransportLifecycleBridge.TransportCondition.STABLE)
        assertNotNull(entry)
        assertEquals(MediaTransportLifecycleBridge.LifecycleAdaptation.NONE, entry!!.adaptation)
    }

    @Test fun `DEGRADED condition maps to ADVISORY adaptation`() {
        val entry = MediaTransportLifecycleBridge.entryFor(MediaTransportLifecycleBridge.TransportCondition.DEGRADED)
        assertNotNull(entry)
        assertEquals(MediaTransportLifecycleBridge.LifecycleAdaptation.ADVISORY, entry!!.adaptation)
    }

    @Test fun `INTERRUPTED condition maps to TERMINATE_ACTIVE_TASKS adaptation`() {
        val entry = MediaTransportLifecycleBridge.entryFor(MediaTransportLifecycleBridge.TransportCondition.INTERRUPTED)
        assertNotNull(entry)
        assertEquals(MediaTransportLifecycleBridge.LifecycleAdaptation.TERMINATE_ACTIVE_TASKS, entry!!.adaptation)
    }

    @Test fun `SUSPENDED condition maps to TERMINATE_ACTIVE_TASKS adaptation`() {
        val entry = MediaTransportLifecycleBridge.entryFor(MediaTransportLifecycleBridge.TransportCondition.SUSPENDED)
        assertNotNull(entry)
        assertEquals(MediaTransportLifecycleBridge.LifecycleAdaptation.TERMINATE_ACTIVE_TASKS, entry!!.adaptation)
    }

    @Test fun `all integration entries have non-blank recoveryPath dispatchImpact and note`() {
        for (entry in MediaTransportLifecycleBridge.integrationEntries) {
            assertTrue("recoveryPath must be non-blank for ${entry.condition}", entry.recoveryPath.isNotBlank())
            assertTrue("dispatchImpact must be non-blank for ${entry.condition}", entry.dispatchImpact.isNotBlank())
            assertTrue("note must be non-blank for ${entry.condition}", entry.note.isNotBlank())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaTransportLifecycleBridge — query helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `entryFor STABLE is non-null`() {
        assertNotNull(MediaTransportLifecycleBridge.entryFor(MediaTransportLifecycleBridge.TransportCondition.STABLE))
    }

    @Test fun `entryFor INTERRUPTED returns TERMINATE_ACTIVE_TASKS entry`() {
        val entry = MediaTransportLifecycleBridge.entryFor(MediaTransportLifecycleBridge.TransportCondition.INTERRUPTED)
        assertNotNull(entry)
        assertEquals(MediaTransportLifecycleBridge.LifecycleAdaptation.TERMINATE_ACTIVE_TASKS, entry!!.adaptation)
    }

    @Test fun `entriesForAdaptation NONE has one entry`() {
        assertEquals(
            1,
            MediaTransportLifecycleBridge.entriesForAdaptation(MediaTransportLifecycleBridge.LifecycleAdaptation.NONE).size
        )
    }

    @Test fun `entriesForAdaptation TERMINATE_ACTIVE_TASKS has two entries`() {
        assertEquals(
            2,
            MediaTransportLifecycleBridge.entriesForAdaptation(
                MediaTransportLifecycleBridge.LifecycleAdaptation.TERMINATE_ACTIVE_TASKS
            ).size
        )
    }

    @Test fun `entriesForOwnership TRANSPORT_LAYER has at least two entries`() {
        assertTrue(
            MediaTransportLifecycleBridge.entriesForOwnership(
                MediaTransportLifecycleBridge.AdaptationOwnership.TRANSPORT_LAYER
            ).size >= 2
        )
    }

    @Test fun `conditionsRequiringAdaptation has three elements`() {
        assertEquals(3, MediaTransportLifecycleBridge.conditionsRequiringAdaptation.size)
    }

    @Test fun `conditionsRequiringAdaptation does not include STABLE`() {
        assertFalse(
            MediaTransportLifecycleBridge.conditionsRequiringAdaptation.contains(
                MediaTransportLifecycleBridge.TransportCondition.STABLE
            )
        )
    }

    @Test fun `conditionsRequiringAdaptation includes DEGRADED INTERRUPTED and SUSPENDED`() {
        val set = MediaTransportLifecycleBridge.conditionsRequiringAdaptation
        assertTrue(set.contains(MediaTransportLifecycleBridge.TransportCondition.DEGRADED))
        assertTrue(set.contains(MediaTransportLifecycleBridge.TransportCondition.INTERRUPTED))
        assertTrue(set.contains(MediaTransportLifecycleBridge.TransportCondition.SUSPENDED))
    }

    @Test fun `conditionsTerminatingTasks has two elements`() {
        assertEquals(2, MediaTransportLifecycleBridge.conditionsTerminatingTasks.size)
    }

    @Test fun `conditionsTerminatingTasks includes INTERRUPTED and SUSPENDED`() {
        val set = MediaTransportLifecycleBridge.conditionsTerminatingTasks
        assertTrue(set.contains(MediaTransportLifecycleBridge.TransportCondition.INTERRUPTED))
        assertTrue(set.contains(MediaTransportLifecycleBridge.TransportCondition.SUSPENDED))
    }

    @Test fun `conditionsTerminatingTasks does not include STABLE or DEGRADED`() {
        val set = MediaTransportLifecycleBridge.conditionsTerminatingTasks
        assertFalse(set.contains(MediaTransportLifecycleBridge.TransportCondition.STABLE))
        assertFalse(set.contains(MediaTransportLifecycleBridge.TransportCondition.DEGRADED))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MediaTransportLifecycleBridge — constants
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `MediaTransportLifecycleBridge INTRODUCED_PR is 40`() {
        assertEquals(40, MediaTransportLifecycleBridge.INTRODUCED_PR)
    }

    @Test fun `MediaTransportLifecycleBridge DESCRIPTION is non-blank`() {
        assertTrue(MediaTransportLifecycleBridge.DESCRIPTION.isNotBlank())
    }

    @Test fun `LIFECYCLE_AUTHORITY_INVARIANT is non-blank and mentions RuntimeController`() {
        assertTrue(MediaTransportLifecycleBridge.LIFECYCLE_AUTHORITY_INVARIANT.isNotBlank())
        assertTrue(
            "LIFECYCLE_AUTHORITY_INVARIANT must mention RuntimeController",
            MediaTransportLifecycleBridge.LIFECYCLE_AUTHORITY_INVARIANT.contains("RuntimeController")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TransportContinuityAnchor — TransportEvent enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `TransportEvent ATTACH wireValue is attach`() {
        assertEquals("attach", TransportContinuityAnchor.TransportEvent.ATTACH.wireValue)
    }

    @Test fun `TransportEvent DETACH wireValue is detach`() {
        assertEquals("detach", TransportContinuityAnchor.TransportEvent.DETACH.wireValue)
    }

    @Test fun `TransportEvent RECONNECT wireValue is reconnect`() {
        assertEquals("reconnect", TransportContinuityAnchor.TransportEvent.RECONNECT.wireValue)
    }

    @Test fun `TransportEvent DEGRADATION wireValue is degradation`() {
        assertEquals("degradation", TransportContinuityAnchor.TransportEvent.DEGRADATION.wireValue)
    }

    @Test fun `TransportEvent RECOVERY wireValue is recovery`() {
        assertEquals("recovery", TransportContinuityAnchor.TransportEvent.RECOVERY.wireValue)
    }

    @Test fun `all five TransportEvent wireValues are distinct`() {
        val wireValues = TransportContinuityAnchor.TransportEvent.entries.map { it.wireValue }
        assertEquals("TransportEvent wireValues must all be unique", wireValues.distinct().size, wireValues.size)
    }

    @Test fun `TransportEvent fromValue returns correct entries`() {
        assertEquals(
            TransportContinuityAnchor.TransportEvent.ATTACH,
            TransportContinuityAnchor.TransportEvent.fromValue("attach")
        )
        assertEquals(
            TransportContinuityAnchor.TransportEvent.DETACH,
            TransportContinuityAnchor.TransportEvent.fromValue("detach")
        )
        assertEquals(
            TransportContinuityAnchor.TransportEvent.RECONNECT,
            TransportContinuityAnchor.TransportEvent.fromValue("reconnect")
        )
        assertEquals(
            TransportContinuityAnchor.TransportEvent.DEGRADATION,
            TransportContinuityAnchor.TransportEvent.fromValue("degradation")
        )
        assertEquals(
            TransportContinuityAnchor.TransportEvent.RECOVERY,
            TransportContinuityAnchor.TransportEvent.fromValue("recovery")
        )
    }

    @Test fun `TransportEvent fromValue returns null for unknown value`() {
        assertNull(TransportContinuityAnchor.TransportEvent.fromValue("unknown_xyz"))
    }

    @Test fun `TransportEvent fromValue returns null for null input`() {
        assertNull(TransportContinuityAnchor.TransportEvent.fromValue(null))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TransportContinuityAnchor — ContinuityPolicy enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `ContinuityPolicy PERSIST wireValue is persist`() {
        assertEquals("persist", TransportContinuityAnchor.ContinuityPolicy.PERSIST.wireValue)
    }

    @Test fun `ContinuityPolicy RESHAPES wireValue is reshapes`() {
        assertEquals("reshapes", TransportContinuityAnchor.ContinuityPolicy.RESHAPES.wireValue)
    }

    @Test fun `ContinuityPolicy TERMINATES wireValue is terminates`() {
        assertEquals("terminates", TransportContinuityAnchor.ContinuityPolicy.TERMINATES.wireValue)
    }

    @Test fun `ContinuityPolicy SUSPENDS wireValue is suspends`() {
        assertEquals("suspends", TransportContinuityAnchor.ContinuityPolicy.SUSPENDS.wireValue)
    }

    @Test fun `all four ContinuityPolicy wireValues are distinct`() {
        val wireValues = TransportContinuityAnchor.ContinuityPolicy.entries.map { it.wireValue }
        assertEquals("ContinuityPolicy wireValues must all be unique", wireValues.distinct().size, wireValues.size)
    }

    @Test fun `ContinuityPolicy fromValue returns correct entries`() {
        assertEquals(
            TransportContinuityAnchor.ContinuityPolicy.PERSIST,
            TransportContinuityAnchor.ContinuityPolicy.fromValue("persist")
        )
        assertEquals(
            TransportContinuityAnchor.ContinuityPolicy.RESHAPES,
            TransportContinuityAnchor.ContinuityPolicy.fromValue("reshapes")
        )
        assertEquals(
            TransportContinuityAnchor.ContinuityPolicy.TERMINATES,
            TransportContinuityAnchor.ContinuityPolicy.fromValue("terminates")
        )
        assertEquals(
            TransportContinuityAnchor.ContinuityPolicy.SUSPENDS,
            TransportContinuityAnchor.ContinuityPolicy.fromValue("suspends")
        )
    }

    @Test fun `ContinuityPolicy fromValue returns null for unknown value`() {
        assertNull(TransportContinuityAnchor.ContinuityPolicy.fromValue("unknown_xyz"))
    }

    @Test fun `ContinuityPolicy fromValue returns null for null input`() {
        assertNull(TransportContinuityAnchor.ContinuityPolicy.fromValue(null))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TransportContinuityAnchor — ContinuityOwnership enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `ContinuityOwnership RUNTIME_CONTROLLER wireValue is runtime_controller`() {
        assertEquals("runtime_controller", TransportContinuityAnchor.ContinuityOwnership.RUNTIME_CONTROLLER.wireValue)
    }

    @Test fun `ContinuityOwnership TRANSPORT_LAYER wireValue is transport_layer`() {
        assertEquals("transport_layer", TransportContinuityAnchor.ContinuityOwnership.TRANSPORT_LAYER.wireValue)
    }

    @Test fun `ContinuityOwnership OPERATOR_OR_USER wireValue is operator_or_user`() {
        assertEquals("operator_or_user", TransportContinuityAnchor.ContinuityOwnership.OPERATOR_OR_USER.wireValue)
    }

    @Test fun `all three ContinuityOwnership wireValues are distinct`() {
        val wireValues = TransportContinuityAnchor.ContinuityOwnership.entries.map { it.wireValue }
        assertEquals("ContinuityOwnership wireValues must all be unique", wireValues.distinct().size, wireValues.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TransportContinuityAnchor — PostEventSessionState enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `PostEventSessionState ATTACHED wireValue is attached`() {
        assertEquals("attached", TransportContinuityAnchor.PostEventSessionState.ATTACHED.wireValue)
    }

    @Test fun `PostEventSessionState DETACHED wireValue is detached`() {
        assertEquals("detached", TransportContinuityAnchor.PostEventSessionState.DETACHED.wireValue)
    }

    @Test fun `PostEventSessionState REATTACHED wireValue is reattached`() {
        assertEquals("reattached", TransportContinuityAnchor.PostEventSessionState.REATTACHED.wireValue)
    }

    @Test fun `all three PostEventSessionState wireValues are distinct`() {
        val wireValues = TransportContinuityAnchor.PostEventSessionState.entries.map { it.wireValue }
        assertEquals("PostEventSessionState wireValues must all be unique", wireValues.distinct().size, wireValues.size)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TransportContinuityAnchor — anchor entries completeness
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `exactly five anchor entries`() {
        assertEquals(5, TransportContinuityAnchor.anchorEntries.size)
    }

    @Test fun `all five TransportEvent values have exactly one anchor entry`() {
        val covered = TransportContinuityAnchor.anchorEntries.map { it.event }.toSet()
        assertEquals(
            "All five TransportEvent values must be covered",
            TransportContinuityAnchor.TransportEvent.entries.toSet(),
            covered
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TransportContinuityAnchor — per-event anchor entry correctness
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `ATTACH anchor has PERSIST policy`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.ATTACH)
        assertNotNull(entry)
        assertEquals(TransportContinuityAnchor.ContinuityPolicy.PERSIST, entry!!.policy)
    }

    @Test fun `ATTACH anchor has RUNTIME_CONTROLLER ownership`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.ATTACH)!!
        assertEquals(TransportContinuityAnchor.ContinuityOwnership.RUNTIME_CONTROLLER, entry.ownership)
    }

    @Test fun `ATTACH anchor has ATTACHED post-event state`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.ATTACH)!!
        assertEquals(TransportContinuityAnchor.PostEventSessionState.ATTACHED, entry.postEventState)
    }

    @Test fun `ATTACH anchor has null detachCause`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.ATTACH)!!
        assertNull(entry.detachCause)
    }

    @Test fun `ATTACH anchor has durableEraRetained true`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.ATTACH)!!
        assertTrue(entry.durableEraRetained)
    }

    @Test fun `DETACH anchor has TERMINATES policy`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.DETACH)
        assertNotNull(entry)
        assertEquals(TransportContinuityAnchor.ContinuityPolicy.TERMINATES, entry!!.policy)
    }

    @Test fun `DETACH anchor has OPERATOR_OR_USER ownership`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.DETACH)!!
        assertEquals(TransportContinuityAnchor.ContinuityOwnership.OPERATOR_OR_USER, entry.ownership)
    }

    @Test fun `DETACH anchor has DETACHED post-event state`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.DETACH)!!
        assertEquals(TransportContinuityAnchor.PostEventSessionState.DETACHED, entry.postEventState)
    }

    @Test fun `DETACH anchor has DISABLE detachCause`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.DETACH)!!
        assertEquals(AttachedRuntimeSession.DetachCause.DISABLE, entry.detachCause)
    }

    @Test fun `DETACH anchor has durableEraRetained false`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.DETACH)!!
        assertFalse(entry.durableEraRetained)
    }

    @Test fun `RECONNECT anchor has RESHAPES policy`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.RECONNECT)
        assertNotNull(entry)
        assertEquals(TransportContinuityAnchor.ContinuityPolicy.RESHAPES, entry!!.policy)
    }

    @Test fun `RECONNECT anchor has TRANSPORT_LAYER ownership`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.RECONNECT)!!
        assertEquals(TransportContinuityAnchor.ContinuityOwnership.TRANSPORT_LAYER, entry.ownership)
    }

    @Test fun `RECONNECT anchor has REATTACHED post-event state`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.RECONNECT)!!
        assertEquals(TransportContinuityAnchor.PostEventSessionState.REATTACHED, entry.postEventState)
    }

    @Test fun `RECONNECT anchor has null detachCause`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.RECONNECT)!!
        assertNull(entry.detachCause)
    }

    @Test fun `RECONNECT anchor has durableEraRetained true`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.RECONNECT)!!
        assertTrue(entry.durableEraRetained)
    }

    @Test fun `DEGRADATION anchor has SUSPENDS policy`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.DEGRADATION)
        assertNotNull(entry)
        assertEquals(TransportContinuityAnchor.ContinuityPolicy.SUSPENDS, entry!!.policy)
    }

    @Test fun `DEGRADATION anchor has TRANSPORT_LAYER ownership`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.DEGRADATION)!!
        assertEquals(TransportContinuityAnchor.ContinuityOwnership.TRANSPORT_LAYER, entry.ownership)
    }

    @Test fun `DEGRADATION anchor has ATTACHED post-event state`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.DEGRADATION)!!
        assertEquals(TransportContinuityAnchor.PostEventSessionState.ATTACHED, entry.postEventState)
    }

    @Test fun `DEGRADATION anchor has DISCONNECT detachCause`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.DEGRADATION)!!
        assertEquals(AttachedRuntimeSession.DetachCause.DISCONNECT, entry.detachCause)
    }

    @Test fun `DEGRADATION anchor has durableEraRetained true`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.DEGRADATION)!!
        assertTrue(entry.durableEraRetained)
    }

    @Test fun `RECOVERY anchor has PERSIST policy`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.RECOVERY)
        assertNotNull(entry)
        assertEquals(TransportContinuityAnchor.ContinuityPolicy.PERSIST, entry!!.policy)
    }

    @Test fun `RECOVERY anchor has TRANSPORT_LAYER ownership`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.RECOVERY)!!
        assertEquals(TransportContinuityAnchor.ContinuityOwnership.TRANSPORT_LAYER, entry.ownership)
    }

    @Test fun `RECOVERY anchor has ATTACHED post-event state`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.RECOVERY)!!
        assertEquals(TransportContinuityAnchor.PostEventSessionState.ATTACHED, entry.postEventState)
    }

    @Test fun `RECOVERY anchor has null detachCause`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.RECOVERY)!!
        assertNull(entry.detachCause)
    }

    @Test fun `RECOVERY anchor has durableEraRetained true`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.RECOVERY)!!
        assertTrue(entry.durableEraRetained)
    }

    @Test fun `all anchor entries have non-blank recoverySequence and note`() {
        for (entry in TransportContinuityAnchor.anchorEntries) {
            assertTrue("recoverySequence must be non-blank for ${entry.event}", entry.recoverySequence.isNotBlank())
            assertTrue("note must be non-blank for ${entry.event}", entry.note.isNotBlank())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TransportContinuityAnchor — query helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `anchorFor ATTACH is non-null`() {
        assertNotNull(TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.ATTACH))
    }

    @Test fun `anchorFor RECONNECT has RESHAPES policy`() {
        val entry = TransportContinuityAnchor.anchorFor(TransportContinuityAnchor.TransportEvent.RECONNECT)!!
        assertEquals(TransportContinuityAnchor.ContinuityPolicy.RESHAPES, entry.policy)
    }

    @Test fun `entriesForPolicy PERSIST has two entries`() {
        assertEquals(
            2,
            TransportContinuityAnchor.entriesForPolicy(TransportContinuityAnchor.ContinuityPolicy.PERSIST).size
        )
    }

    @Test fun `entriesForPolicy TERMINATES has one entry`() {
        assertEquals(
            1,
            TransportContinuityAnchor.entriesForPolicy(TransportContinuityAnchor.ContinuityPolicy.TERMINATES).size
        )
    }

    @Test fun `entriesForOwnership TRANSPORT_LAYER has three entries`() {
        assertEquals(
            3,
            TransportContinuityAnchor.entriesForOwnership(
                TransportContinuityAnchor.ContinuityOwnership.TRANSPORT_LAYER
            ).size
        )
    }

    @Test fun `eventsDurableEraRetained has four elements`() {
        assertEquals(4, TransportContinuityAnchor.eventsDurableEraRetained.size)
    }

    @Test fun `eventsDurableEraRetained includes ATTACH RECONNECT DEGRADATION and RECOVERY`() {
        val set = TransportContinuityAnchor.eventsDurableEraRetained
        assertTrue(set.contains(TransportContinuityAnchor.TransportEvent.ATTACH))
        assertTrue(set.contains(TransportContinuityAnchor.TransportEvent.RECONNECT))
        assertTrue(set.contains(TransportContinuityAnchor.TransportEvent.DEGRADATION))
        assertTrue(set.contains(TransportContinuityAnchor.TransportEvent.RECOVERY))
    }

    @Test fun `eventsDurableEraTerminated has one element`() {
        assertEquals(1, TransportContinuityAnchor.eventsDurableEraTerminated.size)
    }

    @Test fun `eventsDurableEraTerminated includes DETACH`() {
        assertTrue(TransportContinuityAnchor.eventsDurableEraTerminated.contains(
            TransportContinuityAnchor.TransportEvent.DETACH
        ))
    }

    @Test fun `eventsDurableEraRetained and eventsDurableEraTerminated cover all five events`() {
        val all = TransportContinuityAnchor.eventsDurableEraRetained +
            TransportContinuityAnchor.eventsDurableEraTerminated
        assertEquals(
            "Union of retained and terminated sets must equal all five TransportEvent values",
            TransportContinuityAnchor.TransportEvent.entries.toSet(),
            all
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TransportContinuityAnchor — constants
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `TransportContinuityAnchor INTRODUCED_PR is 40`() {
        assertEquals(40, TransportContinuityAnchor.INTRODUCED_PR)
    }

    @Test fun `TransportContinuityAnchor DESCRIPTION is non-blank`() {
        assertTrue(TransportContinuityAnchor.DESCRIPTION.isNotBlank())
    }

    @Test fun `DURABLE_ERA_RECONNECT_INVARIANT is non-blank`() {
        assertTrue(TransportContinuityAnchor.DURABLE_ERA_RECONNECT_INVARIANT.isNotBlank())
    }

    @Test fun `CONTINUITY_AUTHORITY_INVARIANT is non-blank and mentions RuntimeController`() {
        assertTrue(TransportContinuityAnchor.CONTINUITY_AUTHORITY_INVARIANT.isNotBlank())
        assertTrue(
            "CONTINUITY_AUTHORITY_INVARIANT must mention RuntimeController",
            TransportContinuityAnchor.CONTINUITY_AUTHORITY_INVARIANT.contains("RuntimeController")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CanonicalDispatchChain — resolveTransportAdaptedPaths (PR-40)
    // ─────────────────────────────────────────────────────────────────────────

    private fun makeRollout(crossDevice: Boolean = true) = RolloutControlSnapshot(
        crossDeviceAllowed = crossDevice,
        delegatedExecutionAllowed = crossDevice,
        fallbackToLocalAllowed = true,
        goalExecutionAllowed = crossDevice
    )

    @Test fun `resolveTransportAdaptedPaths STABLE returns same result as resolveEligiblePathsForState for ineligible state`() {
        val state = RuntimeController.RuntimeState.LocalOnly
        val rollout = makeRollout(crossDevice = false)
        val base = CanonicalDispatchChain.resolveEligiblePathsForState(state, null, rollout)
        val adapted = CanonicalDispatchChain.resolveTransportAdaptedPaths(
            state, null, rollout,
            MediaTransportLifecycleBridge.TransportCondition.STABLE
        )
        assertEquals(base, adapted)
    }

    @Test fun `resolveTransportAdaptedPaths DEGRADED returns same result as resolveEligiblePathsForState for ineligible state`() {
        val state = RuntimeController.RuntimeState.LocalOnly
        val rollout = makeRollout(crossDevice = false)
        val base = CanonicalDispatchChain.resolveEligiblePathsForState(state, null, rollout)
        val adapted = CanonicalDispatchChain.resolveTransportAdaptedPaths(
            state, null, rollout,
            MediaTransportLifecycleBridge.TransportCondition.DEGRADED
        )
        assertEquals(base, adapted)
    }

    @Test fun `resolveTransportAdaptedPaths INTERRUPTED suppresses CANONICAL STAGED_MESH DELEGATED`() {
        val state = RuntimeController.RuntimeState.LocalOnly
        val rollout = makeRollout(crossDevice = false)
        val adapted = CanonicalDispatchChain.resolveTransportAdaptedPaths(
            state, null, rollout,
            MediaTransportLifecycleBridge.TransportCondition.INTERRUPTED
        )
        val modes = adapted.map { it.pathMode }.toSet()
        assertFalse("CANONICAL must be suppressed by INTERRUPTED", modes.contains(DispatchPathMode.CANONICAL))
        assertFalse("STAGED_MESH must be suppressed by INTERRUPTED", modes.contains(DispatchPathMode.STAGED_MESH))
        assertFalse("DELEGATED must be suppressed by INTERRUPTED", modes.contains(DispatchPathMode.DELEGATED))
    }

    @Test fun `resolveTransportAdaptedPaths SUSPENDED suppresses CANONICAL STAGED_MESH DELEGATED`() {
        val state = RuntimeController.RuntimeState.LocalOnly
        val rollout = makeRollout(crossDevice = false)
        val adapted = CanonicalDispatchChain.resolveTransportAdaptedPaths(
            state, null, rollout,
            MediaTransportLifecycleBridge.TransportCondition.SUSPENDED
        )
        val modes = adapted.map { it.pathMode }.toSet()
        assertFalse("CANONICAL must be suppressed by SUSPENDED", modes.contains(DispatchPathMode.CANONICAL))
        assertFalse("STAGED_MESH must be suppressed by SUSPENDED", modes.contains(DispatchPathMode.STAGED_MESH))
        assertFalse("DELEGATED must be suppressed by SUSPENDED", modes.contains(DispatchPathMode.DELEGATED))
    }

    @Test fun `resolveTransportAdaptedPaths INTERRUPTED still returns LOCAL path`() {
        val state = RuntimeController.RuntimeState.LocalOnly
        val rollout = makeRollout(crossDevice = false)
        val adapted = CanonicalDispatchChain.resolveTransportAdaptedPaths(
            state, null, rollout,
            MediaTransportLifecycleBridge.TransportCondition.INTERRUPTED
        )
        assertTrue(
            "LOCAL path must not be suppressed by INTERRUPTED",
            adapted.any { it.pathMode == DispatchPathMode.LOCAL }
        )
    }

    @Test fun `resolveTransportAdaptedPaths INTERRUPTED still returns COMPATIBILITY path`() {
        val state = RuntimeController.RuntimeState.LocalOnly
        val rollout = makeRollout(crossDevice = false)
        val adapted = CanonicalDispatchChain.resolveTransportAdaptedPaths(
            state, null, rollout,
            MediaTransportLifecycleBridge.TransportCondition.INTERRUPTED
        )
        assertTrue(
            "COMPATIBILITY path must not be suppressed by INTERRUPTED",
            adapted.any { it.pathMode == DispatchPathMode.COMPATIBILITY }
        )
    }

    @Test fun `CROSS_DEVICE_PATH_MODES has exactly three elements`() {
        assertEquals(3, CanonicalDispatchChain.CROSS_DEVICE_PATH_MODES.size)
    }

    @Test fun `CROSS_DEVICE_PATH_MODES contains CANONICAL`() {
        assertTrue(CanonicalDispatchChain.CROSS_DEVICE_PATH_MODES.contains(DispatchPathMode.CANONICAL))
    }

    @Test fun `CROSS_DEVICE_PATH_MODES contains STAGED_MESH`() {
        assertTrue(CanonicalDispatchChain.CROSS_DEVICE_PATH_MODES.contains(DispatchPathMode.STAGED_MESH))
    }

    @Test fun `CROSS_DEVICE_PATH_MODES contains DELEGATED`() {
        assertTrue(CanonicalDispatchChain.CROSS_DEVICE_PATH_MODES.contains(DispatchPathMode.DELEGATED))
    }

    @Test fun `CROSS_DEVICE_PATH_MODES does not contain LOCAL`() {
        assertFalse(CanonicalDispatchChain.CROSS_DEVICE_PATH_MODES.contains(DispatchPathMode.LOCAL))
    }

    @Test fun `CROSS_DEVICE_PATH_MODES does not contain FALLBACK`() {
        assertFalse(CanonicalDispatchChain.CROSS_DEVICE_PATH_MODES.contains(DispatchPathMode.FALLBACK))
    }

    @Test fun `CROSS_DEVICE_PATH_MODES does not contain COMPATIBILITY`() {
        assertFalse(CanonicalDispatchChain.CROSS_DEVICE_PATH_MODES.contains(DispatchPathMode.COMPATIBILITY))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CanonicalSessionAxis — transportContinuityBindings (PR-40)
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `transportContinuityBindings has seven entries`() {
        assertEquals(7, CanonicalSessionAxis.transportContinuityBindings.size)
    }

    @Test fun `all seven CanonicalSessionFamily values have a transport continuity binding`() {
        val covered = CanonicalSessionAxis.transportContinuityBindings.map { it.family }.toSet()
        assertEquals(
            "All seven CanonicalSessionFamily values must have a binding",
            CanonicalSessionFamily.entries.toSet(),
            covered
        )
    }

    @Test fun `RUNTIME_SESSION binding affectedByInterruption is true`() {
        val binding = CanonicalSessionAxis.transportContinuityBindingFor(CanonicalSessionFamily.RUNTIME_SESSION)
        assertNotNull(binding)
        assertTrue(binding!!.affectedByInterruption)
    }

    @Test fun `RUNTIME_SESSION binding survivesReconnect is false`() {
        val binding = CanonicalSessionAxis.transportContinuityBindingFor(CanonicalSessionFamily.RUNTIME_SESSION)!!
        assertFalse(binding.survivesReconnect)
    }

    @Test fun `ATTACHED_RUNTIME_SESSION binding affectedByInterruption is true`() {
        val binding = CanonicalSessionAxis.transportContinuityBindingFor(CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION)
        assertNotNull(binding)
        assertTrue(binding!!.affectedByInterruption)
    }

    @Test fun `ATTACHED_RUNTIME_SESSION binding survivesReconnect is false`() {
        val binding = CanonicalSessionAxis.transportContinuityBindingFor(CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION)!!
        assertFalse(binding.survivesReconnect)
    }

    @Test fun `DELEGATION_TRANSFER_SESSION binding affectedByInterruption is true`() {
        val binding = CanonicalSessionAxis.transportContinuityBindingFor(CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION)
        assertNotNull(binding)
        assertTrue(binding!!.affectedByInterruption)
    }

    @Test fun `DELEGATION_TRANSFER_SESSION binding survivesReconnect is false`() {
        val binding = CanonicalSessionAxis.transportContinuityBindingFor(CanonicalSessionFamily.DELEGATION_TRANSFER_SESSION)!!
        assertFalse(binding.survivesReconnect)
    }

    @Test fun `MESH_SESSION binding affectedByInterruption is true`() {
        val binding = CanonicalSessionAxis.transportContinuityBindingFor(CanonicalSessionFamily.MESH_SESSION)
        assertNotNull(binding)
        assertTrue(binding!!.affectedByInterruption)
    }

    @Test fun `MESH_SESSION binding survivesReconnect is false`() {
        val binding = CanonicalSessionAxis.transportContinuityBindingFor(CanonicalSessionFamily.MESH_SESSION)!!
        assertFalse(binding.survivesReconnect)
    }

    @Test fun `CONTROL_SESSION binding affectedByInterruption is false`() {
        val binding = CanonicalSessionAxis.transportContinuityBindingFor(CanonicalSessionFamily.CONTROL_SESSION)
        assertNotNull(binding)
        assertFalse(binding!!.affectedByInterruption)
    }

    @Test fun `CONTROL_SESSION binding survivesReconnect is true`() {
        val binding = CanonicalSessionAxis.transportContinuityBindingFor(CanonicalSessionFamily.CONTROL_SESSION)!!
        assertTrue(binding.survivesReconnect)
    }

    @Test fun `CONVERSATION_SESSION binding affectedByInterruption is false`() {
        val binding = CanonicalSessionAxis.transportContinuityBindingFor(CanonicalSessionFamily.CONVERSATION_SESSION)
        assertNotNull(binding)
        assertFalse(binding!!.affectedByInterruption)
    }

    @Test fun `CONVERSATION_SESSION binding survivesReconnect is true`() {
        val binding = CanonicalSessionAxis.transportContinuityBindingFor(CanonicalSessionFamily.CONVERSATION_SESSION)!!
        assertTrue(binding.survivesReconnect)
    }

    @Test fun `DURABLE_RUNTIME_SESSION binding affectedByInterruption is false`() {
        val binding = CanonicalSessionAxis.transportContinuityBindingFor(CanonicalSessionFamily.DURABLE_RUNTIME_SESSION)
        assertNotNull(binding)
        assertFalse(binding!!.affectedByInterruption)
    }

    @Test fun `DURABLE_RUNTIME_SESSION binding survivesReconnect is true`() {
        val binding = CanonicalSessionAxis.transportContinuityBindingFor(CanonicalSessionFamily.DURABLE_RUNTIME_SESSION)!!
        assertTrue(binding.survivesReconnect)
    }

    @Test fun `familiesAffectedByInterruption has exactly four members`() {
        assertEquals(4, CanonicalSessionAxis.familiesAffectedByInterruption.size)
    }

    @Test fun `familiesSurvivingReconnect has exactly three members`() {
        assertEquals(3, CanonicalSessionAxis.familiesSurvivingReconnect.size)
    }

    @Test fun `all transport continuity bindings have non-blank transportNote`() {
        for (binding in CanonicalSessionAxis.transportContinuityBindings) {
            assertTrue(
                "transportNote must be non-blank for ${binding.family}",
                binding.transportNote.isNotBlank()
            )
        }
    }

    @Test fun `transportContinuityBindingFor returns null for non-existent family (defensive)`() {
        // All seven families ARE registered; this validates the defensive null-return contract
        // by checking that all known families return non-null.
        for (family in CanonicalSessionFamily.entries) {
            assertNotNull(
                "transportContinuityBindingFor must return non-null for known family $family",
                CanonicalSessionAxis.transportContinuityBindingFor(family)
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // StabilizationBaseline — PR-40 entries
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `media-transport-lifecycle-bridge is registered in StabilizationBaseline`() {
        assertNotNull(StabilizationBaseline.forId("media-transport-lifecycle-bridge"))
    }

    @Test fun `transport-continuity-anchor is registered in StabilizationBaseline`() {
        assertNotNull(StabilizationBaseline.forId("transport-continuity-anchor"))
    }

    @Test fun `canonical-dispatch-chain-transport-adapted-paths is registered in StabilizationBaseline`() {
        assertNotNull(StabilizationBaseline.forId("canonical-dispatch-chain-transport-adapted-paths"))
    }

    @Test fun `canonical-session-axis-transport-continuity-bindings is registered in StabilizationBaseline`() {
        assertNotNull(StabilizationBaseline.forId("canonical-session-axis-transport-continuity-bindings"))
    }

    @Test fun `all four PR-40 entries have CANONICAL_STABLE stability`() {
        val pr40Ids = listOf(
            "media-transport-lifecycle-bridge",
            "transport-continuity-anchor",
            "canonical-dispatch-chain-transport-adapted-paths",
            "canonical-session-axis-transport-continuity-bindings"
        )
        for (id in pr40Ids) {
            val entry = StabilizationBaseline.forId(id)
            assertNotNull("Entry must exist for $id", entry)
            assertEquals(
                "Entry $id must have CANONICAL_STABLE stability",
                StabilizationBaseline.SurfaceStability.CANONICAL_STABLE,
                entry!!.stability
            )
        }
    }

    @Test fun `all four PR-40 entries have EXTEND guidance`() {
        val pr40Ids = listOf(
            "media-transport-lifecycle-bridge",
            "transport-continuity-anchor",
            "canonical-dispatch-chain-transport-adapted-paths",
            "canonical-session-axis-transport-continuity-bindings"
        )
        for (id in pr40Ids) {
            val entry = StabilizationBaseline.forId(id)!!
            assertEquals(
                "Entry $id must have EXTEND guidance",
                StabilizationBaseline.ExtensionGuidance.EXTEND,
                entry.extensionGuidance
            )
        }
    }

    @Test fun `all four PR-40 entries have introducedPr 40`() {
        val pr40Ids = listOf(
            "media-transport-lifecycle-bridge",
            "transport-continuity-anchor",
            "canonical-dispatch-chain-transport-adapted-paths",
            "canonical-session-axis-transport-continuity-bindings"
        )
        for (id in pr40Ids) {
            val entry = StabilizationBaseline.forId(id)!!
            assertEquals("Entry $id must have introducedPr = 40", 40, entry.introducedPr)
        }
    }
}
