package com.ufo.galaxy.runtime

import com.ufo.galaxy.data.AppSettings

/**
 * PR-7 — Prior-session continuity hint presented to V2 when Android re-attaches after
 * process recreation.
 *
 * ## Purpose
 *
 * After Android's process is killed by the low-memory killer and subsequently recreated,
 * the live [DurableSessionContinuityRecord] is gone (it is process-scoped, in-memory only).
 * However, the prior [DurableSessionContinuityRecord.durableSessionId] is persisted in
 * [AppSettings] (SharedPreferences) as [AppSettings.lastDurableSessionId].
 *
 * This class packages that persisted identity hint so that V2 can optionally correlate
 * the re-attaching Android device with its prior session entry, rather than treating
 * every post-process-kill reconnect as an unrecognised new device.
 *
 * ## Authority boundary
 *
 * This hint is **advisory only**.  V2 is the canonical orchestration authority:
 *
 * - V2 decides whether to restore participant state for the returning device.
 * - Android MUST NOT self-authorize session continuation based on this hint.
 * - V2 MAY ignore the hint (e.g. if the prior session has already timed out).
 *
 * ## Wire format
 *
 * The hint is included as metadata in the `DeviceConnected` lifecycle event emitted
 * by Android when [ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.PROCESS_RECREATED_REATTACH]
 * is the active attachment semantics.  Use [toMetadataMap] to obtain the canonical
 * wire representation.
 *
 * ## Relationship to other continuity surfaces
 *
 * | Surface | Stability | Survives process kill? | Owner |
 * |---|---|---|---|
 * | [DurableSessionContinuityRecord.durableSessionId] | Per activation era | ❌ In-memory only | Android |
 * | [AppSettings.lastDurableSessionId] | Across process kills | ✅ SharedPreferences | Android |
 * | [ProcessRecreatedReattachHint.priorDurableSessionId] | Carries persisted value at re-attach | ✅ (read from AppSettings) | Android |
 * | `continuity_token` | V2-managed durable dispatch context | V2-side | V2 coordinator |
 *
 * @property priorDurableSessionId The [DurableSessionContinuityRecord.durableSessionId] from
 *                                  the most recently completed activation era.  Read from
 *                                  [AppSettings.lastDurableSessionId] at re-attach time.
 * @property deviceId               The stable device identifier from [AppSettings.deviceId].
 *                                  Included so V2 can correlate the hint with the correct
 *                                  device entry without additional lookups.
 * @property priorSessionActivationEpochMs Epoch-millisecond timestamp when the prior durable
 *                                  session era started.  `0L` when unknown (field was not
 *                                  persisted by an earlier runtime version).
 *
 * @see AppSettings.lastDurableSessionId
 * @see DurableSessionContinuityRecord
 * @see ParticipantAttachmentTransitionSemantics.AttachmentRecoverySemantics.PROCESS_RECREATED_REATTACH
 */
data class ProcessRecreatedReattachHint(
    val priorDurableSessionId: String,
    val deviceId: String,
    val priorSessionActivationEpochMs: Long = 0L
) {

    // ── Wire serialisation ────────────────────────────────────────────────────

    /**
     * Returns the canonical wire map for this hint, suitable for merging into a
     * `DeviceConnected` lifecycle event metadata payload.
     *
     * [KEY_PRIOR_DURABLE_SESSION_ID] and [KEY_DEVICE_ID] are always present.
     * [KEY_PRIOR_SESSION_ACTIVATION_EPOCH_MS] is included only when
     * [priorSessionActivationEpochMs] > 0 (i.e. the timestamp was persisted).
     *
     * @return An immutable [Map] with the hint fields.
     */
    fun toMetadataMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            KEY_PRIOR_DURABLE_SESSION_ID to priorDurableSessionId,
            KEY_DEVICE_ID to deviceId,
            KEY_ATTACHMENT_RECOVERY_REASON to RECOVERY_REASON_VALUE
        )
        if (priorSessionActivationEpochMs > 0L) {
            map[KEY_PRIOR_SESSION_ACTIVATION_EPOCH_MS] = priorSessionActivationEpochMs
        }
        return map.toMap()
    }

    // ── Companion / factory ───────────────────────────────────────────────────

    companion object {

        // ── Wire key constants ────────────────────────────────────────────────

        /**
         * Wire key for [priorDurableSessionId].
         *
         * This key is included in the `DeviceConnected` event metadata so V2 can
         * optionally correlate the re-attaching device with its prior session.
         */
        const val KEY_PRIOR_DURABLE_SESSION_ID = "prior_durable_session_id"

        /**
         * Wire key for [deviceId].
         *
         * Included alongside [KEY_PRIOR_DURABLE_SESSION_ID] so V2 can match the hint
         * to the correct device registry entry.
         */
        const val KEY_DEVICE_ID = "device_id"

        /**
         * Wire key for [priorSessionActivationEpochMs].
         *
         * Optional — present only when the prior session's activation timestamp was
         * persisted.  V2 may use this for session age / staleness checks.
         */
        const val KEY_PRIOR_SESSION_ACTIVATION_EPOCH_MS = "prior_session_activation_epoch_ms"

        /**
         * Wire key indicating the recovery reason for this attachment.
         *
         * Always set to [RECOVERY_REASON_VALUE] when this hint is present, so that V2
         * can distinguish process-recreation re-attach from other `DeviceConnected` types.
         */
        const val KEY_ATTACHMENT_RECOVERY_REASON = "attachment_recovery_reason"

        /**
         * Wire value for [KEY_ATTACHMENT_RECOVERY_REASON] when this hint is included.
         *
         * Matches [ContinuityRecoveryContext.REASON_PROCESS_RECREATION].
         */
        const val RECOVERY_REASON_VALUE = "process_recreation"

        // ── Factory ───────────────────────────────────────────────────────────

        /**
         * Constructs a [ProcessRecreatedReattachHint] from the persisted fields in
         * [settings], or returns `null` when no prior session identity is available.
         *
         * Returns `null` when [AppSettings.lastDurableSessionId] is blank (true first
         * launch, or after an explicit identity clear).
         *
         * @param settings The [AppSettings] instance to read persisted fields from.
         * @return A [ProcessRecreatedReattachHint] if a prior session identity is
         *         available, `null` otherwise.
         */
        fun fromAppSettings(settings: AppSettings): ProcessRecreatedReattachHint? {
            val priorId = settings.lastDurableSessionId
            if (priorId.isBlank()) return null
            return ProcessRecreatedReattachHint(
                priorDurableSessionId = priorId,
                deviceId = settings.deviceId
            )
        }
    }
}
