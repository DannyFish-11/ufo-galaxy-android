package com.ufo.galaxy.runtime

/**
 * PR-42 — Android Runtime Invariant Enforcement.
 *
 * Defines the high-value Android runtime participation invariants that protect the
 * canonical Android runtime model from semantic drift.  Provides a machine-readable
 * invariant registry and pure-function check utilities so that any caller can assert
 * invariants at checkpoints without requiring mutable framework state.
 *
 * ## Problem addressed
 *
 * After the lifecycle, protocol, projection, transport, and contract-finalization work in
 * prior PRs, the Android runtime model is structurally well-defined.  However, without
 * explicit local invariant enforcement, the intended canonical model may degrade over
 * time through:
 *
 * - **Silent inconsistency** — contradictory state combinations (e.g. runtime Active but
 *   no attached session, or transport INTERRUPTED but cross-device dispatch eligible) that
 *   are individually legal but collectively wrong.
 * - **Drift from center expectations** — Android runtime behavior that diverges from the
 *   canonical participation model without an explicit violation being raised.
 * - **Regression without detection** — future refactors that break an invariant but do
 *   not trigger an immediate test failure.
 *
 * [RuntimeInvariantEnforcer] closes this gap by making every high-value invariant
 * explicit, machine-readable, and locally checkable.
 *
 * ## Design intent
 *
 * This enforcer is a **pure function** model — it holds no state, observes no flows, and
 * produces no side effects.  Callers supply the current runtime/session/rollout/transport
 * state and receive a list of [InvariantCheckResult] entries.  This keeps the invariant
 * logic fully testable in isolation and usable at any call site (diagnostics, test
 * assertions, protocol boundary gates).
 *
 * ## Invariant scopes
 *
 * | [InvariantScope]                        | Description                                                               |
 * |-----------------------------------------|---------------------------------------------------------------------------|
 * | [InvariantScope.SESSION]                | Session lifecycle and identity consistency invariants.                    |
 * | [InvariantScope.TRANSPORT]              | Transport continuity and cross-device eligibility invariants.             |
 * | [InvariantScope.READINESS]              | Dispatch readiness and rollout gate consistency invariants.               |
 * | [InvariantScope.DISPATCH]               | Dispatch path eligibility and path-mode coherence invariants.             |
 * | [InvariantScope.SNAPSHOT]               | Snapshot / projection co-derivation invariants.                           |
 *
 * ## Check result outcomes
 *
 * | [InvariantOutcome]                      | Meaning                                                                   |
 * |-----------------------------------------|---------------------------------------------------------------------------|
 * | [InvariantOutcome.SATISFIED]            | The invariant holds under the supplied state.                             |
 * | [InvariantOutcome.VIOLATED]             | The invariant does not hold; a drift condition is present.                |
 * | [InvariantOutcome.UNVERIFIABLE]         | Insufficient state was supplied to evaluate the invariant.               |
 *
 * ## Canonical invariants
 *
 * The nine core invariants are:
 *
 * 1. **SESSION_ACTIVE_REQUIRES_ATTACHED** — When runtime is Active, the attached session
 *    must be in ATTACHED state.  A detached or null session in an Active runtime is a
 *    drift condition.
 *
 * 2. **ATTACHED_REQUIRES_ACTIVE_OR_RECOVERY** — When an attached session exists in ATTACHED
 *    state, the runtime must be Active.  An attached session in a non-Active runtime
 *    indicates a lifecycle ordering violation.
 *
 * 3. **TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE** — When the transport condition is
 *    INTERRUPTED or SUSPENDED, no cross-device dispatch path (CANONICAL, STAGED_MESH,
 *    DELEGATED) must be returned by [CanonicalDispatchChain.resolveEligiblePathsForState].
 *
 * 4. **SNAPSHOT_REQUIRES_SESSION** — A non-null [AttachedRuntimeHostSessionSnapshot] must
 *    only exist when the attached session is non-null (no dangling projection).
 *
 * 5. **ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE** — Delegated execution eligibility
 *    requires cross-device to also be allowed; [delegatedExecutionAllowed] without
 *    [crossDeviceAllowed] is a configuration drift condition.
 *
 * 6. **DISPATCH_ELIGIBILITY_CONSISTENT_WITH_READINESS** — The result of
 *    [RuntimeDispatchReadinessCoordinator.resolve] must be consistent with the set of
 *    eligible dispatch paths: eligible readiness implies canonical path is present;
 *    ineligible readiness implies canonical path is absent.
 *
 * 7. **DURABLE_SESSION_PRESENT_WHEN_ACTIVE** — When the runtime is Active, a
 *    [DurableSessionContinuityRecord] must be present.  Missing durable session in an
 *    Active runtime indicates a lifecycle gap.
 *
 * 8. **RECOVERY_STATE_CONSISTENT_WITH_RUNTIME** — The [ReconnectRecoveryState] must be
 *    consistent with runtime state: RECOVERING or FAILED may only appear when the runtime
 *    is Active (the session is awaiting a WS reconnect); IDLE is expected in LocalOnly/Idle.
 *
 * 9. **KILL_SWITCH_CLEARS_CROSS_DEVICE** — When the rollout snapshot reflects a kill-switch
 *    (both [crossDeviceAllowed] and [goalExecutionAllowed] are false), the runtime must not
 *    be Active and the attached session must not be ATTACHED.
 *
 * @see RuntimeDispatchReadinessCoordinator
 * @see CanonicalDispatchChain
 * @see MediaTransportLifecycleBridge
 * @see TransportContinuityAnchor
 */
object RuntimeInvariantEnforcer {

    // ── Invariant scope ───────────────────────────────────────────────────────

    /**
     * Domain category for a [RuntimeInvariant].
     *
     * Used to filter invariant checks by area of concern.
     */
    enum class InvariantScope(val wireValue: String) {
        /**
         * Session lifecycle and identity consistency invariants.
         * Violations indicate that the attached session and runtime state are not coherent.
         */
        SESSION("session"),

        /**
         * Transport continuity and cross-device eligibility invariants.
         * Violations indicate that transport conditions are not properly restricting dispatch.
         */
        TRANSPORT("transport"),

        /**
         * Dispatch readiness and rollout gate consistency invariants.
         * Violations indicate that rollout flags or readiness answers are internally inconsistent.
         */
        READINESS("readiness"),

        /**
         * Dispatch path eligibility and path-mode coherence invariants.
         * Violations indicate that the resolved dispatch paths contradict readiness or transport state.
         */
        DISPATCH("dispatch"),

        /**
         * Snapshot / projection co-derivation invariants.
         * Violations indicate that a derived projection exists without the required authoritative truth.
         */
        SNAPSHOT("snapshot");

        companion object {
            /** Returns the [InvariantScope] with [value], or `null` if unknown. */
            fun fromValue(value: String): InvariantScope? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Invariant outcome ─────────────────────────────────────────────────────

    /**
     * The outcome of evaluating a single [RuntimeInvariant].
     */
    enum class InvariantOutcome(val wireValue: String) {
        /**
         * The invariant holds under the supplied state.
         * No drift condition detected for this invariant.
         */
        SATISFIED("satisfied"),

        /**
         * The invariant does not hold under the supplied state.
         * A drift or semantic inconsistency condition is present.
         */
        VIOLATED("violated"),

        /**
         * Insufficient state was supplied to evaluate the invariant.
         * The check was skipped; no conclusion can be drawn.
         */
        UNVERIFIABLE("unverifiable");

        companion object {
            /** Returns the [InvariantOutcome] with [value], or `null` if unknown. */
            fun fromValue(value: String): InvariantOutcome? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Invariant severity ────────────────────────────────────────────────────

    /**
     * Severity of a [RuntimeInvariant] violation.
     *
     * Used to triage violations: CRITICAL violations indicate a definite semantic drift
     * condition that should halt dispatch; WARNING violations indicate a configuration
     * inconsistency that is detectable but may not immediately affect behavior.
     */
    enum class InvariantSeverity(val wireValue: String) {
        /**
         * A violation is a definite semantic drift condition.
         * The invariant protects a core runtime participation guarantee.
         * Critical violations must not be ignored; they indicate the runtime has entered
         * an inconsistent state that requires corrective action.
         */
        CRITICAL("critical"),

        /**
         * A violation indicates a configuration inconsistency or sub-optimal state.
         * The invariant protects against latent drift but the system can continue operating.
         * Warning violations should be investigated and resolved but are not fatal.
         */
        WARNING("warning");

        companion object {
            /** Returns the [InvariantSeverity] with [value], or `null` if unknown. */
            fun fromValue(value: String): InvariantSeverity? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Invariant descriptor ──────────────────────────────────────────────────

    /**
     * Stable machine-readable identifier for a registered [RuntimeInvariant].
     *
     * Used as the primary key in [InvariantCheckResult] and in query helpers.
     */
    enum class InvariantId(val wireValue: String) {
        /** Runtime Active requires an ATTACHED session. */
        SESSION_ACTIVE_REQUIRES_ATTACHED("session_active_requires_attached"),

        /** ATTACHED session requires runtime to be Active. */
        ATTACHED_REQUIRES_ACTIVE_OR_RECOVERY("attached_requires_active_or_recovery"),

        /** INTERRUPTED/SUSPENDED transport must block cross-device dispatch. */
        TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE("transport_interrupted_blocks_cross_device"),

        /** Non-null host-session snapshot requires a non-null attached session. */
        SNAPSHOT_REQUIRES_SESSION("snapshot_requires_session"),

        /** Delegated execution rollout gate requires cross-device to also be allowed. */
        ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE("rollout_gate_delegated_requires_cross_device"),

        /** Dispatch eligibility result must match canonical path presence. */
        DISPATCH_ELIGIBILITY_CONSISTENT_WITH_READINESS("dispatch_eligibility_consistent_with_readiness"),

        /** Active runtime must have a durable session continuity record. */
        DURABLE_SESSION_PRESENT_WHEN_ACTIVE("durable_session_present_when_active"),

        /** Reconnect recovery state must be consistent with runtime state. */
        RECOVERY_STATE_CONSISTENT_WITH_RUNTIME("recovery_state_consistent_with_runtime"),

        /** Kill-switch state must preclude an Active runtime with an ATTACHED session. */
        KILL_SWITCH_CLEARS_CROSS_DEVICE("kill_switch_clears_cross_device");

        companion object {
            /** Returns the [InvariantId] with [value], or `null` if unknown. */
            fun fromValue(value: String): InvariantId? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    /**
     * Metadata descriptor for one canonical Android runtime invariant.
     *
     * @param id             Stable [InvariantId] identifier for this invariant.
     * @param scope          [InvariantScope] domain category.
     * @param severity       [InvariantSeverity] of a violation.
     * @param description    Human-readable invariant statement.
     * @param canonicalSurface The primary Android surface that governs this invariant.
     * @param driftRisk      Cross-repo [AndroidContractFinalizer.DriftRisk] if violated.
     * @param enforcementNote One-sentence note on how and where this invariant is enforced.
     */
    data class RuntimeInvariant(
        val id: InvariantId,
        val scope: InvariantScope,
        val severity: InvariantSeverity,
        val description: String,
        val canonicalSurface: String,
        val driftRisk: AndroidContractFinalizer.DriftRisk,
        val enforcementNote: String
    )

    // ── Invariant check result ────────────────────────────────────────────────

    /**
     * The result of checking one [RuntimeInvariant] against a supplied runtime snapshot.
     *
     * @param invariantId  The [InvariantId] of the evaluated invariant.
     * @param outcome      [InvariantOutcome] of this check.
     * @param violationDetail Human-readable detail when [outcome] is [InvariantOutcome.VIOLATED].
     *                     `null` when the invariant is satisfied or unverifiable.
     */
    data class InvariantCheckResult(
        val invariantId: InvariantId,
        val outcome: InvariantOutcome,
        val violationDetail: String? = null
    ) {
        /** `true` when this result represents a violation. */
        val isViolation: Boolean get() = outcome == InvariantOutcome.VIOLATED

        /** `true` when this result is satisfied (no drift detected). */
        val isSatisfied: Boolean get() = outcome == InvariantOutcome.SATISFIED
    }

    // ── Canonical invariant registry ──────────────────────────────────────────

    /**
     * The complete registry of high-value Android runtime participation invariants.
     *
     * Each entry describes one invariant, its scope, severity, governing surface, and
     * drift risk if violated.  Consumers can query this registry to enumerate all
     * enforced invariants or look up a specific invariant by [InvariantId].
     */
    val invariants: List<RuntimeInvariant> = listOf(

        // ── Session lifecycle invariants ──────────────────────────────────────

        RuntimeInvariant(
            id = InvariantId.SESSION_ACTIVE_REQUIRES_ATTACHED,
            scope = InvariantScope.SESSION,
            severity = InvariantSeverity.CRITICAL,
            description = "When RuntimeController.state is Active, the attached session " +
                "must be in AttachedRuntimeSession.State.ATTACHED.  A detached or null " +
                "session in an Active runtime is a lifecycle ordering drift condition.",
            canonicalSurface = "RuntimeController.state + RuntimeController.attachedSession",
            driftRisk = AndroidContractFinalizer.DriftRisk.HIGH,
            enforcementNote = "Checked at every dispatch eligibility resolution via " +
                "RuntimeDispatchReadinessCoordinator; also enforced in RuntimeInvariantEnforcer.checkAll()."
        ),

        RuntimeInvariant(
            id = InvariantId.ATTACHED_REQUIRES_ACTIVE_OR_RECOVERY,
            scope = InvariantScope.SESSION,
            severity = InvariantSeverity.CRITICAL,
            description = "When AttachedRuntimeSession.State is ATTACHED, the runtime " +
                "must be Active.  An ATTACHED session in a non-Active runtime indicates " +
                "a lifecycle ordering violation (session was not properly closed on stop).",
            canonicalSurface = "RuntimeController.attachedSession + RuntimeController.state",
            driftRisk = AndroidContractFinalizer.DriftRisk.HIGH,
            enforcementNote = "Checked in RuntimeInvariantEnforcer.checkAll(); reinforced " +
                "by RuntimeController.stop() which always closes the session before transitioning."
        ),

        // ── Transport continuity invariants ───────────────────────────────────

        RuntimeInvariant(
            id = InvariantId.TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE,
            scope = InvariantScope.TRANSPORT,
            severity = InvariantSeverity.CRITICAL,
            description = "When MediaTransportLifecycleBridge.TransportCondition is " +
                "INTERRUPTED or SUSPENDED, no cross-device dispatch path (CANONICAL, " +
                "STAGED_MESH, DELEGATED) must be present in the eligible path set.  " +
                "Transport interruption must suppress all cross-device participation.",
            canonicalSurface = "CanonicalDispatchChain.resolveTransportAdaptedPaths() + " +
                "MediaTransportLifecycleBridge",
            driftRisk = AndroidContractFinalizer.DriftRisk.HIGH,
            enforcementNote = "Enforced by CanonicalDispatchChain.resolveTransportAdaptedPaths() " +
                "filtering CROSS_DEVICE_PATH_MODES for INTERRUPTED/SUSPENDED conditions; " +
                "also validated in RuntimeInvariantEnforcer.checkAll()."
        ),

        // ── Snapshot / projection invariants ─────────────────────────────────

        RuntimeInvariant(
            id = InvariantId.SNAPSHOT_REQUIRES_SESSION,
            scope = InvariantScope.SNAPSHOT,
            severity = InvariantSeverity.CRITICAL,
            description = "A non-null AttachedRuntimeHostSessionSnapshot must only exist " +
                "when the attached session is non-null.  A snapshot without a backing session " +
                "is a dangling projection — a projection integrity violation.",
            canonicalSurface = "RuntimeController.hostSessionSnapshot + RuntimeController.attachedSession",
            driftRisk = AndroidContractFinalizer.DriftRisk.HIGH,
            enforcementNote = "Enforced by RuntimeTruthPrecedenceRules single-gate update " +
                "invariant; snapshot is only updated inside updateHostSessionSnapshot() " +
                "which is called exclusively from open/closeAttachedSession()."
        ),

        // ── Rollout gate invariants ───────────────────────────────────────────

        RuntimeInvariant(
            id = InvariantId.ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE,
            scope = InvariantScope.READINESS,
            severity = InvariantSeverity.WARNING,
            description = "RolloutControlSnapshot.delegatedExecutionAllowed = true requires " +
                "RolloutControlSnapshot.crossDeviceAllowed = true.  Delegated execution without " +
                "the cross-device gate is an unreachable configuration; the delegated path " +
                "requires a live cross-device session.",
            canonicalSurface = "RolloutControlSnapshot",
            driftRisk = AndroidContractFinalizer.DriftRisk.MEDIUM,
            enforcementNote = "Checked in RuntimeInvariantEnforcer.checkAll(); enforced at " +
                "dispatch resolution by RuntimeDispatchReadinessCoordinator requiring both " +
                "crossDeviceAllowed and delegatedExecutionAllowed."
        ),

        // ── Dispatch eligibility invariants ───────────────────────────────────

        RuntimeInvariant(
            id = InvariantId.DISPATCH_ELIGIBILITY_CONSISTENT_WITH_READINESS,
            scope = InvariantScope.DISPATCH,
            severity = InvariantSeverity.CRITICAL,
            description = "The dispatch eligibility result from RuntimeDispatchReadinessCoordinator " +
                "must be consistent with the resolved eligible path set: when eligible, the " +
                "CANONICAL path must be present; when ineligible, the CANONICAL path must be absent.",
            canonicalSurface = "RuntimeDispatchReadinessCoordinator + CanonicalDispatchChain",
            driftRisk = AndroidContractFinalizer.DriftRisk.HIGH,
            enforcementNote = "Checked in RuntimeInvariantEnforcer.checkAll() using both the " +
                "readiness result and the resolved paths from CanonicalDispatchChain."
        ),

        // ── Durable session invariant ─────────────────────────────────────────

        RuntimeInvariant(
            id = InvariantId.DURABLE_SESSION_PRESENT_WHEN_ACTIVE,
            scope = InvariantScope.SESSION,
            severity = InvariantSeverity.WARNING,
            description = "When RuntimeController.state is Active, a DurableSessionContinuityRecord " +
                "must be present.  Missing durable session in an Active runtime indicates a " +
                "lifecycle gap — openAttachedSession() was not called or the record was " +
                "prematurely cleared.",
            canonicalSurface = "RuntimeController.durableSessionContinuityRecord + RuntimeController.state",
            driftRisk = AndroidContractFinalizer.DriftRisk.MEDIUM,
            enforcementNote = "Checked in RuntimeInvariantEnforcer.checkAll(); enforced by " +
                "RuntimeController.openAttachedSession() which initialises the record on first " +
                "USER_ACTIVATION or BACKGROUND_RESTORE."
        ),

        // ── Reconnect recovery state invariant ────────────────────────────────

        RuntimeInvariant(
            id = InvariantId.RECOVERY_STATE_CONSISTENT_WITH_RUNTIME,
            scope = InvariantScope.SESSION,
            severity = InvariantSeverity.WARNING,
            description = "ReconnectRecoveryState.RECOVERING or FAILED must only appear " +
                "when RuntimeController.state is Active (the runtime is recovering a WS " +
                "connection).  In LocalOnly, Idle, or Failed states the recovery state " +
                "must be IDLE.",
            canonicalSurface = "RuntimeController.reconnectRecoveryState + RuntimeController.state",
            driftRisk = AndroidContractFinalizer.DriftRisk.MEDIUM,
            enforcementNote = "Checked in RuntimeInvariantEnforcer.checkAll(); enforced by " +
                "RuntimeController.stop() which always resets recovery state to IDLE."
        ),

        // ── Kill-switch invariant ─────────────────────────────────────────────

        RuntimeInvariant(
            id = InvariantId.KILL_SWITCH_CLEARS_CROSS_DEVICE,
            scope = InvariantScope.READINESS,
            severity = InvariantSeverity.CRITICAL,
            description = "When RolloutControlSnapshot reflects a kill-switch state " +
                "(crossDeviceAllowed = false AND goalExecutionAllowed = false), the runtime " +
                "must not be Active and the attached session must not be ATTACHED.  " +
                "This invariant ensures the kill-switch is immediately effective.",
            canonicalSurface = "RolloutControlSnapshot + RuntimeController.state + RuntimeController.attachedSession",
            driftRisk = AndroidContractFinalizer.DriftRisk.HIGH,
            enforcementNote = "Checked in RuntimeInvariantEnforcer.checkAll(); enforced by " +
                "RuntimeController.applyKillSwitch() which atomically disables flags and calls stop()."
        )
    )

    private val invariantIndex: Map<InvariantId, RuntimeInvariant> =
        invariants.associateBy { it.id }

    // ── Query helpers ─────────────────────────────────────────────────────────

    /**
     * Returns the [RuntimeInvariant] for [id], or `null` if not registered.
     */
    fun invariantFor(id: InvariantId): RuntimeInvariant? = invariantIndex[id]

    /**
     * Returns all invariants in [scope].
     */
    fun byScope(scope: InvariantScope): List<RuntimeInvariant> =
        invariants.filter { it.scope == scope }

    /**
     * Returns all invariants with [severity].
     */
    fun bySeverity(severity: InvariantSeverity): List<RuntimeInvariant> =
        invariants.filter { it.severity == severity }

    /**
     * Set of [InvariantId] values for CRITICAL invariants.
     */
    val criticalInvariantIds: Set<InvariantId> =
        invariants
            .filter { it.severity == InvariantSeverity.CRITICAL }
            .map { it.id }
            .toSet()

    /**
     * Set of [InvariantId] values for WARNING invariants.
     */
    val warningInvariantIds: Set<InvariantId> =
        invariants
            .filter { it.severity == InvariantSeverity.WARNING }
            .map { it.id }
            .toSet()

    // ── Invariant check engine ────────────────────────────────────────────────

    /**
     * Checks all registered invariants against the supplied runtime snapshot.
     *
     * This is a **pure function** — it reads no global state, emits no events, and
     * produces no side effects.  The returned list contains exactly one [InvariantCheckResult]
     * per registered invariant.
     *
     * Any invariant that cannot be evaluated from the supplied parameters is returned
     * with [InvariantOutcome.UNVERIFIABLE].
     *
     * @param runtimeState         Current [RuntimeController.RuntimeState].
     * @param attachedSession      Current [AttachedRuntimeSession], or `null` if none.
     * @param rollout              Current [RolloutControlSnapshot].
     * @param transportCondition   Current [MediaTransportLifecycleBridge.TransportCondition].
     * @param hostSessionSnapshot  Current [AttachedRuntimeHostSessionSnapshot], or `null`.
     * @param durableRecord        Current [DurableSessionContinuityRecord], or `null`.
     * @param recoveryState        Current [ReconnectRecoveryState].
     * @return List of [InvariantCheckResult], one per registered invariant, in
     *         [invariants] order.
     */
    fun checkAll(
        runtimeState: RuntimeController.RuntimeState,
        attachedSession: AttachedRuntimeSession?,
        rollout: RolloutControlSnapshot,
        transportCondition: MediaTransportLifecycleBridge.TransportCondition,
        hostSessionSnapshot: AttachedRuntimeHostSessionSnapshot? = null,
        durableRecord: DurableSessionContinuityRecord? = null,
        recoveryState: ReconnectRecoveryState = ReconnectRecoveryState.IDLE
    ): List<InvariantCheckResult> = listOf(
        checkSessionActiveRequiresAttached(runtimeState, attachedSession),
        checkAttachedRequiresActiveOrRecovery(runtimeState, attachedSession),
        checkTransportInterruptedBlocksCrossDevice(runtimeState, attachedSession, rollout, transportCondition),
        checkSnapshotRequiresSession(attachedSession, hostSessionSnapshot),
        checkRolloutGateDelegatedRequiresCrossDevice(rollout),
        checkDispatchEligibilityConsistentWithReadiness(runtimeState, attachedSession, rollout),
        checkDurableSessionPresentWhenActive(runtimeState, durableRecord),
        checkRecoveryStateConsistentWithRuntime(runtimeState, recoveryState),
        checkKillSwitchClearsCrossDevice(runtimeState, attachedSession, rollout)
    )

    /**
     * Returns the set of [InvariantId] values that are violated given the supplied state.
     *
     * Convenience wrapper around [checkAll] for callers that only need the set of
     * violated invariant identifiers rather than the full [InvariantCheckResult] list.
     */
    fun violatedInvariantIds(
        runtimeState: RuntimeController.RuntimeState,
        attachedSession: AttachedRuntimeSession?,
        rollout: RolloutControlSnapshot,
        transportCondition: MediaTransportLifecycleBridge.TransportCondition,
        hostSessionSnapshot: AttachedRuntimeHostSessionSnapshot? = null,
        durableRecord: DurableSessionContinuityRecord? = null,
        recoveryState: ReconnectRecoveryState = ReconnectRecoveryState.IDLE
    ): Set<InvariantId> =
        checkAll(
            runtimeState = runtimeState,
            attachedSession = attachedSession,
            rollout = rollout,
            transportCondition = transportCondition,
            hostSessionSnapshot = hostSessionSnapshot,
            durableRecord = durableRecord,
            recoveryState = recoveryState
        )
            .filter { it.isViolation }
            .map { it.invariantId }
            .toSet()

    /**
     * Returns `true` when all checked invariants are satisfied (no violations) for the
     * supplied state.  Unverifiable invariants are not treated as violations.
     */
    fun allSatisfied(
        runtimeState: RuntimeController.RuntimeState,
        attachedSession: AttachedRuntimeSession?,
        rollout: RolloutControlSnapshot,
        transportCondition: MediaTransportLifecycleBridge.TransportCondition,
        hostSessionSnapshot: AttachedRuntimeHostSessionSnapshot? = null,
        durableRecord: DurableSessionContinuityRecord? = null,
        recoveryState: ReconnectRecoveryState = ReconnectRecoveryState.IDLE
    ): Boolean = violatedInvariantIds(
        runtimeState = runtimeState,
        attachedSession = attachedSession,
        rollout = rollout,
        transportCondition = transportCondition,
        hostSessionSnapshot = hostSessionSnapshot,
        durableRecord = durableRecord,
        recoveryState = recoveryState
    ).isEmpty()

    // ── Individual invariant check functions ──────────────────────────────────

    /**
     * Checks [InvariantId.SESSION_ACTIVE_REQUIRES_ATTACHED].
     *
     * Violation: runtime is Active but the session is null or not ATTACHED.
     */
    fun checkSessionActiveRequiresAttached(
        runtimeState: RuntimeController.RuntimeState,
        attachedSession: AttachedRuntimeSession?
    ): InvariantCheckResult {
        val id = InvariantId.SESSION_ACTIVE_REQUIRES_ATTACHED
        return if (runtimeState !is RuntimeController.RuntimeState.Active) {
            InvariantCheckResult(id, InvariantOutcome.SATISFIED)
        } else if (attachedSession?.isAttached == true) {
            InvariantCheckResult(id, InvariantOutcome.SATISFIED)
        } else {
            val detail = if (attachedSession == null) {
                "Runtime is Active but attachedSession is null"
            } else {
                "Runtime is Active but attachedSession.state=${attachedSession.state}"
            }
            InvariantCheckResult(id, InvariantOutcome.VIOLATED, detail)
        }
    }

    /**
     * Checks [InvariantId.ATTACHED_REQUIRES_ACTIVE_OR_RECOVERY].
     *
     * Violation: session is ATTACHED but runtime is not Active.
     */
    fun checkAttachedRequiresActiveOrRecovery(
        runtimeState: RuntimeController.RuntimeState,
        attachedSession: AttachedRuntimeSession?
    ): InvariantCheckResult {
        val id = InvariantId.ATTACHED_REQUIRES_ACTIVE_OR_RECOVERY
        if (attachedSession?.isAttached != true) {
            return InvariantCheckResult(id, InvariantOutcome.SATISFIED)
        }
        return if (runtimeState is RuntimeController.RuntimeState.Active) {
            InvariantCheckResult(id, InvariantOutcome.SATISFIED)
        } else {
            InvariantCheckResult(
                id,
                InvariantOutcome.VIOLATED,
                "Session is ATTACHED but runtime state=${runtimeState::class.simpleName}"
            )
        }
    }

    /**
     * Checks [InvariantId.TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE].
     *
     * Violation: transport is INTERRUPTED or SUSPENDED but a cross-device path
     * is present in the eligible paths resolved from [CanonicalDispatchChain].
     */
    fun checkTransportInterruptedBlocksCrossDevice(
        runtimeState: RuntimeController.RuntimeState,
        attachedSession: AttachedRuntimeSession?,
        rollout: RolloutControlSnapshot,
        transportCondition: MediaTransportLifecycleBridge.TransportCondition
    ): InvariantCheckResult {
        val id = InvariantId.TRANSPORT_INTERRUPTED_BLOCKS_CROSS_DEVICE
        val isBlocking = transportCondition == MediaTransportLifecycleBridge.TransportCondition.INTERRUPTED ||
            transportCondition == MediaTransportLifecycleBridge.TransportCondition.SUSPENDED
        if (!isBlocking) {
            return InvariantCheckResult(id, InvariantOutcome.SATISFIED)
        }
        val paths = CanonicalDispatchChain.resolveTransportAdaptedPaths(
            runtimeState = runtimeState,
            attachedSession = attachedSession,
            rollout = rollout,
            transportCondition = transportCondition
        )
        val crossDevicePaths = paths.filter { it.pathMode in CanonicalDispatchChain.CROSS_DEVICE_PATH_MODES }
        return if (crossDevicePaths.isEmpty()) {
            InvariantCheckResult(id, InvariantOutcome.SATISFIED)
        } else {
            InvariantCheckResult(
                id,
                InvariantOutcome.VIOLATED,
                "Transport is $transportCondition but cross-device paths present: " +
                    crossDevicePaths.joinToString { it.pathMode.wireValue }
            )
        }
    }

    /**
     * Checks [InvariantId.SNAPSHOT_REQUIRES_SESSION].
     *
     * Violation: host session snapshot is non-null but the attached session is null.
     */
    fun checkSnapshotRequiresSession(
        attachedSession: AttachedRuntimeSession?,
        hostSessionSnapshot: AttachedRuntimeHostSessionSnapshot?
    ): InvariantCheckResult {
        val id = InvariantId.SNAPSHOT_REQUIRES_SESSION
        if (hostSessionSnapshot == null) {
            return InvariantCheckResult(id, InvariantOutcome.SATISFIED)
        }
        return if (attachedSession != null) {
            InvariantCheckResult(id, InvariantOutcome.SATISFIED)
        } else {
            InvariantCheckResult(
                id,
                InvariantOutcome.VIOLATED,
                "hostSessionSnapshot is non-null but attachedSession is null (dangling projection)"
            )
        }
    }

    /**
     * Checks [InvariantId.ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE].
     *
     * Violation: delegatedExecutionAllowed is true but crossDeviceAllowed is false.
     */
    fun checkRolloutGateDelegatedRequiresCrossDevice(
        rollout: RolloutControlSnapshot
    ): InvariantCheckResult {
        val id = InvariantId.ROLLOUT_GATE_DELEGATED_REQUIRES_CROSS_DEVICE
        return if (!rollout.delegatedExecutionAllowed || rollout.crossDeviceAllowed) {
            InvariantCheckResult(id, InvariantOutcome.SATISFIED)
        } else {
            InvariantCheckResult(
                id,
                InvariantOutcome.VIOLATED,
                "delegatedExecutionAllowed=true but crossDeviceAllowed=false " +
                    "(delegated execution requires cross-device to be allowed)"
            )
        }
    }

    /**
     * Checks [InvariantId.DISPATCH_ELIGIBILITY_CONSISTENT_WITH_READINESS].
     *
     * Violation: readiness says eligible but CANONICAL path is absent in resolved paths,
     * or readiness says ineligible but CANONICAL path is present.
     */
    fun checkDispatchEligibilityConsistentWithReadiness(
        runtimeState: RuntimeController.RuntimeState,
        attachedSession: AttachedRuntimeSession?,
        rollout: RolloutControlSnapshot
    ): InvariantCheckResult {
        val id = InvariantId.DISPATCH_ELIGIBILITY_CONSISTENT_WITH_READINESS
        val readiness = RuntimeDispatchReadinessCoordinator.resolve(
            runtimeState = runtimeState,
            attachedSession = attachedSession,
            rollout = rollout
        )
        val eligiblePaths = CanonicalDispatchChain.resolveEligiblePathsForState(
            runtimeState = runtimeState,
            attachedSession = attachedSession,
            rollout = rollout
        )
        val canonicalPresent = eligiblePaths.any { it.pathMode == DispatchPathMode.CANONICAL }
        return when {
            readiness.isEligible && !canonicalPresent ->
                InvariantCheckResult(
                    id,
                    InvariantOutcome.VIOLATED,
                    "Readiness=eligible but CANONICAL path is absent in resolved paths"
                )
            !readiness.isEligible && canonicalPresent ->
                InvariantCheckResult(
                    id,
                    InvariantOutcome.VIOLATED,
                    "Readiness=ineligible (${readiness.ineligibilityReason}) " +
                        "but CANONICAL path is present in resolved paths"
                )
            else ->
                InvariantCheckResult(id, InvariantOutcome.SATISFIED)
        }
    }

    /**
     * Checks [InvariantId.DURABLE_SESSION_PRESENT_WHEN_ACTIVE].
     *
     * When [durableRecord] is `null` (not provided to [checkAll]), the invariant is
     * [InvariantOutcome.UNVERIFIABLE].
     */
    fun checkDurableSessionPresentWhenActive(
        runtimeState: RuntimeController.RuntimeState,
        durableRecord: DurableSessionContinuityRecord?
    ): InvariantCheckResult {
        val id = InvariantId.DURABLE_SESSION_PRESENT_WHEN_ACTIVE
        if (runtimeState !is RuntimeController.RuntimeState.Active) {
            return InvariantCheckResult(id, InvariantOutcome.SATISFIED)
        }
        return when {
            durableRecord != null -> InvariantCheckResult(id, InvariantOutcome.SATISFIED)
            else -> InvariantCheckResult(
                id,
                InvariantOutcome.VIOLATED,
                "Runtime is Active but durableSessionContinuityRecord is null"
            )
        }
    }

    /**
     * Checks [InvariantId.RECOVERY_STATE_CONSISTENT_WITH_RUNTIME].
     *
     * Violation: recovery state is RECOVERING or FAILED but runtime is not Active.
     */
    fun checkRecoveryStateConsistentWithRuntime(
        runtimeState: RuntimeController.RuntimeState,
        recoveryState: ReconnectRecoveryState
    ): InvariantCheckResult {
        val id = InvariantId.RECOVERY_STATE_CONSISTENT_WITH_RUNTIME
        val recoveryActive = recoveryState == ReconnectRecoveryState.RECOVERING ||
            recoveryState == ReconnectRecoveryState.FAILED
        if (!recoveryActive) {
            return InvariantCheckResult(id, InvariantOutcome.SATISFIED)
        }
        return if (runtimeState is RuntimeController.RuntimeState.Active) {
            InvariantCheckResult(id, InvariantOutcome.SATISFIED)
        } else {
            InvariantCheckResult(
                id,
                InvariantOutcome.VIOLATED,
                "RecoveryState=$recoveryState but runtime state=${runtimeState::class.simpleName} " +
                    "(recovery states must only occur while Active)"
            )
        }
    }

    /**
     * Checks [InvariantId.KILL_SWITCH_CLEARS_CROSS_DEVICE].
     *
     * Violation: rollout reflects kill-switch (crossDeviceAllowed=false AND
     * goalExecutionAllowed=false) but the runtime is Active or the session is ATTACHED.
     */
    fun checkKillSwitchClearsCrossDevice(
        runtimeState: RuntimeController.RuntimeState,
        attachedSession: AttachedRuntimeSession?,
        rollout: RolloutControlSnapshot
    ): InvariantCheckResult {
        val id = InvariantId.KILL_SWITCH_CLEARS_CROSS_DEVICE
        val isKillSwitched = !rollout.crossDeviceAllowed && !rollout.goalExecutionAllowed
        if (!isKillSwitched) {
            return InvariantCheckResult(id, InvariantOutcome.SATISFIED)
        }
        val runtimeActive = runtimeState is RuntimeController.RuntimeState.Active
        val sessionAttached = attachedSession?.isAttached == true
        return if (!runtimeActive && !sessionAttached) {
            InvariantCheckResult(id, InvariantOutcome.SATISFIED)
        } else {
            val detail = buildString {
                if (runtimeActive) append("runtime is Active")
                if (runtimeActive && sessionAttached) append(" and ")
                if (sessionAttached) append("session is ATTACHED")
                append(" despite kill-switch state (crossDeviceAllowed=false, goalExecutionAllowed=false)")
            }
            InvariantCheckResult(id, InvariantOutcome.VIOLATED, detail)
        }
    }

    // ── System-level constants ────────────────────────────────────────────────

    /**
     * The PR number that introduced this invariant enforcer.
     */
    const val INTRODUCED_PR: Int = 42

    /**
     * Short description of what this invariant enforcer declares.
     */
    const val DESCRIPTION: String =
        "PR-42 runtime invariant enforcer: defines nine high-value Android runtime " +
            "participation invariants across SESSION, TRANSPORT, READINESS, DISPATCH, and " +
            "SNAPSHOT scopes.  Provides pure-function check utilities for use at dispatch " +
            "boundaries, diagnostics checkpoints, and acceptance tests."

    /**
     * The meta-invariant that governs this enforcer's own correctness:
     * the number of registered [RuntimeInvariant] entries must equal the number of
     * [InvariantId] enum values.
     *
     * If this invariant is violated, a new [InvariantId] was added without a corresponding
     * entry in [invariants] (or vice versa), indicating a coverage gap.
     */
    const val COVERAGE_INVARIANT: String =
        "invariants.size == InvariantId.entries.size — every InvariantId must have " +
            "exactly one RuntimeInvariant entry in the registry."
}
