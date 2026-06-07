package com.ufo.galaxy.runtime

import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.data.SharedPrefsAppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-7 — Durable Android participant identity and re-attach semantics after process recreation.
 *
 * Acceptance and regression test suite for all PR-7 surfaces:
 *
 *  1. [AppSettings.lastDurableSessionId] — persisted prior-session identity field.
 *  2. [ProcessRecreatedReattachHint] — prior-session continuity hint data class.
 *  3. [ContinuityRecoveryContext.REASON_PROCESS_RECREATION] — new interruption reason.
 *  4. [ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.PROCESS_RECREATED_REATTACH]
 *     — new attachment recovery semantics for process-recreation re-attach.
 *  5. [ParticipantAttachmentTransitionSemantics] `process_recreated_reattach` transition entry.
 *  6. [AndroidLifecycleRecoveryContract.PROCESS_RECREATED_REATTACH_NOTE] and updated
 *     [AndroidLifecycleRecoveryContract.PROCESS_RECREATION_BOUNDARY].
 *
 * ## Acceptance criteria verified by this test suite
 *
 * ### AC1: What Android participant/session identity now survives process recreation
 *   - [AppSettings.lastDurableSessionId] is persisted in SharedPreferences (survives process kill).
 *   - [PROCESS_RECREATION_BOUNDARY] survived-list now includes `lastDurableSessionId`.
 *
 * ### AC2: How Android distinguishes fresh attach vs reconnect vs re-attach
 *   - [AttachmentRecoverySemantics.FRESH_ATTACH] — true first attachment, no prior session.
 *   - [AttachmentRecoverySemantics.RECONNECT_RECOVERY] — same era, transparent WS reconnect.
 *   - [AttachmentRecoverySemantics.NEW_ERA_ATTACH] — user-initiated new era, no hint.
 *   - [AttachmentRecoverySemantics.PROCESS_RECREATED_REATTACH] — new era after process kill,
 *     with [ProcessRecreatedReattachHint] carrying the prior session ID.
 *
 * ### AC3: How process recreation affects participant continuity
 *   - Process recreation causes a new activation era (new `durableSessionId`).
 *   - But prior `durableSessionId` survives as `lastDurableSessionId` in AppSettings.
 *   - [ProcessRecreatedReattachHint.fromAppSettings] constructs the hint from persisted fields.
 *   - [ProcessRecreatedReattachHint.toMetadataMap] produces the canonical wire format with
 *     `prior_durable_session_id`, `device_id`, and `attachment_recovery_reason = "process_recreation"`.
 *
 * ### AC4: Whether Android can rejoin more like a durable participant runtime
 *   - Android presents the hint in `DeviceConnected` (not `DeviceReconnected`).
 *   - V2 decides whether to restore state — Android MUST NOT self-authorize.
 *   - [PROCESS_RECREATED_REATTACH_NOTE] documents the authority boundary.
 *
 * ## Test matrix
 *
 * ### AppSettings — lastDurableSessionId field
 *  - default lastDurableSessionId is blank
 *  - lastDurableSessionId persists value
 *  - SharedPrefsAppSettings KEY_LAST_DURABLE_SESSION_ID is "last_durable_session_id"
 *
 * ### ProcessRecreatedReattachHint — construction
 *  - fromAppSettings returns null when lastDurableSessionId is blank
 *  - fromAppSettings returns hint when lastDurableSessionId is non-blank
 *  - fromAppSettings includes correct priorDurableSessionId
 *  - fromAppSettings includes correct deviceId
 *  - default priorSessionActivationEpochMs is 0
 *  - non-zero priorSessionActivationEpochMs is preserved
 *
 * ### ProcessRecreatedReattachHint — wire format
 *  - toMetadataMap contains KEY_PRIOR_DURABLE_SESSION_ID
 *  - toMetadataMap contains KEY_DEVICE_ID
 *  - toMetadataMap contains KEY_ATTACHMENT_RECOVERY_REASON with RECOVERY_REASON_VALUE
 *  - toMetadataMap omits KEY_PRIOR_SESSION_ACTIVATION_EPOCH_MS when 0
 *  - toMetadataMap includes KEY_PRIOR_SESSION_ACTIVATION_EPOCH_MS when non-zero
 *
 * ### ProcessRecreatedReattachHint — wire key constants
 *  - KEY_PRIOR_DURABLE_SESSION_ID is "prior_durable_session_id"
 *  - KEY_DEVICE_ID is "device_id"
 *  - KEY_PRIOR_SESSION_ACTIVATION_EPOCH_MS is "prior_session_activation_epoch_ms"
 *  - KEY_ATTACHMENT_RECOVERY_REASON is "attachment_recovery_reason"
 *  - RECOVERY_REASON_VALUE is "process_recreation"
 *  - all wire key constants are distinct
 *
 * ### ContinuityRecoveryContext — REASON_PROCESS_RECREATION
 *  - REASON_PROCESS_RECREATION wire value is "process_recreation"
 *  - REASON_PROCESS_RECREATION is in KNOWN_INTERRUPTION_REASONS
 *  - isProcessRecreationReattach returns true for REASON_PROCESS_RECREATION
 *  - isProcessRecreationReattach returns false for other known reasons
 *  - isProcessRecreationReattach returns false for null
 *  - isKnownInterruptionReason returns true for REASON_PROCESS_RECREATION
 *  - KNOWN_INTERRUPTION_REASONS has 5 entries
 *
 * ### ParticipantAttachmentTransitionSemantics — PROCESS_RECREATED_REATTACH enum
 *  - PROCESS_RECREATED_REATTACH wireValue is "process_recreated_reattach"
 *  - AttachmentRecoverySemantics wireValues are distinct (6 values)
 *
 * ### ParticipantAttachmentTransitionSemantics — process_recreated_reattach transition
 *  - transition registry has 7 entries
 *  - process_recreated_reattach transition exists
 *  - process_recreated_reattach emits DeviceConnected
 *  - process_recreated_reattach has SESSION_PRESERVED durable session effect
 *  - process_recreated_reattach has PROCESS_RECREATED_REATTACH recovery semantics
 *  - process_recreated_reattach has non-blank rationale
 *  - processRecreatedReattachTransitions contains process_recreated_reattach
 *  - recoveryTransitions contains process_recreated_reattach
 *  - transitionsEmitting DeviceConnected contains process_recreated_reattach
 *
 * ### AndroidLifecycleRecoveryContract — updated process recreation boundary
 *  - PROCESS_RECREATION_BOUNDARY survived list includes lastDurableSessionId
 *  - PROCESS_RECREATED_REATTACH_NOTE is non-blank
 *  - PROCESS_RECREATED_REATTACH_NOTE mentions prior_durable_session_id or ProcessRecreatedReattachHint
 */
class Pr7DurableParticipantIdentityReattachTest {

    // ═══════════════════════════════════════════════════════════════════════
    // AppSettings — lastDurableSessionId field
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `default lastDurableSessionId is blank`() {
        val settings = InMemoryAppSettings()
        assertTrue(
            "Default lastDurableSessionId must be blank",
            settings.lastDurableSessionId.isBlank()
        )
    }

    @Test
    fun `lastDurableSessionId persists value`() {
        val settings = InMemoryAppSettings()
        val priorId = "prior-era-uuid-1234"
        settings.lastDurableSessionId = priorId
        assertEquals(priorId, settings.lastDurableSessionId)
    }

    @Test
    fun `SharedPrefsAppSettings KEY_LAST_DURABLE_SESSION_ID is stable`() {
        assertEquals(
            "KEY_LAST_DURABLE_SESSION_ID must be stable wire key 'last_durable_session_id'",
            "last_durable_session_id",
            SharedPrefsAppSettings.KEY_LAST_DURABLE_SESSION_ID
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ProcessRecreatedReattachHint — construction
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `fromAppSettings returns null when lastDurableSessionId is blank`() {
        val settings = InMemoryAppSettings()
        // lastDurableSessionId defaults to blank
        assertNull(
            "fromAppSettings must return null when lastDurableSessionId is blank",
            ProcessRecreatedReattachHint.fromAppSettings(settings)
        )
    }

    @Test
    fun `fromAppSettings returns null when lastDurableSessionId is empty string`() {
        val settings = InMemoryAppSettings(lastDurableSessionId = "")
        assertNull(ProcessRecreatedReattachHint.fromAppSettings(settings))
    }

    @Test
    fun `fromAppSettings returns hint when lastDurableSessionId is non-blank`() {
        val settings = InMemoryAppSettings(lastDurableSessionId = "session-abc-123")
        val hint = ProcessRecreatedReattachHint.fromAppSettings(settings)
        assertNotNull("fromAppSettings must return non-null hint when lastDurableSessionId is set", hint)
    }

    @Test
    fun `fromAppSettings includes correct priorDurableSessionId`() {
        val priorId = "prior-session-xyz"
        val settings = InMemoryAppSettings(lastDurableSessionId = priorId)
        val hint = ProcessRecreatedReattachHint.fromAppSettings(settings)!!
        assertEquals(priorId, hint.priorDurableSessionId)
    }

    @Test
    fun `fromAppSettings includes correct deviceId`() {
        val deviceId = "my-android-phone"
        val settings = InMemoryAppSettings(
            lastDurableSessionId = "some-prior-id",
            deviceId = deviceId
        )
        val hint = ProcessRecreatedReattachHint.fromAppSettings(settings)!!
        assertEquals(deviceId, hint.deviceId)
    }

    @Test
    fun `default priorSessionActivationEpochMs is 0`() {
        val hint = ProcessRecreatedReattachHint(
            priorDurableSessionId = "session-id",
            deviceId = "device-id"
        )
        assertEquals(0L, hint.priorSessionActivationEpochMs)
    }

    @Test
    fun `non-zero priorSessionActivationEpochMs is preserved`() {
        val ts = 1_700_000_000_000L
        val hint = ProcessRecreatedReattachHint(
            priorDurableSessionId = "session-id",
            deviceId = "device-id",
            priorSessionActivationEpochMs = ts
        )
        assertEquals(ts, hint.priorSessionActivationEpochMs)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ProcessRecreatedReattachHint — wire format
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `toMetadataMap contains KEY_PRIOR_DURABLE_SESSION_ID`() {
        val priorId = "prior-session-id"
        val hint = ProcessRecreatedReattachHint(priorDurableSessionId = priorId, deviceId = "dev")
        val map = hint.toMetadataMap()
        assertTrue(map.containsKey(ProcessRecreatedReattachHint.KEY_PRIOR_DURABLE_SESSION_ID))
        assertEquals(priorId, map[ProcessRecreatedReattachHint.KEY_PRIOR_DURABLE_SESSION_ID])
    }

    @Test
    fun `toMetadataMap contains KEY_DEVICE_ID`() {
        val deviceId = "test-device"
        val hint = ProcessRecreatedReattachHint(priorDurableSessionId = "p-id", deviceId = deviceId)
        val map = hint.toMetadataMap()
        assertTrue(map.containsKey(ProcessRecreatedReattachHint.KEY_DEVICE_ID))
        assertEquals(deviceId, map[ProcessRecreatedReattachHint.KEY_DEVICE_ID])
    }

    @Test
    fun `toMetadataMap contains KEY_ATTACHMENT_RECOVERY_REASON with RECOVERY_REASON_VALUE`() {
        val hint = ProcessRecreatedReattachHint(priorDurableSessionId = "p-id", deviceId = "dev")
        val map = hint.toMetadataMap()
        assertTrue(map.containsKey(ProcessRecreatedReattachHint.KEY_ATTACHMENT_RECOVERY_REASON))
        assertEquals(
            ProcessRecreatedReattachHint.RECOVERY_REASON_VALUE,
            map[ProcessRecreatedReattachHint.KEY_ATTACHMENT_RECOVERY_REASON]
        )
    }

    @Test
    fun `toMetadataMap omits KEY_PRIOR_SESSION_ACTIVATION_EPOCH_MS when 0`() {
        val hint = ProcessRecreatedReattachHint(
            priorDurableSessionId = "p-id",
            deviceId = "dev",
            priorSessionActivationEpochMs = 0L
        )
        val map = hint.toMetadataMap()
        assertFalse(map.containsKey(ProcessRecreatedReattachHint.KEY_PRIOR_SESSION_ACTIVATION_EPOCH_MS))
    }

    @Test
    fun `toMetadataMap includes KEY_PRIOR_SESSION_ACTIVATION_EPOCH_MS when non-zero`() {
        val ts = 1_700_000_000_000L
        val hint = ProcessRecreatedReattachHint(
            priorDurableSessionId = "p-id",
            deviceId = "dev",
            priorSessionActivationEpochMs = ts
        )
        val map = hint.toMetadataMap()
        assertTrue(map.containsKey(ProcessRecreatedReattachHint.KEY_PRIOR_SESSION_ACTIVATION_EPOCH_MS))
        assertEquals(ts, map[ProcessRecreatedReattachHint.KEY_PRIOR_SESSION_ACTIVATION_EPOCH_MS])
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ProcessRecreatedReattachHint — wire key constants
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `KEY_PRIOR_DURABLE_SESSION_ID is prior_durable_session_id`() {
        assertEquals(
            "prior_durable_session_id",
            ProcessRecreatedReattachHint.KEY_PRIOR_DURABLE_SESSION_ID
        )
    }

    @Test
    fun `KEY_DEVICE_ID is device_id`() {
        assertEquals("device_id", ProcessRecreatedReattachHint.KEY_DEVICE_ID)
    }

    @Test
    fun `KEY_PRIOR_SESSION_ACTIVATION_EPOCH_MS is prior_session_activation_epoch_ms`() {
        assertEquals(
            "prior_session_activation_epoch_ms",
            ProcessRecreatedReattachHint.KEY_PRIOR_SESSION_ACTIVATION_EPOCH_MS
        )
    }

    @Test
    fun `KEY_ATTACHMENT_RECOVERY_REASON is attachment_recovery_reason`() {
        assertEquals(
            "attachment_recovery_reason",
            ProcessRecreatedReattachHint.KEY_ATTACHMENT_RECOVERY_REASON
        )
    }

    @Test
    fun `RECOVERY_REASON_VALUE is process_recreation`() {
        assertEquals("process_recreation", ProcessRecreatedReattachHint.RECOVERY_REASON_VALUE)
    }

    @Test
    fun `ProcessRecreatedReattachHint wire key constants are distinct`() {
        val keys = setOf(
            ProcessRecreatedReattachHint.KEY_PRIOR_DURABLE_SESSION_ID,
            ProcessRecreatedReattachHint.KEY_DEVICE_ID,
            ProcessRecreatedReattachHint.KEY_PRIOR_SESSION_ACTIVATION_EPOCH_MS,
            ProcessRecreatedReattachHint.KEY_ATTACHMENT_RECOVERY_REASON
        )
        assertEquals(4, keys.size)
    }

    @Test
    fun `RECOVERY_REASON_VALUE matches ContinuityRecoveryContext REASON_PROCESS_RECREATION`() {
        assertEquals(
            "RECOVERY_REASON_VALUE must match REASON_PROCESS_RECREATION for consistent wire encoding",
            ContinuityRecoveryContext.REASON_PROCESS_RECREATION,
            ProcessRecreatedReattachHint.RECOVERY_REASON_VALUE
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ContinuityRecoveryContext — REASON_PROCESS_RECREATION
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `REASON_PROCESS_RECREATION wire value is process_recreation`() {
        assertEquals(
            "REASON_PROCESS_RECREATION must be the stable wire value 'process_recreation'",
            "process_recreation",
            ContinuityRecoveryContext.REASON_PROCESS_RECREATION
        )
    }

    @Test
    fun `REASON_PROCESS_RECREATION is in KNOWN_INTERRUPTION_REASONS`() {
        assertTrue(
            ContinuityRecoveryContext.KNOWN_INTERRUPTION_REASONS.contains(
                ContinuityRecoveryContext.REASON_PROCESS_RECREATION
            )
        )
    }

    @Test
    fun `isProcessRecreationReattach returns true for REASON_PROCESS_RECREATION`() {
        assertTrue(
            ContinuityRecoveryContext.isProcessRecreationReattach(
                ContinuityRecoveryContext.REASON_PROCESS_RECREATION
            )
        )
    }

    @Test
    fun `isProcessRecreationReattach returns false for REASON_RECONNECT`() {
        assertFalse(
            ContinuityRecoveryContext.isProcessRecreationReattach(
                ContinuityRecoveryContext.REASON_RECONNECT
            )
        )
    }

    @Test
    fun `isProcessRecreationReattach returns false for REASON_HANDOFF`() {
        assertFalse(
            ContinuityRecoveryContext.isProcessRecreationReattach(
                ContinuityRecoveryContext.REASON_HANDOFF
            )
        )
    }

    @Test
    fun `isProcessRecreationReattach returns false for null`() {
        assertFalse(ContinuityRecoveryContext.isProcessRecreationReattach(null))
    }

    @Test
    fun `isProcessRecreationReattach returns false for unknown reason`() {
        assertFalse(ContinuityRecoveryContext.isProcessRecreationReattach("unknown_reason"))
    }

    @Test
    fun `isKnownInterruptionReason returns true for REASON_PROCESS_RECREATION`() {
        assertTrue(
            ContinuityRecoveryContext.isKnownInterruptionReason(
                ContinuityRecoveryContext.REASON_PROCESS_RECREATION
            )
        )
    }

    @Test
    fun `KNOWN_INTERRUPTION_REASONS has 5 entries`() {
        assertEquals(
            "KNOWN_INTERRUPTION_REASONS must include all 5 known reasons after PR-7",
            5,
            ContinuityRecoveryContext.KNOWN_INTERRUPTION_REASONS.size
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantAttachmentTransitionSemantics — PROCESS_RECREATED_REATTACH enum
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `PROCESS_RECREATED_REATTACH wireValue is process_recreated_reattach`() {
        assertEquals(
            "process_recreated_reattach",
            ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics
                .PROCESS_RECREATED_REATTACH.wireValue
        )
    }

    @Test
    fun `AttachmentRecoverySemantics has 6 distinct wireValues after PR-7`() {
        val wireValues = ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics
            .entries.map { it.wireValue }
        assertEquals(
            "AttachmentRecoverySemantics should have 6 values after PR-7 adds PROCESS_RECREATED_REATTACH",
            6,
            wireValues.size
        )
        assertEquals(wireValues.size, wireValues.toSet().size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ParticipantAttachmentTransitionSemantics — process_recreated_reattach transition
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `transition registry has 7 entries after PR-7`() {
        assertEquals(
            "transitionCount must be 7 after PR-7 adds process_recreated_reattach",
            7,
            ParticipantAttachmentTransitionSemantics.transitionCount
        )
        assertEquals(7, ParticipantAttachmentTransitionSemantics.transitions.size)
    }

    @Test
    fun `process_recreated_reattach transition exists`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("process_recreated_reattach")
        assertNotNull("process_recreated_reattach transition must exist in registry", t)
    }

    @Test
    fun `process_recreated_reattach emits DeviceConnected`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("process_recreated_reattach")!!
        assertEquals(
            "process_recreated_reattach must emit DeviceConnected (not DeviceReconnected)",
            "DeviceConnected",
            t.v2EventEmitted
        )
    }

    @Test
    fun `process_recreated_reattach has SESSION_PRESERVED durable session effect`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("process_recreated_reattach")!!
        assertEquals(
            ParticipantAttachmentTransitionSemantics.DurableSessionEffect.SESSION_PRESERVED,
            t.durableSessionEffect
        )
    }

    @Test
    fun `process_recreated_reattach has PROCESS_RECREATED_REATTACH recovery semantics`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("process_recreated_reattach")!!
        assertEquals(
            ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.PROCESS_RECREATED_REATTACH,
            t.recoverySemantics
        )
    }

    @Test
    fun `process_recreated_reattach fromState is UNATTACHED`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("process_recreated_reattach")!!
        assertEquals(
            ParticipantAttachmentTransitionSemantics.AttachmentState.UNATTACHED,
            t.fromState
        )
    }

    @Test
    fun `process_recreated_reattach toState is ATTACHED`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("process_recreated_reattach")!!
        assertEquals(
            ParticipantAttachmentTransitionSemantics.AttachmentState.ATTACHED,
            t.toState
        )
    }

    @Test
    fun `process_recreated_reattach has non-blank rationale`() {
        val t = ParticipantAttachmentTransitionSemantics.transitionFor("process_recreated_reattach")!!
        assertTrue("process_recreated_reattach must have a non-blank rationale", t.rationale.isNotBlank())
    }

    @Test
    fun `processRecreatedReattachTransitions contains process_recreated_reattach`() {
        val ids = ParticipantAttachmentTransitionSemantics.processRecreatedReattachTransitions
            .map { it.transitionId }
        assertTrue(ids.contains("process_recreated_reattach"))
    }

    @Test
    fun `processRecreatedReattachTransitions has exactly 1 entry`() {
        assertEquals(
            1,
            ParticipantAttachmentTransitionSemantics.processRecreatedReattachTransitions.size
        )
    }

    @Test
    fun `recoveryTransitions contains process_recreated_reattach`() {
        val ids = ParticipantAttachmentTransitionSemantics.recoveryTransitions
            .map { it.transitionId }
        assertTrue(
            "recoveryTransitions must include process_recreated_reattach",
            ids.contains("process_recreated_reattach")
        )
    }

    @Test
    fun `recoveryTransitions has 3 entries after PR-7`() {
        assertEquals(
            "recoveryTransitions must have 3 entries: reconnect_recovery_attach, new_era_attach, process_recreated_reattach",
            3,
            ParticipantAttachmentTransitionSemantics.recoveryTransitions.size
        )
    }

    @Test
    fun `transitionsEmitting DeviceConnected contains process_recreated_reattach`() {
        val ids = ParticipantAttachmentTransitionSemantics.transitionsEmitting("DeviceConnected")
            .map { it.transitionId }
        assertTrue(
            "transitionsEmitting DeviceConnected must include process_recreated_reattach",
            ids.contains("process_recreated_reattach")
        )
    }

    @Test
    fun `transitionsEmitting DeviceConnected has 3 entries after PR-7`() {
        val ids = ParticipantAttachmentTransitionSemantics.transitionsEmitting("DeviceConnected")
            .map { it.transitionId }
        assertEquals(
            "DeviceConnected should be emitted by 3 transitions: initial_attach, new_era_attach, process_recreated_reattach",
            3,
            ids.size
        )
    }

    @Test
    fun `all 7 transition IDs are distinct`() {
        val ids = ParticipantAttachmentTransitionSemantics.transitions.map { it.transitionId }
        assertEquals(ids.size, ids.toSet().size)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Distinction between attach types
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `FRESH_ATTACH and PROCESS_RECREATED_REATTACH have distinct wire values`() {
        assertFalse(
            ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.FRESH_ATTACH.wireValue ==
                ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.PROCESS_RECREATED_REATTACH.wireValue
        )
    }

    @Test
    fun `NEW_ERA_ATTACH and PROCESS_RECREATED_REATTACH have distinct wire values`() {
        assertFalse(
            ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.NEW_ERA_ATTACH.wireValue ==
                ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.PROCESS_RECREATED_REATTACH.wireValue
        )
    }

    @Test
    fun `RECONNECT_RECOVERY and PROCESS_RECREATED_REATTACH have distinct wire values`() {
        assertFalse(
            ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.RECONNECT_RECOVERY.wireValue ==
                ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.PROCESS_RECREATED_REATTACH.wireValue
        )
    }

    @Test
    fun `initial_attach and process_recreated_reattach have different recovery semantics`() {
        val initialAttach = ParticipantAttachmentTransitionSemantics.transitionFor("initial_attach")!!
        val processRecreated = ParticipantAttachmentTransitionSemantics.transitionFor("process_recreated_reattach")!!
        assertFalse(initialAttach.recoverySemantics == processRecreated.recoverySemantics)
    }

    @Test
    fun `new_era_attach and process_recreated_reattach have different recovery semantics`() {
        val newEraAttach = ParticipantAttachmentTransitionSemantics.transitionFor("new_era_attach")!!
        val processRecreated = ParticipantAttachmentTransitionSemantics.transitionFor("process_recreated_reattach")!!
        assertFalse(newEraAttach.recoverySemantics == processRecreated.recoverySemantics)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AndroidLifecycleRecoveryContract — updated process recreation boundary
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `PROCESS_RECREATION_BOUNDARY survived list includes lastDurableSessionId`() {
        val survived = AndroidLifecycleRecoveryContract.PROCESS_RECREATION_BOUNDARY[
            AndroidLifecycleRecoveryContract.SURVIVES_PROCESS_RECREATION
        ] ?: emptyList()
        assertTrue(
            "PROCESS_RECREATION_BOUNDARY survived list must include lastDurableSessionId after PR-7",
            survived.any { it.contains("lastDurableSessionId") }
        )
    }

    @Test
    fun `PROCESS_RECREATED_REATTACH_NOTE is non-blank`() {
        assertTrue(AndroidLifecycleRecoveryContract.PROCESS_RECREATED_REATTACH_NOTE.isNotBlank())
    }

    @Test
    fun `PROCESS_RECREATED_REATTACH_NOTE mentions ProcessRecreatedReattachHint or prior_durable_session_id`() {
        val note = AndroidLifecycleRecoveryContract.PROCESS_RECREATED_REATTACH_NOTE
        assertTrue(
            "PROCESS_RECREATED_REATTACH_NOTE must mention the key concept",
            note.contains("ProcessRecreatedReattachHint") || note.contains("prior_durable_session_id")
        )
    }

    @Test
    fun `PROCESS_RECREATION_BOUNDARY lost list mentions DurableSessionContinuityRecord hint`() {
        val lost = AndroidLifecycleRecoveryContract.PROCESS_RECREATION_BOUNDARY[
            AndroidLifecycleRecoveryContract.LOST_ON_PROCESS_RECREATION
        ] ?: emptyList()
        assertTrue(
            "PROCESS_RECREATION_BOUNDARY lost list should mention DurableSessionContinuityRecord",
            lost.any { it.contains("DurableSessionContinuityRecord") }
        )
    }
}
