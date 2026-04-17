package com.ufo.galaxy.runtime

import com.ufo.galaxy.observability.GalaxyLogger

/**
 * PR-2 — Android-side formation participation rebalancer.
 *
 * Evaluates whether the current role, health, and readiness posture of this Android device
 * remains coherent given a changed formation or recovery condition, and produces a
 * [RebalanceDecision] that callers can act on — e.g. by emitting a
 * [FormationRebalanceEvent], updating the [RuntimeHostDescriptor], or signalling the
 * formation coordinator.
 *
 * ## Design intent
 *
 * This class is **stateless and pure**: every [evaluateParticipation] and
 * [evaluateRoleReassignment] call derives its verdict solely from the arguments supplied.
 * There is no hidden mutable state.  Callers own all lifecycle decisions; this class
 * only supplies the evaluation logic.
 *
 * The rebalancer is **not** a lifecycle authority.  [RuntimeController] remains the
 * single owner of session and participation transitions.  The rebalancer's role is to
 * translate raw health / readiness / role signals into actionable [RebalanceDecision]
 * objects that [RuntimeController] and [FormationCoordinationSurface] can use to drive
 * consistent formation behavior.
 *
 * ## Usage
 *
 * ```kotlin
 * val rebalancer = FormationParticipationRebalancer()
 *
 * // Evaluate whether the current participant state requires a rebalance response:
 * val decision = rebalancer.evaluateParticipation(
 *     currentDescriptor = runtimeController.hostDescriptor,
 *     healthState = ParticipantHealthState.DEGRADED,
 *     reconnectRecoveryState = runtimeController.reconnectRecoveryState.value,
 *     activeSessionSnapshot = runtimeController.currentHostSessionSnapshot()
 * )
 * if (decision.requiresRebalance) {
 *     // Act on decision.suggestedEvent
 * }
 *
 * // Evaluate a role reassignment request:
 * val roleDecision = rebalancer.evaluateRoleReassignment(
 *     descriptor = runtimeController.hostDescriptor,
 *     requestedRole = FormationRole.SECONDARY,
 *     healthState = ParticipantHealthState.HEALTHY,
 *     reconnectRecoveryState = runtimeController.reconnectRecoveryState.value
 * )
 * ```
 */
class FormationParticipationRebalancer {

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Evaluates whether the current participant state requires a formation rebalance response.
     *
     * Returns a [RebalanceDecision] containing:
     *  - [RebalanceDecision.requiresRebalance] — whether action is needed.
     *  - [RebalanceDecision.continuationMode] — how execution should continue given
     *    the current state.
     *  - [RebalanceDecision.suggestedEvent] — an optional [FormationRebalanceEvent] to
     *    emit to observers.
     *  - [RebalanceDecision.rationale] — human/operator-readable explanation.
     *
     * @param descriptor             Current [RuntimeHostDescriptor] for this device.
     * @param healthState            Current [ParticipantHealthState] of this device.
     * @param reconnectRecoveryState Current [ReconnectRecoveryState] from [RuntimeController].
     * @param readinessState         Current [ParticipantReadinessState] from the participant model.
     * @param activeSessionSnapshot  Current [AttachedRuntimeHostSessionSnapshot], if any.
     */
    fun evaluateParticipation(
        descriptor: RuntimeHostDescriptor,
        healthState: ParticipantHealthState,
        reconnectRecoveryState: ReconnectRecoveryState,
        readinessState: ParticipantReadinessState = ParticipantReadinessState.UNKNOWN,
        activeSessionSnapshot: AttachedRuntimeHostSessionSnapshot? = null
    ): RebalanceDecision {

        // ── Check 1: WS connectivity recovery in progress ────────────────────
        if (reconnectRecoveryState == ReconnectRecoveryState.RECOVERING) {
            return RebalanceDecision(
                requiresRebalance = true,
                continuationMode = ContinuationMode.DEGRADED_CONTINUATION,
                suggestedEvent = FormationRebalanceEvent.ReadinessChanged(
                    previousReadiness = readinessState,
                    currentReadiness = ParticipantReadinessState.NOT_READY,
                    previousParticipation = descriptor.participationState,
                    currentParticipation = RuntimeHostDescriptor.HostParticipationState.INACTIVE,
                    trigger = "ws_reconnect_in_progress"
                ),
                rationale = "WS reconnect in progress — participant temporarily unavailable"
            )
        }

        // ── Check 2: WS recovery failed ───────────────────────────────────────
        if (reconnectRecoveryState == ReconnectRecoveryState.FAILED) {
            return RebalanceDecision(
                requiresRebalance = true,
                continuationMode = ContinuationMode.WITHDRAW_PARTICIPATION,
                suggestedEvent = FormationRebalanceEvent.ReadinessChanged(
                    previousReadiness = readinessState,
                    currentReadiness = ParticipantReadinessState.NOT_READY,
                    previousParticipation = descriptor.participationState,
                    currentParticipation = RuntimeHostDescriptor.HostParticipationState.INACTIVE,
                    trigger = "ws_recovery_failed"
                ),
                rationale = "WS recovery failed — participant must be withdrawn from formation"
            )
        }

        // ── Check 3: Execution environment health degraded or failed ──────────
        if (ParticipantHealthState.isCompromised(healthState)) {
            val continuationMode = when (healthState) {
                ParticipantHealthState.FAILED -> ContinuationMode.WITHDRAW_PARTICIPATION
                ParticipantHealthState.RECOVERING -> ContinuationMode.AWAIT_HEALTH_RESTORE
                ParticipantHealthState.DEGRADED -> ContinuationMode.DEGRADED_CONTINUATION
                else -> ContinuationMode.CONTINUE_NORMALLY
            }
            return RebalanceDecision(
                requiresRebalance = true,
                continuationMode = continuationMode,
                suggestedEvent = FormationRebalanceEvent.ReadinessChanged(
                    previousReadiness = ParticipantReadinessState.READY,
                    currentReadiness = when (healthState) {
                        ParticipantHealthState.FAILED -> ParticipantReadinessState.NOT_READY
                        ParticipantHealthState.RECOVERING -> ParticipantReadinessState.NOT_READY
                        ParticipantHealthState.DEGRADED -> ParticipantReadinessState.READY_WITH_FALLBACK
                        else -> readinessState
                    },
                    previousParticipation = descriptor.participationState,
                    currentParticipation = when (healthState) {
                        ParticipantHealthState.FAILED -> RuntimeHostDescriptor.HostParticipationState.INACTIVE
                        ParticipantHealthState.RECOVERING -> RuntimeHostDescriptor.HostParticipationState.STANDBY
                        ParticipantHealthState.DEGRADED -> RuntimeHostDescriptor.HostParticipationState.STANDBY
                        else -> descriptor.participationState
                    },
                    trigger = "health_state_${healthState.wireValue}"
                ),
                rationale = "Participant health is ${healthState.wireValue} — formation rebalance required"
            )
        }

        // ── Check 4: Participant is inactive but session suggests it was active ─
        if (descriptor.participationState == RuntimeHostDescriptor.HostParticipationState.INACTIVE
            && activeSessionSnapshot?.isReuseValid == true) {
            GalaxyLogger.log(
                GalaxyLogger.TAG_FORMATION_REBALANCE,
                mapOf(
                    "event" to "participation_state_mismatch",
                    "participation_state" to descriptor.participationState.wireValue,
                    "session_reuse_valid" to true
                )
            )
        }

        // ── No rebalance needed ───────────────────────────────────────────────
        return RebalanceDecision(
            requiresRebalance = false,
            continuationMode = ContinuationMode.CONTINUE_NORMALLY,
            suggestedEvent = null,
            rationale = "Participant state is coherent — no rebalance required"
        )
    }

    /**
     * Evaluates whether this device should accept a requested role reassignment.
     *
     * Returns a [RoleReassignmentDecision] indicating whether the role change should be
     * accepted, declined, or deferred.  A role change is only safe to accept when the
     * device is HEALTHY and its WS connection is stable (IDLE or RECOVERED).
     *
     * @param descriptor             Current [RuntimeHostDescriptor] for this device.
     * @param requestedRole          The new [RuntimeHostDescriptor.FormationRole] being proposed.
     * @param healthState            Current [ParticipantHealthState] of this device.
     * @param reconnectRecoveryState Current [ReconnectRecoveryState] from [RuntimeController].
     */
    fun evaluateRoleReassignment(
        descriptor: RuntimeHostDescriptor,
        requestedRole: RuntimeHostDescriptor.FormationRole,
        healthState: ParticipantHealthState,
        reconnectRecoveryState: ReconnectRecoveryState
    ): RoleReassignmentDecision {

        // Cannot accept a role change while WS recovery is in progress.
        if (reconnectRecoveryState == ReconnectRecoveryState.RECOVERING) {
            return RoleReassignmentDecision(
                accepted = false,
                deferrable = true,
                previousRole = descriptor.formationRole,
                requestedRole = requestedRole,
                declineReason = "ws_reconnect_in_progress"
            )
        }

        // Cannot accept a role change when WS recovery has failed.
        if (reconnectRecoveryState == ReconnectRecoveryState.FAILED) {
            return RoleReassignmentDecision(
                accepted = false,
                deferrable = false,
                previousRole = descriptor.formationRole,
                requestedRole = requestedRole,
                declineReason = "ws_recovery_failed"
            )
        }

        // Cannot safely accept a role change when health is compromised.
        if (healthState == ParticipantHealthState.FAILED) {
            return RoleReassignmentDecision(
                accepted = false,
                deferrable = false,
                previousRole = descriptor.formationRole,
                requestedRole = requestedRole,
                declineReason = "participant_health_failed"
            )
        }
        if (healthState == ParticipantHealthState.RECOVERING) {
            return RoleReassignmentDecision(
                accepted = false,
                deferrable = true,
                previousRole = descriptor.formationRole,
                requestedRole = requestedRole,
                declineReason = "participant_health_recovering"
            )
        }

        // No-op if the requested role is the same as the current role.
        if (requestedRole == descriptor.formationRole) {
            return RoleReassignmentDecision(
                accepted = true,
                deferrable = false,
                previousRole = descriptor.formationRole,
                requestedRole = requestedRole,
                declineReason = null
            )
        }

        // Role change is acceptable — device is healthy and connected.
        return RoleReassignmentDecision(
            accepted = true,
            deferrable = false,
            previousRole = descriptor.formationRole,
            requestedRole = requestedRole,
            declineReason = null
        )
    }

    // ── Result types ──────────────────────────────────────────────────────────

    /**
     * Result of a [evaluateParticipation] call.
     *
     * @property requiresRebalance  `true` when the current state warrants a formation rebalance.
     * @property continuationMode   How execution should proceed given the current state.
     * @property suggestedEvent     Optional [FormationRebalanceEvent] to emit to observers.
     * @property rationale          Human/operator-readable explanation for the decision.
     */
    data class RebalanceDecision(
        val requiresRebalance: Boolean,
        val continuationMode: ContinuationMode,
        val suggestedEvent: FormationRebalanceEvent?,
        val rationale: String
    )

    /**
     * Result of a [evaluateRoleReassignment] call.
     *
     * @property accepted       `true` when the role change is safe to apply immediately.
     * @property deferrable     `true` when the change was declined but could be accepted later
     *                          (e.g. after recovery completes).  `false` when the decline is permanent.
     * @property previousRole   The role before the requested change.
     * @property requestedRole  The role that was proposed.
     * @property declineReason  Machine-readable reason for decline; `null` when [accepted] is `true`.
     */
    data class RoleReassignmentDecision(
        val accepted: Boolean,
        val deferrable: Boolean,
        val previousRole: RuntimeHostDescriptor.FormationRole,
        val requestedRole: RuntimeHostDescriptor.FormationRole,
        val declineReason: String?
    )

    /**
     * How execution should continue given the participant's current state.
     *
     * @property wireValue Stable lowercase string for diagnostics payloads.
     */
    enum class ContinuationMode(val wireValue: String) {
        /** Device is healthy; normal formation participation continues. */
        CONTINUE_NORMALLY("continue_normally"),

        /** Device is compromised but still able to contribute; formation uses degraded path. */
        DEGRADED_CONTINUATION("degraded_continuation"),

        /** Device is recovering; wait until health is restored before assigning new tasks. */
        AWAIT_HEALTH_RESTORE("await_health_restore"),

        /** Device cannot continue in the formation; participation should be withdrawn. */
        WITHDRAW_PARTICIPATION("withdraw_participation")
    }
}
