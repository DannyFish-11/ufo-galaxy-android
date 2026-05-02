package com.ufo.galaxy.runtime

import com.ufo.galaxy.observability.GalaxyLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-11 — Architecture stabilization baseline.
 *
 * Regression and acceptance test suite for all PR-11 additions:
 *
 *  1. **[StabilizationBaseline]** — explicit stabilization baseline registry documenting
 *     canonical stable, canonical frozen, transitional, and retirement-gated surfaces, with
 *     extension vs convergence guidance for each.
 *     - All targeted surfaces are registered.
 *     - [StabilizationBaseline.SurfaceStability] and [StabilizationBaseline.ExtensionGuidance]
 *       enums have the expected values.
 *     - [StabilizationBaseline.byStability], [StabilizationBaseline.byGuidance],
 *       [StabilizationBaseline.forId], [StabilizationBaseline.canonicalSurfaceIds],
 *       [StabilizationBaseline.transitionalSurfaceIds], and
 *       [StabilizationBaseline.extendableSurfaceIds] query helpers are correct.
 *     - [StabilizationBaseline.BASELINE_PR], [StabilizationBaseline.BASELINE_DESCRIPTION],
 *       [StabilizationBaseline.canonicalOnlyWorkCategories],
 *       [StabilizationBaseline.extensionPoints], and
 *       [StabilizationBaseline.convergenceTargets] system-level constants are non-empty.
 *
 *  2. **[GalaxyLogger.TAG_STABILIZATION_BASELINE]** — new stable structured log tag constant
 *     (`"GALAXY:STABILIZATION:BASELINE"`) for stabilization-baseline governance events.
 *
 * ## Test matrix
 *
 * ### StabilizationBaseline — SurfaceStability and ExtensionGuidance enum coverage
 *  - SurfaceStability has CANONICAL_STABLE value
 *  - SurfaceStability has CANONICAL_FROZEN value
 *  - SurfaceStability has TRANSITIONAL value
 *  - SurfaceStability has RETIREMENT_GATED value
 *  - ExtensionGuidance has EXTEND value
 *  - ExtensionGuidance has CONVERGE value
 *  - ExtensionGuidance has WRAP_ONLY value
 *  - ExtensionGuidance has NO_NEW_WORK value
 *
 * ### StabilizationBaseline — canonical stable lifecycle truth surfaces
 *  - runtime-controller-state is registered and CANONICAL_STABLE with EXTEND guidance
 *  - runtime-controller-host-session-snapshot is registered and CANONICAL_STABLE with EXTEND guidance
 *  - runtime-controller-target-readiness-projection is registered and CANONICAL_STABLE with EXTEND guidance
 *  - runtime-controller-reconnect-recovery-state is registered and CANONICAL_STABLE with EXTEND guidance
 *
 * ### StabilizationBaseline — canonical stable dispatch governance surfaces
 *  - canonical-dispatch-chain is registered and CANONICAL_STABLE with EXTEND guidance
 *  - rollout-control-snapshot is registered and CANONICAL_STABLE with EXTEND guidance
 *  - execution-route-tag is registered and CANONICAL_STABLE with EXTEND guidance
 *
 * ### StabilizationBaseline — canonical stable signal surfaces
 *  - delegated-execution-signal is registered and CANONICAL_STABLE with EXTEND guidance
 *  - emitted-signal-ledger is registered and CANONICAL_STABLE with EXTEND guidance
 *
 * ### StabilizationBaseline — canonical stable session identity surfaces
 *  - canonical-session-axis is registered and CANONICAL_STABLE with EXTEND guidance
 *  - attached-runtime-host-session-snapshot is registered and CANONICAL_STABLE with EXTEND guidance
 *  - runtime-identity-contracts is registered and CANONICAL_FROZEN with WRAP_ONLY guidance
 *
 * ### StabilizationBaseline — canonical stable projection model surfaces
 *  - canonical-participant-model is registered and CANONICAL_STABLE with EXTEND guidance
 *  - canonical-device-model is registered and CANONICAL_STABLE with EXTEND guidance
 *  - canonical-capability-provider-model is registered and CANONICAL_STABLE with EXTEND guidance
 *
 * ### StabilizationBaseline — canonical stable failure classification surfaces
 *  - cross-device-setup-error is registered and CANONICAL_STABLE with EXTEND guidance
 *  - takeover-fallback-event is registered and CANONICAL_STABLE with EXTEND guidance
 *
 * ### StabilizationBaseline — canonical stable compatibility inventory surfaces
 *  - compatibility-surface-retirement-registry is registered and CANONICAL_STABLE with EXTEND guidance
 *  - long-tail-compatibility-registry is registered and CANONICAL_STABLE with EXTEND guidance
 *
 * ### StabilizationBaseline — canonical stable protocol alignment surfaces
 *  - ugcp-shared-schema-alignment is registered and CANONICAL_STABLE with EXTEND guidance
 *  - ugcp-protocol-consistency-rules is registered and CANONICAL_STABLE with EXTEND guidance
 *
 * ### StabilizationBaseline — transitional surfaces
 *  - legacy-registration-error-bridge is TRANSITIONAL with CONVERGE guidance
 *  - legacy-registration-error-bridge has non-null canonicalReplacement
 *  - legacy-current-session-snapshot-map is TRANSITIONAL with CONVERGE guidance
 *  - legacy-current-session-snapshot-map has non-null canonicalReplacement
 *  - ugcp-legacy-wire-value-aliases is TRANSITIONAL with CONVERGE guidance
 *  - ugcp-legacy-wire-value-aliases has non-null canonicalReplacement
 *
 * ### StabilizationBaseline — retirement-gated surfaces
 *  - long-tail-relay-forward-reply is RETIREMENT_GATED with NO_NEW_WORK guidance
 *  - long-tail-relay-forward-reply has non-null retirementGate
 *  - protocol-surface-transitional-compatibility is RETIREMENT_GATED with NO_NEW_WORK guidance
 *  - protocol-surface-transitional-compatibility has non-null retirementGate
 *
 * ### StabilizationBaseline — tier and guidance counts
 *  - CANONICAL_STABLE count is 109
 *  - CANONICAL_FROZEN count is 1
 *  - TRANSITIONAL count is 2
 *  - RETIREMENT_GATED count is 3
 *  - total entry count is 115
 *  - EXTEND guidance count is 109
 *  - CONVERGE guidance count is 3
 *  - WRAP_ONLY guidance count is 1
 *  - NO_NEW_WORK guidance count is 2
 *
 * ### StabilizationBaseline — data integrity
 *  - all entries have non-blank surfaceId
 *  - all entries have non-blank displayName
 *  - all entries have non-blank packagePath
 *  - all entries have non-blank rationale
 *  - all surfaceIds are unique
 *  - all TRANSITIONAL entries have non-null canonicalReplacement
 *  - all RETIREMENT_GATED entries have non-null retirementGate
 *  - CANONICAL_STABLE entries do not have canonicalReplacement
 *  - CANONICAL_FROZEN entries do not have canonicalReplacement
 *  - forId returns null for unregistered surface ID
 *
 * ### StabilizationBaseline — query helper correctness
 *  - byStability(CANONICAL_STABLE) returns only CANONICAL_STABLE entries
 *  - byStability(TRANSITIONAL) returns only TRANSITIONAL entries
 *  - byGuidance(EXTEND) returns only EXTEND entries
 *  - byGuidance(NO_NEW_WORK) returns only NO_NEW_WORK entries
 *  - canonicalSurfaceIds contains only CANONICAL_STABLE and CANONICAL_FROZEN IDs
 *  - transitionalSurfaceIds contains only TRANSITIONAL and RETIREMENT_GATED IDs
 *  - canonicalSurfaceIds and transitionalSurfaceIds are disjoint
 *  - extendableSurfaceIds contains only EXTEND-guidance surface IDs
 *  - isRegistered returns true for known surface
 *  - isRegistered returns false for unknown surface
 *
 * ### StabilizationBaseline — system-level constants
 *  - BASELINE_PR is 11
 *  - BASELINE_DESCRIPTION is non-blank
 *  - canonicalOnlyWorkCategories is non-empty
 *  - extensionPoints is non-empty
 *  - convergenceTargets is non-empty
 *  - convergenceTargets size equals TRANSITIONAL + RETIREMENT_GATED count
 *
 * ### GalaxyLogger.TAG_STABILIZATION_BASELINE — stability
 *  - TAG_STABILIZATION_BASELINE value is "GALAXY:STABILIZATION:BASELINE"
 *  - TAG_STABILIZATION_BASELINE contains "STABILIZATION"
 *  - TAG_STABILIZATION_BASELINE contains "BASELINE"
 *  - TAG_STABILIZATION_BASELINE is distinct from TAG_COMPAT_SURFACE
 *  - TAG_STABILIZATION_BASELINE is distinct from TAG_LONG_TAIL_COMPAT
 *  - TAG_STABILIZATION_BASELINE is distinct from TAG_INTERACTION_ACCEPTANCE
 */
class Pr11StabilizationBaselineTest {

    // ══════════════════════════════════════════════════════════════════════════
    // 1. SurfaceStability and ExtensionGuidance enum coverage
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `SurfaceStability has CANONICAL_STABLE value`() {
        val values = StabilizationBaseline.SurfaceStability.values().map { it.name }
        assertTrue(values.contains("CANONICAL_STABLE"))
    }

    @Test fun `SurfaceStability has CANONICAL_FROZEN value`() {
        val values = StabilizationBaseline.SurfaceStability.values().map { it.name }
        assertTrue(values.contains("CANONICAL_FROZEN"))
    }

    @Test fun `SurfaceStability has TRANSITIONAL value`() {
        val values = StabilizationBaseline.SurfaceStability.values().map { it.name }
        assertTrue(values.contains("TRANSITIONAL"))
    }

    @Test fun `SurfaceStability has RETIREMENT_GATED value`() {
        val values = StabilizationBaseline.SurfaceStability.values().map { it.name }
        assertTrue(values.contains("RETIREMENT_GATED"))
    }

    @Test fun `ExtensionGuidance has EXTEND value`() {
        val values = StabilizationBaseline.ExtensionGuidance.values().map { it.name }
        assertTrue(values.contains("EXTEND"))
    }

    @Test fun `ExtensionGuidance has CONVERGE value`() {
        val values = StabilizationBaseline.ExtensionGuidance.values().map { it.name }
        assertTrue(values.contains("CONVERGE"))
    }

    @Test fun `ExtensionGuidance has WRAP_ONLY value`() {
        val values = StabilizationBaseline.ExtensionGuidance.values().map { it.name }
        assertTrue(values.contains("WRAP_ONLY"))
    }

    @Test fun `ExtensionGuidance has NO_NEW_WORK value`() {
        val values = StabilizationBaseline.ExtensionGuidance.values().map { it.name }
        assertTrue(values.contains("NO_NEW_WORK"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. Canonical stable lifecycle truth surfaces
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `runtime-controller-state is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("runtime-controller-state")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    @Test fun `runtime-controller-host-session-snapshot is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("runtime-controller-host-session-snapshot")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    @Test fun `runtime-controller-target-readiness-projection is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("runtime-controller-target-readiness-projection")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    @Test fun `runtime-controller-reconnect-recovery-state is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("runtime-controller-reconnect-recovery-state")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. Canonical stable dispatch governance surfaces
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `canonical-dispatch-chain is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("canonical-dispatch-chain")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    @Test fun `rollout-control-snapshot is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("rollout-control-snapshot")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    @Test fun `execution-route-tag is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("execution-route-tag")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. Canonical stable signal surfaces
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `delegated-execution-signal is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("delegated-execution-signal")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    @Test fun `emitted-signal-ledger is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("emitted-signal-ledger")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. Canonical stable session identity surfaces
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `canonical-session-axis is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("canonical-session-axis")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    @Test fun `attached-runtime-host-session-snapshot is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("attached-runtime-host-session-snapshot")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    @Test fun `runtime-identity-contracts is registered and CANONICAL_FROZEN with WRAP_ONLY guidance`() {
        val e = StabilizationBaseline.forId("runtime-identity-contracts")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_FROZEN, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.WRAP_ONLY, e.extensionGuidance)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 6. Canonical stable projection model surfaces
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `canonical-participant-model is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("canonical-participant-model")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    @Test fun `canonical-device-model is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("canonical-device-model")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    @Test fun `canonical-capability-provider-model is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("canonical-capability-provider-model")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 7. Canonical stable failure classification surfaces
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `cross-device-setup-error is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("cross-device-setup-error")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    @Test fun `takeover-fallback-event is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("takeover-fallback-event")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 8. Canonical stable compatibility inventory surfaces
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `compatibility-surface-retirement-registry is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("compatibility-surface-retirement-registry")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    @Test fun `long-tail-compatibility-registry is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("long-tail-compatibility-registry")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 9. Canonical stable protocol alignment surfaces
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `ugcp-shared-schema-alignment is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("ugcp-shared-schema-alignment")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    @Test fun `ugcp-protocol-consistency-rules is registered and CANONICAL_STABLE with EXTEND guidance`() {
        val e = StabilizationBaseline.forId("ugcp-protocol-consistency-rules")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 10. Transitional surfaces
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `legacy-registration-error-bridge is TRANSITIONAL with CONVERGE guidance`() {
        val e = StabilizationBaseline.forId("legacy-registration-error-bridge")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.TRANSITIONAL, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.CONVERGE, e.extensionGuidance)
    }

    @Test fun `legacy-registration-error-bridge has non-null canonicalReplacement`() {
        val e = StabilizationBaseline.forId("legacy-registration-error-bridge")
        assertNotNull(e!!.canonicalReplacement)
    }

    @Test fun `legacy-current-session-snapshot-map is TRANSITIONAL with CONVERGE guidance`() {
        val e = StabilizationBaseline.forId("legacy-current-session-snapshot-map")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.TRANSITIONAL, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.CONVERGE, e.extensionGuidance)
    }

    @Test fun `legacy-current-session-snapshot-map has non-null canonicalReplacement`() {
        val e = StabilizationBaseline.forId("legacy-current-session-snapshot-map")
        assertNotNull(e!!.canonicalReplacement)
    }

    @Test fun `ugcp-legacy-wire-value-aliases is TRANSITIONAL with CONVERGE guidance`() {
        val e = StabilizationBaseline.forId("ugcp-legacy-wire-value-aliases")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.TRANSITIONAL, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.CONVERGE, e.extensionGuidance)
    }

    @Test fun `ugcp-legacy-wire-value-aliases has non-null canonicalReplacement`() {
        val e = StabilizationBaseline.forId("ugcp-legacy-wire-value-aliases")
        assertNotNull(e!!.canonicalReplacement)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 11. Retirement-gated surfaces
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `long-tail-relay-forward-reply is RETIREMENT_GATED with NO_NEW_WORK guidance`() {
        val e = StabilizationBaseline.forId("long-tail-relay-forward-reply")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.RETIREMENT_GATED, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.NO_NEW_WORK, e.extensionGuidance)
    }

    @Test fun `long-tail-relay-forward-reply has non-null retirementGate`() {
        val e = StabilizationBaseline.forId("long-tail-relay-forward-reply")
        assertNotNull(e!!.retirementGate)
    }

    @Test fun `protocol-surface-transitional-compatibility is RETIREMENT_GATED with NO_NEW_WORK guidance`() {
        val e = StabilizationBaseline.forId("protocol-surface-transitional-compatibility")
        assertNotNull(e)
        assertEquals(StabilizationBaseline.SurfaceStability.RETIREMENT_GATED, e!!.stability)
        assertEquals(StabilizationBaseline.ExtensionGuidance.NO_NEW_WORK, e.extensionGuidance)
    }

    @Test fun `protocol-surface-transitional-compatibility has non-null retirementGate`() {
        val e = StabilizationBaseline.forId("protocol-surface-transitional-compatibility")
        assertNotNull(e!!.retirementGate)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 12. Tier and guidance counts
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `CANONICAL_STABLE count is 109`() {
        assertEquals(109, StabilizationBaseline.byStability(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE).size)
    }

    @Test fun `CANONICAL_FROZEN count is 1`() {
        assertEquals(1, StabilizationBaseline.byStability(StabilizationBaseline.SurfaceStability.CANONICAL_FROZEN).size)
    }

    @Test fun `TRANSITIONAL count is 2`() {
        assertEquals(2, StabilizationBaseline.byStability(StabilizationBaseline.SurfaceStability.TRANSITIONAL).size)
    }

    @Test fun `RETIREMENT_GATED count is 3`() {
        assertEquals(3, StabilizationBaseline.byStability(StabilizationBaseline.SurfaceStability.RETIREMENT_GATED).size)
    }

    @Test fun `total entry count is 115`() {
        assertEquals(115, StabilizationBaseline.entries.size)
    }

    @Test fun `EXTEND guidance count is 109`() {
        assertEquals(109, StabilizationBaseline.byGuidance(StabilizationBaseline.ExtensionGuidance.EXTEND).size)
    }

    @Test fun `CONVERGE guidance count is 3`() {
        assertEquals(3, StabilizationBaseline.byGuidance(StabilizationBaseline.ExtensionGuidance.CONVERGE).size)
    }

    @Test fun `WRAP_ONLY guidance count is 1`() {
        assertEquals(1, StabilizationBaseline.byGuidance(StabilizationBaseline.ExtensionGuidance.WRAP_ONLY).size)
    }

    @Test fun `NO_NEW_WORK guidance count is 2`() {
        assertEquals(2, StabilizationBaseline.byGuidance(StabilizationBaseline.ExtensionGuidance.NO_NEW_WORK).size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 13. Data integrity
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `all entries have non-blank surfaceId`() {
        StabilizationBaseline.entries.forEach { e ->
            assertTrue("surfaceId blank: ${e.surfaceId}", e.surfaceId.isNotBlank())
        }
    }

    @Test fun `all entries have non-blank displayName`() {
        StabilizationBaseline.entries.forEach { e ->
            assertTrue("displayName blank for ${e.surfaceId}", e.displayName.isNotBlank())
        }
    }

    @Test fun `all entries have non-blank packagePath`() {
        StabilizationBaseline.entries.forEach { e ->
            assertTrue("packagePath blank for ${e.surfaceId}", e.packagePath.isNotBlank())
        }
    }

    @Test fun `all entries have non-blank rationale`() {
        StabilizationBaseline.entries.forEach { e ->
            assertTrue("rationale blank for ${e.surfaceId}", e.rationale.isNotBlank())
        }
    }

    @Test fun `all surfaceIds are unique`() {
        val ids = StabilizationBaseline.entries.map { it.surfaceId }
        assertEquals("duplicate surfaceIds found", ids.size, ids.toSet().size)
    }

    @Test fun `all TRANSITIONAL entries have non-null canonicalReplacement`() {
        StabilizationBaseline.byStability(StabilizationBaseline.SurfaceStability.TRANSITIONAL).forEach { e ->
            assertNotNull("canonicalReplacement null for TRANSITIONAL entry: ${e.surfaceId}", e.canonicalReplacement)
        }
    }

    @Test fun `all RETIREMENT_GATED entries have non-null retirementGate`() {
        StabilizationBaseline.byStability(StabilizationBaseline.SurfaceStability.RETIREMENT_GATED).forEach { e ->
            assertNotNull("retirementGate null for RETIREMENT_GATED entry: ${e.surfaceId}", e.retirementGate)
        }
    }

    @Test fun `CANONICAL_STABLE entries do not have canonicalReplacement`() {
        StabilizationBaseline.byStability(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE).forEach { e ->
            assertNull("canonicalReplacement non-null for CANONICAL_STABLE entry: ${e.surfaceId}", e.canonicalReplacement)
        }
    }

    @Test fun `CANONICAL_FROZEN entries do not have canonicalReplacement`() {
        StabilizationBaseline.byStability(StabilizationBaseline.SurfaceStability.CANONICAL_FROZEN).forEach { e ->
            assertNull("canonicalReplacement non-null for CANONICAL_FROZEN entry: ${e.surfaceId}", e.canonicalReplacement)
        }
    }

    @Test fun `forId returns null for unregistered surface ID`() {
        assertNull(StabilizationBaseline.forId("not-a-real-surface-id"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 14. Query helper correctness
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `byStability CANONICAL_STABLE returns only CANONICAL_STABLE entries`() {
        val result = StabilizationBaseline.byStability(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE)
        assertTrue(result.isNotEmpty())
        result.forEach { e ->
            assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, e.stability)
        }
    }

    @Test fun `byStability TRANSITIONAL returns only TRANSITIONAL entries`() {
        val result = StabilizationBaseline.byStability(StabilizationBaseline.SurfaceStability.TRANSITIONAL)
        assertTrue(result.isNotEmpty())
        result.forEach { e ->
            assertEquals(StabilizationBaseline.SurfaceStability.TRANSITIONAL, e.stability)
        }
    }

    @Test fun `byGuidance EXTEND returns only EXTEND entries`() {
        val result = StabilizationBaseline.byGuidance(StabilizationBaseline.ExtensionGuidance.EXTEND)
        assertTrue(result.isNotEmpty())
        result.forEach { e ->
            assertEquals(StabilizationBaseline.ExtensionGuidance.EXTEND, e.extensionGuidance)
        }
    }

    @Test fun `byGuidance NO_NEW_WORK returns only NO_NEW_WORK entries`() {
        val result = StabilizationBaseline.byGuidance(StabilizationBaseline.ExtensionGuidance.NO_NEW_WORK)
        assertTrue(result.isNotEmpty())
        result.forEach { e ->
            assertEquals(StabilizationBaseline.ExtensionGuidance.NO_NEW_WORK, e.extensionGuidance)
        }
    }

    @Test fun `canonicalSurfaceIds contains only CANONICAL_STABLE and CANONICAL_FROZEN IDs`() {
        val expected = StabilizationBaseline.entries
            .filter {
                it.stability == StabilizationBaseline.SurfaceStability.CANONICAL_STABLE ||
                    it.stability == StabilizationBaseline.SurfaceStability.CANONICAL_FROZEN
            }
            .map { it.surfaceId }
            .toSet()
        assertEquals(expected, StabilizationBaseline.canonicalSurfaceIds)
    }

    @Test fun `transitionalSurfaceIds contains only TRANSITIONAL and RETIREMENT_GATED IDs`() {
        val expected = StabilizationBaseline.entries
            .filter {
                it.stability == StabilizationBaseline.SurfaceStability.TRANSITIONAL ||
                    it.stability == StabilizationBaseline.SurfaceStability.RETIREMENT_GATED
            }
            .map { it.surfaceId }
            .toSet()
        assertEquals(expected, StabilizationBaseline.transitionalSurfaceIds)
    }

    @Test fun `canonicalSurfaceIds and transitionalSurfaceIds are disjoint`() {
        val intersection = StabilizationBaseline.canonicalSurfaceIds.intersect(
            StabilizationBaseline.transitionalSurfaceIds
        )
        assertTrue("canonical and transitional sets overlap: $intersection", intersection.isEmpty())
    }

    @Test fun `extendableSurfaceIds contains only EXTEND-guidance surface IDs`() {
        val expected = StabilizationBaseline.entries
            .filter { it.extensionGuidance == StabilizationBaseline.ExtensionGuidance.EXTEND }
            .map { it.surfaceId }
            .toSet()
        assertEquals(expected, StabilizationBaseline.extendableSurfaceIds)
    }

    @Test fun `isRegistered returns true for known surface`() {
        assertTrue(StabilizationBaseline.isRegistered("canonical-dispatch-chain"))
    }

    @Test fun `isRegistered returns false for unknown surface`() {
        assertFalse(StabilizationBaseline.isRegistered("not-a-real-surface-id"))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 15. System-level constants
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `BASELINE_PR is 11`() {
        assertEquals(11, StabilizationBaseline.BASELINE_PR)
    }

    @Test fun `BASELINE_DESCRIPTION is non-blank`() {
        assertTrue(StabilizationBaseline.BASELINE_DESCRIPTION.isNotBlank())
    }

    @Test fun `canonicalOnlyWorkCategories is non-empty`() {
        assertTrue(StabilizationBaseline.canonicalOnlyWorkCategories.isNotEmpty())
    }

    @Test fun `extensionPoints is non-empty`() {
        assertTrue(StabilizationBaseline.extensionPoints.isNotEmpty())
    }

    @Test fun `convergenceTargets is non-empty`() {
        assertTrue(StabilizationBaseline.convergenceTargets.isNotEmpty())
    }

    @Test fun `convergenceTargets size equals TRANSITIONAL plus RETIREMENT_GATED count`() {
        val transitionalCount = StabilizationBaseline.byStability(StabilizationBaseline.SurfaceStability.TRANSITIONAL).size
        val retirementGatedCount = StabilizationBaseline.byStability(StabilizationBaseline.SurfaceStability.RETIREMENT_GATED).size
        assertEquals(transitionalCount + retirementGatedCount, StabilizationBaseline.convergenceTargets.size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 16. GalaxyLogger.TAG_STABILIZATION_BASELINE — stability
    // ══════════════════════════════════════════════════════════════════════════

    @Test fun `TAG_STABILIZATION_BASELINE value is GALAXY:STABILIZATION:BASELINE`() {
        assertEquals("GALAXY:STABILIZATION:BASELINE", GalaxyLogger.TAG_STABILIZATION_BASELINE)
    }

    @Test fun `TAG_STABILIZATION_BASELINE contains STABILIZATION`() {
        assertTrue(GalaxyLogger.TAG_STABILIZATION_BASELINE.contains("STABILIZATION"))
    }

    @Test fun `TAG_STABILIZATION_BASELINE contains BASELINE`() {
        assertTrue(GalaxyLogger.TAG_STABILIZATION_BASELINE.contains("BASELINE"))
    }

    @Test fun `TAG_STABILIZATION_BASELINE is distinct from TAG_COMPAT_SURFACE`() {
        assertFalse(GalaxyLogger.TAG_STABILIZATION_BASELINE == GalaxyLogger.TAG_COMPAT_SURFACE)
    }

    @Test fun `TAG_STABILIZATION_BASELINE is distinct from TAG_LONG_TAIL_COMPAT`() {
        assertFalse(GalaxyLogger.TAG_STABILIZATION_BASELINE == GalaxyLogger.TAG_LONG_TAIL_COMPAT)
    }

    @Test fun `TAG_STABILIZATION_BASELINE is distinct from TAG_INTERACTION_ACCEPTANCE`() {
        assertFalse(GalaxyLogger.TAG_STABILIZATION_BASELINE == GalaxyLogger.TAG_INTERACTION_ACCEPTANCE)
    }
}
