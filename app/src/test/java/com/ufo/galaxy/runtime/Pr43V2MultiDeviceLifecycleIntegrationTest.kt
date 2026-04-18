package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-43 — V2 Multi-Device Lifecycle Integration.
 *
 * Focused acceptance test suite validating all PR-43 additions:
 *
 *  1. [V2MultiDeviceLifecycleEvent] — sealed class with stable wire values and companion constants.
 *     - [V2MultiDeviceLifecycleEvent.DeviceConnected] wire value, fields, and companion constant.
 *     - [V2MultiDeviceLifecycleEvent.DeviceReconnected] wire value and fields.
 *     - [V2MultiDeviceLifecycleEvent.DeviceDisconnected] wire value and fields.
 *     - [V2MultiDeviceLifecycleEvent.DeviceDegraded] wire value and fields.
 *     - [V2MultiDeviceLifecycleEvent.DeviceHealthChanged] wire value and fields.
 *     - [V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged] wire value and fields.
 *     - ALL_WIRE_VALUES coverage invariant.
 *     - WIRE_HEARTBEAT_MISS_UNSUPPORTED is defined but not in ALL_WIRE_VALUES.
 *     - All six wire values are distinct.
 *     - INTRODUCED_PR is 43.
 *
 *  2. [StabilizationBaseline] — PR-43 entries registered.
 *     - v2-multi-device-lifecycle-event is registered as CANONICAL_STABLE.
 *     - runtime-controller-v2-lifecycle-events is registered as CANONICAL_STABLE.
 *     - galaxy-logger-tag-v2-lifecycle is registered as CANONICAL_STABLE.
 *     - all PR-43 entries have introducedPr = 43.
 *     - all PR-43 entries have EXTEND guidance.
 *
 *  3. [com.ufo.galaxy.observability.GalaxyLogger.TAG_V2_LIFECYCLE] — log tag constant.
 *     - TAG_V2_LIFECYCLE value is "GALAXY:V2:LIFECYCLE".
 *     - TAG_V2_LIFECYCLE is distinct from all pre-PR-43 tags.
 *
 * ## Test matrix
 *
 * ### V2MultiDeviceLifecycleEvent — DeviceConnected
 *  - WIRE_DEVICE_CONNECTED is "v2_device_connected"
 *  - wireValue matches WIRE_DEVICE_CONNECTED
 *  - carries deviceId, sessionId, runtimeSessionId, durableSessionId, sessionContinuityEpoch, openSource
 *  - durableSessionId may be null
 *  - timestampMs is non-negative
 *
 * ### V2MultiDeviceLifecycleEvent — DeviceReconnected
 *  - WIRE_DEVICE_RECONNECTED is "v2_device_reconnected"
 *  - wireValue matches WIRE_DEVICE_RECONNECTED
 *  - carries deviceId, sessionId, runtimeSessionId, durableSessionId, sessionContinuityEpoch
 *  - sessionContinuityEpoch reflects incremented reconnect count
 *  - durableSessionId may be null
 *
 * ### V2MultiDeviceLifecycleEvent — DeviceDisconnected
 *  - WIRE_DEVICE_DISCONNECTED is "v2_device_disconnected"
 *  - wireValue matches WIRE_DEVICE_DISCONNECTED
 *  - carries deviceId, sessionId, detachCause, sessionDurationMs
 *  - sessionId may be null
 *  - detachCause matches DetachCause wire values
 *
 * ### V2MultiDeviceLifecycleEvent — DeviceDegraded
 *  - WIRE_DEVICE_DEGRADED is "v2_device_degraded"
 *  - wireValue matches WIRE_DEVICE_DEGRADED
 *  - carries deviceId, sessionId, degradationKind, continuationMode
 *  - sessionId may be null
 *  - degradationKind covers ws_recovering, ws_recovery_failed, health_degraded, etc.
 *
 * ### V2MultiDeviceLifecycleEvent — DeviceHealthChanged
 *  - WIRE_DEVICE_HEALTH_CHANGED is "v2_device_health_changed"
 *  - wireValue matches WIRE_DEVICE_HEALTH_CHANGED
 *  - carries deviceId, sessionId, previousHealth, currentHealth, requiresRebalance, continuationMode, trigger
 *  - requiresRebalance can be true or false
 *
 * ### V2MultiDeviceLifecycleEvent — ParticipantReadinessChanged
 *  - WIRE_PARTICIPANT_READINESS_CHANGED is "v2_participant_readiness_changed"
 *  - wireValue matches WIRE_PARTICIPANT_READINESS_CHANGED
 *  - carries deviceId, sessionId, previousReadiness, currentReadiness, previousParticipation, currentParticipation, trigger
 *  - sessionId may be null
 *
 * ### V2MultiDeviceLifecycleEvent — ALL_WIRE_VALUES
 *  - ALL_WIRE_VALUES has exactly six entries
 *  - ALL_WIRE_VALUES contains all six WIRE_* constants
 *  - WIRE_HEARTBEAT_MISS_UNSUPPORTED is NOT in ALL_WIRE_VALUES (explicitly unsupported)
 *  - all six entries are distinct strings
 *
 * ### V2MultiDeviceLifecycleEvent — companion constants
 *  - INTRODUCED_PR is 43
 *  - DESCRIPTION is non-blank
 *  - COVERAGE_INVARIANT is non-blank
 *  - WIRE_HEARTBEAT_MISS_UNSUPPORTED is non-blank
 *
 * ### StabilizationBaseline — PR-43 entries
 *  - exactly three entries with introducedPr == 43
 *  - v2-multi-device-lifecycle-event is CANONICAL_STABLE
 *  - runtime-controller-v2-lifecycle-events is CANONICAL_STABLE
 *  - galaxy-logger-tag-v2-lifecycle is CANONICAL_STABLE
 *  - all three entries have EXTEND guidance
 *  - all three entries have non-blank rationale
 *
 * ### GalaxyLogger — TAG_V2_LIFECYCLE
 *  - value is "GALAXY:V2:LIFECYCLE"
 *  - is distinct from TAG_FORMATION_REBALANCE, TAG_RUNTIME_LIFECYCLE, TAG_RECONNECT_RECOVERY
 */
class Pr43V2MultiDeviceLifecycleIntegrationTest {

    // ── Wire value constants ───────────────────────────────────────────────────

    @Test
    fun `WIRE_DEVICE_CONNECTED is v2_device_connected`() {
        assertEquals("v2_device_connected", V2MultiDeviceLifecycleEvent.WIRE_DEVICE_CONNECTED)
    }

    @Test
    fun `WIRE_DEVICE_RECONNECTED is v2_device_reconnected`() {
        assertEquals("v2_device_reconnected", V2MultiDeviceLifecycleEvent.WIRE_DEVICE_RECONNECTED)
    }

    @Test
    fun `WIRE_DEVICE_DISCONNECTED is v2_device_disconnected`() {
        assertEquals("v2_device_disconnected", V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DISCONNECTED)
    }

    @Test
    fun `WIRE_DEVICE_DEGRADED is v2_device_degraded`() {
        assertEquals("v2_device_degraded", V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DEGRADED)
    }

    @Test
    fun `WIRE_DEVICE_HEALTH_CHANGED is v2_device_health_changed`() {
        assertEquals("v2_device_health_changed", V2MultiDeviceLifecycleEvent.WIRE_DEVICE_HEALTH_CHANGED)
    }

    @Test
    fun `WIRE_PARTICIPANT_READINESS_CHANGED is v2_participant_readiness_changed`() {
        assertEquals(
            "v2_participant_readiness_changed",
            V2MultiDeviceLifecycleEvent.WIRE_PARTICIPANT_READINESS_CHANGED
        )
    }

    @Test
    fun `all six wire values are distinct`() {
        val values = V2MultiDeviceLifecycleEvent.ALL_WIRE_VALUES
        assertEquals("six distinct wire values required", 6, values.size)
    }

    // ── ALL_WIRE_VALUES coverage ───────────────────────────────────────────────

    @Test
    fun `ALL_WIRE_VALUES has exactly six entries`() {
        assertEquals(6, V2MultiDeviceLifecycleEvent.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `ALL_WIRE_VALUES contains WIRE_DEVICE_CONNECTED`() {
        assertTrue(
            V2MultiDeviceLifecycleEvent.ALL_WIRE_VALUES.contains(
                V2MultiDeviceLifecycleEvent.WIRE_DEVICE_CONNECTED
            )
        )
    }

    @Test
    fun `ALL_WIRE_VALUES contains WIRE_DEVICE_RECONNECTED`() {
        assertTrue(
            V2MultiDeviceLifecycleEvent.ALL_WIRE_VALUES.contains(
                V2MultiDeviceLifecycleEvent.WIRE_DEVICE_RECONNECTED
            )
        )
    }

    @Test
    fun `ALL_WIRE_VALUES contains WIRE_DEVICE_DISCONNECTED`() {
        assertTrue(
            V2MultiDeviceLifecycleEvent.ALL_WIRE_VALUES.contains(
                V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DISCONNECTED
            )
        )
    }

    @Test
    fun `ALL_WIRE_VALUES contains WIRE_DEVICE_DEGRADED`() {
        assertTrue(
            V2MultiDeviceLifecycleEvent.ALL_WIRE_VALUES.contains(
                V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DEGRADED
            )
        )
    }

    @Test
    fun `ALL_WIRE_VALUES contains WIRE_DEVICE_HEALTH_CHANGED`() {
        assertTrue(
            V2MultiDeviceLifecycleEvent.ALL_WIRE_VALUES.contains(
                V2MultiDeviceLifecycleEvent.WIRE_DEVICE_HEALTH_CHANGED
            )
        )
    }

    @Test
    fun `ALL_WIRE_VALUES contains WIRE_PARTICIPANT_READINESS_CHANGED`() {
        assertTrue(
            V2MultiDeviceLifecycleEvent.ALL_WIRE_VALUES.contains(
                V2MultiDeviceLifecycleEvent.WIRE_PARTICIPANT_READINESS_CHANGED
            )
        )
    }

    @Test
    fun `WIRE_HEARTBEAT_MISS_UNSUPPORTED is NOT in ALL_WIRE_VALUES`() {
        // Heartbeat-miss is explicitly unsupported; the constant is a documentation marker only.
        assertFalse(
            "heartbeat-miss must not be registered as an emitted event type",
            V2MultiDeviceLifecycleEvent.ALL_WIRE_VALUES.contains(
                V2MultiDeviceLifecycleEvent.WIRE_HEARTBEAT_MISS_UNSUPPORTED
            )
        )
    }

    // ── DeviceConnected subclass ───────────────────────────────────────────────

    @Test
    fun `DeviceConnected wireValue matches WIRE_DEVICE_CONNECTED`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceConnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            runtimeSessionId = "rt-1",
            durableSessionId = "durable-1",
            sessionContinuityEpoch = 0,
            openSource = "user_activation"
        )
        assertEquals(V2MultiDeviceLifecycleEvent.WIRE_DEVICE_CONNECTED, event.wireValue)
    }

    @Test
    fun `DeviceConnected carries all required fields`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceConnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            runtimeSessionId = "rt-abc",
            durableSessionId = "durable-xyz",
            sessionContinuityEpoch = 0,
            openSource = "user_activation",
            timestampMs = 1000L
        )
        assertEquals("Pixel_8", event.deviceId)
        assertEquals("sess-1", event.sessionId)
        assertEquals("rt-abc", event.runtimeSessionId)
        assertEquals("durable-xyz", event.durableSessionId)
        assertEquals(0, event.sessionContinuityEpoch)
        assertEquals("user_activation", event.openSource)
        assertEquals(1000L, event.timestampMs)
    }

    @Test
    fun `DeviceConnected durableSessionId may be null`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceConnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            runtimeSessionId = "rt-1",
            durableSessionId = null,
            sessionContinuityEpoch = 0,
            openSource = "background_restore"
        )
        assertNull(event.durableSessionId)
    }

    @Test
    fun `DeviceConnected openSource background_restore is valid`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceConnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            runtimeSessionId = "rt-1",
            durableSessionId = null,
            sessionContinuityEpoch = 0,
            openSource = "background_restore"
        )
        assertEquals("background_restore", event.openSource)
    }

    // ── DeviceReconnected subclass ────────────────────────────────────────────

    @Test
    fun `DeviceReconnected wireValue matches WIRE_DEVICE_RECONNECTED`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceReconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-2",
            runtimeSessionId = "rt-2",
            durableSessionId = "durable-1",
            sessionContinuityEpoch = 1
        )
        assertEquals(V2MultiDeviceLifecycleEvent.WIRE_DEVICE_RECONNECTED, event.wireValue)
    }

    @Test
    fun `DeviceReconnected sessionContinuityEpoch reflects reconnect count`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceReconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-2",
            runtimeSessionId = "rt-2",
            durableSessionId = "durable-1",
            sessionContinuityEpoch = 3
        )
        assertEquals(3, event.sessionContinuityEpoch)
    }

    @Test
    fun `DeviceReconnected durableSessionId persists from prior connect era`() {
        val durableId = "durable-era-xyz"
        val event = V2MultiDeviceLifecycleEvent.DeviceReconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-2",
            runtimeSessionId = "rt-2",
            durableSessionId = durableId,
            sessionContinuityEpoch = 2
        )
        assertEquals(durableId, event.durableSessionId)
    }

    // ── DeviceDisconnected subclass ───────────────────────────────────────────

    @Test
    fun `DeviceDisconnected wireValue matches WIRE_DEVICE_DISCONNECTED`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "disconnect",
            sessionDurationMs = 45_000L
        )
        assertEquals(V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DISCONNECTED, event.wireValue)
    }

    @Test
    fun `DeviceDisconnected sessionId may be null`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = null,
            detachCause = "disable",
            sessionDurationMs = 0L
        )
        assertNull(event.sessionId)
    }

    @Test
    fun `DeviceDisconnected detachCause disconnect maps to transient WS drop`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "disconnect",
            sessionDurationMs = 10_000L
        )
        assertEquals("disconnect", event.detachCause)
    }

    @Test
    fun `DeviceDisconnected detachCause disable maps to explicit stop`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "disable",
            sessionDurationMs = 10_000L
        )
        assertEquals("disable", event.detachCause)
    }

    @Test
    fun `DeviceDisconnected carries sessionDurationMs`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "invalidation",
            sessionDurationMs = 120_000L
        )
        assertEquals(120_000L, event.sessionDurationMs)
    }

    // ── DeviceDegraded subclass ───────────────────────────────────────────────

    @Test
    fun `DeviceDegraded wireValue matches WIRE_DEVICE_DEGRADED`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDegraded(
            deviceId = "Pixel_8",
            sessionId = null,
            degradationKind = "ws_recovering",
            continuationMode = "degraded_continuation"
        )
        assertEquals(V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DEGRADED, event.wireValue)
    }

    @Test
    fun `DeviceDegraded degradationKind ws_recovering is valid`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDegraded(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            degradationKind = "ws_recovering",
            continuationMode = "degraded_continuation"
        )
        assertEquals("ws_recovering", event.degradationKind)
    }

    @Test
    fun `DeviceDegraded degradationKind ws_recovery_failed is valid`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDegraded(
            deviceId = "Pixel_8",
            sessionId = null,
            degradationKind = "ws_recovery_failed",
            continuationMode = "withdraw_participation"
        )
        assertEquals("ws_recovery_failed", event.degradationKind)
    }

    @Test
    fun `DeviceDegraded degradationKind health_degraded is valid`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDegraded(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            degradationKind = "health_degraded",
            continuationMode = "degraded_continuation"
        )
        assertEquals("health_degraded", event.degradationKind)
    }

    @Test
    fun `DeviceDegraded degradationKind health_recovering is valid`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDegraded(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            degradationKind = "health_recovering",
            continuationMode = "await_health_restore"
        )
        assertEquals("health_recovering", event.degradationKind)
    }

    @Test
    fun `DeviceDegraded degradationKind health_failed is valid`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDegraded(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            degradationKind = "health_failed",
            continuationMode = "withdraw_participation"
        )
        assertEquals("health_failed", event.degradationKind)
    }

    @Test
    fun `DeviceDegraded sessionId may be null`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDegraded(
            deviceId = "Pixel_8",
            sessionId = null,
            degradationKind = "ws_recovering",
            continuationMode = "degraded_continuation"
        )
        assertNull(event.sessionId)
    }

    // ── DeviceHealthChanged subclass ──────────────────────────────────────────

    @Test
    fun `DeviceHealthChanged wireValue matches WIRE_DEVICE_HEALTH_CHANGED`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceHealthChanged(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            previousHealth = "unknown",
            currentHealth = "healthy",
            requiresRebalance = false,
            continuationMode = "continue_normally",
            trigger = "health_healthy"
        )
        assertEquals(V2MultiDeviceLifecycleEvent.WIRE_DEVICE_HEALTH_CHANGED, event.wireValue)
    }

    @Test
    fun `DeviceHealthChanged requiresRebalance is false for HEALTHY`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceHealthChanged(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            previousHealth = "unknown",
            currentHealth = "healthy",
            requiresRebalance = false,
            continuationMode = "continue_normally",
            trigger = "health_healthy"
        )
        assertFalse(event.requiresRebalance)
    }

    @Test
    fun `DeviceHealthChanged requiresRebalance is true for DEGRADED`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceHealthChanged(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            previousHealth = "healthy",
            currentHealth = "degraded",
            requiresRebalance = true,
            continuationMode = "degraded_continuation",
            trigger = "health_degraded"
        )
        assertTrue(event.requiresRebalance)
    }

    @Test
    fun `DeviceHealthChanged carries all required fields`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceHealthChanged(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            previousHealth = "healthy",
            currentHealth = "failed",
            requiresRebalance = true,
            continuationMode = "withdraw_participation",
            trigger = "health_failed",
            timestampMs = 5000L
        )
        assertEquals("Pixel_8", event.deviceId)
        assertEquals("sess-1", event.sessionId)
        assertEquals("healthy", event.previousHealth)
        assertEquals("failed", event.currentHealth)
        assertTrue(event.requiresRebalance)
        assertEquals("withdraw_participation", event.continuationMode)
        assertEquals("health_failed", event.trigger)
        assertEquals(5000L, event.timestampMs)
    }

    @Test
    fun `DeviceHealthChanged previousHealth reflects actual prior state for transition from healthy to degraded`() {
        // Simulate a state transition: healthy → degraded.
        // RuntimeController tracks _lastKnownHealthState; this test verifies the data model
        // correctly accepts a non-UNKNOWN previousHealth when the caller passes one.
        val event = V2MultiDeviceLifecycleEvent.DeviceHealthChanged(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            previousHealth = ParticipantHealthState.HEALTHY.wireValue,
            currentHealth = ParticipantHealthState.DEGRADED.wireValue,
            requiresRebalance = true,
            continuationMode = "degraded_continuation",
            trigger = "health_degraded"
        )
        assertEquals(ParticipantHealthState.HEALTHY.wireValue, event.previousHealth)
        assertEquals(ParticipantHealthState.DEGRADED.wireValue, event.currentHealth)
    }

    @Test
    fun `DeviceHealthChanged previousHealth is unknown for first health report`() {
        // First health report (no prior state) should carry "unknown" as previousHealth.
        val event = V2MultiDeviceLifecycleEvent.DeviceHealthChanged(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            previousHealth = ParticipantHealthState.UNKNOWN.wireValue,
            currentHealth = ParticipantHealthState.HEALTHY.wireValue,
            requiresRebalance = false,
            continuationMode = "continue_normally",
            trigger = "health_healthy"
        )
        assertEquals(ParticipantHealthState.UNKNOWN.wireValue, event.previousHealth)
    }

    // ── ParticipantReadinessChanged subclass ──────────────────────────────────

    @Test
    fun `ParticipantReadinessChanged wireValue matches WIRE_PARTICIPANT_READINESS_CHANGED`() {
        val event = V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            previousReadiness = "ready",
            currentReadiness = "not_ready",
            previousParticipation = "active",
            currentParticipation = "inactive",
            trigger = "ws_disconnect_active"
        )
        assertEquals(
            V2MultiDeviceLifecycleEvent.WIRE_PARTICIPANT_READINESS_CHANGED,
            event.wireValue
        )
    }

    @Test
    fun `ParticipantReadinessChanged sessionId may be null`() {
        val event = V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged(
            deviceId = "Pixel_8",
            sessionId = null,
            previousReadiness = "ready",
            currentReadiness = "not_ready",
            previousParticipation = "active",
            currentParticipation = "inactive",
            trigger = "ws_recovery_failed"
        )
        assertNull(event.sessionId)
    }

    @Test
    fun `ParticipantReadinessChanged carries all six semantic fields`() {
        val event = V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            previousReadiness = "ready",
            currentReadiness = "not_ready",
            previousParticipation = "active",
            currentParticipation = "standby",
            trigger = "health_state_degraded"
        )
        assertEquals("ready", event.previousReadiness)
        assertEquals("not_ready", event.currentReadiness)
        assertEquals("active", event.previousParticipation)
        assertEquals("standby", event.currentParticipation)
        assertEquals("health_state_degraded", event.trigger)
    }

    // ── Companion constants ────────────────────────────────────────────────────

    @Test
    fun `INTRODUCED_PR is 43`() {
        assertEquals(43, V2MultiDeviceLifecycleEvent.INTRODUCED_PR)
    }

    @Test
    fun `DESCRIPTION is non-blank`() {
        assertTrue(V2MultiDeviceLifecycleEvent.DESCRIPTION.isNotBlank())
    }

    @Test
    fun `COVERAGE_INVARIANT is non-blank`() {
        assertTrue(V2MultiDeviceLifecycleEvent.COVERAGE_INVARIANT.isNotBlank())
    }

    @Test
    fun `WIRE_HEARTBEAT_MISS_UNSUPPORTED is non-blank`() {
        assertTrue(V2MultiDeviceLifecycleEvent.WIRE_HEARTBEAT_MISS_UNSUPPORTED.isNotBlank())
    }

    @Test
    fun `WIRE_HEARTBEAT_MISS_UNSUPPORTED is distinct from all emitted event wire values`() {
        val emittedValues = V2MultiDeviceLifecycleEvent.ALL_WIRE_VALUES
        assertFalse(emittedValues.contains(V2MultiDeviceLifecycleEvent.WIRE_HEARTBEAT_MISS_UNSUPPORTED))
    }

    // ── V2 hook mapping completeness ───────────────────────────────────────────

    @Test
    fun `each wire value starts with v2_ prefix for namespace isolation`() {
        V2MultiDeviceLifecycleEvent.ALL_WIRE_VALUES.forEach { wireValue ->
            assertTrue(
                "wire value '$wireValue' must start with 'v2_' to avoid namespace collision",
                wireValue.startsWith("v2_")
            )
        }
    }

    @Test
    fun `DeviceConnected and DeviceReconnected have distinct wire values`() {
        assertFalse(
            V2MultiDeviceLifecycleEvent.WIRE_DEVICE_CONNECTED ==
                V2MultiDeviceLifecycleEvent.WIRE_DEVICE_RECONNECTED
        )
    }

    @Test
    fun `DeviceDisconnected and DeviceDegraded have distinct wire values`() {
        assertFalse(
            V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DISCONNECTED ==
                V2MultiDeviceLifecycleEvent.WIRE_DEVICE_DEGRADED
        )
    }

    // ── StabilizationBaseline — PR-43 entries ─────────────────────────────────

    @Test
    fun `StabilizationBaseline has exactly three PR-43 entries`() {
        val pr43Entries = StabilizationBaseline.entries.filter { it.introducedPr == 43 }
        assertEquals("exactly three PR-43 entries expected", 3, pr43Entries.size)
    }

    @Test
    fun `v2-multi-device-lifecycle-event is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("v2-multi-device-lifecycle-event")
        assertNotNull("v2-multi-device-lifecycle-event must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `runtime-controller-v2-lifecycle-events is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("runtime-controller-v2-lifecycle-events")
        assertNotNull("runtime-controller-v2-lifecycle-events must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `galaxy-logger-tag-v2-lifecycle is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("galaxy-logger-tag-v2-lifecycle")
        assertNotNull("galaxy-logger-tag-v2-lifecycle must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `all PR-43 entries have EXTEND guidance`() {
        val pr43Entries = StabilizationBaseline.entries.filter { it.introducedPr == 43 }
        pr43Entries.forEach { entry ->
            assertEquals(
                "PR-43 entry '${entry.surfaceId}' must have EXTEND guidance",
                StabilizationBaseline.ExtensionGuidance.EXTEND,
                entry.extensionGuidance
            )
        }
    }

    @Test
    fun `all PR-43 entries have non-blank rationale`() {
        val pr43Entries = StabilizationBaseline.entries.filter { it.introducedPr == 43 }
        pr43Entries.forEach { entry ->
            assertTrue(
                "PR-43 entry '${entry.surfaceId}' must have non-blank rationale",
                entry.rationale.isNotBlank()
            )
        }
    }

    @Test
    fun `all PR-43 entries reference com_ufo_galaxy packagePath`() {
        val pr43Entries = StabilizationBaseline.entries.filter { it.introducedPr == 43 }
        pr43Entries.forEach { entry ->
            assertTrue(
                "PR-43 entry '${entry.surfaceId}' must have a com.ufo.galaxy packagePath",
                entry.packagePath.startsWith("com.ufo.galaxy")
            )
        }
    }

    // ── GalaxyLogger.TAG_V2_LIFECYCLE ─────────────────────────────────────────

    @Test
    fun `TAG_V2_LIFECYCLE value is GALAXY_colon_V2_colon_LIFECYCLE`() {
        assertEquals(
            "GALAXY:V2:LIFECYCLE",
            com.ufo.galaxy.observability.GalaxyLogger.TAG_V2_LIFECYCLE
        )
    }

    @Test
    fun `TAG_V2_LIFECYCLE is distinct from TAG_FORMATION_REBALANCE`() {
        assertFalse(
            com.ufo.galaxy.observability.GalaxyLogger.TAG_V2_LIFECYCLE ==
                com.ufo.galaxy.observability.GalaxyLogger.TAG_FORMATION_REBALANCE
        )
    }

    @Test
    fun `TAG_V2_LIFECYCLE is distinct from TAG_RUNTIME_LIFECYCLE`() {
        assertFalse(
            com.ufo.galaxy.observability.GalaxyLogger.TAG_V2_LIFECYCLE ==
                com.ufo.galaxy.observability.GalaxyLogger.TAG_RUNTIME_LIFECYCLE
        )
    }

    @Test
    fun `TAG_V2_LIFECYCLE is distinct from TAG_RECONNECT_RECOVERY`() {
        assertFalse(
            com.ufo.galaxy.observability.GalaxyLogger.TAG_V2_LIFECYCLE ==
                com.ufo.galaxy.observability.GalaxyLogger.TAG_RECONNECT_RECOVERY
        )
    }

    @Test
    fun `TAG_V2_LIFECYCLE is distinct from TAG_FORMATION_HEALTH`() {
        assertFalse(
            com.ufo.galaxy.observability.GalaxyLogger.TAG_V2_LIFECYCLE ==
                com.ufo.galaxy.observability.GalaxyLogger.TAG_FORMATION_HEALTH
        )
    }
}
