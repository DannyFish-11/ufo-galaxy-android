package com.ufo.galaxy.runtime

/**
 * PR-123 — Android V2 Distributed Activation Compatibility Contract.
 *
 * Classifies Android task activation identity relative to the V2 distributed execution
 * activation path so each [ReconciliationSignal] task signal can carry the strongest
 * available activation correlation anchor when V2 enforces stricter distributed semantics.
 *
 * ## Problem addressed
 *
 * V2's stricter distributed execution activation requires Android task signals to carry
 * a [ReconciliationSignal.dispatchPlanId] when one exists, so V2 can verify that the
 * Android-side activation is correlated with a specific distributed dispatch plan.
 * Without this anchor:
 *  - V2 cannot distinguish task activations triggered by a concrete distributed dispatch
 *    plan from task activations that arrived through a legacy or session-only path.
 *  - V2's worker selection logic may over-trust signals that lack a plan anchor, treating
 *    ambiguous activations as if they were plan-bound.
 *  - Stricter worker dispatch enforcement silently fails because the plan identity gap
 *    is invisible to both Android and V2.
 *
 * This contract formalises activation identity strength as a machine-actionable
 * [ActivationIdentityClass] and provides the stable wire keys and alignment map so V2
 * can route task signals to the correct distributed handling path.
 *
 * ## Activation identity classes
 *
 * | [ActivationIdentityClass]      | Has dispatch_plan_id | Has task_id | V2 distributed handling          |
 * |-------------------------------|---------------------|-------------|----------------------------------|
 * | [ActivationIdentityClass.DISPATCH_PLAN_ANCHORED]  | yes | yes | Full plan correlation — strongest |
 * | [ActivationIdentityClass.TASK_IDENTITY_ONLY]      | no  | yes | Task-correlated, plan-unanchored  |
 * | [ActivationIdentityClass.SESSION_ANCHORED]        | no  | no  | Session-level only, ambiguous     |
 * | [ActivationIdentityClass.UNANCHORED]              | no  | no  | No stable identity                |
 *
 * ## Wire integration
 *
 * [KEY_ACTIVATION_IDENTITY_CLASS] and companion keys are embedded in:
 *  - [ReconciliationSignal] payload for all task-bearing signal kinds
 *    ([ReconciliationSignal.Kind.TASK_ACCEPTED], [ReconciliationSignal.Kind.TASK_RESULT],
 *    [ReconciliationSignal.Kind.TASK_CANCELLED], [ReconciliationSignal.Kind.TASK_FAILED]).
 *  - [ReconciliationSignal.dispatchPlanId] carries the raw plan ID for direct V2 lookup.
 *
 * ## Boundary constraint
 *
 * Android MUST NOT fabricate a dispatch plan ID it did not receive from V2.  A
 * [ActivationIdentityClass.TASK_IDENTITY_ONLY] classification is truthful when no plan ID
 * was supplied; Android must not promote it to [DISPATCH_PLAN_ANCHORED] by generating a
 * synthetic plan identity.  V2 owns the plan ID namespace.
 *
 * @see ReconciliationSignal
 * @see RuntimeController
 */
object AndroidV2DistributedActivationCompatibilityContract {

    /** Android PR that introduced this contract. */
    const val INTRODUCED_PR = 123

    /** Wire schema version for this contract's activation identity fields. */
    const val SCHEMA_VERSION = "1"

    // ── Wire key constants ─────────────────────────────────────────────────────

    /**
     * Wire key for the [ActivationIdentityClass.wireValue] in task-signal payloads.
     *
     * V2 MUST read this key to determine the strength of the Android-side activation anchor
     * before routing the signal into its distributed execution handling path.
     */
    const val KEY_ACTIVATION_IDENTITY_CLASS = "activation_identity_class"

    /**
     * Wire key indicating whether a concrete V2 dispatch plan ID is present.
     *
     * Value: `"true"` or `"false"`.  When `"true"`, [ReconciliationSignal.KEY_DISPATCH_PLAN_ID]
     * carries the stable plan identifier for V2 plan-level correlation.
     */
    const val KEY_HAS_DISPATCH_PLAN_ID = "activation_has_dispatch_plan_id"

    /**
     * Wire key indicating whether a stable task ID is present.
     *
     * Value: `"true"` or `"false"`.  When `"false"`, the signal is session-anchored or
     * unanchored and V2 MUST NOT treat it as a task-correlated activation.
     */
    const val KEY_HAS_TASK_ID = "activation_has_task_id"

    /**
     * Wire key for this contract's [SCHEMA_VERSION].
     */
    const val KEY_ACTIVATION_SCHEMA_VERSION = "activation_identity_schema_version"

    // ── ActivationIdentityClass ────────────────────────────────────────────────

    /**
     * Activation identity strength class for an Android task signal relative to V2's
     * distributed execution activation path.
     *
     * @property wireValue                     Stable lowercase string for wire transport.
     * @property isDispatchPlanAnchored        True when the signal carries a concrete V2 dispatch
     *                                         plan identifier, enabling full plan-level correlation.
     * @property isTaskIdentityPresent         True when the signal carries a stable task identifier,
     *                                         enabling at least task-level correlation.
     * @property isAmbiguousForDistributed     True when the activation identity is too weak for
     *                                         V2 to route confidently via distributed semantics.
     */
    enum class ActivationIdentityClass(
        val wireValue: String,
        val isDispatchPlanAnchored: Boolean,
        val isTaskIdentityPresent: Boolean,
        val isAmbiguousForDistributed: Boolean
    ) {

        /**
         * The task activation is anchored to a concrete V2 dispatch plan ID.
         *
         * This is the strongest activation identity class.  Android received a dispatch plan
         * identifier from V2 and has propagated it through every task signal for the duration
         * of the activation.  V2 can fully correlate this signal with its worker dispatch plan.
         */
        DISPATCH_PLAN_ANCHORED(
            wireValue = "dispatch_plan_anchored",
            isDispatchPlanAnchored = true,
            isTaskIdentityPresent = true,
            isAmbiguousForDistributed = false
        ),

        /**
         * The task activation carries a stable task ID but no dispatch plan ID.
         *
         * Android has task-level identity but the signal was not tagged with a V2 dispatch plan
         * identifier.  V2 can correlate at the task level but cannot verify plan-level binding.
         * This is weaker than [DISPATCH_PLAN_ANCHORED] and may be over-trusted once V2 enforces
         * stricter distributed semantics.
         */
        TASK_IDENTITY_ONLY(
            wireValue = "task_identity_only",
            isDispatchPlanAnchored = false,
            isTaskIdentityPresent = true,
            isAmbiguousForDistributed = false
        ),

        /**
         * The signal carries no task ID but has a session anchor.
         *
         * Session-anchored activations are ambiguous for distributed handling because V2 cannot
         * correlate the signal with a specific dispatched task.  V2 MUST NOT promote this signal
         * to task-level or plan-level correlation without explicit reconciliation.
         */
        SESSION_ANCHORED(
            wireValue = "session_anchored",
            isDispatchPlanAnchored = false,
            isTaskIdentityPresent = false,
            isAmbiguousForDistributed = true
        ),

        /**
         * The signal carries no stable task, session, or plan identity.
         *
         * Unanchored activations MUST NOT be used for distributed execution correlation.  V2
         * MUST treat any task-bearing signal classified as [UNANCHORED] as structurally invalid
         * and MUST NOT allow it to advance a distributed dispatch state.
         */
        UNANCHORED(
            wireValue = "unanchored",
            isDispatchPlanAnchored = false,
            isTaskIdentityPresent = false,
            isAmbiguousForDistributed = true
        );

        companion object {
            /** All stable wire values. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()

            fun fromWireValue(value: String?): ActivationIdentityClass? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── classify ──────────────────────────────────────────────────────────────

    /**
     * Returns the [ActivationIdentityClass] for the given task activation context.
     *
     * This is the canonical single-call entry point for classifying activation identity before
     * emitting any [ReconciliationSignal] task signal.  Callers MUST use this method rather
     * than constructing identity classifications ad hoc.
     *
     * Priority order (strongest wins):
     * 1. Both [taskId] and [dispatchPlanId] non-null → [ActivationIdentityClass.DISPATCH_PLAN_ANCHORED]
     * 2. [taskId] non-null, [dispatchPlanId] null → [ActivationIdentityClass.TASK_IDENTITY_ONLY]
     * 3. [taskId] null but [sessionId] non-null → [ActivationIdentityClass.SESSION_ANCHORED]
     * 4. All null → [ActivationIdentityClass.UNANCHORED]
     *
     * @param taskId        Task identifier from the originating dispatch envelope; null if absent.
     * @param dispatchPlanId V2-issued dispatch plan identifier; null if not provided.
     * @param sessionId     Durable session identifier; null if not available.
     */
    fun classify(
        taskId: String?,
        dispatchPlanId: String?,
        sessionId: String? = null
    ): ActivationIdentityClass = when {
        !taskId.isNullOrBlank() && !dispatchPlanId.isNullOrBlank() ->
            ActivationIdentityClass.DISPATCH_PLAN_ANCHORED
        !taskId.isNullOrBlank() ->
            ActivationIdentityClass.TASK_IDENTITY_ONLY
        !sessionId.isNullOrBlank() ->
            ActivationIdentityClass.SESSION_ANCHORED
        else ->
            ActivationIdentityClass.UNANCHORED
    }

    // ── toWireMap ──────────────────────────────────────────────────────────────

    /**
     * Produces the wire map for embedding this contract's activation identity fields in
     * any [ReconciliationSignal] task payload.
     *
     * All values are strings so the map is suitable for embedding in any JSON payload.
     *
     * @param activationClass The [ActivationIdentityClass] returned by [classify].
     * @param dispatchPlanId  The raw dispatch plan identifier; null if absent.
     * @param taskId          The raw task identifier; null if absent.
     */
    fun toWireMap(
        activationClass: ActivationIdentityClass,
        dispatchPlanId: String? = null,
        taskId: String? = null
    ): Map<String, String> = buildMap {
        put(KEY_ACTIVATION_IDENTITY_CLASS, activationClass.wireValue)
        put(KEY_HAS_DISPATCH_PLAN_ID, activationClass.isDispatchPlanAnchored.toString())
        put(KEY_HAS_TASK_ID, activationClass.isTaskIdentityPresent.toString())
        put(KEY_ACTIVATION_SCHEMA_VERSION, SCHEMA_VERSION)
    }

    // ── V2 alignment map ───────────────────────────────────────────────────────

    /**
     * Maps each [ActivationIdentityClass] to the V2-side distributed handling expectation.
     *
     * V2 MUST read [KEY_ACTIVATION_IDENTITY_CLASS] from task signal payloads and route the
     * signal via the corresponding handling path to preserve distributed execution truthfulness.
     */
    val V2_DISTRIBUTED_ACTIVATION_ALIGNMENT_MAP: Map<String, String> = mapOf(
        ActivationIdentityClass.DISPATCH_PLAN_ANCHORED.wireValue to
            "v2_distributed: correlate via dispatch_plan_id; full worker-plan binding verification",
        ActivationIdentityClass.TASK_IDENTITY_ONLY.wireValue to
            "v2_distributed: correlate via task_id only; no plan-level binding; accept without plan check",
        ActivationIdentityClass.SESSION_ANCHORED.wireValue to
            "v2_distributed: session-level only; do not promote to task correlation; advisory use only",
        ActivationIdentityClass.UNANCHORED.wireValue to
            "v2_distributed: no stable identity; reject for distributed dispatch correlation"
    )

    // ── Invariants ─────────────────────────────────────────────────────────────

    /**
     * Machine-verifiable invariants for the distributed activation compatibility contract.
     *
     * All values must be `true`; any `false` indicates a regression.
     */
    val DISTRIBUTED_ACTIVATION_INVARIANTS: Map<String, Boolean> = mapOf(

        // INV-ACTIV-01: DISPATCH_PLAN_ANCHORED is the only class with isDispatchPlanAnchored=true
        "only_dispatch_plan_anchored_has_plan_anchor" to
            (ActivationIdentityClass.entries.count { it.isDispatchPlanAnchored } == 1 &&
                ActivationIdentityClass.DISPATCH_PLAN_ANCHORED.isDispatchPlanAnchored),

        // INV-ACTIV-02: SESSION_ANCHORED and UNANCHORED are the only ambiguous classes
        "only_session_anchored_and_unanchored_are_ambiguous" to
            (ActivationIdentityClass.entries
                .filter { it.isAmbiguousForDistributed }
                .map { it.wireValue }
                .toSet() == setOf(
                ActivationIdentityClass.SESSION_ANCHORED.wireValue,
                ActivationIdentityClass.UNANCHORED.wireValue
            )),

        // INV-ACTIV-03: classify with both taskId and dispatchPlanId yields DISPATCH_PLAN_ANCHORED
        "classify_with_plan_id_yields_dispatch_plan_anchored" to
            (classify(
                taskId = "t-123",
                dispatchPlanId = "plan-abc",
                sessionId = "s-xyz"
            ) == ActivationIdentityClass.DISPATCH_PLAN_ANCHORED),

        // INV-ACTIV-04: classify with taskId only yields TASK_IDENTITY_ONLY
        "classify_with_task_id_only_yields_task_identity_only" to
            (classify(
                taskId = "t-123",
                dispatchPlanId = null,
                sessionId = "s-xyz"
            ) == ActivationIdentityClass.TASK_IDENTITY_ONLY),

        // INV-ACTIV-05: classify with no taskId yields SESSION_ANCHORED when sessionId present
        "classify_with_session_only_yields_session_anchored" to
            (classify(
                taskId = null,
                dispatchPlanId = null,
                sessionId = "s-xyz"
            ) == ActivationIdentityClass.SESSION_ANCHORED),

        // INV-ACTIV-06: V2_DISTRIBUTED_ACTIVATION_ALIGNMENT_MAP covers all ActivationIdentityClass values
        "alignment_map_covers_all_activation_identity_classes" to
            (ActivationIdentityClass.entries
                .all { V2_DISTRIBUTED_ACTIVATION_ALIGNMENT_MAP.containsKey(it.wireValue) })
    )
}
