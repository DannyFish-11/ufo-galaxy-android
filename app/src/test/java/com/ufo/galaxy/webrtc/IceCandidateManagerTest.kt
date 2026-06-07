package com.ufo.galaxy.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [IceCandidateManager] covering:
 * - Duplicate candidate detection
 * - Priority ordering (relay → srflx → host)
 * - TURN-only fallback activation and retry counter
 * - Error callbacks on exhausted retries / missing TURN candidates
 * - Reset behaviour
 * - [onApplyCandidate] invocation order
 */
class IceCandidateManagerTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun host(sdp: String = "host-candidate") = SignalingMessage.IceCandidate(
        "candidate:0 1 UDP 2130706431 192.168.1.1 54321 typ host",
        "0", 0
    ).let { it.copy(candidate = sdp.ifEmpty { it.candidate }) }

    private fun srflx(sdp: String = "srflx-candidate") = SignalingMessage.IceCandidate(
        "candidate:1 1 UDP 1694498815 1.2.3.4 54321 typ srflx raddr 192.168.1.1 rport 54321",
        "0", 0
    ).let { it.copy(candidate = sdp.ifEmpty { it.candidate }) }

    private fun relay(sdp: String = "relay-candidate") = SignalingMessage.IceCandidate(
        "candidate:2 1 UDP 33562623 5.6.7.8 3478 typ relay raddr 1.2.3.4 rport 54321",
        "0", 0
    ).let { it.copy(candidate = sdp.ifEmpty { it.candidate }) }

    private fun manager(
        traceId: String = "test-trace",
        applied: MutableList<SignalingMessage.IceCandidate> = mutableListOf(),
        errors: MutableList<String> = mutableListOf()
    ) = IceCandidateManager(
        traceId = traceId,
        onApplyCandidate = { applied.add(it) },
        onError = { errors.add(it) }
    )

    // ── size / isEmpty ────────────────────────────────────────────────────────

    @Test
    fun `new manager is empty`() {
        val m = manager()
        assertEquals(0, m.size)
        assertTrue(m.isEmpty)
    }

    @Test
    fun `size increases after addCandidate`() {
        val m = manager()
        m.addCandidate(host())
        assertEquals(1, m.size)
        m.addCandidate(srflx())
        assertEquals(2, m.size)
    }

    // ── duplicate detection ───────────────────────────────────────────────────

    @Test
    fun `duplicate candidate is rejected`() {
        val applied = mutableListOf<SignalingMessage.IceCandidate>()
        val m = manager(applied = applied)
        val c = host("candidate:dup 1 UDP 100 10.0.0.1 1234 typ host")

        val first = m.addCandidate(c)
        val second = m.addCandidate(c)

        assertTrue("First should be accepted", first)
        assertFalse("Duplicate should be rejected", second)
        assertEquals("Applied exactly once", 1, applied.size)
        assertEquals("Stored exactly once", 1, m.size)
    }

    @Test
    fun `addCandidates deduplicates within a batch`() {
        val applied = mutableListOf<SignalingMessage.IceCandidate>()
        val m = manager(applied = applied)
        val c = host("candidate:dup 1 UDP 100 10.0.0.1 1234 typ host")

        val count = m.addCandidates(listOf(c, c, c))

        assertEquals("Only one accepted", 1, count)
        assertEquals(1, applied.size)
    }

    @Test
    fun `addCandidates deduplicates across calls`() {
        val applied = mutableListOf<SignalingMessage.IceCandidate>()
        val m = manager(applied = applied)
        val c = srflx("candidate:1 1 UDP 100 1.2.3.4 1234 typ srflx raddr 10.0.0.1 rport 1234")

        m.addCandidate(c)
        val count = m.addCandidates(listOf(c))

        assertEquals("Already seen – not re-applied", 0, count)
        assertEquals(1, applied.size)
    }

    // ── priority ordering ─────────────────────────────────────────────────────

    @Test
    fun `addCandidates applies relay before srflx before host`() {
        val applied = mutableListOf<SignalingMessage.IceCandidate>()
        val m = manager(applied = applied)

        val h = host("candidate:0 1 UDP 2130706431 192.168.1.1 54321 typ host")
        val s = srflx("candidate:1 1 UDP 1694498815 1.2.3.4 54321 typ srflx raddr 192.168.1.1 rport 54321")
        val r = relay("candidate:2 1 UDP 33562623 5.6.7.8 3478 typ relay raddr 1.2.3.4 rport 54321")

        // Arrive in host → srflx → relay order; should be applied relay → srflx → host
        m.addCandidates(listOf(h, s, r))

        assertEquals(3, applied.size)
        assertEquals("relay first", SignalingMessage.IceCandidate.TYPE_RELAY, applied[0].candidateType)
        assertEquals("srflx second", SignalingMessage.IceCandidate.TYPE_SRFLX, applied[1].candidateType)
        assertEquals("host last", SignalingMessage.IceCandidate.TYPE_HOST, applied[2].candidateType)
    }

    @Test
    fun `getOrderedCandidates returns relay before srflx before host`() {
        val m = manager()
        val h = host("candidate:0 1 UDP 2130706431 192.168.1.1 54321 typ host")
        val s = srflx("candidate:1 1 UDP 1694498815 1.2.3.4 54321 typ srflx raddr 192.168.1.1 rport 54321")
        val r = relay("candidate:2 1 UDP 33562623 5.6.7.8 3478 typ relay raddr 1.2.3.4 rport 54321")

        m.addCandidates(listOf(h, s, r))
        val ordered = m.getOrderedCandidates()

        assertEquals(SignalingMessage.IceCandidate.TYPE_RELAY, ordered[0].candidateType)
        assertEquals(SignalingMessage.IceCandidate.TYPE_SRFLX, ordered[1].candidateType)
        assertEquals(SignalingMessage.IceCandidate.TYPE_HOST, ordered[2].candidateType)
    }

    @Test
    fun `priorityOf returns correct values`() {
        assertEquals(3, IceCandidateManager.priorityOf(SignalingMessage.IceCandidate.TYPE_RELAY))
        assertEquals(2, IceCandidateManager.priorityOf(SignalingMessage.IceCandidate.TYPE_SRFLX))
        assertEquals(1, IceCandidateManager.priorityOf(SignalingMessage.IceCandidate.TYPE_HOST))
        assertEquals(0, IceCandidateManager.priorityOf("unknown"))
    }

    // ── TURN candidate detection ──────────────────────────────────────────────

    @Test
    fun `hasTurnCandidates is false when no relay candidates present`() {
        val m = manager()
        m.addCandidates(listOf(host(), srflx()))
        assertFalse(m.hasTurnCandidates())
    }

    @Test
    fun `hasTurnCandidates is true when relay candidate present`() {
        val m = manager()
        m.addCandidates(listOf(host(), relay()))
        assertTrue(m.hasTurnCandidates())
    }

    @Test
    fun `getTurnCandidates returns only relay candidates`() {
        val m = manager()
        val r = relay("candidate:2 1 UDP 33562623 5.6.7.8 3478 typ relay raddr 1.2.3.4 rport 54321")
        m.addCandidates(listOf(host(), srflx(), r))

        val turnOnly = m.getTurnCandidates()
        assertEquals(1, turnOnly.size)
        assertEquals(SignalingMessage.IceCandidate.TYPE_RELAY, turnOnly[0].candidateType)
    }

    // ── TURN fallback ─────────────────────────────────────────────────────────

    @Test
    fun `startTurnFallback activates relay-only mode`() {
        val applied = mutableListOf<SignalingMessage.IceCandidate>()
        val m = manager(applied = applied)
        m.addCandidates(listOf(host(), relay()))
        applied.clear()

        val started = m.startTurnFallback()

        assertTrue("Fallback should start when relay candidate exists", started)
        assertTrue("Fallback should be active", m.isTurnFallbackActive)
        assertEquals(1, m.fallbackAttempts)
        assertEquals("Relay candidate should be re-applied", 1, applied.size)
    }

    @Test
    fun `startTurnFallback blocks non-relay candidates after activation`() {
        val applied = mutableListOf<SignalingMessage.IceCandidate>()
        val m = manager(applied = applied)
        m.addCandidates(listOf(relay()))
        m.startTurnFallback()
        applied.clear()

        // New host candidate should be rejected in fallback mode
        val accepted = m.addCandidate(host("candidate:new-host 1 UDP 100 192.168.1.99 1234 typ host"))
        assertFalse("Host candidate should be filtered in fallback mode", accepted)
        assertEquals("No additional candidates applied", 0, applied.size)
    }

    @Test
    fun `startTurnFallback allows new relay candidates after activation`() {
        val applied = mutableListOf<SignalingMessage.IceCandidate>()
        val m = manager(applied = applied)
        val r1 = relay("candidate:r1 1 UDP 33562623 5.6.7.8 3478 typ relay raddr 1.2.3.4 rport 3478")
        m.addCandidate(r1)
        m.startTurnFallback()
        applied.clear()

        // A new (different) relay candidate should still be accepted
        val r2 = relay("candidate:r2 1 UDP 33562622 5.6.7.9 3478 typ relay raddr 1.2.3.5 rport 3478")
        val accepted = m.addCandidate(r2)
        assertTrue("New relay candidate accepted in fallback mode", accepted)
        assertEquals(1, applied.size)
    }

    @Test
    fun `startTurnFallback fails without relay candidates and invokes onError`() {
        val errors = mutableListOf<String>()
        val m = manager(errors = errors)
        m.addCandidates(listOf(host(), srflx()))

        val started = m.startTurnFallback()

        assertFalse("Should not start if no relay candidates", started)
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("no relay candidates"))
    }

    @Test
    fun `startTurnFallback respects MAX_FALLBACK_ATTEMPTS`() {
        val errors = mutableListOf<String>()
        val m = manager(errors = errors)
        m.addCandidate(relay("candidate:r 1 UDP 33562623 5.6.7.8 3478 typ relay raddr 1.2.3.4 rport 3478"))

        // Exhaust all attempts
        repeat(IceCandidateManager.MAX_FALLBACK_ATTEMPTS) { m.startTurnFallback() }

        // One more should fail with exhausted error
        val started = m.startTurnFallback()
        assertFalse("Should fail once max attempts reached", started)
        assertTrue(errors.any { it.contains("exhausted") })
    }

    @Test
    fun `fallbackAttempts increments correctly`() {
        val m = manager()
        m.addCandidate(relay("candidate:r 1 UDP 33562623 5.6.7.8 3478 typ relay raddr 1.2.3.4 rport 3478"))

        assertEquals(0, m.fallbackAttempts)
        m.startTurnFallback()
        assertEquals(1, m.fallbackAttempts)
        m.startTurnFallback()
        assertEquals(2, m.fallbackAttempts)
    }

    @Test
    fun `lastBackoffDelayMs is set after startTurnFallback`() {
        val m = manager()
        m.addCandidate(relay("candidate:r 1 UDP 33562623 5.6.7.8 3478 typ relay raddr 1.2.3.4 rport 3478"))
        assertEquals(0L, m.lastBackoffDelayMs)

        m.startTurnFallback()
        assertEquals(IceCandidateManager.FALLBACK_BACKOFF_MS[0], m.lastBackoffDelayMs)

        m.startTurnFallback()
        assertEquals(IceCandidateManager.FALLBACK_BACKOFF_MS[1], m.lastBackoffDelayMs)
    }

    @Test
    fun `lastBackoffDelayMs resets to 0 after reset`() {
        val m = manager()
        m.addCandidate(relay("candidate:r 1 UDP 33562623 5.6.7.8 3478 typ relay raddr 1.2.3.4 rport 3478"))
        m.startTurnFallback()
        assertTrue(m.lastBackoffDelayMs > 0L)

        m.reset()
        assertEquals(0L, m.lastBackoffDelayMs)
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test
    fun `reset clears all candidates and resets state`() {
        val applied = mutableListOf<SignalingMessage.IceCandidate>()
        val m = manager(applied = applied)
        m.addCandidates(listOf(host(), srflx(), relay()))
        m.startTurnFallback()

        m.reset()

        assertEquals("All candidates cleared", 0, m.size)
        assertTrue(m.isEmpty)
        assertFalse("Fallback no longer active after reset", m.isTurnFallbackActive)
        assertEquals("Attempt counter reset", 0, m.fallbackAttempts)
    }

    @Test
    fun `after reset addCandidates accepts previously-seen candidates`() {
        val applied = mutableListOf<SignalingMessage.IceCandidate>()
        val m = manager(applied = applied)
        val h = host("candidate:0 1 UDP 2130706431 192.168.1.1 54321 typ host")
        m.addCandidate(h)
        m.reset()
        applied.clear()

        val accepted = m.addCandidate(h)
        assertTrue("Candidate accepted after reset", accepted)
        assertEquals(1, applied.size)
    }

    // ── addCandidates return value ────────────────────────────────────────────

    @Test
    fun `addCandidates returns count of applied candidates`() {
        val m = manager()
        val count = m.addCandidates(listOf(host(), srflx(), relay()))
        assertEquals(3, count)
    }

    @Test
    fun `addCandidates with all duplicates returns 0`() {
        val m = manager()
        val h = host("candidate:0 1 UDP 100 10.0.0.1 1234 typ host")
        m.addCandidate(h)
        val count = m.addCandidates(listOf(h, h))
        assertEquals(0, count)
    }

    // ── traceId in errors ─────────────────────────────────────────────────────

    @Test
    fun `onError includes traceId when relay candidates missing`() {
        val errors = mutableListOf<String>()
        val m = IceCandidateManager(
            traceId = "my-trace-xyz",
            onError = { errors.add(it) }
        )
        m.startTurnFallback()
        assertTrue(errors.any { it.contains("my-trace-xyz") })
    }

    @Test
    fun `onError includes traceId when fallback exhausted`() {
        val errors = mutableListOf<String>()
        val m = IceCandidateManager(
            traceId = "exhaust-trace",
            onError = { errors.add(it) }
        )
        m.addCandidate(relay("candidate:r 1 UDP 33562623 5.6.7.8 3478 typ relay raddr 1.2.3.4 rport 3478"))
        repeat(IceCandidateManager.MAX_FALLBACK_ATTEMPTS + 1) { m.startTurnFallback() }
        assertTrue(errors.any { it.contains("exhaust-trace") })
    }
}
