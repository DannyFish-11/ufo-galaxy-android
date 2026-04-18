package com.ufo.galaxy.runtime

/**
 * PR-40 — Android Media Transport and Task-Lifecycle Convergence.
 *
 * Defines the canonical integration model between Android-side media/session
 * transport conditions and task/runtime lifecycle semantics.  This bridge makes
 * the relationship between transport state and task-lifecycle behavior explicit and
 * machine-readable so that the Android runtime reacts predictably during attach,
 * detach, reconnect, degradation, and recovery scenarios.
 *
 * ## Problem addressed
 *
 * The Android runtime participates in a distributed multi-device execution model where
 * task lifecycle and media/session transport realities are tightly coupled.  However,
 * prior to PR-40 the mapping from transport conditions to concrete lifecycle adaptations
 * was implicit and scattered across independent handling paths.  This created
 * opportunities for:
 *
 * - **Ambiguous degradation response** — multiple components could independently
 *   decide what to do when transport quality deteriorates, leading to divergent
 *   runtime behavior.
 * - **Ownership drift** — transport-driven side paths could evolve independently of
 *   canonical runtime lifecycle decisions, reducing coherence.
 * - **Recovery uncertainty** — the transition path from a degraded or suspended state
 *   back to normal operation was not declared, making it difficult to verify that
 *   recovery completed correctly.
 *
 * ## Design intent
 *
 * [MediaTransportLifecycleBridge] is an **additive, compatibility-safe** governance
 * model.  It does not change any existing runtime behavior, wire contracts, or identifier
 * values.  It formalizes the transport→lifecycle adaptation mappings that were previously
 * implicit so that:
 *
 * - The canonical adaptation path is unambiguous for each transport condition.
 * - Ownership boundaries between transport adaptation and runtime lifecycle decisions
 *   are explicit.
 * - Recovery paths are declared alongside their corresponding degradation entries.
 * - Future extension can reference this model rather than reinventing the mapping.
 *
 * ## Transport condition model
 *
 * | [TransportCondition]                         | Meaning                                                                  |
 * |----------------------------------------------|--------------------------------------------------------------------------|
 * | [TransportCondition.STABLE]                  | Transport is fully operational; no adaptation needed.                    |
 * | [TransportCondition.DEGRADED]                | Transport quality has declined but the connection is still functional.   |
 * | [TransportCondition.INTERRUPTED]             | Transport is temporarily unavailable; reconnect in progress.             |
 * | [TransportCondition.SUSPENDED]               | Transport is administratively suspended; no automatic recovery expected. |
 *
 * ## Lifecycle adaptation model
 *
 * | [LifecycleAdaptation]                        | Meaning                                                                  |
 * |----------------------------------------------|--------------------------------------------------------------------------|
 * | [LifecycleAdaptation.NONE]                   | No adaptation required; runtime continues normally.                      |
 * | [LifecycleAdaptation.ADVISORY]               | Telemetry and surface-layer notification only; no execution change.      |
 * | [LifecycleAdaptation.SUSPEND_NEW_TASKS]      | Accept no new delegated tasks; in-flight tasks may complete.             |
 * | [LifecycleAdaptation.TERMINATE_ACTIVE_TASKS] | Terminate all active delegated tasks and enter recovery hold.            |
 *
 * ## Canonical adaptation path
 *
 * Each [TransportIntegrationEntry] in [integrationEntries] records:
 * - The transport condition that triggers the adaptation.
 * - The required lifecycle adaptation.
 * - The authority that owns the adaptation decision (transport layer or runtime layer).
 * - The canonical recovery path back to [TransportCondition.STABLE].
 *
 * The canonical adaptation path keeps transport-driven decisions from independently
 * evolving away from [RuntimeController]-governed lifecycle semantics:
 *
 * ```
 * Transport condition change detected
 *   → MediaTransportLifecycleBridge.integrationEntries lookup
 *   → LifecycleAdaptation applied via RuntimeController (sole lifecycle authority)
 *     → Recovery: TransportContinuityAnchor.anchorFor(RECOVERY)
 *       → RuntimeController session reopen / state transition
 * ```
 *
 * ## Relationship to other governance objects
 *
 * - [RuntimeController] — the **sole lifecycle authority**; all adaptation decisions
 *   declared here must be executed through [RuntimeController]'s lifecycle methods.
 * - [TransportContinuityAnchor] — companion PR-40 object that governs transport
 *   continuity semantics during specific lifecycle events (ATTACH, DETACH, RECONNECT,
 *   DEGRADATION, RECOVERY).  [MediaTransportLifecycleBridge] governs the adaptation
 *   logic; [TransportContinuityAnchor] governs the session continuity policy.
 * - [CanonicalDispatchChain] — describes the execution paths; this bridge declares
 *   which transport conditions suspend or terminate dispatch on those paths.
 * - [ReconnectRecoveryState] — carries user-facing recovery phase (RECOVERING /
 *   RECOVERED / FAILED); [TransportCondition.INTERRUPTED] maps to [ReconnectRecoveryState.RECOVERING].
 * - [StabilizationBaseline] — PR-40 entries are registered in [StabilizationBaseline.entries].
 *
 * @see TransportContinuityAnchor
 * @see RuntimeController
 * @see ReconnectRecoveryState
 */
object MediaTransportLifecycleBridge {

    // ── Transport condition ───────────────────────────────────────────────────

    /**
     * Observable condition of the Android-side media/session transport.
     *
     * These conditions are the inputs to the lifecycle adaptation logic defined in
     * [integrationEntries].  [RuntimeController] is the sole authority that applies
     * the resulting [LifecycleAdaptation].
     *
     * @property wireValue Stable lowercase string used in structured log entries and
     *                     any host-facing transport-condition protocol fields.
     */
    enum class TransportCondition(val wireValue: String) {

        /**
         * Transport is fully operational.
         *
         * All execution paths are eligible.  [MediaTransportLifecycleBridge] requires
         * no adaptation; [RuntimeController] state machine is unaffected.
         */
        STABLE("stable"),

        /**
         * Transport quality has declined but the connection is still functional.
         *
         * Examples: elevated WS message latency, partial packet loss, reduced
         * throughput, or elevated error rate while the underlying WS connection is
         * technically still open.  The runtime should emit an advisory telemetry event
         * and suspend new delegated task acceptance until quality stabilizes.
         */
        DEGRADED("degraded"),

        /**
         * Transport is temporarily unavailable; automatic reconnect is in progress.
         *
         * Maps to [ReconnectRecoveryState.RECOVERING].  The runtime must not accept
         * new delegated tasks and should terminate any active delegated tasks that
         * cannot be safely held across the disconnect window.
         */
        INTERRUPTED("interrupted"),

        /**
         * Transport is administratively suspended; no automatic recovery is expected.
         *
         * Examples: cross-device disabled by user, kill-switch applied, or explicit
         * operator detach.  All active delegated tasks are terminated; the runtime
         * transitions to [RuntimeController.RuntimeState.LocalOnly].
         */
        SUSPENDED("suspended");

        companion object {
            /**
             * Returns the [TransportCondition] matching [value], or `null` for unknown values.
             */
            fun fromValue(value: String?): TransportCondition? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Lifecycle adaptation ──────────────────────────────────────────────────

    /**
     * Lifecycle adaptation that the runtime must apply in response to a
     * [TransportCondition] change.
     *
     * All adaptations **must** be applied through [RuntimeController]; no other
     * component may independently apply lifecycle adaptations in response to transport
     * condition changes.
     *
     * @property wireValue Stable lowercase string used in structured log entries.
     */
    enum class LifecycleAdaptation(val wireValue: String) {

        /**
         * No adaptation required.
         *
         * The runtime continues operating normally.  This is the adaptation for
         * [TransportCondition.STABLE].
         */
        NONE("none"),

        /**
         * Telemetry and surface-layer notification only.
         *
         * Emit a structured log event at INFO / WARN level; notify surface layers
         * via the relevant [RuntimeController] observable.  No execution path change.
         * In-flight tasks continue.  New tasks may still be accepted.
         */
        ADVISORY("advisory"),

        /**
         * Accept no new delegated tasks; in-flight tasks may complete.
         *
         * [RuntimeController.attachedSession] remains in [AttachedRuntimeSession.State.ATTACHED]
         * so in-flight tasks can complete, but [AttachedRuntimeSession.isReuseValid] should
         * be treated as `false` by dispatch-gating logic until transport stabilizes.
         * New [AttachedRuntimeSession.withExecutionAccepted] calls are suppressed.
         */
        SUSPEND_NEW_TASKS("suspend_new_tasks"),

        /**
         * Terminate all active delegated tasks and enter a recovery hold.
         *
         * All in-flight delegated tasks receive a [AttachedRuntimeSession.DetachCause.DISCONNECT]
         * or [AttachedRuntimeSession.DetachCause.EXPLICIT_DETACH] signal via
         * [RuntimeController.closeAttachedSession].  The runtime enters a recovery
         * hold (governed by [TransportContinuityAnchor]) until transport is restored.
         */
        TERMINATE_ACTIVE_TASKS("terminate_active_tasks");

        companion object {
            /**
             * Returns the [LifecycleAdaptation] matching [value], or `null` for unknown values.
             */
            fun fromValue(value: String?): LifecycleAdaptation? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Adaptation ownership ──────────────────────────────────────────────────

    /**
     * The layer that owns the adaptation decision for a given [TransportCondition].
     *
     * Ownership determines which component is permitted to trigger the adaptation.
     * All adaptations are ultimately **applied** by [RuntimeController]; this enum
     * records which layer is permitted to **initiate** the adaptation request.
     *
     * @property wireValue Stable lowercase string for structured log entries.
     */
    enum class AdaptationOwnership(val wireValue: String) {

        /**
         * The transport/network layer detects the condition and initiates the adaptation
         * request.  Example: [GalaxyWebSocketClient] detects disconnect and notifies
         * [RuntimeController] via its WS listener callback.
         */
        TRANSPORT_LAYER("transport_layer"),

        /**
         * [RuntimeController] itself detects and applies the adaptation through its
         * internal lifecycle methods (e.g. [RuntimeController.stop],
         * [RuntimeController.invalidateSession]).  No external component initiates this.
         */
        RUNTIME_LAYER("runtime_layer"),

        /**
         * The user or an operator explicitly requests the adaptation (e.g. toggle
         * cross-device off, explicit detach command from the host).  The request is
         * delivered to [RuntimeController] through the standard public API.
         */
        OPERATOR_OR_USER("operator_or_user")
    }

    // ── Transport integration entry ───────────────────────────────────────────

    /**
     * A single entry in the [integrationEntries] registry, mapping one
     * [TransportCondition] to the [LifecycleAdaptation] that the runtime must apply.
     *
     * @param condition       The transport condition that triggers this entry.
     * @param adaptation      The lifecycle adaptation required when [condition] is observed.
     * @param ownership       The layer that owns / initiates the adaptation decision.
     * @param recoveryPath    Human-readable description of how the runtime recovers from
     *                        this condition back to [TransportCondition.STABLE].
     * @param dispatchImpact  Machine-readable tag describing the dispatch eligibility impact.
     *                        Stable values: `"none"`, `"advisory"`,
     *                        `"new_tasks_suspended"`, `"all_tasks_terminated"`.
     * @param note            One-sentence summary of the entry's governance rationale.
     */
    data class TransportIntegrationEntry(
        val condition: TransportCondition,
        val adaptation: LifecycleAdaptation,
        val ownership: AdaptationOwnership,
        val recoveryPath: String,
        val dispatchImpact: String,
        val note: String
    )

    // ── Integration entry registry ────────────────────────────────────────────

    /**
     * Canonical registry of all transport-condition → lifecycle-adaptation mappings.
     *
     * This is the authoritative reference for how the Android runtime responds to
     * each [TransportCondition].  All runtime behavior in response to transport
     * condition changes must be consistent with the entries declared here.
     *
     * Use [entryFor] to look up the adaptation for a specific condition.
     */
    val integrationEntries: List<TransportIntegrationEntry> = listOf(

        TransportIntegrationEntry(
            condition    = TransportCondition.STABLE,
            adaptation   = LifecycleAdaptation.NONE,
            ownership    = AdaptationOwnership.RUNTIME_LAYER,
            recoveryPath = "No recovery needed; transport is fully operational.",
            dispatchImpact = "none",
            note = "Stable transport: no lifecycle adaptation; all dispatch paths remain eligible."
        ),

        TransportIntegrationEntry(
            condition    = TransportCondition.DEGRADED,
            adaptation   = LifecycleAdaptation.ADVISORY,
            ownership    = AdaptationOwnership.TRANSPORT_LAYER,
            recoveryPath = "Transport layer monitors quality metrics and emits STABLE once " +
                "latency / error rate returns below threshold. RuntimeController observes " +
                "recovery via its permanent WS listener and re-enables new task acceptance.",
            dispatchImpact = "advisory",
            note = "Degraded transport: advisory telemetry emitted; new task acceptance " +
                "is suspended until transport stabilises per SUSPEND_NEW_TASKS semantics."
        ),

        TransportIntegrationEntry(
            condition    = TransportCondition.INTERRUPTED,
            adaptation   = LifecycleAdaptation.TERMINATE_ACTIVE_TASKS,
            ownership    = AdaptationOwnership.TRANSPORT_LAYER,
            recoveryPath = "GalaxyWebSocketClient automatic exponential-backoff reconnect; " +
                "on reconnect success RuntimeController.openAttachedSession() with " +
                "SessionOpenSource.RECONNECT_RECOVERY and DurableSessionContinuityRecord " +
                "epoch increment. ReconnectRecoveryState transitions RECOVERING → RECOVERED.",
            dispatchImpact = "all_tasks_terminated",
            note = "Transport interrupted: active delegated tasks terminated with DISCONNECT " +
                "cause; session reopened on reconnect. Maps to ReconnectRecoveryState.RECOVERING."
        ),

        TransportIntegrationEntry(
            condition    = TransportCondition.SUSPENDED,
            adaptation   = LifecycleAdaptation.TERMINATE_ACTIVE_TASKS,
            ownership    = AdaptationOwnership.OPERATOR_OR_USER,
            recoveryPath = "Manual re-enable by user or operator via RuntimeController.start() " +
                "or RuntimeController.connectIfEnabled(); produces a new activation era " +
                "and a fresh DurableSessionContinuityRecord.",
            dispatchImpact = "all_tasks_terminated",
            note = "Transport administratively suspended: all delegated tasks terminated with " +
                "DISABLE or EXPLICIT_DETACH cause; runtime transitions to LocalOnly."
        )
    )

    // ── Lookup helpers ────────────────────────────────────────────────────────

    private val conditionIndex: Map<TransportCondition, TransportIntegrationEntry> =
        integrationEntries.associateBy { it.condition }

    /**
     * Returns the [TransportIntegrationEntry] for [condition], or `null` if
     * [condition] is not registered (defensive; all four conditions are registered).
     */
    fun entryFor(condition: TransportCondition): TransportIntegrationEntry? =
        conditionIndex[condition]

    /**
     * Returns all entries whose [TransportIntegrationEntry.adaptation] matches [adaptation].
     */
    fun entriesForAdaptation(adaptation: LifecycleAdaptation): List<TransportIntegrationEntry> =
        integrationEntries.filter { it.adaptation == adaptation }

    /**
     * Returns all entries owned by [ownership].
     */
    fun entriesForOwnership(ownership: AdaptationOwnership): List<TransportIntegrationEntry> =
        integrationEntries.filter { it.ownership == ownership }

    /**
     * Returns the set of [TransportCondition] values that require any lifecycle
     * adaptation beyond [LifecycleAdaptation.NONE].
     *
     * Consumers can use this set to gate telemetry or surface-layer notification
     * logic to only the conditions that actually require a runtime response.
     */
    val conditionsRequiringAdaptation: Set<TransportCondition> =
        integrationEntries
            .filter { it.adaptation != LifecycleAdaptation.NONE }
            .map { it.condition }
            .toSet()

    /**
     * Returns the set of [TransportCondition] values that result in task termination
     * (i.e. [LifecycleAdaptation.TERMINATE_ACTIVE_TASKS]).
     */
    val conditionsTerminatingTasks: Set<TransportCondition> =
        integrationEntries
            .filter { it.adaptation == LifecycleAdaptation.TERMINATE_ACTIVE_TASKS }
            .map { it.condition }
            .toSet()

    // ── System-level constants ────────────────────────────────────────────────

    /**
     * The PR number that introduced this bridge.
     */
    const val INTRODUCED_PR: Int = 40

    /**
     * One-sentence description of this bridge's purpose.
     */
    const val DESCRIPTION: String =
        "Canonical integration model between Android media/session transport conditions " +
            "and task/runtime lifecycle adaptations (PR-40 — Android Media Transport and " +
            "Task-Lifecycle Convergence).  All transport-driven lifecycle adaptations must " +
            "be executed through RuntimeController using the entries declared here."

    /**
     * Invariant: the sole authority for applying lifecycle adaptations is [RuntimeController].
     *
     * No transport-layer component, surface layer, or test double may independently
     * modify [RuntimeController.RuntimeState], [AttachedRuntimeSession] state, or
     * [RuntimeController.attachedSession] in response to a transport condition change.
     * All adaptations must be requested via [RuntimeController]'s public API.
     */
    const val LIFECYCLE_AUTHORITY_INVARIANT: String =
        "RuntimeController is the sole authority for all lifecycle adaptations triggered " +
            "by transport condition changes.  Transport-layer components must request " +
            "adaptations via RuntimeController; they must not independently modify runtime " +
            "or session state."
}
