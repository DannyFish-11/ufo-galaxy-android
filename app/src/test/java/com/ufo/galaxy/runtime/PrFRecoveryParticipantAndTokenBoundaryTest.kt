package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR-F / PR-04 — Recovery participant role and token boundary semantics.
 *
 * Validates the four key requirements introduced by the problem statement:
 *
 * 1. **Android明确为recovery participant** — [ContinuityRecoveryContext.ANDROID_RECOVERY_ROLE]
 *    is defined, stable, and distinct from any coordinator role.
 *
 * 2. **reconnect状态与continuity输出一致** — [ReconnectRecoveryState.RECOVERED] wire value
 *    semantics imply the continuity epoch has already been updated; the ordering guarantee
 *    documented in [ReconnectRecoveryState] and [RuntimeController] is enforced.
 *
 * 3. **区分continuity_token与continuation_token** — [ContinuityRecoveryContext.CONTINUITY_TOKEN_WIRE_FIELD]
 *    and [ContinuityRecoveryContext.CONTINUATION_TOKEN_WIRE_FIELD] are distinct, stable
 *    wire-field names with non-overlapping semantics.
 *
 * 4. **明确durable continuity与其他session identity的边界** — [DurableSessionContinuityRecord]
 *    boundary constants correctly describe the scope of each identifier tier.
 *
 * ## Test matrix
 *
 * ### Android recovery participant role
 *  - ANDROID_RECOVERY_ROLE wire value is "recovery_participant"
 *  - isRecoveryParticipant returns true for ANDROID_RECOVERY_ROLE
 *  - isRecoveryParticipant returns false for null
 *  - isRecoveryParticipant returns false for other strings
 *  - ANDROID_RECOVERY_ROLE does not equal "recovery_coordinator"
 *  - ANDROID_RECOVERY_ROLE does not equal "coordinator"
 *
 * ### Token boundary: CONTINUITY_TOKEN_WIRE_FIELD
 *  - CONTINUITY_TOKEN_WIRE_FIELD wire value is "continuity_token"
 *  - CONTINUITY_TOKEN_WIRE_FIELD is distinct from CONTINUATION_TOKEN_WIRE_FIELD
 *
 * ### Token boundary: CONTINUATION_TOKEN_WIRE_FIELD
 *  - CONTINUATION_TOKEN_WIRE_FIELD wire value is "continuation_token"
 *  - CONTINUATION_TOKEN_WIRE_FIELD is distinct from CONTINUITY_TOKEN_WIRE_FIELD
 *
 * ### isContinuityOnlyInterruption
 *  - reconnect is a continuity-only interruption (no continuation_token expected)
 *  - transport_degraded is a continuity-only interruption
 *  - handoff is NOT a continuity-only interruption (continuation_token may be present)
 *  - device_pause is NOT a continuity-only interruption
 *  - null is NOT a continuity-only interruption
 *
 * ### ReconnectRecoveryState — continuity consistency semantics
 *  - RECOVERED wire value is stable ("recovered")
 *  - RECOVERED is distinct from RECOVERING and FAILED
 *  - all four states have distinct wire values
 *
 * ### DurableSessionContinuityRecord — session identity boundary
 *  - KEY_DURABLE_SESSION_ID wire key is "durable_session_id"
 *  - KEY_SESSION_CONTINUITY_EPOCH wire key is "session_continuity_epoch"
 *  - durableSessionId is preserved across withEpochIncremented
 *  - sessionContinuityEpoch is incremented by withEpochIncremented
 *  - create() starts at epoch 0
 *  - withEpochIncremented increments epoch by exactly 1
 *  - multiple successive withEpochIncremented calls accumulate correctly
 */
class PrFRecoveryParticipantAndTokenBoundaryTest {

    // ── Android recovery participant role ─────────────────────────────────────

    @Test
    fun `ANDROID_RECOVERY_ROLE wire value is recovery_participant`() {
        assertEquals(
            "ANDROID_RECOVERY_ROLE must be the stable wire value 'recovery_participant'",
            "recovery_participant",
            ContinuityRecoveryContext.ANDROID_RECOVERY_ROLE
        )
    }

    @Test
    fun `isRecoveryParticipant returns true for ANDROID_RECOVERY_ROLE`() {
        assertTrue(
            "isRecoveryParticipant must return true for the canonical participant role constant",
            ContinuityRecoveryContext.isRecoveryParticipant(ContinuityRecoveryContext.ANDROID_RECOVERY_ROLE)
        )
    }

    @Test
    fun `isRecoveryParticipant returns false for null`() {
        assertFalse(
            "isRecoveryParticipant must return false for null — unset / legacy role",
            ContinuityRecoveryContext.isRecoveryParticipant(null)
        )
    }

    @Test
    fun `isRecoveryParticipant returns false for arbitrary string`() {
        assertFalse(
            "isRecoveryParticipant must return false for an unrecognised role string",
            ContinuityRecoveryContext.isRecoveryParticipant("some_other_role")
        )
    }

    @Test
    fun `ANDROID_RECOVERY_ROLE is not recovery_coordinator`() {
        assertNotEquals(
            "Android is a participant — the role must not accidentally match 'recovery_coordinator'",
            "recovery_coordinator",
            ContinuityRecoveryContext.ANDROID_RECOVERY_ROLE
        )
    }

    @Test
    fun `ANDROID_RECOVERY_ROLE is not coordinator`() {
        assertNotEquals(
            "Android is a participant — the role must not accidentally match 'coordinator'",
            "coordinator",
            ContinuityRecoveryContext.ANDROID_RECOVERY_ROLE
        )
    }

    // ── Token boundary: CONTINUITY_TOKEN_WIRE_FIELD ───────────────────────────

    @Test
    fun `CONTINUITY_TOKEN_WIRE_FIELD wire value is continuity_token`() {
        assertEquals(
            "CONTINUITY_TOKEN_WIRE_FIELD must be the stable wire field name 'continuity_token'",
            "continuity_token",
            ContinuityRecoveryContext.CONTINUITY_TOKEN_WIRE_FIELD
        )
    }

    @Test
    fun `CONTINUITY_TOKEN_WIRE_FIELD is distinct from CONTINUATION_TOKEN_WIRE_FIELD`() {
        assertNotEquals(
            "continuity_token (V2-managed durable token) and continuation_token " +
                "(executor-managed handoff state token) must have different wire field names",
            ContinuityRecoveryContext.CONTINUATION_TOKEN_WIRE_FIELD,
            ContinuityRecoveryContext.CONTINUITY_TOKEN_WIRE_FIELD
        )
    }

    // ── Token boundary: CONTINUATION_TOKEN_WIRE_FIELD ────────────────────────

    @Test
    fun `CONTINUATION_TOKEN_WIRE_FIELD wire value is continuation_token`() {
        assertEquals(
            "CONTINUATION_TOKEN_WIRE_FIELD must be the stable wire field name 'continuation_token'",
            "continuation_token",
            ContinuityRecoveryContext.CONTINUATION_TOKEN_WIRE_FIELD
        )
    }

    @Test
    fun `CONTINUATION_TOKEN_WIRE_FIELD is distinct from CONTINUITY_TOKEN_WIRE_FIELD`() {
        assertNotEquals(
            "continuation_token (handoff state) and continuity_token (durable context) " +
                "must have different wire field names",
            ContinuityRecoveryContext.CONTINUITY_TOKEN_WIRE_FIELD,
            ContinuityRecoveryContext.CONTINUATION_TOKEN_WIRE_FIELD
        )
    }

    // ── isContinuityOnlyInterruption ──────────────────────────────────────────

    @Test
    fun `isContinuityOnlyInterruption returns true for reconnect`() {
        assertTrue(
            "reconnect dispatches should not carry a continuation_token — only continuity_token applies",
            ContinuityRecoveryContext.isContinuityOnlyInterruption(ContinuityRecoveryContext.REASON_RECONNECT)
        )
    }

    @Test
    fun `isContinuityOnlyInterruption returns true for transport_degraded`() {
        assertTrue(
            "transport_degraded dispatches should not carry a continuation_token — only continuity_token applies",
            ContinuityRecoveryContext.isContinuityOnlyInterruption(ContinuityRecoveryContext.REASON_TRANSPORT_DEGRADED)
        )
    }

    @Test
    fun `isContinuityOnlyInterruption returns false for handoff`() {
        assertFalse(
            "handoff dispatches may carry a continuation_token from the originating executor",
            ContinuityRecoveryContext.isContinuityOnlyInterruption(ContinuityRecoveryContext.REASON_HANDOFF)
        )
    }

    @Test
    fun `isContinuityOnlyInterruption returns false for device_pause`() {
        assertFalse(
            "device_pause dispatches may carry a continuation_token",
            ContinuityRecoveryContext.isContinuityOnlyInterruption(ContinuityRecoveryContext.REASON_DEVICE_PAUSE)
        )
    }

    @Test
    fun `isContinuityOnlyInterruption returns false for null`() {
        assertFalse(
            "null reason is not a continuity-only interruption (legacy / non-recovery dispatch)",
            ContinuityRecoveryContext.isContinuityOnlyInterruption(null)
        )
    }

    // ── ReconnectRecoveryState — continuity consistency semantics ─────────────

    @Test
    fun `RECOVERED wire value is stable`() {
        assertEquals(
            "RECOVERED wire value must be 'recovered' — changing it would break V2 contract",
            "recovered",
            ReconnectRecoveryState.RECOVERED.wireValue
        )
    }

    @Test
    fun `RECOVERED is distinct from RECOVERING`() {
        assertNotEquals(
            "RECOVERED and RECOVERING must have different wire values to avoid ambiguity",
            ReconnectRecoveryState.RECOVERING.wireValue,
            ReconnectRecoveryState.RECOVERED.wireValue
        )
    }

    @Test
    fun `RECOVERED is distinct from FAILED`() {
        assertNotEquals(
            "RECOVERED and FAILED must have different wire values",
            ReconnectRecoveryState.FAILED.wireValue,
            ReconnectRecoveryState.RECOVERED.wireValue
        )
    }

    @Test
    fun `all four ReconnectRecoveryState wire values are distinct`() {
        val wireValues = ReconnectRecoveryState.entries.map { it.wireValue }.toSet()
        assertEquals(
            "All four ReconnectRecoveryState enum entries must have distinct wire values",
            ReconnectRecoveryState.entries.size,
            wireValues.size
        )
    }

    // ── DurableSessionContinuityRecord — session identity boundary ────────────

    @Test
    fun `KEY_DURABLE_SESSION_ID wire key is durable_session_id`() {
        assertEquals(
            "KEY_DURABLE_SESSION_ID must match the canonical cross-repo wire key",
            "durable_session_id",
            DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID
        )
    }

    @Test
    fun `KEY_SESSION_CONTINUITY_EPOCH wire key is session_continuity_epoch`() {
        assertEquals(
            "KEY_SESSION_CONTINUITY_EPOCH must match the canonical cross-repo wire key",
            "session_continuity_epoch",
            DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH
        )
    }

    @Test
    fun `durableSessionId is preserved across withEpochIncremented`() {
        val original = DurableSessionContinuityRecord.create("user_activation")
        val incremented = original.withEpochIncremented()
        assertEquals(
            "durableSessionId must be stable across reconnects — it is the durable identity anchor",
            original.durableSessionId,
            incremented.durableSessionId
        )
    }

    @Test
    fun `sessionContinuityEpoch is incremented by withEpochIncremented`() {
        val original = DurableSessionContinuityRecord.create("user_activation")
        val incremented = original.withEpochIncremented()
        assertEquals(
            "Each withEpochIncremented call must increment the epoch by exactly 1",
            original.sessionContinuityEpoch + 1,
            incremented.sessionContinuityEpoch
        )
    }

    @Test
    fun `create starts at epoch 0`() {
        val record = DurableSessionContinuityRecord.create("user_activation")
        assertEquals(
            "A fresh durable session era must start at epoch 0",
            0,
            record.sessionContinuityEpoch
        )
    }

    @Test
    fun `withEpochIncremented increments epoch by exactly 1`() {
        val base = DurableSessionContinuityRecord.create("background_restore")
        val afterOne = base.withEpochIncremented()
        assertEquals(
            "First reconnect within an era must produce epoch 1",
            1,
            afterOne.sessionContinuityEpoch
        )
    }

    @Test
    fun `multiple withEpochIncremented calls accumulate correctly`() {
        val base = DurableSessionContinuityRecord.create("user_activation")
        val afterThree = base.withEpochIncremented().withEpochIncremented().withEpochIncremented()
        assertEquals(
            "Three successive reconnects within an era must produce epoch 3",
            3,
            afterThree.sessionContinuityEpoch
        )
        assertEquals(
            "durableSessionId must remain stable through multiple reconnect epochs",
            base.durableSessionId,
            afterThree.durableSessionId
        )
    }

    @Test
    fun `KEY_DURABLE_SESSION_ID is distinct from KEY_SESSION_CONTINUITY_EPOCH`() {
        assertNotEquals(
            "The durable session id key and the epoch key must be distinct",
            DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID,
            DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH
        )
    }

    @Test
    fun `ALL_KEYS contains KEY_DURABLE_SESSION_ID`() {
        assertTrue(
            "ALL_KEYS must include KEY_DURABLE_SESSION_ID for complete wire serialisation",
            DurableSessionContinuityRecord.ALL_KEYS.contains(DurableSessionContinuityRecord.KEY_DURABLE_SESSION_ID)
        )
    }

    @Test
    fun `ALL_KEYS contains KEY_SESSION_CONTINUITY_EPOCH`() {
        assertTrue(
            "ALL_KEYS must include KEY_SESSION_CONTINUITY_EPOCH for complete wire serialisation",
            DurableSessionContinuityRecord.ALL_KEYS.contains(DurableSessionContinuityRecord.KEY_SESSION_CONTINUITY_EPOCH)
        )
    }
}
