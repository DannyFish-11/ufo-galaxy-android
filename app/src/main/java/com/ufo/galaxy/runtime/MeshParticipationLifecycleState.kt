package com.ufo.galaxy.runtime

/**
 * PR-04 — Android mesh participation lifecycle state.
 *
 * Models the position of this Android device in the mesh participation lifecycle as a
 * first-class, wire-serialisable state.  This enum closes the gap between Android being
 * "participation-ready" and being a **stable runtime participant** with explicit lifecycle
 * semantics that V2 can reason about.
 *
 * ## Motivation
 *
 * Prior to PR-04, Android's mesh participation was represented only by
 * [AndroidMeshParticipationContract.ReadinessLevel] — a coarse three-value readiness
 * assessment (READY / PARTIAL / DEFERRED).  This was insufficient because:
 *  - It captured static capability readiness, not dynamic participation position.
 *  - It did not model barrier-related states (waiting, released) that are critical for
 *    V2 to correctly sequence multi-device coordination.
 *  - It did not model degraded, constrained, or fallback runtime participation paths —
 *    Android was either "ready" or "not ready" with no intermediate contract.
 *
 * [MeshParticipationLifecycleState] introduces a richer, runtime-lifecycle-oriented state
 * model that V2 can consume to understand exactly where Android sits in the mesh execution
 * flow at any given point.
 *
 * ## State definitions
 *
 * | [MeshParticipationLifecycleState] | Wire value          | Description |
 * |-----------------------------------|---------------------|-------------|
 * | [INACTIVE]                        | `inactive`          | Not participating; no mesh session active |
 * | [JOINING]                         | `joining`           | Establishing participation in a mesh session |
 * | [ACTIVE]                          | `active`            | Actively participating in a mesh session |
 * | [BARRIER_WAITING]                 | `barrier_waiting`   | Waiting at a V2-coordinated barrier |
 * | [BARRIER_RELEASED]                | `barrier_released`  | Barrier released; proceeding with execution |
 * | [DEGRADED]                        | `degraded`          | Participating with reduced capability |
 * | [CONSTRAINED]                     | `constrained`       | Participating under rollout / policy constraints |
 * | [FALLBACK]                        | `fallback`          | Participating via fallback execution path |
 * | [LEAVING]                         | `leaving`           | Withdrawing from the mesh session |
 *
 * ## Transition rules
 *
 * Normal happy-path:
 * ```
 * INACTIVE → JOINING → ACTIVE → [BARRIER_WAITING → BARRIER_RELEASED] → LEAVING → INACTIVE
 * ```
 *
 * Degraded/constrained paths:
 * ```
 * ACTIVE → DEGRADED → ACTIVE (recovery)
 * ACTIVE → CONSTRAINED → ACTIVE (constraint lifted)
 * ACTIVE → FALLBACK → LEAVING
 * JOINING → INACTIVE (join rejected / gate closed)
 * ```
 *
 * @property wireValue Stable lowercase wire value used in diagnostics, V2-facing payloads,
 *                     and [DeviceStateSnapshotPayload.mesh_participation_lifecycle_state].
 * @property description Human-readable description for operator surfaces and audit logs.
 */
enum class MeshParticipationLifecycleState(
    val wireValue: String,
    val description: String
) {

    /**
     * Android is not participating in any mesh session.  No mesh assignment is active.
     * This is the initial state and the state after [LEAVING] completes.
     */
    INACTIVE(
        wireValue = "inactive",
        description = "Not participating; no mesh session active"
    ),

    /**
     * Android is in the process of joining a mesh session — the session has been assigned
     * but participation has not yet been confirmed.  Capability advertisement for the mesh
     * session is pending V2 acknowledgement.
     */
    JOINING(
        wireValue = "joining",
        description = "Establishing participation in a mesh session"
    ),

    /**
     * Android is an active participant in a mesh session.  Subtask assignments may be
     * accepted and executed.  This is the steady-state for a healthy mesh participant.
     */
    ACTIVE(
        wireValue = "active",
        description = "Actively participating in a mesh session"
    ),

    /**
     * Android has received a barrier signal and is waiting for the V2 coordinator to
     * release the barrier.  No new subtask assignments should be accepted until the
     * barrier is released (transitions to [BARRIER_RELEASED]).
     *
     * Barrier coordination authority belongs to V2.  Android reports this state to
     * surface the wait position for operator observability.
     */
    BARRIER_WAITING(
        wireValue = "barrier_waiting",
        description = "Waiting at a V2-coordinated barrier; no new subtask assignments"
    ),

    /**
     * The V2 coordinator has released the barrier.  Android may resume accepting subtask
     * assignments.  This state is transient — it transitions to [ACTIVE] once execution
     * resumes, or to [LEAVING] if the session is ending.
     */
    BARRIER_RELEASED(
        wireValue = "barrier_released",
        description = "Barrier released; ready to resume execution"
    ),

    /**
     * Android is participating in the mesh session but with reduced capability.  Typically
     * caused by a degraded runtime health state ([ParticipantHealthState.DEGRADED]).
     * V2 should apply reduced dispatch priority for this participant.
     */
    DEGRADED(
        wireValue = "degraded",
        description = "Participating with reduced capability due to runtime health degradation"
    ),

    /**
     * Android is participating in the mesh session but under rollout or policy constraints.
     * Typically caused by a partially-open gate (e.g. [RolloutControlSnapshot.crossDeviceAllowed]
     * is true but [RolloutControlSnapshot.delegatedExecutionAllowed] is false).
     * V2 should apply the declared participation constraints.
     */
    CONSTRAINED(
        wireValue = "constrained",
        description = "Participating under active rollout or policy constraints"
    ),

    /**
     * Android is participating via a fallback execution path (e.g. local-only fallback
     * after a failed cross-device path, or planner/grounding fallback tier active).
     * The fallback path is less capable than the primary path; V2 should account for this
     * when evaluating result quality.
     */
    FALLBACK(
        wireValue = "fallback",
        description = "Participating via fallback execution path"
    ),

    /**
     * Android is withdrawing from the mesh session (graceful leave).  No new subtask
     * assignments will be accepted.  Transitions to [INACTIVE] when the leave is complete.
     */
    LEAVING(
        wireValue = "leaving",
        description = "Withdrawing from the mesh session"
    );

    companion object {

        /**
         * Returns the [MeshParticipationLifecycleState] with the given [wireValue], or
         * `null` if no match is found.
         */
        fun fromWireValue(value: String?): MeshParticipationLifecycleState? =
            entries.firstOrNull { it.wireValue == value }

        /**
         * Returns `true` when [state] allows Android to accept new subtask assignments.
         *
         * Only [ACTIVE] and [BARRIER_RELEASED] allow new assignments.
         * [DEGRADED] and [CONSTRAINED] technically permit assignments with caveats,
         * but the decision to assign should rest with V2 after evaluating the state.
         */
        fun isAcceptingSubtasks(state: MeshParticipationLifecycleState): Boolean =
            state == ACTIVE || state == BARRIER_RELEASED || state == DEGRADED || state == CONSTRAINED

        /**
         * Returns `true` when [state] is a barrier-related transitional state.
         */
        fun isBarrierState(state: MeshParticipationLifecycleState): Boolean =
            state == BARRIER_WAITING || state == BARRIER_RELEASED

        /**
         * Returns `true` when [state] represents a degraded, constrained, or fallback
         * participation path — i.e. Android is participating but not at full capability.
         */
        fun isLimitedParticipation(state: MeshParticipationLifecycleState): Boolean =
            state == DEGRADED || state == CONSTRAINED || state == FALLBACK

        /** All stable wire values for schema validation purposes. */
        val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()

        /**
         * Derives the [MeshParticipationLifecycleState] from the current Android runtime state.
         *
         * This is the canonical derivation logic — all state snapshot emission and test
         * assertions should use this function rather than re-implementing derivation.
         *
         * Derivation precedence:
         * 1. If cross-device is not allowed → [INACTIVE]
         * 2. If participant health is FAILED or UNKNOWN and not already active → [INACTIVE]
         * 3. If barrier state is WAITING → [BARRIER_WAITING]
         * 4. If barrier state is RELEASED → [BARRIER_RELEASED]
         * 5. If participant health is DEGRADED → [DEGRADED]
         * 6. If a rollout constraint is active → [CONSTRAINED]
         * 7. If a fallback tier is active → [FALLBACK]
         * 8. If participation state is ACTIVE → [ACTIVE]
         * 9. Otherwise → [INACTIVE]
         *
         * @param rollout             Current rollout control snapshot.
         * @param healthState         Current participant health.
         * @param barrierState        Current barrier participation state.
         * @param fallbackActive      Whether a fallback execution tier is currently active.
         * @param participationState  Current host participation state.
         */
        fun derive(
            rollout: RolloutControlSnapshot,
            healthState: ParticipantHealthState,
            barrierState: BarrierParticipationState,
            fallbackActive: Boolean,
            participationState: RuntimeHostDescriptor.HostParticipationState
        ): MeshParticipationLifecycleState {
            if (!rollout.crossDeviceAllowed) return INACTIVE
            if (participationState == RuntimeHostDescriptor.HostParticipationState.INACTIVE) return INACTIVE
            if (healthState == ParticipantHealthState.FAILED) return INACTIVE
            if (barrierState == BarrierParticipationState.WAITING) return BARRIER_WAITING
            if (barrierState == BarrierParticipationState.RELEASED) return BARRIER_RELEASED
            if (healthState == ParticipantHealthState.DEGRADED) return DEGRADED
            if (!rollout.delegatedExecutionAllowed) return CONSTRAINED
            if (fallbackActive) return FALLBACK
            return if (participationState == RuntimeHostDescriptor.HostParticipationState.ACTIVE) ACTIVE
            else INACTIVE
        }
    }
}
