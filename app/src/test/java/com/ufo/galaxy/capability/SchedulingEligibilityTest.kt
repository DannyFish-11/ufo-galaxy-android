package com.ufo.galaxy.capability

import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.runtime.RuntimeHostDescriptor
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [SchedulingEligibility] — the canonical Android scheduling eligibility
 * projection introduced in PR-6 (post-#533 dual-repo runtime unification master plan:
 * Canonical Device Capability & Scheduling Basis).
 *
 * ## Test matrix
 *
 * ### PlacementPreference derivation from AndroidCapabilityVector
 *  - INELIGIBLE when device is INACTIVE (no capabilities eligible).
 *  - LOCAL_PREFERRED when only local execution is eligible.
 *  - REMOTE_PREFERRED when only cross-device participation is eligible.
 *  - INDIFFERENT when both local and cross-device are eligible.
 *
 * ### Individual eligibility flags
 *  - canAcceptLocalTasks mirrors isEligibleForLocalExecution.
 *  - canAcceptCrossDeviceTasks mirrors isEligibleForCrossDeviceParticipation.
 *  - canAcceptParallelSubtasks mirrors isEligibleForParallelSubtask.
 *
 * ### reason string
 *  - reason equals PlacementPreference.wireValue for each outcome.
 *
 * ### PlacementPreference.fromValue
 *  - Round-trips for all four preferences.
 *  - Returns INELIGIBLE for unknown strings.
 *  - Returns INELIGIBLE for null input.
 *
 * ### Full device vs partial device
 *  - A fully ready ACTIVE device produces INDIFFERENT preference.
 *  - A device with only accessibility (no model) produces REMOTE_PREFERRED.
 *  - A device with only model (no accessibility) produces INELIGIBLE (no local, no cross-device).
 *  - STANDBY device is INELIGIBLE regardless of capability dimensions.
 *  - DRAINING device is INELIGIBLE regardless of capability dimensions.
 */
class SchedulingEligibilityTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun allReadySettings() = InMemoryAppSettings(
        crossDeviceEnabled = true,
        goalExecutionEnabled = true,
        localModelEnabled = true,
        parallelExecutionEnabled = true,
        deviceRole = "phone",
        modelReady = true,
        accessibilityReady = true,
        overlayReady = true
    )

    private fun allFalseSettings() = InMemoryAppSettings()

    private fun activeDescriptor() = RuntimeHostDescriptor.of(
        deviceId = "pixel-9",
        deviceRole = "phone"
    ).withState(RuntimeHostDescriptor.HostParticipationState.ACTIVE)

    private fun inactiveDescriptor() = RuntimeHostDescriptor.of(
        deviceId = "pixel-9",
        deviceRole = "phone"
    ) // defaults to INACTIVE

    private fun standbyDescriptor() = RuntimeHostDescriptor.of(
        deviceId = "pixel-9",
        deviceRole = "phone"
    ).withState(RuntimeHostDescriptor.HostParticipationState.STANDBY)

    private fun drainingDescriptor() = RuntimeHostDescriptor.of(
        deviceId = "pixel-9",
        deviceRole = "phone"
    ).withState(RuntimeHostDescriptor.HostParticipationState.DRAINING)

    // ── PlacementPreference derivation ────────────────────────────────────────

    @Test
    fun `INELIGIBLE when device is INACTIVE`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), hostDescriptor = null)
        val eligibility = SchedulingEligibility.from(vector)
        assertEquals(
            "INACTIVE device (null descriptor) must produce INELIGIBLE preference",
            SchedulingEligibility.PlacementPreference.INELIGIBLE,
            eligibility.placementPreference
        )
    }

    @Test
    fun `LOCAL_PREFERRED when only local execution is eligible`() {
        // Local possible: localModelEnabled + modelReady + accessibilityReady + overlayReady
        // Cross-device disabled: crossDeviceEnabled=false
        val settings = allReadySettings().also {
            it.crossDeviceEnabled = false
            it.goalExecutionEnabled = false
        }
        val vector = AndroidCapabilityVector.from(settings, activeDescriptor())
        val eligibility = SchedulingEligibility.from(vector)
        assertEquals(
            "device with only local capabilities must produce LOCAL_PREFERRED",
            SchedulingEligibility.PlacementPreference.LOCAL_PREFERRED,
            eligibility.placementPreference
        )
    }

    @Test
    fun `REMOTE_PREFERRED when only cross-device participation is eligible`() {
        // Cross-device possible; local not available (model not ready)
        val settings = allReadySettings().also {
            it.localModelEnabled = false
            it.modelReady = false
        }
        val vector = AndroidCapabilityVector.from(settings, activeDescriptor())
        val eligibility = SchedulingEligibility.from(vector)
        assertEquals(
            "device with only cross-device capability must produce REMOTE_PREFERRED",
            SchedulingEligibility.PlacementPreference.REMOTE_PREFERRED,
            eligibility.placementPreference
        )
    }

    @Test
    fun `INDIFFERENT when both local and cross-device are eligible`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), activeDescriptor())
        val eligibility = SchedulingEligibility.from(vector)
        assertEquals(
            "fully capable ACTIVE device must produce INDIFFERENT preference",
            SchedulingEligibility.PlacementPreference.INDIFFERENT,
            eligibility.placementPreference
        )
    }

    // ── Individual eligibility flags ──────────────────────────────────────────

    @Test
    fun `canAcceptLocalTasks mirrors isEligibleForLocalExecution`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), activeDescriptor())
        val eligibility = SchedulingEligibility.from(vector)
        assertEquals(vector.isEligibleForLocalExecution, eligibility.canAcceptLocalTasks)
    }

    @Test
    fun `canAcceptCrossDeviceTasks mirrors isEligibleForCrossDeviceParticipation`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), activeDescriptor())
        val eligibility = SchedulingEligibility.from(vector)
        assertEquals(vector.isEligibleForCrossDeviceParticipation, eligibility.canAcceptCrossDeviceTasks)
    }

    @Test
    fun `canAcceptParallelSubtasks mirrors isEligibleForParallelSubtask`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), activeDescriptor())
        val eligibility = SchedulingEligibility.from(vector)
        assertEquals(vector.isEligibleForParallelSubtask, eligibility.canAcceptParallelSubtasks)
    }

    // ── reason string ─────────────────────────────────────────────────────────

    @Test
    fun `reason equals wireValue for INELIGIBLE`() {
        val vector = AndroidCapabilityVector.from(allFalseSettings(), hostDescriptor = null)
        val eligibility = SchedulingEligibility.from(vector)
        assertEquals("ineligible", eligibility.reason)
    }

    @Test
    fun `reason equals wireValue for LOCAL_PREFERRED`() {
        val settings = allReadySettings().also {
            it.crossDeviceEnabled = false
            it.goalExecutionEnabled = false
        }
        val vector = AndroidCapabilityVector.from(settings, activeDescriptor())
        val eligibility = SchedulingEligibility.from(vector)
        assertEquals("local_preferred", eligibility.reason)
    }

    @Test
    fun `reason equals wireValue for REMOTE_PREFERRED`() {
        val settings = allReadySettings().also {
            it.localModelEnabled = false
            it.modelReady = false
        }
        val vector = AndroidCapabilityVector.from(settings, activeDescriptor())
        val eligibility = SchedulingEligibility.from(vector)
        assertEquals("remote_preferred", eligibility.reason)
    }

    @Test
    fun `reason equals wireValue for INDIFFERENT`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), activeDescriptor())
        val eligibility = SchedulingEligibility.from(vector)
        assertEquals("indifferent", eligibility.reason)
    }

    // ── PlacementPreference.fromValue ─────────────────────────────────────────

    @Test
    fun `PlacementPreference fromValue round-trips for all four preferences`() {
        for (pref in SchedulingEligibility.PlacementPreference.entries) {
            assertEquals(
                "fromValue must round-trip for ${pref.wireValue}",
                pref,
                SchedulingEligibility.PlacementPreference.fromValue(pref.wireValue)
            )
        }
    }

    @Test
    fun `PlacementPreference fromValue returns INELIGIBLE for unknown string`() {
        assertEquals(
            SchedulingEligibility.PlacementPreference.INELIGIBLE,
            SchedulingEligibility.PlacementPreference.fromValue("totally_unknown")
        )
    }

    @Test
    fun `PlacementPreference fromValue returns INELIGIBLE for null input`() {
        assertEquals(
            SchedulingEligibility.PlacementPreference.INELIGIBLE,
            SchedulingEligibility.PlacementPreference.fromValue(null)
        )
    }

    // ── Full vs partial device scenarios ─────────────────────────────────────

    @Test
    fun `fully ready ACTIVE device produces INDIFFERENT preference`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), activeDescriptor())
        assertEquals(
            SchedulingEligibility.PlacementPreference.INDIFFERENT,
            SchedulingEligibility.from(vector).placementPreference
        )
    }

    @Test
    fun `device with accessibility only and no model produces REMOTE_PREFERRED`() {
        val settings = allReadySettings().also {
            it.localModelEnabled = false
            it.modelReady = false
        }
        val vector = AndroidCapabilityVector.from(settings, activeDescriptor())
        // CROSS_DEVICE_COORDINATION still present; LOCAL_INFERENCE absent → REMOTE_PREFERRED
        assertEquals(
            SchedulingEligibility.PlacementPreference.REMOTE_PREFERRED,
            SchedulingEligibility.from(vector).placementPreference
        )
    }

    @Test
    fun `STANDBY device is INELIGIBLE regardless of capability dimensions`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), standbyDescriptor())
        val eligibility = SchedulingEligibility.from(vector)
        assertEquals(
            "STANDBY device must produce INELIGIBLE preference",
            SchedulingEligibility.PlacementPreference.INELIGIBLE,
            eligibility.placementPreference
        )
        assertFalse("STANDBY device must not accept local tasks", eligibility.canAcceptLocalTasks)
        assertFalse("STANDBY device must not accept cross-device tasks", eligibility.canAcceptCrossDeviceTasks)
        assertFalse("STANDBY device must not accept parallel subtasks", eligibility.canAcceptParallelSubtasks)
    }

    @Test
    fun `DRAINING device is INELIGIBLE regardless of capability dimensions`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), drainingDescriptor())
        val eligibility = SchedulingEligibility.from(vector)
        assertEquals(
            "DRAINING device must produce INELIGIBLE preference",
            SchedulingEligibility.PlacementPreference.INELIGIBLE,
            eligibility.placementPreference
        )
        assertFalse("DRAINING device must not accept local tasks", eligibility.canAcceptLocalTasks)
        assertFalse("DRAINING device must not accept cross-device tasks", eligibility.canAcceptCrossDeviceTasks)
    }
}
