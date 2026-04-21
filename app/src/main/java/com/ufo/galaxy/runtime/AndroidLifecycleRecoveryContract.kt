package com.ufo.galaxy.runtime

/**
 * PR-60 — Android lifecycle and recovery responsibility boundary contract.
 *
 * This object is the **canonical reference for what Android can recover locally**
 * vs **what must be re-synchronized from V2** after various lifecycle disruptions.
 *
 * It serves as the authoritative documentation anchor for reviewers to determine:
 *  1. How Android behaves across lifecycle changes and reconnect scenarios.
 *  2. What Android can recover locally vs what must be re-synchronized from V2.
 *  3. Whether remaining limitations are intentional or accidental.
 *
 * ## Android recovery role
 *
 * Android is a **recovery participant**, not a recovery coordinator.  All recovery
 * decisions (session resumption, task retry, barrier re-evaluation) belong to V2.
 * Android's only recovery duties are:
 *  1. Re-establishing WS connectivity.
 *  2. Emitting appropriate [V2MultiDeviceLifecycleEvent] signals.
 *  3. Reporting updated [DurableSessionContinuityRecord] state on reconnect.
 *
 * See also [ContinuityRecoveryContext.ANDROID_RECOVERY_ROLE].
 *
 * ## Lifecycle disruption taxonomy
 *
 * | Disruption | In-memory state lost? | Settings preserved? | Durable session preserved? | V2 event |
 * |---|---|---|---|---|
 * | Transient WS disconnect (auto-reconnect) | No | Yes | Yes (epoch++) | [V2MultiDeviceLifecycleEvent.DeviceReconnected] |
 * | Reconnect failure (exhausted) | No | Yes | Yes (but era ends on next stop()) | [V2MultiDeviceLifecycleEvent.DeviceDisconnected] |
 * | App backgrounded | No | Yes | Yes | None (connection preserved) |
 * | App foregrounded | No | Yes | Yes | None (or DeviceConnected if reconnecting) |
 * | Activity config change | No | Yes | Yes | None (RuntimeController survives) |
 * | Explicit stop (user-initiated) | Yes | Yes (crossDeviceEnabled=false) | Reset | [V2MultiDeviceLifecycleEvent.DeviceDisconnected] |
 * | Process recreation (low-memory kill) | Yes (all) | Yes (SharedPrefs) | Reset | [V2MultiDeviceLifecycleEvent.DeviceConnected] (new era) |
 *
 * ## Process recreation boundary
 *
 * Process recreation is the most disruptive case.  [PROCESS_RECREATION_BOUNDARY]
 * enumerates exactly what survives and what is lost.
 */
object AndroidLifecycleRecoveryContract {

    // ── Process recreation boundary ───────────────────────────────────────────

    /**
     * Wire key for the list of state that **survives** an Android process recreation.
     *
     * These fields are read from [com.ufo.galaxy.data.AppSettings] (SharedPreferences)
     * on process restart, so they are effectively durable across process death.
     */
    const val SURVIVES_PROCESS_RECREATION = "survives_process_recreation"

    /**
     * Wire key for the list of state that is **lost** on Android process recreation.
     *
     * These fields are held in process memory and must be rebuilt from V2 after restart.
     */
    const val LOST_ON_PROCESS_RECREATION = "lost_on_process_recreation"

    /**
     * The complete process-recreation boundary declaration.
     *
     * ## What survives process recreation (persisted in SharedPreferences)
     *
     * - `crossDeviceEnabled` — Android will attempt to reconnect on restart if this was true
     * - `gatewayHost` / `gatewayPort` / `useTls` — connection configuration
     * - `deviceId` — stable hardware identity
     * - `gatewayToken` — auth token
     * - All other [com.ufo.galaxy.data.AppSettings] fields
     *
     * ## What is lost on process recreation (in-memory only)
     *
     * - [DurableSessionContinuityRecord] — process-scoped; a new era starts on restart.
     *   V2 will see a fresh [V2MultiDeviceLifecycleEvent.DeviceConnected] event (not
     *   DeviceReconnected) because `runtime_attachment_session_id` is regenerated.
     * - `_runtimeAttachmentSessionId` — regenerated fresh each time
     * - [ReconnectRecoveryState] — resets to IDLE
     * - [RuntimeController.RuntimeState] — resets to Idle / Starting
     * - [AttachedRuntimeSession] — closed; new session opened on reconnect
     * - `_reconciliationEpoch` counter — resets to 0 (process-scoped; V2 detects this
     *   as a new epoch sequence when participantId appears fresh)
     * - Any in-flight task state — lost; V2 must handle this via its own task timeout/retry
     *
     * ## V2 re-synchronization responsibilities
     *
     * After Android process recreation, V2 is responsible for:
     * - Treating the new `runtime_attachment_session_id` as a new attachment era
     * - Not attempting to resume tasks that were in-flight before the process death
     * - Re-evaluating participant readiness from the fresh DeviceConnected event
     * - Re-dispatching any tasks that need to continue (based on V2's own task state)
     */
    val PROCESS_RECREATION_BOUNDARY = mapOf(
        SURVIVES_PROCESS_RECREATION to listOf(
            "crossDeviceEnabled (AppSettings/SharedPreferences)",
            "gatewayHost/gatewayPort/useTls/allowSelfSigned (AppSettings/SharedPreferences)",
            "deviceId (AppSettings/SharedPreferences)",
            "gatewayToken (AppSettings/SharedPreferences)",
            "goalExecutionEnabled and other feature flags (AppSettings/SharedPreferences)"
        ),
        LOST_ON_PROCESS_RECREATION to listOf(
            "DurableSessionContinuityRecord (process-scoped; new era on restart)",
            "_runtimeAttachmentSessionId (regenerated fresh; new attachment era)",
            "ReconnectRecoveryState (resets to IDLE)",
            "RuntimeController.RuntimeState (resets to Idle)",
            "AttachedRuntimeSession (new session on reconnect; V2 sees DeviceConnected)",
            "_reconciliationEpoch counter (resets to 0; process-scoped)",
            "in-flight task state (lost; V2 must handle via timeout/retry policy)"
        )
    )

    // ── Transient WS disconnect recovery ─────────────────────────────────────

    /**
     * What Android can recover locally after a **transient WS disconnect** (auto-reconnect).
     *
     * After a transparent reconnect, Android re-establishes the session identity using the
     * preserved `_runtimeAttachmentSessionId` and increments the `sessionContinuityEpoch`.
     * V2 receives [V2MultiDeviceLifecycleEvent.DeviceReconnected] and can correlate the
     * returning device to its prior session using [DurableSessionContinuityRecord.durableSessionId].
     *
     * **Android recovers locally (no V2 re-sync required)**:
     *  - Session attachment (re-opened with same `runtime_attachment_session_id`)
     *  - Durable session continuity (same `durableSessionId`, incremented epoch)
     *  - Participant identity and health state
     *  - Rollout control and readiness flags
     *
     * **V2 must handle** (cannot be recovered by Android alone):
     *  - Any task that was in-flight at disconnect time (Android does not know if it completed)
     *  - Barrier/merge coordination state for any interrupted tasks
     *  - Re-dispatch decisions for interrupted tasks
     */
    val TRANSIENT_DISCONNECT_RECOVERY = mapOf(
        "android_recovers_locally" to listOf(
            "WS connection (auto-reconnect, no user action required)",
            "runtime_attachment_session_id (preserved across reconnects within same era)",
            "DurableSessionContinuityRecord (same durableSessionId; sessionContinuityEpoch incremented)",
            "participant identity (deviceId, hostId, participantId unchanged)",
            "attached session (reopened in ATTACHED state with RECONNECT_RECOVERY source)",
            "reconnectRecoveryState (transitions RECOVERING → RECOVERED)"
        ),
        "v2_must_handle" to listOf(
            "in-flight task outcome (was task completed before disconnect? V2 must check)",
            "barrier/merge state for any interrupted tasks",
            "re-dispatch decisions for interrupted multi-step tasks",
            "canonical session truth update (V2 reconciles on DeviceReconnected)"
        )
    )

    // ── Reconnect failure recovery ────────────────────────────────────────────

    /**
     * What happens when WS reconnect **fails** (all attempts exhausted).
     *
     * [ReconnectRecoveryState.FAILED] is set; the UI shows a manual-reconnect CTA.
     * User must explicitly tap reconnect or re-enable cross-device.  Until then:
     * - Android is in [RuntimeController.RuntimeState.Active] but disconnected
     * - V2 sees no signal until the user reconnects
     * - Any in-flight task is considered lost on the Android side
     *
     * This is an **explicit intentional limitation**: Android does not autonomously
     * restart the runtime after a reconnect failure.  User action is required.
     */
    const val RECONNECT_FAILURE_LIMITATION =
        "After reconnect failure Android does not autonomously restart — user action required. " +
            "V2 must treat the participant as unavailable until DeviceConnected is re-observed."

    // ── Hybrid participant limitations ────────────────────────────────────────

    /**
     * Summary of the intentional hybrid participant limitations on the Android side.
     *
     * These are tracked, explicit deferrals — not accidental omissions.  See
     * [HybridParticipantCapability.deferredCapabilities] for the machine-readable list.
     *
     * For each limitation, V2's expected fallback behaviour is noted.
     */
    val HYBRID_LIMITATIONS = mapOf(
        HybridParticipantCapability.HYBRID_EXECUTE_FULL.wireValue to (
            "Full hybrid_execute not implemented; Android sends hybrid_degrade reply. " +
                "V2 must apply full-remote-execution fallback."
        ),
        HybridParticipantCapability.WEBRTC_PEER_TRANSPORT.wireValue to (
            "WebRTC peer transport has minimal-compat stubs only; not production-ready. " +
                "V2 must not rely on WebRTC for correctness-critical flows."
        ),
        HybridParticipantCapability.BARRIER_COORDINATION.wireValue to (
            "Android is not a barrier participant; barrier decisions belong to V2. " +
                "Android contributes task completion signals only."
        )
    )
}
