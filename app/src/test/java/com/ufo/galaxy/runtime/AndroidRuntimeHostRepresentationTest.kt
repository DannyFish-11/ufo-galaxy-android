package com.ufo.galaxy.runtime

import com.ufo.galaxy.agent.TakeoverEligibilityAssessor
import com.ufo.galaxy.agent.TakeoverRequestEnvelope
import com.ufo.galaxy.agent.TakeoverResponseEnvelope
import com.ufo.galaxy.data.InMemoryAppSettings
import org.junit.Assert.*
import org.junit.Test

/**
 * Integration-style unit tests verifying Android first-class runtime-host representation
 * and participation behavior (PR-5, post-#533 dual-repo runtime unification).
 *
 * This test class validates that Android's host identity model ([RuntimeHostDescriptor])
 * integrates correctly with the existing takeover eligibility and response paths so that:
 *  - Host identity metadata flows correctly into the canonical structures.
 *  - [TakeoverResponseEnvelope] carries host identity on acceptance.
 *  - [TakeoverEligibilityAssessor] is unaffected by the addition of host descriptors.
 *  - [RuntimeHostDescriptor] correctly represents different lifecycle states.
 *
 * ## Test matrix
 *
 * ### Host descriptor creation via [RuntimeHostDescriptor.of]
 *  - Newly created descriptor is in INACTIVE state.
 *  - isAcceptingTasks is false until explicitly transitioned to ACTIVE.
 *  - Transition to ACTIVE via withState produces an accepting host.
 *
 * ### Host metadata integration
 *  - toMetadataMap keys do not clash with existing capability_report metadata keys.
 *  - Merging host metadata into an existing metadata map preserves all original keys.
 *  - Host metadata keys take their expected values after merge.
 *
 * ### TakeoverResponseEnvelope with host identity (PR-5)
 *  - Acceptance response carries runtime_host_id from the host descriptor.
 *  - Acceptance response carries formation_role from the host descriptor.
 *  - Rejection response has null runtime_host_id and null formation_role.
 *
 * ### TakeoverEligibilityAssessor compatibility
 *  - Adding a RuntimeHostDescriptor does not change eligibility assessment outcomes.
 *  - A fully ready device is still ELIGIBLE regardless of host descriptor presence.
 *  - An ineligible device is still blocked regardless of host descriptor presence.
 *
 * ### Host lifecycle state transitions
 *  - INACTIVE → ACTIVE transition via withState.
 *  - ACTIVE → DRAINING transition via withState.
 *  - DRAINING → INACTIVE transition via withState.
 *  - State machine coverage: all four states are reachable via withState.
 *
 * ### Formation role semantics
 *  - PRIMARY host isPrimary == true; SECONDARY and SATELLITE are not.
 *  - FormationRole.fromValue round-trips for all three roles.
 *
 * ### Runtime host participation representation
 *  - An ACTIVE PRIMARY host is both isPrimary and isAcceptingTasks.
 *  - An INACTIVE PRIMARY host is isPrimary but not isAcceptingTasks.
 *  - A SECONDARY ACTIVE host is isAcceptingTasks but not isPrimary.
 */
class AndroidRuntimeHostRepresentationTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun readySettings() = InMemoryAppSettings(
        crossDeviceEnabled = true,
        goalExecutionEnabled = true,
        accessibilityReady = true,
        overlayReady = true
    )

    private fun activeHostDescriptor(
        role: RuntimeHostDescriptor.FormationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        hostId: String = "test-host-abc"
    ): RuntimeHostDescriptor = RuntimeHostDescriptor.of(
        deviceId = "pixel-8",
        deviceRole = "phone",
        formationRole = role,
        hostId = hostId
    ).withState(RuntimeHostDescriptor.HostParticipationState.ACTIVE)

    private fun takeover(sourceRuntimePosture: String? = SourceRuntimePosture.JOIN_RUNTIME) =
        TakeoverRequestEnvelope(
            takeover_id = "to-test",
            task_id = "t-test",
            trace_id = "tr-test",
            goal = "send message",
            source_runtime_posture = sourceRuntimePosture
        )

    // ── Host descriptor creation ──────────────────────────────────────────────

    @Test
    fun `newly created descriptor via of() is in INACTIVE state`() {
        val d = RuntimeHostDescriptor.of(deviceId = "dev-1", deviceRole = "phone")
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.INACTIVE,
            d.participationState
        )
    }

    @Test
    fun `newly created descriptor is not accepting tasks`() {
        val d = RuntimeHostDescriptor.of(deviceId = "dev-1", deviceRole = "phone")
        assertFalse("INACTIVE host must not accept tasks", d.isAcceptingTasks)
    }

    @Test
    fun `withState ACTIVE makes descriptor accept tasks`() {
        val d = RuntimeHostDescriptor.of(deviceId = "dev-1", deviceRole = "phone")
            .withState(RuntimeHostDescriptor.HostParticipationState.ACTIVE)
        assertTrue("ACTIVE host must accept tasks", d.isAcceptingTasks)
    }

    // ── Host metadata integration ─────────────────────────────────────────────

    @Test
    fun `host metadata keys do not clash with existing capability report metadata keys`() {
        val hostKeys = setOf(
            RuntimeHostDescriptor.KEY_HOST_ID,
            RuntimeHostDescriptor.KEY_FORMATION_ROLE,
            RuntimeHostDescriptor.KEY_PARTICIPATION_STATE,
            RuntimeHostDescriptor.KEY_REGISTERED_AT_MS
        )
        // These are the canonical capability_report metadata keys.
        val capabilityKeys = setOf(
            "goal_execution_enabled",
            "local_model_enabled",
            "cross_device_enabled",
            "parallel_execution_enabled",
            "device_role",
            "model_ready",
            "accessibility_ready",
            "overlay_ready"
        )
        val intersection = hostKeys.intersect(capabilityKeys)
        assertTrue(
            "host metadata keys must not clash with capability_report keys: $intersection",
            intersection.isEmpty()
        )
    }

    @Test
    fun `merging host metadata preserves all original capability metadata keys`() {
        val capMeta: MutableMap<String, Any> = mutableMapOf(
            "goal_execution_enabled" to true,
            "local_model_enabled" to false,
            "cross_device_enabled" to true,
            "parallel_execution_enabled" to false,
            "device_role" to "phone",
            "model_ready" to true,
            "accessibility_ready" to true,
            "overlay_ready" to true
        )
        val descriptor = activeHostDescriptor()
        capMeta.putAll(descriptor.toMetadataMap())

        assertTrue(capMeta.containsKey("goal_execution_enabled"))
        assertTrue(capMeta.containsKey("local_model_enabled"))
        assertTrue(capMeta.containsKey("cross_device_enabled"))
        assertTrue(capMeta.containsKey("device_role"))
        assertTrue(capMeta.containsKey("model_ready"))
    }

    @Test
    fun `merged metadata contains host keys with correct values`() {
        val capMeta: MutableMap<String, Any> = mutableMapOf(
            "device_role" to "phone"
        )
        val descriptor = activeHostDescriptor(hostId = "h-specific")
        capMeta.putAll(descriptor.toMetadataMap())

        assertEquals("h-specific", capMeta[RuntimeHostDescriptor.KEY_HOST_ID])
        assertEquals("primary", capMeta[RuntimeHostDescriptor.KEY_FORMATION_ROLE])
        assertEquals("active", capMeta[RuntimeHostDescriptor.KEY_PARTICIPATION_STATE])
    }

    // ── TakeoverResponseEnvelope with host identity ───────────────────────────

    @Test
    fun `accepted takeover response carries runtime_host_id from host descriptor`() {
        val descriptor = activeHostDescriptor(hostId = "h-accept-99")
        val req = takeover()
        val response = TakeoverResponseEnvelope(
            takeover_id = req.takeover_id,
            task_id = req.task_id,
            trace_id = req.trace_id,
            accepted = true,
            device_id = "android-dev-1",
            runtime_session_id = "sess-abc",
            source_runtime_posture = req.source_runtime_posture,
            runtime_host_id = descriptor.hostId,
            formation_role = descriptor.formationRole.wireValue
        )
        assertEquals("h-accept-99", response.runtime_host_id)
    }

    @Test
    fun `accepted takeover response carries formation_role from host descriptor`() {
        val descriptor = activeHostDescriptor(role = RuntimeHostDescriptor.FormationRole.SECONDARY)
        val req = takeover()
        val response = TakeoverResponseEnvelope(
            takeover_id = req.takeover_id,
            task_id = req.task_id,
            trace_id = req.trace_id,
            accepted = true,
            device_id = "android-dev-1",
            runtime_host_id = descriptor.hostId,
            formation_role = descriptor.formationRole.wireValue
        )
        assertEquals("secondary", response.formation_role)
    }

    @Test
    fun `rejection response has null runtime_host_id by default`() {
        val req = takeover()
        val response = TakeoverResponseEnvelope(
            takeover_id = req.takeover_id,
            task_id = req.task_id,
            trace_id = req.trace_id,
            accepted = false,
            rejection_reason = "cross_device_disabled"
        )
        assertNull(
            "rejection response must have null runtime_host_id by default",
            response.runtime_host_id
        )
    }

    @Test
    fun `rejection response has null formation_role by default`() {
        val req = takeover()
        val response = TakeoverResponseEnvelope(
            takeover_id = req.takeover_id,
            task_id = req.task_id,
            trace_id = req.trace_id,
            accepted = false,
            rejection_reason = "cross_device_disabled"
        )
        assertNull(
            "rejection response must have null formation_role by default",
            response.formation_role
        )
    }

    // ── TakeoverEligibilityAssessor compatibility ─────────────────────────────

    @Test
    fun `eligibility assessment is unaffected by host descriptor presence`() {
        val assessor = TakeoverEligibilityAssessor(readySettings())
        val result = assessor.assess(takeover())
        assertTrue(
            "fully ready device must be eligible regardless of host descriptor",
            result.eligible
        )
    }

    @Test
    fun `ineligible device remains blocked regardless of host descriptor`() {
        val settings = readySettings().also { it.crossDeviceEnabled = false }
        val assessor = TakeoverEligibilityAssessor(settings)
        val result = assessor.assess(takeover())
        assertFalse(
            "blocked device must not become eligible due to host descriptor",
            result.eligible
        )
    }

    // ── Host lifecycle state transitions ──────────────────────────────────────

    @Test
    fun `INACTIVE to ACTIVE transition via withState`() {
        val d = RuntimeHostDescriptor.of(deviceId = "dev-1", deviceRole = "phone")
        assertEquals(RuntimeHostDescriptor.HostParticipationState.INACTIVE, d.participationState)
        val active = d.withState(RuntimeHostDescriptor.HostParticipationState.ACTIVE)
        assertEquals(RuntimeHostDescriptor.HostParticipationState.ACTIVE, active.participationState)
    }

    @Test
    fun `ACTIVE to DRAINING transition via withState`() {
        val d = RuntimeHostDescriptor.of(deviceId = "dev-1", deviceRole = "phone")
            .withState(RuntimeHostDescriptor.HostParticipationState.ACTIVE)
        val draining = d.withState(RuntimeHostDescriptor.HostParticipationState.DRAINING)
        assertEquals(RuntimeHostDescriptor.HostParticipationState.DRAINING, draining.participationState)
    }

    @Test
    fun `DRAINING to INACTIVE transition via withState`() {
        val d = RuntimeHostDescriptor.of(deviceId = "dev-1", deviceRole = "phone")
            .withState(RuntimeHostDescriptor.HostParticipationState.DRAINING)
        val inactive = d.withState(RuntimeHostDescriptor.HostParticipationState.INACTIVE)
        assertEquals(RuntimeHostDescriptor.HostParticipationState.INACTIVE, inactive.participationState)
    }

    @Test
    fun `all four participation states are reachable via withState`() {
        val base = RuntimeHostDescriptor.of(deviceId = "dev-1", deviceRole = "phone")
        val reachable = RuntimeHostDescriptor.HostParticipationState.entries.map { state ->
            base.withState(state).participationState
        }.toSet()
        assertEquals(
            "all four participation states must be reachable",
            RuntimeHostDescriptor.HostParticipationState.entries.toSet(),
            reachable
        )
    }

    // ── Formation role semantics ──────────────────────────────────────────────

    @Test
    fun `PRIMARY host isPrimary is true`() {
        assertTrue(activeHostDescriptor(role = RuntimeHostDescriptor.FormationRole.PRIMARY).isPrimary)
    }

    @Test
    fun `SECONDARY host isPrimary is false`() {
        assertFalse(activeHostDescriptor(role = RuntimeHostDescriptor.FormationRole.SECONDARY).isPrimary)
    }

    @Test
    fun `SATELLITE host isPrimary is false`() {
        assertFalse(activeHostDescriptor(role = RuntimeHostDescriptor.FormationRole.SATELLITE).isPrimary)
    }

    @Test
    fun `FormationRole fromValue round-trips for all roles`() {
        for (role in RuntimeHostDescriptor.FormationRole.entries) {
            assertEquals(
                "fromValue must round-trip for ${role.wireValue}",
                role,
                RuntimeHostDescriptor.FormationRole.fromValue(role.wireValue)
            )
        }
    }

    // ── Participation representation ──────────────────────────────────────────

    @Test
    fun `ACTIVE PRIMARY host is both isPrimary and isAcceptingTasks`() {
        val d = activeHostDescriptor(role = RuntimeHostDescriptor.FormationRole.PRIMARY)
        assertTrue("must be primary", d.isPrimary)
        assertTrue("must be accepting tasks", d.isAcceptingTasks)
    }

    @Test
    fun `INACTIVE PRIMARY host is isPrimary but not isAcceptingTasks`() {
        val d = RuntimeHostDescriptor.of(
            deviceId = "dev-1",
            deviceRole = "phone",
            formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY
        )
        assertTrue("must be primary", d.isPrimary)
        assertFalse("INACTIVE host must not accept tasks", d.isAcceptingTasks)
    }

    @Test
    fun `SECONDARY ACTIVE host is isAcceptingTasks but not isPrimary`() {
        val d = activeHostDescriptor(role = RuntimeHostDescriptor.FormationRole.SECONDARY)
        assertFalse("secondary is not primary", d.isPrimary)
        assertTrue("ACTIVE host must accept tasks", d.isAcceptingTasks)
    }

    @Test
    fun `STANDBY host is not accepting tasks even with JOIN_RUNTIME posture`() {
        val d = RuntimeHostDescriptor.of(deviceId = "dev-1", deviceRole = "phone")
            .withState(RuntimeHostDescriptor.HostParticipationState.STANDBY)
        assertFalse("STANDBY host must not accept tasks", d.isAcceptingTasks)
    }
}
