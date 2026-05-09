package com.ufo.galaxy.runtime

/**
 * PR-08 (Android side) — Canonical Runtime Truth Unification: state-surface and reporting
 * semantics alignment with center-side canonical truth.
 *
 * [AndroidCanonicalTruthAlignmentContract] is the formal Android-side declaration for
 * the cross-repo PR-08 work: aligning Android runtime state/reporting surfaces with
 * the center-side canonical truth model.
 *
 * ## Problem addressed
 *
 * Prior Android PRs established many individual state surfaces and reporting paths but
 * left four semantic gaps:
 *
 *  1. **State-surface distribution** — capability, observation, active execution, and
 *     terminal state were distributed across orthogonal surfaces with no single contract
 *     declaring their canonical category and precedence.
 *  2. **Uplink semantic ambiguity** — result and state uplinks lacked a unified canonical
 *     classification of _reporting scenario_, forcing center-side consumers to infer whether
 *     a snapshot was emitted from a normal, degraded, fallback, constrained, delayed, partial,
 *     or recovered context.
 *  3. **Observed vs derived vs reported blurring** — surfaces such as
 *     [LocalExecutionModeGate], [CapabilityAuthoritySnapshot], and
 *     [AndroidExecutionLifecycleContract] are each locally derived or locally observed;
 *     no contract declared the distinction or the precedence when they disagree.
 *  4. **Degraded/fallback/constrained/partial/delayed/recovered reporting** — these
 *     six conditions each require distinct uplink semantics, but prior PRs only partially
 *     covered them (DEGRADED in [ExecutionUplinkDiscipline], fallback_tier in
 *     [DeviceExecutionEventPayload], constrained_reasons in [DeviceStateSnapshotPayload]).
 *
 * ## This PR closes these gaps by:
 *
 *  1. [StateSurfaceClass] — canonical four-category classification for every Android state
 *     surface: [StateSurfaceClass.LOCALLY_OBSERVED], [StateSurfaceClass.LOCALLY_DERIVED],
 *     [StateSurfaceClass.ACTIVE_RUNTIME], [StateSurfaceClass.TERMINAL_REPORTED].
 *  2. [ReportingScenario] — canonical seven-scenario classification for Android uplink
 *     reporting context: NORMAL, DEGRADED, FALLBACK, CONSTRAINED, DELAYED, PARTIAL, RECOVERED.
 *  3. [STATE_SURFACE_REGISTRY] — machine-verifiable mapping of all canonical Android state
 *     surfaces to their [StateSurfaceClass], including precedence rules.
 *  4. [REPORTING_SCENARIO_UPLINK_RULES] — the canonical uplink discipline for each
 *     [ReportingScenario], including required fields and V2 consumer action.
 *  5. [CANONICAL_TRUTH_INVARIANTS] — testable invariants ensuring no ambiguity between
 *     surface categories and reporting scenarios.
 *
 * ## Wire protocol alignment
 *
 * Two new fields are added to both [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload]
 * and [com.ufo.galaxy.protocol.DeviceExecutionEventPayload]:
 *
 *  - `reporting_scenario` ([ReportingScenario.wireValue]) — canonical reporting context.
 *  - `state_surface_class` ([StateSurfaceClass.wireValue]) — the dominant state category
 *    at snapshot/event emission time.
 *
 * V2 center-side consumers MUST read `reporting_scenario` to correctly classify snapshots
 * and apply the appropriate canonical truth policy before reading individual state fields.
 *
 * @see StateSurfaceClass
 * @see ReportingScenario
 * @see STATE_SURFACE_REGISTRY
 * @see REPORTING_SCENARIO_UPLINK_RULES
 * @see CANONICAL_TRUTH_INVARIANTS
 * @see ExecutionUplinkDiscipline
 * @see AndroidExecutionLifecycleContract
 */
object AndroidCanonicalTruthAlignmentContract {

    // ── State surface classification ──────────────────────────────────────────

    /**
     * Canonical four-category classification of Android-side state surfaces.
     *
     * Every Android state surface MUST fall into exactly one of these four categories.
     * Center-side consumers MUST use this classification when interpreting Android state
     * snapshots and execution events.
     *
     * ## Category precedence for conflict resolution
     *
     * When two surfaces in different categories report conflicting state for the same
     * semantic concept, the following precedence applies (highest → lowest):
     *
     * ```
     * ACTIVE_RUNTIME > TERMINAL_REPORTED > LOCALLY_DERIVED > LOCALLY_OBSERVED
     * ```
     *
     * Example: if [ACTIVE_RUNTIME] says a task is ACTIVE but [LOCALLY_OBSERVED] says the
     * foreground is invisible, the ACTIVE_RUNTIME state takes precedence for execution
     * dispatch eligibility — but both are included in the snapshot for full observability.
     *
     * @property wireValue   Stable lowercase wire value for JSON payloads and logging.
     * @property description Human-readable category description.
     * @property precedence  Integer precedence (higher = takes priority in conflict resolution).
     */
    enum class StateSurfaceClass(
        val wireValue: String,
        val description: String,
        val precedence: Int
    ) {

        /**
         * State that Android directly observes from hardware, OS, or user interaction.
         *
         * Examples: `carrier_foreground_visible` (from ProcessLifecycleOwner),
         * `accessibility_ready` (from AccessibilityService.isServiceRunning),
         * `llama_cpp_available` (from NativeLoader), `model_ready` (from file presence check).
         *
         * Observation truth is never derived or inferred — it is a direct poll or callback
         * from an Android platform API. Center-side consumers should treat these as the
         * most raw form of Android truth.
         *
         * **V2 consumer guidance**: Use as baseline evidence for capability assessment.
         * Do NOT derive capability from observations alone — cross-reference with
         * [LOCALLY_DERIVED] authority surfaces.
         */
        LOCALLY_OBSERVED(
            wireValue = "locally_observed",
            description = "Directly observed from hardware, OS, or user interaction — not derived or inferred.",
            precedence = 10
        ),

        /**
         * State that Android derives from one or more locally observed inputs using a
         * documented derivation contract.
         *
         * Examples: `execution_mode_state` (derived via [LocalExecutionModeGate.decide] from
         * observed `cross_device_enabled`, WS connection state, and capability degradation flag);
         * `participant_identity_freshness` (derived via [com.ufo.galaxy.session.DurableParticipantIdentity.createFromGap]
         * from last-active gap); `capability_schema_version` (derived from [CapabilityAuthoritySnapshot]).
         *
         * Derived truth has an explicit, testable derivation contract. Center-side consumers
         * MUST prefer derived surfaces over raw observations when both are present.
         *
         * **V2 consumer guidance**: Prefer these surfaces for dispatch eligibility and
         * capability classification. They subsume the raw observed inputs and apply
         * canonical Android-side derivation logic that V2 must not replicate.
         */
        LOCALLY_DERIVED(
            wireValue = "locally_derived",
            description = "Derived from observed inputs using a documented derivation contract.",
            precedence = 20
        ),

        /**
         * State reflecting an actively executing task or active runtime participation.
         *
         * Examples: `phase` field in [com.ufo.galaxy.protocol.DeviceExecutionEventPayload]
         * when phase is ACTIVE or DEGRADED; active `task_id` in flight;
         * [AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.ACTIVE] or
         * [AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.DEGRADED].
         *
         * Active runtime state is the authoritative Android-side signal for in-flight
         * execution. When an [ACTIVE_RUNTIME] state is present, center-side dispatch MUST NOT
         * assign a new task to this participant until the execution reaches a terminal state.
         *
         * **V2 consumer guidance**: This is the highest-precedence category for dispatch
         * eligibility. Block new dispatch while any [ACTIVE_RUNTIME] surface reports active.
         */
        ACTIVE_RUNTIME(
            wireValue = "active_runtime",
            description = "Reflects an actively executing task or active runtime participation.",
            precedence = 30
        ),

        /**
         * State representing a completed terminal outcome that Android has reported to the center.
         *
         * Examples: [AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.COMPLETED],
         * [AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.FAILED],
         * [AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.REJECTED].
         *
         * Terminal reported state means Android has emitted a result uplink for this outcome
         * and has cleared or is clearing its in-flight task state. Center-side consumers MUST
         * process the result uplink before treating the participant as eligible for new dispatch.
         *
         * **V2 consumer guidance**: Consume the result uplink first. Only after applying the
         * terminal result should the participant be returned to the dispatch-eligible pool.
         */
        TERMINAL_REPORTED(
            wireValue = "terminal_reported",
            description = "Terminal execution outcome that has been reported to the center via result uplink.",
            precedence = 25
        );

        companion object {
            /** All wire values as a stable set for contract validation. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()

            /** The category with the highest precedence. */
            val HIGHEST_PRECEDENCE: StateSurfaceClass = entries.maxByOrNull { it.precedence }!!

            /** Parse a wire value to a [StateSurfaceClass], or `null` if unknown. */
            fun fromWireValue(wireValue: String?): StateSurfaceClass? =
                entries.firstOrNull { it.wireValue == wireValue }
        }
    }

    // ── Reporting scenario classification ─────────────────────────────────────

    /**
     * Canonical seven-scenario classification for Android uplink reporting context.
     *
     * The `reporting_scenario` field in [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload]
     * and [com.ufo.galaxy.protocol.DeviceExecutionEventPayload] carries this classification
     * so center-side consumers can apply the appropriate canonical truth policy immediately,
     * without parsing individual state fields to infer context.
     *
     * ## Scenario ordering for center-side truth policy
     *
     * | Scenario     | Center-side truth policy                                              |
     * |--------------|-----------------------------------------------------------------------|
     * | NORMAL       | Accept all state fields at face value.                                |
     * | DEGRADED     | Reduce capability estimate; block full-capability dispatch.           |
     * | FALLBACK     | Treat as reduced-path execution; expect fallback_tier field.          |
     * | CONSTRAINED  | Participant is constrained; expect mesh_constrained_reasons.          |
     * | DELAYED      | Execution is delayed; do not treat as failed; await state update.     |
     * | PARTIAL      | Result covers a subset of assigned work; do not close the full task.  |
     * | RECOVERED    | Participant has recovered from a prior degraded/failed state.         |
     *
     * @property wireValue       Stable lowercase wire value for JSON payloads.
     * @property description     Human-readable scenario description.
     * @property v2PolicyAction  The canonical V2 center-side policy action for this scenario.
     * @property requiresDegradedFields  True when the snapshot MUST include degraded-context fields.
     * @property isTerminal              True when this scenario implies a terminal execution outcome.
     */
    enum class ReportingScenario(
        val wireValue: String,
        val description: String,
        val v2PolicyAction: String,
        val requiresDegradedFields: Boolean = false,
        val isTerminal: Boolean = false
    ) {

        /**
         * Normal execution with no degradation, fallback, constraint, delay, partial result,
         * or recovery context.
         *
         * All state fields should be interpreted at face value. No special V2 policy applies.
         */
        NORMAL(
            wireValue = "normal",
            description = "Normal execution with no degradation or exceptional context.",
            v2PolicyAction = "accept_state_at_face_value"
        ),

        /**
         * Android is executing in a degraded state — one or more capability components are
         * unavailable or impaired, but execution is continuing with reduced capability.
         *
         * Required fields when this scenario is reported:
         *  - `local_intelligence_status` — the degraded component (DEGRADED, UNAVAILABLE, or RECOVERING).
         *  - `degraded_reasons` — non-empty list of machine-readable degradation reasons.
         *  - `execution_mode_state` — CROSS_DEVICE_DEGRADED or LOCAL_ONLY.
         *
         * **V2 policy**: Reduce capability estimate for this participant. Do NOT assign
         * full-capability tasks while DEGRADED is the reported scenario.
         */
        DEGRADED(
            wireValue = "degraded",
            description = "Execution continuing with reduced capability due to component degradation.",
            v2PolicyAction = "reduce_capability_estimate_block_full_capability_dispatch",
            requiresDegradedFields = true
        ),

        /**
         * Android has transitioned to a fallback execution path — a lower-fidelity or
         * lower-reliability path because the primary path was unavailable.
         *
         * Required fields when this scenario is reported:
         *  - `current_fallback_tier` — identifies the active fallback tier.
         *  - `fallback_tier` (in execution events) — active tier at event time.
         *
         * **V2 policy**: Treat as reduced-path execution. The task may still complete,
         * but with lower quality or reliability than a non-fallback execution.
         */
        FALLBACK(
            wireValue = "fallback",
            description = "Execution on a lower-fidelity fallback path after primary path failure.",
            v2PolicyAction = "treat_as_reduced_path_execution_expect_fallback_tier"
        ),

        /**
         * Android's participation is constrained by resource, policy, or mesh conditions.
         *
         * Required fields when this scenario is reported:
         *  - `mesh_constrained_reasons` — non-empty list of constraint reasons (from
         *    [AndroidMeshParticipationRuntimeContract]).
         *
         * **V2 policy**: Participant is alive but operating under constraints. Do not
         * assign additional tasks until constraints are lifted and a NORMAL snapshot arrives.
         */
        CONSTRAINED(
            wireValue = "constrained",
            description = "Participation constrained by resource, policy, or mesh conditions.",
            v2PolicyAction = "hold_additional_dispatch_until_constraints_cleared"
        ),

        /**
         * Execution has been accepted but is delayed before starting (e.g. waiting for a
         * resource to become available, a barrier to be released, or a timeout to expire).
         *
         * Required fields when this scenario is reported:
         *  - `phase` (in execution events) — PENDING or ACTIVATING.
         *  - `barrier_participation_state` — WAITING (if the delay is barrier-driven).
         *
         * **V2 policy**: Do NOT treat this as a failure. Await a follow-up state snapshot
         * indicating the delay has resolved (NORMAL or ACTIVE scenario).
         */
        DELAYED(
            wireValue = "delayed",
            description = "Execution accepted but delayed before active processing begins.",
            v2PolicyAction = "do_not_treat_as_failure_await_followup_state_snapshot"
        ),

        /**
         * Android has completed or reported a subset of the assigned work, but the full task
         * is not yet finished (e.g. a parallel subtask result, a partial plan execution, or
         * a mid-flow checkpoint).
         *
         * Required fields when this scenario is reported:
         *  - In execution events: `phase` = EXECUTION_PROGRESS or a subtask-specific phase.
         *  - The result payload MUST NOT be treated as a final task closure.
         *
         * **V2 policy**: Accumulate partial results. Do not close the parent task until
         * a NORMAL terminal scenario arrives.
         */
        PARTIAL(
            wireValue = "partial",
            description = "Partial work reported; the full task remains open.",
            v2PolicyAction = "accumulate_partial_results_do_not_close_parent_task"
        ),

        /**
         * Android has recovered from a prior DEGRADED, FALLBACK, CONSTRAINED, or DELAYED
         * state and is now reporting that recovery is complete.
         *
         * Required fields when this scenario is reported:
         *  - `reconnect_recovery_state` — RECOVERED (from [ReconnectRecoveryState]).
         *  - `participant_identity_freshness` — RECOVERED or FRESH (from
         *    [com.ufo.galaxy.session.DurableParticipantIdentity.IdentityFreshness]).
         *
         * **V2 policy**: Re-evaluate dispatch eligibility. The participant has returned to
         * a higher-capability state and may now accept full-capability tasks if other
         * capability signals confirm readiness.
         */
        RECOVERED(
            wireValue = "recovered",
            description = "Recovery from a prior degraded/fallback/constrained state is complete.",
            v2PolicyAction = "re_evaluate_dispatch_eligibility_capability_may_be_restored"
        );

        companion object {
            /** Wire key for `reporting_scenario` field in protocol payloads. */
            const val WIRE_KEY = "reporting_scenario"

            /** All wire values as a stable set for contract validation. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()

            /** Parse a wire value to a [ReportingScenario], or `null` if unknown. */
            fun fromWireValue(wireValue: String?): ReportingScenario? =
                entries.firstOrNull { it.wireValue == wireValue }

            /** Scenarios that require degraded-context fields in the payload. */
            val DEGRADED_CONTEXT_SCENARIOS: Set<ReportingScenario> =
                entries.filter { it.requiresDegradedFields }.toSet()

            /** Scenarios that do NOT represent terminal outcomes. */
            val NON_TERMINAL_SCENARIOS: Set<ReportingScenario> =
                entries.filter { !it.isTerminal }.toSet()
        }
    }

    // ── State surface registry ────────────────────────────────────────────────

    /**
     * A single registered Android state surface entry in the canonical truth registry.
     *
     * @property surfaceKey          Stable identifier for this surface (kebab-case).
     * @property surfaceClass        The [StateSurfaceClass] for this surface.
     * @property wireKey             The wire-protocol key for this surface's primary value.
     * @property sourceType          Whether the surface is from a snapshot or event payload.
     * @property description         One-sentence description of what this surface represents.
     * @property v2ConsumerNote      V2 consumer guidance for this surface.
     */
    data class StateSurfaceEntry(
        val surfaceKey: String,
        val surfaceClass: StateSurfaceClass,
        val wireKey: String,
        val sourceType: SourceType,
        val description: String,
        val v2ConsumerNote: String
    ) {
        enum class SourceType(val wireValue: String) {
            SNAPSHOT("snapshot"),
            EXECUTION_EVENT("execution_event"),
            BOTH("both")
        }
    }

    /**
     * Canonical machine-readable registry of all Android state surfaces and their
     * [StateSurfaceClass] classification.
     *
     * Center-side consumers MUST use this registry to determine the category and
     * precedence of each field before applying canonical truth policy.
     *
     * Keyed by [StateSurfaceEntry.surfaceKey].
     */
    val STATE_SURFACE_REGISTRY: Map<String, StateSurfaceEntry> = linkedMapOf(

        // ── LOCALLY_OBSERVED surfaces ──────────────────────────────────────────

        "carrier-foreground-visible" to StateSurfaceEntry(
            surfaceKey = "carrier-foreground-visible",
            surfaceClass = StateSurfaceClass.LOCALLY_OBSERVED,
            wireKey = "carrier_foreground_visible",
            sourceType = StateSurfaceEntry.SourceType.BOTH,
            description = "Whether the app is currently in the foreground (ProcessLifecycleOwner observation).",
            v2ConsumerNote = "Use as presence hint; do NOT block dispatch solely on foreground state."
        ),
        "accessibility-ready" to StateSurfaceEntry(
            surfaceKey = "accessibility-ready",
            surfaceClass = StateSurfaceClass.LOCALLY_OBSERVED,
            wireKey = "accessibility_ready",
            sourceType = StateSurfaceEntry.SourceType.SNAPSHOT,
            description = "Whether the accessibility service is active (direct AccessibilityService observation).",
            v2ConsumerNote = "Required for interaction-surface tasks; cross-reference with interaction_surface_ready."
        ),
        "overlay-ready" to StateSurfaceEntry(
            surfaceKey = "overlay-ready",
            surfaceClass = StateSurfaceClass.LOCALLY_OBSERVED,
            wireKey = "overlay_ready",
            sourceType = StateSurfaceEntry.SourceType.SNAPSHOT,
            description = "Whether the overlay draw permission is granted (direct OS permission observation).",
            v2ConsumerNote = "Required for floating-overlay tasks; cross-reference with interaction_surface_ready."
        ),
        "llama-cpp-available" to StateSurfaceEntry(
            surfaceKey = "llama-cpp-available",
            surfaceClass = StateSurfaceClass.LOCALLY_OBSERVED,
            wireKey = "llama_cpp_available",
            sourceType = StateSurfaceEntry.SourceType.SNAPSHOT,
            description = "Whether the llama.cpp native library loaded successfully (NativeLoader observation).",
            v2ConsumerNote = "Raw native availability; use local_intelligence_status for derived capability classification."
        ),
        "model-ready" to StateSurfaceEntry(
            surfaceKey = "model-ready",
            surfaceClass = StateSurfaceClass.LOCALLY_OBSERVED,
            wireKey = "model_ready",
            sourceType = StateSurfaceEntry.SourceType.SNAPSHOT,
            description = "Whether the on-device model file is present and verified (file-system observation).",
            v2ConsumerNote = "Raw model presence; use local_intelligence_status for derived capability classification."
        ),

        // ── LOCALLY_DERIVED surfaces ───────────────────────────────────────────

        "execution-mode-state" to StateSurfaceEntry(
            surfaceKey = "execution-mode-state",
            surfaceClass = StateSurfaceClass.LOCALLY_DERIVED,
            wireKey = "execution_mode_state",
            sourceType = StateSurfaceEntry.SourceType.BOTH,
            description = "Five-state execution mode derived via LocalExecutionModeGate.decide().",
            v2ConsumerNote = "MUST be read instead of inferring from mode_state/mode_readiness_state combinations."
        ),
        "participant-identity-freshness" to StateSurfaceEntry(
            surfaceKey = "participant-identity-freshness",
            surfaceClass = StateSurfaceClass.LOCALLY_DERIVED,
            wireKey = "participant_identity_freshness",
            sourceType = StateSurfaceEntry.SourceType.SNAPSHOT,
            description = "Identity freshness classification (FRESH/RECOVERED/STALE) derived from offline gap.",
            v2ConsumerNote = "Android-authoritative freshness; MUST be read rather than applying center-side heuristics."
        ),
        "local-intelligence-status" to StateSurfaceEntry(
            surfaceKey = "local-intelligence-status",
            surfaceClass = StateSurfaceClass.LOCALLY_DERIVED,
            wireKey = "local_intelligence_status",
            sourceType = StateSurfaceEntry.SourceType.SNAPSHOT,
            description = "Derived single-field inference capability status from CapabilityAuthoritySnapshot.",
            v2ConsumerNote = "Canonical inference signal; do NOT infer from warmup_result or runtime_health_snapshot."
        ),
        "interaction-surface-ready" to StateSurfaceEntry(
            surfaceKey = "interaction-surface-ready",
            surfaceClass = StateSurfaceClass.LOCALLY_DERIVED,
            wireKey = "interaction_surface_ready",
            sourceType = StateSurfaceEntry.SourceType.BOTH,
            description = "Derived: accessibility_ready AND overlay_ready. True when full interaction surface operational.",
            v2ConsumerNote = "Use for task eligibility gating on UI-interaction tasks."
        ),
        "carrier-runtime-state" to StateSurfaceEntry(
            surfaceKey = "carrier-runtime-state",
            surfaceClass = StateSurfaceClass.LOCALLY_DERIVED,
            wireKey = "carrier_runtime_state",
            sourceType = StateSurfaceEntry.SourceType.BOTH,
            description = "Wire label of RuntimeController.state at snapshot/event time (idle/starting/active/failed/local_only).",
            v2ConsumerNote = "Derived from RuntimeController state machine; use to classify snapshot emission context."
        ),
        "reconnect-recovery-state" to StateSurfaceEntry(
            surfaceKey = "reconnect-recovery-state",
            surfaceClass = StateSurfaceClass.LOCALLY_DERIVED,
            wireKey = "reconnect_recovery_state",
            sourceType = StateSurfaceEntry.SourceType.SNAPSHOT,
            description = "Wire value of RuntimeController.reconnectRecoveryState (idle/recovering/recovered/failed).",
            v2ConsumerNote = "Tag snapshots emitted during reconnect recovery cycles with appropriate handling."
        ),

        // ── ACTIVE_RUNTIME surfaces ────────────────────────────────────────────

        "execution-lifecycle-phase" to StateSurfaceEntry(
            surfaceKey = "execution-lifecycle-phase",
            surfaceClass = StateSurfaceClass.ACTIVE_RUNTIME,
            wireKey = "phase",
            sourceType = StateSurfaceEntry.SourceType.EXECUTION_EVENT,
            description = "Current AndroidExecutionLifecycleContract phase (e.g. active, degraded, retrying).",
            v2ConsumerNote = "Highest-precedence signal for dispatch eligibility. Block new dispatch while ACTIVE or DEGRADED."
        ),
        "mesh-participation-lifecycle-state" to StateSurfaceEntry(
            surfaceKey = "mesh-participation-lifecycle-state",
            surfaceClass = StateSurfaceClass.ACTIVE_RUNTIME,
            wireKey = "mesh_participation_lifecycle_state",
            sourceType = StateSurfaceEntry.SourceType.SNAPSHOT,
            description = "Current AndroidMeshParticipationRuntimeContract state (joining/active/barrier_waiting/etc).",
            v2ConsumerNote = "Use for mesh task eligibility gating alongside execution_lifecycle_phase."
        ),
        "collaboration-lifecycle-state" to StateSurfaceEntry(
            surfaceKey = "collaboration-lifecycle-state",
            surfaceClass = StateSurfaceClass.ACTIVE_RUNTIME,
            wireKey = "collaboration_lifecycle_state",
            sourceType = StateSurfaceEntry.SourceType.SNAPSHOT,
            description = "Current LocalCollaborationAgent execution lifecycle state (executing/completed/failed/etc).",
            v2ConsumerNote = "For subtask/collaboration-aware dispatch decisions."
        ),

        // ── TERMINAL_REPORTED surfaces ─────────────────────────────────────────

        "terminal-execution-phase" to StateSurfaceEntry(
            surfaceKey = "terminal-execution-phase",
            surfaceClass = StateSurfaceClass.TERMINAL_REPORTED,
            wireKey = "phase",
            sourceType = StateSurfaceEntry.SourceType.EXECUTION_EVENT,
            description = "Terminal AndroidExecutionLifecycleContract phase (completed/failed/rejected).",
            v2ConsumerNote = "Consume result uplink first. Return participant to dispatch pool only after result applied."
        ),
        "durable-participant-id" to StateSurfaceEntry(
            surfaceKey = "durable-participant-id",
            surfaceClass = StateSurfaceClass.LOCALLY_DERIVED,
            wireKey = "durable_participant_id",
            sourceType = StateSurfaceEntry.SourceType.SNAPSHOT,
            description = "Stable per-installation UUID for cross-era participant record correlation.",
            v2ConsumerNote = "Primary key for android_device_state_store.py; constant across process kills."
        )
    )

    // ── Reporting scenario uplink rules ───────────────────────────────────────

    /**
     * Canonical uplink discipline for a single [ReportingScenario].
     *
     * Specifies what fields MUST be present in the snapshot/event when Android reports
     * in this scenario, and what V2 MUST do on receipt.
     *
     * @property scenario             The [ReportingScenario] this rule governs.
     * @property requiredSnapshotKeys Fields that MUST be populated in [DeviceStateSnapshotPayload].
     * @property requiredEventKeys    Fields that MUST be populated in [DeviceExecutionEventPayload].
     * @property v2PolicyAction       Canonical V2 center-side policy action (matches [ReportingScenario.v2PolicyAction]).
     * @property stateUplinkRequired  Whether a state snapshot MUST accompany this scenario report.
     * @property resultUplinkRequired Whether a result signal MUST accompany this scenario report.
     */
    data class ScenarioUplinkRule(
        val scenario: ReportingScenario,
        val requiredSnapshotKeys: Set<String>,
        val requiredEventKeys: Set<String>,
        val v2PolicyAction: String,
        val stateUplinkRequired: Boolean,
        val resultUplinkRequired: Boolean
    )

    /**
     * Canonical uplink rule table keyed by [ReportingScenario.wireValue].
     *
     * Production emission code and tests MUST consult this table to verify that the
     * correct fields are present for the active reporting scenario.
     */
    val REPORTING_SCENARIO_UPLINK_RULES: Map<String, ScenarioUplinkRule> = run {
        val S = ReportingScenario
        mapOf(
            S.NORMAL.wireValue to ScenarioUplinkRule(
                scenario = S.NORMAL,
                requiredSnapshotKeys = setOf("reporting_scenario", "execution_mode_state"),
                requiredEventKeys = setOf("reporting_scenario"),
                v2PolicyAction = S.NORMAL.v2PolicyAction,
                stateUplinkRequired = false,
                resultUplinkRequired = false
            ),
            S.DEGRADED.wireValue to ScenarioUplinkRule(
                scenario = S.DEGRADED,
                requiredSnapshotKeys = setOf(
                    "reporting_scenario",
                    "execution_mode_state",
                    "local_intelligence_status",
                    "degraded_reasons"
                ),
                requiredEventKeys = setOf("reporting_scenario", "fallback_tier"),
                v2PolicyAction = S.DEGRADED.v2PolicyAction,
                stateUplinkRequired = true,
                resultUplinkRequired = false
            ),
            S.FALLBACK.wireValue to ScenarioUplinkRule(
                scenario = S.FALLBACK,
                requiredSnapshotKeys = setOf(
                    "reporting_scenario",
                    "current_fallback_tier"
                ),
                requiredEventKeys = setOf("reporting_scenario", "fallback_tier"),
                v2PolicyAction = S.FALLBACK.v2PolicyAction,
                stateUplinkRequired = true,
                resultUplinkRequired = false
            ),
            S.CONSTRAINED.wireValue to ScenarioUplinkRule(
                scenario = S.CONSTRAINED,
                requiredSnapshotKeys = setOf(
                    "reporting_scenario",
                    "mesh_constrained_reasons"
                ),
                requiredEventKeys = setOf("reporting_scenario"),
                v2PolicyAction = S.CONSTRAINED.v2PolicyAction,
                stateUplinkRequired = true,
                resultUplinkRequired = false
            ),
            S.DELAYED.wireValue to ScenarioUplinkRule(
                scenario = S.DELAYED,
                requiredSnapshotKeys = setOf(
                    "reporting_scenario",
                    "execution_mode_state"
                ),
                requiredEventKeys = setOf("reporting_scenario"),
                v2PolicyAction = S.DELAYED.v2PolicyAction,
                stateUplinkRequired = true,
                resultUplinkRequired = false
            ),
            S.PARTIAL.wireValue to ScenarioUplinkRule(
                scenario = S.PARTIAL,
                requiredSnapshotKeys = setOf("reporting_scenario"),
                requiredEventKeys = setOf("reporting_scenario"),
                v2PolicyAction = S.PARTIAL.v2PolicyAction,
                stateUplinkRequired = false,
                resultUplinkRequired = false
            ),
            S.RECOVERED.wireValue to ScenarioUplinkRule(
                scenario = S.RECOVERED,
                requiredSnapshotKeys = setOf(
                    "reporting_scenario",
                    "reconnect_recovery_state",
                    "participant_identity_freshness"
                ),
                requiredEventKeys = setOf("reporting_scenario"),
                v2PolicyAction = S.RECOVERED.v2PolicyAction,
                stateUplinkRequired = true,
                resultUplinkRequired = false
            )
        )
    }

    // ── Canonical truth invariants ─────────────────────────────────────────────

    /**
     * Testable canonical truth invariants for the Android-side unification.
     *
     * All values MUST be `true`. Any `false` indicates a semantic gap that the center-side
     * canonical truth model will observe as ambiguous or inconsistent.
     *
     * Tests in [Pr8AndroidCanonicalTruthAlignmentTest] assert all invariants.
     */
    val CANONICAL_TRUTH_INVARIANTS: Map<String, Boolean> = mapOf(

        // Every ReportingScenario has a corresponding uplink rule
        "all_reporting_scenarios_have_uplink_rules" to
            ReportingScenario.entries.all { scenario ->
                REPORTING_SCENARIO_UPLINK_RULES.containsKey(scenario.wireValue)
            },

        // Every wire value in REPORTING_SCENARIO_UPLINK_RULES corresponds to a known scenario
        "all_uplink_rule_keys_are_known_scenarios" to
            REPORTING_SCENARIO_UPLINK_RULES.keys.all { key ->
                ReportingScenario.fromWireValue(key) != null
            },

        // StateSurfaceClass wire values are distinct
        "state_surface_class_wire_values_are_distinct" to
            (StateSurfaceClass.ALL_WIRE_VALUES.size == StateSurfaceClass.entries.size),

        // ReportingScenario wire values are distinct
        "reporting_scenario_wire_values_are_distinct" to
            (ReportingScenario.ALL_WIRE_VALUES.size == ReportingScenario.entries.size),

        // ACTIVE_RUNTIME has the highest precedence among all surface classes
        "active_runtime_has_highest_precedence" to
            (StateSurfaceClass.entries.maxByOrNull { it.precedence } == StateSurfaceClass.ACTIVE_RUNTIME),

        // LOCALLY_OBSERVED has the lowest precedence
        "locally_observed_has_lowest_precedence" to
            (StateSurfaceClass.entries.minByOrNull { it.precedence } == StateSurfaceClass.LOCALLY_OBSERVED),

        // DEGRADED scenario requires degraded fields
        "degraded_scenario_requires_degraded_fields" to
            (ReportingScenario.DEGRADED.requiresDegradedFields),

        // NORMAL scenario does not require degraded fields
        "normal_scenario_does_not_require_degraded_fields" to
            (!ReportingScenario.NORMAL.requiresDegradedFields),

        // DEGRADED, FALLBACK, CONSTRAINED, DELAYED, and RECOVERED all require state uplink
        "degraded_requires_state_uplink" to
            (REPORTING_SCENARIO_UPLINK_RULES[ReportingScenario.DEGRADED.wireValue]?.stateUplinkRequired == true),

        "fallback_requires_state_uplink" to
            (REPORTING_SCENARIO_UPLINK_RULES[ReportingScenario.FALLBACK.wireValue]?.stateUplinkRequired == true),

        "constrained_requires_state_uplink" to
            (REPORTING_SCENARIO_UPLINK_RULES[ReportingScenario.CONSTRAINED.wireValue]?.stateUplinkRequired == true),

        "delayed_requires_state_uplink" to
            (REPORTING_SCENARIO_UPLINK_RULES[ReportingScenario.DELAYED.wireValue]?.stateUplinkRequired == true),

        "recovered_requires_state_uplink" to
            (REPORTING_SCENARIO_UPLINK_RULES[ReportingScenario.RECOVERED.wireValue]?.stateUplinkRequired == true),

        // NORMAL scenario does not require state uplink
        "normal_does_not_require_state_uplink" to
            (REPORTING_SCENARIO_UPLINK_RULES[ReportingScenario.NORMAL.wireValue]?.stateUplinkRequired == false),

        // No scenario in this contract requires a result uplink (result uplinks are governed by ExecutionUplinkDiscipline)
        "no_scenario_requires_result_uplink_in_this_contract" to
            REPORTING_SCENARIO_UPLINK_RULES.values.none { it.resultUplinkRequired },

        // All STATE_SURFACE_REGISTRY entries have non-empty surfaceKey and wireKey
        "all_surface_registry_entries_have_non_empty_keys" to
            STATE_SURFACE_REGISTRY.values.all { it.surfaceKey.isNotBlank() && it.wireKey.isNotBlank() },

        // RECOVERED scenario required snapshot keys include reconnect_recovery_state
        "recovered_snapshot_keys_include_reconnect_recovery_state" to
            (REPORTING_SCENARIO_UPLINK_RULES[ReportingScenario.RECOVERED.wireValue]
                ?.requiredSnapshotKeys?.contains("reconnect_recovery_state") == true),

        // ReportingScenario.WIRE_KEY constant is "reporting_scenario"
        "reporting_scenario_wire_key_is_reporting_scenario" to
            (ReportingScenario.WIRE_KEY == "reporting_scenario")
    )

    // ── Integration alignment declaration ─────────────────────────────────────

    /**
     * PR number that introduces this canonical truth alignment contract.
     *
     * Referenced by tests to confirm the contract version.
     */
    const val INTRODUCED_PR = "PR-08"

    /**
     * Cross-reference to the center-side canonical truth model this contract aligns with.
     */
    const val CENTER_SIDE_CANONICAL_TRUTH_MODEL = "ugcp.truth_event_model.v2"

    /**
     * The canonical Android-side test class that regression-protects this alignment.
     *
     * All tests in this class must pass before any modification to the canonical truth
     * alignment surfaces is considered safe.
     */
    const val TEST_CLASS = "Pr8AndroidCanonicalTruthAlignmentTest"

    /**
     * Validates [CANONICAL_TRUTH_INVARIANTS] and returns a list of violations.
     *
     * An empty list means all canonical truth invariants are satisfied. Used by
     * [Pr8AndroidCanonicalTruthAlignmentTest] to catch future drift.
     */
    fun validateInvariants(): List<String> =
        CANONICAL_TRUTH_INVARIANTS
            .filter { (_, value) -> !value }
            .map { (key, _) -> "CANONICAL_TRUTH_INVARIANT '$key' is false" }
}
