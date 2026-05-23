package com.ufo.galaxy.runtime

/**
 * PR-116 — Android Continuity Recovery State Model.
 *
 * Unified, observable, uplink-capable contract for Android-side continuity recovery state.
 *
 * ## Problem addressed
 *
 * Before this contract, Android's continuity recovery semantics were spread across multiple
 * points-of-truth:
 *  - [ReconnectRecoveryState] tracked WS reconnect lifecycle (RECOVERING / RECOVERED / FAILED)
 *  - [InflightContinuityDisposition] classified prior in-flight execution after restart
 *  - [InflightContinuityRecoverySnapshot] held the Android-local recovery artifact result
 *  - [ContinuityRecoveryContext] tracked durable session continuity
 *
 * Each piece was correct but consumed separately, making it hard for V2 to get a single,
 * stable recovery state signal from Android without combining fields from different carriers
 * (state snapshot, reconciliation signal, runtime truth snapshot).
 *
 * This contract converges those recovery semantics into one observable state model with
 * explicit wire keys for uplink metadata.
 *
 * ## Recovery state vocabulary
 *
 * | [RecoveryPhase]                | Meaning                                                            |
 * |--------------------------------|--------------------------------------------------------------------|
 * | [RecoveryPhase.RESUMED_CLEANLY]           | No interrupted state; runtime started clean            |
 * | [RecoveryPhase.RECOVERING]                | WS reconnect in progress; session continuity pending   |
 * | [RecoveryPhase.RECOVERED_INFLIGHT]        | Prior in-flight task was locally recovered; fully live |
 * | [RecoveryPhase.LOST_INFLIGHT]             | Prior in-flight task was dropped; V2 must handle close |
 * | [RecoveryPhase.REQUIRES_RECONCILIATION]   | Durable artifact found; V2 must resolve continuity     |
 * | [RecoveryPhase.STALE_RECOVERY_ARTIFACT]   | Artifact found but belongs to an old session; ignored  |
 * | [RecoveryPhase.RECOVERY_FAILED]           | All reconnect attempts exhausted; user action needed   |
 *
 * ## Boundary constraint
 *
 * Android MUST NOT claim [RecoveryPhase.RECOVERED_INFLIGHT] means the prior execution
 * result is valid — only V2 can adjudicate canonical continuity.  Android's recovery phase
 * is advisory recovery evidence, not canonical final truth.
 *
 * ## Wire contract
 *
 * [SCHEMA_VERSION] must be incremented when new recovery phases are added or when the
 * uplink wire keys change.  V2 V2_CONSUMPTION_PATH_MAP lists the stable V2 consumption paths.
 *
 * ## Uplink integration
 *
 * This contract's [KEY_CONTINUITY_RECOVERY_STATE] / [KEY_CONTINUITY_RECOVERY_SOURCE] /
 * [KEY_CONTINUITY_RECOVERY_SCHEMA_VERSION] wire keys appear in:
 *  - [com.ufo.galaxy.protocol.GoalResultPayload]  (result uplink)
 *  - [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload]  (state snapshot uplink)
 *  - [ReconciliationSignal] payload for [ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT]
 *
 * This is intentionally **advisory Android-side recovery evidence**, not canonical V2 truth.
 * V2 owns canonical continuity adjudication; Android only reports its local recovery observation.
 */
object AndroidContinuityRecoveryStateModel {

    /** Wire schema version for this contract's fields. */
    const val SCHEMA_VERSION = "1"

    // ── Wire key constants ─────────────────────────────────────────────────────

    /**
     * Wire key for the unified Android-side continuity recovery phase.
     *
     * Value: one of the [RecoveryPhase.wireValue] strings.
     */
    const val KEY_CONTINUITY_RECOVERY_STATE = "continuity_recovery_state"

    /**
     * Wire key for the recovery-phase source label.
     *
     * Identifies the Android code path that produced the [KEY_CONTINUITY_RECOVERY_STATE]
     * observation (e.g. `"process_recreated"`, `"runtime_stop"`, `"reconnect_recovery"`).
     */
    const val KEY_CONTINUITY_RECOVERY_SOURCE = "continuity_recovery_source"

    /**
     * Wire key for this contract's [SCHEMA_VERSION].
     */
    const val KEY_CONTINUITY_RECOVERY_SCHEMA_VERSION = "continuity_recovery_schema_version"
    const val KEY_SCHEMA_VERSION = AndroidCompletionClosureUplinkContract.KEY_SCHEMA_VERSION
    const val KEY_COMPLETION_CLOSURE_CONTRACT_VERSION =
        AndroidCompletionClosureUplinkContract.KEY_COMPLETION_CLOSURE_CONTRACT_VERSION

    // ── Recovery phase enum ────────────────────────────────────────────────────

    /**
     * Unified Android-side continuity recovery phase classification.
     *
     * Each value maps to a well-defined Android local condition that V2 can consume as
     * advisory evidence for canonical continuity adjudication.
     *
     * V2 MUST NOT treat any [RecoveryPhase] value as a canonical final truth statement —
     * Android is a bounded recovery *participant*, not the recovery *coordinator*.
     */
    enum class RecoveryPhase(val wireValue: String) {

        /**
         * No interrupted in-flight state; runtime started or resumed cleanly.
         *
         * This is the nominal case: no durable recovery artifact was found and the WS
         * transport is not in a reconnect cycle.
         */
        RESUMED_CLEANLY("resumed-cleanly"),

        /**
         * WS reconnect in progress; session continuity is pending.
         *
         * Maps to [ReconnectRecoveryState.RECOVERING].  Android is in the middle of
         * automatic backoff reconnect.  V2 should not assume the session is stable
         * until a [RECOVERED_INFLIGHT], [REQUIRES_RECONCILIATION], or [RESUMED_CLEANLY]
         * phase is reported.
         */
        RECOVERING("recovering"),

        /**
         * Prior in-flight task was recovered locally; runtime is live.
         *
         * The task is still actively tracked by Android (not just a durable artifact).
         * V2 MAY use this as evidence that the execution path is still viable, but MUST
         * independently verify closure via its canonical reconciliation chain.
         */
        RECOVERED_INFLIGHT("recovered-inflight"),

        /**
         * Prior in-flight task was dropped locally; Android no longer has it running.
         *
         * V2 MUST handle a possible task-close or reconciliation pass for the referenced
         * task, since Android cannot resume it.
         */
        LOST_INFLIGHT("lost-inflight"),

        /**
         * Durable artifact found but Android must wait for V2 to reconcile canonical truth.
         *
         * Android recovered the artifact identity (task ID, session epoch) but does not
         * have a live execution in progress.  V2 owns the reconciliation decision.
         */
        REQUIRES_RECONCILIATION("requires-reconciliation"),

        /**
         * Recovery artifact found but it belongs to an old session epoch.
         *
         * The artifact's [InflightContinuityRecoveryArtifact.durableSessionId] or
         * [InflightContinuityRecoveryArtifact.sessionContinuityEpoch] does not match
         * the current session.  V2 MUST NOT treat this artifact as evidence of current
         * continuity — it belongs to a prior durable session era.
         */
        STALE_RECOVERY_ARTIFACT("stale-recovery-artifact"),

        /**
         * All reconnect attempts were exhausted; manual intervention may be required.
         *
         * Maps to [ReconnectRecoveryState.FAILED].  Android's WS client has entered a
         * perpetual watchdog cycle.  V2 should not expect a reconnect until a new
         * [RECOVERING] or [RESUMED_CLEANLY] phase is reported.
         */
        RECOVERY_FAILED("recovery-failed");

        companion object {
            fun fromWireValue(value: String?): RecoveryPhase? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Derivation ─────────────────────────────────────────────────────────────

    /**
     * Derives the unified [RecoveryPhase] from the authoritative Android-local sources.
     *
     * Priority order:
     * 1. [ReconnectRecoveryState.RECOVERING] → [RecoveryPhase.RECOVERING]
     * 2. [ReconnectRecoveryState.FAILED]     → [RecoveryPhase.RECOVERY_FAILED]
     * 3. [InflightContinuityDisposition.STALE_RECOVERY_ARTIFACT] → [RecoveryPhase.STALE_RECOVERY_ARTIFACT]
     * 4. [InflightContinuityDisposition.LOST_INFLIGHT]           → [RecoveryPhase.LOST_INFLIGHT]
     * 5. [InflightContinuityDisposition.REQUIRES_RECONCILIATION] → [RecoveryPhase.REQUIRES_RECONCILIATION]
     * 6. [InflightContinuityDisposition.RECOVERED_INFLIGHT]      → [RecoveryPhase.RECOVERED_INFLIGHT]
     * 7. else                                                     → [RecoveryPhase.RESUMED_CLEANLY]
     */
    fun derive(
        reconnectRecoveryState: ReconnectRecoveryState,
        inflightDisposition: InflightContinuityDisposition
    ): RecoveryPhase = when {
        reconnectRecoveryState == ReconnectRecoveryState.RECOVERING ->
            RecoveryPhase.RECOVERING
        reconnectRecoveryState == ReconnectRecoveryState.FAILED ->
            RecoveryPhase.RECOVERY_FAILED
        inflightDisposition == InflightContinuityDisposition.STALE_RECOVERY_ARTIFACT ->
            RecoveryPhase.STALE_RECOVERY_ARTIFACT
        inflightDisposition == InflightContinuityDisposition.LOST_INFLIGHT ->
            RecoveryPhase.LOST_INFLIGHT
        inflightDisposition == InflightContinuityDisposition.REQUIRES_RECONCILIATION ->
            RecoveryPhase.REQUIRES_RECONCILIATION
        inflightDisposition == InflightContinuityDisposition.RECOVERED_INFLIGHT ->
            RecoveryPhase.RECOVERED_INFLIGHT
        else ->
            RecoveryPhase.RESUMED_CLEANLY
    }

    /**
     * Produces the minimal wire map for embedding this contract's fields in any uplink payload.
     *
     * @param phase      Derived [RecoveryPhase] for the current Android recovery state.
     * @param source     Source label from [InflightContinuityRecoverySnapshot.source] or
     *                   `"reconnect_recovery"` / `"none"` for transport-only states.
     */
    fun toWireMap(phase: RecoveryPhase, source: String): Map<String, String> = mapOf(
        KEY_CONTINUITY_RECOVERY_STATE to phase.wireValue,
        KEY_CONTINUITY_RECOVERY_SOURCE to source,
        KEY_CONTINUITY_RECOVERY_SCHEMA_VERSION to SCHEMA_VERSION,
        KEY_SCHEMA_VERSION to AndroidCompletionClosureUplinkContract.PAYLOAD_SCHEMA_VERSION,
        KEY_COMPLETION_CLOSURE_CONTRACT_VERSION to AndroidCompletionClosureUplinkContract.SCHEMA_VERSION
    )

    // ── Invariants ─────────────────────────────────────────────────────────────

    /**
     * Formal invariants for this recovery state model.
     *
     * These invariants must hold at all times.  Any PR modifying Android recovery paths
     * must verify all invariants remain satisfied.
     */
    val RECOVERY_STATE_INVARIANTS: List<String> = listOf(
        "INV-REC-01: recovery phase MUST be derived from authoritative runtime sources " +
            "(ReconnectRecoveryState + InflightContinuityDisposition); " +
            "MUST NOT be set by UI layer directly",
        "INV-REC-02: STALE_RECOVERY_ARTIFACT MUST NOT be consumed by V2 as evidence " +
            "of current session continuity; it belongs to a prior durable session era",
        "INV-REC-03: RECOVERED_INFLIGHT is advisory Android-local evidence only; " +
            "V2 MUST NOT treat it as canonical closure without independent adjudication",
        "INV-REC-04: recovery phase MUST be written into uplink metadata " +
            "(GoalResultPayload + DeviceStateSnapshotPayload + ReconciliationSignal payload) " +
            "using the KEY_CONTINUITY_RECOVERY_STATE wire key; MUST NOT remain local-log-only",
        "INV-REC-05: REQUIRES_RECONCILIATION MUST be reported via RUNTIME_TRUTH_SNAPSHOT " +
            "reconciliation signal so V2 can trigger its canonical reconciliation pass",
        "INV-REC-06: recovery phase source label MUST be non-blank in all uplink maps " +
            "so V2 observability can attribute the recovery observation to a specific path",
        "INV-REC-07: recovery phase MUST align with the session continuity epoch; " +
            "a STALE_RECOVERY_ARTIFACT observation from epoch N MUST NOT be reported " +
            "under epoch N+1 as current evidence",
        "INV-REC-08: Android MUST NOT introduce a parallel recovery transmission stack; " +
            "recovery state MUST travel exclusively via existing uplink carriers " +
            "(goal_result / state_snapshot / reconciliation_signal)"
    )

    // ── V2 consumption map ─────────────────────────────────────────────────────

    /**
     * Maps each [RecoveryPhase] to the V2 canonical consumption path that handles it.
     *
     * V2 MUST route recovery state signals through these paths rather than re-inferring
     * recovery classification from field combinations.
     */
    val V2_CONSUMPTION_PATH_MAP: Map<String, String> = mapOf(
        RecoveryPhase.RESUMED_CLEANLY.wireValue to
            "v2:continuity_adjudication/no_recovery_action_required",
        RecoveryPhase.RECOVERING.wireValue to
            "v2:reconnect_classifier/pending_reconnect_verdict",
        RecoveryPhase.RECOVERED_INFLIGHT.wireValue to
            "v2:continuity_adjudication/advisory_inflight_evidence",
        RecoveryPhase.LOST_INFLIGHT.wireValue to
            "v2:continuity_adjudication/task_closure_or_reconciliation_required",
        RecoveryPhase.REQUIRES_RECONCILIATION.wireValue to
            "v2:reconciliation_chain/canonical_truth_reconciliation_pass",
        RecoveryPhase.STALE_RECOVERY_ARTIFACT.wireValue to
            "v2:continuity_adjudication/stale_artifact_rejection",
        RecoveryPhase.RECOVERY_FAILED.wireValue to
            "v2:reconnect_classifier/terminal_reconnect_failure_handling"
    )
}
