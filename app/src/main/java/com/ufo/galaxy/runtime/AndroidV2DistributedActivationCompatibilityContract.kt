package com.ufo.galaxy.runtime

/**
 * **Stage 6 / PR-123 — Android-side V2 distributed execution activation compatibility contract.**
 *
 * This contract defines the identity fields, wire keys, and behavioral invariants that ensure
 * Android-emitted task lifecycle [ReconciliationSignal] signals carry enough stable identity for
 * V2's stricter distributed execution activation path to correlate and route them correctly.
 *
 * ## Problem addressed
 *
 * V2's stricter distributed execution activation tracks task lifecycle via a dispatch plan
 * record identified by `dispatch_plan_id`.  Before Stage 6, Android's [ReconciliationSignal]
 * task lifecycle signals (TASK_ACCEPTED, TASK_STATUS_UPDATE, TASK_RESULT, TASK_CANCELLED,
 * TASK_FAILED) did not carry `dispatch_plan_id`, requiring V2 to infer the originating dispatch
 * plan record from a combination of `task_id` + `participant_id` + `correlation_id`.  This
 * inference is fragile once V2 enforces stricter distributed activation semantics where the
 * same `task_id` may theoretically be re-dispatched across recovery boundaries.
 *
 * This contract formalises the fix: Android now stores the `dispatch_plan_id` from the inbound
 * dispatch envelope at task acceptance time and forwards it to every task lifecycle signal in the
 * same activation span.  V2 can correlate any signal to the originating dispatch plan record
 * without ambiguity.
 *
 * ## Identity classification
 *
 * [ActivationIdentityClass] captures how much stable distributed identity is present in a signal:
 *
 * | Class | Description | V2 correlation path |
 * |-------|-------------|---------------------|
 * | [ActivationIdentityClass.FULL_PLAN_IDENTITY] | dispatch_plan_id + task_id + participant_id | Unambiguous; preferred path |
 * | [ActivationIdentityClass.TASK_AND_PARTICIPANT_IDENTITY] | task_id + participant_id without dispatch_plan_id | Acceptable for pre-dispatch-plan senders |
 * | [ActivationIdentityClass.TASK_IDENTITY_ONLY] | task_id only (no participant_id, no dispatch_plan_id) | Weak; V2 must infer participant |
 * | [ActivationIdentityClass.INSUFFICIENT_IDENTITY] | No stable task or plan identity | V2 cannot correlate; signal must be dropped |
 *
 * ## Invariants
 *
 * [DISTRIBUTED_ACTIVATION_INVARIANTS] lists the behavioral invariants Android must uphold to
 * remain a truthful upstream participant in V2's stricter distributed execution activation path.
 *
 * ## Wire key contract
 *
 * [KEY_DISPATCH_PLAN_ID_IN_RECONCILIATION] is the canonical wire key for `dispatch_plan_id` in
 * [ReconciliationSignal] payloads.  It aliases [ReconciliationSignal.KEY_DISPATCH_PLAN_ID].
 * V2 consumers must use this key — not `dispatch_plan_id` bare — to read the field from the
 * payload map.
 *
 * [KEY_ACTIVATION_IDENTITY_CLASS] is the wire key emitted when [classify] produces a
 * [ActivationIdentityClass]; V2 can use it to quickly determine the correlation tier without
 * inspecting individual identity fields.
 */
object AndroidV2DistributedActivationCompatibilityContract {

    // ── Schema version ────────────────────────────────────────────────────────

    /** Schema version for this contract.  Increment when adding non-backward-compatible fields. */
    const val SCHEMA_VERSION = "1"

    const val KEY_ACTIVATION_IDENTITY_SCHEMA_VERSION = "v2_distributed_activation_identity_schema_version"

    // ── Wire key constants ─────────────────────────────────────────────────────

    /**
     * Wire key for the V2 distributed execution dispatch plan identifier carried in
     * [ReconciliationSignal] task lifecycle signals.
     *
     * Aliases [ReconciliationSignal.KEY_DISPATCH_PLAN_ID].  V2 should prefer this constant
     * over the bare string `"reconciliation_dispatch_plan_id"` to remain aligned with the
     * canonical contract definition.
     */
    const val KEY_DISPATCH_PLAN_ID_IN_RECONCILIATION = ReconciliationSignal.KEY_DISPATCH_PLAN_ID

    /**
     * Payload wire key for the [ActivationIdentityClass.wireValue] that V2 can use to
     * quickly determine the correlation tier of an incoming reconciliation signal.
     */
    const val KEY_ACTIVATION_IDENTITY_CLASS = "v2_distributed_activation_identity_class"

    // ── Identity classification ────────────────────────────────────────────────

    /**
     * Enum classifying how much stable distributed activation identity a
     * [ReconciliationSignal] carries.
     *
     * V2 should use [ActivationIdentityClass.isFullyCorrelatable] to gate whether
     * the signal can be tied to the originating dispatch plan without inference.
     *
     * @property wireValue Stable lowercase wire string.
     * @property isFullyCorrelatable `true` only when dispatch_plan_id + task_id +
     *                               participant_id are all present; correlates to a specific
     *                               dispatch plan record without ambiguity.
     * @property requiresInference `true` when V2 must combine multiple fields to infer
     *                             the dispatch plan; less reliable under strict distributed
     *                             activation semantics.
     */
    enum class ActivationIdentityClass(
        val wireValue: String,
        val isFullyCorrelatable: Boolean,
        val requiresInference: Boolean
    ) {
        /**
         * All three stable identity fields are present:
         * `dispatch_plan_id` + `task_id` + `participant_id`.
         *
         * V2 can correlate this signal to a specific dispatch plan record without any inference.
         * This is the preferred identity class for V2's stricter distributed execution activation.
         */
        FULL_PLAN_IDENTITY(
            wireValue = "full_plan_identity",
            isFullyCorrelatable = true,
            requiresInference = false
        ),

        /**
         * `task_id` + `participant_id` are present but `dispatch_plan_id` is absent.
         *
         * Emitted by legacy senders that pre-date PR-48 dispatch plan metadata, or by
         * Android callers that did not receive a `dispatch_plan_id` in the inbound envelope.
         * V2 must combine `task_id` + `participant_id` to correlate; this is acceptable
         * under non-strict activation semantics but is ambiguous under strict enforcement.
         */
        TASK_AND_PARTICIPANT_IDENTITY(
            wireValue = "task_and_participant_identity",
            isFullyCorrelatable = false,
            requiresInference = true
        ),

        /**
         * Only `task_id` is present; `participant_id` and `dispatch_plan_id` are absent.
         *
         * V2 must infer the participant from session or transport context.  Weak; V2
         * should not trust this class for strict distributed activation correlation.
         */
        TASK_IDENTITY_ONLY(
            wireValue = "task_identity_only",
            isFullyCorrelatable = false,
            requiresInference = true
        ),

        /**
         * No stable task or plan identity is present.
         *
         * This class applies to PARTICIPANT_STATE and RUNTIME_TRUTH_SNAPSHOT signals
         * (which are not task-scoped) and to any malformed signal where both `task_id`
         * and `dispatch_plan_id` are absent.  V2 MUST NOT use such signals for dispatch
         * plan record correlation.
         */
        INSUFFICIENT_IDENTITY(
            wireValue = "insufficient_identity",
            isFullyCorrelatable = false,
            requiresInference = false
        );

        companion object {
            /** Parse [value] to an [ActivationIdentityClass]; returns null for unknown values. */
            fun fromWireValue(value: String?): ActivationIdentityClass? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values for schema validation. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Classification ─────────────────────────────────────────────────────────

    /**
     * Classifies the [ActivationIdentityClass] of a reconciliation signal based on
     * which identity fields are present.
     *
     * @param kind          The signal's [ReconciliationSignal.Kind].
     * @param taskId        The signal's `taskId` field; null for participant/snapshot signals.
     * @param participantId The signal's `participantId`.
     * @param dispatchPlanId The signal's `dispatchPlanId`; null for legacy senders.
     * @return The appropriate [ActivationIdentityClass] for V2 correlation routing.
     */
    fun classify(
        kind: ReconciliationSignal.Kind,
        taskId: String?,
        participantId: String,
        dispatchPlanId: String?
    ): ActivationIdentityClass {
        // Participant-state and runtime-truth-snapshot signals are not task-scoped;
        // they carry no task correlation identity by design.
        if (kind == ReconciliationSignal.Kind.PARTICIPANT_STATE ||
            kind == ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT
        ) {
            return ActivationIdentityClass.INSUFFICIENT_IDENTITY
        }
        val hasTask = !taskId.isNullOrBlank()
        val hasPlan = !dispatchPlanId.isNullOrBlank()
        val hasParticipant = participantId.isNotBlank()
        return when {
            hasPlan && hasTask && hasParticipant -> ActivationIdentityClass.FULL_PLAN_IDENTITY
            hasTask && hasParticipant -> ActivationIdentityClass.TASK_AND_PARTICIPANT_IDENTITY
            hasTask -> ActivationIdentityClass.TASK_IDENTITY_ONLY
            else -> ActivationIdentityClass.INSUFFICIENT_IDENTITY
        }
    }

    /**
     * Convenience overload: classifies directly from a [ReconciliationSignal].
     */
    fun classify(signal: ReconciliationSignal): ActivationIdentityClass =
        classify(
            kind = signal.kind,
            taskId = signal.taskId,
            participantId = signal.participantId,
            dispatchPlanId = signal.dispatchPlanId
        )

    /**
     * Produces a wire map entry for [kind] carrying the [ActivationIdentityClass.wireValue]
     * and the schema version.  Suitable for inclusion in a [ReconciliationSignal] payload.
     */
    fun toWireMap(
        kind: ReconciliationSignal.Kind,
        taskId: String?,
        participantId: String,
        dispatchPlanId: String?
    ): Map<String, Any?> {
        val identityClass = classify(kind, taskId, participantId, dispatchPlanId)
        return mapOf(
            KEY_ACTIVATION_IDENTITY_CLASS to identityClass.wireValue,
            KEY_ACTIVATION_IDENTITY_SCHEMA_VERSION to SCHEMA_VERSION
        )
    }

    // ── V2 alignment map ──────────────────────────────────────────────────────

    /**
     * Maps each [ActivationIdentityClass] to the V2 correlation path it implies.
     *
     * V2 should route incoming [ReconciliationSignal] signals to the appropriate
     * correlation path based on the [KEY_ACTIVATION_IDENTITY_CLASS] payload field.
     */
    val V2_DISTRIBUTED_ACTIVATION_ALIGNMENT_MAP: Map<String, String> = mapOf(
        ActivationIdentityClass.FULL_PLAN_IDENTITY.wireValue to
            "v2.dispatch_plan_tracker.correlate_by_plan_id",
        ActivationIdentityClass.TASK_AND_PARTICIPANT_IDENTITY.wireValue to
            "v2.dispatch_plan_tracker.correlate_by_task_and_participant",
        ActivationIdentityClass.TASK_IDENTITY_ONLY.wireValue to
            "v2.dispatch_plan_tracker.correlate_by_task_inferred_participant",
        ActivationIdentityClass.INSUFFICIENT_IDENTITY.wireValue to
            "v2.dispatch_plan_tracker.non_task_signal_do_not_correlate"
    )

    // ── Invariants ────────────────────────────────────────────────────────────

    /**
     * Behavioral invariants Android must uphold to remain a truthful upstream participant
     * in V2's stricter distributed execution activation path.
     *
     * These invariants are machine-verifiable and must not be relaxed without a corresponding
     * contract version increment and V2-side protocol update.
     */
    val DISTRIBUTED_ACTIVATION_INVARIANTS: List<String> = listOf(
        "INV-DA-01: dispatch_plan_id MUST be forwarded from the inbound dispatch envelope " +
            "into ReconciliationSignal.dispatchPlanId at task acceptance time when the sender " +
            "provides it. Android must not suppress or transform the value.",

        "INV-DA-02: dispatch_plan_id MUST be propagated from TASK_ACCEPTED through " +
            "TASK_RESULT / TASK_CANCELLED / TASK_FAILED for the same activation span. " +
            "It must not be reset or cleared between acceptance and the terminal signal.",

        "INV-DA-03: When dispatch_plan_id is absent (legacy sender), Android MUST emit " +
            "TASK_AND_PARTICIPANT_IDENTITY class signals, not FULL_PLAN_IDENTITY. " +
            "Android must not fabricate or synthesise a dispatch_plan_id.",

        "INV-DA-04: PARTICIPANT_STATE and RUNTIME_TRUTH_SNAPSHOT signals MUST be classified " +
            "as INSUFFICIENT_IDENTITY by the contract classifier. V2 MUST NOT use these signals " +
            "for dispatch plan record correlation.",

        "INV-DA-05: When dispatch_plan_id is present in a task lifecycle signal payload, " +
            "it MUST match ReconciliationSignal.dispatchPlanId on the same signal. " +
            "The two representations must not diverge.",

        "INV-DA-06: Android must not emit FULL_PLAN_IDENTITY class signals for PARTICIPANT_STATE " +
            "or RUNTIME_TRUTH_SNAPSHOT kinds regardless of whether a prior task left a non-null " +
            "dispatch_plan_id in local state."
    )
}
