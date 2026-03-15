package com.ufo.galaxy.observability

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe holder for the current end-to-end trace identifiers.
 *
 * ## Purpose
 * Provides a single source of truth for `trace_id` and `span_id` values that are
 * propagated across the Android client → Gateway → Agent Runtime chain.
 *
 * ## Trace ID contract (AIP v3)
 * - Generated once per WS session via [generateTraceId]; reused in every message
 *   within that session for end-to-end log correlation.
 * - If the server provides a `trace_id` in a downlink message and the client does not
 *   yet have one (or starts a fresh span), [acceptServerTraceId] should be called so
 *   that subsequent uplink messages echo the server-assigned value.
 * - Always non-null after [reset] or the first [currentTraceId] read.
 *
 * ## Span ID contract
 * - A `span_id` identifies a single logical operation within a trace (e.g. one
 *   task execution, one signaling round-trip).
 * - [startSpan] creates a new child span under the current trace.
 * - [currentSpanId] returns the active span; `null` when no span is active.
 * - [endSpan] clears the active span.
 *
 * ## Usage
 * ```kotlin
 * // Session start (WS open):
 * TraceContext.reset()                          // generate fresh trace_id
 *
 * // Outgoing message:
 * val traceId = TraceContext.currentTraceId()
 * val spanId  = TraceContext.currentSpanId()    // null if no active span
 *
 * // Server-provided trace_id (downlink message):
 * TraceContext.acceptServerTraceId(serverTraceId)
 *
 * // Task execution span:
 * val spanId = TraceContext.startSpan()
 * try { executeTask() } finally { TraceContext.endSpan() }
 * ```
 */
object TraceContext {

    private val _traceId = AtomicReference<String>(generateTraceId())
    private val _spanId  = AtomicReference<String?>(null)

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Returns the current trace identifier.  Never null — a fresh ID is generated
     * at object initialisation and on every [reset] call.
     */
    fun currentTraceId(): String = _traceId.get()

    /**
     * Returns the active span identifier, or `null` when no span is in progress.
     */
    fun currentSpanId(): String? = _spanId.get()

    /**
     * Resets the trace context for a new WS session.
     *
     * Generates a new [currentTraceId] and clears any active [currentSpanId].
     * Call this in [GalaxyWebSocketClient.onOpen] to ensure every session has a
     * fresh trace lineage.
     */
    fun reset() {
        _traceId.set(generateTraceId())
        _spanId.set(null)
    }

    /**
     * Accepts a trace identifier provided by the server (downlink message).
     *
     * If the server provides a `trace_id` and the client should echo it in all
     * subsequent uplink messages, call this method.  A blank or null [serverId]
     * is ignored — the client-generated ID is kept.
     *
     * @param serverId The `trace_id` extracted from an inbound AIP v3 message.
     */
    fun acceptServerTraceId(serverId: String?) {
        if (!serverId.isNullOrBlank()) {
            _traceId.set(serverId)
        }
    }

    /**
     * Opens a new child span under the current trace.
     *
     * A `span_id` is a short UUID representing a single logical operation (e.g.
     * one task execution, one signaling handshake).  Concurrent spans are not
     * supported — calling [startSpan] while a span is active replaces the previous
     * span ID.
     *
     * @return The new span identifier.
     */
    fun startSpan(): String {
        val id = generateSpanId()
        _spanId.set(id)
        return id
    }

    /**
     * Closes the active span.  After this call [currentSpanId] returns `null`.
     * Safe to call even when no span is active (no-op).
     */
    fun endSpan() {
        _spanId.set(null)
    }

    // ── ID generation helpers ──────────────────────────────────────────────────

    /**
     * Generates a fresh, globally-unique trace identifier (UUID v4 string).
     *
     * Intended to be called once per WS session; thereafter the same ID is reused
     * for every message in the session.
     */
    fun generateTraceId(): String = UUID.randomUUID().toString()

    /**
     * Generates a short span identifier (first 16 hex chars of a UUID v4).
     *
     * Shorter than a full UUID to reduce log verbosity while remaining collision-
     * resistant enough for a single device session.
     */
    fun generateSpanId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
}
