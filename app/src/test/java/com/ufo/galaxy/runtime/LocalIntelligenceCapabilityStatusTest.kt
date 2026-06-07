package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [LocalIntelligenceCapabilityStatus].
 *
 * Validates that the status enum correctly maps every [LocalInferenceRuntimeManager.ManagerState]
 * and [RuntimeStartResult] variant to the right capability tier, and that wire-value
 * serialisation/parsing round-trips cleanly.
 */
class LocalIntelligenceCapabilityStatusTest {

    // ── Derivation from ManagerState ─────────────────────────────────────────

    @Test
    fun `Running state maps to ACTIVE`() {
        val snapshot = RuntimeHealthSnapshot(
            plannerHealth = RuntimeHealthSnapshot.ComponentHealth.HEALTHY,
            groundingHealth = RuntimeHealthSnapshot.ComponentHealth.HEALTHY
        )
        val status = LocalIntelligenceCapabilityStatus.from(
            LocalInferenceRuntimeManager.ManagerState.Running(snapshot)
        )
        assertEquals(LocalIntelligenceCapabilityStatus.ACTIVE, status)
    }

    @Test
    fun `Degraded state maps to DEGRADED`() {
        val snapshot = RuntimeHealthSnapshot(
            plannerHealth = RuntimeHealthSnapshot.ComponentHealth.UNHEALTHY,
            groundingHealth = RuntimeHealthSnapshot.ComponentHealth.HEALTHY
        )
        val status = LocalIntelligenceCapabilityStatus.from(
            LocalInferenceRuntimeManager.ManagerState.Degraded(snapshot, "planner unavailable")
        )
        assertEquals(LocalIntelligenceCapabilityStatus.DEGRADED, status)
    }

    @Test
    fun `Failed state maps to DISABLED`() {
        val status = LocalIntelligenceCapabilityStatus.from(
            LocalInferenceRuntimeManager.ManagerState.Failed("both runtimes down")
        )
        assertEquals(LocalIntelligenceCapabilityStatus.DISABLED, status)
    }

    @Test
    fun `Stopped state maps to DISABLED`() {
        val status = LocalIntelligenceCapabilityStatus.from(
            LocalInferenceRuntimeManager.ManagerState.Stopped
        )
        assertEquals(LocalIntelligenceCapabilityStatus.DISABLED, status)
    }

    @Test
    fun `Starting state maps to DISABLED`() {
        val status = LocalIntelligenceCapabilityStatus.from(
            LocalInferenceRuntimeManager.ManagerState.Starting
        )
        assertEquals(LocalIntelligenceCapabilityStatus.DISABLED, status)
    }

    @Test
    fun `SafeMode state maps to DISABLED`() {
        val status = LocalIntelligenceCapabilityStatus.from(
            LocalInferenceRuntimeManager.ManagerState.SafeMode
        )
        assertEquals(LocalIntelligenceCapabilityStatus.DISABLED, status)
    }

    // ── Derivation from RuntimeStartResult ───────────────────────────────────

    @Test
    fun `Success result maps to ACTIVE`() {
        assertEquals(
            LocalIntelligenceCapabilityStatus.ACTIVE,
            LocalIntelligenceCapabilityStatus.from(RuntimeStartResult.Success)
        )
    }

    @Test
    fun `Degraded result maps to DEGRADED`() {
        assertEquals(
            LocalIntelligenceCapabilityStatus.DEGRADED,
            LocalIntelligenceCapabilityStatus.from(RuntimeStartResult.Degraded("partial"))
        )
    }

    @Test
    fun `Failure result maps to DISABLED`() {
        assertEquals(
            LocalIntelligenceCapabilityStatus.DISABLED,
            LocalIntelligenceCapabilityStatus.from(
                RuntimeStartResult.Failure(RuntimeStartResult.StartStage.HEALTH_CHECK, "no server")
            )
        )
    }

    // ── Wire values ───────────────────────────────────────────────────────────

    @Test
    fun `all wire values are distinct lowercase strings`() {
        val wireValues = LocalIntelligenceCapabilityStatus.entries.map { it.wireValue }
        assertEquals("Wire values must be unique", wireValues.size, wireValues.distinct().size)
        wireValues.forEach { value ->
            assertEquals("Wire value must be lowercase: $value", value, value.lowercase())
        }
    }

    @Test
    fun `fromWireValue round-trips every status`() {
        for (status in LocalIntelligenceCapabilityStatus.entries) {
            val parsed = LocalIntelligenceCapabilityStatus.fromWireValue(status.wireValue)
            assertEquals("Round-trip failed for $status", status, parsed)
        }
    }

    @Test
    fun `fromWireValue null returns DISABLED`() {
        assertEquals(
            LocalIntelligenceCapabilityStatus.DISABLED,
            LocalIntelligenceCapabilityStatus.fromWireValue(null)
        )
    }

    @Test
    fun `fromWireValue empty string returns DISABLED`() {
        assertEquals(
            LocalIntelligenceCapabilityStatus.DISABLED,
            LocalIntelligenceCapabilityStatus.fromWireValue("")
        )
    }

    @Test
    fun `fromWireValue unknown value returns DISABLED`() {
        assertEquals(
            LocalIntelligenceCapabilityStatus.DISABLED,
            LocalIntelligenceCapabilityStatus.fromWireValue("not_a_known_status")
        )
    }

    // ── Consistency: capability honesty contract ──────────────────────────────

    @Test
    fun `only ACTIVE status indicates full local AI availability`() {
        val activeCapable = LocalIntelligenceCapabilityStatus.entries.filter {
            it == LocalIntelligenceCapabilityStatus.ACTIVE
        }
        assertEquals("Exactly one status should indicate full availability", 1, activeCapable.size)
    }

    @Test
    fun `DEGRADED and DISABLED statuses indicate limited or no availability`() {
        assertNotEquals(
            "DEGRADED must not equal ACTIVE",
            LocalIntelligenceCapabilityStatus.ACTIVE,
            LocalIntelligenceCapabilityStatus.DEGRADED
        )
        assertNotEquals(
            "DISABLED must not equal ACTIVE",
            LocalIntelligenceCapabilityStatus.ACTIVE,
            LocalIntelligenceCapabilityStatus.DISABLED
        )
        assertNotEquals(
            "DEGRADED must not equal DISABLED",
            LocalIntelligenceCapabilityStatus.DEGRADED,
            LocalIntelligenceCapabilityStatus.DISABLED
        )
    }

    @Test
    fun `ManagerState Running is the only state that produces ACTIVE`() {
        val allStates: List<LocalInferenceRuntimeManager.ManagerState> = listOf(
            LocalInferenceRuntimeManager.ManagerState.Stopped,
            LocalInferenceRuntimeManager.ManagerState.Starting,
            LocalInferenceRuntimeManager.ManagerState.SafeMode,
            LocalInferenceRuntimeManager.ManagerState.Failed("test"),
            LocalInferenceRuntimeManager.ManagerState.Degraded(RuntimeHealthSnapshot.unknown(), "partial"),
            LocalInferenceRuntimeManager.ManagerState.Running(
                RuntimeHealthSnapshot(
                    plannerHealth = RuntimeHealthSnapshot.ComponentHealth.HEALTHY,
                    groundingHealth = RuntimeHealthSnapshot.ComponentHealth.HEALTHY
                )
            )
        )
        val activeStates = allStates.filter {
            LocalIntelligenceCapabilityStatus.from(it) == LocalIntelligenceCapabilityStatus.ACTIVE
        }
        assertEquals("Only Running state should produce ACTIVE", 1, activeStates.size)
        assertTrue("Running state must produce ACTIVE",
            activeStates.first() is LocalInferenceRuntimeManager.ManagerState.Running)
    }
}
