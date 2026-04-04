package com.ufo.galaxy.capability

import com.ufo.galaxy.data.CapabilityReport
import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.runtime.RuntimeHostDescriptor
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [AndroidCapabilityVector] — the canonical Android capability
 * representation introduced in PR-6 (post-#533 dual-repo runtime unification master
 * plan: Canonical Device Capability & Scheduling Basis).
 *
 * ## Test matrix
 *
 * ### ExecutionDimension derivation from AppSettings
 *  - LOCAL_INFERENCE present when localModelEnabled + modelReady are both true.
 *  - LOCAL_INFERENCE absent when localModelEnabled is false.
 *  - LOCAL_INFERENCE absent when modelReady is false.
 *  - ACCESSIBILITY_EXECUTION present when accessibilityReady + overlayReady are true.
 *  - ACCESSIBILITY_EXECUTION absent when accessibilityReady is false.
 *  - ACCESSIBILITY_EXECUTION absent when overlayReady is false.
 *  - PARALLEL_SUBTASK present when parallelExecutionEnabled is true.
 *  - PARALLEL_SUBTASK absent when parallelExecutionEnabled is false.
 *  - CROSS_DEVICE_COORDINATION present when crossDeviceEnabled + goalExecutionEnabled are true.
 *  - CROSS_DEVICE_COORDINATION absent when crossDeviceEnabled is false.
 *  - CROSS_DEVICE_COORDINATION absent when goalExecutionEnabled is false.
 *  - All four dimensions present for a fully-ready device.
 *  - Empty dimension set for an all-false settings.
 *
 * ### Formation role and participation state from RuntimeHostDescriptor
 *  - formationRole taken from hostDescriptor when provided.
 *  - participationState taken from hostDescriptor when provided.
 *  - formationRole defaults to PRIMARY when hostDescriptor is null.
 *  - participationState defaults to INACTIVE when hostDescriptor is null.
 *  - hostId taken from hostDescriptor when provided.
 *  - hostId is empty string when hostDescriptor is null.
 *
 * ### Derived scheduling eligibility flags
 *  - isEligibleForLocalExecution true when ACTIVE + LOCAL_INFERENCE + ACCESSIBILITY_EXECUTION.
 *  - isEligibleForLocalExecution false when INACTIVE even with all dimensions present.
 *  - isEligibleForLocalExecution false when LOCAL_INFERENCE missing.
 *  - isEligibleForLocalExecution false when ACCESSIBILITY_EXECUTION missing.
 *  - isEligibleForCrossDeviceParticipation true when ACTIVE + CROSS_DEVICE_COORDINATION.
 *  - isEligibleForCrossDeviceParticipation false when INACTIVE.
 *  - isEligibleForCrossDeviceParticipation false when CROSS_DEVICE_COORDINATION missing.
 *  - isEligibleForParallelSubtask true when ACTIVE + PARALLEL_SUBTASK.
 *  - isEligibleForParallelSubtask false when INACTIVE.
 *  - isPrimaryHost true when formationRole is PRIMARY.
 *  - isPrimaryHost false when formationRole is SECONDARY.
 *
 * ### Wire format (toSchedulingMetadata)
 *  - Returns exactly the four scheduling-basis keys.
 *  - scheduling_local_eligible reflects isEligibleForLocalExecution.
 *  - scheduling_cross_device_eligible reflects isEligibleForCrossDeviceParticipation.
 *  - scheduling_parallel_subtask_eligible reflects isEligibleForParallelSubtask.
 *  - scheduling_execution_dimensions is comma-separated wire values, or empty string.
 *  - Keys do not clash with REQUIRED_METADATA_KEYS.
 *  - Keys do not clash with RuntimeHostDescriptor metadata keys.
 *
 * ### CapabilityReport integration
 *  - validateSchedulingBasis returns false for empty metadata.
 *  - validateSchedulingBasis returns true after merging toSchedulingMetadata.
 *  - SCHEDULING_BASIS_METADATA_KEYS matches the four expected key strings.
 *
 * ### ExecutionDimension.fromValue
 *  - Round-trips for all four dimensions.
 *  - Returns null for unknown strings.
 *  - Returns null for null input.
 */
class AndroidCapabilityVectorTest {

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

    private fun activeHostDescriptor(
        role: RuntimeHostDescriptor.FormationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        hostId: String = "host-test-1"
    ) = RuntimeHostDescriptor.of(
        deviceId = "pixel-9",
        deviceRole = "phone",
        formationRole = role,
        hostId = hostId
    ).withState(RuntimeHostDescriptor.HostParticipationState.ACTIVE)

    // ── ExecutionDimension derivation ─────────────────────────────────────────

    @Test
    fun `LOCAL_INFERENCE present when localModelEnabled and modelReady are true`() {
        val settings = allFalseSettings().also {
            it.localModelEnabled = true
            it.modelReady = true
        }
        val vector = AndroidCapabilityVector.from(settings)
        assertTrue(AndroidCapabilityVector.ExecutionDimension.LOCAL_INFERENCE in vector.executionDimensions)
    }

    @Test
    fun `LOCAL_INFERENCE absent when localModelEnabled is false`() {
        val settings = allReadySettings().also { it.localModelEnabled = false }
        val vector = AndroidCapabilityVector.from(settings)
        assertFalse(AndroidCapabilityVector.ExecutionDimension.LOCAL_INFERENCE in vector.executionDimensions)
    }

    @Test
    fun `LOCAL_INFERENCE absent when modelReady is false`() {
        val settings = allReadySettings().also { it.modelReady = false }
        val vector = AndroidCapabilityVector.from(settings)
        assertFalse(AndroidCapabilityVector.ExecutionDimension.LOCAL_INFERENCE in vector.executionDimensions)
    }

    @Test
    fun `ACCESSIBILITY_EXECUTION present when accessibilityReady and overlayReady are true`() {
        val settings = allFalseSettings().also {
            it.accessibilityReady = true
            it.overlayReady = true
        }
        val vector = AndroidCapabilityVector.from(settings)
        assertTrue(AndroidCapabilityVector.ExecutionDimension.ACCESSIBILITY_EXECUTION in vector.executionDimensions)
    }

    @Test
    fun `ACCESSIBILITY_EXECUTION absent when accessibilityReady is false`() {
        val settings = allReadySettings().also { it.accessibilityReady = false }
        val vector = AndroidCapabilityVector.from(settings)
        assertFalse(AndroidCapabilityVector.ExecutionDimension.ACCESSIBILITY_EXECUTION in vector.executionDimensions)
    }

    @Test
    fun `ACCESSIBILITY_EXECUTION absent when overlayReady is false`() {
        val settings = allReadySettings().also { it.overlayReady = false }
        val vector = AndroidCapabilityVector.from(settings)
        assertFalse(AndroidCapabilityVector.ExecutionDimension.ACCESSIBILITY_EXECUTION in vector.executionDimensions)
    }

    @Test
    fun `PARALLEL_SUBTASK present when parallelExecutionEnabled is true`() {
        val settings = allFalseSettings().also { it.parallelExecutionEnabled = true }
        val vector = AndroidCapabilityVector.from(settings)
        assertTrue(AndroidCapabilityVector.ExecutionDimension.PARALLEL_SUBTASK in vector.executionDimensions)
    }

    @Test
    fun `PARALLEL_SUBTASK absent when parallelExecutionEnabled is false`() {
        val settings = allReadySettings().also { it.parallelExecutionEnabled = false }
        val vector = AndroidCapabilityVector.from(settings)
        assertFalse(AndroidCapabilityVector.ExecutionDimension.PARALLEL_SUBTASK in vector.executionDimensions)
    }

    @Test
    fun `CROSS_DEVICE_COORDINATION present when crossDeviceEnabled and goalExecutionEnabled are true`() {
        val settings = allFalseSettings().also {
            it.crossDeviceEnabled = true
            it.goalExecutionEnabled = true
        }
        val vector = AndroidCapabilityVector.from(settings)
        assertTrue(AndroidCapabilityVector.ExecutionDimension.CROSS_DEVICE_COORDINATION in vector.executionDimensions)
    }

    @Test
    fun `CROSS_DEVICE_COORDINATION absent when crossDeviceEnabled is false`() {
        val settings = allReadySettings().also { it.crossDeviceEnabled = false }
        val vector = AndroidCapabilityVector.from(settings)
        assertFalse(AndroidCapabilityVector.ExecutionDimension.CROSS_DEVICE_COORDINATION in vector.executionDimensions)
    }

    @Test
    fun `CROSS_DEVICE_COORDINATION absent when goalExecutionEnabled is false`() {
        val settings = allReadySettings().also { it.goalExecutionEnabled = false }
        val vector = AndroidCapabilityVector.from(settings)
        assertFalse(AndroidCapabilityVector.ExecutionDimension.CROSS_DEVICE_COORDINATION in vector.executionDimensions)
    }

    @Test
    fun `all four dimensions present for a fully-ready device`() {
        val vector = AndroidCapabilityVector.from(allReadySettings())
        assertEquals(
            "fully ready device must have all four execution dimensions",
            4,
            vector.executionDimensions.size
        )
        assertTrue(AndroidCapabilityVector.ExecutionDimension.LOCAL_INFERENCE in vector.executionDimensions)
        assertTrue(AndroidCapabilityVector.ExecutionDimension.ACCESSIBILITY_EXECUTION in vector.executionDimensions)
        assertTrue(AndroidCapabilityVector.ExecutionDimension.PARALLEL_SUBTASK in vector.executionDimensions)
        assertTrue(AndroidCapabilityVector.ExecutionDimension.CROSS_DEVICE_COORDINATION in vector.executionDimensions)
    }

    @Test
    fun `empty dimension set for all-false settings`() {
        val vector = AndroidCapabilityVector.from(allFalseSettings())
        assertTrue(
            "all-false settings must produce an empty dimension set",
            vector.executionDimensions.isEmpty()
        )
    }

    // ── Formation role and participation state ────────────────────────────────

    @Test
    fun `formationRole taken from hostDescriptor when provided`() {
        val descriptor = activeHostDescriptor(role = RuntimeHostDescriptor.FormationRole.SECONDARY)
        val vector = AndroidCapabilityVector.from(allReadySettings(), descriptor)
        assertEquals(RuntimeHostDescriptor.FormationRole.SECONDARY, vector.formationRole)
    }

    @Test
    fun `participationState taken from hostDescriptor when provided`() {
        val descriptor = activeHostDescriptor()
        val vector = AndroidCapabilityVector.from(allReadySettings(), descriptor)
        assertEquals(RuntimeHostDescriptor.HostParticipationState.ACTIVE, vector.participationState)
    }

    @Test
    fun `formationRole defaults to PRIMARY when hostDescriptor is null`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), hostDescriptor = null)
        assertEquals(RuntimeHostDescriptor.FormationRole.PRIMARY, vector.formationRole)
    }

    @Test
    fun `participationState defaults to INACTIVE when hostDescriptor is null`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), hostDescriptor = null)
        assertEquals(RuntimeHostDescriptor.HostParticipationState.INACTIVE, vector.participationState)
    }

    @Test
    fun `hostId taken from hostDescriptor when provided`() {
        val descriptor = activeHostDescriptor(hostId = "specific-host-id")
        val vector = AndroidCapabilityVector.from(allReadySettings(), descriptor)
        assertEquals("specific-host-id", vector.hostId)
    }

    @Test
    fun `hostId is empty string when hostDescriptor is null`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), hostDescriptor = null)
        assertEquals("", vector.hostId)
    }

    // ── Derived scheduling eligibility flags ─────────────────────────────────

    @Test
    fun `isEligibleForLocalExecution true when ACTIVE with LOCAL_INFERENCE and ACCESSIBILITY_EXECUTION`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), activeHostDescriptor())
        assertTrue(
            "fully ready ACTIVE device must be eligible for local execution",
            vector.isEligibleForLocalExecution
        )
    }

    @Test
    fun `isEligibleForLocalExecution false when INACTIVE even with all dimensions`() {
        // No host descriptor → defaults to INACTIVE
        val vector = AndroidCapabilityVector.from(allReadySettings(), hostDescriptor = null)
        assertFalse(
            "INACTIVE device must not be eligible for local execution",
            vector.isEligibleForLocalExecution
        )
    }

    @Test
    fun `isEligibleForLocalExecution false when LOCAL_INFERENCE missing`() {
        val settings = allReadySettings().also { it.localModelEnabled = false }
        val vector = AndroidCapabilityVector.from(settings, activeHostDescriptor())
        assertFalse(
            "device without LOCAL_INFERENCE must not be eligible for local execution",
            vector.isEligibleForLocalExecution
        )
    }

    @Test
    fun `isEligibleForLocalExecution false when ACCESSIBILITY_EXECUTION missing`() {
        val settings = allReadySettings().also { it.accessibilityReady = false }
        val vector = AndroidCapabilityVector.from(settings, activeHostDescriptor())
        assertFalse(
            "device without ACCESSIBILITY_EXECUTION must not be eligible for local execution",
            vector.isEligibleForLocalExecution
        )
    }

    @Test
    fun `isEligibleForCrossDeviceParticipation true when ACTIVE with CROSS_DEVICE_COORDINATION`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), activeHostDescriptor())
        assertTrue(
            "fully ready ACTIVE device must be eligible for cross-device participation",
            vector.isEligibleForCrossDeviceParticipation
        )
    }

    @Test
    fun `isEligibleForCrossDeviceParticipation false when INACTIVE`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), hostDescriptor = null)
        assertFalse(
            "INACTIVE device must not be eligible for cross-device participation",
            vector.isEligibleForCrossDeviceParticipation
        )
    }

    @Test
    fun `isEligibleForCrossDeviceParticipation false when CROSS_DEVICE_COORDINATION missing`() {
        val settings = allReadySettings().also { it.crossDeviceEnabled = false }
        val vector = AndroidCapabilityVector.from(settings, activeHostDescriptor())
        assertFalse(
            "device without CROSS_DEVICE_COORDINATION must not be cross-device eligible",
            vector.isEligibleForCrossDeviceParticipation
        )
    }

    @Test
    fun `isEligibleForParallelSubtask true when ACTIVE with PARALLEL_SUBTASK`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), activeHostDescriptor())
        assertTrue(
            "fully ready ACTIVE device must be eligible for parallel subtask",
            vector.isEligibleForParallelSubtask
        )
    }

    @Test
    fun `isEligibleForParallelSubtask false when INACTIVE`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), hostDescriptor = null)
        assertFalse(
            "INACTIVE device must not be eligible for parallel subtask",
            vector.isEligibleForParallelSubtask
        )
    }

    @Test
    fun `isPrimaryHost true when formationRole is PRIMARY`() {
        val descriptor = activeHostDescriptor(role = RuntimeHostDescriptor.FormationRole.PRIMARY)
        val vector = AndroidCapabilityVector.from(allReadySettings(), descriptor)
        assertTrue("PRIMARY formation role must make isPrimaryHost=true", vector.isPrimaryHost)
    }

    @Test
    fun `isPrimaryHost false when formationRole is SECONDARY`() {
        val descriptor = activeHostDescriptor(role = RuntimeHostDescriptor.FormationRole.SECONDARY)
        val vector = AndroidCapabilityVector.from(allReadySettings(), descriptor)
        assertFalse("SECONDARY formation role must make isPrimaryHost=false", vector.isPrimaryHost)
    }

    // ── Wire format ───────────────────────────────────────────────────────────

    @Test
    fun `toSchedulingMetadata returns exactly the four scheduling-basis keys`() {
        val meta = AndroidCapabilityVector.from(allReadySettings(), activeHostDescriptor())
            .toSchedulingMetadata()
        assertEquals("must have exactly 4 scheduling-basis keys", 4, meta.size)
        assertTrue(meta.containsKey(AndroidCapabilityVector.KEY_LOCAL_ELIGIBLE))
        assertTrue(meta.containsKey(AndroidCapabilityVector.KEY_CROSS_DEVICE_ELIGIBLE))
        assertTrue(meta.containsKey(AndroidCapabilityVector.KEY_PARALLEL_SUBTASK_ELIGIBLE))
        assertTrue(meta.containsKey(AndroidCapabilityVector.KEY_EXECUTION_DIMENSIONS))
    }

    @Test
    fun `scheduling_local_eligible reflects isEligibleForLocalExecution`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), activeHostDescriptor())
        val meta = vector.toSchedulingMetadata()
        assertEquals(
            vector.isEligibleForLocalExecution,
            meta[AndroidCapabilityVector.KEY_LOCAL_ELIGIBLE]
        )
    }

    @Test
    fun `scheduling_cross_device_eligible reflects isEligibleForCrossDeviceParticipation`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), activeHostDescriptor())
        val meta = vector.toSchedulingMetadata()
        assertEquals(
            vector.isEligibleForCrossDeviceParticipation,
            meta[AndroidCapabilityVector.KEY_CROSS_DEVICE_ELIGIBLE]
        )
    }

    @Test
    fun `scheduling_parallel_subtask_eligible reflects isEligibleForParallelSubtask`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), activeHostDescriptor())
        val meta = vector.toSchedulingMetadata()
        assertEquals(
            vector.isEligibleForParallelSubtask,
            meta[AndroidCapabilityVector.KEY_PARALLEL_SUBTASK_ELIGIBLE]
        )
    }

    @Test
    fun `scheduling_execution_dimensions is comma-separated wire values for full device`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), activeHostDescriptor())
        val dimsString = vector.toSchedulingMetadata()[AndroidCapabilityVector.KEY_EXECUTION_DIMENSIONS] as String
        val parts = dimsString.split(",").toSet()
        assertTrue(parts.contains("local_inference"))
        assertTrue(parts.contains("accessibility_execution"))
        assertTrue(parts.contains("parallel_subtask"))
        assertTrue(parts.contains("cross_device_coordination"))
    }

    @Test
    fun `scheduling_execution_dimensions is empty string for all-false settings`() {
        val vector = AndroidCapabilityVector.from(allFalseSettings())
        val dimsString = vector.toSchedulingMetadata()[AndroidCapabilityVector.KEY_EXECUTION_DIMENSIONS] as String
        assertEquals("", dimsString)
    }

    @Test
    fun `scheduling metadata keys do not clash with REQUIRED_METADATA_KEYS`() {
        val schedulingKeys = setOf(
            AndroidCapabilityVector.KEY_LOCAL_ELIGIBLE,
            AndroidCapabilityVector.KEY_CROSS_DEVICE_ELIGIBLE,
            AndroidCapabilityVector.KEY_PARALLEL_SUBTASK_ELIGIBLE,
            AndroidCapabilityVector.KEY_EXECUTION_DIMENSIONS
        )
        val intersection = schedulingKeys.intersect(CapabilityReport.REQUIRED_METADATA_KEYS)
        assertTrue(
            "scheduling metadata keys must not clash with REQUIRED_METADATA_KEYS: $intersection",
            intersection.isEmpty()
        )
    }

    @Test
    fun `scheduling metadata keys do not clash with RuntimeHostDescriptor metadata keys`() {
        val schedulingKeys = setOf(
            AndroidCapabilityVector.KEY_LOCAL_ELIGIBLE,
            AndroidCapabilityVector.KEY_CROSS_DEVICE_ELIGIBLE,
            AndroidCapabilityVector.KEY_PARALLEL_SUBTASK_ELIGIBLE,
            AndroidCapabilityVector.KEY_EXECUTION_DIMENSIONS
        )
        val hostKeys = setOf(
            RuntimeHostDescriptor.KEY_HOST_ID,
            RuntimeHostDescriptor.KEY_FORMATION_ROLE,
            RuntimeHostDescriptor.KEY_PARTICIPATION_STATE,
            RuntimeHostDescriptor.KEY_REGISTERED_AT_MS
        )
        val intersection = schedulingKeys.intersect(hostKeys)
        assertTrue(
            "scheduling metadata keys must not clash with RuntimeHostDescriptor keys: $intersection",
            intersection.isEmpty()
        )
    }

    // ── CapabilityReport integration ──────────────────────────────────────────

    @Test
    fun `validateSchedulingBasis returns false for empty metadata`() {
        val report = CapabilityReport(
            platform = "android",
            device_id = "dev-sb-001",
            supported_actions = listOf("screen_capture")
        )
        assertFalse(
            "validateSchedulingBasis() must return false when scheduling keys are absent",
            report.validateSchedulingBasis()
        )
        assertEquals(
            "all 4 scheduling keys must be reported missing",
            CapabilityReport.SCHEDULING_BASIS_METADATA_KEYS,
            report.missingSchedulingBasisKeys()
        )
    }

    @Test
    fun `validateSchedulingBasis returns true after merging toSchedulingMetadata`() {
        val vector = AndroidCapabilityVector.from(allReadySettings(), activeHostDescriptor())
        val report = CapabilityReport(
            platform = "android",
            device_id = "dev-sb-002",
            supported_actions = listOf("screen_capture"),
            metadata = vector.toSchedulingMetadata()
        )
        assertTrue(
            "validateSchedulingBasis() must return true after merging scheduling metadata",
            report.validateSchedulingBasis()
        )
        assertTrue(
            "missingSchedulingBasisKeys() must be empty",
            report.missingSchedulingBasisKeys().isEmpty()
        )
    }

    @Test
    fun `SCHEDULING_BASIS_METADATA_KEYS contains exactly the four expected key strings`() {
        val keys = CapabilityReport.SCHEDULING_BASIS_METADATA_KEYS
        assertEquals("must have exactly 4 scheduling-basis keys", 4, keys.size)
        assertTrue(keys.contains("scheduling_local_eligible"))
        assertTrue(keys.contains("scheduling_cross_device_eligible"))
        assertTrue(keys.contains("scheduling_parallel_subtask_eligible"))
        assertTrue(keys.contains("scheduling_execution_dimensions"))
    }

    // ── ExecutionDimension.fromValue ──────────────────────────────────────────

    @Test
    fun `ExecutionDimension fromValue round-trips for all four dimensions`() {
        for (dim in AndroidCapabilityVector.ExecutionDimension.entries) {
            assertEquals(
                "fromValue must round-trip for ${dim.wireValue}",
                dim,
                AndroidCapabilityVector.ExecutionDimension.fromValue(dim.wireValue)
            )
        }
    }

    @Test
    fun `ExecutionDimension fromValue returns null for unknown string`() {
        assertNull(AndroidCapabilityVector.ExecutionDimension.fromValue("totally_unknown"))
    }

    @Test
    fun `ExecutionDimension fromValue returns null for null input`() {
        assertNull(AndroidCapabilityVector.ExecutionDimension.fromValue(null))
    }
}
