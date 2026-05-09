package com.ufo.galaxy.runtime

/**
 * PR-08 (Android) — Canonical Runtime Truth Unification contract.
 *
 * Tightens Android runtime state/reporting semantics so that every reported state
 * surface maps cleanly into the center-side canonical truth model.
 *
 * ## Problem addressed
 *
 * Prior PRs established individual runtime truth surfaces:
 *  - [ExecutionUplinkDiscipline] (PR-07): uplink discipline per lifecycle phase.
 *  - [AndroidExecutionLifecycleContract] (PR-07): execution lifecycle phase state machine.
 *  - [AndroidR8CanonicalPathClosureContract] (PR-8Android): execution_mode_state and
 *    durable_participant_id wiring into DeviceStateSnapshotPayload.
 *
 * Despite this work, several semantic gaps remained:
 *
 *  1. **Distributed state surfaces blur semantic classes**: Android's wire payloads
 *     contain capability fields, observation fields, active-runtime-state fields,
 *     derived-local-state fields, and terminal-reporting fields all in a flat map.
 *     V2's canonical truth reducer cannot classify a field's semantic role without
 *     implicit knowledge of each field's derivation path.
 *
 *  2. **Degraded/fallback/constrained reporting lacks a unified class**: Whether Android
 *     reports degraded_reasons, mesh_constrained_reasons, planner_fallback_tier,
 *     or execution_mode_state="cross_device_degraded", V2 cannot tell which class of
 *     condition is active (capability gap vs runtime degradation vs transport constraint
 *     vs partial availability vs delayed-queue operation vs successful recovery).
 *
 *  3. **Result uplink semantic class is implicit**: V2 knows a result uplink arrived
 *     but cannot distinguish an authoritative terminal completion from an authoritative
 *     interruption report (which implies retry policy should be applied) from an
 *     authoritative recovery completion (which closes a prior interruption era) from an
 *     informational progress update.
 *
 *  4. **Local-observation basis is undeclared**: V2 cannot tell whether a field was
 *     derived from live runtime state, from a cached snapshot, or from a local projection,
 *     creating risk of stale-state propagation.
 *
 * ## What this contract introduces
 *
 *  1. [ReportedStateSemanticClass] — the 5-class vocabulary for classifying any reported
 *     Android state surface.
 *  2. [DegradedConditionClass] — the 7-class vocabulary for classifying any degraded,
 *     fallback, constrained, partial, delayed, or recovered condition.
 *  3. [ResultUplinkSemanticClass] — the 4-class vocabulary for classifying any result
 *     uplink signal emitted by Android.
 *  4. [LocalObservationBasis] — the 4-value basis declaration for how any reported
 *     field was derived.
 *  5. Classifier methods [classifySnapshot], [classifyDegradedCondition],
 *     [classifyResultUplink] — pure functions used at snapshot-emission and
 *     event-emission call sites.
 *
 * ## Wire alignment
 *
 * | New field                    | Payload                     | Wire key                      |
 * |------------------------------|-----------------------------|-------------------------------|
 * | reported_state_semantic_class | DeviceStateSnapshotPayload  | "reported_state_semantic_class" |
 * | degraded_condition_class      | DeviceStateSnapshotPayload  | "degraded_condition_class"    |
 * | local_observation_basis       | DeviceStateSnapshotPayload  | "local_observation_basis"     |
 * | reported_state_semantic_class | DeviceExecutionEventPayload | "reported_state_semantic_class" |
 * | result_uplink_semantic_class  | DeviceExecutionEventPayload | "result_uplink_semantic_class"|
 *
 * ## Relationship to prior contracts
 *
 * | Contract                                  | Scope                                      |
 * |-------------------------------------------|--------------------------------------------|
 * | [AndroidExecutionLifecycleContract]        | Execution phase state machine              |
 * | [ExecutionUplinkDiscipline]                | When to emit result/state uplinks          |
 * | [AndroidR8CanonicalPathClosureContract]    | execution_mode_state / durable_participant_id wiring |
 * | [AndroidCanonicalRuntimeTruthContract] (this) | What semantic class any reported state belongs to |
 *
 * @see ExecutionUplinkDiscipline
 * @see AndroidExecutionLifecycleContract
 * @see AndroidR8CanonicalPathClosureContract
 * @see com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
 * @see com.ufo.galaxy.protocol.DeviceExecutionEventPayload
 */
object AndroidCanonicalRuntimeTruthContract {

    /** PR number that introduces this contract. */
    const val INTRODUCED_PR = "PR-08Android"

    // ── Reported state semantic class ─────────────────────────────────────────

    /**
     * Semantic class of an Android-reported state surface.
     *
     * Every field in [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload] and
     * [com.ufo.galaxy.protocol.DeviceExecutionEventPayload] belongs to exactly one
     * semantic class.  The [classifySnapshot] function derives the **dominant** class
     * for a given snapshot based on which fields carry meaningful values.
     *
     * V2's canonical truth reducer MUST use this class to decide which truth tier
     * an incoming Android snapshot contributes to:
     *
     * | Class                | V2 truth tier             | Action                                    |
     * |----------------------|---------------------------|-------------------------------------------|
     * | CAPABILITY           | capability_truth          | Update device capability score            |
     * | OBSERVATION          | participant_observation   | Update participant observation record     |
     * | ACTIVE_RUNTIME       | runtime_state_truth       | Update runtime state for dispatch scoring |
     * | DERIVED_LOCAL        | local_projection          | Store as Android-originated projection    |
     * | TERMINAL_REPORTING   | terminal_truth            | Close active execution and apply cleanup  |
     *
     * @property wireValue    Stable wire string emitted in the `reported_state_semantic_class` field.
     * @property displayName  Short human-readable label.
     * @property description  One-sentence semantic description.
     * @property v2TruthTier  The V2 canonical truth tier this class contributes to.
     */
    enum class ReportedStateSemanticClass(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val v2TruthTier: String
    ) {

        /**
         * The snapshot primarily reports Android's **capabilities** — what the device
         * can do, independent of what it is currently doing.
         *
         * Canonical examples: model_ready, llama_cpp_available, grounding_ready,
         * planner_ready, local_intelligence_status.
         *
         * V2 uses this class to update the device capability score for dispatch decisions.
         */
        CAPABILITY(
            wireValue = "capability",
            displayName = "Capability",
            description = "Reports what Android can do (capability declaration, independent of active execution).",
            v2TruthTier = "capability_truth"
        ),

        /**
         * The snapshot primarily reports **locally observed** conditions — what Android
         * sees in its environment or about its own participation state.
         *
         * Canonical examples: mesh_participation_lifecycle_state, barrier_participation_state,
         * carrier_foreground_visible, interaction_surface_ready, offline_queue_depth.
         *
         * V2 uses this class to update the participant observation record.
         */
        OBSERVATION(
            wireValue = "observation",
            displayName = "Observation",
            description = "Reports what Android observes about its environment or participation state.",
            v2TruthTier = "participant_observation"
        ),

        /**
         * The snapshot primarily reports **active runtime state** — what Android is
         * currently doing as an execution participant.
         *
         * Canonical examples: carrier_runtime_state="active", execution_mode_state=
         * "cross_device_active", execution_busy=true, active_execution_count > 0.
         *
         * V2 uses this class to update runtime state for dispatch scoring. This is
         * the most time-sensitive class — staleness matters most here.
         */
        ACTIVE_RUNTIME(
            wireValue = "active_runtime",
            displayName = "Active Runtime",
            description = "Reports what Android is actively doing as an execution participant.",
            v2TruthTier = "runtime_state_truth"
        ),

        /**
         * The snapshot primarily carries **locally derived state** — values computed by
         * Android from raw observations rather than directly observed.
         *
         * Canonical examples: planner_fallback_tier, grounding_fallback_tier,
         * participant_identity_freshness, local_loop_config (derived from LocalLoopConfig).
         *
         * V2 uses this class to store the value as an Android-originated projection and
         * may apply its own staleness/derivation heuristics.
         */
        DERIVED_LOCAL(
            wireValue = "derived_local",
            displayName = "Derived Local",
            description = "Reports locally computed/derived state, not directly observed.",
            v2TruthTier = "local_projection"
        ),

        /**
         * The snapshot carries a **terminal condition report** — Android is reporting
         * that some active state has reached a terminal or failed condition.
         *
         * Canonical examples: carrier_runtime_state="failed", reconnect_recovery_state=
         * "failed", execution_mode_state="inactive" after an active period.
         *
         * V2 uses this class to close active execution records and apply cleanup policy.
         */
        TERMINAL_REPORTING(
            wireValue = "terminal_reporting",
            displayName = "Terminal Reporting",
            description = "Reports a terminal condition (failed, inactive after active, recovery failed).",
            v2TruthTier = "terminal_truth"
        );

        companion object {
            /** All stable wire values; used for wire-stability contract tests. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Degraded condition class ──────────────────────────────────────────────

    /**
     * Classification of any degraded, fallback, constrained, partial, delayed, or
     * recovered local condition reported in a snapshot.
     *
     * V2's canonical truth reducer uses this class to determine what policy to apply
     * when Android reports a non-nominal condition:
     *
     * | Class       | V2 interpretation                                               |
     * |-------------|------------------------------------------------------------------|
     * | NOMINAL     | All systems nominal; apply standard dispatch scoring            |
     * | DEGRADED    | Runtime is degraded; reduce dispatch priority                   |
     * | FALLBACK    | Running on fallback path; adjust capability expectations        |
     * | CONSTRAINED | Participating under constraints; limit concurrency              |
     * | PARTIAL     | Partially available; split dispatch if possible                 |
     * | DELAYED     | Available but delayed (offline queue); extend timeout budget    |
     * | RECOVERED   | Recovered from prior degraded/failed state; resume normal policy|
     *
     * @property wireValue    Stable wire string emitted in the `degraded_condition_class` field.
     * @property displayName  Short human-readable label.
     * @property description  One-sentence description.
     * @property isNominal    True when no degradation is present.
     * @property impliesReducedCapability True when V2 should reduce dispatch priority.
     */
    enum class DegradedConditionClass(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val isNominal: Boolean,
        val impliesReducedCapability: Boolean
    ) {

        /**
         * All systems are operating nominally. No degradation, fallback, or constraint.
         *
         * Preconditions: degraded_reasons is empty, mesh_constrained_reasons is empty,
         * planner_fallback_tier is null or primary, execution_mode_state is
         * "cross_device_active" or "local_only", reconnect_recovery_state is "idle"
         * or "recovered".
         */
        NOMINAL(
            wireValue = "nominal",
            displayName = "Nominal",
            description = "All systems operating nominally; no degradation present.",
            isNominal = true,
            impliesReducedCapability = false
        ),

        /**
         * The runtime is operating in a degraded state — some capability is reduced
         * but the system is still participating.
         *
         * Canonical triggers: degraded_reasons non-empty, execution_mode_state=
         * "cross_device_degraded", local_intelligence_status indicates degradation.
         */
        DEGRADED(
            wireValue = "degraded",
            displayName = "Degraded",
            description = "Runtime is degraded; some capability reduced but still participating.",
            isNominal = false,
            impliesReducedCapability = true
        ),

        /**
         * The runtime is operating on a fallback path — an alternative to the
         * primary execution path due to some primary-path unavailability.
         *
         * Canonical triggers: planner_fallback_tier is non-primary,
         * grounding_fallback_tier is non-primary, current_fallback_tier is set.
         */
        FALLBACK(
            wireValue = "fallback",
            displayName = "Fallback",
            description = "Running on fallback path; primary path unavailable.",
            isNominal = false,
            impliesReducedCapability = true
        ),

        /**
         * The runtime is participating under constraints — limited by resource
         * availability, rollout gates, or eligibility restrictions.
         *
         * Canonical triggers: mesh_constrained_reasons non-empty, rollout gate
         * suppression active, cross_device_eligibility=false while connected.
         */
        CONSTRAINED(
            wireValue = "constrained",
            displayName = "Constrained",
            description = "Participating under constraints; limited resources or eligibility.",
            isNominal = false,
            impliesReducedCapability = true
        ),

        /**
         * The runtime is partially available — some subsystems are available while
         * others are not, enabling partial task execution.
         *
         * Canonical triggers: planner_ready != grounding_ready (one subsystem up,
         * one down), local_loop_ready=false while model_ready=true.
         */
        PARTIAL(
            wireValue = "partial",
            displayName = "Partial",
            description = "Partially available; some subsystems available, others not.",
            isNominal = false,
            impliesReducedCapability = true
        ),

        /**
         * The runtime is available but operating with delayed responses — tasks are
         * queued offline and will be replayed when connectivity is restored.
         *
         * Canonical triggers: offline_queue_depth > 0 while not fully connected,
         * execution_mode_state="transitioning" with tasks queued.
         */
        DELAYED(
            wireValue = "delayed",
            displayName = "Delayed",
            description = "Available but delayed; tasks queued offline for replay.",
            isNominal = false,
            impliesReducedCapability = false
        ),

        /**
         * The runtime has **recovered** from a prior degraded, failed, or interrupted
         * state and is now operating normally again.
         *
         * Canonical triggers: reconnect_recovery_state="recovered",
         * participant_identity_freshness="recovered", prior degraded_reasons now empty.
         *
         * V2 should resume standard dispatch policy after receiving this class.
         */
        RECOVERED(
            wireValue = "recovered",
            displayName = "Recovered",
            description = "Recovered from prior degraded/failed/interrupted state; resuming normal policy.",
            isNominal = false,
            impliesReducedCapability = false
        );

        companion object {
            /** All stable wire values; used for wire-stability contract tests. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Result uplink semantic class ──────────────────────────────────────────

    /**
     * Semantic class of a result uplink signal emitted by Android.
     *
     * V2 uses this class to determine what truth-maintenance action to take when
     * receiving an Android result signal:
     *
     * | Class                     | V2 action                                               |
     * |---------------------------|----------------------------------------------------------|
     * | AUTHORITATIVE_TERMINAL    | Close execution; apply success/failure/rejected policy  |
     * | AUTHORITATIVE_INTERRUPTION| Mark execution interrupted; apply retry/fallback policy |
     * | AUTHORITATIVE_RECOVERY    | Close interruption era; resume normal policy            |
     * | INFORMATIONAL             | Record progress; no terminal action                     |
     *
     * @property wireValue    Stable wire string emitted in the `result_uplink_semantic_class` field.
     * @property displayName  Short human-readable label.
     * @property description  One-sentence description.
     * @property isTerminal   True when this uplink closes the execution flow.
     * @property v2Action     The V2 canonical action associated with this class.
     */
    enum class ResultUplinkSemanticClass(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val isTerminal: Boolean,
        val v2Action: String
    ) {

        /**
         * The result uplink is an **authoritative terminal** report: the execution has
         * reached a definitive terminal state (COMPLETED, FAILED, REJECTED).
         *
         * V2 MUST close the execution flow and apply success/failure/rejected cleanup
         * policy after receiving this uplink.
         *
         * Applies to: COMPLETED, FAILED, TIMED_OUT, REJECTED execution phases.
         */
        AUTHORITATIVE_TERMINAL(
            wireValue = "authoritative_terminal",
            displayName = "Authoritative Terminal",
            description = "Execution has reached a definitive terminal state; V2 must close the flow.",
            isTerminal = true,
            v2Action = "close_execution_apply_terminal_policy"
        ),

        /**
         * The result uplink is an **authoritative interruption** report: the execution
         * was interrupted (not definitively failed), and V2 should apply its retry or
         * fallback policy.
         *
         * V2 MUST mark the execution as interrupted and apply its interruption-handling
         * policy (retry/fallback/reschedule) based on the interruption_cause field.
         *
         * Applies to: INTERRUPTED execution phase.
         */
        AUTHORITATIVE_INTERRUPTION(
            wireValue = "authoritative_interruption",
            displayName = "Authoritative Interruption",
            description = "Execution was interrupted; V2 must apply retry/fallback policy based on cause.",
            isTerminal = false,
            v2Action = "apply_retry_or_fallback_policy"
        ),

        /**
         * The result uplink is an **authoritative recovery** completion report: a prior
         * interrupted or degraded execution has been successfully recovered.
         *
         * V2 MUST close the interruption era for this execution and resume normal policy.
         * This uplink is distinct from AUTHORITATIVE_TERMINAL: it explicitly signals that
         * a recovery path succeeded, which may affect V2's retry/fallback bookkeeping.
         *
         * Applies to: execution completion after a prior INTERRUPTED/RETRYING phase.
         */
        AUTHORITATIVE_RECOVERY(
            wireValue = "authoritative_recovery",
            displayName = "Authoritative Recovery",
            description = "Prior interrupted/degraded execution has been successfully recovered; V2 closes the interruption era.",
            isTerminal = true,
            v2Action = "close_interruption_era_resume_normal"
        ),

        /**
         * The result uplink is **informational**: a progress update that does not
         * require a terminal or interruption-handling action from V2.
         *
         * V2 MAY record this for observability but MUST NOT take terminal or
         * interruption-handling actions.
         *
         * Applies to: ACTIVE, PENDING, ACTIVATING, DEGRADED, RETRYING phases
         * (non-terminal state signals emitted for observability purposes).
         */
        INFORMATIONAL(
            wireValue = "informational",
            displayName = "Informational",
            description = "Progress update; V2 records for observability but takes no terminal action.",
            isTerminal = false,
            v2Action = "record_for_observability"
        );

        companion object {
            /** All stable wire values; used for wire-stability contract tests. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Local observation basis ───────────────────────────────────────────────

    /**
     * Basis on which locally observed or derived state was produced.
     *
     * V2 uses this declaration to apply appropriate staleness/trust heuristics
     * to fields in the incoming snapshot.
     *
     * @property wireValue   Stable wire string emitted in the `local_observation_basis` field.
     * @property description One-sentence description.
     * @property isFresh     True when the basis implies fresh live-state derivation.
     */
    enum class LocalObservationBasis(
        val wireValue: String,
        val description: String,
        val isFresh: Boolean
    ) {
        /**
         * Fields are derived from the live Android runtime state at snapshot-emission time.
         * This is the nominal basis — all fields reflect the current runtime truth.
         */
        LIVE_RUNTIME(
            wireValue = "live_runtime",
            description = "All reported fields derived from live Android runtime state at emission time.",
            isFresh = true
        ),

        /**
         * Some fields are derived from a cached or last-known state (e.g. on reconnect
         * paths before all state is re-verified). V2 should treat such fields with
         * elevated staleness risk.
         */
        CACHED_STATE(
            wireValue = "cached_state",
            description = "Some fields derived from cached/last-known state; elevated staleness risk.",
            isFresh = false
        ),

        /**
         * Fields are derived from a local projection (computed from raw state, not
         * directly observed). Projections are logically derived but may lag live state.
         */
        DERIVED_PROJECTION(
            wireValue = "derived_projection",
            description = "Fields computed from local projection; may lag live runtime state.",
            isFresh = false
        ),

        /**
         * The observation basis is unknown or unavailable (e.g. early startup before
         * runtime state is initialised). V2 must treat all fields as potentially stale.
         */
        NONE(
            wireValue = "none",
            description = "Observation basis unknown; all fields should be treated as potentially stale.",
            isFresh = false
        );

        companion object {
            /** All stable wire values. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Classifier: snapshot semantic class ───────────────────────────────────

    /**
     * Derives the dominant [ReportedStateSemanticClass] for a snapshot, given the
     * key discriminating field values from [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload].
     *
     * **Classification priority** (highest wins):
     * 1. TERMINAL_REPORTING — if [carrierRuntimeState]="failed" or
     *    [reconnectRecoveryState]="failed" or [executionModeState]="inactive".
     * 2. ACTIVE_RUNTIME — if [carrierRuntimeState]="active" or [executionBusy]=true
     *    or [executionModeState]="cross_device_active".
     * 3. OBSERVATION — if [meshParticipationLifecycleState] is non-null and non-"inactive"
     *    or [carrierForegroundVisible] is non-null.
     * 4. DERIVED_LOCAL — if [plannerFallbackTier] or [groundingFallbackTier] is non-null.
     * 5. CAPABILITY — default (model_ready, llama_cpp_available, etc.).
     *
     * @param carrierRuntimeState         [DeviceStateSnapshotPayload.carrier_runtime_state]
     * @param reconnectRecoveryState      [DeviceStateSnapshotPayload.reconnect_recovery_state]
     * @param executionModeState          [DeviceStateSnapshotPayload.execution_mode_state]
     * @param executionBusy               [DeviceStateSnapshotPayload.execution_busy]
     * @param meshParticipationLifecycle  [DeviceStateSnapshotPayload.mesh_participation_lifecycle_state]
     * @param carrierForegroundVisible    [DeviceStateSnapshotPayload.carrier_foreground_visible]
     * @param plannerFallbackTier         [DeviceStateSnapshotPayload.planner_fallback_tier]
     * @param groundingFallbackTier       [DeviceStateSnapshotPayload.grounding_fallback_tier]
     * @return The dominant [ReportedStateSemanticClass] for this snapshot.
     */
    fun classifySnapshot(
        carrierRuntimeState: String? = null,
        reconnectRecoveryState: String? = null,
        executionModeState: String? = null,
        executionBusy: Boolean? = null,
        meshParticipationLifecycle: String? = null,
        carrierForegroundVisible: Boolean? = null,
        plannerFallbackTier: String? = null,
        groundingFallbackTier: String? = null
    ): ReportedStateSemanticClass {
        // 1. Terminal: failed carrier, failed recovery, or inactive after prior activity
        if (carrierRuntimeState == "failed" ||
            reconnectRecoveryState == "failed" ||
            executionModeState == "inactive"
        ) {
            return ReportedStateSemanticClass.TERMINAL_REPORTING
        }

        // 2. Active runtime: actively executing cross-device or local task
        if (carrierRuntimeState == "active" ||
            executionBusy == true ||
            executionModeState == "cross_device_active"
        ) {
            return ReportedStateSemanticClass.ACTIVE_RUNTIME
        }

        // 3. Observation: mesh participation or foreground visibility is meaningful
        // meshParticipationLifecycle excludes "inactive" because inactive is the absence of
        // participation — not an observation worth reporting to V2.  carrierForegroundVisible
        // includes any non-null value (true = foreground, false = background) because both
        // foreground and background carrier state are meaningful observations for V2's
        // participant-interaction model.
        if ((meshParticipationLifecycle != null && meshParticipationLifecycle != "inactive") ||
            carrierForegroundVisible != null
        ) {
            return ReportedStateSemanticClass.OBSERVATION
        }

        // 4. Derived local: fallback tiers indicate derived configuration state
        if (plannerFallbackTier != null || groundingFallbackTier != null) {
            return ReportedStateSemanticClass.DERIVED_LOCAL
        }

        // 5. Default: capability snapshot
        return ReportedStateSemanticClass.CAPABILITY
    }

    // ── Classifier: degraded condition class ─────────────────────────────────

    /**
     * Derives the [DegradedConditionClass] for a snapshot, given the key degradation
     * indicator fields from [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload].
     *
     * **Classification priority** (checked in order; first match wins):
     * 1. RECOVERED — if [reconnectRecoveryState]="recovered" and [degradedReasons] is empty.
     * 2. DEGRADED — if [degradedReasons] is non-empty or [executionModeState]="cross_device_degraded".
     * 3. FALLBACK — if [plannerFallbackTier] or [groundingFallbackTier] or [currentFallbackTier] is non-null.
     * 4. CONSTRAINED — if [meshConstrainedReasons] is non-empty or [crossDeviceEligibility]=false.
     * 5. PARTIAL — if [plannerReady] != [groundingReady] (partial subsystem availability).
     * 6. DELAYED — if [offlineQueueDepth] > 0.
     * 7. NOMINAL — all nominal.
     *
     * @param reconnectRecoveryState  [DeviceStateSnapshotPayload.reconnect_recovery_state]
     * @param degradedReasons         [DeviceStateSnapshotPayload.degraded_reasons]
     * @param executionModeState      [DeviceStateSnapshotPayload.execution_mode_state]
     * @param plannerFallbackTier     [DeviceStateSnapshotPayload.planner_fallback_tier]
     * @param groundingFallbackTier   [DeviceStateSnapshotPayload.grounding_fallback_tier]
     * @param currentFallbackTier     [DeviceStateSnapshotPayload.current_fallback_tier]
     * @param meshConstrainedReasons  [DeviceStateSnapshotPayload.mesh_constrained_reasons]
     * @param crossDeviceEligibility  [DeviceStateSnapshotPayload.cross_device_eligibility]
     * @param plannerReady            [DeviceStateSnapshotPayload.planner_ready]
     * @param groundingReady          [DeviceStateSnapshotPayload.grounding_ready]
     * @param offlineQueueDepth       [DeviceStateSnapshotPayload.offline_queue_depth]
     * @return The [DegradedConditionClass] for this snapshot.
     */
    fun classifyDegradedCondition(
        reconnectRecoveryState: String? = null,
        degradedReasons: List<String> = emptyList(),
        executionModeState: String? = null,
        plannerFallbackTier: String? = null,
        groundingFallbackTier: String? = null,
        currentFallbackTier: String? = null,
        meshConstrainedReasons: List<String> = emptyList(),
        crossDeviceEligibility: Boolean? = null,
        plannerReady: Boolean? = null,
        groundingReady: Boolean? = null,
        offlineQueueDepth: Int? = null
    ): DegradedConditionClass {
        // 1. Recovered: explicitly signalled recovery with no remaining degradation
        if (reconnectRecoveryState == "recovered" && degradedReasons.isEmpty()) {
            return DegradedConditionClass.RECOVERED
        }

        // 2. Degraded: explicit degradation reasons or degraded mode state
        if (degradedReasons.isNotEmpty() || executionModeState == "cross_device_degraded") {
            return DegradedConditionClass.DEGRADED
        }

        // 3. Fallback: any fallback tier is configured (non-primary path active)
        if (plannerFallbackTier != null || groundingFallbackTier != null || currentFallbackTier != null) {
            return DegradedConditionClass.FALLBACK
        }

        // 4. Constrained: mesh constraints or eligibility restrictions
        if (meshConstrainedReasons.isNotEmpty() || crossDeviceEligibility == false) {
            return DegradedConditionClass.CONSTRAINED
        }

        // 5. Partial: one subsystem ready but not the other
        if (plannerReady != null && groundingReady != null && plannerReady != groundingReady) {
            return DegradedConditionClass.PARTIAL
        }

        // 6. Delayed: offline queue has pending items
        if (offlineQueueDepth != null && offlineQueueDepth > 0) {
            return DegradedConditionClass.DELAYED
        }

        // 7. Nominal: no degradation detected
        return DegradedConditionClass.NOMINAL
    }

    // ── Classifier: result uplink semantic class ──────────────────────────────

    /**
     * Derives the [ResultUplinkSemanticClass] for a result uplink signal given the
     * [AndroidExecutionLifecycleContract.ExecutionLifecyclePhase] being entered and
     * whether the execution was preceded by an interruption/retry phase.
     *
     * **Classification logic:**
     * 1. INFORMATIONAL — for non-terminal phases (PENDING, ACTIVATING, ACTIVE, DEGRADED,
     *    RETRYING, CAPABILITY_IDLE, UNKNOWN).
     * 2. AUTHORITATIVE_INTERRUPTION — for INTERRUPTED phase.
     * 3. AUTHORITATIVE_RECOVERY — for COMPLETED/FAILED after priorPhaseWasInterruption=true.
     * 4. AUTHORITATIVE_TERMINAL — for all other terminal phases (COMPLETED, FAILED,
     *    TIMED_OUT, REJECTED) without a prior interruption.
     *
     * @param phase                      The [AndroidExecutionLifecycleContract.ExecutionLifecyclePhase]
     *                                   being entered.
     * @param priorPhaseWasInterruption  True if the immediately prior phase was
     *                                   INTERRUPTED or RETRYING; signals a recovery path.
     * @return The [ResultUplinkSemanticClass] for this result uplink.
     */
    fun classifyResultUplink(
        phase: AndroidExecutionLifecycleContract.ExecutionLifecyclePhase,
        priorPhaseWasInterruption: Boolean = false
    ): ResultUplinkSemanticClass {
        val P = AndroidExecutionLifecycleContract.ExecutionLifecyclePhase
        return when (phase) {
            // Interruption: V2 must apply retry/fallback policy
            P.INTERRUPTED -> ResultUplinkSemanticClass.AUTHORITATIVE_INTERRUPTION

            // Terminal phases after a prior interruption: recovery completion
            P.COMPLETED, P.FAILED ->
                if (priorPhaseWasInterruption) ResultUplinkSemanticClass.AUTHORITATIVE_RECOVERY
                else ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL

            // Other terminal phases: authoritative terminal (no recovery distinction)
            P.TIMED_OUT, P.REJECTED -> ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL

            // Non-terminal phases: informational
            P.CAPABILITY_IDLE, P.PENDING, P.ACTIVATING, P.ACTIVE,
            P.DEGRADED, P.RETRYING, P.UNKNOWN ->
                ResultUplinkSemanticClass.INFORMATIONAL
        }
    }

    // ── Closure invariants ────────────────────────────────────────────────────

    /**
     * Machine-verifiable closure invariants for this contract.
     * All values must be `true`; any `false` indicates a regression.
     */
    val CLOSURE_INVARIANTS: Map<String, Boolean> = mapOf(

        // ReportedStateSemanticClass wire values cover the expected 5-class vocabulary
        "reported_state_semantic_class_has_5_values" to
            (ReportedStateSemanticClass.ALL_WIRE_VALUES.size == 5),

        // DegradedConditionClass wire values cover the expected 7-class vocabulary
        "degraded_condition_class_has_7_values" to
            (DegradedConditionClass.ALL_WIRE_VALUES.size == 7),

        // ResultUplinkSemanticClass wire values cover the expected 4-class vocabulary
        "result_uplink_semantic_class_has_4_values" to
            (ResultUplinkSemanticClass.ALL_WIRE_VALUES.size == 4),

        // LocalObservationBasis wire values cover the expected 4-value vocabulary
        "local_observation_basis_has_4_values" to
            (LocalObservationBasis.ALL_WIRE_VALUES.size == 4),

        // NOMINAL is the only DegradedConditionClass with isNominal=true
        "only_nominal_degraded_class_is_nominal" to
            (DegradedConditionClass.entries.count { it.isNominal } == 1 &&
                DegradedConditionClass.NOMINAL.isNominal),

        // AUTHORITATIVE_TERMINAL and AUTHORITATIVE_RECOVERY are the terminal result classes
        "terminal_result_uplink_classes_count_is_2" to
            (ResultUplinkSemanticClass.entries.count { it.isTerminal } == 2),

        // classifyResultUplink returns INFORMATIONAL for CAPABILITY_IDLE
        "classify_result_uplink_capability_idle_is_informational" to
            (classifyResultUplink(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.CAPABILITY_IDLE
            ) == ResultUplinkSemanticClass.INFORMATIONAL),

        // classifyResultUplink returns AUTHORITATIVE_INTERRUPTION for INTERRUPTED
        "classify_result_uplink_interrupted_is_authoritative_interruption" to
            (classifyResultUplink(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.INTERRUPTED
            ) == ResultUplinkSemanticClass.AUTHORITATIVE_INTERRUPTION),

        // classifyResultUplink returns AUTHORITATIVE_RECOVERY for COMPLETED after interruption
        "classify_result_uplink_completed_after_interruption_is_recovery" to
            (classifyResultUplink(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED,
                priorPhaseWasInterruption = true
            ) == ResultUplinkSemanticClass.AUTHORITATIVE_RECOVERY),

        // classifyResultUplink returns AUTHORITATIVE_TERMINAL for COMPLETED without interruption
        "classify_result_uplink_completed_without_interruption_is_terminal" to
            (classifyResultUplink(
                AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED,
                priorPhaseWasInterruption = false
            ) == ResultUplinkSemanticClass.AUTHORITATIVE_TERMINAL),

        // classifyDegradedCondition returns NOMINAL when no conditions present
        "classify_degraded_condition_nominal_when_no_conditions" to
            (classifyDegradedCondition() == DegradedConditionClass.NOMINAL),

        // classifyDegradedCondition returns DEGRADED when degradedReasons is non-empty
        "classify_degraded_condition_degraded_when_reasons_non_empty" to
            (classifyDegradedCondition(degradedReasons = listOf("inference_unavailable")) ==
                DegradedConditionClass.DEGRADED),

        // classifyDegradedCondition returns RECOVERED when reconnect_recovery_state=recovered
        "classify_degraded_condition_recovered_when_recovered" to
            (classifyDegradedCondition(reconnectRecoveryState = "recovered") ==
                DegradedConditionClass.RECOVERED),

        // classifySnapshot returns TERMINAL_REPORTING when carrier_runtime_state=failed
        "classify_snapshot_terminal_reporting_when_carrier_failed" to
            (classifySnapshot(carrierRuntimeState = "failed") ==
                ReportedStateSemanticClass.TERMINAL_REPORTING),

        // classifySnapshot returns ACTIVE_RUNTIME when execution_mode_state=cross_device_active
        "classify_snapshot_active_runtime_when_cross_device_active" to
            (classifySnapshot(executionModeState = "cross_device_active") ==
                ReportedStateSemanticClass.ACTIVE_RUNTIME),

        // classifySnapshot returns CAPABILITY when no discriminating fields present
        "classify_snapshot_capability_when_no_discriminating_fields" to
            (classifySnapshot() == ReportedStateSemanticClass.CAPABILITY),

        // LIVE_RUNTIME is the only LocalObservationBasis with isFresh=true
        "only_live_runtime_observation_basis_is_fresh" to
            (LocalObservationBasis.entries.count { it.isFresh } == 1 &&
                LocalObservationBasis.LIVE_RUNTIME.isFresh),

        // All three new DeviceStateSnapshotPayload canonical-truth fields default to null
        // (compile-time presence verified; runtime null-default verified here via an actual instance)
        "snapshot_new_fields_default_to_null" to
            com.ufo.galaxy.protocol.DeviceStateSnapshotPayload(
                device_id = "test",
                llama_cpp_available = false,
                ncnn_available = false,
                active_runtime_type = "CENTER",
                model_ready = false,
                accessibility_ready = false,
                overlay_ready = false,
                local_loop_ready = false,
                model_id = null,
                runtime_type = null,
                checksum_ok = null,
                mobilevlm_present = false,
                mobilevlm_checksum_ok = false,
                seeclick_present = false,
                pending_first_download = true,
                warmup_result = "unavailable",
                offline_queue_depth = 0,
                current_fallback_tier = null
            ).let {
                it.reported_state_semantic_class == null &&
                    it.degraded_condition_class == null &&
                    it.local_observation_basis == null
            }
    )

    /** The canonical test class for this contract. */
    const val TEST_CLASS = "Pr8AndroidCanonicalRuntimeTruthUnificationTest"
}
