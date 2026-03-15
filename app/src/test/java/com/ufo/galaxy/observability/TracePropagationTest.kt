package com.ufo.galaxy.observability

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [TraceContext].
 *
 * Covers:
 *  - Trace ID generation: non-null, non-blank, UUID format.
 *  - Span ID generation: shorter than trace ID (16 hex chars).
 *  - Session reset: new trace ID generated on [reset]; span cleared.
 *  - Server-provided trace ID acceptance: valid IDs are accepted; blank/null are ignored.
 *  - Echo behaviour: after [acceptServerTraceId], [currentTraceId] returns the server ID.
 *  - Span lifecycle: startSpan → currentSpanId non-null → endSpan → null.
 *  - Concurrent safety: multiple threads setting / reading do not throw.
 */
class TracePropagationTest {

    @Before
    fun setUp() {
        TraceContext.reset()
    }

    @After
    fun tearDown() {
        TraceContext.endSpan()
        TraceContext.reset()
    }

    // ── trace_id generation ────────────────────────────────────────────────────

    @Test
    fun `currentTraceId is non-null and non-blank after init`() {
        val id = TraceContext.currentTraceId()
        assertNotNull(id)
        assertTrue("trace_id must not be blank", id.isNotBlank())
    }

    @Test
    fun `generateTraceId returns UUID format`() {
        val id = TraceContext.generateTraceId()
        // UUID v4 = 8-4-4-4-12 hex chars with dashes
        assertTrue(
            "trace_id '$id' should match UUID pattern",
            id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
        )
    }

    @Test
    fun `reset generates a new trace_id`() {
        val first = TraceContext.currentTraceId()
        TraceContext.reset()
        val second = TraceContext.currentTraceId()
        assertNotEquals("reset() must produce a new trace_id", first, second)
    }

    @Test
    fun `reset clears any active span`() {
        TraceContext.startSpan()
        assertNotNull(TraceContext.currentSpanId())
        TraceContext.reset()
        assertNull("reset() must clear the active span", TraceContext.currentSpanId())
    }

    // ── Server trace_id acceptance ─────────────────────────────────────────────

    @Test
    fun `acceptServerTraceId sets trace_id when valid`() {
        val serverTraceId = "server-trace-abc-123"
        TraceContext.acceptServerTraceId(serverTraceId)
        assertEquals(serverTraceId, TraceContext.currentTraceId())
    }

    @Test
    fun `acceptServerTraceId ignores blank string`() {
        val original = TraceContext.currentTraceId()
        TraceContext.acceptServerTraceId("")
        assertEquals("blank server ID must be ignored", original, TraceContext.currentTraceId())
    }

    @Test
    fun `acceptServerTraceId ignores null`() {
        val original = TraceContext.currentTraceId()
        TraceContext.acceptServerTraceId(null)
        assertEquals("null server ID must be ignored", original, TraceContext.currentTraceId())
    }

    @Test
    fun `acceptServerTraceId ignores whitespace-only string`() {
        val original = TraceContext.currentTraceId()
        TraceContext.acceptServerTraceId("   ")
        assertEquals("whitespace-only server ID must be ignored", original, TraceContext.currentTraceId())
    }

    @Test
    fun `trace_id echoed back after acceptServerTraceId`() {
        val serverTrace = "trace-from-server-0042"
        TraceContext.acceptServerTraceId(serverTrace)
        // Simulates outbound message: client reads and echoes the accepted ID.
        val echoed = TraceContext.currentTraceId()
        assertEquals("client should echo the server-provided trace_id", serverTrace, echoed)
    }

    // ── span_id lifecycle ─────────────────────────────────────────────────────

    @Test
    fun `currentSpanId is null before startSpan`() {
        assertNull(TraceContext.currentSpanId())
    }

    @Test
    fun `startSpan returns non-blank span_id`() {
        val spanId = TraceContext.startSpan()
        assertTrue("span_id must not be blank", spanId.isNotBlank())
    }

    @Test
    fun `startSpan sets currentSpanId`() {
        val spanId = TraceContext.startSpan()
        assertEquals(spanId, TraceContext.currentSpanId())
    }

    @Test
    fun `endSpan clears currentSpanId`() {
        TraceContext.startSpan()
        TraceContext.endSpan()
        assertNull("endSpan() must clear the active span", TraceContext.currentSpanId())
    }

    @Test
    fun `endSpan is safe when no span is active`() {
        // Should not throw
        TraceContext.endSpan()
        assertNull(TraceContext.currentSpanId())
    }

    @Test
    fun `generateSpanId returns 16 hex chars`() {
        val spanId = TraceContext.generateSpanId()
        assertEquals("span_id must be 16 chars", 16, spanId.length)
        assertTrue("span_id must be hex", spanId.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `successive startSpan calls return distinct ids`() {
        val id1 = TraceContext.startSpan()
        val id2 = TraceContext.startSpan()
        assertNotEquals("successive startSpan calls must return distinct ids", id1, id2)
    }

    // ── Integration: server trace_id preserved after span operations ──────────

    @Test
    fun `server trace_id preserved across span lifecycle`() {
        val serverTrace = "srv-trace-xyz-999"
        TraceContext.acceptServerTraceId(serverTrace)
        val spanId = TraceContext.startSpan()
        assertNotNull(spanId)
        assertEquals(serverTrace, TraceContext.currentTraceId())
        TraceContext.endSpan()
        assertEquals("server trace_id must survive span operations", serverTrace, TraceContext.currentTraceId())
    }

    // ── Thread safety ─────────────────────────────────────────────────────────

    @Test
    fun `concurrent reads and writes do not throw`() {
        val threads = (1..10).map { i ->
            Thread {
                TraceContext.acceptServerTraceId("trace-$i")
                TraceContext.currentTraceId()
                TraceContext.startSpan()
                TraceContext.currentSpanId()
                TraceContext.endSpan()
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(1_000) }
        // If we reach here without an exception the test passes
    }
}
