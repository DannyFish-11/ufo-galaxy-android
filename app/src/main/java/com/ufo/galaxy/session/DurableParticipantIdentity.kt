package com.ufo.galaxy.session

import com.ufo.galaxy.runtime.DurableSessionContinuityRecord
import java.util.UUID

/**
 * PR-79 (Android) — Durable participant identity with explicit freshness semantics.
 *
 * [DurableParticipantIdentity] is the stable, cross-session Android participant identity
 * anchor for V2's `android_device_state_store.py` and `unified_governance_semantics.py`
 * canonical consumers.
 *
 * ## Problem addressed (R7 / R11)
 *
 * Before this class, V2 canonical consumers had to reconstruct participant identity from
 * a combination of:
 *  - `device_id` (device hardware identifier)
 *  - `durable_session_id` (from [DurableSessionContinuityRecord] — session-scoped)
 *  - `runtime_session_id` (per-WS-connection — ephemeral)
 *  - `attached_session_id` (per-attached-session — replaced on each reconnect)
 *
 * This left two specific gaps:
 *
 *  1. **No participant-level identity anchor that survives process recreation.**  The
 *     `durable_session_id` is per activation era — it resets on process kill.  V2's
 *     `android_device_state_store.py` needed a stable identifier to correlate device
 *     state records across era boundaries (e.g., a device that was killed and restarted
 *     with new `durable_session_id`).
 *
 *  2. **No explicit freshness semantics.**  V2's `unified_governance_semantics.py` had
 *     no Android-originated signal indicating whether the participant's current identity
 *     was freshly registered, recovered from a reconnect, or potentially stale (the device
 *     had been offline for a long time).  Without this, V2 had to apply its own arbitrary
 *     staleness heuristics.
 *
 * [DurableParticipantIdentity] closes both gaps:
 *  1. [participantId] is the **installation-scoped stable identifier** — constant across
 *     all activation eras and process recreations for a given app installation.
 *  2. [IdentityFreshness] is the explicit **Android-side freshness classification** that
 *     V2 can read directly without applying heuristics.
 *
 * ## Relationship to session identity hierarchy
 *
 *  | Identifier             | Stability scope              | Resets on                          |
 *  |------------------------|------------------------------|------------------------------------|
 *  | [participantId]        | Per app installation         | App uninstall only                 |
 *  | `durable_session_id`   | Per activation era           | process kill or explicit `stop()`  |
 *  | `runtime_session_id`   | Per WS connection            | Every reconnect                    |
 *  | `attached_session_id`  | Per attached session         | Reconnect (new session opened)     |
 *
 * [participantId] is the most durable Android-side identifier and forms the top of the
 * participant identity hierarchy.  V2's `android_device_state_store.py` SHOULD use it as
 * the primary key for cross-era participant-record correlation.
 *
 * ## Freshness classification
 *
 * Android classifies its own identity freshness at registration time using [IdentityFreshness]:
 *
 *  - [IdentityFreshness.FRESH]: first registration in this installation, or after a long
 *    offline gap that exceeded the stale threshold.  V2 should treat the participant as
 *    newly joining.
 *  - [IdentityFreshness.RECOVERED]: participant re-registered after a process kill or WS
 *    reconnect within the same ongoing session era.  V2 should associate the new session
 *    state with the known participant record.
 *  - [IdentityFreshness.STALE]: Android detected its own session was stale at registration
 *    time (offline gap exceeds [STALE_THRESHOLD_MS]).  V2 SHOULD treat this as an implicit
 *    session-truth invalidation and re-validate all cached participant state.
 *
 * @property participantId            Stable per-installation UUID.  Constant until uninstall.
 * @property deviceId                 Device hardware identifier (from [AppSettings.deviceId]).
 * @property deviceRole               Logical device role (e.g. `"phone"`, `"tablet"`).
 * @property freshness                [IdentityFreshness] classification for this registration.
 * @property durableSessionId         The [DurableSessionContinuityRecord.durableSessionId] of the
 *                                    current activation era; `null` before first activation.
 * @property sessionContinuityEpoch   The [DurableSessionContinuityRecord.sessionContinuityEpoch]
 *                                    of the current era; `0` at first attach.
 * @property registrationEpochMs      Epoch-milliseconds when this identity was last registered.
 * @property lastActiveEpochMs        Epoch-milliseconds of the most recent liveness signal
 *                                    (heartbeat, state snapshot, or execution event).
 */
data class DurableParticipantIdentity(
    val participantId: String,
    val deviceId: String,
    val deviceRole: String,
    val freshness: IdentityFreshness,
    val durableSessionId: String?,
    val sessionContinuityEpoch: Int,
    val registrationEpochMs: Long,
    val lastActiveEpochMs: Long
) {

    // ── Freshness classification ───────────────────────────────────────────────

    /**
     * Android-side freshness classification for this participant identity.
     *
     * V2's `unified_governance_semantics.py` MUST read this field directly rather than
     * computing its own freshness heuristic from timestamps.  The Android-side classification
     * is authoritative for two reasons:
     *  1. Android knows the reason for any offline gap (process kill, user-backgrounded,
     *     explicit stop) and can classify freshness more accurately than a pure timestamp diff.
     *  2. Using Android's classification prevents drift between Android's self-reported
     *     state and V2's independently-computed staleness verdict.
     *
     * @property wireValue    Stable lowercase string for wire serialization.
     * @property displayName  Short human-readable label.
     * @property description  One-sentence semantic description.
     * @property v2Guidance   How V2 `unified_governance_semantics.py` should treat participants
     *                        with this freshness classification.
     */
    enum class IdentityFreshness(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val v2Guidance: String
    ) {

        /**
         * First registration in this installation, or re-registration after the
         * offline gap exceeded [STALE_THRESHOLD_MS] (longer than stale threshold).
         *
         * V2 SHOULD treat this participant as newly joining: validate the capability
         * report fully, do not apply continuity assumptions from prior records.
         */
        FRESH(
            wireValue = "fresh",
            displayName = "Fresh",
            description = "New registration or re-registration after offline gap exceeding stale threshold.",
            v2Guidance = "Treat as newly joining participant; validate capability report; no continuity assumptions."
        ),

        /**
         * Re-registration after a process kill or WS reconnect within an ongoing
         * activation era (offline gap less than [STALE_THRESHOLD_MS]).
         *
         * V2 SHOULD associate the new session state with the known participant record.
         * The [sessionContinuityEpoch] differentiates successive reconnects within the
         * same era.
         */
        RECOVERED(
            wireValue = "recovered",
            displayName = "Recovered",
            description = "Re-registration after process kill or reconnect within the current era.",
            v2Guidance = "Associate with existing participant record; epoch identifies reconnect sequence."
        ),

        /**
         * Android detected its own identity as stale at registration time.  This means the
         * previous `durable_session_id` or `participantId` registration is no longer valid.
         *
         * V2 MUST re-validate all cached state for this participant and treat the prior
         * participant record as invalidated.  This is distinct from [FRESH] in that Android
         * explicitly signals the invalidation rather than V2 detecting it.
         */
        STALE(
            wireValue = "stale",
            displayName = "Stale",
            description = "Android detected its own identity as stale; prior participant record is invalid.",
            v2Guidance = "Re-validate all cached participant state; treat prior record as invalidated."
        );

        companion object {
            /** Parses [value] to an [IdentityFreshness], or returns `null` for unknown values. */
            fun fromWireValue(value: String?): IdentityFreshness? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values for this enum. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Derived helpers ───────────────────────────────────────────────────────

    /**
     * `true` when this identity is considered current and non-stale, i.e.,
     * [freshness] is [IdentityFreshness.FRESH] or [IdentityFreshness.RECOVERED].
     *
     * V2 SHOULD treat `false` (i.e., [IdentityFreshness.STALE]) as a signal to
     * invalidate cached participant state.
     */
    val isCurrent: Boolean
        get() = freshness != IdentityFreshness.STALE

    /**
     * `true` when this identity was recovered from a prior session era
     * ([IdentityFreshness.RECOVERED]).
     *
     * Recovered identities carry continuity: V2 SHOULD use [sessionContinuityEpoch]
     * to correlate successive sessions.
     */
    val isRecovered: Boolean
        get() = freshness == IdentityFreshness.RECOVERED

    /**
     * The offline gap in milliseconds between [registrationEpochMs] and [lastActiveEpochMs].
     *
     * A value of `0` means the identity was just registered.  Used by V2 to decide whether
     * to apply freshness-threshold re-evaluation independent of [freshness].
     */
    val offlineGapMs: Long
        get() = maxOf(0L, registrationEpochMs - lastActiveEpochMs)

    // ── Wire serialisation ────────────────────────────────────────────────────

    /**
     * Builds the canonical wire map for this participant identity.
     *
     * All keys are stable and align with the field names expected by V2's
     * `android_device_state_store.py`.  This map SHOULD be merged into
     * `DeviceStateSnapshotPayload` metadata payloads at emission time.
     *
     * Keys:
     *  - [KEY_PARTICIPANT_ID]             — [participantId]
     *  - [KEY_DEVICE_ID]                  — [deviceId]
     *  - [KEY_DEVICE_ROLE]                — [deviceRole]
     *  - [KEY_IDENTITY_FRESHNESS]         — [IdentityFreshness.wireValue]
     *  - [KEY_DURABLE_SESSION_ID]         — [durableSessionId], or absent if null
     *  - [KEY_SESSION_CONTINUITY_EPOCH]   — [sessionContinuityEpoch]
     *  - [KEY_REGISTRATION_EPOCH_MS]      — [registrationEpochMs]
     *  - [KEY_LAST_ACTIVE_EPOCH_MS]       — [lastActiveEpochMs]
     *  - [KEY_IS_CURRENT]                 — [isCurrent]
     *  - [KEY_IS_RECOVERED]               — [isRecovered]
     *  - [KEY_OFFLINE_GAP_MS]             — [offlineGapMs]
     *  - [KEY_SCHEMA_VERSION]             — [SCHEMA_VERSION]
     */
    fun toWireMap(): Map<String, Any> = buildMap {
        put(KEY_PARTICIPANT_ID, participantId)
        put(KEY_DEVICE_ID, deviceId)
        put(KEY_DEVICE_ROLE, deviceRole)
        put(KEY_IDENTITY_FRESHNESS, freshness.wireValue)
        put(KEY_SESSION_CONTINUITY_EPOCH, sessionContinuityEpoch)
        put(KEY_REGISTRATION_EPOCH_MS, registrationEpochMs)
        put(KEY_LAST_ACTIVE_EPOCH_MS, lastActiveEpochMs)
        put(KEY_IS_CURRENT, isCurrent)
        put(KEY_IS_RECOVERED, isRecovered)
        put(KEY_OFFLINE_GAP_MS, offlineGapMs)
        put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        durableSessionId?.let { put(KEY_DURABLE_SESSION_ID, it) }
    }

    companion object {

        // ── Schema versioning ─────────────────────────────────────────────────

        /** Wire-map schema version. Allows V2 to detect field-contract drift. */
        const val SCHEMA_VERSION = "1.0"

        // ── Wire key constants ────────────────────────────────────────────────

        /** Wire key for the stable per-installation participant identifier. */
        const val KEY_PARTICIPANT_ID = "durable_participant_id"

        /** Wire key for the device hardware identifier. */
        const val KEY_DEVICE_ID = "device_id"

        /** Wire key for the logical device role (e.g. "phone", "tablet"). */
        const val KEY_DEVICE_ROLE = "device_role"

        /**
         * Wire key for the [IdentityFreshness] classification.
         * V2 MUST read this rather than computing its own freshness heuristic.
         */
        const val KEY_IDENTITY_FRESHNESS = "participant_identity_freshness"

        /**
         * Wire key for the current [DurableSessionContinuityRecord.durableSessionId].
         * Absent when no activation era is active.
         */
        const val KEY_DURABLE_SESSION_ID = "durable_session_id"

        /**
         * Wire key for [sessionContinuityEpoch].
         * Identifies the reconnect sequence count within the current activation era.
         */
        const val KEY_SESSION_CONTINUITY_EPOCH = "session_continuity_epoch"

        /** Wire key for [registrationEpochMs] (epoch-ms of last registration). */
        const val KEY_REGISTRATION_EPOCH_MS = "participant_registration_epoch_ms"

        /** Wire key for [lastActiveEpochMs] (epoch-ms of last liveness signal). */
        const val KEY_LAST_ACTIVE_EPOCH_MS = "participant_last_active_epoch_ms"

        /** Wire key for [isCurrent] (false signals V2 to invalidate cached state). */
        const val KEY_IS_CURRENT = "participant_identity_is_current"

        /** Wire key for [isRecovered] (true when [IdentityFreshness.RECOVERED]). */
        const val KEY_IS_RECOVERED = "participant_identity_is_recovered"

        /** Wire key for [offlineGapMs] (last-active to registration gap in ms). */
        const val KEY_OFFLINE_GAP_MS = "participant_offline_gap_ms"

        /** Wire key for the schema version. */
        const val KEY_SCHEMA_VERSION = "participant_identity_schema_version"

        /** All required wire keys that must be present in a valid [toWireMap] output. */
        val REQUIRED_WIRE_KEYS: Set<String> = setOf(
            KEY_PARTICIPANT_ID,
            KEY_DEVICE_ID,
            KEY_DEVICE_ROLE,
            KEY_IDENTITY_FRESHNESS,
            KEY_SESSION_CONTINUITY_EPOCH,
            KEY_REGISTRATION_EPOCH_MS,
            KEY_LAST_ACTIVE_EPOCH_MS,
            KEY_IS_CURRENT,
            KEY_IS_RECOVERED,
            KEY_OFFLINE_GAP_MS,
            KEY_SCHEMA_VERSION
        )

        /**
         * The offline gap threshold in milliseconds above which Android classifies
         * its own identity as [IdentityFreshness.STALE].
         *
         * Value: 24 hours (86 400 000 ms).  Chosen to match the typical V2-side
         * participant inactivity timeout; Android classifies itself as stale before
         * V2 would independently flag it, ensuring the participant re-registers
         * cleanly rather than V2 applying a forced eviction.
         */
        const val STALE_THRESHOLD_MS: Long = 86_400_000L // 24 hours

        // ── Factory ───────────────────────────────────────────────────────────

        /**
         * Creates a new [DurableParticipantIdentity] for a first-time or post-stale
         * registration.
         *
         * [freshness] is set to [IdentityFreshness.FRESH].
         * [participantId] is a freshly generated UUID v4.
         *
         * @param deviceId             Device hardware identifier.
         * @param deviceRole           Logical device role.
         * @param durableSessionId     Current [DurableSessionContinuityRecord.durableSessionId],
         *                             or `null` when not yet activated.
         * @param sessionContinuityEpoch Current epoch counter.
         * @return A new [DurableParticipantIdentity] with [IdentityFreshness.FRESH].
         */
        fun createFresh(
            deviceId: String,
            deviceRole: String,
            durableSessionId: String? = null,
            sessionContinuityEpoch: Int = 0
        ): DurableParticipantIdentity {
            val now = System.currentTimeMillis()
            return DurableParticipantIdentity(
                participantId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                deviceRole = deviceRole,
                freshness = IdentityFreshness.FRESH,
                durableSessionId = durableSessionId,
                sessionContinuityEpoch = sessionContinuityEpoch,
                registrationEpochMs = now,
                lastActiveEpochMs = now
            )
        }

        /**
         * Creates a [DurableParticipantIdentity] for a recovery re-registration
         * (process kill or WS reconnect within the same era).
         *
         * [freshness] is set to [IdentityFreshness.RECOVERED].
         * [participantId] is preserved from the prior record ([existingParticipantId]).
         *
         * @param existingParticipantId  The stable [participantId] from the prior record.
         * @param deviceId               Device hardware identifier.
         * @param deviceRole             Logical device role.
         * @param continuityRecord       The current [DurableSessionContinuityRecord] for the
         *                               new activation era.
         * @param lastActiveEpochMs      The [lastActiveEpochMs] from the prior record, used
         *                               to compute [offlineGapMs].
         * @return A [DurableParticipantIdentity] with [IdentityFreshness.RECOVERED].
         */
        fun createRecovered(
            existingParticipantId: String,
            deviceId: String,
            deviceRole: String,
            continuityRecord: DurableSessionContinuityRecord,
            lastActiveEpochMs: Long
        ): DurableParticipantIdentity = DurableParticipantIdentity(
            participantId = existingParticipantId,
            deviceId = deviceId,
            deviceRole = deviceRole,
            freshness = IdentityFreshness.RECOVERED,
            durableSessionId = continuityRecord.durableSessionId,
            sessionContinuityEpoch = continuityRecord.sessionContinuityEpoch,
            registrationEpochMs = System.currentTimeMillis(),
            lastActiveEpochMs = lastActiveEpochMs
        )

        /**
         * Creates a [DurableParticipantIdentity] by inspecting the offline gap and
         * applying [STALE_THRESHOLD_MS] to determine [IdentityFreshness].
         *
         * - If no prior [existingParticipantId] is known → [IdentityFreshness.FRESH] with
         *   a new [participantId].
         * - If [offlineGapMs] >= [STALE_THRESHOLD_MS] → [IdentityFreshness.STALE] with
         *   a new [participantId] (prior identity is invalidated).
         * - If [offlineGapMs] < [STALE_THRESHOLD_MS] → [IdentityFreshness.RECOVERED] with
         *   the existing [participantId] preserved.
         *
         * @param existingParticipantId  The prior participant ID, or `null` for first registration.
         * @param deviceId               Device hardware identifier.
         * @param deviceRole             Logical device role.
         * @param continuityRecord       The [DurableSessionContinuityRecord] for the new era.
         * @param lastActiveEpochMs      Epoch-ms of the most recent liveness signal; used to
         *                               compute the offline gap.
         * @return The appropriate [DurableParticipantIdentity] for the given inputs.
         */
        fun createFromGap(
            existingParticipantId: String?,
            deviceId: String,
            deviceRole: String,
            continuityRecord: DurableSessionContinuityRecord,
            lastActiveEpochMs: Long
        ): DurableParticipantIdentity {
            val now = System.currentTimeMillis()
            val offlineGap = maxOf(0L, now - lastActiveEpochMs)

            return when {
                existingParticipantId == null -> {
                    // No prior identity — always fresh.
                    DurableParticipantIdentity(
                        participantId = UUID.randomUUID().toString(),
                        deviceId = deviceId,
                        deviceRole = deviceRole,
                        freshness = IdentityFreshness.FRESH,
                        durableSessionId = continuityRecord.durableSessionId,
                        sessionContinuityEpoch = continuityRecord.sessionContinuityEpoch,
                        registrationEpochMs = now,
                        lastActiveEpochMs = lastActiveEpochMs
                    )
                }
                offlineGap >= STALE_THRESHOLD_MS -> {
                    // Offline gap exceeds threshold — identity is stale, rotate participantId.
                    DurableParticipantIdentity(
                        participantId = UUID.randomUUID().toString(),
                        deviceId = deviceId,
                        deviceRole = deviceRole,
                        freshness = IdentityFreshness.STALE,
                        durableSessionId = continuityRecord.durableSessionId,
                        sessionContinuityEpoch = continuityRecord.sessionContinuityEpoch,
                        registrationEpochMs = now,
                        lastActiveEpochMs = lastActiveEpochMs
                    )
                }
                else -> {
                    // Gap is within threshold — recovered identity, preserve participantId.
                    DurableParticipantIdentity(
                        participantId = existingParticipantId,
                        deviceId = deviceId,
                        deviceRole = deviceRole,
                        freshness = IdentityFreshness.RECOVERED,
                        durableSessionId = continuityRecord.durableSessionId,
                        sessionContinuityEpoch = continuityRecord.sessionContinuityEpoch,
                        registrationEpochMs = now,
                        lastActiveEpochMs = lastActiveEpochMs
                    )
                }
            }
        }

        /**
         * Updates the [lastActiveEpochMs] of an existing [DurableParticipantIdentity]
         * without changing any other field.
         *
         * Call this on each heartbeat, state snapshot, or execution event to keep the
         * identity record fresh for staleness detection purposes.
         *
         * @param identity      The current [DurableParticipantIdentity].
         * @param epochMs       The new [lastActiveEpochMs]; defaults to [System.currentTimeMillis].
         * @return A copy with [lastActiveEpochMs] set to [epochMs].
         */
        fun touch(
            identity: DurableParticipantIdentity,
            epochMs: Long = System.currentTimeMillis()
        ): DurableParticipantIdentity = identity.copy(lastActiveEpochMs = epochMs)
    }
}
