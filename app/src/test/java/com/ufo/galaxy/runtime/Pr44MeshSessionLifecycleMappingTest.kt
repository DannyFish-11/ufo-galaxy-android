package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-44 — Mesh Session Lifecycle Mapping.
 *
 * Validates all PR-44 additions to [V2MultiDeviceLifecycleEvent]:
 *  1. [V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint] — four-value enum with stable
 *     wire values and a companion [MeshSessionLifecycleHint.ALL_WIRE_VALUES] set.
 *  2. [V2MultiDeviceLifecycleEvent.DeviceConnected.meshLifecycleHint] — computed property
 *     derived from [V2MultiDeviceLifecycleEvent.DeviceConnected.openSource].
 *  3. [V2MultiDeviceLifecycleEvent.DeviceReconnected.meshLifecycleHint] — always
 *     [MeshSessionLifecycleHint.RESTORE_ACTIVATE].
 *  4. [V2MultiDeviceLifecycleEvent.DeviceDisconnected.meshLifecycleHint] — computed property
 *     derived from [V2MultiDeviceLifecycleEvent.DeviceDisconnected.detachCause].
 *  5. [V2MultiDeviceLifecycleEvent.DeviceDisconnected.isResumable] — true iff detachCause
 *     is `"disconnect"`.
 *  6. [StabilizationBaseline] — two PR-44 entries registered as CANONICAL_STABLE.
 *  7. [V2MultiDeviceLifecycleEvent.INTRODUCED_PR_MESH_HINT] — companion constant is 44.
 *
 * ## Test matrix
 *
 * ### MeshSessionLifecycleHint — wire values
 *  - CREATE_ACTIVATE wireValue is "mesh_create_activate"
 *  - RESTORE_ACTIVATE wireValue is "mesh_restore_activate"
 *  - SUSPEND wireValue is "mesh_suspend"
 *  - TERMINATE wireValue is "mesh_terminate"
 *  - ALL_WIRE_VALUES has exactly four entries
 *  - all four wire values are distinct
 *  - fromValue returns correct enum for each wire value
 *  - fromValue returns null for unknown value
 *
 * ### MeshSessionLifecycleHint — companion constants
 *  - OPEN_SOURCE_BACKGROUND_RESTORE is "background_restore"
 *  - DETACH_CAUSE_DISCONNECT is "disconnect"
 *  - INTRODUCED_PR_MESH_HINT is 44
 *
 * ### DeviceConnected.meshLifecycleHint
 *  - user_activation → CREATE_ACTIVATE
 *  - background_restore → RESTORE_ACTIVATE
 *  - unknown openSource → CREATE_ACTIVATE (safe default)
 *
 * ### DeviceReconnected.meshLifecycleHint
 *  - always RESTORE_ACTIVATE regardless of other fields
 *
 * ### DeviceDisconnected.meshLifecycleHint
 *  - detachCause=disconnect → SUSPEND
 *  - detachCause=disable → TERMINATE
 *  - detachCause=explicit_detach → TERMINATE
 *  - detachCause=invalidation → TERMINATE
 *  - unknown detachCause → TERMINATE (safe default)
 *
 * ### DeviceDisconnected.isResumable
 *  - detachCause=disconnect → isResumable is true
 *  - detachCause=disable → isResumable is false
 *  - detachCause=explicit_detach → isResumable is false
 *  - detachCause=invalidation → isResumable is false
 *  - unknown detachCause → isResumable is false (safe default)
 *
 * ### meshLifecycleHint / isResumable consistency
 *  - SUSPEND hint always corresponds to isResumable=true
 *  - TERMINATE hint always corresponds to isResumable=false
 *
 * ### StabilizationBaseline — PR-44 entries
 *  - exactly two entries with introducedPr == 44
 *  - v2-mesh-session-lifecycle-hint is CANONICAL_STABLE with EXTEND guidance
 *  - v2-device-disconnected-is-resumable is CANONICAL_STABLE with EXTEND guidance
 *  - all PR-44 entries have non-blank rationale
 *  - all PR-44 entries reference com.ufo.galaxy packagePath
 */
class Pr44MeshSessionLifecycleMappingTest {

    // ── MeshSessionLifecycleHint — wire values ────────────────────────────────

    @Test
    fun `MeshSessionLifecycleHint CREATE_ACTIVATE wireValue is mesh_create_activate`() {
        assertEquals(
            "mesh_create_activate",
            V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.CREATE_ACTIVATE.wireValue
        )
    }

    @Test
    fun `MeshSessionLifecycleHint RESTORE_ACTIVATE wireValue is mesh_restore_activate`() {
        assertEquals(
            "mesh_restore_activate",
            V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.RESTORE_ACTIVATE.wireValue
        )
    }

    @Test
    fun `MeshSessionLifecycleHint SUSPEND wireValue is mesh_suspend`() {
        assertEquals(
            "mesh_suspend",
            V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.SUSPEND.wireValue
        )
    }

    @Test
    fun `MeshSessionLifecycleHint TERMINATE wireValue is mesh_terminate`() {
        assertEquals(
            "mesh_terminate",
            V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.TERMINATE.wireValue
        )
    }

    @Test
    fun `MeshSessionLifecycleHint ALL_WIRE_VALUES has exactly four entries`() {
        assertEquals(4, V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.ALL_WIRE_VALUES.size)
    }

    @Test
    fun `MeshSessionLifecycleHint all four wire values are distinct`() {
        val values = V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.ALL_WIRE_VALUES
        assertEquals(
            "all four mesh lifecycle hint wire values must be distinct",
            4,
            values.distinct().size
        )
    }

    @Test
    fun `MeshSessionLifecycleHint ALL_WIRE_VALUES contains all four values`() {
        val all = V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.ALL_WIRE_VALUES
        val hint = V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint
        assertTrue(all.contains(hint.CREATE_ACTIVATE.wireValue))
        assertTrue(all.contains(hint.RESTORE_ACTIVATE.wireValue))
        assertTrue(all.contains(hint.SUSPEND.wireValue))
        assertTrue(all.contains(hint.TERMINATE.wireValue))
    }

    @Test
    fun `MeshSessionLifecycleHint fromValue returns CREATE_ACTIVATE for mesh_create_activate`() {
        val result = V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.fromValue("mesh_create_activate")
        assertEquals(V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.CREATE_ACTIVATE, result)
    }

    @Test
    fun `MeshSessionLifecycleHint fromValue returns RESTORE_ACTIVATE for mesh_restore_activate`() {
        val result = V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.fromValue("mesh_restore_activate")
        assertEquals(V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.RESTORE_ACTIVATE, result)
    }

    @Test
    fun `MeshSessionLifecycleHint fromValue returns SUSPEND for mesh_suspend`() {
        val result = V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.fromValue("mesh_suspend")
        assertEquals(V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.SUSPEND, result)
    }

    @Test
    fun `MeshSessionLifecycleHint fromValue returns TERMINATE for mesh_terminate`() {
        val result = V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.fromValue("mesh_terminate")
        assertEquals(V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.TERMINATE, result)
    }

    @Test
    fun `MeshSessionLifecycleHint fromValue returns null for unknown value`() {
        val result = V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.fromValue("unknown_hint")
        assertEquals(null, result)
    }

    @Test
    fun `MeshSessionLifecycleHint fromValue returns null for null input`() {
        val result = V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.fromValue(null)
        assertEquals(null, result)
    }

    // ── Companion constants ───────────────────────────────────────────────────

    @Test
    fun `OPEN_SOURCE_BACKGROUND_RESTORE is background_restore`() {
        assertEquals("background_restore", V2MultiDeviceLifecycleEvent.OPEN_SOURCE_BACKGROUND_RESTORE)
    }

    @Test
    fun `DETACH_CAUSE_DISCONNECT is disconnect`() {
        assertEquals("disconnect", V2MultiDeviceLifecycleEvent.DETACH_CAUSE_DISCONNECT)
    }

    @Test
    fun `INTRODUCED_PR_MESH_HINT is 44`() {
        assertEquals(44, V2MultiDeviceLifecycleEvent.INTRODUCED_PR_MESH_HINT)
    }

    // ── DeviceConnected.meshLifecycleHint ─────────────────────────────────────

    @Test
    fun `DeviceConnected meshLifecycleHint is CREATE_ACTIVATE for user_activation`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceConnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            runtimeSessionId = "rt-1",
            durableSessionId = "durable-1",
            sessionContinuityEpoch = 0,
            openSource = "user_activation"
        )
        assertEquals(
            V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.CREATE_ACTIVATE,
            event.meshLifecycleHint
        )
    }

    @Test
    fun `DeviceConnected meshLifecycleHint is RESTORE_ACTIVATE for background_restore`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceConnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            runtimeSessionId = "rt-1",
            durableSessionId = "durable-1",
            sessionContinuityEpoch = 0,
            openSource = "background_restore"
        )
        assertEquals(
            V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.RESTORE_ACTIVATE,
            event.meshLifecycleHint
        )
    }

    @Test
    fun `DeviceConnected meshLifecycleHint defaults to CREATE_ACTIVATE for unknown openSource`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceConnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            runtimeSessionId = "rt-1",
            durableSessionId = null,
            sessionContinuityEpoch = 0,
            openSource = "unknown_source"
        )
        assertEquals(
            "unknown openSource must default to CREATE_ACTIVATE for safe semantics",
            V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.CREATE_ACTIVATE,
            event.meshLifecycleHint
        )
    }

    @Test
    fun `DeviceConnected user_activation meshLifecycleHint wireValue is mesh_create_activate`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceConnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            runtimeSessionId = "rt-1",
            durableSessionId = null,
            sessionContinuityEpoch = 0,
            openSource = "user_activation"
        )
        assertEquals("mesh_create_activate", event.meshLifecycleHint.wireValue)
    }

    @Test
    fun `DeviceConnected background_restore meshLifecycleHint wireValue is mesh_restore_activate`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceConnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            runtimeSessionId = "rt-1",
            durableSessionId = "durable-existing",
            sessionContinuityEpoch = 0,
            openSource = "background_restore"
        )
        assertEquals("mesh_restore_activate", event.meshLifecycleHint.wireValue)
    }

    // ── DeviceReconnected.meshLifecycleHint ───────────────────────────────────

    @Test
    fun `DeviceReconnected meshLifecycleHint is always RESTORE_ACTIVATE`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceReconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-2",
            runtimeSessionId = "rt-2",
            durableSessionId = "durable-1",
            sessionContinuityEpoch = 1
        )
        assertEquals(
            V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.RESTORE_ACTIVATE,
            event.meshLifecycleHint
        )
    }

    @Test
    fun `DeviceReconnected meshLifecycleHint wireValue is mesh_restore_activate`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceReconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-2",
            runtimeSessionId = "rt-2",
            durableSessionId = "durable-1",
            sessionContinuityEpoch = 2
        )
        assertEquals("mesh_restore_activate", event.meshLifecycleHint.wireValue)
    }

    @Test
    fun `DeviceReconnected meshLifecycleHint is RESTORE_ACTIVATE with null durableSessionId`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceReconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-3",
            runtimeSessionId = "rt-3",
            durableSessionId = null,
            sessionContinuityEpoch = 0
        )
        assertEquals(
            "RESTORE_ACTIVATE expected even when durableSessionId is null",
            V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.RESTORE_ACTIVATE,
            event.meshLifecycleHint
        )
    }

    // ── DeviceDisconnected.meshLifecycleHint ──────────────────────────────────

    @Test
    fun `DeviceDisconnected meshLifecycleHint is SUSPEND for detachCause=disconnect`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "disconnect",
            sessionDurationMs = 30_000L
        )
        assertEquals(
            V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.SUSPEND,
            event.meshLifecycleHint
        )
    }

    @Test
    fun `DeviceDisconnected meshLifecycleHint is TERMINATE for detachCause=disable`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "disable",
            sessionDurationMs = 60_000L
        )
        assertEquals(
            V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.TERMINATE,
            event.meshLifecycleHint
        )
    }

    @Test
    fun `DeviceDisconnected meshLifecycleHint is TERMINATE for detachCause=explicit_detach`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "explicit_detach",
            sessionDurationMs = 45_000L
        )
        assertEquals(
            V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.TERMINATE,
            event.meshLifecycleHint
        )
    }

    @Test
    fun `DeviceDisconnected meshLifecycleHint is TERMINATE for detachCause=invalidation`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = null,
            detachCause = "invalidation",
            sessionDurationMs = 0L
        )
        assertEquals(
            V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.TERMINATE,
            event.meshLifecycleHint
        )
    }

    @Test
    fun `DeviceDisconnected meshLifecycleHint defaults to TERMINATE for unknown detachCause`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "unknown_cause",
            sessionDurationMs = 0L
        )
        assertEquals(
            "unknown detachCause must default to TERMINATE for safe semantics",
            V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.TERMINATE,
            event.meshLifecycleHint
        )
    }

    @Test
    fun `DeviceDisconnected disconnect meshLifecycleHint wireValue is mesh_suspend`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "disconnect",
            sessionDurationMs = 10_000L
        )
        assertEquals("mesh_suspend", event.meshLifecycleHint.wireValue)
    }

    @Test
    fun `DeviceDisconnected disable meshLifecycleHint wireValue is mesh_terminate`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "disable",
            sessionDurationMs = 10_000L
        )
        assertEquals("mesh_terminate", event.meshLifecycleHint.wireValue)
    }

    // ── DeviceDisconnected.isResumable ────────────────────────────────────────

    @Test
    fun `DeviceDisconnected isResumable is true for detachCause=disconnect`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "disconnect",
            sessionDurationMs = 10_000L
        )
        assertTrue(
            "isResumable must be true for transient WS disconnect",
            event.isResumable
        )
    }

    @Test
    fun `DeviceDisconnected isResumable is false for detachCause=disable`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "disable",
            sessionDurationMs = 60_000L
        )
        assertFalse(
            "isResumable must be false for permanent disable",
            event.isResumable
        )
    }

    @Test
    fun `DeviceDisconnected isResumable is false for detachCause=explicit_detach`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "explicit_detach",
            sessionDurationMs = 45_000L
        )
        assertFalse(
            "isResumable must be false for explicit_detach",
            event.isResumable
        )
    }

    @Test
    fun `DeviceDisconnected isResumable is false for detachCause=invalidation`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = null,
            detachCause = "invalidation",
            sessionDurationMs = 0L
        )
        assertFalse(
            "isResumable must be false for invalidation",
            event.isResumable
        )
    }

    @Test
    fun `DeviceDisconnected isResumable is false for unknown detachCause`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "something_else",
            sessionDurationMs = 0L
        )
        assertFalse(
            "unknown detachCause must default isResumable to false for safe semantics",
            event.isResumable
        )
    }

    // ── meshLifecycleHint / isResumable consistency ───────────────────────────

    @Test
    fun `SUSPEND meshLifecycleHint always corresponds to isResumable=true`() {
        val suspendEvent = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "disconnect",
            sessionDurationMs = 10_000L
        )
        assertEquals(V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.SUSPEND, suspendEvent.meshLifecycleHint)
        assertTrue(
            "SUSPEND meshLifecycleHint must always correlate with isResumable=true",
            suspendEvent.isResumable
        )
    }

    @Test
    fun `TERMINATE meshLifecycleHint always corresponds to isResumable=false for disable`() {
        val terminateEvent = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "disable",
            sessionDurationMs = 60_000L
        )
        assertEquals(V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.TERMINATE, terminateEvent.meshLifecycleHint)
        assertFalse(
            "TERMINATE meshLifecycleHint must always correlate with isResumable=false",
            terminateEvent.isResumable
        )
    }

    @Test
    fun `TERMINATE meshLifecycleHint always corresponds to isResumable=false for explicit_detach`() {
        val terminateEvent = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "explicit_detach",
            sessionDurationMs = 30_000L
        )
        assertEquals(V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.TERMINATE, terminateEvent.meshLifecycleHint)
        assertFalse(terminateEvent.isResumable)
    }

    @Test
    fun `TERMINATE meshLifecycleHint always corresponds to isResumable=false for invalidation`() {
        val terminateEvent = V2MultiDeviceLifecycleEvent.DeviceDisconnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            detachCause = "invalidation",
            sessionDurationMs = 0L
        )
        assertEquals(V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.TERMINATE, terminateEvent.meshLifecycleHint)
        assertFalse(terminateEvent.isResumable)
    }

    // ── StabilizationBaseline — PR-44 entries ─────────────────────────────────

    @Test
    fun `StabilizationBaseline has exactly two PR-44 entries`() {
        val pr44Entries = StabilizationBaseline.entries.filter { it.introducedPr == 44 }
        assertEquals("exactly two PR-44 entries expected", 2, pr44Entries.size)
    }

    @Test
    fun `v2-mesh-session-lifecycle-hint is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("v2-mesh-session-lifecycle-hint")
        assertNotNull("v2-mesh-session-lifecycle-hint must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `v2-device-disconnected-is-resumable is registered as CANONICAL_STABLE`() {
        val entry = StabilizationBaseline.forId("v2-device-disconnected-is-resumable")
        assertNotNull("v2-device-disconnected-is-resumable must be registered", entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }

    @Test
    fun `all PR-44 entries have EXTEND guidance`() {
        val pr44Entries = StabilizationBaseline.entries.filter { it.introducedPr == 44 }
        pr44Entries.forEach { entry ->
            assertEquals(
                "PR-44 entry '${entry.surfaceId}' must have EXTEND guidance",
                StabilizationBaseline.ExtensionGuidance.EXTEND,
                entry.extensionGuidance
            )
        }
    }

    @Test
    fun `all PR-44 entries have non-blank rationale`() {
        val pr44Entries = StabilizationBaseline.entries.filter { it.introducedPr == 44 }
        pr44Entries.forEach { entry ->
            assertTrue(
                "PR-44 entry '${entry.surfaceId}' must have non-blank rationale",
                entry.rationale.isNotBlank()
            )
        }
    }

    @Test
    fun `all PR-44 entries reference com.ufo.galaxy packagePath`() {
        val pr44Entries = StabilizationBaseline.entries.filter { it.introducedPr == 44 }
        pr44Entries.forEach { entry ->
            assertTrue(
                "PR-44 entry '${entry.surfaceId}' must have a com.ufo.galaxy packagePath",
                entry.packagePath.startsWith("com.ufo.galaxy")
            )
        }
    }

    // ── PR-43 entries not disturbed by PR-44 ──────────────────────────────────

    @Test
    fun `PR-43 entry count is unchanged at three after PR-44 addition`() {
        val pr43Entries = StabilizationBaseline.entries.filter { it.introducedPr == 43 }
        assertEquals(
            "PR-44 must not add entries with introducedPr=43; PR-43 count must stay at 3",
            3,
            pr43Entries.size
        )
    }

    // ── DeviceConnected.processRecreatedReattachHint (PR-7/PR-F) ─────────────

    @Test
    fun `DeviceConnected processRecreatedReattachHint defaults to null`() {
        val event = V2MultiDeviceLifecycleEvent.DeviceConnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            runtimeSessionId = "rt-1",
            durableSessionId = "durable-1",
            sessionContinuityEpoch = 0,
            openSource = "user_activation"
        )
        assertTrue(
            "processRecreatedReattachHint must be null by default (fresh attach)",
            event.processRecreatedReattachHint == null
        )
    }

    @Test
    fun `DeviceConnected processRecreatedReattachHint can be set for process_recreation reattach`() {
        val hint = ProcessRecreatedReattachHint(
            priorDurableSessionId = "prior-era-id-123",
            deviceId = "Pixel_8"
        )
        val event = V2MultiDeviceLifecycleEvent.DeviceConnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            runtimeSessionId = "rt-1",
            durableSessionId = "new-era-id-456",
            sessionContinuityEpoch = 0,
            openSource = "background_restore",
            processRecreatedReattachHint = hint
        )
        assertNotNull(
            "processRecreatedReattachHint must be non-null for process-recreation re-attach",
            event.processRecreatedReattachHint
        )
        assertEquals(
            "priorDurableSessionId in hint must match expected value",
            "prior-era-id-123",
            event.processRecreatedReattachHint!!.priorDurableSessionId
        )
    }

    @Test
    fun `DeviceConnected with processRecreatedReattachHint does not affect meshLifecycleHint`() {
        val hint = ProcessRecreatedReattachHint(
            priorDurableSessionId = "prior-era-id-123",
            deviceId = "Pixel_8"
        )
        val event = V2MultiDeviceLifecycleEvent.DeviceConnected(
            deviceId = "Pixel_8",
            sessionId = "sess-1",
            runtimeSessionId = "rt-1",
            durableSessionId = "new-era-id-456",
            sessionContinuityEpoch = 0,
            openSource = "background_restore",
            processRecreatedReattachHint = hint
        )
        assertEquals(
            "meshLifecycleHint must remain RESTORE_ACTIVATE for background_restore even with hint",
            V2MultiDeviceLifecycleEvent.MeshSessionLifecycleHint.RESTORE_ACTIVATE,
            event.meshLifecycleHint
        )
    }

    @Test
    fun `DeviceConnected processRecreatedReattachHint priorDurableSessionId differs from durableSessionId`() {
        val hint = ProcessRecreatedReattachHint(
            priorDurableSessionId = "prior-era-id-OLD",
            deviceId = "Pixel_8"
        )
        val event = V2MultiDeviceLifecycleEvent.DeviceConnected(
            deviceId = "Pixel_8",
            sessionId = "sess-2",
            runtimeSessionId = "rt-2",
            durableSessionId = "new-era-id-NEW",
            sessionContinuityEpoch = 0,
            openSource = "background_restore",
            processRecreatedReattachHint = hint
        )
        assertNotNull(event.processRecreatedReattachHint)
        assertFalse(
            "priorDurableSessionId in hint must differ from the new era durableSessionId",
            event.processRecreatedReattachHint!!.priorDurableSessionId == event.durableSessionId
        )
    }
}
