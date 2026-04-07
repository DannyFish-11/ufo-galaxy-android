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
 *  2. Adding a pre-computed [isSuitableTarget] boolean — the authoritative suitability decision.
 *  3. Adding an [unsuitabilityReason] for diagnostics and policy explainability.
 *  4. Adding a [selectionOutcome] — the canonical three-way selection-truth outcome that
 *     downstream multi-target selection can consume directly without further inference (PR-24).
 *
 * ## Twelve canonical fields
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
 * [selectionOutcome] maps the suitability/readiness truth to a canonical three-way outcome
 * that downstream multi-target selection can consume without re-inferring from individual
 * fields.  This eliminates implicit first-active or explicit-target-only assumptions:
 *  - [SelectionOutcome.SELECTED]  — target is currently suitable; may receive delegated execution.
 *  - [SelectionOutcome.FALLBACK]  — target is temporarily unavailable (e.g. disconnected) but
 *    is not permanently excluded; eligible for fallback use if the primary target fails.
 *  - [SelectionOutcome.REJECTED]  — target session was permanently invalidated; must not be
 *    selected or used as a fallback.
 *
 * ## State-transition semantics
 *
 * | Lifecycle event | [isSuitableTarget] | [unsuitabilityReason]          | [selectionOutcome]          |
 * |-----------------|--------------------|---------------------------------|-----------------------------|
 * | attach          | `true`             | `null`                         | [SelectionOutcome.SELECTED] |
 * | detach          | `false`            | [UNSUITABILITY_NOT_ATTACHED]   | [SelectionOutcome.FALLBACK] |
 * | reconnect       | `true`             | `null`                         | [SelectionOutcome.SELECTED] |
 * | invalidate      | `false`            | [UNSUITABILITY_INVALIDATED]    | [SelectionOutcome.REJECTED] |
 *
 * ## Obtaining an instance
 *
 * Use [RuntimeController.delegatedTargetReadinessProjection] for continuous observable updates,
 * or [RuntimeController.currentDelegatedTargetReadinessProjection] for a point-in-time query.
 * Do not construct directly.  In tests, use [from] with a manually constructed
 * [AttachedRuntimeHostSessionSnapshot].
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

    // ── Canonical selection-truth outcome (PR-24) ─────────────────────────────

    /**
     * Canonical three-way selection-truth outcome derived from [isSuitableTarget] and
     * [unsuitabilityReason] (PR-24 — stabilize host-facing readiness and target suitability
     * semantics).
     *
     * Downstream multi-target selection consumes this field directly to classify each
     * candidate target without re-inferring meaning from the underlying boolean and reason
     * string.  This is the primary mechanism by which multi-target situations are prevented
     * from collapsing to first-active or explicit-target-only assumptions.
     *
     * @see SelectionOutcome
     */
    val selectionOutcome: SelectionOutcome
        get() = when {
            isSuitableTarget -> SelectionOutcome.SELECTED
            unsuitabilityReason == UNSUITABILITY_INVALIDATED -> SelectionOutcome.REJECTED
            else -> SelectionOutcome.FALLBACK
        }

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

    // ── Serialisation ─────────────────────────────────────────────────────────

    /**
     * Builds the canonical wire map for this readiness projection.
     *
     * All nine snapshot fields are included, forwarded from
     * [AttachedRuntimeHostSessionSnapshot.toMap].  Additionally:
     *  - [KEY_IS_SUITABLE_TARGET] is always present.
     *  - [KEY_SELECTION_OUTCOME] is always present (wire value of [selectionOutcome]).
     *  - [KEY_UNSUITABILITY_REASON] is included **only** when [unsuitabilityReason]
     *    is non-`null` (i.e. when [isSuitableTarget] is `false`).
     *
     * @return An immutable [Map] suitable for transmission to the main-repo
     *         delegated target selection policy.
     */
    fun toMap(): Map<String, Any> = buildMap {
        putAll(snapshot.toMap())
        put(KEY_IS_SUITABLE_TARGET, isSuitableTarget)
        put(KEY_SELECTION_OUTCOME, selectionOutcome.wireValue)
        unsuitabilityReason?.let { put(KEY_UNSUITABILITY_REASON, it) }
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
         * Wire key for [selectionOutcome] ([SelectionOutcome.wireValue]).
         * Always present in [toMap] output (PR-24).
         */
        const val KEY_SELECTION_OUTCOME = "readiness_selection_outcome"

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

        // ── Always-present keys ───────────────────────────────────────────────

        /**
         * The keys that are **always** present in [toMap] output, regardless of
         * session state.  [KEY_INVALIDATION_REASON] and [KEY_UNSUITABILITY_REASON]
         * are excluded because they are conditional.
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
         *  - [selectionOutcome] is derived from [isSuitableTarget] and [unsuitabilityReason].
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

    // ── SelectionOutcome — canonical three-way selection-truth (PR-24) ────────

    /**
     * Canonical three-way selection-truth outcome for a delegated target candidate (PR-24).
     *
     * Downstream multi-target selection policy consumes [selectionOutcome] to classify each
     * candidate directly, preventing implicit first-active or explicit-target-only assumptions.
     *
     * @property wireValue Stable, machine-readable string emitted in [toMap] under
     *                     [KEY_SELECTION_OUTCOME].  Never changes between releases.
     */
    enum class SelectionOutcome(val wireValue: String) {
        /**
         * The target is currently attached, reuse-valid, and postured correctly.
         * It may receive a new delegated execution immediately.
         */
        SELECTED("selected"),

        /**
         * The target session was permanently invalidated (e.g. identity mismatch, auth
         * expiry).  It must not be selected and is not eligible for fallback use.
         */
        REJECTED("rejected"),

        /**
         * The target is temporarily not attached (e.g. transient disconnect, session
         * disabled), but has not been permanently invalidated.  It may be promoted to
         * [SELECTED] after reconnect, or used as a fallback if the primary target fails.
         */
        FALLBACK("fallback")
    }
}
