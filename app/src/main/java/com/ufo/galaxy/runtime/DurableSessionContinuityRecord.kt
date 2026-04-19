package com.ufo.galaxy.runtime

import java.util.UUID

/**
 * PR-1 — Durable session continuity record for the Android mesh session runtime foundation.
 *
 * Models a **durable session identity** that persists across WS reconnects within a single
 * activation era.  Unlike [AttachedRuntimeSession.sessionId] (stable within one attached
 * session but replaced when the session is closed and reopened on reconnect) and the per-WS-
 * connection `runtime_session_id` (regenerated on every reconnect), a
 * [DurableSessionContinuityRecord] provides:
 *
 *  - A [durableSessionId] that remains constant across **all reconnect cycles** within the same
 *    activation era (i.e., from user-activation or background-restore until explicit stop or
 *    invalidation).
 *  - A [sessionContinuityEpoch] counter that increments monotonically with each transparent
 *    reconnect within the era, allowing host-side consumers to detect and correlate reconnect
 *    events without losing the overarching session identity.
 *
 * ## Relationship to other session identifiers and continuity_token
 *
 *  | Identifier / token                | Stability                       | Resets on                              | Owner           |
 *  |-----------------------------------|---------------------------------|----------------------------------------|-----------------|
 *  | `runtime_session_id`              | Per WS connection               | Every reconnect                        | Android         |
 *  | `attached_runtime_session_id`     | Per attached session            | Reconnect (new session opened)         | Android         |
 *  | [durableSessionId] (this record)  | Per activation era              | `stop()` or `invalidateSession()` only | Android         |
 *  | `continuity_token`                | Per durable dispatch context    | New dispatch (not reconnect)           | V2 coordinator  |
 *  | `continuation_token`              | Per delegated handoff           | Every new handoff delegation           | Originating executor |
 *
 * **Identity boundary rules:**
 * - [durableSessionId] is the **most stable** Android-side session identity: constant
 *   across all reconnects within a single activation era.  It forms the top of the
 *   Android continuity hierarchy.
 * - `continuity_token` is a **V2-managed** opaque token that spans the durable
 *   execution context.  It may correlate multiple [durableSessionId] values across
 *   different devices or hand-off sequences, but its lifecycle is controlled by V2,
 *   not Android.  Android echoes it back but MUST NOT generate or modify it.
 * - `continuation_token` is an **executor-managed** per-handoff state token; it is
 *   absent for pure reconnect-recovery dispatches and must not be confused with
 *   `continuity_token`.
 * - `attached_runtime_session_id` is scoped to one attached session and is replaced
 *   on each transparent reconnect.  V2 can correlate successive values using
 *   [durableSessionId].
 *
 * [durableSessionId] lets the center-side system treat multiple successive
 * `attached_runtime_session_id` values (from the same activation era, across reconnects) as
 * belonging to the same durable runtime participation.
 *
 * ## Session continuity epoch
 *
 * [sessionContinuityEpoch] is a monotone, zero-indexed counter:
 *  - `0` at first attach (initial activation).
 *  - `1` after the first transparent reconnect within the same era.
 *  - `N` after `N` transparent reconnects.
 *
 * The epoch enables host-side consumers to distinguish "same session, reconnected again" from
 * "brand-new session started from scratch."  The epoch is purely informational; no behavioral
 * gates are conditioned on its value.
 *
 * ## Lifecycle
 *
 * ```
 * USER_ACTIVATION / BACKGROUND_RESTORE (no existing durable record)
 *        │
 *        ▼
 *  ┌─────────────────────┐   RECONNECT_RECOVERY   ┌──────────────────────────────────┐
 *  │ DurableSession(e=0) │ ──────────────────────▶ │ DurableSession(e=1, same id)     │ ──▶ …
 *  └─────────────────────┘                          └──────────────────────────────────┘
 *        │
 *        │ stop() / invalidateSession()
 *        ▼
 *      null  (next activation starts a new era)
 * ```
 *
 * [RuntimeController] creates and maintains one [DurableSessionContinuityRecord] per activation
 * era.  It is projected into [AttachedRuntimeHostSessionSnapshot] via the [KEY_DURABLE_SESSION_ID]
 * and [KEY_SESSION_CONTINUITY_EPOCH] snapshot fields so host-facing consumers can access durable
 * session continuity without additional queries.
 *
 * ## Design intent
 *
 * This record is **additive and compatibility-safe**.  It does not change any existing runtime
 * behavior, wire contracts, or identifier values.  Existing consumers of [AttachedRuntimeSession],
 * [AttachedRuntimeHostSessionSnapshot], and [DelegatedTargetReadinessProjection] continue to work
 * without modification; the durable fields are optional additions to the snapshot.
 *
 * @property durableSessionId       Stable UUID identifying this activation era.  Constant across
 *                                  all reconnects within the era; reset only by [RuntimeController.stop]
 *                                  or [RuntimeController.invalidateSession].
 * @property sessionContinuityEpoch Monotone counter of reconnect events within this era.
 *                                  `0` at first attach; incremented with each [SessionOpenSource.RECONNECT_RECOVERY].
 * @property activationEpochMs      Epoch-millisecond timestamp when this durable session era started.
 * @property activationSource       Wire value of the [RuntimeController] session-open source that
 *                                  started this era (e.g., `"user_activation"`, `"background_restore"`).
 */
data class DurableSessionContinuityRecord(
    val durableSessionId: String,
    val sessionContinuityEpoch: Int,
    val activationEpochMs: Long,
    val activationSource: String
) {

    // ── Derived helpers ───────────────────────────────────────────────────────

    /**
     * Returns a copy with [sessionContinuityEpoch] incremented by one.
     *
     * Call on each successful reconnect within the same activation era so that host-side
     * consumers can correlate the new attached session with the prior one while recognising
     * that a transparent reconnect occurred.
     *
     * @return A new [DurableSessionContinuityRecord] with epoch == old epoch + 1; all other
     *         fields are unchanged.
     */
    fun withEpochIncremented(): DurableSessionContinuityRecord =
        copy(sessionContinuityEpoch = sessionContinuityEpoch + 1)

    // ── Serialisation ─────────────────────────────────────────────────────────

    /**
     * Builds the canonical wire map for this durable session continuity record.
     *
     * All four fields are always included in the returned map.
     *
     * @return An immutable [Map] suitable for merging into AIP v3 metadata payloads or for
     *         transmission to the main-repo authoritative session registry.
     */
    fun toMetadataMap(): Map<String, Any> = mapOf(
        KEY_DURABLE_SESSION_ID to durableSessionId,
        KEY_SESSION_CONTINUITY_EPOCH to sessionContinuityEpoch,
        KEY_ACTIVATION_EPOCH_MS to activationEpochMs,
        KEY_ACTIVATION_SOURCE to activationSource
    )

    // ── Companion / factory ───────────────────────────────────────────────────

    companion object {

        // ── Wire key constants ────────────────────────────────────────────────

        /**
         * Wire key for [durableSessionId].
         *
         * This key aligns with the `durable_session_id` canonical cross-repo term used in
         * the center-side durable mesh/session contracts.
         */
        const val KEY_DURABLE_SESSION_ID = "durable_session_id"

        /** Wire key for [sessionContinuityEpoch]. */
        const val KEY_SESSION_CONTINUITY_EPOCH = "session_continuity_epoch"

        /** Wire key for [activationEpochMs]. */
        const val KEY_ACTIVATION_EPOCH_MS = "durable_session_activation_epoch_ms"

        /** Wire key for [activationSource]. */
        const val KEY_ACTIVATION_SOURCE = "durable_session_activation_source"

        // ── All keys — useful in tests ────────────────────────────────────────

        /** All four wire keys defined on this record. */
        val ALL_KEYS: Set<String> = setOf(
            KEY_DURABLE_SESSION_ID,
            KEY_SESSION_CONTINUITY_EPOCH,
            KEY_ACTIVATION_EPOCH_MS,
            KEY_ACTIVATION_SOURCE
        )

        // ── Factory ───────────────────────────────────────────────────────────

        /**
         * Creates a new [DurableSessionContinuityRecord] for a new activation era.
         *
         * The [durableSessionId] is a freshly generated UUID v4.  [sessionContinuityEpoch]
         * starts at `0`.  [activationEpochMs] is set to [System.currentTimeMillis].
         *
         * @param activationSource Wire value of the [RuntimeController] session-open source
         *                         that initiated this era (e.g., `"user_activation"`).
         * @return A new [DurableSessionContinuityRecord] at epoch 0.
         */
        fun create(activationSource: String): DurableSessionContinuityRecord =
            DurableSessionContinuityRecord(
                durableSessionId = UUID.randomUUID().toString(),
                sessionContinuityEpoch = 0,
                activationEpochMs = System.currentTimeMillis(),
                activationSource = activationSource
            )
    }
}
