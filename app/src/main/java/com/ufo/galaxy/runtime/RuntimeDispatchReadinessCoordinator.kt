package com.ufo.galaxy.runtime

/**
 * PR-37 — Runtime dispatch readiness coordinator.
 *
 * Provides an explicit, authoritative resolution of dispatch eligibility by combining
 * [RuntimeController.RuntimeState], [AttachedRuntimeSession] state, and
 * [RolloutControlSnapshot] gating into a single, queryable result.
 *
 * ## Purpose
 *
 * Prior to this coordinator, the relationship between the three governing models was
 * implicit:
 *
 * - [CanonicalDispatchChain] described which execution paths exist and their rollout
 *   gates, but could not reason about runtime or session state at runtime.
 * - [CanonicalSessionAxis] described session identity and continuity but had no
 *   authority over whether dispatch was currently eligible.
 * - [RuntimeController] owned both runtime state and session state but had no single
 *   surface that combined them with dispatch chain eligibility into one queryable answer.
 *
 * [RuntimeDispatchReadinessCoordinator] fills that gap.  It is the canonical place to
 * ask: "Given the current runtime state, session state, and rollout flags, what
 * dispatch path should be taken — and why?"
 *
 * ## Design intent
 *
 * This coordinator is a **pure function** — it does not hold state and does not
 * observe any flows.  Callers supply the current values and receive a [DispatchReadiness]
 * result.  [RuntimeController] uses this in [RuntimeController.currentDispatchReadiness]
 * to expose an always-current readiness assessment.
 *
 * ## Contribution role (A4)
 *
 * [DispatchReadiness] is a **contribution input** to the center's dispatch-slot authority.
 * It reports Android's local eligibility conditions (runtime state, session attachment,
 * rollout flag) so that the center can incorporate them into its dispatch-slot selection
 * decision.  Android does not own the final dispatch readiness verdict — the center
 * decides which device(s) receive tasks and when dispatch is actually permitted.
 *
 * See [AndroidAuthorityBoundaryClosure.CenterAuthorityDomain.DISPATCH_READINESS_AUTHORITY]
 * and [AndroidAuthorityBoundaryClosure.DISPATCH_READINESS_CONTRIBUTION_ROLE].
 *
 * ## Relation to CanonicalDispatchChain
 *
 * [CanonicalDispatchChain.resolveEligiblePathsForState] uses this coordinator's output
 * to filter the dispatch path registry to paths that are actually eligible given the
 * runtime/session truth.
 *
 * ## Eligibility rules
 *
 * | Condition                             | Eligible? | Recommended path | Reason                   |
 * |---------------------------------------|-----------|------------------|--------------------------|
 * | Runtime NOT Active                    | NO        | LOCAL            | `runtime_not_active`     |
 * | Session NOT attached                  | NO        | LOCAL            | `session_not_attached`   |
 * | Cross-device rollout flag = false     | NO        | LOCAL            | `cross_device_gated_off` |
 * | All above pass                        | YES       | CANONICAL        | —                        |
 *
 * @see RuntimeController.currentDispatchReadiness
 * @see CanonicalDispatchChain.resolveEligiblePathsForState
 */
object RuntimeDispatchReadinessCoordinator {

    /**
     * The result of a [RuntimeDispatchReadinessCoordinator.resolve] call.
     *
     * Encodes whether cross-device dispatch is eligible and, if not, why.  Also carries
     * pre-computed boolean flags for the two primary eligibility dimensions so callers
     * do not need to repeat the evaluation logic.
     *
     * @property isEligible           `true` when all eligibility conditions are satisfied.
     * @property recommendedPathMode  The [DispatchPathMode] to use given current conditions.
     *                                [DispatchPathMode.CANONICAL] when eligible;
     *                                [DispatchPathMode.LOCAL] when not.
     * @property ineligibilityReason  Machine-readable reason when [isEligible]`=false`; `null`
     *                                when eligible.  Stable values: `"runtime_not_active"`,
     *                                `"session_not_attached"`, `"cross_device_gated_off"`.
     * @property sessionIsAttached    `true` when the supplied [AttachedRuntimeSession] is in
     *                                [AttachedRuntimeSession.State.ATTACHED].
     * @property runtimeIsActive      `true` when the supplied [RuntimeController.RuntimeState]
     *                                is [RuntimeController.RuntimeState.Active].
     */
    data class DispatchReadiness(
        val isEligible: Boolean,
        val recommendedPathMode: DispatchPathMode,
        val ineligibilityReason: String?,
        val sessionIsAttached: Boolean,
        val runtimeIsActive: Boolean
    ) {
        /**
         * Stable, machine-readable string representation suitable for structured log entries
         * and host-facing protocol fields.
         */
        val wireValue: String
            get() = if (isEligible) "eligible" else "ineligible:${ineligibilityReason ?: "unknown"}"
    }

    /**
     * Evaluates dispatch eligibility from the supplied runtime/session/rollout inputs.
     *
     * This is a pure function; it has no side effects and reads no global state.
     *
     * @param runtimeState    Current [RuntimeController.RuntimeState] from [RuntimeController.state].
     * @param attachedSession Current [AttachedRuntimeSession] from [RuntimeController.attachedSession].
     *                        May be `null` when no session has been opened yet.
     * @param rollout         Current [RolloutControlSnapshot] from [RuntimeController.rolloutControlSnapshot].
     * @return                A [DispatchReadiness] describing eligibility and the recommended path.
     */
    fun resolve(
        runtimeState: RuntimeController.RuntimeState,
        attachedSession: AttachedRuntimeSession?,
        rollout: RolloutControlSnapshot
    ): DispatchReadiness {
        val isActive = runtimeState is RuntimeController.RuntimeState.Active
        val isAttached = attachedSession?.isAttached == true
        val crossDeviceAllowed = rollout.crossDeviceAllowed

        return when {
            !isActive -> DispatchReadiness(
                isEligible           = false,
                recommendedPathMode  = DispatchPathMode.LOCAL,
                ineligibilityReason  = "runtime_not_active",
                sessionIsAttached    = isAttached,
                runtimeIsActive      = false
            )
            !isAttached -> DispatchReadiness(
                isEligible           = false,
                recommendedPathMode  = DispatchPathMode.LOCAL,
                ineligibilityReason  = "session_not_attached",
                sessionIsAttached    = false,
                runtimeIsActive      = true
            )
            !crossDeviceAllowed -> DispatchReadiness(
                isEligible           = false,
                recommendedPathMode  = DispatchPathMode.LOCAL,
                ineligibilityReason  = "cross_device_gated_off",
                sessionIsAttached    = true,
                runtimeIsActive      = true
            )
            else -> DispatchReadiness(
                isEligible           = true,
                recommendedPathMode  = DispatchPathMode.CANONICAL,
                ineligibilityReason  = null,
                sessionIsAttached    = true,
                runtimeIsActive      = true
            )
        }
    }

    // ── Allowed runtime state transition table ────────────────────────────────

    /**
     * Canonical allowed state transitions for [RuntimeController.RuntimeState].
     *
     * Keys are the prior state class; values are the set of valid next-state classes.
     * Any transition not present in this map is **unexpected** and indicates a potential
     * race condition or lifecycle ordering issue.
     *
     * Used by [RuntimeController.transitionState] to classify transitions as governed
     * or unexpected before emitting a [RuntimeLifecycleTransitionEvent].
     */
    val ALLOWED_TRANSITIONS: Map<Class<out RuntimeController.RuntimeState>, Set<Class<out RuntimeController.RuntimeState>>> =
        mapOf(
            RuntimeController.RuntimeState.Idle::class.java to setOf(
                RuntimeController.RuntimeState.Starting::class.java,
                RuntimeController.RuntimeState.LocalOnly::class.java
            ),
            RuntimeController.RuntimeState.Starting::class.java to setOf(
                RuntimeController.RuntimeState.Active::class.java,
                RuntimeController.RuntimeState.Failed::class.java,
                RuntimeController.RuntimeState.LocalOnly::class.java
            ),
            RuntimeController.RuntimeState.Active::class.java to setOf(
                RuntimeController.RuntimeState.LocalOnly::class.java,
                // Active → Failed is possible if a WS error fires after connection (rare but valid).
                RuntimeController.RuntimeState.Failed::class.java
            ),
            RuntimeController.RuntimeState.Failed::class.java to setOf(
                RuntimeController.RuntimeState.LocalOnly::class.java,
                RuntimeController.RuntimeState.Starting::class.java
            ),
            RuntimeController.RuntimeState.LocalOnly::class.java to setOf(
                RuntimeController.RuntimeState.Starting::class.java,
                RuntimeController.RuntimeState.Active::class.java
            )
        )

    /**
     * Returns `true` when transitioning from [from] to [to] is a governed (expected)
     * transition according to [ALLOWED_TRANSITIONS].
     */
    fun isGoverned(
        from: RuntimeController.RuntimeState,
        to: RuntimeController.RuntimeState
    ): Boolean {
        val allowed = ALLOWED_TRANSITIONS[from::class.java] ?: return false
        return to::class.java in allowed
    }

    // ── A4 authority boundary ─────────────────────────────────────────────────

    /**
     * Stable wire tag declaring that [DispatchReadiness] is a contribution input to the
     * center's dispatch-slot authority, not an Android-owned final dispatch verdict.
     *
     * Mirrors [AndroidAuthorityBoundaryClosure.DISPATCH_READINESS_CONTRIBUTION_ROLE].
     */
    const val CONTRIBUTION_ROLE =
        "dispatch_eligibility_contribution_input_for_center_dispatch_slot_authority"
}
