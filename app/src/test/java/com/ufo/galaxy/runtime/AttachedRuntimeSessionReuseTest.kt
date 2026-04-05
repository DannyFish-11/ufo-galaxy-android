package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [AttachedRuntimeSession] **reuse semantics** (PR-14, post-#533
 * dual-repo runtime unification master plan — Persistent Attached-Runtime Reuse
 * Foundations, Android side).
 *
 * ## Test matrix
 *
 * ### Initial state
 *  - delegatedExecutionCount is 0 on a freshly created session.
 *  - isReuseValid is true for a freshly created session.
 *
 * ### isReuseValid semantics
 *  - isReuseValid is true when state == ATTACHED.
 *  - isReuseValid is false when state == DETACHING.
 *  - isReuseValid is false when state == DETACHED.
 *
 * ### withExecutionAccepted — count increment
 *  - First call increments delegatedExecutionCount from 0 to 1.
 *  - Second consecutive call increments from 1 to 2.
 *  - Ten successive calls produce delegatedExecutionCount == 10.
 *  - Original session is not mutated.
 *
 * ### withExecutionAccepted — identity preservation
 *  - sessionId is unchanged after withExecutionAccepted.
 *  - hostId is unchanged after withExecutionAccepted.
 *  - deviceId is unchanged after withExecutionAccepted.
 *  - attachedAtMs is unchanged after withExecutionAccepted.
 *  - state remains ATTACHED after withExecutionAccepted.
 *  - detachCause remains null after withExecutionAccepted.
 *
 * ### Reuse invalidation via detach transitions
 *  - isReuseValid becomes false after beginDetaching(EXPLICIT_DETACH).
 *  - isReuseValid becomes false after beginDetaching(DISCONNECT).
 *  - isReuseValid becomes false after beginDetaching(DISABLE).
 *  - isReuseValid becomes false after beginDetaching(INVALIDATION).
 *  - isReuseValid becomes false after detachedWith(EXPLICIT_DETACH).
 *  - isReuseValid becomes false after detachedWith(DISCONNECT).
 *  - isReuseValid becomes false after detachedWith(DISABLE).
 *  - isReuseValid becomes false after detachedWith(INVALIDATION).
 *
 * ### delegatedExecutionCount preserved across lifecycle transitions
 *  - Count is preserved when session transitions to DETACHING.
 *  - Count is preserved when session transitions to DETACHED.
 *
 * ### toMetadataMap includes delegatedExecutionCount
 *  - Map contains KEY_DELEGATED_EXECUTION_COUNT key with value 0 on fresh session.
 *  - Map value reflects updated count after withExecutionAccepted.
 *
 * ### Multiple tasks over a single session — no session re-creation
 *  - Same sessionId is preserved across multiple withExecutionAccepted calls.
 */
class AttachedRuntimeSessionReuseTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun freshSession(
        hostId: String = "host-pr14",
        deviceId: String = "pixel-8"
    ) = AttachedRuntimeSession.create(hostId = hostId, deviceId = deviceId)

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun `delegatedExecutionCount is 0 on a freshly created session`() {
        assertEquals(0, freshSession().delegatedExecutionCount)
    }

    @Test
    fun `isReuseValid is true for a freshly created session`() {
        assertTrue(
            "A new ATTACHED session must be reuse-valid",
            freshSession().isReuseValid
        )
    }

    // ── isReuseValid semantics ────────────────────────────────────────────────

    @Test
    fun `isReuseValid is true when state is ATTACHED`() {
        val session = freshSession()
        assertEquals(AttachedRuntimeSession.State.ATTACHED, session.state)
        assertTrue(session.isReuseValid)
    }

    @Test
    fun `isReuseValid is false when state is DETACHING`() {
        val detaching = freshSession().beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals(AttachedRuntimeSession.State.DETACHING, detaching.state)
        assertFalse(
            "A DETACHING session must not be reuse-valid",
            detaching.isReuseValid
        )
    }

    @Test
    fun `isReuseValid is false when state is DETACHED`() {
        val detached = freshSession().detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals(AttachedRuntimeSession.State.DETACHED, detached.state)
        assertFalse(
            "A DETACHED session must not be reuse-valid",
            detached.isReuseValid
        )
    }

    // ── withExecutionAccepted — count increment ───────────────────────────────

    @Test
    fun `first withExecutionAccepted increments count from 0 to 1`() {
        val updated = freshSession().withExecutionAccepted()
        assertEquals(1, updated.delegatedExecutionCount)
    }

    @Test
    fun `second withExecutionAccepted increments count from 1 to 2`() {
        val after2 = freshSession()
            .withExecutionAccepted()
            .withExecutionAccepted()
        assertEquals(2, after2.delegatedExecutionCount)
    }

    @Test
    fun `ten successive withExecutionAccepted calls produce count 10`() {
        var session = freshSession()
        repeat(10) { session = session.withExecutionAccepted() }
        assertEquals(10, session.delegatedExecutionCount)
    }

    @Test
    fun `original session is not mutated by withExecutionAccepted`() {
        val original = freshSession()
        original.withExecutionAccepted()
        assertEquals(
            "Original session must remain immutable",
            0,
            original.delegatedExecutionCount
        )
    }

    // ── withExecutionAccepted — identity preservation ─────────────────────────

    @Test
    fun `sessionId is unchanged after withExecutionAccepted`() {
        val session = freshSession()
        val updated = session.withExecutionAccepted()
        assertEquals(session.sessionId, updated.sessionId)
    }

    @Test
    fun `hostId is unchanged after withExecutionAccepted`() {
        val session = freshSession(hostId = "my-host")
        assertEquals("my-host", session.withExecutionAccepted().hostId)
    }

    @Test
    fun `deviceId is unchanged after withExecutionAccepted`() {
        val session = freshSession(deviceId = "my-pixel")
        assertEquals("my-pixel", session.withExecutionAccepted().deviceId)
    }

    @Test
    fun `attachedAtMs is unchanged after withExecutionAccepted`() {
        val session = freshSession()
        assertEquals(session.attachedAtMs, session.withExecutionAccepted().attachedAtMs)
    }

    @Test
    fun `state remains ATTACHED after withExecutionAccepted`() {
        assertEquals(
            AttachedRuntimeSession.State.ATTACHED,
            freshSession().withExecutionAccepted().state
        )
    }

    @Test
    fun `detachCause remains null after withExecutionAccepted`() {
        assertNull(freshSession().withExecutionAccepted().detachCause)
    }

    // ── Reuse invalidation via detach transitions ─────────────────────────────

    @Test
    fun `isReuseValid becomes false after beginDetaching EXPLICIT_DETACH`() {
        assertFalse(
            freshSession()
                .beginDetaching(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
                .isReuseValid
        )
    }

    @Test
    fun `isReuseValid becomes false after beginDetaching DISCONNECT`() {
        assertFalse(
            freshSession()
                .beginDetaching(AttachedRuntimeSession.DetachCause.DISCONNECT)
                .isReuseValid
        )
    }

    @Test
    fun `isReuseValid becomes false after beginDetaching DISABLE`() {
        assertFalse(
            freshSession()
                .beginDetaching(AttachedRuntimeSession.DetachCause.DISABLE)
                .isReuseValid
        )
    }

    @Test
    fun `isReuseValid becomes false after beginDetaching INVALIDATION`() {
        assertFalse(
            freshSession()
                .beginDetaching(AttachedRuntimeSession.DetachCause.INVALIDATION)
                .isReuseValid
        )
    }

    @Test
    fun `isReuseValid becomes false after detachedWith EXPLICIT_DETACH`() {
        assertFalse(
            freshSession()
                .detachedWith(AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH)
                .isReuseValid
        )
    }

    @Test
    fun `isReuseValid becomes false after detachedWith DISCONNECT`() {
        assertFalse(
            freshSession()
                .detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
                .isReuseValid
        )
    }

    @Test
    fun `isReuseValid becomes false after detachedWith DISABLE`() {
        assertFalse(
            freshSession()
                .detachedWith(AttachedRuntimeSession.DetachCause.DISABLE)
                .isReuseValid
        )
    }

    @Test
    fun `isReuseValid becomes false after detachedWith INVALIDATION`() {
        assertFalse(
            freshSession()
                .detachedWith(AttachedRuntimeSession.DetachCause.INVALIDATION)
                .isReuseValid
        )
    }

    // ── delegatedExecutionCount preserved across lifecycle transitions ─────────

    @Test
    fun `execution count is preserved when session transitions to DETACHING`() {
        val detaching = freshSession()
            .withExecutionAccepted()
            .withExecutionAccepted()
            .withExecutionAccepted()
            .beginDetaching(AttachedRuntimeSession.DetachCause.DISABLE)
        assertEquals(
            "Execution count must survive beginDetaching transition",
            3,
            detaching.delegatedExecutionCount
        )
    }

    @Test
    fun `execution count is preserved when session transitions to DETACHED`() {
        val detached = freshSession()
            .withExecutionAccepted()
            .withExecutionAccepted()
            .detachedWith(AttachedRuntimeSession.DetachCause.DISCONNECT)
        assertEquals(
            "Execution count must survive detachedWith transition",
            2,
            detached.delegatedExecutionCount
        )
    }

    // ── toMetadataMap includes delegatedExecutionCount ────────────────────────

    @Test
    fun `toMetadataMap contains KEY_DELEGATED_EXECUTION_COUNT with value 0 on fresh session`() {
        val map = freshSession().toMetadataMap()
        assertTrue(
            "toMetadataMap must contain KEY_DELEGATED_EXECUTION_COUNT",
            map.containsKey(AttachedRuntimeSession.KEY_DELEGATED_EXECUTION_COUNT)
        )
        assertEquals(0, map[AttachedRuntimeSession.KEY_DELEGATED_EXECUTION_COUNT])
    }

    @Test
    fun `toMetadataMap reflects updated count after withExecutionAccepted`() {
        val map = freshSession()
            .withExecutionAccepted()
            .withExecutionAccepted()
            .toMetadataMap()
        assertEquals(
            "Metadata map must reflect updated execution count",
            2,
            map[AttachedRuntimeSession.KEY_DELEGATED_EXECUTION_COUNT]
        )
    }

    // ── Multiple tasks over single session — no session re-creation ───────────

    @Test
    fun `sessionId is identical across multiple withExecutionAccepted calls`() {
        val original = freshSession()
        val afterThree = original
            .withExecutionAccepted()
            .withExecutionAccepted()
            .withExecutionAccepted()
        assertEquals(
            "Session identity (sessionId) must be preserved across multiple task acceptances",
            original.sessionId,
            afterThree.sessionId
        )
    }
}
