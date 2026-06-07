package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [SourceRuntimePosture] — canonical source-device participation posture
 * semantics aligned with the main repo's PR #533 contract.
 *
 * Test matrix:
 *  - Known values are returned unchanged by [SourceRuntimePosture.fromValue].
 *  - Unknown, blank, and null values are normalised to [SourceRuntimePosture.DEFAULT].
 *  - [SourceRuntimePosture.isJoinRuntime] / [isControlOnly] predicates are correct.
 *  - [SourceRuntimePosture.ALL_VALUES] contains exactly the two canonical values.
 *  - Default is [SourceRuntimePosture.CONTROL_ONLY] for backwards safety.
 */
class SourceRuntimePostureTest {

    // ── fromValue: known values ───────────────────────────────────────────────

    @Test
    fun `fromValue returns control_only for canonical string`() {
        assertEquals(
            SourceRuntimePosture.CONTROL_ONLY,
            SourceRuntimePosture.fromValue("control_only")
        )
    }

    @Test
    fun `fromValue returns join_runtime for canonical string`() {
        assertEquals(
            SourceRuntimePosture.JOIN_RUNTIME,
            SourceRuntimePosture.fromValue("join_runtime")
        )
    }

    // ── fromValue: safe defaults ──────────────────────────────────────────────

    @Test
    fun `fromValue returns default for null`() {
        assertEquals(
            SourceRuntimePosture.DEFAULT,
            SourceRuntimePosture.fromValue(null)
        )
    }

    @Test
    fun `fromValue returns default for blank string`() {
        assertEquals(
            SourceRuntimePosture.DEFAULT,
            SourceRuntimePosture.fromValue("")
        )
    }

    @Test
    fun `fromValue returns default for whitespace string`() {
        assertEquals(
            SourceRuntimePosture.DEFAULT,
            SourceRuntimePosture.fromValue("   ")
        )
    }

    @Test
    fun `fromValue returns default for unknown value`() {
        assertEquals(
            SourceRuntimePosture.DEFAULT,
            SourceRuntimePosture.fromValue("future_posture_value")
        )
    }

    @Test
    fun `fromValue returns default for mixed-case variant`() {
        // Wire format is strictly lower-snake-case; mixed case is not recognised.
        assertEquals(
            SourceRuntimePosture.DEFAULT,
            SourceRuntimePosture.fromValue("Control_Only")
        )
    }

    // ── DEFAULT is CONTROL_ONLY ───────────────────────────────────────────────

    @Test
    fun `DEFAULT equals CONTROL_ONLY for backwards safety`() {
        assertEquals(SourceRuntimePosture.CONTROL_ONLY, SourceRuntimePosture.DEFAULT)
    }

    // ── Predicate: isJoinRuntime ──────────────────────────────────────────────

    @Test
    fun `isJoinRuntime returns true for join_runtime`() {
        assertTrue(SourceRuntimePosture.isJoinRuntime(SourceRuntimePosture.JOIN_RUNTIME))
    }

    @Test
    fun `isJoinRuntime returns false for control_only`() {
        assertFalse(SourceRuntimePosture.isJoinRuntime(SourceRuntimePosture.CONTROL_ONLY))
    }

    @Test
    fun `isJoinRuntime returns false for null`() {
        assertFalse(SourceRuntimePosture.isJoinRuntime(null))
    }

    @Test
    fun `isJoinRuntime returns false for unknown value`() {
        assertFalse(SourceRuntimePosture.isJoinRuntime("something_else"))
    }

    // ── Predicate: isControlOnly ─────────────────────────────────────────────

    @Test
    fun `isControlOnly returns true for control_only`() {
        assertTrue(SourceRuntimePosture.isControlOnly(SourceRuntimePosture.CONTROL_ONLY))
    }

    @Test
    fun `isControlOnly returns false for join_runtime`() {
        assertFalse(SourceRuntimePosture.isControlOnly(SourceRuntimePosture.JOIN_RUNTIME))
    }

    @Test
    fun `isControlOnly returns true for null (safe default)`() {
        assertTrue(SourceRuntimePosture.isControlOnly(null))
    }

    @Test
    fun `isControlOnly returns true for unknown value (safe default)`() {
        assertTrue(SourceRuntimePosture.isControlOnly("legacy_value"))
    }

    // ── ALL_VALUES completeness ───────────────────────────────────────────────

    @Test
    fun `ALL_VALUES contains exactly control_only and join_runtime`() {
        assertEquals(
            setOf(SourceRuntimePosture.CONTROL_ONLY, SourceRuntimePosture.JOIN_RUNTIME),
            SourceRuntimePosture.ALL_VALUES
        )
    }

    @Test
    fun `fromValue result is always in ALL_VALUES`() {
        val inputs = listOf("control_only", "join_runtime", null, "", "unknown", "JOIN_RUNTIME")
        for (input in inputs) {
            val result = SourceRuntimePosture.fromValue(input)
            assertTrue(
                "fromValue($input) = '$result' must be in ALL_VALUES",
                result in SourceRuntimePosture.ALL_VALUES
            )
        }
    }
}
