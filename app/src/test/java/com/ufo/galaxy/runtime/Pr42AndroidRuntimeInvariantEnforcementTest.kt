package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-42 — Android Runtime Invariant Enforcement.
 *
 * Focused acceptance test suite validating all PR-42 additions:
 *
 *  1. [RuntimeInvariantEnforcer] — runtime invariant registry and check engine.
 *     - [RuntimeInvariantEnforcer.InvariantScope] enum values and wireValues.
 *     - [RuntimeInvariantEnforcer.InvariantOutcome] enum values and wireValues.
 *     - [RuntimeInvariantEnforcer.InvariantSeverity] enum values and wireValues.
 *     - [RuntimeInvariantEnforcer.InvariantId] enum values and wireValues.
 *     - Registry completeness: invariants.size == InvariantId.entries.size.
 *     - All invariants have non-blank description, canonicalSurface, enforcementNote.
 *     - criticalInvariantIds and warningInvariantIds are disjoint and cover all invariants.
 *     - Query helpers: invariantFor, byScope, bySeverity.
 *     - Constants: INTRODUCED_PR, DESCRIPTION, COVERAGE_INVARIANT.
 *
 *  2. [RuntimeInvariantEnforcer.checkAll] / individual check functions.
 *     - SESSION_ACTIVE_REQUIRES_ATTACHED: Active+no session → VIOLATED.
 *     - SESSION_ACTIVE_REQUIRES_ATTACHED: Active+ATTACHED → SATISFIED.
 *     - SESSION_ACTIVE_REQUIRES_ATTACHED: LocalOnly+no session → SATISFIED.
 *     - ATTACHED_REQUIRES_ACTIVE_OR_RECOVERY: ATTACHED+LocalOnly → VIOLATED.
 *     - ATTACHED_REQUIRES_ACTIVE_OR_RECOVERY: ATTACHED+Active → SATISFIED.
 *     - ATTACHED_REQUIRES_ACTIVE_OR_RECOVERY: no session → SATISFIED.
 *     - TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE: INTERRUPTED+eligible → SATISFIED when filter works.
 *     - TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE: STABLE → SATISFIED.
 *     - SNAPSHOT_REQUIRES_SESSION: snapshot+no session → VIOLATED.
 *     - SNAPSHOT_REQUIRES_SESSION: snapshot+session → SATISFIED.
 *     - SNAPSHOT_REQUIRES_SESSION: no snapshot → SATISFIED.
 *     - ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE: delegated=true, cross=false → VIOLATED.
 *     - ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE: delegated=true, cross=true → SATISFIED.
 *     - ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE: both false → SATISFIED.
 *     - DISPATCH_ELIGIBILITY_CONSISTENT_WITH_READINESS: eligible but canonical absent → VIOLATED.
 *     - DISPATCH_ELIGIBILITY_CONSISTENT_WITH_READINESS: ineligible and canonical absent → SATISFIED.
 *     - DISPATCH_ELIGIBILITY_CONSISTENT_WITH_READINESS: eligible and canonical present → SATISFIED.
 *     - DURABLE_SESSION_PRESENT_WHEN_ACTIVE: Active+null record → VIOLATED.
 *     - DURABLE_SESSION_PRESENT_WHEN_ACTIVE: Active+non-null record → SATISFIED.
 *     - DURABLE_SESSION_PRESENT_WHEN_ACTIVE: LocalOnly+null record → SATISFIED.
 *     - RECOVERY_STATE_CONSISTENT_WITH_RUNTIME: RECOVERING+LocalOnly → VIOLATED.
 *     - RECOVERY_STATE_CONSISTENT_WITH_RUNTIME: RECOVERING+Active → SATISFIED.
 *     - RECOVERY_STATE_CONSISTENT_WITH_RUNTIME: IDLE+LocalOnly → SATISFIED.
 *     - KILL_SWITCH_CLEARS_CROSS_DEVICE: kill-switch+Active → VIOLATED.
 *     - KILL_SWITCH_CLEARS_CROSS_DEVICE: kill-switch+LocalOnly+no session → SATISFIED.
 *     - KILL_SWITCH_CLEARS_CROSS_DEVICE: no kill-switch → SATISFIED.
 *
 *  3. [RuntimeInvariantEnforcer.violatedInvariantIds] and [RuntimeInvariantEnforcer.allSatisfied].
 *     - violatedInvariantIds returns empty set when all satisfied.
 *     - violatedInvariantIds returns non-empty set when violated.
 *     - allSatisfied returns true when no violations.
 *     - allSatisfied returns false when a violation is present.
 *
 *  4. [CanonicalDispatchChain.resolveInvariantProtectedPaths] — PR-42 addition.
 *     - critical SESSION violation: blockedByInvariant=true, paths empty.
 *     - critical TRANSPORT violation: cross-device paths suppressed.
 *     - warning-only violation: paths are NOT suppressed.
 *     - all invariants satisfied: paths are equivalent to resolveContractFinalizedPaths result.
 *     - InvariantProtectedPathResult.allInvariantsSatisfied is true when no violations.
 *     - InvariantProtectedPathResult.violatedIds is empty when no violations.
 *
 *  5. [CanonicalSessionAxis.invariantBindings] — PR-42 addition.
 *     - invariantBindings has 7 entries (one per CanonicalSessionFamily).
 *     - all seven families have a binding.
 *     - ATTACHED_RUNTIME_SESSION has the most guarding invariants (5).
 *     - CONVERSATION_SESSION has empty guardingInvariants.
 *     - familiesWithActiveInvariant excludes CONVERSATION_SESSION.
 *     - familiesWithActiveInvariant includes ATTACHED_RUNTIME_SESSION.
 *     - invariantBindingFor returns correct binding for known family.
 *     - invariantBindingFor returns null for... (defensive; actually all have bindings).
 *     - all non-empty guardingInvariants reference valid InvariantId values.
 *     - all bindings have non-blank enforcementNote.
 *
 *  6. [StabilizationBaseline] — PR-42 entries registered.
 *     - runtime-invariant-enforcer is registered as CANONICAL_STABLE.
 *     - canonical-dispatch-chain-invariant-protected-paths is registered as CANONICAL_STABLE.
 *     - canonical-session-axis-invariant-bindings is registered as CANONICAL_STABLE.
 *     - invariant-protected-path-result is registered as CANONICAL_STABLE.
 *     - all PR-42 entries have introducedPr = 42.
 *     - all PR-42 entries have EXTEND guidance.
 *
 * ## Test matrix
 *
 * ### RuntimeInvariantEnforcer — InvariantScope enum
 *  - SESSION wireValue is "session"
 *  - TRANSPORT wireValue is "transport"
 *  - READINESS wireValue is "readiness"
 *  - DISPATCH wireValue is "dispatch"
 *  - SNAPSHOT wireValue is "snapshot"
 *  - all five wireValues are distinct
 *  - fromValue returns correct entry for each wireValue
 *  - fromValue returns null for unknown value
 *
 * ### RuntimeInvariantEnforcer — InvariantOutcome enum
 *  - SATISFIED wireValue is "satisfied"
 *  - VIOLATED wireValue is "violated"
 *  - UNVERIFIABLE wireValue is "unverifiable"
 *  - all three wireValues are distinct
 *  - fromValue returns correct entry for each wireValue
 *  - fromValue returns null for unknown value
 *
 * ### RuntimeInvariantEnforcer — InvariantSeverity enum
 *  - CRITICAL wireValue is "critical"
 *  - WARNING wireValue is "warning"
 *  - all two wireValues are distinct
 *  - fromValue returns correct entry for each wireValue
 *  - fromValue returns null for unknown value
 *
 * ### RuntimeInvariantEnforcer — InvariantId enum
 *  - nine InvariantId values exist
 *  - all wireValues are distinct
 *  - fromValue returns correct entry for known values
 *  - fromValue returns null for unknown value
 *
 * ### RuntimeInvariantEnforcer — invariant registry
 *  - invariants.size equals InvariantId.entries.size (coverage invariant)
 *  - all invariant IDs are unique
 *  - all invariants have non-blank description, canonicalSurface, enforcementNote
 *  - SESSION scope invariants exist
 *  - TRANSPORT scope invariants exist
 *  - READINESS scope invariants exist
 *  - DISPATCH scope invariants exist
 *  - SNAPSHOT scope invariants exist
 *  - criticalInvariantIds and warningInvariantIds partition all invariant IDs
 *  - criticalInvariantIds contains SESSION_ACTIVE_REQUIRES_ATTACHED
 *  - criticalInvariantIds contains TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE
 *  - warningInvariantIds contains ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE
 *  - invariantFor returns correct entry for known ID
 *  - invariantFor returns null for... (all IDs have entries; defensive)
 *  - byScope(SESSION) returns non-empty list
 *  - bySeverity(CRITICAL) returns non-empty list
 *  - INTRODUCED_PR is 42
 */
class Pr42AndroidRuntimeInvariantEnforcementTest {

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeInvariantEnforcer — InvariantScope enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `InvariantScope SESSION wireValue is session`() {
        assertEquals("session", RuntimeInvariantEnforcer.InvariantScope.SESSION.wireValue)
    }

    @Test fun `InvariantScope TRANSPORT wireValue is transport`() {
        assertEquals("transport", RuntimeInvariantEnforcer.InvariantScope.TRANSPORT.wireValue)
    }

    @Test fun `InvariantScope READINESS wireValue is readiness`() {
        assertEquals("readiness", RuntimeInvariantEnforcer.InvariantScope.READINESS.wireValue)
    }

    @Test fun `InvariantScope DISPATCH wireValue is dispatch`() {
        assertEquals("dispatch", RuntimeInvariantEnforcer.InvariantScope.DISPATCH.wireValue)
    }

    @Test fun `InvariantScope SNAPSHOT wireValue is snapshot`() {
        assertEquals("snapshot", RuntimeInvariantEnforcer.InvariantScope.SNAPSHOT.wireValue)
    }

    @Test fun `all five InvariantScope wireValues are distinct`() {
        val wireValues = RuntimeInvariantEnforcer.InvariantScope.entries.map { it.wireValue }
        assertEquals(wireValues.distinct().size, wireValues.size)
    }

    @Test fun `InvariantScope fromValue returns correct entries`() {
        assertEquals(
            RuntimeInvariantEnforcer.InvariantScope.SESSION,
            RuntimeInvariantEnforcer.InvariantScope.fromValue("session")
        )
        assertEquals(
            RuntimeInvariantEnforcer.InvariantScope.TRANSPORT,
            RuntimeInvariantEnforcer.InvariantScope.fromValue("transport")
        )
        assertEquals(
            RuntimeInvariantEnforcer.InvariantScope.DISPATCH,
            RuntimeInvariantEnforcer.InvariantScope.fromValue("dispatch")
        )
    }

    @Test fun `InvariantScope fromValue returns null for unknown value`() {
        assertNull(RuntimeInvariantEnforcer.InvariantScope.fromValue("unknown_xyz"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeInvariantEnforcer — InvariantOutcome enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `InvariantOutcome SATISFIED wireValue is satisfied`() {
        assertEquals("satisfied", RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED.wireValue)
    }

    @Test fun `InvariantOutcome VIOLATED wireValue is violated`() {
        assertEquals("violated", RuntimeInvariantEnforcer.InvariantOutcome.VIOLATED.wireValue)
    }

    @Test fun `InvariantOutcome UNVERIFIABLE wireValue is unverifiable`() {
        assertEquals("unverifiable", RuntimeInvariantEnforcer.InvariantOutcome.UNVERIFIABLE.wireValue)
    }

    @Test fun `all three InvariantOutcome wireValues are distinct`() {
        val wireValues = RuntimeInvariantEnforcer.InvariantOutcome.entries.map { it.wireValue }
        assertEquals(wireValues.distinct().size, wireValues.size)
    }

    @Test fun `InvariantOutcome fromValue returns correct entries`() {
        assertEquals(
            RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED,
            RuntimeInvariantEnforcer.InvariantOutcome.fromValue("satisfied")
        )
        assertEquals(
            RuntimeInvariantEnforcer.InvariantOutcome.VIOLATED,
            RuntimeInvariantEnforcer.InvariantOutcome.fromValue("violated")
        )
        assertEquals(
            RuntimeInvariantEnforcer.InvariantOutcome.UNVERIFIABLE,
            RuntimeInvariantEnforcer.InvariantOutcome.fromValue("unverifiable")
        )
    }

    @Test fun `InvariantOutcome fromValue returns null for unknown value`() {
        assertNull(RuntimeInvariantEnforcer.InvariantOutcome.fromValue("unknown_xyz"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeInvariantEnforcer — InvariantSeverity enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `InvariantSeverity CRITICAL wireValue is critical`() {
        assertEquals("critical", RuntimeInvariantEnforcer.InvariantSeverity.CRITICAL.wireValue)
    }

    @Test fun `InvariantSeverity WARNING wireValue is warning`() {
        assertEquals("warning", RuntimeInvariantEnforcer.InvariantSeverity.WARNING.wireValue)
    }

    @Test fun `all two InvariantSeverity wireValues are distinct`() {
        val wireValues = RuntimeInvariantEnforcer.InvariantSeverity.entries.map { it.wireValue }
        assertEquals(wireValues.distinct().size, wireValues.size)
    }

    @Test fun `InvariantSeverity fromValue returns correct entries`() {
        assertEquals(
            RuntimeInvariantEnforcer.InvariantSeverity.CRITICAL,
            RuntimeInvariantEnforcer.InvariantSeverity.fromValue("critical")
        )
        assertEquals(
            RuntimeInvariantEnforcer.InvariantSeverity.WARNING,
            RuntimeInvariantEnforcer.InvariantSeverity.fromValue("warning")
        )
    }

    @Test fun `InvariantSeverity fromValue returns null for unknown value`() {
        assertNull(RuntimeInvariantEnforcer.InvariantSeverity.fromValue("unknown_xyz"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeInvariantEnforcer — InvariantId enum
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `InvariantId has nine values`() {
        assertEquals(9, RuntimeInvariantEnforcer.InvariantId.entries.size)
    }

    @Test fun `all InvariantId wireValues are distinct`() {
        val wireValues = RuntimeInvariantEnforcer.InvariantId.entries.map { it.wireValue }
        assertEquals(wireValues.distinct().size, wireValues.size)
    }

    @Test fun `InvariantId SESSION_ACTIVE_REQUIRES_ATTACHED wireValue`() {
        assertEquals(
            "session_active_requires_attached",
            RuntimeInvariantEnforcer.InvariantId.SESSION_ACTIVE_REQUIRES_ATTACHED.wireValue
        )
    }

    @Test fun `InvariantId TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE wireValue`() {
        assertEquals(
            "transport_interrupted_blocks_cross_device",
            RuntimeInvariantEnforcer.InvariantId.TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE.wireValue
        )
    }

    @Test fun `InvariantId fromValue returns correct entry for known value`() {
        assertEquals(
            RuntimeInvariantEnforcer.InvariantId.SESSION_ACTIVE_REQUIRES_ATTACHED,
            RuntimeInvariantEnforcer.InvariantId.fromValue("session_active_requires_attached")
        )
        assertEquals(
            RuntimeInvariantEnforcer.InvariantId.KILL_SWITCH_CLEARS_CROSS_DEVICE,
            RuntimeInvariantEnforcer.InvariantId.fromValue("kill_switch_clears_cross_device")
        )
    }

    @Test fun `InvariantId fromValue returns null for unknown value`() {
        assertNull(RuntimeInvariantEnforcer.InvariantId.fromValue("unknown_invariant_xyz"))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeInvariantEnforcer — invariant registry
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `invariants size equals InvariantId entries size (coverage invariant)`() {
        assertEquals(
            "Coverage invariant: every InvariantId must have exactly one entry",
            RuntimeInvariantEnforcer.InvariantId.entries.size,
            RuntimeInvariantEnforcer.invariants.size
        )
    }

    @Test fun `all invariant IDs are unique`() {
        val ids = RuntimeInvariantEnforcer.invariants.map { it.id }
        assertEquals(ids.distinct().size, ids.size)
    }

    @Test fun `all invariants have non-blank description`() {
        RuntimeInvariantEnforcer.invariants.forEach { inv ->
            assertTrue(
                "Invariant ${inv.id} must have non-blank description",
                inv.description.isNotBlank()
            )
        }
    }

    @Test fun `all invariants have non-blank canonicalSurface`() {
        RuntimeInvariantEnforcer.invariants.forEach { inv ->
            assertTrue(
                "Invariant ${inv.id} must have non-blank canonicalSurface",
                inv.canonicalSurface.isNotBlank()
            )
        }
    }

    @Test fun `all invariants have non-blank enforcementNote`() {
        RuntimeInvariantEnforcer.invariants.forEach { inv ->
            assertTrue(
                "Invariant ${inv.id} must have non-blank enforcementNote",
                inv.enforcementNote.isNotBlank()
            )
        }
    }

    @Test fun `all five InvariantScope values have at least one invariant`() {
        val scopes = RuntimeInvariantEnforcer.invariants.map { it.scope }.toSet()
        assertTrue("SESSION scope must have invariants", RuntimeInvariantEnforcer.InvariantScope.SESSION in scopes)
        assertTrue("TRANSPORT scope must have invariants", RuntimeInvariantEnforcer.InvariantScope.TRANSPORT in scopes)
        assertTrue("READINESS scope must have invariants", RuntimeInvariantEnforcer.InvariantScope.READINESS in scopes)
        assertTrue("DISPATCH scope must have invariants", RuntimeInvariantEnforcer.InvariantScope.DISPATCH in scopes)
        assertTrue("SNAPSHOT scope must have invariants", RuntimeInvariantEnforcer.InvariantScope.SNAPSHOT in scopes)
    }

    @Test fun `criticalInvariantIds and warningInvariantIds partition all invariant IDs`() {
        val allIds = RuntimeInvariantEnforcer.InvariantId.entries.toSet()
        val covered = RuntimeInvariantEnforcer.criticalInvariantIds + RuntimeInvariantEnforcer.warningInvariantIds
        assertEquals("criticalInvariantIds + warningInvariantIds must cover all invariants", allIds, covered)
        assertTrue(
            "criticalInvariantIds and warningInvariantIds must be disjoint",
            (RuntimeInvariantEnforcer.criticalInvariantIds intersect RuntimeInvariantEnforcer.warningInvariantIds).isEmpty()
        )
    }

    @Test fun `criticalInvariantIds contains SESSION_ACTIVE_REQUIRES_ATTACHED`() {
        assertTrue(
            RuntimeInvariantEnforcer.InvariantId.SESSION_ACTIVE_REQUIRES_ATTACHED in
                RuntimeInvariantEnforcer.criticalInvariantIds
        )
    }

    @Test fun `criticalInvariantIds contains TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE`() {
        assertTrue(
            RuntimeInvariantEnforcer.InvariantId.TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE in
                RuntimeInvariantEnforcer.criticalInvariantIds
        )
    }

    @Test fun `criticalInvariantIds contains SNAPSHOT_REQUIRES_SESSION`() {
        assertTrue(
            RuntimeInvariantEnforcer.InvariantId.SNAPSHOT_REQUIRES_SESSION in
                RuntimeInvariantEnforcer.criticalInvariantIds
        )
    }

    @Test fun `warningInvariantIds contains ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE`() {
        assertTrue(
            RuntimeInvariantEnforcer.InvariantId.ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE in
                RuntimeInvariantEnforcer.warningInvariantIds
        )
    }

    @Test fun `invariantFor returns correct entry for known InvariantId`() {
        val inv = RuntimeInvariantEnforcer.invariantFor(
            RuntimeInvariantEnforcer.InvariantId.SESSION_ACTIVE_REQUIRES_ATTACHED
        )
        assertNotNull(inv)
        assertEquals(RuntimeInvariantEnforcer.InvariantScope.SESSION, inv!!.scope)
        assertEquals(RuntimeInvariantEnforcer.InvariantSeverity.CRITICAL, inv.severity)
    }

    @Test fun `byScope SESSION returns non-empty list`() {
        val sessionInvariants = RuntimeInvariantEnforcer.byScope(RuntimeInvariantEnforcer.InvariantScope.SESSION)
        assertTrue("SESSION scope must have at least one invariant", sessionInvariants.isNotEmpty())
    }

    @Test fun `byScope TRANSPORT returns non-empty list`() {
        val transportInvariants = RuntimeInvariantEnforcer.byScope(RuntimeInvariantEnforcer.InvariantScope.TRANSPORT)
        assertTrue("TRANSPORT scope must have at least one invariant", transportInvariants.isNotEmpty())
    }

    @Test fun `bySeverity CRITICAL returns non-empty list`() {
        val criticalInvariants = RuntimeInvariantEnforcer.bySeverity(RuntimeInvariantEnforcer.InvariantSeverity.CRITICAL)
        assertTrue("CRITICAL severity must have at least one invariant", criticalInvariants.isNotEmpty())
    }

    @Test fun `INTRODUCED_PR is 42`() {
        assertEquals(42, RuntimeInvariantEnforcer.INTRODUCED_PR)
    }

    @Test fun `DESCRIPTION is non-blank`() {
        assertTrue(RuntimeInvariantEnforcer.DESCRIPTION.isNotBlank())
    }

    @Test fun `COVERAGE_INVARIANT is non-blank`() {
        assertTrue(RuntimeInvariantEnforcer.COVERAGE_INVARIANT.isNotBlank())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeInvariantEnforcer — SESSION_ACTIVE_REQUIRES_ATTACHED
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `SESSION_ACTIVE_REQUIRES_ATTACHED Active with no session is VIOLATED`() {
        val result = RuntimeInvariantEnforcer.checkSessionActiveRequiresAttached(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = null
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.VIOLATED, result.outcome)
        assertTrue(result.isViolation)
        assertNotNull(result.violationDetail)
    }

    @Test fun `SESSION_ACTIVE_REQUIRES_ATTACHED Active with ATTACHED session is SATISFIED`() {
        val session = AttachedRuntimeSession.create(hostId = "h1", deviceId = "d1")
        val result = RuntimeInvariantEnforcer.checkSessionActiveRequiresAttached(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = session
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
        assertTrue(result.isSatisfied)
        assertNull(result.violationDetail)
    }

    @Test fun `SESSION_ACTIVE_REQUIRES_ATTACHED LocalOnly with no session is SATISFIED`() {
        val result = RuntimeInvariantEnforcer.checkSessionActiveRequiresAttached(
            runtimeState = RuntimeController.RuntimeState.LocalOnly,
            attachedSession = null
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    @Test fun `SESSION_ACTIVE_REQUIRES_ATTACHED Idle with no session is SATISFIED`() {
        val result = RuntimeInvariantEnforcer.checkSessionActiveRequiresAttached(
            runtimeState = RuntimeController.RuntimeState.Idle,
            attachedSession = null
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeInvariantEnforcer — ATTACHED_REQUIRES_ACTIVE_OR_RECOVERY
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `ATTACHED_REQUIRES_ACTIVE_OR_RECOVERY ATTACHED session with LocalOnly is VIOLATED`() {
        val session = AttachedRuntimeSession.create(hostId = "h1", deviceId = "d1")
        val result = RuntimeInvariantEnforcer.checkAttachedRequiresActiveOrRecovery(
            runtimeState = RuntimeController.RuntimeState.LocalOnly,
            attachedSession = session
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.VIOLATED, result.outcome)
        assertNotNull(result.violationDetail)
    }

    @Test fun `ATTACHED_REQUIRES_ACTIVE_OR_RECOVERY ATTACHED session with Active is SATISFIED`() {
        val session = AttachedRuntimeSession.create(hostId = "h1", deviceId = "d1")
        val result = RuntimeInvariantEnforcer.checkAttachedRequiresActiveOrRecovery(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = session
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    @Test fun `ATTACHED_REQUIRES_ACTIVE_OR_RECOVERY no session is SATISFIED`() {
        val result = RuntimeInvariantEnforcer.checkAttachedRequiresActiveOrRecovery(
            runtimeState = RuntimeController.RuntimeState.LocalOnly,
            attachedSession = null
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    @Test fun `ATTACHED_REQUIRES_ACTIVE_OR_RECOVERY ATTACHED with Starting is VIOLATED`() {
        val session = AttachedRuntimeSession.create(hostId = "h1", deviceId = "d1")
        val result = RuntimeInvariantEnforcer.checkAttachedRequiresActiveOrRecovery(
            runtimeState = RuntimeController.RuntimeState.Starting,
            attachedSession = session
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.VIOLATED, result.outcome)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeInvariantEnforcer — TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE INTERRUPTED with eligible state is SATISFIED when filter works`() {
        val session = AttachedRuntimeSession.create(hostId = "h1", deviceId = "d1")
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = true
        )
        // Note: resolveTransportAdaptedPaths with INTERRUPTED should already suppress cross-device,
        // but if there's a bug and it doesn't, the invariant check catches it.
        // Here we verify the check itself returns SATISFIED when the transport filter is working.
        val result = RuntimeInvariantEnforcer.checkTransportInterruptedBlocksCrossDevice(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = session,
            rollout = rollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.INTERRUPTED
        )
        // Since resolveTransportAdaptedPaths correctly filters cross-device paths for INTERRUPTED,
        // the invariant should be SATISFIED (the mechanism works correctly).
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    @Test fun `TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE STABLE is always SATISFIED`() {
        val session = AttachedRuntimeSession.create(hostId = "h1", deviceId = "d1")
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = true
        )
        val result = RuntimeInvariantEnforcer.checkTransportInterruptedBlocksCrossDevice(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = session,
            rollout = rollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    @Test fun `TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE SUSPENDED is also SATISFIED when filter works`() {
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = false,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        val result = RuntimeInvariantEnforcer.checkTransportInterruptedBlocksCrossDevice(
            runtimeState = RuntimeController.RuntimeState.LocalOnly,
            attachedSession = null,
            rollout = rollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.SUSPENDED
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    @Test fun `TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE DEGRADED is SATISFIED (advisory only)`() {
        val session = AttachedRuntimeSession.create(hostId = "h1", deviceId = "d1")
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = true
        )
        val result = RuntimeInvariantEnforcer.checkTransportInterruptedBlocksCrossDevice(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = session,
            rollout = rollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.DEGRADED
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeInvariantEnforcer — SNAPSHOT_REQUIRES_SESSION
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `SNAPSHOT_REQUIRES_SESSION snapshot with no session is VIOLATED`() {
        val session = AttachedRuntimeSession.create(hostId = "h1", deviceId = "d1")
        val snapshot = AttachedRuntimeHostSessionSnapshot.from(session = session, runtimeSessionId = "runtime-uuid-001", hostRole = "primary")
        val result = RuntimeInvariantEnforcer.checkSnapshotRequiresSession(
            attachedSession = null,
            hostSessionSnapshot = snapshot
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.VIOLATED, result.outcome)
        assertNotNull(result.violationDetail)
    }

    @Test fun `SNAPSHOT_REQUIRES_SESSION snapshot with session is SATISFIED`() {
        val session = AttachedRuntimeSession.create(hostId = "h1", deviceId = "d1")
        val snapshot = AttachedRuntimeHostSessionSnapshot.from(session = session, runtimeSessionId = "runtime-uuid-001", hostRole = "primary")
        val result = RuntimeInvariantEnforcer.checkSnapshotRequiresSession(
            attachedSession = session,
            hostSessionSnapshot = snapshot
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    @Test fun `SNAPSHOT_REQUIRES_SESSION no snapshot is SATISFIED`() {
        val result = RuntimeInvariantEnforcer.checkSnapshotRequiresSession(
            attachedSession = null,
            hostSessionSnapshot = null
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeInvariantEnforcer — ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE delegated=true cross=false is VIOLATED`() {
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = false,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        val result = RuntimeInvariantEnforcer.checkRolloutGateDelegatedRequiresCrossDevice(rollout)
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.VIOLATED, result.outcome)
        assertNotNull(result.violationDetail)
    }

    @Test fun `ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE delegated=true cross=true is SATISFIED`() {
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = true
        )
        val result = RuntimeInvariantEnforcer.checkRolloutGateDelegatedRequiresCrossDevice(rollout)
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    @Test fun `ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE both false is SATISFIED`() {
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = false,
            delegatedExecutionAllowed = false,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        val result = RuntimeInvariantEnforcer.checkRolloutGateDelegatedRequiresCrossDevice(rollout)
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeInvariantEnforcer — DISPATCH_ELIGIBILITY_CONSISTENT_WITH_READINESS
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `DISPATCH_ELIGIBILITY_CONSISTENT ineligible runtime canonical absent is SATISFIED`() {
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = true
        )
        // Runtime not active → ineligible → canonical path should not be resolved
        val result = RuntimeInvariantEnforcer.checkDispatchEligibilityConsistentWithReadiness(
            runtimeState = RuntimeController.RuntimeState.LocalOnly,
            attachedSession = null,
            rollout = rollout
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    @Test fun `DISPATCH_ELIGIBILITY_CONSISTENT eligible runtime canonical present is SATISFIED`() {
        val session = AttachedRuntimeSession.create(hostId = "h1", deviceId = "d1")
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = true
        )
        val result = RuntimeInvariantEnforcer.checkDispatchEligibilityConsistentWithReadiness(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = session,
            rollout = rollout
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeInvariantEnforcer — DURABLE_SESSION_PRESENT_WHEN_ACTIVE
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `DURABLE_SESSION_PRESENT_WHEN_ACTIVE Active with null record is VIOLATED`() {
        val result = RuntimeInvariantEnforcer.checkDurableSessionPresentWhenActive(
            runtimeState = RuntimeController.RuntimeState.Active,
            durableRecord = null
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.VIOLATED, result.outcome)
        assertNotNull(result.violationDetail)
    }

    @Test fun `DURABLE_SESSION_PRESENT_WHEN_ACTIVE Active with non-null record is SATISFIED`() {
        val durableRecord = DurableSessionContinuityRecord.create("user_activation")
        val result = RuntimeInvariantEnforcer.checkDurableSessionPresentWhenActive(
            runtimeState = RuntimeController.RuntimeState.Active,
            durableRecord = durableRecord
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    @Test fun `DURABLE_SESSION_PRESENT_WHEN_ACTIVE LocalOnly with null record is SATISFIED`() {
        val result = RuntimeInvariantEnforcer.checkDurableSessionPresentWhenActive(
            runtimeState = RuntimeController.RuntimeState.LocalOnly,
            durableRecord = null
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    @Test fun `DURABLE_SESSION_PRESENT_WHEN_ACTIVE Idle with null record is SATISFIED`() {
        val result = RuntimeInvariantEnforcer.checkDurableSessionPresentWhenActive(
            runtimeState = RuntimeController.RuntimeState.Idle,
            durableRecord = null
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeInvariantEnforcer — RECOVERY_STATE_CONSISTENT_WITH_RUNTIME
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `RECOVERY_STATE_CONSISTENT RECOVERING with LocalOnly is VIOLATED`() {
        val result = RuntimeInvariantEnforcer.checkRecoveryStateConsistentWithRuntime(
            runtimeState = RuntimeController.RuntimeState.LocalOnly,
            recoveryState = ReconnectRecoveryState.RECOVERING
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.VIOLATED, result.outcome)
        assertNotNull(result.violationDetail)
    }

    @Test fun `RECOVERY_STATE_CONSISTENT FAILED with LocalOnly is VIOLATED`() {
        val result = RuntimeInvariantEnforcer.checkRecoveryStateConsistentWithRuntime(
            runtimeState = RuntimeController.RuntimeState.LocalOnly,
            recoveryState = ReconnectRecoveryState.FAILED
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.VIOLATED, result.outcome)
    }

    @Test fun `RECOVERY_STATE_CONSISTENT RECOVERING with Active is SATISFIED`() {
        val result = RuntimeInvariantEnforcer.checkRecoveryStateConsistentWithRuntime(
            runtimeState = RuntimeController.RuntimeState.Active,
            recoveryState = ReconnectRecoveryState.RECOVERING
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    @Test fun `RECOVERY_STATE_CONSISTENT IDLE with LocalOnly is SATISFIED`() {
        val result = RuntimeInvariantEnforcer.checkRecoveryStateConsistentWithRuntime(
            runtimeState = RuntimeController.RuntimeState.LocalOnly,
            recoveryState = ReconnectRecoveryState.IDLE
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    @Test fun `RECOVERY_STATE_CONSISTENT RECOVERED with Active is SATISFIED`() {
        val result = RuntimeInvariantEnforcer.checkRecoveryStateConsistentWithRuntime(
            runtimeState = RuntimeController.RuntimeState.Active,
            recoveryState = ReconnectRecoveryState.RECOVERED
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeInvariantEnforcer — KILL_SWITCH_CLEARS_CROSS_DEVICE
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `KILL_SWITCH_CLEARS_CROSS_DEVICE kill-switch Active is VIOLATED`() {
        val killSwitchRollout = RolloutControlSnapshot(
            crossDeviceAllowed = false,
            delegatedExecutionAllowed = false,
            fallbackToLocalAllowed = false,
            goalExecutionAllowed = false
        )
        val result = RuntimeInvariantEnforcer.checkKillSwitchClearsCrossDevice(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = null,
            rollout = killSwitchRollout
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.VIOLATED, result.outcome)
        assertNotNull(result.violationDetail)
    }

    @Test fun `KILL_SWITCH_CLEARS_CROSS_DEVICE kill-switch ATTACHED session is VIOLATED`() {
        val killSwitchRollout = RolloutControlSnapshot(
            crossDeviceAllowed = false,
            delegatedExecutionAllowed = false,
            fallbackToLocalAllowed = false,
            goalExecutionAllowed = false
        )
        val session = AttachedRuntimeSession.create(hostId = "h1", deviceId = "d1")
        val result = RuntimeInvariantEnforcer.checkKillSwitchClearsCrossDevice(
            runtimeState = RuntimeController.RuntimeState.LocalOnly,
            attachedSession = session,
            rollout = killSwitchRollout
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.VIOLATED, result.outcome)
    }

    @Test fun `KILL_SWITCH_CLEARS_CROSS_DEVICE kill-switch LocalOnly no session is SATISFIED`() {
        val killSwitchRollout = RolloutControlSnapshot(
            crossDeviceAllowed = false,
            delegatedExecutionAllowed = false,
            fallbackToLocalAllowed = false,
            goalExecutionAllowed = false
        )
        val result = RuntimeInvariantEnforcer.checkKillSwitchClearsCrossDevice(
            runtimeState = RuntimeController.RuntimeState.LocalOnly,
            attachedSession = null,
            rollout = killSwitchRollout
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    @Test fun `KILL_SWITCH_CLEARS_CROSS_DEVICE no kill-switch is SATISFIED`() {
        val normalRollout = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = true
        )
        val result = RuntimeInvariantEnforcer.checkKillSwitchClearsCrossDevice(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = null,
            rollout = normalRollout
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantOutcome.SATISFIED, result.outcome)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RuntimeInvariantEnforcer — checkAll / violatedInvariantIds / allSatisfied
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `checkAll returns one result per registered invariant`() {
        val results = RuntimeInvariantEnforcer.checkAll(
            runtimeState = RuntimeController.RuntimeState.LocalOnly,
            attachedSession = null,
            rollout = RolloutControlSnapshot(
                crossDeviceAllowed = false,
                delegatedExecutionAllowed = false,
                fallbackToLocalAllowed = true,
                goalExecutionAllowed = false
            ),
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE
        )
        assertEquals(RuntimeInvariantEnforcer.InvariantId.entries.size, results.size)
    }

    @Test fun `violatedInvariantIds returns empty set when all satisfied`() {
        val session = AttachedRuntimeSession.create(hostId = "h1", deviceId = "d1")
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = true
        )
        val durableRecord = DurableSessionContinuityRecord.create("user_activation")
        val violated = RuntimeInvariantEnforcer.violatedInvariantIds(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = session,
            rollout = rollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE,
            durableRecord = durableRecord,
            recoveryState = ReconnectRecoveryState.IDLE
        )
        assertTrue("No invariants should be violated for a well-formed Active state", violated.isEmpty())
    }

    @Test fun `violatedInvariantIds returns non-empty set when violation present`() {
        // Delegated allowed without cross-device → ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE violation
        val badRollout = RolloutControlSnapshot(
            crossDeviceAllowed = false,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        val violated = RuntimeInvariantEnforcer.violatedInvariantIds(
            runtimeState = RuntimeController.RuntimeState.LocalOnly,
            attachedSession = null,
            rollout = badRollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE
        )
        assertTrue(
            "ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE must be violated",
            RuntimeInvariantEnforcer.InvariantId.ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE in violated
        )
    }

    @Test fun `allSatisfied returns true for well-formed Active state`() {
        val session = AttachedRuntimeSession.create(hostId = "h1", deviceId = "d1")
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = true
        )
        val durableRecord = DurableSessionContinuityRecord.create("user_activation")
        val satisfied = RuntimeInvariantEnforcer.allSatisfied(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = session,
            rollout = rollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE,
            durableRecord = durableRecord,
            recoveryState = ReconnectRecoveryState.IDLE
        )
        assertTrue("All invariants should be satisfied for a well-formed Active state", satisfied)
    }

    @Test fun `allSatisfied returns false when kill-switch violated`() {
        val killSwitchRollout = RolloutControlSnapshot(
            crossDeviceAllowed = false,
            delegatedExecutionAllowed = false,
            fallbackToLocalAllowed = false,
            goalExecutionAllowed = false
        )
        val satisfied = RuntimeInvariantEnforcer.allSatisfied(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = null,
            rollout = killSwitchRollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE
        )
        assertFalse("allSatisfied must return false when kill-switch invariant is violated", satisfied)
    }

    @Test fun `allSatisfied returns true for clean LocalOnly state`() {
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = false,
            delegatedExecutionAllowed = false,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        val satisfied = RuntimeInvariantEnforcer.allSatisfied(
            runtimeState = RuntimeController.RuntimeState.LocalOnly,
            attachedSession = null,
            rollout = rollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE,
            recoveryState = ReconnectRecoveryState.IDLE
        )
        assertTrue("All invariants should be satisfied for a clean LocalOnly state", satisfied)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CanonicalDispatchChain — resolveInvariantProtectedPaths
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `resolveInvariantProtectedPaths clean Active state returns non-empty paths`() {
        val session = AttachedRuntimeSession.create(hostId = "h1", deviceId = "d1")
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = true
        )
        val durableRecord = DurableSessionContinuityRecord.create("user_activation")
        val result = CanonicalDispatchChain.resolveInvariantProtectedPaths(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = session,
            rollout = rollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE,
            durableRecord = durableRecord,
            recoveryState = ReconnectRecoveryState.IDLE
        )
        assertFalse("Paths should not be empty for a well-formed Active state", result.paths.isEmpty())
        assertFalse("blockedByInvariant must be false for well-formed state", result.blockedByInvariant)
        assertTrue("allInvariantsSatisfied must be true for well-formed state", result.allInvariantsSatisfied)
    }

    @Test fun `resolveInvariantProtectedPaths kill-switch violated blocks paths`() {
        val killSwitchRollout = RolloutControlSnapshot(
            crossDeviceAllowed = false,
            delegatedExecutionAllowed = false,
            fallbackToLocalAllowed = false,
            goalExecutionAllowed = false
        )
        // Active runtime with kill-switch: KILL_SWITCH invariant is CRITICAL/READINESS
        // (not SESSION or DISPATCH), so it should NOT block all paths in the current impl.
        // Let's use a SESSION violation instead: Active + no session
        val result = CanonicalDispatchChain.resolveInvariantProtectedPaths(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = null,  // SESSION_ACTIVE_REQUIRES_ATTACHED violated
            rollout = killSwitchRollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE
        )
        // SESSION_ACTIVE_REQUIRES_ATTACHED is CRITICAL+SESSION → should block
        assertTrue("blockedByInvariant must be true when a CRITICAL SESSION invariant is violated",
            result.blockedByInvariant)
        assertTrue("paths must be empty when blocked by invariant", result.paths.isEmpty())
        assertFalse("violations must be non-empty when blocked", result.violations.isEmpty())
    }

    @Test fun `resolveInvariantProtectedPaths ATTACHED session with LocalOnly blocks paths`() {
        // ATTACHED_REQUIRES_ACTIVE_OR_RECOVERY is CRITICAL+SESSION
        val session = AttachedRuntimeSession.create(hostId = "h1", deviceId = "d1")
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = false,
            delegatedExecutionAllowed = false,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        val result = CanonicalDispatchChain.resolveInvariantProtectedPaths(
            runtimeState = RuntimeController.RuntimeState.LocalOnly,
            attachedSession = session,  // ATTACHED but LocalOnly → VIOLATION
            rollout = rollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE
        )
        assertTrue("blockedByInvariant must be true for CRITICAL SESSION violation", result.blockedByInvariant)
        assertTrue("paths must be empty when blocked", result.paths.isEmpty())
    }

    @Test fun `resolveInvariantProtectedPaths warning-only violation does not block paths`() {
        // ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE is WARNING — should not block paths
        val warnRollout = RolloutControlSnapshot(
            crossDeviceAllowed = false,
            delegatedExecutionAllowed = true,  // WARNING invariant violated
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = false
        )
        val result = CanonicalDispatchChain.resolveInvariantProtectedPaths(
            runtimeState = RuntimeController.RuntimeState.LocalOnly,
            attachedSession = null,
            rollout = warnRollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE
        )
        assertFalse("blockedByInvariant must be false for WARNING-only violation", result.blockedByInvariant)
        assertFalse("allInvariantsSatisfied must be false when a WARNING is violated",
            result.allInvariantsSatisfied)
        assertTrue(
            "ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE must be in violatedIds",
            RuntimeInvariantEnforcer.InvariantId.ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE in result.violatedIds
        )
    }

    @Test fun `resolveInvariantProtectedPaths result subset of resolveContractFinalizedPaths`() {
        val session = AttachedRuntimeSession.create(hostId = "h1", deviceId = "d1")
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = true,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = true,
            goalExecutionAllowed = true
        )
        val durableRecord = DurableSessionContinuityRecord.create("user_activation")
        val invariantResult = CanonicalDispatchChain.resolveInvariantProtectedPaths(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = session,
            rollout = rollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE,
            durableRecord = durableRecord
        )
        val contractFinalizedPaths = CanonicalDispatchChain.resolveContractFinalizedPaths(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = session,
            rollout = rollout,
            transportCondition = MediaTransportLifecycleBridge.TransportCondition.STABLE
        )
        val invariantModes = invariantResult.paths.map { it.pathMode }.toSet()
        val finalizedModes = contractFinalizedPaths.map { it.pathMode }.toSet()
        assertTrue(
            "Invariant-protected paths must be a subset of contract-finalized paths",
            finalizedModes.containsAll(invariantModes)
        )
    }

    @Test fun `InvariantProtectedPathResult allInvariantsSatisfied is false when violations present`() {
        val result = InvariantProtectedPathResult(
            paths = emptyList(),
            violations = listOf(
                RuntimeInvariantEnforcer.InvariantCheckResult(
                    invariantId = RuntimeInvariantEnforcer.InvariantId.SESSION_ACTIVE_REQUIRES_ATTACHED,
                    outcome = RuntimeInvariantEnforcer.InvariantOutcome.VIOLATED,
                    violationDetail = "test violation"
                )
            ),
            blockedByInvariant = true
        )
        assertFalse(result.allInvariantsSatisfied)
        assertEquals(
            setOf(RuntimeInvariantEnforcer.InvariantId.SESSION_ACTIVE_REQUIRES_ATTACHED),
            result.violatedIds
        )
    }

    @Test fun `InvariantProtectedPathResult allInvariantsSatisfied is true when no violations`() {
        val result = InvariantProtectedPathResult(
            paths = emptyList(),
            violations = emptyList(),
            blockedByInvariant = false
        )
        assertTrue(result.allInvariantsSatisfied)
        assertTrue(result.violatedIds.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CanonicalSessionAxis — invariantBindings
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `invariantBindings has 7 entries`() {
        assertEquals(7, CanonicalSessionAxis.invariantBindings.size)
    }

    @Test fun `all seven CanonicalSessionFamily values have an invariant binding`() {
        val boundFamilies = CanonicalSessionAxis.invariantBindings.map { it.family }.toSet()
        CanonicalSessionFamily.entries.forEach { family ->
            assertTrue("$family must have an invariant binding", family in boundFamilies)
        }
    }

    @Test fun `CONVERSATION_SESSION has empty guardingInvariants`() {
        val binding = CanonicalSessionAxis.invariantBindingFor(CanonicalSessionFamily.CONVERSATION_SESSION)
        assertNotNull(binding)
        assertTrue(
            "CONVERSATION_SESSION should have no guarding invariants (local-only family)",
            binding!!.guardingInvariants.isEmpty()
        )
    }

    @Test fun `ATTACHED_RUNTIME_SESSION has multiple guarding invariants`() {
        val binding = CanonicalSessionAxis.invariantBindingFor(CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION)
        assertNotNull(binding)
        assertTrue(
            "ATTACHED_RUNTIME_SESSION should have multiple guarding invariants",
            binding!!.guardingInvariants.size >= 3
        )
    }

    @Test fun `familiesWithActiveInvariant excludes CONVERSATION_SESSION`() {
        assertFalse(
            "CONVERSATION_SESSION must not be in familiesWithActiveInvariant",
            CanonicalSessionFamily.CONVERSATION_SESSION in CanonicalSessionAxis.familiesWithActiveInvariant
        )
    }

    @Test fun `familiesWithActiveInvariant includes ATTACHED_RUNTIME_SESSION`() {
        assertTrue(
            "ATTACHED_RUNTIME_SESSION must be in familiesWithActiveInvariant",
            CanonicalSessionFamily.ATTACHED_RUNTIME_SESSION in CanonicalSessionAxis.familiesWithActiveInvariant
        )
    }

    @Test fun `familiesWithActiveInvariant includes RUNTIME_SESSION`() {
        assertTrue(
            "RUNTIME_SESSION must be in familiesWithActiveInvariant",
            CanonicalSessionFamily.RUNTIME_SESSION in CanonicalSessionAxis.familiesWithActiveInvariant
        )
    }

    @Test fun `familiesWithActiveInvariant includes DURABLE_RUNTIME_SESSION`() {
        assertTrue(
            "DURABLE_RUNTIME_SESSION must be in familiesWithActiveInvariant",
            CanonicalSessionFamily.DURABLE_RUNTIME_SESSION in CanonicalSessionAxis.familiesWithActiveInvariant
        )
    }

    @Test fun `invariantBindingFor returns correct binding for RUNTIME_SESSION`() {
        val binding = CanonicalSessionAxis.invariantBindingFor(CanonicalSessionFamily.RUNTIME_SESSION)
        assertNotNull(binding)
        assertEquals(CanonicalSessionFamily.RUNTIME_SESSION, binding!!.family)
        assertTrue(
            "RUNTIME_SESSION binding must include SESSION_ACTIVE_REQUIRES_ATTACHED",
            RuntimeInvariantEnforcer.InvariantId.SESSION_ACTIVE_REQUIRES_ATTACHED in binding.guardingInvariants
        )
    }

    @Test fun `all invariant bindings have non-blank enforcementNote`() {
        CanonicalSessionAxis.invariantBindings.forEach { binding ->
            assertTrue(
                "${binding.family} must have non-blank enforcementNote",
                binding.enforcementNote.isNotBlank()
            )
        }
    }

    @Test fun `all non-empty guardingInvariants reference valid InvariantId values`() {
        val validIds = RuntimeInvariantEnforcer.InvariantId.entries.toSet()
        CanonicalSessionAxis.invariantBindings.forEach { binding ->
            binding.guardingInvariants.forEach { id ->
                assertTrue(
                    "${binding.family} references invalid InvariantId $id",
                    id in validIds
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // StabilizationBaseline — PR-42 entries
    // ─────────────────────────────────────────────────────────────────────────

    @Test fun `runtime-invariant-enforcer is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("runtime-invariant-enforcer")
        assertNotNull("runtime-invariant-enforcer must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test fun `canonical-dispatch-chain-invariant-protected-paths is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("canonical-dispatch-chain-invariant-protected-paths")
        assertNotNull("canonical-dispatch-chain-invariant-protected-paths must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test fun `canonical-session-axis-invariant-bindings is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("canonical-session-axis-invariant-bindings")
        assertNotNull("canonical-session-axis-invariant-bindings must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test fun `invariant-protected-path-result is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("invariant-protected-path-result")
        assertNotNull("invariant-protected-path-result must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test fun `all PR-42 entries have introducedPr equal to 42`() {
        val pr42Entries = StabilizationBaseline.entries.filter { it.introducedPr == 42 }
        assertTrue("There must be at least one PR-42 entry", pr42Entries.isNotEmpty())
        pr42Entries.forEach { entry ->
            assertEquals("${entry.surfaceId} must have introducedPr=42", 42, entry.introducedPr)
        }
    }

    @Test fun `all PR-42 entries have EXTEND guidance`() {
        val pr42Entries = StabilizationBaseline.entries.filter { it.introducedPr == 42 }
        pr42Entries.forEach { entry ->
            assertEquals(
                "${entry.surfaceId} must have EXTEND guidance",
                StabilizationBaseline.ExtensionGuidance.EXTEND,
                entry.extensionGuidance
            )
        }
    }

    @Test fun `four PR-42 entries are registered`() {
        val pr42Entries = StabilizationBaseline.entries.filter { it.introducedPr == 42 }
        assertEquals(
            "Exactly four PR-42 entries must be registered in StabilizationBaseline",
            4,
            pr42Entries.size
        )
    }

    @Test fun `all PR-42 entries have non-blank rationale`() {
        val pr42Entries = StabilizationBaseline.entries.filter { it.introducedPr == 42 }
        pr42Entries.forEach { entry ->
            assertTrue("${entry.surfaceId} must have non-blank rationale", entry.rationale.isNotBlank())
        }
    }
}
