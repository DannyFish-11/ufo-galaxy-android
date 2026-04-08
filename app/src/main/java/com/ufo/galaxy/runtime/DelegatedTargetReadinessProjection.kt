package com.ufo.galaxy.runtime

/**
 * **Host-facing delegated target readiness projection** (PR-20 / PR-24, post-#533 dual-repo
 * runtime unification master plan — Host-Facing Delegated Target Readiness Projection,
 * Android side).
 *
 * [DelegatedTargetReadinessProjection] is the authoritative, semantically stable output
 * envelope that lets the main-repository delegated target selection policy determine
 * whether this Android attached runtime is currently suitable to receive a new delegated
 * execution without inferring suitability from scattered fields.
 *
 * ## Why a dedicated readiness projection is needed
 *
 * [AttachedRuntimeHostSessionSnapshot] provides all nine session fields, but gives the
 * host no explicit, pre-computed suitability decision.  The host's selection policy would
 * have to combine [AttachedRuntimeHostSessionSnapshot.isReuseValid],
 * [AttachedRuntimeHostSessionSnapshot.attachmentState],
 * [AttachedRuntimeHostSessionSnapshot.invalidationReason], and
 * [AttachedRuntimeHostSessionSnapshot.posture] through implicit inference, which is fragile
 * in reconnect / invalidate / high-count / risky-posture scenarios.
 *
 * [DelegatedTargetReadinessProjection] resolves this by:
 *  1. Embedding the full nine-field [snapshot] for identity and provenance tracing.
 *  2. Adding a pre-computed [isSuitableTarget] boolean — the authoritative decision.
 *  3. Adding an [unsuitabilityReason] for diagnostics and policy explainability.
 *  4. (PR-24) Exposing a [selectionOutcome] canonical enum so downstream can act on
 *     SELECTED / REJECTED / FALLBACK semantics without ad-hoc field inference.
 *
 * ## Thirteen canonical fields
 *
 * | Field                    | Wire key                                   | Always present? |
 * |--------------------------|--------------------------------------------|-----------------|
 * | [sessionId]              | [KEY_SESSION_ID]                           | yes             |
 * | [deviceId]               | [KEY_DEVICE_ID]                            | yes             |
 * | [runtimeSessionId]       | [KEY_RUNTIME_SESSION_ID]                   | yes             |
 * | [attachmentState]        | [KEY_ATTACHMENT_STATE]                     | yes             |
 * | [isReuseValid]           | [KEY_IS_REUSE_VALID]                       | yes             |
 * | [delegatedExecutionCount]| [KEY_DELEGATED_EXECUTION_COUNT]            | yes             |
 * | [invalidationReason]     | [KEY_INVALIDATION_REASON]                  | conditional     |
 * | [posture]                | [KEY_POSTURE]                              | yes             |
 * | [isSuitableTarget]       | [KEY_IS_SUITABLE_TARGET]                   | yes             |
 * | [unsuitabilityReason]    | [KEY_UNSUITABILITY_REASON]                 | conditional     |
 * | [selectionOutcome]       | [KEY_SELECTION_OUTCOME]                    | yes             |
 * | [selectionOutcomeReason] | [KEY_SELECTION_OUTCOME_REASON]             | conditional     |
 *
 * ## Suitability semantics
 *
 * [isSuitableTarget] is `true` if and only if **all** of the following hold:
 *  - [attachmentState] == `"attached"` (session is live).
 *  - [isReuseValid] == `true` (host may direct additional tasks here).
 *  - [invalidationReason] == `null` (no invalidation event has occurred).
 *  - [posture] == [SourceRuntimePosture.JOIN_RUNTIME] (device participates as executor).
 *
 * When [isSuitableTarget] is `false`, [unsuitabilityReason] provides a stable,
 * machine-readable string explaining why:
 *  - [UNSUITABILITY_INVALIDATED]  — session was closed via INVALIDATION.
 *  - [UNSUITABILITY_NOT_ATTACHED] — session is DETACHING or DETACHED (non-invalidation).
 *
 * ## Selection outcome semantics (PR-24)
 *
 * [selectionOutcome] canonicalises the target's role in multi-target selection:
 *  - [TargetSelectionOutcome.SELECTED]  — suitable, primary-role candidate.
 *  - [TargetSelectionOutcome.FALLBACK]  — suitable, but non-primary-role (SECONDARY /
 *    SATELLITE); preferred over REJECTED but secondary to a SELECTED candidate.
 *  - [TargetSelectionOutcome.REJECTED]  — not suitable; must not receive new tasks.
 *
 * [selectionOutcomeReason] is a stable machine-readable string that accompanies non-null
 * outcomes:
 *  - `null` for [TargetSelectionOutcome.SELECTED].
 *  - [FALLBACK_REASON_NON_PRIMARY_ROLE] for [TargetSelectionOutcome.FALLBACK].
 *  - The [unsuitabilityReason] value for [TargetSelectionOutcome.REJECTED].
 *
 * ## State-transition semantics
 *
 * | Lifecycle event         | [isSuitableTarget] | [selectionOutcome]             | [selectionOutcomeReason]          |
 * |-------------------------|--------------------|--------------------------------|-----------------------------------|
 * | attach (primary role)   | `true`             | [TargetSelectionOutcome.SELECTED]  | `null`                            |
 * | attach (secondary role) | `true`             | [TargetSelectionOutcome.FALLBACK]  | [FALLBACK_REASON_NON_PRIMARY_ROLE]|
 * | detach                  | `false`            | [TargetSelectionOutcome.REJECTED]  | [UNSUITABILITY_NOT_ATTACHED]      |
 * | reconnect               | `true`             | [TargetSelectionOutcome.SELECTED]  | `null`                            |
 * | invalidate              | `false`            | [TargetSelectionOutcome.REJECTED]  | [UNSUITABILITY_INVALIDATED]       |
 *
 * ## Obtaining an instance
 *
 * Use [RuntimeController.currentDelegatedTargetReadinessProjection] for a point-in-time
 * query, or observe [RuntimeController.targetReadinessProjection] as a [kotlinx.coroutines.flow.StateFlow]
 * for reactive consumption.  Do not construct directly.  In tests, use [from] with a
 * manually constructed [AttachedRuntimeHostSessionSnapshot].
 *
 * @property snapshot                 Full nine-field [AttachedRuntimeHostSessionSnapshot]
 *                                    from which this projection is derived.
 * @property isSuitableTarget         Pre-computed suitability decision.  `true` iff the
 *                                    attached runtime is currently eligible to receive a
 *                                    new delegated execution.
 * @property unsuitabilityReason      Non-`null` only when [isSuitableTarget] is `false`.
 *                                    Stable machine-readable string for policy explainability.
 */
data class DelegatedTargetReadinessProjection(
    val snapshot: AttachedRuntimeHostSessionSnapshot,
    val isSuitableTarget: Boolean,
    val unsuitabilityReason: String?
) {

    // ── Convenience accessors (delegate to snapshot) ──────────────────────────

    /** Delegates to [AttachedRuntimeHostSessionSnapshot.sessionId]. */
    val sessionId: String get() = snapshot.sessionId

    /** Delegates to [AttachedRuntimeHostSessionSnapshot.deviceId]. */
    val deviceId: String get() = snapshot.deviceId

    /** Delegates to [AttachedRuntimeHostSessionSnapshot.runtimeSessionId]. */
    val runtimeSessionId: String get() = snapshot.runtimeSessionId

    /** Delegates to [AttachedRuntimeHostSessionSnapshot.attachmentState]. */
    val attachmentState: String get() = snapshot.attachmentState

    /** Delegates to [AttachedRuntimeHostSessionSnapshot.isReuseValid]. */
    val isReuseValid: Boolean get() = snapshot.isReuseValid

    /** Delegates to [AttachedRuntimeHostSessionSnapshot.delegatedExecutionCount]. */
    val delegatedExecutionCount: Int get() = snapshot.delegatedExecutionCount

    /** Delegates to [AttachedRuntimeHostSessionSnapshot.invalidationReason]. */
    val invalidationReason: String? get() = snapshot.invalidationReason

    /** Delegates to [AttachedRuntimeHostSessionSnapshot.posture]. */
    val posture: String get() = snapshot.posture

    // ── Selection outcome (PR-24) ─────────────────────────────────────────────

    /**
     * Canonical selection classification for this target (PR-24).
     *
     * Derived purely from [isSuitableTarget] and [snapshot.hostRole]:
     *  - [TargetSelectionOutcome.REJECTED] — not suitable; downstream must not assign
     *    new tasks to this target.
     *  - [TargetSelectionOutcome.FALLBACK]  — suitable but non-primary (SECONDARY /
     *    SATELLITE role); preferred over REJECTED, secondary to SELECTED.
     *  - [TargetSelectionOutcome.SELECTED]  — suitable, primary-role candidate.
     *
     * This property is the authoritative, stable surface that lets the main-repository
     * selection policy make a decision without re-combining scattered fields.
     */
    val selectionOutcome: TargetSelectionOutcome get() = when {
        !isSuitableTarget -> TargetSelectionOutcome.REJECTED
        snapshot.hostRole == RuntimeHostDescriptor.FormationRole.SECONDARY.wireValue ||
            snapshot.hostRole == RuntimeHostDescriptor.FormationRole.SATELLITE.wireValue ->
            TargetSelectionOutcome.FALLBACK
        else -> TargetSelectionOutcome.SELECTED
    }

    /**
     * Stable machine-readable reason string accompanying [selectionOutcome] (PR-24).
     *
     *  - `null` when [selectionOutcome] is [TargetSelectionOutcome.SELECTED].
     *  - [FALLBACK_REASON_NON_PRIMARY_ROLE] when [selectionOutcome] is
     *    [TargetSelectionOutcome.FALLBACK].
     *  - The [unsuitabilityReason] value when [selectionOutcome] is
     *    [TargetSelectionOutcome.REJECTED].
     */
    val selectionOutcomeReason: String? get() = when (selectionOutcome) {
        TargetSelectionOutcome.SELECTED -> null
        TargetSelectionOutcome.FALLBACK -> FALLBACK_REASON_NON_PRIMARY_ROLE
        TargetSelectionOutcome.REJECTED -> unsuitabilityReason
    }

    // ── Serialisation ─────────────────────────────────────────────────────────

    /**
     * Builds the canonical wire map for this readiness projection.
     *
     * All nine snapshot fields are included, forwarded from
     * [AttachedRuntimeHostSessionSnapshot.toMap].  Additionally:
     *  - [KEY_IS_SUITABLE_TARGET] is always present.
     *  - [KEY_UNSUITABILITY_REASON] is included **only** when [unsuitabilityReason]
     *    is non-`null` (i.e. when [isSuitableTarget] is `false`).
     *  - [KEY_SELECTION_OUTCOME] is always present (PR-24).
     *  - [KEY_SELECTION_OUTCOME_REASON] is included **only** when [selectionOutcomeReason]
     *    is non-`null` (i.e. when [selectionOutcome] is not [TargetSelectionOutcome.SELECTED]).
     *
     * @return An immutable [Map] suitable for transmission to the main-repo
     *         delegated target selection policy.
     */
    fun toMap(): Map<String, Any> = buildMap {
        putAll(snapshot.toMap())
        put(KEY_IS_SUITABLE_TARGET, isSuitableTarget)
        unsuitabilityReason?.let { put(KEY_UNSUITABILITY_REASON, it) }
        put(KEY_SELECTION_OUTCOME, selectionOutcome.wireValue)
        selectionOutcomeReason?.let { put(KEY_SELECTION_OUTCOME_REASON, it) }
    }

    // ── Companion / factory ───────────────────────────────────────────────────

    companion object {

        // ── Wire key constants ────────────────────────────────────────────────

        /** Wire key for [sessionId] (forwarded from snapshot). */
        const val KEY_SESSION_ID = AttachedRuntimeHostSessionSnapshot.KEY_SESSION_ID

        /** Wire key for [deviceId] (forwarded from snapshot). */
        const val KEY_DEVICE_ID = AttachedRuntimeHostSessionSnapshot.KEY_DEVICE_ID

        /** Wire key for [runtimeSessionId] (forwarded from snapshot). */
        const val KEY_RUNTIME_SESSION_ID = AttachedRuntimeHostSessionSnapshot.KEY_RUNTIME_SESSION_ID

        /** Wire key for [attachmentState] (forwarded from snapshot). */
        const val KEY_ATTACHMENT_STATE = AttachedRuntimeHostSessionSnapshot.KEY_ATTACHMENT_STATE

        /** Wire key for [isReuseValid] (forwarded from snapshot). */
        const val KEY_IS_REUSE_VALID = AttachedRuntimeHostSessionSnapshot.KEY_IS_REUSE_VALID

        /** Wire key for [delegatedExecutionCount] (forwarded from snapshot). */
        const val KEY_DELEGATED_EXECUTION_COUNT = AttachedRuntimeHostSessionSnapshot.KEY_DELEGATED_EXECUTION_COUNT

        /**
         * Wire key for [invalidationReason] (forwarded from snapshot).
         * Present in [toMap] output **only** when [invalidationReason] is non-`null`.
         */
        const val KEY_INVALIDATION_REASON = AttachedRuntimeHostSessionSnapshot.KEY_INVALIDATION_REASON

        /** Wire key for [posture] (forwarded from snapshot). */
        const val KEY_POSTURE = AttachedRuntimeHostSessionSnapshot.KEY_POSTURE

        /**
         * Wire key for [isSuitableTarget].
         * Always present in [toMap] output.
         */
        const val KEY_IS_SUITABLE_TARGET = "readiness_is_suitable_target"

        /**
         * Wire key for [unsuitabilityReason].
         * Present in [toMap] output **only** when [isSuitableTarget] is `false`.
         */
        const val KEY_UNSUITABILITY_REASON = "readiness_unsuitability_reason"

        /**
         * Wire key for [selectionOutcome] (PR-24).
         * Always present in [toMap] output.
         */
        const val KEY_SELECTION_OUTCOME = "readiness_selection_outcome"

        /**
         * Wire key for [selectionOutcomeReason] (PR-24).
         * Present in [toMap] output **only** when [selectionOutcomeReason] is non-`null`
         * (i.e. when [selectionOutcome] is [TargetSelectionOutcome.FALLBACK] or
         * [TargetSelectionOutcome.REJECTED]).
         */
        const val KEY_SELECTION_OUTCOME_REASON = "readiness_selection_outcome_reason"

        // ── Unsuitability reason constants ────────────────────────────────────

        /**
         * [unsuitabilityReason] value when the session was closed via
         * [AttachedRuntimeSession.DetachCause.INVALIDATION].
         */
        const val UNSUITABILITY_INVALIDATED = "invalidated"

        /**
         * [unsuitabilityReason] value when the session is not attached (DETACHING or
         * DETACHED) for a non-invalidation cause.
         */
        const val UNSUITABILITY_NOT_ATTACHED = "not_attached"

        // ── Selection outcome reason constants (PR-24) ────────────────────────

        /**
         * [selectionOutcomeReason] value when [selectionOutcome] is
         * [TargetSelectionOutcome.FALLBACK] because the host's [snapshot.hostRole] is
         * [RuntimeHostDescriptor.FormationRole.SECONDARY] or
         * [RuntimeHostDescriptor.FormationRole.SATELLITE].
         */
        const val FALLBACK_REASON_NON_PRIMARY_ROLE = "non_primary_role"

        // ── Always-present keys ───────────────────────────────────────────────

        /**
         * The keys that are **always** present in [toMap] output, regardless of
         * session state.  [KEY_INVALIDATION_REASON], [KEY_UNSUITABILITY_REASON], and
         * [KEY_SELECTION_OUTCOME_REASON] are excluded because they are conditional.
         */
        val ALWAYS_PRESENT_KEYS: Set<String> = buildSet {
            addAll(AttachedRuntimeHostSessionSnapshot.ALWAYS_PRESENT_KEYS)
            add(KEY_IS_SUITABLE_TARGET)
            add(KEY_SELECTION_OUTCOME)
        }

        // ── Factory ───────────────────────────────────────────────────────────

        /**
         * Builds a [DelegatedTargetReadinessProjection] from an existing
         * [AttachedRuntimeHostSessionSnapshot].
         *
         * **Suitability rules applied here:**
         *  - [isSuitableTarget] is `true` iff [AttachedRuntimeHostSessionSnapshot.isReuseValid]
         *    is `true`, [AttachedRuntimeHostSessionSnapshot.attachmentState] is `"attached"`,
         *    [AttachedRuntimeHostSessionSnapshot.invalidationReason] is `null`, and
         *    [AttachedRuntimeHostSessionSnapshot.posture] is [SourceRuntimePosture.JOIN_RUNTIME].
         *  - [unsuitabilityReason] is [UNSUITABILITY_INVALIDATED] when
         *    [AttachedRuntimeHostSessionSnapshot.invalidationReason] is non-`null`.
         *  - [unsuitabilityReason] is [UNSUITABILITY_NOT_ATTACHED] when the session is not
         *    attached and not invalidated.
         *  - [unsuitabilityReason] is `null` when [isSuitableTarget] is `true`.
         *
         * **Selection outcome** ([selectionOutcome]) and reason ([selectionOutcomeReason])
         * are computed properties on the returned instance; they do not require additional
         * factory parameters (PR-24).
         *
         * @param snapshot The [AttachedRuntimeHostSessionSnapshot] to evaluate.
         * @return A fully populated [DelegatedTargetReadinessProjection].
         */
        fun from(snapshot: AttachedRuntimeHostSessionSnapshot): DelegatedTargetReadinessProjection {
            val isSuitable = snapshot.isReuseValid &&
                snapshot.attachmentState == AttachedRuntimeSession.State.ATTACHED.wireValue &&
                snapshot.invalidationReason == null &&
                snapshot.posture == SourceRuntimePosture.JOIN_RUNTIME
            val unsuitabilityReason = when {
                isSuitable -> null
                snapshot.invalidationReason != null -> UNSUITABILITY_INVALIDATED
                else -> UNSUITABILITY_NOT_ATTACHED
            }
            return DelegatedTargetReadinessProjection(
                snapshot = snapshot,
                isSuitableTarget = isSuitable,
                unsuitabilityReason = unsuitabilityReason
            )
        }
    }
}

/**
 * Canonical selection outcome for a delegated target (PR-24 — selection truth consolidation).
 *
 * [TargetSelectionOutcome] is exposed as a stable, machine-readable enum on
 * [DelegatedTargetReadinessProjection.selectionOutcome] so that the main-repository
 * delegated target selection policy can act on a pre-classified verdict rather than
 * inferring it from scattered readiness fields.
 *
 * | Value      | Wire value   | Meaning                                                  |
 * |------------|--------------|----------------------------------------------------------|
 * | [SELECTED] | `"selected"` | Suitable, primary-role target; preferred for assignment. |
 * | [FALLBACK] | `"fallback"` | Suitable, non-primary-role target; secondary preference. |
 * | [REJECTED] | `"rejected"` | Not suitable; must not receive new delegated executions. |
 */
enum class TargetSelectionOutcome(
    /** Stable wire value for serialisation; used as the value for
     *  [DelegatedTargetReadinessProjection.KEY_SELECTION_OUTCOME] in [DelegatedTargetReadinessProjection.toMap]. */
    val wireValue: String
) {
    /**
     * The target is a suitable, primary-role candidate.
     *
     * This outcome is assigned when [DelegatedTargetReadinessProjection.isSuitableTarget]
     * is `true` and the host's formation role is [RuntimeHostDescriptor.FormationRole.PRIMARY],
     * [RuntimeHostDescriptor.FormationRole.DEFAULT], or any other role that is not explicitly
     * secondary/tertiary.
     */
    SELECTED("selected"),

    /**
     * The target is suitable, but carries a lower selection priority than a [SELECTED] target.
     *
     * This outcome is assigned when [DelegatedTargetReadinessProjection.isSuitableTarget]
     * is `true` and the host's formation role is [RuntimeHostDescriptor.FormationRole.SECONDARY]
     * or [RuntimeHostDescriptor.FormationRole.SATELLITE].  It prevents multi-target selection
     * from being driven solely by first-active or explicit-target heuristics — secondary
     * devices are always identifiable as FALLBACK regardless of attachment order.
     */
    FALLBACK("fallback"),

    /**
     * The target is not suitable and must not receive new delegated executions.
     *
     * This outcome is assigned when [DelegatedTargetReadinessProjection.isSuitableTarget]
     * is `false`.  The accompanying [DelegatedTargetReadinessProjection.selectionOutcomeReason]
     * echoes [DelegatedTargetReadinessProjection.unsuitabilityReason] for diagnostics.
     */
    REJECTED("rejected"),
}
