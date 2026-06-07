package com.ufo.galaxy.shared.protocol

/**
 * Unified reconnection and heartbeat configuration shared across Android and Wear OS.
 *
 * Previously the two projects used different constants:
 * - Android: heartbeat 30s, backoff 1s→2s→4s→8s→16s→30s, jitter 1s, max attempts 10
 * - Wear OS: heartbeat 20s, backoff 5s→10s→20s→30s, jitter 2s, no max attempts
 *
 * This object merges both into a single agreed-upon configuration that satisfies
 * Wear OS Doze-mode requirements (shorter heartbeat) while keeping Android's
 * tighter initial backoff for faster recovery.
 *
 * ## Agreed values
 * | Parameter            | Value    | Rationale                                    |
 * |----------------------|----------|----------------------------------------------|
 * | Initial delay        | 5 s      | Wear OS value — less aggressive than 1 s     |
 * | Max delay            | 30 s     | Shared cap — prevents excessive waiting      |
 * | Backoff multiplier   | 2.0x     | Standard exponential doubling                |
 * | Jitter               | ±1 s     | Compromise between Android 1s & Wear 2s      |
 * | Max attempts         | 20       | Shared — higher than Android's 10            |
 * | Heartbeat interval   | 20 s     | Wear OS value — prevents Doze/NAT timeouts   |
 * | Heartbeat timeout    | 10 s     | Shared — pong must arrive within this window |
 */
object ReconnectionConfig {
    /** Initial reconnect delay in milliseconds (5 s). */
    const val INITIAL_DELAY_MS = 5_000L

    /** Maximum reconnect delay in milliseconds (30 s cap). */
    const val MAX_DELAY_MS = 30_000L

    /** Exponential backoff multiplier (2.0x = double each attempt). */
    const val MULTIPLIER = 2.0

    /** Random jitter range in milliseconds (±1 s). */
    const val JITTER_MS = 1_000L

    /** Maximum consecutive reconnect attempts before resetting counter (20). */
    const val MAX_ATTEMPTS = 20

    /** Heartbeat (ping) emit interval in milliseconds (20 s). */
    const val HEARTBEAT_INTERVAL_MS = 20_000L

    /** Pong reception timeout in milliseconds (10 s). */
    const val HEARTBEAT_TIMEOUT_MS = 10_000L

    /** Maximum consecutive missed heartbeats before forcing reconnect (3). */
    const val MAX_MISSED_HEARTBEATS = 3

    /**
     * Compute the delay for reconnect attempt [attempt] using exponential backoff
     * with jitter.  Returns a value between
     * `[INITIAL_DELAY_MS - JITTER_MS, min(MAX_DELAY_MS, INITIAL_DELAY_MS * 2^attempt) + JITTER_MS]`.
     *
     * @param attempt Zero-based attempt counter.
     * @return Delay in milliseconds to wait before the next reconnect attempt.
     */
    fun computeDelay(attempt: Int): Long {
        val exponential = (INITIAL_DELAY_MS * kotlin.math.pow(MULTIPLIER, attempt.toDouble())).toLong()
        val capped = exponential.coerceAtMost(MAX_DELAY_MS)
        val jitter = (-JITTER_MS..JITTER_MS).random()
        return (capped + jitter).coerceAtLeast(1_000L)
    }

    /** Convenience: Kotlin [Double.pow] wrapper for Java callers. */
    private fun pow(base: Double, exp: Double): Double = kotlin.math.pow(base, exp)
}
