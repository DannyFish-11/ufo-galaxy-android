package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [RuntimeHostDescriptor] — the canonical Android runtime-host identity
 * model introduced in PR-5 (post-#533 dual-repo runtime unification, Android side).
 *
 * ## Test matrix
 *
 * ### FormationRole enum
 *  - All three roles have distinct, stable wire values.
 *  - fromValue maps known strings to the correct enum entry.
 *  - fromValue with unknown / null input returns DEFAULT (PRIMARY).
 *
 * ### HostParticipationState enum
 *  - All four states have distinct, stable wire values.
 *  - fromValue maps known strings to the correct enum entry.
 *  - fromValue with unknown / null input returns DEFAULT (INACTIVE).
 *
 * ### [RuntimeHostDescriptor.of] factory
 *  - Generates a unique hostId when not supplied.
 *  - Uses PRIMARY as default formation role.
 *  - Uses INACTIVE as default participation state.
 *  - deviceId and deviceRole are preserved exactly.
 *
 * ### [RuntimeHostDescriptor.toMetadataMap]
 *  - Contains all four required keys.
 *  - host_id matches the descriptor's hostId.
 *  - formation_role value matches the role's wireValue.
 *  - participation_state value matches the state's wireValue.
 *  - registered_at_ms is a positive Long.
 *
 * ### [RuntimeHostDescriptor.isAcceptingTasks]
 *  - True only when participationState == ACTIVE.
 *  - False for STANDBY, DRAINING, INACTIVE.
 *
 * ### [RuntimeHostDescriptor.isPrimary]
 *  - True only when formationRole == PRIMARY.
 *  - False for SECONDARY, SATELLITE.
 *
 * ### [RuntimeHostDescriptor.withState]
 *  - Returns a new instance with the updated participationState.
 *  - All other fields are unchanged.
 *  - Original descriptor is not mutated.
 *
 * ### Metadata key constants
 *  - KEY_HOST_ID is "runtime_host_id".
 *  - KEY_FORMATION_ROLE is "runtime_host_formation_role".
 *  - KEY_PARTICIPATION_STATE is "runtime_host_participation_state".
 *  - KEY_REGISTERED_AT_MS is "runtime_host_registered_at_ms".
 */
class RuntimeHostDescriptorTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun descriptor(
        formationRole: RuntimeHostDescriptor.FormationRole = RuntimeHostDescriptor.FormationRole.PRIMARY,
        participationState: RuntimeHostDescriptor.HostParticipationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE,
        hostId: String = "test-host-id",
        deviceId: String = "test-device",
        deviceRole: String = "phone"
    ) = RuntimeHostDescriptor(
        hostId = hostId,
        deviceId = deviceId,
        deviceRole = deviceRole,
        formationRole = formationRole,
        participationState = participationState
    )

    // ── FormationRole wire values ─────────────────────────────────────────────

    @Test
    fun `FormationRole PRIMARY wireValue is primary`() {
        assertEquals("primary", RuntimeHostDescriptor.FormationRole.PRIMARY.wireValue)
    }

    @Test
    fun `FormationRole SECONDARY wireValue is secondary`() {
        assertEquals("secondary", RuntimeHostDescriptor.FormationRole.SECONDARY.wireValue)
    }

    @Test
    fun `FormationRole SATELLITE wireValue is satellite`() {
        assertEquals("satellite", RuntimeHostDescriptor.FormationRole.SATELLITE.wireValue)
    }

    @Test
    fun `FormationRole wire values are all distinct`() {
        val values = RuntimeHostDescriptor.FormationRole.entries.map { it.wireValue }
        assertEquals("wire values must be distinct", values.size, values.toSet().size)
    }

    @Test
    fun `FormationRole fromValue primary returns PRIMARY`() {
        assertEquals(
            RuntimeHostDescriptor.FormationRole.PRIMARY,
            RuntimeHostDescriptor.FormationRole.fromValue("primary")
        )
    }

    @Test
    fun `FormationRole fromValue secondary returns SECONDARY`() {
        assertEquals(
            RuntimeHostDescriptor.FormationRole.SECONDARY,
            RuntimeHostDescriptor.FormationRole.fromValue("secondary")
        )
    }

    @Test
    fun `FormationRole fromValue satellite returns SATELLITE`() {
        assertEquals(
            RuntimeHostDescriptor.FormationRole.SATELLITE,
            RuntimeHostDescriptor.FormationRole.fromValue("satellite")
        )
    }

    @Test
    fun `FormationRole fromValue unknown returns DEFAULT`() {
        assertEquals(
            RuntimeHostDescriptor.FormationRole.DEFAULT,
            RuntimeHostDescriptor.FormationRole.fromValue("unknown_role")
        )
    }

    @Test
    fun `FormationRole fromValue null returns DEFAULT`() {
        assertEquals(
            RuntimeHostDescriptor.FormationRole.DEFAULT,
            RuntimeHostDescriptor.FormationRole.fromValue(null)
        )
    }

    @Test
    fun `FormationRole DEFAULT is PRIMARY`() {
        assertEquals(
            RuntimeHostDescriptor.FormationRole.PRIMARY,
            RuntimeHostDescriptor.FormationRole.DEFAULT
        )
    }

    // ── HostParticipationState wire values ────────────────────────────────────

    @Test
    fun `HostParticipationState ACTIVE wireValue is active`() {
        assertEquals("active", RuntimeHostDescriptor.HostParticipationState.ACTIVE.wireValue)
    }

    @Test
    fun `HostParticipationState STANDBY wireValue is standby`() {
        assertEquals("standby", RuntimeHostDescriptor.HostParticipationState.STANDBY.wireValue)
    }

    @Test
    fun `HostParticipationState DRAINING wireValue is draining`() {
        assertEquals("draining", RuntimeHostDescriptor.HostParticipationState.DRAINING.wireValue)
    }

    @Test
    fun `HostParticipationState INACTIVE wireValue is inactive`() {
        assertEquals("inactive", RuntimeHostDescriptor.HostParticipationState.INACTIVE.wireValue)
    }

    @Test
    fun `HostParticipationState wire values are all distinct`() {
        val values = RuntimeHostDescriptor.HostParticipationState.entries.map { it.wireValue }
        assertEquals("wire values must be distinct", values.size, values.toSet().size)
    }

    @Test
    fun `HostParticipationState fromValue active returns ACTIVE`() {
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.ACTIVE,
            RuntimeHostDescriptor.HostParticipationState.fromValue("active")
        )
    }

    @Test
    fun `HostParticipationState fromValue standby returns STANDBY`() {
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.STANDBY,
            RuntimeHostDescriptor.HostParticipationState.fromValue("standby")
        )
    }

    @Test
    fun `HostParticipationState fromValue draining returns DRAINING`() {
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.DRAINING,
            RuntimeHostDescriptor.HostParticipationState.fromValue("draining")
        )
    }

    @Test
    fun `HostParticipationState fromValue inactive returns INACTIVE`() {
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.INACTIVE,
            RuntimeHostDescriptor.HostParticipationState.fromValue("inactive")
        )
    }

    @Test
    fun `HostParticipationState fromValue unknown returns DEFAULT`() {
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.DEFAULT,
            RuntimeHostDescriptor.HostParticipationState.fromValue("unknown_state")
        )
    }

    @Test
    fun `HostParticipationState fromValue null returns DEFAULT`() {
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.DEFAULT,
            RuntimeHostDescriptor.HostParticipationState.fromValue(null)
        )
    }

    @Test
    fun `HostParticipationState DEFAULT is INACTIVE`() {
        assertEquals(
            RuntimeHostDescriptor.HostParticipationState.INACTIVE,
            RuntimeHostDescriptor.HostParticipationState.DEFAULT
        )
    }

    // ── RuntimeHostDescriptor.of factory ─────────────────────────────────────

    @Test
    fun `of factory generates a non-blank hostId`() {
        val d = RuntimeHostDescriptor.of(deviceId = "dev-1", deviceRole = "phone")
        assertTrue("hostId must not be blank", d.hostId.isNotBlank())
    }

    @Test
    fun `of factory generates unique hostIds on repeated calls`() {
        val d1 = RuntimeHostDescriptor.of(deviceId = "dev-1", deviceRole = "phone")
        val d2 = RuntimeHostDescriptor.of(deviceId = "dev-1", deviceRole = "phone")
        assertNotEquals(
            "consecutive of() calls must produce different hostIds",
            d1.hostId, d2.hostId
        )
    }

    @Test
    fun `of factory preserves deviceId`() {
        val d = RuntimeHostDescriptor.of(deviceId = "pixel-8", deviceRole = "phone")
        assertEquals("pixel-8", d.deviceId)
    }

    @Test
    fun `of factory preserves deviceRole`() {
        val d = RuntimeHostDescriptor.of(deviceId = "dev-1", deviceRole = "tablet")
        assertEquals("tablet", d.deviceRole)
    }

    @Test
    fun `of factory defaults to PRIMARY formationRole`() {
        val d = RuntimeHostDescriptor.of(deviceId = "dev-1", deviceRole = "phone")
        assertEquals(RuntimeHostDescriptor.FormationRole.PRIMARY, d.formationRole)
    }

    @Test
    fun `of factory defaults to INACTIVE participationState`() {
        val d = RuntimeHostDescriptor.of(deviceId = "dev-1", deviceRole = "phone")
        assertEquals(RuntimeHostDescriptor.HostParticipationState.INACTIVE, d.participationState)
    }

    @Test
    fun `of factory accepts explicit formationRole`() {
        val d = RuntimeHostDescriptor.of(
            deviceId = "dev-1",
            deviceRole = "phone",
            formationRole = RuntimeHostDescriptor.FormationRole.SECONDARY
        )
        assertEquals(RuntimeHostDescriptor.FormationRole.SECONDARY, d.formationRole)
    }

    @Test
    fun `of factory accepts explicit hostId`() {
        val d = RuntimeHostDescriptor.of(
            deviceId = "dev-1",
            deviceRole = "phone",
            hostId = "fixed-host-id"
        )
        assertEquals("fixed-host-id", d.hostId)
    }

    // ── toMetadataMap ─────────────────────────────────────────────────────────

    @Test
    fun `toMetadataMap contains all four required keys`() {
        val meta = descriptor().toMetadataMap()
        assertTrue("must contain KEY_HOST_ID", meta.containsKey(RuntimeHostDescriptor.KEY_HOST_ID))
        assertTrue("must contain KEY_FORMATION_ROLE", meta.containsKey(RuntimeHostDescriptor.KEY_FORMATION_ROLE))
        assertTrue("must contain KEY_PARTICIPATION_STATE", meta.containsKey(RuntimeHostDescriptor.KEY_PARTICIPATION_STATE))
        assertTrue("must contain KEY_REGISTERED_AT_MS", meta.containsKey(RuntimeHostDescriptor.KEY_REGISTERED_AT_MS))
    }

    @Test
    fun `toMetadataMap host_id matches hostId`() {
        val d = descriptor(hostId = "h-123")
        assertEquals("h-123", d.toMetadataMap()[RuntimeHostDescriptor.KEY_HOST_ID])
    }

    @Test
    fun `toMetadataMap formation_role matches wireValue`() {
        val d = descriptor(formationRole = RuntimeHostDescriptor.FormationRole.SECONDARY)
        assertEquals("secondary", d.toMetadataMap()[RuntimeHostDescriptor.KEY_FORMATION_ROLE])
    }

    @Test
    fun `toMetadataMap participation_state matches wireValue`() {
        val d = descriptor(participationState = RuntimeHostDescriptor.HostParticipationState.STANDBY)
        assertEquals("standby", d.toMetadataMap()[RuntimeHostDescriptor.KEY_PARTICIPATION_STATE])
    }

    @Test
    fun `toMetadataMap registered_at_ms is a positive Long`() {
        val d = descriptor()
        val ts = d.toMetadataMap()[RuntimeHostDescriptor.KEY_REGISTERED_AT_MS]
        assertTrue("registered_at_ms must be a Long", ts is Long)
        assertTrue("registered_at_ms must be positive", (ts as Long) > 0L)
    }

    // ── isAcceptingTasks ──────────────────────────────────────────────────────

    @Test
    fun `isAcceptingTasks is true when participationState is ACTIVE`() {
        assertTrue(descriptor(participationState = RuntimeHostDescriptor.HostParticipationState.ACTIVE).isAcceptingTasks)
    }

    @Test
    fun `isAcceptingTasks is false when participationState is STANDBY`() {
        assertFalse(descriptor(participationState = RuntimeHostDescriptor.HostParticipationState.STANDBY).isAcceptingTasks)
    }

    @Test
    fun `isAcceptingTasks is false when participationState is DRAINING`() {
        assertFalse(descriptor(participationState = RuntimeHostDescriptor.HostParticipationState.DRAINING).isAcceptingTasks)
    }

    @Test
    fun `isAcceptingTasks is false when participationState is INACTIVE`() {
        assertFalse(descriptor(participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE).isAcceptingTasks)
    }

    // ── isPrimary ─────────────────────────────────────────────────────────────

    @Test
    fun `isPrimary is true when formationRole is PRIMARY`() {
        assertTrue(descriptor(formationRole = RuntimeHostDescriptor.FormationRole.PRIMARY).isPrimary)
    }

    @Test
    fun `isPrimary is false when formationRole is SECONDARY`() {
        assertFalse(descriptor(formationRole = RuntimeHostDescriptor.FormationRole.SECONDARY).isPrimary)
    }

    @Test
    fun `isPrimary is false when formationRole is SATELLITE`() {
        assertFalse(descriptor(formationRole = RuntimeHostDescriptor.FormationRole.SATELLITE).isPrimary)
    }

    // ── withState ─────────────────────────────────────────────────────────────

    @Test
    fun `withState returns a new descriptor with the updated state`() {
        val original = descriptor(participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE)
        val updated = original.withState(RuntimeHostDescriptor.HostParticipationState.ACTIVE)
        assertEquals(RuntimeHostDescriptor.HostParticipationState.ACTIVE, updated.participationState)
    }

    @Test
    fun `withState does not mutate the original descriptor`() {
        val original = descriptor(participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE)
        original.withState(RuntimeHostDescriptor.HostParticipationState.ACTIVE)
        assertEquals(
            "original must not be mutated",
            RuntimeHostDescriptor.HostParticipationState.INACTIVE,
            original.participationState
        )
    }

    @Test
    fun `withState preserves all other fields`() {
        val original = descriptor(
            hostId = "h-preserve",
            deviceId = "d-preserve",
            deviceRole = "tablet",
            formationRole = RuntimeHostDescriptor.FormationRole.SECONDARY,
            participationState = RuntimeHostDescriptor.HostParticipationState.INACTIVE
        )
        val updated = original.withState(RuntimeHostDescriptor.HostParticipationState.DRAINING)
        assertEquals("h-preserve", updated.hostId)
        assertEquals("d-preserve", updated.deviceId)
        assertEquals("tablet", updated.deviceRole)
        assertEquals(RuntimeHostDescriptor.FormationRole.SECONDARY, updated.formationRole)
    }

    // ── Metadata key constants ─────────────────────────────────────────────────

    @Test
    fun `KEY_HOST_ID is runtime_host_id`() {
        assertEquals("runtime_host_id", RuntimeHostDescriptor.KEY_HOST_ID)
    }

    @Test
    fun `KEY_FORMATION_ROLE is runtime_host_formation_role`() {
        assertEquals("runtime_host_formation_role", RuntimeHostDescriptor.KEY_FORMATION_ROLE)
    }

    @Test
    fun `KEY_PARTICIPATION_STATE is runtime_host_participation_state`() {
        assertEquals("runtime_host_participation_state", RuntimeHostDescriptor.KEY_PARTICIPATION_STATE)
    }

    @Test
    fun `KEY_REGISTERED_AT_MS is runtime_host_registered_at_ms`() {
        assertEquals("runtime_host_registered_at_ms", RuntimeHostDescriptor.KEY_REGISTERED_AT_MS)
    }

    // ── FormationRole enum completeness ────────────────────────────────────────

    @Test
    fun `FormationRole has exactly three entries`() {
        assertEquals(3, RuntimeHostDescriptor.FormationRole.entries.size)
    }

    // ── HostParticipationState enum completeness ──────────────────────────────

    @Test
    fun `HostParticipationState has exactly four entries`() {
        assertEquals(4, RuntimeHostDescriptor.HostParticipationState.entries.size)
    }
}
