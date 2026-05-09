package com.ufo.galaxy.runtime

/**
 * PR-08 (Android) — Canonical Runtime Truth Unification Contract.
 *
 * Unifies Android-side runtime state and reporting semantics so that local execution
 * state, mesh participation state, fallback/degraded state, and uplink behavior map
 * cleanly into the center-side canonical truth model.
 *
 * ## Problem addressed
 *
 * Prior to PR-08, Android's reporting surfaces blurred four fundamentally different
 * categories of state into a single flat map:
 *
 *  1. **Capability state** — what the device *can do* (static capability advertisement).
 *  2. **Observation state** — what Android locally *observed* (raw runtime sensor signals).
 *  3. **Active execution state** — what is actively *executing right now*.
 *  4. **Derived local state** — state *computed* from observations (e.g. mode gate decision).
 *  5. **Terminal reporting state** — final outcomes (completion/failure/rejection).
 *
 * V2 consumers receiving a flat snapshot payload had no canonical way to know which of
 * these categories the snapshot primarily represented, making it difficult to apply the
 * correct truth-reconciliation policy for each category.
 *
 * Additionally, degraded, fallback, constrained, partial, delayed, and recovered conditions
 * were reported as raw string lists without canonical classification, forcing V2 to apply
 * ad-hoc heuristics.
 *
 * [AndroidCanonicalRuntimeTruthContract] closes these gaps by defining:
 *  1. [ReportedStateCategory] — the five canonical state categories.
 *  2. [DegradedConditionKind] — the eight canonical degraded condition kinds.
 *  3. [classifyStateCategory] — canonical function to derive the category from a snapshot.
 *  4. [classifyDegradedConditions] — canonical function to derive degraded condition kinds.
 *  5. [CANONICAL_TRUTH_INVARIANTS] — machine-verifiable contract invariants.
 *
 * ## Wire protocol alignment
 *
 * The two new fields added to [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload] and
 * [com.ufo.galaxy.protocol.DeviceExecutionEventPayload] are:
 *
 * | Field                   | Wire key                    | Source                                 |
 * |-------------------------|-----------------------------|----------------------------------------|
 * | reported_state_category | "reported_state_category"   | [classifyStateCategory]                |
 * | degraded_condition_kinds| "degraded_condition_kinds"  | [classifyDegradedConditions]           |
 *
 * ## Relationship to prior PR contracts
 *
 * | PR                   | Theme                                       | Surfaces added                                  |
 * |----------------------|---------------------------------------------|-------------------------------------------------|
 * | PR-07 (Android)      | Execution lifecycle hardening               | AndroidExecutionLifecycleContract, ExecutionUplinkDiscipline |
 * | PR-8Android          | R8 canonical path wiring                    | execution_mode_state, durable_participant_id    |
 * | PR-08 (Android, this)| Canonical runtime truth unification         | ReportedStateCategory, DegradedConditionKind    |
 *
 * @see AndroidExecutionLifecycleContract
 * @see ExecutionUplinkDiscipline
 * @see com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
 * @see com.ufo.galaxy.protocol.DeviceExecutionEventPayload
 */
object AndroidCanonicalRuntimeTruthContract {

    // ── Reported state category ───────────────────────────────────────────────

    /**
     * The five canonical categories into which any Android runtime state report falls.
     *
     * Every [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload] and every
     * [com.ufo.galaxy.protocol.DeviceExecutionEventPayload] emission MUST be classified
     * into one of these categories.  The category is carried in the `reported_state_category`
     * wire field so that V2 can apply the correct truth-reconciliation policy.
     *
     * ## V2 reconciliation implications per category
     *
     * | Category                 | V2 dispatch eligibility | V2 truth-reconciliation policy              |
     * |--------------------------|-------------------------|---------------------------------------------|
     * | CAPABILITY_STATE         | Yes (new task OK)       | Update capability cache; no task closure    |
     * | OBSERVATION_STATE        | No (transitioning)      | Record observation; do not update dispatch  |
     * | ACTIVE_EXECUTION_STATE   | No (task in flight)     | Track progress; wait for terminal event     |
     * | DERIVED_LOCAL_STATE      | Conditional             | Apply mode-gate decision; no task closure   |
     * | TERMINAL_REPORTING_STATE | Yes (after clearing)    | Close task; update capability cache         |
     *
     * @property wireValue   Stable snake_case wire value for JSON payloads and logging.
     * @property description Human-readable description for reviewers and debugging.
     */
    enum class ReportedStateCategory(
        val wireValue: String,
        val description: String
    ) {

        /**
         * This report primarily reflects the device's **static capability** advertisement.
         *
         * The device is registered, connected, idle (no active execution), and advertising
         * its capabilities to V2 for dispatch consideration.  The snapshot is safe to use
         * for dispatch scoring and capability cache updates.
         *
         * Canonical example: snapshot emitted on first connection or after returning to idle.
         */
        CAPABILITY_STATE(
            wireValue = "capability_state",
            description = "Device idle and advertising capabilities; safe for dispatch scoring"
        ),

        /**
         * This report primarily reflects an **locally-observed** runtime sensor signal.
         *
         * The device has observed a local runtime event (e.g. recovery transition, lifecycle
         * change, connectivity shift) and is reporting the observed state.  V2 MUST NOT use
         * this snapshot for dispatch decisions — it reflects transitional observation state
         * that has not yet been reconciled into a stable capability or execution state.
         *
         * Canonical example: snapshot emitted during reconnect recovery.
         */
        OBSERVATION_STATE(
            wireValue = "observation_state",
            description = "Locally observed runtime signal; V2 must not use for dispatch"
        ),

        /**
         * This report primarily reflects an **active execution** that is currently in flight.
         *
         * The device has an active task assigned and is executing it.  V2 MUST NOT dispatch
         * additional full-capability tasks to a participant reporting this category.
         *
         * Canonical example: snapshot emitted after execution_started, during execution_progress.
         */
        ACTIVE_EXECUTION_STATE(
            wireValue = "active_execution_state",
            description = "Active task in flight; V2 must not dispatch new full-capability tasks"
        ),

        /**
         * This report primarily reflects a **locally derived** state computed from observations.
         *
         * The device has applied local logic (e.g. [LocalExecutionModeGate], mesh constraints,
         * fallback ladder selection) to its observed sensor state and is reporting the computed
         * result.  V2 MUST apply the mode-gate decision from `execution_mode_state` rather than
         * re-computing it from field combinations.
         *
         * Canonical example: snapshot emitted after a mode transition or fallback tier change.
         */
        DERIVED_LOCAL_STATE(
            wireValue = "derived_local_state",
            description = "Locally derived state from mode gate / mesh / fallback; apply directly"
        ),

        /**
         * This report reflects a **terminal execution outcome** (completion, failure, rejection).
         *
         * V2 MUST use this signal to close the associated task and update its execution record.
         * After processing a terminal report, V2 should re-evaluate dispatch eligibility based
         * on the updated capability fields in the same snapshot.
         *
         * Canonical example: execution event with phase=completed/failed/cancelled/rejected.
         */
        TERMINAL_REPORTING_STATE(
            wireValue = "terminal_reporting_state",
            description = "Terminal execution outcome; V2 must close the task"
        );

        companion object {

            /**
             * Returns the [ReportedStateCategory] for [wireValue], or `null` if unknown.
             */
            fun fromWireValue(wireValue: String?): ReportedStateCategory? =
                entries.firstOrNull { it.wireValue == wireValue }

            /**
             * All stable wire values for validation and forward-compat checks.
             */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Degraded condition kind ───────────────────────────────────────────────

    /**
     * Canonical enumeration of the eight degraded condition kinds that Android may report.
     *
     * Each condition kind maps to a distinct runtime situation that constrains Android's
     * participation quality.  The `degraded_condition_kinds` wire field carries a list of
     * active [wireValue] strings so V2 can identify all concurrent degradation factors.
     *
     * ## V2 degradation handling contract
     *
     * | Kind         | V2 handling                                                                     |
     * |--------------|---------------------------------------------------------------------------------|
     * | DEGRADED     | Restrict dispatch to base-capability tasks only                                 |
     * | FALLBACK     | Accept fallback-tier execution; note reduced result quality                     |
     * | CONSTRAINED  | Apply mesh constraint budgets; do not over-dispatch                             |
     * | PARTIAL      | Expect partial results; do not treat partial completion as failure               |
     * | DELAYED      | Apply extended timeout windows; do not retry prematurely                        |
     * | RECOVERING   | Participant in recovery cycle; do not dispatch; await RECOVERED signal          |
     * | RECOVERED    | Recovery complete but capability realignment may be pending                     |
     * | UNKNOWN      | Forward-compat sentinel; apply conservative handling                            |
     *
     * @property wireValue   Stable snake_case wire value.
     * @property description Human-readable description.
     */
    enum class DegradedConditionKind(
        val wireValue: String,
        val description: String
    ) {

        /**
         * Android runtime health is degraded; only base capabilities are available.
         *
         * Triggered when `local_intelligence_status = "degraded"`, `warmup_result` indicates
         * an impaired runtime, or `degraded_reasons` is non-empty.
         */
        DEGRADED(
            wireValue = "degraded",
            description = "Runtime health degraded; base capabilities only"
        ),

        /**
         * Android is operating on a fallback execution path.
         *
         * Triggered when `current_fallback_tier`, `planner_fallback_tier`, or
         * `grounding_fallback_tier` is non-null.  Result quality may be reduced vs full-path.
         */
        FALLBACK(
            wireValue = "fallback",
            description = "Fallback execution path active; result quality may be reduced"
        ),

        /**
         * Android mesh participation is constrained by resource, rollout, or policy limits.
         *
         * Triggered when `mesh_constrained_reasons` is non-empty or
         * `mesh_participation_lifecycle_state = "constrained"`.
         */
        CONSTRAINED(
            wireValue = "constrained",
            description = "Mesh participation constrained by resource or policy limits"
        ),

        /**
         * Android is producing or has produced a partial execution result.
         *
         * Triggered when execution is active but less than the full capability set is
         * available (ACTIVE_EXECUTION_STATE + DEGRADED).  V2 should not treat a partial
         * completion as a failure.
         */
        PARTIAL(
            wireValue = "partial",
            description = "Partial execution result; do not treat partial completion as failure"
        ),

        /**
         * Android execution is proceeding but with increased latency.
         *
         * Triggered when inference warmup is slow, the offline queue is deep (≥ 3 tasks),
         * or a retry cycle is in progress.  V2 must apply extended timeout windows.
         */
        DELAYED(
            wireValue = "delayed",
            description = "Execution proceeding with increased latency; apply extended timeouts"
        ),

        /**
         * Android is in an active recovery cycle.
         *
         * Triggered when `reconnect_recovery_state = "recovering"`.  V2 MUST NOT dispatch
         * new tasks; await RECOVERED signal before resuming dispatch.
         */
        RECOVERING(
            wireValue = "recovering",
            description = "Active recovery cycle; V2 must not dispatch new tasks"
        ),

        /**
         * Android has completed a recovery cycle; capability realignment may be pending.
         *
         * Triggered when `reconnect_recovery_state = "recovered"`.  V2 should re-evaluate
         * capability before dispatching new tasks.
         */
        RECOVERED(
            wireValue = "recovered",
            description = "Recovery complete; await capability realignment before dispatching"
        ),

        /**
         * Forward-compatibility sentinel for unknown degraded condition kinds.
         *
         * V2 MUST apply conservative handling when this kind is present.
         */
        UNKNOWN(
            wireValue = "unknown",
            description = "Unknown degraded condition; apply conservative handling"
        );

        companion object {

            /**
             * Returns the [DegradedConditionKind] for [wireValue], or `null` if unknown.
             */
            fun fromWireValue(wireValue: String?): DegradedConditionKind? =
                entries.firstOrNull { it.wireValue == wireValue }

            /**
             * All stable wire values for validation and forward-compat checks.
             */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Wire key constants ────────────────────────────────────────────────────

    /** Wire key for the `reported_state_category` field in snapshot and event payloads. */
    const val KEY_REPORTED_STATE_CATEGORY = "reported_state_category"

    /** Wire key for the `degraded_condition_kinds` field in snapshot and event payloads. */
    const val KEY_DEGRADED_CONDITION_KINDS = "degraded_condition_kinds"

    // ── Classification functions ──────────────────────────────────────────────

    /**
     * Derives the canonical [ReportedStateCategory] for a state snapshot from its fields.
     *
     * Classification rules (applied in priority order):
     *
     * 1. If `executionBusy == true` → [ReportedStateCategory.ACTIVE_EXECUTION_STATE].
     * 2. If `reconnectRecoveryState` is `"recovering"` or `"recovered"` →
     *    [ReportedStateCategory.OBSERVATION_STATE] (observing recovery cycle).
     * 3. If any fallback tier is set OR any derived mode-gate fields are non-default →
     *    [ReportedStateCategory.DERIVED_LOCAL_STATE].
     * 4. Otherwise → [ReportedStateCategory.CAPABILITY_STATE].
     *
     * This function is **pure** — it does not read global state and is safe to call
     * from any thread.
     *
     * @param executionBusy           Current `execution_busy` field value.
     * @param reconnectRecoveryState  Current `reconnect_recovery_state` field value.
     * @param currentFallbackTier     Current `current_fallback_tier` field value.
     * @param plannerFallbackTier     Current `planner_fallback_tier` field value.
     * @param groundingFallbackTier   Current `grounding_fallback_tier` field value.
     * @param executionModeState      Current `execution_mode_state` field value.
     * @param meshConstrainedReasons  Current `mesh_constrained_reasons` field value.
     * @return The canonical [ReportedStateCategory] for this snapshot.
     */
    fun classifyStateCategory(
        executionBusy: Boolean?,
        reconnectRecoveryState: String?,
        currentFallbackTier: String?,
        plannerFallbackTier: String?,
        groundingFallbackTier: String?,
        executionModeState: String?,
        meshConstrainedReasons: List<String>
    ): ReportedStateCategory {
        // Rule 1: active execution takes highest priority
        if (executionBusy == true) return ReportedStateCategory.ACTIVE_EXECUTION_STATE

        // Rule 2: recovery observation (transitional; do not use for dispatch)
        if (reconnectRecoveryState == "recovering" || reconnectRecoveryState == "recovered") {
            return ReportedStateCategory.OBSERVATION_STATE
        }

        // Rule 3: derived state — mode gate / fallback / mesh constraint computations dominate
        val hasFallback = !currentFallbackTier.isNullOrEmpty() ||
            !plannerFallbackTier.isNullOrEmpty() ||
            !groundingFallbackTier.isNullOrEmpty()
        val hasModeDecision = executionModeState != null &&
            executionModeState != "inactive" &&
            executionModeState != "local_only"
        val hasConstraint = meshConstrainedReasons.isNotEmpty()
        if (hasFallback || hasModeDecision || hasConstraint) {
            return ReportedStateCategory.DERIVED_LOCAL_STATE
        }

        // Default: capability advertisement state
        return ReportedStateCategory.CAPABILITY_STATE
    }

    /**
     * Derives the canonical [ReportedStateCategory] for an execution event from its phase.
     *
     * Classification rules based on execution event phase:
     *
     * | Phase                  | Category                   |
     * |------------------------|----------------------------|
     * | execution_started      | ACTIVE_EXECUTION_STATE     |
     * | execution_progress     | ACTIVE_EXECUTION_STATE     |
     * | takeover_milestone     | ACTIVE_EXECUTION_STATE     |
     * | completed              | TERMINAL_REPORTING_STATE   |
     * | failed                 | TERMINAL_REPORTING_STATE   |
     * | cancelled              | TERMINAL_REPORTING_STATE   |
     * | stagnation_detected    | TERMINAL_REPORTING_STATE   |
     * | fallback_transition    | DERIVED_LOCAL_STATE        |
     * | (other/unknown)        | OBSERVATION_STATE          |
     *
     * @param phase  The `phase` field value from [com.ufo.galaxy.protocol.DeviceExecutionEventPayload].
     * @return The canonical [ReportedStateCategory] for this execution event.
     */
    fun classifyEventCategory(phase: String): ReportedStateCategory = when (phase) {
        "execution_started", "execution_progress", "takeover_milestone" ->
            ReportedStateCategory.ACTIVE_EXECUTION_STATE
        "completed", "failed", "cancelled", "stagnation_detected" ->
            ReportedStateCategory.TERMINAL_REPORTING_STATE
        "fallback_transition" ->
            ReportedStateCategory.DERIVED_LOCAL_STATE
        else ->
            ReportedStateCategory.OBSERVATION_STATE
    }

    /**
     * Derives the list of active [DegradedConditionKind]s from a snapshot's fields.
     *
     * Returns only the conditions that are **currently active**.  An empty list means the
     * participant is fully healthy and unconstrained.
     *
     * Derivation rules:
     *  - DEGRADED: `localIntelligenceStatus == "degraded"` OR `degradedReasons.isNotEmpty()`
     *  - FALLBACK: any fallback tier field is non-null/non-empty
     *  - CONSTRAINED: `meshConstrainedReasons.isNotEmpty()`
     *  - PARTIAL: `executionBusy && (localIntelligenceStatus == "degraded" OR hasFallback)`
     *  - DELAYED: `offlineQueueDepth >= 3`
     *  - RECOVERING: `reconnectRecoveryState == "recovering"`
     *  - RECOVERED: `reconnectRecoveryState == "recovered"`
     *
     * @param localIntelligenceStatus  Current `local_intelligence_status` field value.
     * @param degradedReasons          Current `degraded_reasons` field value.
     * @param currentFallbackTier      Current `current_fallback_tier` field value.
     * @param plannerFallbackTier      Current `planner_fallback_tier` field value.
     * @param groundingFallbackTier    Current `grounding_fallback_tier` field value.
     * @param meshConstrainedReasons   Current `mesh_constrained_reasons` field value.
     * @param executionBusy            Current `execution_busy` field value.
     * @param offlineQueueDepth        Current `offline_queue_depth` field value.
     * @param reconnectRecoveryState   Current `reconnect_recovery_state` field value.
     * @return List of active [DegradedConditionKind.wireValue] strings; empty if healthy.
     */
    fun classifyDegradedConditions(
        localIntelligenceStatus: String?,
        degradedReasons: List<String>,
        currentFallbackTier: String?,
        plannerFallbackTier: String?,
        groundingFallbackTier: String?,
        meshConstrainedReasons: List<String>,
        executionBusy: Boolean?,
        offlineQueueDepth: Int?,
        reconnectRecoveryState: String?
    ): List<String> {
        val conditions = mutableListOf<String>()

        val isDegraded = localIntelligenceStatus == "degraded" || degradedReasons.isNotEmpty()
        val hasFallback = !currentFallbackTier.isNullOrEmpty() ||
            !plannerFallbackTier.isNullOrEmpty() ||
            !groundingFallbackTier.isNullOrEmpty()

        if (isDegraded) conditions += DegradedConditionKind.DEGRADED.wireValue
        if (hasFallback) conditions += DegradedConditionKind.FALLBACK.wireValue
        if (meshConstrainedReasons.isNotEmpty()) conditions += DegradedConditionKind.CONSTRAINED.wireValue
        if (executionBusy == true && (isDegraded || hasFallback)) {
            conditions += DegradedConditionKind.PARTIAL.wireValue
        }
        if ((offlineQueueDepth ?: 0) >= 3) conditions += DegradedConditionKind.DELAYED.wireValue
        if (reconnectRecoveryState == "recovering") conditions += DegradedConditionKind.RECOVERING.wireValue
        if (reconnectRecoveryState == "recovered") conditions += DegradedConditionKind.RECOVERED.wireValue

        return conditions
    }

    // ── Machine-verifiable contract invariants ────────────────────────────────

    /**
     * Machine-verifiable invariants for the canonical runtime truth contract.
     *
     * All values MUST be `true`.  Any `false` indicates a contract regression.
     * Tests in [com.ufo.galaxy.runtime.Pr08AndroidCanonicalRuntimeTruthTest] assert all
     * invariants hold.
     */
    val CANONICAL_TRUTH_INVARIANTS: Map<String, Boolean> = mapOf(

        // All ReportedStateCategory entries have distinct wire values
        "state_category_wire_values_distinct" to
            (ReportedStateCategory.entries.map { it.wireValue }.toSet().size ==
                ReportedStateCategory.entries.size),

        // All DegradedConditionKind entries have distinct wire values
        "degraded_condition_wire_values_distinct" to
            (DegradedConditionKind.entries.map { it.wireValue }.toSet().size ==
                DegradedConditionKind.entries.size),

        // ReportedStateCategory has exactly 5 entries
        "state_category_count_is_five" to
            (ReportedStateCategory.entries.size == 5),

        // DegradedConditionKind has exactly 8 entries
        "degraded_condition_count_is_eight" to
            (DegradedConditionKind.entries.size == 8),

        // KEY_REPORTED_STATE_CATEGORY matches the payload field name
        "reported_state_category_key_matches_field_name" to
            (KEY_REPORTED_STATE_CATEGORY == "reported_state_category"),

        // KEY_DEGRADED_CONDITION_KINDS matches the payload field name
        "degraded_condition_kinds_key_matches_field_name" to
            (KEY_DEGRADED_CONDITION_KINDS == "degraded_condition_kinds"),

        // CAPABILITY_STATE is distinct from ACTIVE_EXECUTION_STATE
        "capability_and_active_are_distinct" to
            (ReportedStateCategory.CAPABILITY_STATE != ReportedStateCategory.ACTIVE_EXECUTION_STATE),

        // TERMINAL_REPORTING_STATE is distinct from ACTIVE_EXECUTION_STATE
        "terminal_and_active_are_distinct" to
            (ReportedStateCategory.TERMINAL_REPORTING_STATE != ReportedStateCategory.ACTIVE_EXECUTION_STATE),

        // classifyStateCategory: executionBusy=true → ACTIVE_EXECUTION_STATE
        "execution_busy_maps_to_active_execution_state" to
            (classifyStateCategory(
                executionBusy = true,
                reconnectRecoveryState = null,
                currentFallbackTier = null,
                plannerFallbackTier = null,
                groundingFallbackTier = null,
                executionModeState = null,
                meshConstrainedReasons = emptyList()
            ) == ReportedStateCategory.ACTIVE_EXECUTION_STATE),

        // classifyStateCategory: recovering → OBSERVATION_STATE
        "recovering_maps_to_observation_state" to
            (classifyStateCategory(
                executionBusy = false,
                reconnectRecoveryState = "recovering",
                currentFallbackTier = null,
                plannerFallbackTier = null,
                groundingFallbackTier = null,
                executionModeState = null,
                meshConstrainedReasons = emptyList()
            ) == ReportedStateCategory.OBSERVATION_STATE),

        // classifyStateCategory: fallback tier set → DERIVED_LOCAL_STATE
        "fallback_tier_maps_to_derived_local_state" to
            (classifyStateCategory(
                executionBusy = false,
                reconnectRecoveryState = null,
                currentFallbackTier = "tier_2",
                plannerFallbackTier = null,
                groundingFallbackTier = null,
                executionModeState = null,
                meshConstrainedReasons = emptyList()
            ) == ReportedStateCategory.DERIVED_LOCAL_STATE),

        // classifyStateCategory: idle healthy → CAPABILITY_STATE
        "idle_healthy_maps_to_capability_state" to
            (classifyStateCategory(
                executionBusy = false,
                reconnectRecoveryState = null,
                currentFallbackTier = null,
                plannerFallbackTier = null,
                groundingFallbackTier = null,
                executionModeState = null,
                meshConstrainedReasons = emptyList()
            ) == ReportedStateCategory.CAPABILITY_STATE),

        // classifyEventCategory: completed → TERMINAL_REPORTING_STATE
        "event_completed_maps_to_terminal_state" to
            (classifyEventCategory("completed") == ReportedStateCategory.TERMINAL_REPORTING_STATE),

        // classifyEventCategory: execution_started → ACTIVE_EXECUTION_STATE
        "event_execution_started_maps_to_active_state" to
            (classifyEventCategory("execution_started") == ReportedStateCategory.ACTIVE_EXECUTION_STATE),

        // classifyEventCategory: fallback_transition → DERIVED_LOCAL_STATE
        "event_fallback_transition_maps_to_derived_state" to
            (classifyEventCategory("fallback_transition") == ReportedStateCategory.DERIVED_LOCAL_STATE),

        // classifyDegradedConditions: empty inputs → empty list
        "healthy_state_produces_empty_degraded_conditions" to
            classifyDegradedConditions(
                localIntelligenceStatus = "active",
                degradedReasons = emptyList(),
                currentFallbackTier = null,
                plannerFallbackTier = null,
                groundingFallbackTier = null,
                meshConstrainedReasons = emptyList(),
                executionBusy = false,
                offlineQueueDepth = 0,
                reconnectRecoveryState = "idle"
            ).isEmpty(),

        // classifyDegradedConditions: recovering state produces RECOVERING condition
        "recovering_state_produces_recovering_condition" to
            classifyDegradedConditions(
                localIntelligenceStatus = "active",
                degradedReasons = emptyList(),
                currentFallbackTier = null,
                plannerFallbackTier = null,
                groundingFallbackTier = null,
                meshConstrainedReasons = emptyList(),
                executionBusy = false,
                offlineQueueDepth = 0,
                reconnectRecoveryState = "recovering"
            ).contains(DegradedConditionKind.RECOVERING.wireValue)
    )

    // ── Test evidence surface ─────────────────────────────────────────────────

    /** Canonical test class that regression-protects this contract. */
    const val TEST_CLASS = "Pr08AndroidCanonicalRuntimeTruthTest"

    /** PR number that introduces this contract. */
    const val INTRODUCED_PR = "PR-08"
}
