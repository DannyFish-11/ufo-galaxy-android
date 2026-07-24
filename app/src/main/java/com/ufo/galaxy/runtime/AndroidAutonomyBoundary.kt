package com.ufo.galaxy.runtime

/**
 * PR-78 (Android) — Android-side autonomy boundary declaration.
 *
 * [AndroidAutonomyBoundary] is the explicit, machine-verifiable declaration of
 * which operations Android may execute autonomously (without V2 involvement) in
 * local mode, and how those semantics change when V2 is present in cross-device mode.
 *
 * ## Motivation
 *
 * [AndroidAuthorityBoundaryClosure] (PR-76) declares what Android is *authorized* to
 * contribute — participant truth, execution signals, and continuity data.  It does NOT
 * address the orthogonal question of *autonomy*: when Android is running in local mode
 * (`cross_device_enabled=false`), what decisions can it make entirely on its own?  And
 * which of those decisions change semantics when V2 is present?
 *
 * Without an explicit autonomy boundary, the system risks mixing two fundamentally
 * different operating models:
 *  - "Unified agent" — all decisions flow through V2 even in local mode.
 *  - "Edge autonomous entity" — Android makes all decisions independently even when
 *    V2 is connected.
 *
 * The correct model is: Android is an **autonomous edge agent** in local mode, and a
 * **V2-governed participant** in cross-device mode.  These are not competing models;
 * they are the two defined operating states of the same system.
 *
 * ## Two autonomy modes
 *
 * | [AutonomyMode]         | Trigger condition                                   | V2 involvement                                |
 * |------------------------|-----------------------------------------------------|-----------------------------------------------|
 * | [AutonomyMode.LOCAL_AUTONOMOUS] | `cross_device_enabled=false` (default)     | None; Android is the sole decision-maker      |
 * | [AutonomyMode.V2_GOVERNED]      | WS connected, `cross_device_enabled=true`  | V2 governs dispatch, assignment, and result interpretation |
 *
 * ## Four local autonomy operations
 *
 * In local mode Android has full autonomy over four operations.  In V2-governed mode
 * only [LocalAutonomyOperation.LOCAL_GROUNDING] remains unconditionally Android-owned;
 * the other three operations reduce to contribution semantics.
 *
 * | [LocalAutonomyOperation]          | LOCAL_AUTONOMOUS | V2_GOVERNED                                      |
 * |-----------------------------------|:----------------:|--------------------------------------------------|
 * | [LOCAL_PLANNING]                  | ✓ Autonomous     | V2 owns initial decomposition; Android contributes per-step planning |
 * | [LOCAL_GROUNDING]                 | ✓ Autonomous     | ✓ Always Android-owned; V2 never grounds coordinates |
 * | [LOCAL_ADVICE]                    | ✓ Autonomous     | Advice emitted as execution_contribution; V2 may consume or discard |
 * | [LOCAL_EXECUTION]                 | ✓ Autonomous     | V2 governs dispatch/result; Android owns per-step execution |
 *
 * ## Relationship to authority boundary
 *
 * Autonomy (who decides independently) and authority (who owns final truth) are
 * orthogonal:
 *  - In LOCAL_AUTONOMOUS mode Android has full autonomy AND owns local truth.
 *  - In V2_GOVERNED mode Android retains execution autonomy per-step (authority
 *    boundaries from [AndroidAuthorityBoundaryClosure] still apply).
 *  - Grounding is always Android-owned in both authority and autonomy dimensions.
 *
 * @see AndroidAuthorityBoundaryClosure
 * @see ExecutionRouteTag
 * @see ParticipantRuntimeSemanticsBoundary
 * @see AndroidExecutionGovernanceContract
 */
object AndroidAutonomyBoundary {

    /** PR number that introduced this autonomy boundary declaration. */
    const val INTRODUCED_PR = 78

    // ── Autonomy modes ────────────────────────────────────────────────────────

    /**
     * The two mutually exclusive autonomy operating modes for Android.
     *
     * The active [AutonomyMode] determines which party is the decision-making authority
     * for planning, grounding, advice, and execution operations.
     *
     * @property wireValue   Stable lowercase wire tag for this mode.
     * @property displayName Short human-readable name.
     * @property description One-sentence description.
     * @property v2Required  Whether V2 participation is required in this mode.
     */
    enum class AutonomyMode(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val v2Required: Boolean
    ) {

        /**
         * Android is the sole decision-maker for all four [LocalAutonomyOperation]s.
         *
         * Activated when `cross_device_enabled = false` (the system default).  No V2
         * connection exists; Android MUST NOT block waiting for V2 approval.
         *
         * Corresponding [ExecutionRouteTag]: [ExecutionRouteTag.LOCAL].
         */
        LOCAL_AUTONOMOUS(
            wireValue = "local_autonomous",
            displayName = "Local Autonomous",
            description = "Android is the sole decision-maker; all four local autonomy " +
                "operations proceed without V2.",
            v2Required = false
        ),

        /**
         * V2 governs task assignment, dispatch-slot selection, and result interpretation.
         *
         * Activated when a WebSocket connection to the Galaxy Gateway is established and
         * `cross_device_enabled = true`.  Android still owns its four local operations
         * but their semantics reduce to contribution inputs as defined by
         * [AndroidAuthorityBoundaryClosure]:
         *  - [LOCAL_GROUNDING] is always Android-owned.
         *  - [LOCAL_PLANNING], [LOCAL_ADVICE], [LOCAL_EXECUTION] emit as
         *    [AndroidAuthorityBoundaryClosure.ContributionAuthority.EXECUTION_CONTRIBUTION].
         *
         * Corresponding [ExecutionRouteTag]s: [ExecutionRouteTag.CROSS_DEVICE],
         * [ExecutionRouteTag.DELEGATED].
         */
        V2_GOVERNED(
            wireValue = "v2_governed",
            displayName = "V2 Governed",
            description = "V2 governs task assignment and result interpretation; Android " +
                "contributes execution signals as per AndroidAuthorityBoundaryClosure.",
            v2Required = true
        );

        companion object {
            /** Returns the [AutonomyMode] with [value], or `null` if unknown. */
            fun fromWireValue(value: String): AutonomyMode? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values for this enumeration. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── Local autonomy operations ─────────────────────────────────────────────

    /**
     * The four operations that Android may execute with varying degrees of autonomy
     * depending on the active [AutonomyMode].
     *
     * @property wireValue             Stable lowercase wire tag.
     * @property displayName           Short human-readable name.
     * @property description           One-sentence description.
     * @property autonomousInLocalMode Whether Android has full autonomy in [AutonomyMode.LOCAL_AUTONOMOUS].
     * @property alwaysAndroidOwned    Whether this operation is Android-owned in both modes.
     * @property v2GovernedSemantics   How this operation's semantics change in [AutonomyMode.V2_GOVERNED].
     */
    enum class LocalAutonomyOperation(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val autonomousInLocalMode: Boolean,
        val alwaysAndroidOwned: Boolean,
        val v2GovernedSemantics: String
    ) {

        /**
         * Android decomposes goals into action sequences using the on-device unified VLM (MAI-UI-2B)
         * planner ([com.ufo.galaxy.loop.LocalPlanner]).
         *
         * **LOCAL_AUTONOMOUS**: Android plans independently; the plan is final and is not
         * submitted to V2.
         *
         * **V2_GOVERNED**: V2 owns the initial goal decomposition authority and task
         * assignment.  Android still runs per-step planning for the assigned work, but the
         * result is emitted as
         * [AndroidAuthorityBoundaryClosure.ContributionAuthority.EXECUTION_CONTRIBUTION].
         */
        LOCAL_PLANNING(
            wireValue = "local_planning",
            displayName = "Local Planning",
            description = "Android decomposes goals into action steps using the on-device " +
                "unified VLM (MAI-UI-2B) planner.",
            autonomousInLocalMode = true,
            alwaysAndroidOwned = false,
            v2GovernedSemantics = "V2 owns initial goal decomposition authority; Android " +
                "runs per-step planning for assigned work as execution_contribution."
        ),

        /**
         * Android resolves UI element screen coordinates using the on-device unified VLM
         * grounding model ([com.ufo.galaxy.loop.ExecutorBridge]).
         *
         * **Always Android-owned** — grounding is an on-device operation in both modes.
         * V2 never resolves screen coordinates; the on-device unified VLM is the sole grounding
         * authority for this device.
         */
        LOCAL_GROUNDING(
            wireValue = "local_grounding",
            displayName = "Local Grounding",
            description = "Android resolves UI element screen coordinates using the on-device " +
                "unified VLM (MAI-UI-2B) grounding model.",
            autonomousInLocalMode = true,
            alwaysAndroidOwned = true,
            v2GovernedSemantics = "Grounding is always Android-owned; V2 never grounds " +
                "device-screen coordinates."
        ),

        /**
         * Android surfaces recommendations, error explanations, and advisory information
         * to the user without requiring V2 pre-approval.
         *
         * **LOCAL_AUTONOMOUS**: Android issues advice freely; no V2 coordination required.
         *
         * **V2_GOVERNED**: Advisory signals must be emitted as
         * [AndroidAuthorityBoundaryClosure.ContributionAuthority.EXECUTION_CONTRIBUTION].
         * V2 may consume or discard advice signals at its discretion.
         *
         * Canonical surface: [com.ufo.galaxy.ui.viewmodel.UnifiedResultPresentation].
         */
        LOCAL_ADVICE(
            wireValue = "local_advice",
            displayName = "Local Advice",
            description = "Android surfaces recommendations and error explanations to the " +
                "user without V2 pre-approval.",
            autonomousInLocalMode = true,
            alwaysAndroidOwned = false,
            v2GovernedSemantics = "Advice signals emitted as execution_contribution; V2 may " +
                "consume or discard them."
        ),

        /**
         * Android dispatches accessibility actions via [com.ufo.galaxy.loop.AccessibilityActionExecutor]
         * and completes execution steps without per-step V2 authorization.
         *
         * **LOCAL_AUTONOMOUS**: Android executes each step autonomously; no per-step V2
         * authorization is needed.
         *
         * **V2_GOVERNED**: V2 governs pre-task dispatch authorization and post-task result
         * interpretation.  Android retains per-step execution autonomy; it does not request
         * V2 permission before each individual AccessibilityService action.
         */
        LOCAL_EXECUTION(
            wireValue = "local_execution",
            displayName = "Local Execution",
            description = "Android dispatches accessibility actions and completes execution " +
                "steps without per-step V2 authorization.",
            autonomousInLocalMode = true,
            alwaysAndroidOwned = false,
            v2GovernedSemantics = "V2 governs pre-task dispatch and post-task result " +
                "interpretation; Android owns per-step execution."
        );

        companion object {
            /** Returns the [LocalAutonomyOperation] with [value], or `null` if unknown. */
            fun fromWireValue(value: String): LocalAutonomyOperation? =
                entries.firstOrNull { it.wireValue == value }

            /** All stable wire values for this enumeration. */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()

            /**
             * The set of operations that are Android-owned regardless of [AutonomyMode].
             *
             * Only [LOCAL_GROUNDING] qualifies: V2 never resolves device-screen coordinates.
             */
            val ALWAYS_ANDROID_OWNED: Set<LocalAutonomyOperation> =
                entries.filter { it.alwaysAndroidOwned }.toSet()

            /**
             * The set of operations that are fully autonomous in [AutonomyMode.LOCAL_AUTONOMOUS].
             *
             * All four operations are autonomous in local mode.
             */
            val AUTONOMOUS_IN_LOCAL_MODE: Set<LocalAutonomyOperation> =
                entries.filter { it.autonomousInLocalMode }.toSet()
        }
    }

    // ── Autonomy boundary contract ────────────────────────────────────────────

    /**
     * Returns `true` if [operation] is fully autonomous under [mode].
     *
     * Decision rules:
     *  - [AutonomyMode.LOCAL_AUTONOMOUS]: all four operations are autonomous
     *    ([LocalAutonomyOperation.autonomousInLocalMode] is `true` for all entries).
     *  - [AutonomyMode.V2_GOVERNED]: only [LocalAutonomyOperation.LOCAL_GROUNDING] is
     *    unconditionally autonomous ([LocalAutonomyOperation.alwaysAndroidOwned] = `true`).
     *    The other three operations reduce to contribution semantics under V2 governance.
     */
    fun isAutonomous(operation: LocalAutonomyOperation, mode: AutonomyMode): Boolean =
        when (mode) {
            AutonomyMode.LOCAL_AUTONOMOUS -> operation.autonomousInLocalMode
            AutonomyMode.V2_GOVERNED -> operation.alwaysAndroidOwned
        }

    // ── Wire map ──────────────────────────────────────────────────────────────

    /**
     * Produces a stable machine-readable map of the current autonomy boundary contract.
     *
     * Suitable for structured telemetry, audit trails, and cross-repo alignment checks.
     */
    fun buildAutonomyWireMap(): Map<String, Any> = mapOf(
        "introduced_pr" to INTRODUCED_PR,
        "schema_version" to "1.0",
        "autonomy_modes" to AutonomyMode.ALL_WIRE_VALUES.toList().sorted(),
        "local_autonomy_operations" to LocalAutonomyOperation.ALL_WIRE_VALUES.toList().sorted(),
        "always_android_owned_operations" to
            LocalAutonomyOperation.ALWAYS_ANDROID_OWNED.map { it.wireValue }.sorted(),
        "all_operations_autonomous_in_local_mode" to
            (LocalAutonomyOperation.AUTONOMOUS_IN_LOCAL_MODE.size ==
                LocalAutonomyOperation.entries.size),
        "grounding_always_android_owned" to true,
        "local_mode_has_no_v2_dependency" to true,
        "v2_governed_mode_android_owns_per_step_execution" to true,
        "v2_governed_mode_v2_owns_dispatch_and_result_interpretation" to true
    )

    // ── Autonomy invariant assertions ─────────────────────────────────────────

    /**
     * Set of wire-value assertions that must hold for the autonomy boundary to be
     * correctly enforced.
     *
     * Each key names a mandatory property; the `true` value asserts it holds.
     * A cross-repo audit can verify all values are `true` to confirm closure.
     */
    val AUTONOMY_INVARIANTS: Map<String, Boolean> = mapOf(
        "local_mode_android_may_plan_without_v2" to true,
        "local_mode_android_may_ground_without_v2" to true,
        "local_mode_android_may_advise_without_v2" to true,
        "local_mode_android_may_execute_without_v2" to true,
        "grounding_is_always_android_owned_in_both_modes" to true,
        "v2_governed_mode_does_not_remove_android_per_step_execution_autonomy" to true,
        "v2_governed_mode_v2_owns_dispatch_slot_selection" to true,
        "v2_governed_mode_v2_owns_result_interpretation" to true,
        "autonomy_boundary_is_orthogonal_to_authority_boundary" to true
    )
}
