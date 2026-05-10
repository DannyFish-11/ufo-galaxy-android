package com.ufo.galaxy.runtime

import com.ufo.galaxy.observability.GalaxyLogger

/**
 * PR-A1 (Android) — Barrier coordination participant for Android-side mesh runtime.
 *
 * Manages Android's runtime position in V2-coordinated barrier/release cycles.
 * This class closes the [HybridParticipantCapability.BARRIER_COORDINATION] gap that
 * previously kept [AndroidMeshParticipationContract.ParticipationReport.fullMeshRuntimeExecutable]
 * always `false`.
 *
 * ## Design
 *
 *  - Android is a **barrier participant**, not a barrier authority.  V2 coordinates barrier
 *    decisions; this class provides Android's runnable response path.
 *  - State transitions follow the [BarrierParticipationState] lifecycle:
 *    ```
 *    NOT_APPLICABLE → WAITING → RELEASED → NOT_APPLICABLE
 *                           ↘ TIMED_OUT → NOT_APPLICABLE
 *    ```
 *  - [enterBarrierWait] transitions from [BarrierParticipationState.NOT_APPLICABLE] to
 *    [BarrierParticipationState.WAITING] and records the barrier session identity.
 *  - [acknowledgeBarrierRelease] transitions from WAITING to
 *    [BarrierParticipationState.RELEASED], confirming V2's release signal was received.
 *  - [handleBarrierTimeout] transitions from WAITING to
 *    [BarrierParticipationState.TIMED_OUT], indicating the local timeout expired.
 *  - [resetBarrier] transitions any state back to [BarrierParticipationState.NOT_APPLICABLE],
 *    preparing for the next barrier rendezvous.
 *  - [currentState] exposes the live [BarrierParticipationState] for snapshot reporting.
 *  - All transitions emit [GalaxyLogger.TAG_HYBRID_PARTICIPANT] log entries for tracing.
 *
 * ## Capability promotion
 *
 * The presence of this class is what permits
 * [HybridParticipantCapability.BARRIER_COORDINATION] to carry
 * [HybridParticipantCapability.SupportLevel.AVAILABLE].  It provides the runnable
 * barrier participation path that Android previously lacked.
 *
 * @see HybridParticipantCapability.BARRIER_COORDINATION
 * @see BarrierParticipationState
 * @see AndroidMeshParticipationContract
 */
class BarrierCoordinationParticipant {

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile
    private var _state: BarrierParticipationState = BarrierParticipationState.NOT_APPLICABLE

    @Volatile
    private var _barrierSessionId: String? = null

    /**
     * The current [BarrierParticipationState] of this participant.
     *
     * Exposed for snapshot reporting via [DeviceStateSnapshotPayload.barrier_participation_state].
     * Always reflects the live state after the last transition.
     */
    val currentState: BarrierParticipationState
        get() = _state

    /**
     * The barrier session identifier from the most recent [enterBarrierWait] call.
     * `null` when no barrier session is active ([BarrierParticipationState.NOT_APPLICABLE]).
     */
    val currentBarrierSessionId: String?
        get() = _barrierSessionId

    // ── Transitions ───────────────────────────────────────────────────────────

    /**
     * Enters the barrier-wait state for the given [barrierSessionId].
     *
     * Transitions from [BarrierParticipationState.NOT_APPLICABLE] to
     * [BarrierParticipationState.WAITING].  If the current state is already
     * [BarrierParticipationState.WAITING], the call is a no-op (idempotent).
     *
     * This MUST be called when Android reaches a barrier rendezvous point in a
     * V2-coordinated mesh session.  V2 will then observe [currentState] as WAITING
     * via [DeviceStateSnapshotPayload] and include this participant in its barrier
     * completion evaluation.
     *
     * @param barrierSessionId Stable identifier of the barrier session assigned by V2.
     * @return `true` if the transition was executed; `false` if already waiting.
     */
    fun enterBarrierWait(barrierSessionId: String): Boolean {
        if (_state == BarrierParticipationState.WAITING) return false
        _barrierSessionId = barrierSessionId
        _state = BarrierParticipationState.WAITING
        GalaxyLogger.log(
            GalaxyLogger.TAG_HYBRID_PARTICIPANT,
            mapOf(
                "event"              to "barrier_enter_wait",
                "barrier_session_id" to barrierSessionId
            )
        )
        return true
    }

    /**
     * Acknowledges a barrier release signal from V2.
     *
     * Transitions from [BarrierParticipationState.WAITING] to
     * [BarrierParticipationState.RELEASED].  If the current state is not WAITING,
     * the call returns `false` (cannot acknowledge a release that was not preceded by a
     * wait).
     *
     * After calling this, Android may proceed with post-barrier execution.  The caller
     * should [resetBarrier] once post-barrier work completes.
     *
     * @param barrierSessionId Barrier session identifier from the V2 release signal.
     *                         Must match the session recorded in [enterBarrierWait].
     * @return `true` if the transition was executed; `false` if not in WAITING state.
     */
    fun acknowledgeBarrierRelease(barrierSessionId: String): Boolean {
        if (_state != BarrierParticipationState.WAITING) return false
        _state = BarrierParticipationState.RELEASED
        GalaxyLogger.log(
            GalaxyLogger.TAG_HYBRID_PARTICIPANT,
            mapOf(
                "event"              to "barrier_release_acknowledged",
                "barrier_session_id" to barrierSessionId
            )
        )
        return true
    }

    /**
     * Records that the local barrier timeout expired before V2 sent a release signal.
     *
     * Transitions from [BarrierParticipationState.WAITING] to
     * [BarrierParticipationState.TIMED_OUT].  If the current state is not WAITING,
     * the call returns `false`.
     *
     * V2 should observe [currentState] as TIMED_OUT and apply its own timeout-divergence
     * policy (e.g. removing this participant from the barrier quorum).
     *
     * @return `true` if the transition was executed; `false` if not in WAITING state.
     */
    fun handleBarrierTimeout(): Boolean {
        if (_state != BarrierParticipationState.WAITING) return false
        val sessionId = _barrierSessionId
        _state = BarrierParticipationState.TIMED_OUT
        GalaxyLogger.log(
            GalaxyLogger.TAG_HYBRID_PARTICIPANT,
            mapOf(
                "event"              to "barrier_timeout",
                "barrier_session_id" to (sessionId ?: "unknown")
            )
        )
        return true
    }

    /**
     * Resets the barrier state back to [BarrierParticipationState.NOT_APPLICABLE].
     *
     * Call this after post-barrier execution completes, after a timeout is resolved,
     * or when a mesh session ends.  Always succeeds.
     *
     * @param barrierSessionId Barrier session identifier being cleared (for tracing).
     */
    fun resetBarrier(barrierSessionId: String = _barrierSessionId ?: "") {
        val previousState = _state
        _state = BarrierParticipationState.NOT_APPLICABLE
        _barrierSessionId = null
        GalaxyLogger.log(
            GalaxyLogger.TAG_HYBRID_PARTICIPANT,
            mapOf(
                "event"              to "barrier_reset",
                "previous_state"     to previousState.wireValue,
                "barrier_session_id" to barrierSessionId
            )
        )
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    /**
     * Produces a diagnostics metadata map for inclusion in telemetry and log entries.
     *
     * @return Stable key→value map with [currentState] wire value and session identity.
     */
    fun toMetadataMap(): Map<String, Any?> = mapOf(
        KEY_BARRIER_STATE      to _state.wireValue,
        KEY_BARRIER_SESSION_ID to _barrierSessionId
    )

    companion object {
        /** Wire key for [currentState] in diagnostics metadata maps. */
        const val KEY_BARRIER_STATE = "barrier_coordination_state"

        /** Wire key for [currentBarrierSessionId] in diagnostics metadata maps. */
        const val KEY_BARRIER_SESSION_ID = "barrier_coordination_session_id"

        /**
         * Checks whether Android's current barrier state is considered active
         * (i.e. participating in a live barrier rendezvous).
         *
         * Delegates to [BarrierParticipationState.isActive].
         */
        fun isActive(state: BarrierParticipationState): Boolean =
            BarrierParticipationState.isActive(state)
    }
}
