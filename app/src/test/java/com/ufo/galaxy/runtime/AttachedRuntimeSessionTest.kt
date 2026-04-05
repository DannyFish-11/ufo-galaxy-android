package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [AttachedRuntimeSession] — the canonical Android attached-runtime session
 * model introduced in PR-7 (post-#533 dual-repo runtime unification, Android side —
 * Android Attached-Runtime Session Semantics).
 *
 * ## Test matrix
 *
 * ### [AttachedRuntimeSession.State] enum
 *  - All three states have distinct, stable wire values.
 *  - fromValue maps known strings to the correct enum entry.
 *  - fromValue with unknown / null input returns DEFAULT (ATTACHED).
 *
 * ### [AttachedRuntimeSession.DetachCause] enum
 *  - All four causes have distinct, stable wire values.
 *  - fromValue maps known strings to the correct enum entry.
 *  - fromValue with unknown / null input returns null.
 *
 * ### [AttachedRuntimeSession.create] factory
 *  - Generates a unique sessionId when not supplied.
 *  - Starts in ATTACHED state.
 *  - detachCause is null on creation.
 *  - detachedAtMs is null on creation.
 *  - hostId and deviceId are preserved exactly.
 *
 * ### [AttachedRuntimeSession.isAttached]
 *  - True only when state == ATTACHED.
 *  - False when state == DETACHING.
 *  - False when state == DETACHED.
 *
 * ### [AttachedRuntimeSession.isDetached]
 *  - True only when state == DETACHED.
 *  - False when state == ATTACHED or DETACHING.
 *
 * ### [AttachedRuntimeSession.beginDetaching]
 *  - Returns a new instance in DETACHING state.
 *  - detachCause is set to the provided cause.
 *  - Original instance is not mutated.
 *  - All other fields are preserved.
 *
 * ### [AttachedRuntimeSession.detachedWith]
 *  - Returns a new instance in DETACHED state.
 *  - detachCause is set to the provided cause.
 *  - detachedAtMs is set.
 *  - Original instance is not mutated.
 *  - May be called from ATTACHED (abrupt) or DETACHING (graceful completion).
 *
 * ### [AttachedRuntimeSession.toMetadataMap]
 *  - Contains required keys: session_id, host_id, state, attached_at_ms.
 *  - KEY_DETACH_CAUSE is absent when detachCause is null.
 *  - KEY_DETACH_CAUSE is present when detachCause is set.
 *  - State value matches the wireValue of the current state.
 *
 * ### Metadata key constants
 *  - KEY_SESSION_ID is "attached_session_id".
 *  - KEY_HOST_ID is "attached_session_host_id".
 *  - KEY_STATE is "attached_session_state".
 *  - KEY_ATTACHED_AT_MS is "attached_session_attached_at_ms".
 *  - KEY_DETACH_CAUSE is "attached_session_detach_cause".
 *
 * ### Distinction from connection presence
 *  - A freshly created session (ATTACHED) represents explicit cross-device participation.
 *  - A DETACHED session no longer represents active participation.
 *  - sessionId is distinct across separate create() calls.
 */
class AttachedRuntimeSessionTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun attachedSession(
        hostId: String = "test-host-id",
        deviceId: String = "test-device",
        sessionId: String = "test-session-id"
    ) = AttachedRuntimeSession(
        sessionId = sessionId,
        hostId = hostId,
        deviceId = deviceId,
        attachedAtMs = 1_000L,
        state = AttachedRuntimeSession.State.ATTACHED,
        detachCause = null,
        detachedAtMs = null
    )

    // ── State enum wire values ────────────────────────────────────────────────

    @Test
    fun `State ATTACHED wireValue is attached`() {
        assertEquals("attached", AttachedRuntimeSession.State.ATTACHED.wireValue)
    }

    @Test
    fun `State DETACHING wireValue is detaching`() {
        assertEquals("detaching", AttachedRuntimeSession.State.DETACHING.wireValue)
    }

    @Test
    fun `State DETACHED wireValue is detached`() {
        assertEquals("detached", AttachedRuntimeSession.State.DETACHED.wireValue)
    }

    @Test
    fun `State wire values are all distinct`() {
        val values = AttachedRuntimeSession.State.entries.map { it.wireValue }
        assertEquals("wire values must be distinct", values.size, values.toSet().size)
    }

    @Test
    fun `State fromValue attached returns ATTACHED`() {
        assertEquals(
            AttachedRuntimeSession.State.ATTACHED,
            AttachedRuntimeSession.State.fromValue("attached")
        )
    }

    @Test
    fun `State fromValue detaching returns DETACHING`() {
        assertEquals(
            AttachedRuntimeSession.State.DETACHING,
            AttachedRuntimeSession.State.fromValue("detaching")
        )
    }

    @Test
    fun `State fromValue detached returns DETACHED`() {
        assertEquals(
            AttachedRuntimeSession.State.DETACHED,
            AttachedRuntimeSession.State.fromValue("detached")
        )
    }

    @Test
    fun `State fromValue unknown returns DEFAULT`() {
        assertEquals(
            AttachedRuntimeSession.State.DEFAULT,
            AttachedRuntimeSession.State.fromValue("unknown_state")
        )
    }

    @Test
    fun `State fromValue null returns DEFAULT`() {
        assertEquals(
            AttachedRuntimeSession.State.DEFAULT,
            AttachedRuntimeSession.State.fromValue(null)
        )
    }

    @Test
    fun `State DEFAULT is ATTACHED`() {
        assertEquals(AttachedRuntimeSession.State.ATTACHED, AttachedRuntimeSession.State.DEFAULT)
    }

    @Test
    fun `State has exactly three entries`() {
        assertEquals(3, AttachedRuntimeSession.State.entries.size)
    }

    // ── DetachCause enum wire values ──────────────────────────────────────────

    @Test
    fun `DetachCause EXPLICIT_DETACH wireValue is explicit_detach`() {
        assertEquals("explicit_detach", AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH.wireValue)
    }

    @Test
    fun `DetachCause DISCONNECT wireValue is disconnect`() {
        assertEquals("disconnect", AttachedRuntimeSession.DetachCause.DISCONNECT.wireValue)
    }

    @Test
    fun `DetachCause DISABLE wireValue is disable`() {
        assertEquals("disable", AttachedRuntimeSession.DetachCause.DISABLE.wireValue)
    }

    @Test
    fun `DetachCause INVALIDATION wireValue is invalidation`() {
        assertEquals("invalidation", AttachedRuntimeSession.DetachCause.INVALIDATION.wireValue)
    }

    @Test
    fun `DetachCause wire values are all distinct`() {
        val values = AttachedRuntimeSession.DetachCause.entries.map { it.wireValue }
        assertEquals("wire values must be distinct", values.size, values.toSet().size)
    }

    @Test
    fun `DetachCause fromValue explicit_detach returns EXPLICIT_DETACH`() {
        assertEquals(
            AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH,
            AttachedRuntimeSession.DetachCause.fromValue("explicit_detach")
        )
    }

    @Test
    fun `DetachCause fromValue disconnect returns DISCONNECT`() {
        assertEquals(
            AttachedRuntimeSession.DetachCause.DISCONNECT,
            AttachedRuntimeSession.DetachCause.fromValue("disconnect")
        )
    }

    @Test
    fun `DetachCause fromValue disable returns DISABLE`() {
        assertEquals(
            AttachedRuntimeSession.DetachCause.DISABLE,
            AttachedRuntimeSession.DetachCause.fromValue("disable")
        )
    }

    @Test
    fun `DetachCause fromValue invalidation returns INVALIDATION`() {
        assertEquals(
            AttachedRuntimeSession.DetachCause.INVALIDATION,
            AttachedRuntimeSession.DetachCause.fromValue("invalidation")
        )
    }

    @Test
    fun `DetachCause fromValue unknown returns null`() {
        assertNull(AttachedRuntimeSession.DetachCause.fromValue("unknown_cause"))
    }

    @Test
    fun `DetachCause fromValue null returns null`() {
        assertNull(AttachedRuntimeSession.DetachCause.fromValue(null))
    }

    @Test
    fun `DetachCause has exactly four entries`() {
        assertEquals(4, AttachedRuntimeSession.DetachCause.entries.size)
    }

    // ── create factory ────────────────────────────────────────────────────────

    @Test
    fun `create factory generates a non-blank sessionId`() {
        val session = AttachedRuntimeSession.create(hostId = "h-1", deviceId = "d-1")
        assertTrue("sessionId must not be blank", session.sessionId.isNotBlank())
    }

    @Test
    fun `create factory generates unique sessionIds on repeated calls`() {
        val s1 = AttachedRuntimeSession.create(hostId = "h-1", deviceId = "d-1")
        val s2 = AttachedRuntimeSession.create(hostId = "h-1", deviceId = "d-1")
        assertNotEquals("consecutive create() calls must produce different sessionIds", s1.sessionId, s2.sessionId)
    }

    @Test
    fun `create factory starts in ATTACHED state`() {
        val session = AttachedRuntimeSession.create(hostId = "h-1", deviceId = "d-1")
        assertEquals(AttachedRuntimeSession.State.ATTACHED, session.state)
    }

    @Test
    fun `create factory sets detachCause to null`() {
        val session = AttachedRuntimeSession.create(hostId = "h-1", deviceId = "d-1")
        assertNull("detachCause must be null on a freshly created session", session.detachCause)
    }

    @Test
    fun `create factory sets detachedAtMs to null`() {
        val session = AttachedRuntimeSession.create(hostId = "h-1", deviceId = "d-1")
        assertNull("detachedAtMs must be null on a freshly created session", session.detachedAtMs)
    }

    @Test
    fun `create factory preserves hostId`() {
        val session = AttachedRuntimeSession.create(hostId = "host-abc", deviceId = "d-1")
        assertEquals("host-abc", session.hostId)
    }

    @Test
    fun `create factory preserves deviceId`() {
        val session = AttachedRuntimeSession.create(hostId = "h-1", deviceId = "pixel-8-pro")
        assertEquals("pixel-8-pro", session.deviceId)
    }

    @Test
    fun `create factory accepts explicit sessionId`() {
        val session = AttachedRuntimeSession.create(
            hostId = "h-1", deviceId = "d-1", sessionId = "fixed-session-id"
        )
        assertEquals("fixed-session-id", session.sessionId)
    }

    @Test
    fun `create factory sets attachedAtMs to a positive value`() {
        val session = AttachedRuntimeSession.create(hostId = "h-1", deviceId = "d-1")
        assertTrue("attachedAtMs must be positive", session.attachedAtMs > 0L)
    }

    // ── isAttached ────────────────────────────────────────────────────────────

    @Test
    fun `isAttached is true when state is ATTACHED`() {
        val session = attachedSession()
        assertTrue("isAttached must be true for ATTACHED state", session.isAttached)
    }

    @Test
    fun `isAttached is false when state is DETACHING`() {
        val session = attachedSession().beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertFalse("isAttached must be false for DETACHING state", session.isAttached)
    }

    @Test
    fun `isAttached is false when state is DETACHED`() {
        val session = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        assertFalse("isAttached must be false for DETACHED state", session.isAttached)
    }

    // ── isDetached ────────────────────────────────────────────────────────────

    @Test
    fun `isDetached is true when state is DETACHED`() {
        val session = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        assertTrue("isDetached must be true for DETACHED state", session.isDetached)
    }

    @Test
    fun `isDetached is false when state is ATTACHED`() {
        assertFalse(attachedSession().isDetached)
    }

    @Test
    fun `isDetached is false when state is DETACHING`() {
        val session = attachedSession().beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertFalse(session.isDetached)
    }

    // ── beginDetaching ────────────────────────────────────────────────────────

    @Test
    fun `beginDetaching returns a new instance in DETACHING state`() {
        val result = attachedSession().beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals(AttachedRuntimeSession.State.DETACHING, result.state)
    }

    @Test
    fun `beginDetaching sets detachCause to provided cause`() {
        val result = attachedSession().beginDetaching(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        assertEquals(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH, result.detachCause)
    }

    @Test
    fun `beginDetaching does not mutate the original`() {
        val original = attachedSession()
        original.beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals(
            "original state must not be mutated",
            AttachedRuntimeSession.State.ATTACHED,
            original.state
        )
    }

    @Test
    fun `beginDetaching preserves sessionId`() {
        val original = attachedSession(sessionId = "sess-preserve")
        val result = original.beginDetaching(AttachedRuntimeSession.DetachCause.DISABLE)
        assertEquals("sess-preserve", result.sessionId)
    }

    @Test
    fun `beginDetaching preserves hostId and deviceId`() {
        val original = attachedSession(hostId = "h-keep", deviceId = "d-keep")
        val result = original.beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals("h-keep", result.hostId)
        assertEquals("d-keep", result.deviceId)
    }

    @Test
    fun `beginDetaching leaves detachedAtMs null`() {
        val result = attachedSession().beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertNull("detachedAtMs must remain null while DETACHING", result.detachedAtMs)
    }

    // ── detachedWith ──────────────────────────────────────────────────────────

    @Test
    fun `detachedWith returns a new instance in DETACHED state`() {
        val result = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        assertEquals(AttachedRuntimeSession.State.DETACHED, result.state)
    }

    @Test
    fun `detachedWith sets detachCause to provided cause`() {
        val result = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        assertEquals(AttachedRuntimeSession.DetachCause.INVALIDATION, result.detachCause)
    }

    @Test
    fun `detachedWith sets a non-null detachedAtMs`() {
        val result = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertNotNull("detachedAtMs must be set after detachedWith", result.detachedAtMs)
    }

    @Test
    fun `detachedWith accepts explicit timestamp`() {
        val result = attachedSession().detachedWith(
            cause = AttachedRuntimeSession.DetachCause.DISABLE,
            timestampMs = 9_999L
        )
        assertEquals(9_999L, result.detachedAtMs)
    }

    @Test
    fun `detachedWith does not mutate the original`() {
        val original = attachedSession()
        original.detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals(
            "original state must not be mutated",
            AttachedRuntimeSession.State.ATTACHED,
            original.state
        )
    }

    @Test
    fun `detachedWith may be called from DETACHING state graceful path`() {
        val result = attachedSession()
            .beginDetaching(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
            .detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        assertEquals(AttachedRuntimeSession.State.DETACHED, result.state)
        assertEquals(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH, result.detachCause)
    }

    @Test
    fun `detachedWith may be called directly from ATTACHED state abrupt path`() {
        val result = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals(AttachedRuntimeSession.State.DETACHED, result.state)
    }

    // ── toMetadataMap ─────────────────────────────────────────────────────────

    @Test
    fun `toMetadataMap contains KEY_SESSION_ID`() {
        val map = attachedSession(sessionId = "s-123").toMetadataMap()
        assertTrue("map must contain KEY_SESSION_ID", map.containsKey(AttachedRuntimeSession.KEY_SESSION_ID))
        assertEquals("s-123", map[AttachedRuntimeSession.KEY_SESSION_ID])
    }

    @Test
    fun `toMetadataMap contains KEY_HOST_ID`() {
        val map = attachedSession(hostId = "h-abc").toMetadataMap()
        assertTrue(map.containsKey(AttachedRuntimeSession.KEY_HOST_ID))
        assertEquals("h-abc", map[AttachedRuntimeSession.KEY_HOST_ID])
    }

    @Test
    fun `toMetadataMap contains KEY_STATE with correct wireValue`() {
        val map = attachedSession().toMetadataMap()
        assertTrue(map.containsKey(AttachedRuntimeSession.KEY_STATE))
        assertEquals("attached", map[AttachedRuntimeSession.KEY_STATE])
    }

    @Test
    fun `toMetadataMap contains KEY_ATTACHED_AT_MS`() {
        val session = AttachedRuntimeSession(
            sessionId = "s", hostId = "h", deviceId = "d",
            attachedAtMs = 12345L, state = AttachedRuntimeSession.State.ATTACHED
        )
        val map = session.toMetadataMap()
        assertTrue(map.containsKey(AttachedRuntimeSession.KEY_ATTACHED_AT_MS))
        assertEquals(12345L, map[AttachedRuntimeSession.KEY_ATTACHED_AT_MS])
    }

    @Test
    fun `toMetadataMap does not contain KEY_DETACH_CAUSE when detachCause is null`() {
        val map = attachedSession().toMetadataMap()
        assertFalse(
            "KEY_DETACH_CAUSE must be absent when cause is null",
            map.containsKey(AttachedRuntimeSession.KEY_DETACH_CAUSE)
        )
    }

    @Test
    fun `toMetadataMap contains KEY_DETACH_CAUSE when detachCause is set`() {
        val session = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        val map = session.toMetadataMap()
        assertTrue(
            "KEY_DETACH_CAUSE must be present when cause is set",
            map.containsKey(AttachedRuntimeSession.KEY_DETACH_CAUSE)
        )
        assertEquals("disable", map[AttachedRuntimeSession.KEY_DETACH_CAUSE])
    }

    @Test
    fun `toMetadataMap state value matches DETACHED wireValue after detach`() {
        val session = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        val map = session.toMetadataMap()
        assertEquals("detached", map[AttachedRuntimeSession.KEY_STATE])
    }

    // ── Metadata key constants ────────────────────────────────────────────────

    @Test
    fun `KEY_SESSION_ID is attached_session_id`() {
        assertEquals("attached_session_id", AttachedRuntimeSession.KEY_SESSION_ID)
    }

    @Test
    fun `KEY_HOST_ID is attached_session_host_id`() {
        assertEquals("attached_session_host_id", AttachedRuntimeSession.KEY_HOST_ID)
    }

    @Test
    fun `KEY_STATE is attached_session_state`() {
        assertEquals("attached_session_state", AttachedRuntimeSession.KEY_STATE)
    }

    @Test
    fun `KEY_ATTACHED_AT_MS is attached_session_attached_at_ms`() {
        assertEquals("attached_session_attached_at_ms", AttachedRuntimeSession.KEY_ATTACHED_AT_MS)
    }

    @Test
    fun `KEY_DETACH_CAUSE is attached_session_detach_cause`() {
        assertEquals("attached_session_detach_cause", AttachedRuntimeSession.KEY_DETACH_CAUSE)
    }

    // ── Distinction from connection presence ──────────────────────────────────

    @Test
    fun `freshly created session represents explicit cross-device participation`() {
        val session = AttachedRuntimeSession.create(hostId = "h-1", deviceId = "d-1")
        assertTrue(
            "A freshly created session must be ATTACHED — representing explicit participation",
            session.isAttached
        )
    }

    @Test
    fun `DETACHED session does not represent active participation`() {
        val session = AttachedRuntimeSession.create(hostId = "h-1", deviceId = "d-1")
            .detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        assertFalse(
            "A DETACHED session must not be considered active participation",
            session.isAttached
        )
    }

    @Test
    fun `consecutive create calls produce distinct session identities`() {
        val s1 = AttachedRuntimeSession.create(hostId = "h-1", deviceId = "d-1")
        val s2 = AttachedRuntimeSession.create(hostId = "h-1", deviceId = "d-1")
        assertNotEquals(
            "Each attach event must produce a distinct sessionId",
            s1.sessionId, s2.sessionId
        )
    }

    @Test
    fun `DISABLE cause represents administrative cross-device disablement`() {
        val session = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
        assertEquals(AttachedRuntimeSession.DetachCause.DISABLE, session.detachCause)
        assertEquals("disable", session.detachCause!!.wireValue)
    }

    @Test
    fun `DISCONNECT cause represents connection loss not administrative action`() {
        val session = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals(AttachedRuntimeSession.DetachCause.DISCONNECT, session.detachCause)
        assertEquals("disconnect", session.detachCause!!.wireValue)
    }

    @Test
    fun `INVALIDATION cause represents session trust failure`() {
        val session = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
        assertEquals(AttachedRuntimeSession.DetachCause.INVALIDATION, session.detachCause)
    }

    @Test
    fun `EXPLICIT_DETACH cause represents intentional operator action`() {
        val session = attachedSession().detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
        assertEquals(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH, session.detachCause)
    }
}
