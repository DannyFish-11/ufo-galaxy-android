package com.ufo.galaxy.runtime

import com.ufo.galaxy.data.InMemoryAppSettings
import org.junit.Assert.*
import org.junit.Test

/**
 * Android-repo half of PR-5 in the post-#533 dual-repo runtime host unification track.
 *
 * **PR-5 scope**: promote Android to a first-class runtime host in registration,
 * formation, and projection.
 *
 * ## Test matrix
 *
 * ### RuntimeHostDescriptor — creation and fields
 *  - Default formation role is [RuntimeHostDescriptor.FormationRole.EXECUTOR].
 *  - Default participation state is [RuntimeHostDescriptor.HostParticipationState.REGISTERED].
 *  - `hostId` is composite: `"deviceId:runtimeSessionId"`.
 *  - `hostCapabilities` list is preserved as-is.
 *
 * ### RuntimeHostDescriptor.FormationRole
 *  - `fromDeviceRole("hub")` returns [RuntimeHostDescriptor.FormationRole.COORDINATOR].
 *  - `fromDeviceRole("phone")` returns [RuntimeHostDescriptor.FormationRole.EXECUTOR].
 *  - `fromDeviceRole("tablet")` returns [RuntimeHostDescriptor.FormationRole.EXECUTOR].
 *  - `fromDeviceRole("")` returns [RuntimeHostDescriptor.FormationRole.EXECUTOR] (safe default).
 *  - All roles expose correct [RuntimeHostDescriptor.FormationRole.wireValue].
 *
 * ### RuntimeHostDescriptor.HostParticipationState
 *  - All states expose correct [RuntimeHostDescriptor.HostParticipationState.wireValue].
 *  - `fromWireValue` round-trips all canonical values.
 *  - Unknown wire values fall back to [RuntimeHostDescriptor.HostParticipationState.REGISTERED].
 *
 * ### RuntimeHostDescriptor.isEligibleForTaskAssignment
 *  - `true` for [RuntimeHostDescriptor.HostParticipationState.REGISTERED].
 *  - `true` for [RuntimeHostDescriptor.HostParticipationState.ACTIVE].
 *  - `false` for [RuntimeHostDescriptor.HostParticipationState.SUSPENDED].
 *  - `false` for [RuntimeHostDescriptor.HostParticipationState.WITHDRAWN].
 *
 * ### RuntimeHostDescriptor.toRegistrationMap
 *  - All four keys from [RuntimeHostDescriptor.REGISTRATION_MAP_KEYS] are present.
 *  - `runtime_host_eligible` is always `true`.
 *  - `host_formation_role` matches [RuntimeHostDescriptor.FormationRole.wireValue].
 *  - `host_participation_state` matches [RuntimeHostDescriptor.HostParticipationState.wireValue].
 *  - `host_capabilities` is a comma-separated string.
 *
 * ### RuntimeHostDescriptor.fromSettings
 *  - `goalExecutionEnabled=true` contributes `"goal_execution"` capability.
 *  - `localModelEnabled=true` contributes `"local_model_inference"` capability.
 *  - `parallelExecutionEnabled=true` contributes `"parallel_subtask_execution"` capability.
 *  - `accessibilityReady=true` contributes `"accessibility_control"` capability.
 *  - All flags `false` → empty capability list.
 *  - `deviceRole="hub"` → [RuntimeHostDescriptor.FormationRole.COORDINATOR].
 *  - `deviceRole="phone"` → [RuntimeHostDescriptor.FormationRole.EXECUTOR].
 *  - `hostId` is correctly composed from `deviceId` and `runtimeSessionId`.
 *  - Initial `participationState` is always [RuntimeHostDescriptor.HostParticipationState.REGISTERED].
 *
 * ### Backward compatibility
 *  - [RuntimeHostDescriptor.REGISTRATION_MAP_KEYS] does not overlap with the 8 legacy
 *    [com.ufo.galaxy.data.CapabilityReport.REQUIRED_METADATA_KEYS].
 */
class AndroidRuntimeHostRegistrationTest {

    // ── Creation and fields ───────────────────────────────────────────────────

    @Test
    fun `default formation role is EXECUTOR`() {
        val descriptor = RuntimeHostDescriptor(
            hostId = "dev:sess",
            runtimeSessionId = "sess-1"
        )
        assertEquals(RuntimeHostDescriptor.FormationRole.EXECUTOR, descriptor.formationRole)
    }

    @Test
    fun `default participation state is REGISTERED`() {
        val descriptor = RuntimeHostDescriptor(
            hostId = "dev:sess",
            runtimeSessionId = "sess-1"
        )
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.REGISTERED,
            descriptor.participationState
        )
    }

    @Test
    fun `hostId is composite deviceId colon runtimeSessionId`() {
        val descriptor = RuntimeHostDescriptor.fromSettings(
            deviceId = "pixel8-001",
            runtimeSessionId = "rs-abc123",
            settings = InMemoryAppSettings()
        )
        assertEquals("pixel8-001:rs-abc123", descriptor.hostId)
    }

    @Test
    fun `hostCapabilities are preserved`() {
        val caps = listOf("goal_execution", "local_model_inference")
        val descriptor = RuntimeHostDescriptor(
            hostId = "dev:sess",
            runtimeSessionId = "sess-1",
            hostCapabilities = caps
        )
        assertEquals(caps, descriptor.hostCapabilities)
    }

    // ── FormationRole ─────────────────────────────────────────────────────────

    @Test
    fun `FormationRole fromDeviceRole hub returns COORDINATOR`() {
        assertEquals(
            RuntimeHostDescriptor.FormationRole.COORDINATOR,
            RuntimeHostDescriptor.FormationRole.fromDeviceRole("hub")
        )
    }

    @Test
    fun `FormationRole fromDeviceRole phone returns EXECUTOR`() {
        assertEquals(
            RuntimeHostDescriptor.FormationRole.EXECUTOR,
            RuntimeHostDescriptor.FormationRole.fromDeviceRole("phone")
        )
    }

    @Test
    fun `FormationRole fromDeviceRole tablet returns EXECUTOR`() {
        assertEquals(
            RuntimeHostDescriptor.FormationRole.EXECUTOR,
            RuntimeHostDescriptor.FormationRole.fromDeviceRole("tablet")
        )
    }

    @Test
    fun `FormationRole fromDeviceRole empty returns EXECUTOR as safe default`() {
        assertEquals(
            RuntimeHostDescriptor.FormationRole.EXECUTOR,
            RuntimeHostDescriptor.FormationRole.fromDeviceRole("")
        )
    }

    @Test
    fun `FormationRole wireValues are canonical`() {
        assertEquals("executor", RuntimeHostDescriptor.FormationRole.EXECUTOR.wireValue)
        assertEquals("coordinator", RuntimeHostDescriptor.FormationRole.COORDINATOR.wireValue)
        assertEquals("observer", RuntimeHostDescriptor.FormationRole.OBSERVER.wireValue)
    }

    @Test
    fun `FormationRole fromWireValue round-trips all canonical values`() {
        assertEquals(
            RuntimeHostDescriptor.FormationRole.EXECUTOR,
            RuntimeHostDescriptor.FormationRole.fromWireValue("executor")
        )
        assertEquals(
            RuntimeHostDescriptor.FormationRole.COORDINATOR,
            RuntimeHostDescriptor.FormationRole.fromWireValue("coordinator")
        )
        assertEquals(
            RuntimeHostDescriptor.FormationRole.OBSERVER,
            RuntimeHostDescriptor.FormationRole.fromWireValue("observer")
        )
    }

    @Test
    fun `FormationRole fromWireValue unknown falls back to EXECUTOR`() {
        assertEquals(
            RuntimeHostDescriptor.FormationRole.EXECUTOR,
            RuntimeHostDescriptor.FormationRole.fromWireValue("future_role")
        )
        assertEquals(
            RuntimeHostDescriptor.FormationRole.EXECUTOR,
            RuntimeHostDescriptor.FormationRole.fromWireValue(null)
        )
    }

    // ── HostParticipationState ────────────────────────────────────────────────

    @Test
    fun `HostParticipationState wireValues are canonical`() {
        assertEquals("registered", RuntimeHostDescriptor.HostParticipationState.REGISTERED.wireValue)
        assertEquals("active", RuntimeHostDescriptor.HostParticipationState.ACTIVE.wireValue)
        assertEquals("suspended", RuntimeHostDescriptor.HostParticipationState.SUSPENDED.wireValue)
        assertEquals("withdrawn", RuntimeHostDescriptor.HostParticipationState.WITHDRAWN.wireValue)
    }

    @Test
    fun `HostParticipationState fromWireValue round-trips all canonical values`() {
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.REGISTERED,
            RuntimeHostDescriptor.HostParticipationState.fromWireValue("registered")
        )
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.ACTIVE,
            RuntimeHostDescriptor.HostParticipationState.fromWireValue("active")
        )
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.SUSPENDED,
            RuntimeHostDescriptor.HostParticipationState.fromWireValue("suspended")
        )
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.WITHDRAWN,
            RuntimeHostDescriptor.HostParticipationState.fromWireValue("withdrawn")
        )
    }

    @Test
    fun `HostParticipationState fromWireValue unknown falls back to REGISTERED`() {
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.REGISTERED,
            RuntimeHostDescriptor.HostParticipationState.fromWireValue("future_state")
        )
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.REGISTERED,
            RuntimeHostDescriptor.HostParticipationState.fromWireValue(null)
        )
    }

    // ── isEligibleForTaskAssignment ───────────────────────────────────────────

    @Test
    fun `isEligibleForTaskAssignment is true for REGISTERED state`() {
        val descriptor = RuntimeHostDescriptor(
            hostId = "dev:sess",
            runtimeSessionId = "sess-1",
            participationState = RuntimeHostDescriptor.HostParticipationState.REGISTERED
        )
        assertTrue(
            "REGISTERED host must be eligible for task assignment",
            descriptor.isEligibleForTaskAssignment
        )
    }

    @Test
    fun `isEligibleForTaskAssignment is true for ACTIVE state`() {
        val descriptor = RuntimeHostDescriptor(
            hostId = "dev:sess",
            runtimeSessionId = "sess-1",
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertTrue(
            "ACTIVE host must be eligible for task assignment",
            descriptor.isEligibleForTaskAssignment
        )
    }

    @Test
    fun `isEligibleForTaskAssignment is false for SUSPENDED state`() {
        val descriptor = RuntimeHostDescriptor(
            hostId = "dev:sess",
            runtimeSessionId = "sess-1",
            participationState = RuntimeHostDescriptor.HostParticipationState.SUSPENDED
        )
        assertFalse(
            "SUSPENDED host must NOT be eligible for task assignment",
            descriptor.isEligibleForTaskAssignment
        )
    }

    @Test
    fun `isEligibleForTaskAssignment is false for WITHDRAWN state`() {
        val descriptor = RuntimeHostDescriptor(
            hostId = "dev:sess",
            runtimeSessionId = "sess-1",
            participationState = RuntimeHostDescriptor.HostParticipationState.WITHDRAWN
        )
        assertFalse(
            "WITHDRAWN host must NOT be eligible for task assignment",
            descriptor.isEligibleForTaskAssignment
        )
    }

    // ── toRegistrationMap ─────────────────────────────────────────────────────

    @Test
    fun `toRegistrationMap contains all REGISTRATION_MAP_KEYS`() {
        val descriptor = RuntimeHostDescriptor(
            hostId = "dev:sess",
            runtimeSessionId = "sess-1",
            hostCapabilities = listOf("goal_execution")
        )
        val map = descriptor.toRegistrationMap()
        for (key in RuntimeHostDescriptor.REGISTRATION_MAP_KEYS) {
            assertTrue("toRegistrationMap must contain key '$key'", map.containsKey(key))
        }
    }

    @Test
    fun `toRegistrationMap runtime_host_eligible is always true`() {
        val descriptor = RuntimeHostDescriptor(
            hostId = "dev:sess",
            runtimeSessionId = "sess-1"
        )
        assertEquals(true, descriptor.toRegistrationMap()["runtime_host_eligible"])
    }

    @Test
    fun `toRegistrationMap host_formation_role matches FormationRole wireValue`() {
        val descriptor = RuntimeHostDescriptor(
            hostId = "dev:sess",
            runtimeSessionId = "sess-1",
            formationRole = RuntimeHostDescriptor.FormationRole.COORDINATOR
        )
        assertEquals("coordinator", descriptor.toRegistrationMap()["host_formation_role"])
    }

    @Test
    fun `toRegistrationMap host_participation_state matches HostParticipationState wireValue`() {
        val descriptor = RuntimeHostDescriptor(
            hostId = "dev:sess",
            runtimeSessionId = "sess-1",
            participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE
        )
        assertEquals("active", descriptor.toRegistrationMap()["host_participation_state"])
    }

    @Test
    fun `toRegistrationMap host_capabilities is comma-separated`() {
        val caps = listOf("goal_execution", "local_model_inference", "accessibility_control")
        val descriptor = RuntimeHostDescriptor(
            hostId = "dev:sess",
            runtimeSessionId = "sess-1",
            hostCapabilities = caps
        )
        assertEquals(
            "goal_execution,local_model_inference,accessibility_control",
            descriptor.toRegistrationMap()["host_capabilities"]
        )
    }

    @Test
    fun `toRegistrationMap host_capabilities is empty string when no capabilities`() {
        val descriptor = RuntimeHostDescriptor(
            hostId = "dev:sess",
            runtimeSessionId = "sess-1",
            hostCapabilities = emptyList()
        )
        assertEquals("", descriptor.toRegistrationMap()["host_capabilities"])
    }

    // ── fromSettings ──────────────────────────────────────────────────────────

    @Test
    fun `fromSettings goalExecutionEnabled true contributes goal_execution capability`() {
        val settings = InMemoryAppSettings(goalExecutionEnabled = true)
        val descriptor = RuntimeHostDescriptor.fromSettings("dev", "sess", settings)
        assertTrue(
            "goal_execution capability must be present when goalExecutionEnabled=true",
            descriptor.hostCapabilities.contains("goal_execution")
        )
    }

    @Test
    fun `fromSettings localModelEnabled true contributes local_model_inference capability`() {
        val settings = InMemoryAppSettings(localModelEnabled = true)
        val descriptor = RuntimeHostDescriptor.fromSettings("dev", "sess", settings)
        assertTrue(
            "local_model_inference capability must be present when localModelEnabled=true",
            descriptor.hostCapabilities.contains("local_model_inference")
        )
    }

    @Test
    fun `fromSettings parallelExecutionEnabled true contributes parallel_subtask_execution capability`() {
        val settings = InMemoryAppSettings(parallelExecutionEnabled = true)
        val descriptor = RuntimeHostDescriptor.fromSettings("dev", "sess", settings)
        assertTrue(
            "parallel_subtask_execution must be present when parallelExecutionEnabled=true",
            descriptor.hostCapabilities.contains("parallel_subtask_execution")
        )
    }

    @Test
    fun `fromSettings accessibilityReady true contributes accessibility_control capability`() {
        val settings = InMemoryAppSettings(accessibilityReady = true)
        val descriptor = RuntimeHostDescriptor.fromSettings("dev", "sess", settings)
        assertTrue(
            "accessibility_control must be present when accessibilityReady=true",
            descriptor.hostCapabilities.contains("accessibility_control")
        )
    }

    @Test
    fun `fromSettings all flags false produces empty capability list`() {
        val settings = InMemoryAppSettings(
            goalExecutionEnabled = false,
            localModelEnabled = false,
            parallelExecutionEnabled = false,
            accessibilityReady = false
        )
        val descriptor = RuntimeHostDescriptor.fromSettings("dev", "sess", settings)
        assertTrue(
            "Capability list must be empty when all flags are false",
            descriptor.hostCapabilities.isEmpty()
        )
    }

    @Test
    fun `fromSettings deviceRole hub produces COORDINATOR formation role`() {
        val settings = InMemoryAppSettings(deviceRole = "hub")
        val descriptor = RuntimeHostDescriptor.fromSettings("dev", "sess", settings)
        assertEquals(
            "Hub device must have COORDINATOR formation role",
            RuntimeHostDescriptor.FormationRole.COORDINATOR,
            descriptor.formationRole
        )
    }

    @Test
    fun `fromSettings deviceRole phone produces EXECUTOR formation role`() {
        val settings = InMemoryAppSettings(deviceRole = "phone")
        val descriptor = RuntimeHostDescriptor.fromSettings("dev", "sess", settings)
        assertEquals(
            "Phone device must have EXECUTOR formation role",
            RuntimeHostDescriptor.FormationRole.EXECUTOR,
            descriptor.formationRole
        )
    }

    @Test
    fun `fromSettings initial participationState is always REGISTERED`() {
        val settings = InMemoryAppSettings(
            goalExecutionEnabled = true,
            crossDeviceEnabled = true
        )
        val descriptor = RuntimeHostDescriptor.fromSettings("dev", "sess", settings)
        assertEquals(
            "Initial participation state must be REGISTERED",
            RuntimeHostDescriptor.HostParticipationState.REGISTERED,
            descriptor.participationState
        )
    }

    @Test
    fun `fromSettings produces all four host capabilities when all flags are true`() {
        val settings = InMemoryAppSettings(
            goalExecutionEnabled = true,
            localModelEnabled = true,
            parallelExecutionEnabled = true,
            accessibilityReady = true
        )
        val descriptor = RuntimeHostDescriptor.fromSettings("dev", "sess", settings)
        assertEquals(
            "All four capabilities must be present when all flags are true",
            4,
            descriptor.hostCapabilities.size
        )
        assertTrue(descriptor.hostCapabilities.contains("goal_execution"))
        assertTrue(descriptor.hostCapabilities.contains("local_model_inference"))
        assertTrue(descriptor.hostCapabilities.contains("parallel_subtask_execution"))
        assertTrue(descriptor.hostCapabilities.contains("accessibility_control"))
    }

    // ── Backward compatibility ────────────────────────────────────────────────

    @Test
    fun `REGISTRATION_MAP_KEYS does not overlap with CapabilityReport REQUIRED_METADATA_KEYS`() {
        val legacyKeys = com.ufo.galaxy.data.CapabilityReport.REQUIRED_METADATA_KEYS
        val hostKeys = RuntimeHostDescriptor.REGISTRATION_MAP_KEYS
        val overlap = legacyKeys.intersect(hostKeys)
        assertTrue(
            "Runtime host registration keys must not conflict with legacy required metadata keys. " +
                "Overlap found: $overlap",
            overlap.isEmpty()
        )
    }

    @Test
    fun `REGISTRATION_MAP_KEYS contains exactly the four expected keys`() {
        val keys = RuntimeHostDescriptor.REGISTRATION_MAP_KEYS
        assertEquals("must have exactly 4 registration keys", 4, keys.size)
        assertTrue(keys.contains("runtime_host_eligible"))
        assertTrue(keys.contains("host_formation_role"))
        assertTrue(keys.contains("host_participation_state"))
        assertTrue(keys.contains("host_capabilities"))
    }
}
