package com.ufo.galaxy.runtime

/**
 * PR-118 — Android Continuity Diagnostics Contract.
 *
 * Establishes a minimal service-visible diagnostics surface for key Android continuity
 * runtime decisions, advancing them from "internal log visible" to
 * "service/runtime-visible diagnostics visible".
 *
 * ## Problem addressed
 *
 * Before this contract, the following key continuity decisions in Android were only
 * observable via logcat:
 *  - Reconnect classification outcome (TRANSPORT_RECONNECT vs PROCESS_RECREATION vs FRESH_ATTACH)
 *  - Offline replay queued / flushed
 *  - Recovery artifact resolved as recovered / lost / requires-reconciliation
 *  - Reconciliation signal queued / resent / buffer-dropped
 *
 * This made it impossible for the service layer, tests, or upstream tooling to:
 *  - Assert that a reconnect produced the correct classification
 *  - Observe whether offline replay started and completed
 *  - Verify that recovery artifact resolution reached the expected state
 *  - Confirm that reconciliation signals were emitted, retried, or deduped
 *
 * ## Solution
 *
 * [ContinuityDiagnosticsEvent] is a sealed class with one subtype per observable
 * continuity decision.  [RuntimeController] exposes a
 * `continuityDiagnosticsEvents: SharedFlow<ContinuityDiagnosticsEvent>` that observers
 * can collect to see real runtime decisions as they happen — without reading logcat.
 *
 * Events are emitted directly at the runtime decision points inside [RuntimeController]:
 *  - [ContinuityDiagnosticsEvent.ReconnectClassificationOutcome] — at every
 *    [ReconnectRecoveryState] transition in the permanent WS listener
 *    (IDLE→RECOVERING, RECOVERING→RECOVERED, RECOVERING→FAILED, FAILED→RECOVERING)
 *  - [ContinuityDiagnosticsEvent.OfflineReplayEvent] — when an offline replay is
 *    queued or flushed via [RuntimeController.recordOfflineReplayQueued] /
 *    [RuntimeController.recordOfflineReplayFlushed]
 *  - [ContinuityDiagnosticsEvent.RecoveryArtifactResolved] — from
 *    [RuntimeController.publishInflightContinuityRecovery] at every
 *    [InflightContinuityDisposition] classification
 *  - [ContinuityDiagnosticsEvent.ReconciliationSignalDiagnostic] — from
 *    [RuntimeController.emitReconciliationSignal] at every signal emission attempt
 *
 * ## Boundary constraints
 *
 *  - This contract is **diagnostics-visible only**.  It must not be promoted to
 *    canonical truth, nor consumed as final continuity adjudication.
 *  - Events reflect Android-local runtime decisions.  V2 owns canonical continuity
 *    adjudication; these events are advisory evidence.
 *  - [sessionEpochHint] is best-effort; observers must not treat it as the canonical
 *    session identity — use [durableSessionId] for stable session correlation.
 *  - Old-session events carry the session ID from the decision point; consumers
 *    should compare [durableSessionId] against their own current session state to
 *    detect cross-session contamination.
 *
 * @see RuntimeController.continuityDiagnosticsEvents
 * @see AndroidContinuityRecoveryStateModel
 * @see AndroidReconnectRecoveryParticipationContract
 * @see ReconciliationSignal
 */
object AndroidContinuityDiagnosticsContract {

    /** The Android PR number that introduced this contract. */
    const val INTRODUCED_PR = 118

    /** Wire schema version for all [ContinuityDiagnosticsEvent] wire maps. */
    const val SCHEMA_VERSION = "1"

    // ── ContinuityDiagnosticsEvent ─────────────────────────────────────────────

    /**
     * Sealed supertype for all service-visible continuity diagnostic events.
     *
     * Each subtype corresponds to one observable continuity decision in the Android runtime.
     * Events are emitted on [RuntimeController.continuityDiagnosticsEvents] at the exact
     * runtime decision point — not reconstructed from logs.
     *
     * @property sessionEpochHint  Best-effort epoch string for the session active at emission
     *                             time.  Format: `"<durableSessionId>:<epoch>"` or
     *                             `"<durableSessionId>:none"` when epoch is unavailable, or
     *                             `"no_session"` when no durable session is open.
     * @property durableSessionId  The durable session ID active at emission time, or `null`
     *                             if no session was open.
     * @property observedAtMs      [System.currentTimeMillis] when the event was emitted.
     */
    sealed class ContinuityDiagnosticsEvent {
        abstract val sessionEpochHint: String
        abstract val durableSessionId: String?
        abstract val observedAtMs: Long

        /**
         * Reconnect recovery state transition observed by the runtime.
         *
         * Emitted at every [ReconnectRecoveryState] transition:
         *  - IDLE → RECOVERING (WS disconnected while Active)
         *  - RECOVERING → RECOVERED (WS reconnected successfully)
         *  - RECOVERING → FAILED (reconnect attempts exhausted)
         *  - FAILED → RECOVERING (watchdog reentry)
         *
         * @property fromState  Previous [ReconnectRecoveryState.wireValue].
         * @property toState    New [ReconnectRecoveryState.wireValue].
         * @property trigger    Short label identifying the code path: one of
         *                      `"ws_disconnect_active"`, `"ws_reconnected_active"`,
         *                      `"ws_error"`, `"watchdog_reentry"`.
         */
        data class ReconnectClassificationOutcome(
            val fromState: String,
            val toState: String,
            val trigger: String,
            override val sessionEpochHint: String,
            override val durableSessionId: String?,
            override val observedAtMs: Long
        ) : ContinuityDiagnosticsEvent() {
            fun toWireMap(): Map<String, Any?> = mapOf(
                "event_kind" to EVENT_KIND_RECONNECT_CLASSIFICATION,
                "from_state" to fromState,
                "to_state" to toState,
                "trigger" to trigger,
                "session_epoch_hint" to sessionEpochHint,
                "durable_session_id" to durableSessionId,
                "observed_at_ms" to observedAtMs,
                "schema_version" to SCHEMA_VERSION
            )
        }

        /**
         * Offline queue event: a batch of messages was queued or the queue was flushed.
         *
         * Emitted when [RuntimeController.recordOfflineReplayQueued] or
         * [RuntimeController.recordOfflineReplayFlushed] is called by the connection
         * service layer.
         *
         * @property kind       [OfflineReplayEventKind.wireValue] — QUEUED or FLUSHED.
         * @property queueDepth Number of messages in the offline queue at emission time.
         * @property sessionTag The durable session tag attached to the queued messages,
         *                      or `null` if the queue is untagged.
         */
        data class OfflineReplayEvent(
            val kind: String,
            val queueDepth: Int,
            val sessionTag: String?,
            override val sessionEpochHint: String,
            override val durableSessionId: String?,
            override val observedAtMs: Long
        ) : ContinuityDiagnosticsEvent() {
            fun toWireMap(): Map<String, Any?> = mapOf(
                "event_kind" to EVENT_KIND_OFFLINE_REPLAY,
                "kind" to kind,
                "queue_depth" to queueDepth,
                "session_tag" to sessionTag,
                "session_epoch_hint" to sessionEpochHint,
                "durable_session_id" to durableSessionId,
                "observed_at_ms" to observedAtMs,
                "schema_version" to SCHEMA_VERSION
            )
        }

        /**
         * Recovery artifact resolved: Android classified an in-flight continuity
         * artifact as recovered, lost, or requiring reconciliation.
         *
         * Emitted from [RuntimeController.publishInflightContinuityRecovery] at every
         * [InflightContinuityDisposition] classification.
         *
         * @property disposition  [InflightContinuityDisposition.wireValue] — the resolution.
         * @property source       Source label from the runtime decision path
         *                        (e.g. `"process_recreated"`, `"runtime_stop"`,
         *                        `"reconnect_recovery"`).
         * @property taskId       Task ID from the artifact, or `null` if unavailable.
         * @property artifactSessionId  Durable session ID from the artifact itself, or
         *                              `null` if the artifact carried none.
         */
        data class RecoveryArtifactResolved(
            val disposition: String,
            val source: String,
            val taskId: String?,
            val artifactSessionId: String?,
            override val sessionEpochHint: String,
            override val durableSessionId: String?,
            override val observedAtMs: Long
        ) : ContinuityDiagnosticsEvent() {
            fun toWireMap(): Map<String, Any?> = mapOf(
                "event_kind" to EVENT_KIND_RECOVERY_ARTIFACT_RESOLVED,
                "disposition" to disposition,
                "source" to source,
                "task_id" to taskId,
                "artifact_session_id" to artifactSessionId,
                "session_epoch_hint" to sessionEpochHint,
                "durable_session_id" to durableSessionId,
                "observed_at_ms" to observedAtMs,
                "schema_version" to SCHEMA_VERSION
            )
        }

        /**
         * Reconciliation signal diagnostic: a [ReconciliationSignal] was emitted,
         * buffered, retried, or could not be emitted.
         *
         * Emitted from [RuntimeController.emitReconciliationSignal] at every signal
         * emission attempt.
         *
         * @property signalKind    [ReconciliationSignal.Kind.wireValue] of the signal.
         * @property signalId      Unique stable ID for deduplication.
         * @property taskId        Task ID carried by the signal, or `null`.
         * @property emitOutcome   [ReconciliationEmitOutcome.wireValue]:
         *                          - EMITTED_IMMEDIATELY — tryEmit succeeded
         *                          - BUFFER_FULL_RETRY_SCHEDULED — terminal signal buffered for retry
         *                          - BUFFER_FULL_DROPPED — non-terminal signal dropped
         */
        data class ReconciliationSignalDiagnostic(
            val signalKind: String,
            val signalId: String,
            val taskId: String?,
            val emitOutcome: String,
            override val sessionEpochHint: String,
            override val durableSessionId: String?,
            override val observedAtMs: Long
        ) : ContinuityDiagnosticsEvent() {
            fun toWireMap(): Map<String, Any?> = mapOf(
                "event_kind" to EVENT_KIND_RECONCILIATION_SIGNAL_DIAGNOSTIC,
                "signal_kind" to signalKind,
                "signal_id" to signalId,
                "task_id" to taskId,
                "emit_outcome" to emitOutcome,
                "session_epoch_hint" to sessionEpochHint,
                "durable_session_id" to durableSessionId,
                "observed_at_ms" to observedAtMs,
                "schema_version" to SCHEMA_VERSION
            )
        }
    }

    // ── Event kind wire constants ──────────────────────────────────────────────

    /** Wire value for [ContinuityDiagnosticsEvent.ReconnectClassificationOutcome]. */
    const val EVENT_KIND_RECONNECT_CLASSIFICATION = "reconnect_classification_outcome"

    /** Wire value for [ContinuityDiagnosticsEvent.OfflineReplayEvent]. */
    const val EVENT_KIND_OFFLINE_REPLAY = "offline_replay_event"

    /** Wire value for [ContinuityDiagnosticsEvent.RecoveryArtifactResolved]. */
    const val EVENT_KIND_RECOVERY_ARTIFACT_RESOLVED = "recovery_artifact_resolved"

    /** Wire value for [ContinuityDiagnosticsEvent.ReconciliationSignalDiagnostic]. */
    const val EVENT_KIND_RECONCILIATION_SIGNAL_DIAGNOSTIC = "reconciliation_signal_diagnostic"

    // ── OfflineReplayEventKind ─────────────────────────────────────────────────

    /** Classification for [ContinuityDiagnosticsEvent.OfflineReplayEvent.kind]. */
    enum class OfflineReplayEventKind(val wireValue: String) {
        /** One or more messages were appended to the offline queue. */
        QUEUED("queued"),
        /** Queued messages were flushed (sent) after reconnect. */
        FLUSHED("flushed")
    }

    // ── ReconciliationEmitOutcome ──────────────────────────────────────────────

    /** Outcome classification for [ContinuityDiagnosticsEvent.ReconciliationSignalDiagnostic.emitOutcome]. */
    enum class ReconciliationEmitOutcome(val wireValue: String) {
        /** [MutableSharedFlow.tryEmit] succeeded; signal delivered immediately to collectors. */
        EMITTED_IMMEDIATELY("emitted_immediately"),
        /** Buffer was full; signal is terminal — retry was scheduled in a coroutine. */
        BUFFER_FULL_RETRY_SCHEDULED("buffer_full_retry_scheduled"),
        /** Buffer was full; signal is non-terminal — no retry; event is dropped. */
        BUFFER_FULL_DROPPED("buffer_full_dropped")
    }

    // ── Invariants ─────────────────────────────────────────────────────────────

    /**
     * Formal invariants for this diagnostics contract.
     *
     * All invariants must hold at all times.  Any PR modifying Android continuity
     * or diagnostics paths must verify these invariants remain satisfied.
     */
    val DIAGNOSTICS_INVARIANTS: List<String> = listOf(
        "INV-DIAG-01: ContinuityDiagnosticsEvent MUST be emitted at the real runtime " +
            "decision point; MUST NOT be reconstructed from logs after the fact",
        "INV-DIAG-02: ReconnectClassificationOutcome MUST be emitted for every " +
            "ReconnectRecoveryState transition; zero-transition sessions produce no events",
        "INV-DIAG-03: RecoveryArtifactResolved MUST be emitted for every " +
            "publishInflightContinuityRecovery call, including RESUMED_CLEANLY outcomes",
        "INV-DIAG-04: ReconciliationSignalDiagnostic MUST be emitted for every " +
            "emitReconciliationSignal call, capturing the true emit outcome",
        "INV-DIAG-05: sessionEpochHint MUST reflect the durable session state at the " +
            "moment of the runtime decision; MUST NOT be back-filled from a later epoch",
        "INV-DIAG-06: durableSessionId in an event MUST match the durable session that " +
            "was active when the runtime decision was made; consumers MUST compare this " +
            "against their current session to detect cross-session contamination",
        "INV-DIAG-07: diagnostics events are consumption-only evidence; " +
            "MUST NOT be promoted to canonical continuity truth by consumers",
        "INV-DIAG-08: the continuityDiagnosticsEvents SharedFlow MUST NOT bypass existing " +
            "runtime/service channels; it observes decisions already made by RuntimeController"
    )

    // ── V2 consumption paths ───────────────────────────────────────────────────

    /**
     * Maps each event kind to the recommended V2 consumption path.
     *
     * V2 consumers SHOULD route each event to the indicated path rather than
     * re-inferring continuity state from field combinations.
     */
    val V2_CONSUMPTION_PATH_MAP: Map<String, String> = mapOf(
        EVENT_KIND_RECONNECT_CLASSIFICATION to
            "v2:reconnect_classifier/classification_outcome_observation",
        EVENT_KIND_OFFLINE_REPLAY to
            "v2:continuity_adjudication/offline_replay_progress_observation",
        EVENT_KIND_RECOVERY_ARTIFACT_RESOLVED to
            "v2:continuity_adjudication/android_recovery_evidence_channel",
        EVENT_KIND_RECONCILIATION_SIGNAL_DIAGNOSTIC to
            "v2:reconciliation_chain/signal_emission_audit"
    )
}
