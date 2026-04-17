package com.ufo.galaxy.coordination

import android.util.Log
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.runtime.AttachedRuntimeHostSessionSnapshot
import com.ufo.galaxy.runtime.FormationParticipationRebalancer
import com.ufo.galaxy.runtime.FormationRebalanceEvent
import com.ufo.galaxy.runtime.ParticipantHealthState
import com.ufo.galaxy.runtime.ParticipantReadinessState
import com.ufo.galaxy.runtime.ReconnectRecoveryState
import com.ufo.galaxy.runtime.RuntimeHostDescriptor
import com.ufo.galaxy.runtime.RuntimeIdentityContracts
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * PR-2 — Formation coordination surface for Android runtime rebalance and recovery.
 *
 * This is the canonical Android-side bridge between [RuntimeController][com.ufo.galaxy.runtime.RuntimeController]
 * session / state changes and formation-aware rebalance / recovery behavior.  It:
 *
 *  1. Accepts participant state change notifications from [RuntimeController] (session
 *     open/close, health changes, role reassignment requests).
 *  2. Delegates evaluation to [FormationParticipationRebalancer] to obtain a typed
 *     [FormationParticipationRebalancer.RebalanceDecision] or
 *     [FormationParticipationRebalancer.RoleReassignmentDecision].
 *  3. Emits [FormationRebalanceEvent] instances on the [rebalanceEvents] shared flow so
 *     that any observer (UI, diagnostics, center-side coordinator) can react.
 *  4. Emits structured [GalaxyLogger] entries for every formation-relevant event.
 *
 * ## Authority
 *
 * [FormationCoordinationSurface] is **not** a lifecycle authority.  It does not modify
 * [RuntimeHostDescriptor] values, does not open or close sessions, and does not drive
 * [ReconnectRecoveryState].  All runtime authority remains with [RuntimeController].
 * This surface is purely an **observable coordination bridge**.
 *
 * ## Thread safety
 *
 * All public methods are safe to call from any thread.  The [rebalanceEvents] flow
 * uses an in-memory buffer of [REBALANCE_EVENT_BUFFER_SIZE] entries; if the buffer is full,
 * events are dropped.  Callers that need guaranteed delivery must drain the flow promptly.
 *
 * ## Usage
 *
 * ```kotlin
 * val coordinationSurface = FormationCoordinationSurface()
 *
 * // Collect rebalance events from a coroutine scope:
 * scope.launch {
 *     coordinationSurface.rebalanceEvents.collect { event ->
 *         when (event) {
 *             is FormationRebalanceEvent.ReadinessChanged -> handleReadinessChanged(event)
 *             is FormationRebalanceEvent.ParticipantLost  -> handleParticipantLost(event)
 *             is FormationRebalanceEvent.RecoveryCompleted -> handleRecoveryCompleted(event)
 *             // etc.
 *         }
 *     }
 * }
 *
 * // Notify when a participant's health changes:
 * coordinationSurface.onParticipantHealthChanged(
 *     descriptor = runtimeController.hostDescriptor!!,
 *     newHealthState = ParticipantHealthState.DEGRADED,
 *     reconnectRecoveryState = runtimeController.reconnectRecoveryState.value
 * )
 * ```
 *
 * @param rebalancer Optional [FormationParticipationRebalancer] instance.  Defaults to a
 *                   freshly constructed instance.  Override in tests to inject a fake.
 */
class FormationCoordinationSurface(
    private val rebalancer: FormationParticipationRebalancer = FormationParticipationRebalancer()
) {

    // ── Observable event flow ─────────────────────────────────────────────────

    private val _rebalanceEvents =
        MutableSharedFlow<FormationRebalanceEvent>(extraBufferCapacity = REBALANCE_EVENT_BUFFER_SIZE)

    /**
     * Observable stream of [FormationRebalanceEvent] instances emitted by this surface.
     *
     * Consumers should collect this flow from a coroutine scope scoped to the component's
     * lifetime (e.g. `viewModelScope`, `serviceScope`).  Events are emitted non-suspending
     * and buffered; if the buffer fills the oldest events are dropped.
     */
    val rebalanceEvents: SharedFlow<FormationRebalanceEvent> = _rebalanceEvents.asSharedFlow()

    // ── Public notification API ───────────────────────────────────────────────

    /**
     * Notifies the surface that this participant's health state has changed.
     *
     * Evaluates the new state via [rebalancer] and emits a [FormationRebalanceEvent] if
     * the evaluation determines that a rebalance is required.
     *
     * @param descriptor             Current [RuntimeHostDescriptor] for this device.
     * @param newHealthState         The new [ParticipantHealthState].
     * @param reconnectRecoveryState Current [ReconnectRecoveryState] from [RuntimeController].
     * @param readinessState         Current [ParticipantReadinessState]; defaults to UNKNOWN.
     * @param activeSessionSnapshot  Current session snapshot, if available.
     */
    fun onParticipantHealthChanged(
        descriptor: RuntimeHostDescriptor,
        newHealthState: ParticipantHealthState,
        reconnectRecoveryState: ReconnectRecoveryState,
        readinessState: ParticipantReadinessState = ParticipantReadinessState.UNKNOWN,
        activeSessionSnapshot: AttachedRuntimeHostSessionSnapshot? = null
    ) {
        Log.d(TAG, "[FORMATION] onParticipantHealthChanged: health=${newHealthState.wireValue}")

        val decision = rebalancer.evaluateParticipation(
            descriptor = descriptor,
            healthState = newHealthState,
            reconnectRecoveryState = reconnectRecoveryState,
            readinessState = readinessState,
            activeSessionSnapshot = activeSessionSnapshot
        )

        GalaxyLogger.log(
            GalaxyLogger.TAG_FORMATION_HEALTH,
            mapOf(
                "event" to "participant_health_changed",
                "health_state" to newHealthState.wireValue,
                "requires_rebalance" to decision.requiresRebalance,
                "continuation_mode" to decision.continuationMode.wireValue,
                "rationale" to decision.rationale
            )
        )

        if (decision.requiresRebalance) {
            emitRebalanceEvent(
                event = decision.suggestedEvent
                    ?: FormationRebalanceEvent.ReadinessChanged(
                        previousReadiness = readinessState,
                        currentReadiness = ParticipantReadinessState.UNKNOWN,
                        previousParticipation = descriptor.participationState,
                        currentParticipation = descriptor.participationState,
                        trigger = "health_${newHealthState.wireValue}"
                    ),
                rationale = decision.rationale
            )
        }
    }

    /**
     * Notifies the surface that a reconnect-driven recovery state transition has occurred.
     *
     * Called by [RuntimeController] (or its observers) when [ReconnectRecoveryState] changes.
     * Evaluates the new state and emits appropriate [FormationRebalanceEvent] instances:
     *
     *  - `RECOVERING` → [FormationRebalanceEvent.ReadinessChanged] (participant temporarily unavailable).
     *  - `RECOVERED`  → [FormationRebalanceEvent.ParticipantRejoined] (participant back in formation).
     *  - `FAILED`     → [FormationRebalanceEvent.ReadinessChanged] (participant withdrawn from formation).
     *
     * @param descriptor                 Current [RuntimeHostDescriptor] for this device.
     * @param previousRecoveryState      The [ReconnectRecoveryState] before the transition.
     * @param newRecoveryState           The [ReconnectRecoveryState] after the transition.
     * @param sessionContinuityEpoch     Optional epoch from [DurableSessionContinuityRecord][com.ufo.galaxy.runtime.DurableSessionContinuityRecord]
     *                                   for RECOVERED transitions.
     * @param affectedMeshId             Optional mesh session identifier.
     */
    fun onReconnectRecoveryStateChanged(
        descriptor: RuntimeHostDescriptor,
        previousRecoveryState: ReconnectRecoveryState,
        newRecoveryState: ReconnectRecoveryState,
        sessionContinuityEpoch: Int? = null,
        affectedMeshId: String? = null
    ) {
        Log.d(
            TAG,
            "[FORMATION] onReconnectRecoveryStateChanged: " +
                "${previousRecoveryState.wireValue}→${newRecoveryState.wireValue}"
        )

        when (newRecoveryState) {
            ReconnectRecoveryState.RECOVERING -> {
                emitRebalanceEvent(
                    event = FormationRebalanceEvent.ReadinessChanged(
                        previousReadiness = ParticipantReadinessState.READY,
                        currentReadiness = ParticipantReadinessState.NOT_READY,
                        previousParticipation = descriptor.participationState,
                        currentParticipation = RuntimeHostDescriptor.HostParticipationState.INACTIVE,
                        trigger = "ws_disconnect_active"
                    ),
                    rationale = "WS disconnect: participant temporarily unavailable for formation"
                )
            }

            ReconnectRecoveryState.RECOVERED -> {
                val participantId = RuntimeIdentityContracts.participantNodeId(
                    deviceId = descriptor.deviceId,
                    runtimeHostId = descriptor.hostId
                )
                emitRebalanceEvent(
                    event = FormationRebalanceEvent.ParticipantRejoined(
                        rejoinedParticipantId = participantId,
                        rejoinedDeviceId = descriptor.deviceId,
                        sessionContinuityEpoch = sessionContinuityEpoch,
                        affectedMeshId = affectedMeshId
                    ),
                    rationale = "WS reconnect successful: participant rejoined formation (epoch=$sessionContinuityEpoch)"
                )
            }

            ReconnectRecoveryState.FAILED -> {
                emitRebalanceEvent(
                    event = FormationRebalanceEvent.ReadinessChanged(
                        previousReadiness = ParticipantReadinessState.NOT_READY,
                        currentReadiness = ParticipantReadinessState.NOT_READY,
                        previousParticipation = RuntimeHostDescriptor.HostParticipationState.INACTIVE,
                        currentParticipation = RuntimeHostDescriptor.HostParticipationState.INACTIVE,
                        trigger = "ws_recovery_failed"
                    ),
                    rationale = "WS recovery failed: participant withdrawn from formation"
                )
            }

            ReconnectRecoveryState.IDLE -> Unit // No formation event needed for IDLE.
        }
    }

    /**
     * Notifies the surface that a role reassignment has been requested for this device.
     *
     * Evaluates the request via [rebalancer] and emits a
     * [FormationRebalanceEvent.RoleReassignmentRequested] when the change is accepted or
     * deferred.  When declined permanently, only a diagnostic log entry is emitted (no flow event).
     *
     * @param descriptor             Current [RuntimeHostDescriptor] for this device.
     * @param requestedRole          The new [RuntimeHostDescriptor.FormationRole] being proposed.
     * @param healthState            Current [ParticipantHealthState] of this device.
     * @param reconnectRecoveryState Current [ReconnectRecoveryState] from [RuntimeController].
     * @param requestingCoordinator  Identifier of the entity requesting the reassignment.
     * @param sessionId              Optional session scoping the reassignment.
     * @return The [FormationParticipationRebalancer.RoleReassignmentDecision] produced by the evaluation.
     */
    fun onRoleReassignmentRequested(
        descriptor: RuntimeHostDescriptor,
        requestedRole: RuntimeHostDescriptor.FormationRole,
        healthState: ParticipantHealthState,
        reconnectRecoveryState: ReconnectRecoveryState,
        requestingCoordinator: String,
        sessionId: String? = null
    ): FormationParticipationRebalancer.RoleReassignmentDecision {
        Log.d(
            TAG,
            "[FORMATION] onRoleReassignmentRequested: " +
                "${descriptor.formationRole.wireValue}→${requestedRole.wireValue}"
        )

        val decision = rebalancer.evaluateRoleReassignment(
            descriptor = descriptor,
            requestedRole = requestedRole,
            healthState = healthState,
            reconnectRecoveryState = reconnectRecoveryState
        )

        val outcomeTag = when {
            decision.accepted -> "role_reassignment_accepted"
            decision.deferrable -> "role_reassignment_deferred"
            else -> "role_reassignment_declined"
        }

        GalaxyLogger.log(
            GalaxyLogger.TAG_FORMATION_ROLE,
            buildMap {
                put("event", outcomeTag)
                put("previous_role", decision.previousRole.wireValue)
                put("requested_role", decision.requestedRole.wireValue)
                put("accepted", decision.accepted)
                put("deferrable", decision.deferrable)
                decision.declineReason?.let { put("decline_reason", it) }
            }
        )

        if (decision.accepted) {
            emitRebalanceEvent(
                event = FormationRebalanceEvent.RoleReassignmentRequested(
                    requestedRole = requestedRole,
                    previousRole = descriptor.formationRole,
                    requestingCoordinator = requestingCoordinator,
                    sessionId = sessionId
                ),
                rationale = "Role reassignment accepted: ${descriptor.formationRole.wireValue}→${requestedRole.wireValue}"
            )
        }

        return decision
    }

    /**
     * Notifies the surface that a formation participant was lost (disconnected or removed).
     *
     * Emits a [FormationRebalanceEvent.ParticipantLost] event and, if only one participant
     * remains, also emits a [FormationRebalanceEvent.DegradedFormationDetected] event.
     *
     * @param lostParticipantId     Stable participant identifier of the lost node.
     * @param lostDeviceId          Device identifier of the lost participant.
     * @param detachCause           Wire value of the detach cause.
     * @param presentParticipantCount Number of participants currently present after the loss.
     * @param expectedParticipantCount Number of participants the formation expected.
     * @param affectedMeshId        Optional mesh session identifier.
     */
    fun onParticipantLost(
        lostParticipantId: String,
        lostDeviceId: String,
        detachCause: String,
        presentParticipantCount: Int,
        expectedParticipantCount: Int,
        affectedMeshId: String? = null
    ) {
        Log.w(
            TAG,
            "[FORMATION] onParticipantLost: participant_id=$lostParticipantId " +
                "cause=$detachCause present=$presentParticipantCount expected=$expectedParticipantCount"
        )

        emitRebalanceEvent(
            event = FormationRebalanceEvent.ParticipantLost(
                lostParticipantId = lostParticipantId,
                lostDeviceId = lostDeviceId,
                detachCause = detachCause,
                affectedMeshId = affectedMeshId
            ),
            rationale = "Participant $lostDeviceId lost from formation (cause=$detachCause)"
        )

        if (presentParticipantCount < expectedParticipantCount) {
            emitRebalanceEvent(
                event = FormationRebalanceEvent.DegradedFormationDetected(
                    presentParticipantCount = presentParticipantCount,
                    expectedParticipantCount = expectedParticipantCount,
                    absentParticipantIds = listOf(lostParticipantId),
                    degradedMeshId = affectedMeshId
                ),
                rationale = "Formation degraded: $presentParticipantCount/$expectedParticipantCount participants present"
            )
        }
    }

    /**
     * Notifies the surface that a formation recovery was completed successfully.
     *
     * Emits a [FormationRebalanceEvent.RecoveryCompleted] event.
     *
     * @param restoredParticipantCount Number of participants now present.
     * @param recoveryTrigger          Machine-readable description of what resolved the degraded state.
     * @param affectedMeshId           Optional mesh session identifier.
     */
    fun onRecoveryCompleted(
        restoredParticipantCount: Int,
        recoveryTrigger: String,
        affectedMeshId: String? = null
    ) {
        Log.i(TAG, "[FORMATION] onRecoveryCompleted: trigger=$recoveryTrigger participants=$restoredParticipantCount")

        emitRebalanceEvent(
            event = FormationRebalanceEvent.RecoveryCompleted(
                restoredParticipantCount = restoredParticipantCount,
                recoveryTrigger = recoveryTrigger,
                affectedMeshId = affectedMeshId
            ),
            rationale = "Formation recovery completed: $restoredParticipantCount participants present (trigger=$recoveryTrigger)"
        )
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun emitRebalanceEvent(event: FormationRebalanceEvent, rationale: String) {
        GalaxyLogger.log(
            GalaxyLogger.TAG_FORMATION_REBALANCE,
            mapOf(
                "event" to event.wireValue,
                "rationale" to rationale
            )
        )
        _rebalanceEvents.tryEmit(event)
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "GALAXY:FORMATION:COORD"

        /**
         * Buffer capacity for the [rebalanceEvents] shared flow.
         *
         * Sized to hold enough events to cover a typical burst (e.g. a mesh session with
         * multiple participants all transitioning simultaneously) without overwhelming memory.
         */
        const val REBALANCE_EVENT_BUFFER_SIZE = 16
    }
}
