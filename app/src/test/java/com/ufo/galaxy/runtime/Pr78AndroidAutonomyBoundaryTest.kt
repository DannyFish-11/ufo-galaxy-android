package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-78 (Android) — Unit tests for [AndroidAutonomyBoundary].
 *
 * Validates all acceptance criteria for the Android-side autonomy boundary declaration:
 *
 *  1. **Autonomy modes** — LOCAL_AUTONOMOUS and V2_GOVERNED are structurally correct and
 *     have distinct, stable wire values.
 *  2. **Four local autonomy operations** — LOCAL_PLANNING, LOCAL_GROUNDING, LOCAL_ADVICE,
 *     LOCAL_EXECUTION have correct wire values, autonomousInLocalMode, alwaysAndroidOwned,
 *     and v2GovernedSemantics fields.
 *  3. **ALWAYS_ANDROID_OWNED** — exactly LOCAL_GROUNDING; grounding is always Android-owned.
 *  4. **AUTONOMOUS_IN_LOCAL_MODE** — all four operations; local mode has no V2 dependency.
 *  5. **isAutonomous()** — correct for all operation × mode combinations.
 *  6. **buildAutonomyWireMap()** — schema invariants and mandatory keys.
 *  7. **AUTONOMY_INVARIANTS** — all values are `true`.
 *
 * ## Test matrix
 *
 * ### INTRODUCED_PR
 *  - INTRODUCED_PR is 78
 *
 * ### AutonomyMode — enum values
 *  - LOCAL_AUTONOMOUS wireValue is "local_autonomous"
 *  - V2_GOVERNED wireValue is "v2_governed"
 *  - LOCAL_AUTONOMOUS v2Required is false
 *  - V2_GOVERNED v2Required is true
 *  - both AutonomyMode wireValues are distinct
 *  - ALL_WIRE_VALUES has exactly two entries
 *  - fromWireValue returns LOCAL_AUTONOMOUS for "local_autonomous"
 *  - fromWireValue returns V2_GOVERNED for "v2_governed"
 *  - fromWireValue returns null for unknown value
 *  - all entries have non-blank displayName and description
 *
 * ### LocalAutonomyOperation — wire values
 *  - LOCAL_PLANNING wireValue is "local_planning"
 *  - LOCAL_GROUNDING wireValue is "local_grounding"
 *  - LOCAL_ADVICE wireValue is "local_advice"
 *  - LOCAL_EXECUTION wireValue is "local_execution"
 *  - all four wireValues are distinct
 *  - ALL_WIRE_VALUES has exactly four entries
 *  - fromWireValue round-trips all four wireValues correctly
 *  - fromWireValue returns null for unknown value
 *  - all entries have non-blank displayName and description
 *  - all entries have non-blank v2GovernedSemantics
 *
 * ### LocalAutonomyOperation — autonomy flags
 *  - all four operations have autonomousInLocalMode = true
 *  - only LOCAL_GROUNDING has alwaysAndroidOwned = true
 *  - LOCAL_PLANNING alwaysAndroidOwned is false
 *  - LOCAL_ADVICE alwaysAndroidOwned is false
 *  - LOCAL_EXECUTION alwaysAndroidOwned is false
 *
 * ### ALWAYS_ANDROID_OWNED
 *  - ALWAYS_ANDROID_OWNED contains exactly LOCAL_GROUNDING
 *  - ALWAYS_ANDROID_OWNED does not contain LOCAL_PLANNING
 *  - ALWAYS_ANDROID_OWNED does not contain LOCAL_ADVICE
 *  - ALWAYS_ANDROID_OWNED does not contain LOCAL_EXECUTION
 *
 * ### AUTONOMOUS_IN_LOCAL_MODE
 *  - AUTONOMOUS_IN_LOCAL_MODE contains all four operations
 *
 * ### isAutonomous() — LOCAL_AUTONOMOUS mode
 *  - LOCAL_PLANNING is autonomous in LOCAL_AUTONOMOUS
 *  - LOCAL_GROUNDING is autonomous in LOCAL_AUTONOMOUS
 *  - LOCAL_ADVICE is autonomous in LOCAL_AUTONOMOUS
 *  - LOCAL_EXECUTION is autonomous in LOCAL_AUTONOMOUS
 *
 * ### isAutonomous() — V2_GOVERNED mode
 *  - LOCAL_GROUNDING is autonomous in V2_GOVERNED (always Android-owned)
 *  - LOCAL_PLANNING is NOT autonomous in V2_GOVERNED
 *  - LOCAL_ADVICE is NOT autonomous in V2_GOVERNED
 *  - LOCAL_EXECUTION is NOT autonomous in V2_GOVERNED
 *
 * ### buildAutonomyWireMap()
 *  - introduced_pr is 78
 *  - schema_version is "1.0"
 *  - autonomy_modes is present and has two entries
 *  - local_autonomy_operations is present and has four entries
 *  - always_android_owned_operations contains "local_grounding" only
 *  - all_operations_autonomous_in_local_mode is true
 *  - grounding_always_android_owned is true
 *  - local_mode_has_no_v2_dependency is true
 *  - v2_governed_mode_android_owns_per_step_execution is true
 *  - v2_governed_mode_v2_owns_dispatch_and_result_interpretation is true
 *
 * ### AUTONOMY_INVARIANTS
 *  - all invariant values are true
 *  - local_mode_android_may_plan_without_v2 is true
 *  - local_mode_android_may_ground_without_v2 is true
 *  - local_mode_android_may_advise_without_v2 is true
 *  - local_mode_android_may_execute_without_v2 is true
 *  - grounding_is_always_android_owned_in_both_modes is true
 *  - v2_governed_mode_does_not_remove_android_per_step_execution_autonomy is true
 *  - v2_governed_mode_v2_owns_dispatch_slot_selection is true
 *  - v2_governed_mode_v2_owns_result_interpretation is true
 *  - autonomy_boundary_is_orthogonal_to_authority_boundary is true
 */
class Pr78AndroidAutonomyBoundaryTest {

    // ── INTRODUCED_PR ─────────────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is 78`() {
        assertEquals(78, AndroidAutonomyBoundary.INTRODUCED_PR)
    }

    // ── AutonomyMode — wire values ────────────────────────────────────────────

    @Test
    fun `LOCAL_AUTONOMOUS wireValue is local_autonomous`() {
        assertEquals("local_autonomous", AndroidAutonomyBoundary.AutonomyMode.LOCAL_AUTONOMOUS.wireValue)
    }

    @Test
    fun `V2_GOVERNED wireValue is v2_governed`() {
        assertEquals("v2_governed", AndroidAutonomyBoundary.AutonomyMode.V2_GOVERNED.wireValue)
    }

    @Test
    fun `LOCAL_AUTONOMOUS v2Required is false`() {
        assertFalse(AndroidAutonomyBoundary.AutonomyMode.LOCAL_AUTONOMOUS.v2Required)
    }

    @Test
    fun `V2_GOVERNED v2Required is true`() {
        assertTrue(AndroidAutonomyBoundary.AutonomyMode.V2_GOVERNED.v2Required)
    }

    @Test
    fun `all AutonomyMode wireValues are distinct`() {
        val wireValues = AndroidAutonomyBoundary.AutonomyMode.entries.map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    @Test
    fun `AutonomyMode ALL_WIRE_VALUES has exactly two entries`() {
        assertEquals(2, AndroidAutonomyBoundary.AutonomyMode.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `fromWireValue returns LOCAL_AUTONOMOUS for local_autonomous`() {
        assertEquals(
            AndroidAutonomyBoundary.AutonomyMode.LOCAL_AUTONOMOUS,
            AndroidAutonomyBoundary.AutonomyMode.fromWireValue("local_autonomous")
        )
    }

    @Test
    fun `fromWireValue returns V2_GOVERNED for v2_governed`() {
        assertEquals(
            AndroidAutonomyBoundary.AutonomyMode.V2_GOVERNED,
            AndroidAutonomyBoundary.AutonomyMode.fromWireValue("v2_governed")
        )
    }

    @Test
    fun `AutonomyMode fromWireValue returns null for unknown value`() {
        assertNull(AndroidAutonomyBoundary.AutonomyMode.fromWireValue("unknown_mode"))
    }

    @Test
    fun `all AutonomyMode entries have non-blank displayName and description`() {
        for (mode in AndroidAutonomyBoundary.AutonomyMode.entries) {
            assertTrue(
                "displayName blank for ${mode.name}",
                mode.displayName.isNotBlank()
            )
            assertTrue(
                "description blank for ${mode.name}",
                mode.description.isNotBlank()
            )
        }
    }

    // ── LocalAutonomyOperation — wire values ──────────────────────────────────

    @Test
    fun `LOCAL_PLANNING wireValue is local_planning`() {
        assertEquals(
            "local_planning",
            AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_PLANNING.wireValue
        )
    }

    @Test
    fun `LOCAL_GROUNDING wireValue is local_grounding`() {
        assertEquals(
            "local_grounding",
            AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_GROUNDING.wireValue
        )
    }

    @Test
    fun `LOCAL_ADVICE wireValue is local_advice`() {
        assertEquals(
            "local_advice",
            AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_ADVICE.wireValue
        )
    }

    @Test
    fun `LOCAL_EXECUTION wireValue is local_execution`() {
        assertEquals(
            "local_execution",
            AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_EXECUTION.wireValue
        )
    }

    @Test
    fun `all LocalAutonomyOperation wireValues are distinct`() {
        val wireValues = AndroidAutonomyBoundary.LocalAutonomyOperation.entries.map { it.wireValue }
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    @Test
    fun `LocalAutonomyOperation ALL_WIRE_VALUES has exactly four entries`() {
        assertEquals(4, AndroidAutonomyBoundary.LocalAutonomyOperation.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `LocalAutonomyOperation fromWireValue round-trips all four wireValues`() {
        for (op in AndroidAutonomyBoundary.LocalAutonomyOperation.entries) {
            assertEquals(
                op,
                AndroidAutonomyBoundary.LocalAutonomyOperation.fromWireValue(op.wireValue)
            )
        }
    }

    @Test
    fun `LocalAutonomyOperation fromWireValue returns null for unknown value`() {
        assertNull(
            AndroidAutonomyBoundary.LocalAutonomyOperation.fromWireValue("unknown_operation")
        )
    }

    @Test
    fun `all LocalAutonomyOperation entries have non-blank displayName and description`() {
        for (op in AndroidAutonomyBoundary.LocalAutonomyOperation.entries) {
            assertTrue("displayName blank for ${op.name}", op.displayName.isNotBlank())
            assertTrue("description blank for ${op.name}", op.description.isNotBlank())
        }
    }

    @Test
    fun `all LocalAutonomyOperation entries have non-blank v2GovernedSemantics`() {
        for (op in AndroidAutonomyBoundary.LocalAutonomyOperation.entries) {
            assertTrue(
                "v2GovernedSemantics blank for ${op.name}",
                op.v2GovernedSemantics.isNotBlank()
            )
        }
    }

    // ── LocalAutonomyOperation — autonomy flags ───────────────────────────────

    @Test
    fun `all four operations have autonomousInLocalMode true`() {
        for (op in AndroidAutonomyBoundary.LocalAutonomyOperation.entries) {
            assertTrue(
                "autonomousInLocalMode is false for ${op.name}",
                op.autonomousInLocalMode
            )
        }
    }

    @Test
    fun `only LOCAL_GROUNDING has alwaysAndroidOwned true`() {
        for (op in AndroidAutonomyBoundary.LocalAutonomyOperation.entries) {
            if (op == AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_GROUNDING) {
                assertTrue("LOCAL_GROUNDING.alwaysAndroidOwned should be true", op.alwaysAndroidOwned)
            } else {
                assertFalse("${op.name}.alwaysAndroidOwned should be false", op.alwaysAndroidOwned)
            }
        }
    }

    @Test
    fun `LOCAL_PLANNING alwaysAndroidOwned is false`() {
        assertFalse(AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_PLANNING.alwaysAndroidOwned)
    }

    @Test
    fun `LOCAL_ADVICE alwaysAndroidOwned is false`() {
        assertFalse(AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_ADVICE.alwaysAndroidOwned)
    }

    @Test
    fun `LOCAL_EXECUTION alwaysAndroidOwned is false`() {
        assertFalse(AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_EXECUTION.alwaysAndroidOwned)
    }

    // ── ALWAYS_ANDROID_OWNED ──────────────────────────────────────────────────

    @Test
    fun `ALWAYS_ANDROID_OWNED contains exactly LOCAL_GROUNDING`() {
        val owned = AndroidAutonomyBoundary.LocalAutonomyOperation.ALWAYS_ANDROID_OWNED
        assertEquals(setOf(AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_GROUNDING), owned)
    }

    @Test
    fun `ALWAYS_ANDROID_OWNED does not contain LOCAL_PLANNING`() {
        assertFalse(
            AndroidAutonomyBoundary.LocalAutonomyOperation.ALWAYS_ANDROID_OWNED.contains(
                AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_PLANNING
            )
        )
    }

    @Test
    fun `ALWAYS_ANDROID_OWNED does not contain LOCAL_ADVICE`() {
        assertFalse(
            AndroidAutonomyBoundary.LocalAutonomyOperation.ALWAYS_ANDROID_OWNED.contains(
                AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_ADVICE
            )
        )
    }

    @Test
    fun `ALWAYS_ANDROID_OWNED does not contain LOCAL_EXECUTION`() {
        assertFalse(
            AndroidAutonomyBoundary.LocalAutonomyOperation.ALWAYS_ANDROID_OWNED.contains(
                AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_EXECUTION
            )
        )
    }

    // ── AUTONOMOUS_IN_LOCAL_MODE ──────────────────────────────────────────────

    @Test
    fun `AUTONOMOUS_IN_LOCAL_MODE contains all four operations`() {
        val autonomous = AndroidAutonomyBoundary.LocalAutonomyOperation.AUTONOMOUS_IN_LOCAL_MODE
        assertEquals(AndroidAutonomyBoundary.LocalAutonomyOperation.entries.size, autonomous.size)
        for (op in AndroidAutonomyBoundary.LocalAutonomyOperation.entries) {
            assertTrue("${op.name} missing from AUTONOMOUS_IN_LOCAL_MODE", autonomous.contains(op))
        }
    }

    // ── isAutonomous() — LOCAL_AUTONOMOUS mode ────────────────────────────────

    @Test
    fun `LOCAL_PLANNING is autonomous in LOCAL_AUTONOMOUS`() {
        assertTrue(
            AndroidAutonomyBoundary.isAutonomous(
                AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_PLANNING,
                AndroidAutonomyBoundary.AutonomyMode.LOCAL_AUTONOMOUS
            )
        )
    }

    @Test
    fun `LOCAL_GROUNDING is autonomous in LOCAL_AUTONOMOUS`() {
        assertTrue(
            AndroidAutonomyBoundary.isAutonomous(
                AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_GROUNDING,
                AndroidAutonomyBoundary.AutonomyMode.LOCAL_AUTONOMOUS
            )
        )
    }

    @Test
    fun `LOCAL_ADVICE is autonomous in LOCAL_AUTONOMOUS`() {
        assertTrue(
            AndroidAutonomyBoundary.isAutonomous(
                AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_ADVICE,
                AndroidAutonomyBoundary.AutonomyMode.LOCAL_AUTONOMOUS
            )
        )
    }

    @Test
    fun `LOCAL_EXECUTION is autonomous in LOCAL_AUTONOMOUS`() {
        assertTrue(
            AndroidAutonomyBoundary.isAutonomous(
                AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_EXECUTION,
                AndroidAutonomyBoundary.AutonomyMode.LOCAL_AUTONOMOUS
            )
        )
    }

    // ── isAutonomous() — V2_GOVERNED mode ────────────────────────────────────

    @Test
    fun `LOCAL_GROUNDING is autonomous in V2_GOVERNED (always Android-owned)`() {
        assertTrue(
            AndroidAutonomyBoundary.isAutonomous(
                AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_GROUNDING,
                AndroidAutonomyBoundary.AutonomyMode.V2_GOVERNED
            )
        )
    }

    @Test
    fun `LOCAL_PLANNING is NOT autonomous in V2_GOVERNED`() {
        assertFalse(
            AndroidAutonomyBoundary.isAutonomous(
                AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_PLANNING,
                AndroidAutonomyBoundary.AutonomyMode.V2_GOVERNED
            )
        )
    }

    @Test
    fun `LOCAL_ADVICE is NOT autonomous in V2_GOVERNED`() {
        assertFalse(
            AndroidAutonomyBoundary.isAutonomous(
                AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_ADVICE,
                AndroidAutonomyBoundary.AutonomyMode.V2_GOVERNED
            )
        )
    }

    @Test
    fun `LOCAL_EXECUTION is NOT autonomous in V2_GOVERNED`() {
        assertFalse(
            AndroidAutonomyBoundary.isAutonomous(
                AndroidAutonomyBoundary.LocalAutonomyOperation.LOCAL_EXECUTION,
                AndroidAutonomyBoundary.AutonomyMode.V2_GOVERNED
            )
        )
    }

    // ── buildAutonomyWireMap() ────────────────────────────────────────────────

    @Test
    fun `buildAutonomyWireMap introduced_pr is 78`() {
        val map = AndroidAutonomyBoundary.buildAutonomyWireMap()
        assertEquals(78, map["introduced_pr"])
    }

    @Test
    fun `buildAutonomyWireMap schema_version is 1_0`() {
        val map = AndroidAutonomyBoundary.buildAutonomyWireMap()
        assertEquals("1.0", map["schema_version"])
    }

    @Test
    fun `buildAutonomyWireMap autonomy_modes is present and has two entries`() {
        val map = AndroidAutonomyBoundary.buildAutonomyWireMap()
        val modes = map["autonomy_modes"]
        assertNotNull(modes)
        @Suppress("UNCHECKED_CAST")
        val list = modes as List<String>
        assertEquals(2, list.size)
    }

    @Test
    fun `buildAutonomyWireMap local_autonomy_operations is present and has four entries`() {
        val map = AndroidAutonomyBoundary.buildAutonomyWireMap()
        val ops = map["local_autonomy_operations"]
        assertNotNull(ops)
        @Suppress("UNCHECKED_CAST")
        val list = ops as List<String>
        assertEquals(4, list.size)
    }

    @Test
    fun `buildAutonomyWireMap always_android_owned_operations contains local_grounding only`() {
        val map = AndroidAutonomyBoundary.buildAutonomyWireMap()
        @Suppress("UNCHECKED_CAST")
        val owned = map["always_android_owned_operations"] as List<String>
        assertEquals(listOf("local_grounding"), owned)
    }

    @Test
    fun `buildAutonomyWireMap all_operations_autonomous_in_local_mode is true`() {
        val map = AndroidAutonomyBoundary.buildAutonomyWireMap()
        assertEquals(true, map["all_operations_autonomous_in_local_mode"])
    }

    @Test
    fun `buildAutonomyWireMap grounding_always_android_owned is true`() {
        val map = AndroidAutonomyBoundary.buildAutonomyWireMap()
        assertEquals(true, map["grounding_always_android_owned"])
    }

    @Test
    fun `buildAutonomyWireMap local_mode_has_no_v2_dependency is true`() {
        val map = AndroidAutonomyBoundary.buildAutonomyWireMap()
        assertEquals(true, map["local_mode_has_no_v2_dependency"])
    }

    @Test
    fun `buildAutonomyWireMap v2_governed_mode_android_owns_per_step_execution is true`() {
        val map = AndroidAutonomyBoundary.buildAutonomyWireMap()
        assertEquals(true, map["v2_governed_mode_android_owns_per_step_execution"])
    }

    @Test
    fun `buildAutonomyWireMap v2_governed_mode_v2_owns_dispatch_and_result_interpretation is true`() {
        val map = AndroidAutonomyBoundary.buildAutonomyWireMap()
        assertEquals(true, map["v2_governed_mode_v2_owns_dispatch_and_result_interpretation"])
    }

    // ── AUTONOMY_INVARIANTS ───────────────────────────────────────────────────

    @Test
    fun `all AUTONOMY_INVARIANTS values are true`() {
        for ((key, value) in AndroidAutonomyBoundary.AUTONOMY_INVARIANTS) {
            assertTrue("invariant '$key' is not true", value)
        }
    }

    @Test
    fun `local_mode_android_may_plan_without_v2 is true`() {
        assertEquals(true, AndroidAutonomyBoundary.AUTONOMY_INVARIANTS["local_mode_android_may_plan_without_v2"])
    }

    @Test
    fun `local_mode_android_may_ground_without_v2 is true`() {
        assertEquals(true, AndroidAutonomyBoundary.AUTONOMY_INVARIANTS["local_mode_android_may_ground_without_v2"])
    }

    @Test
    fun `local_mode_android_may_advise_without_v2 is true`() {
        assertEquals(true, AndroidAutonomyBoundary.AUTONOMY_INVARIANTS["local_mode_android_may_advise_without_v2"])
    }

    @Test
    fun `local_mode_android_may_execute_without_v2 is true`() {
        assertEquals(true, AndroidAutonomyBoundary.AUTONOMY_INVARIANTS["local_mode_android_may_execute_without_v2"])
    }

    @Test
    fun `grounding_is_always_android_owned_in_both_modes is true`() {
        assertEquals(
            true,
            AndroidAutonomyBoundary.AUTONOMY_INVARIANTS["grounding_is_always_android_owned_in_both_modes"]
        )
    }

    @Test
    fun `v2_governed_mode_does_not_remove_android_per_step_execution_autonomy is true`() {
        assertEquals(
            true,
            AndroidAutonomyBoundary.AUTONOMY_INVARIANTS[
                "v2_governed_mode_does_not_remove_android_per_step_execution_autonomy"
            ]
        )
    }

    @Test
    fun `v2_governed_mode_v2_owns_dispatch_slot_selection is true`() {
        assertEquals(
            true,
            AndroidAutonomyBoundary.AUTONOMY_INVARIANTS["v2_governed_mode_v2_owns_dispatch_slot_selection"]
        )
    }

    @Test
    fun `v2_governed_mode_v2_owns_result_interpretation is true`() {
        assertEquals(
            true,
            AndroidAutonomyBoundary.AUTONOMY_INVARIANTS["v2_governed_mode_v2_owns_result_interpretation"]
        )
    }

    @Test
    fun `autonomy_boundary_is_orthogonal_to_authority_boundary is true`() {
        assertEquals(
            true,
            AndroidAutonomyBoundary.AUTONOMY_INVARIANTS["autonomy_boundary_is_orthogonal_to_authority_boundary"]
        )
    }
}
