package com.ufo.galaxy.runtime

/**
 * PR-04 — Android mesh participation runtime contract.
 *
 * Closes Android-side mesh participation as a **stable runtime lifecycle contract** rather
 * than a loose capability declaration.  This object is the canonical authority for:
 *
 *  1. Deriving the current [MeshParticipationLifecycleState] from live runtime state.
 *  2. Deriving the current [BarrierParticipationState] and mapping barrier events to
 *     participation lifecycle transitions.
 *  3. Deriving the current [CollaborationLifecycleState] from subtask execution outcomes.
 *  4. Building a structured [MeshRuntimeStateReport] for V2-facing state payloads.
 *  5. Computing constrained reasons for degraded/fallback/constrained participation paths.
 *
 * ## Design principles
 *
 *  - **Android is a participant, not an authority**: V2 owns mesh session lifecycle,
 *    barrier coordination, and convergence.  Android reports its local state; V2 decides.
 *  - **Wire-aligned semantics**: all state values use stable [wireValue] strings that
 *    map to the V2 mesh runtime state contract.
 *  - **No silent downgrade**: when participation is limited, the reason is always
 *    captured in [MeshRuntimeStateReport.constrainedReasons].
 *
 * ## Relationship to [AndroidMeshParticipationContract]
 *
 * [AndroidMeshParticipationContract] models Android's **capability readiness** (READY /
 * PARTIAL / DEFERRED) based on capability availability and rollout gates.  This object
 * models Android's **runtime participation lifecycle** — the dynamic state of Android
 * as a live mesh participant during execution.  The two contracts are complementary:
 *  - Capability readiness determines whether Android *can* participate.
 *  - Runtime lifecycle describes where Android *is* in the participation flow.
 *
 * @see MeshParticipationLifecycleState
 * @see BarrierParticipationState
 * @see CollaborationLifecycleState
 * @see AndroidMeshParticipationContract
 */
object AndroidMeshParticipationRuntimeContract {

    /**
     * Structured Android-side mesh runtime state report for V2-facing payloads.
     *
     * Included in [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload] via the wire fields
     * [mesh_participation_lifecycle_state], [barrier_participation_state],
     * [collaboration_lifecycle_state], and [mesh_constrained_reasons].
     *
     * @property participationLifecycle Current position in the mesh participation lifecycle.
     * @property barrierState           Current barrier participation state.
     * @property collaborationLifecycle Current collaboration lifecycle state.
     * @property constrainedReasons     Non-empty when participation is limited;
     *                                  each entry is a machine-readable reason string.
     */
    data class MeshRuntimeStateReport(
        val participationLifecycle: MeshParticipationLifecycleState,
        val barrierState: BarrierParticipationState,
        val collaborationLifecycle: CollaborationLifecycleState,
        val constrainedReasons: List<String>
    ) {

        /**
         * Returns a stable wire-map for inclusion in structured log entries and
         * V2-facing diagnostic surfaces.
         */
        fun toWireMap(): Map<String, Any> = buildMap {
            put(KEY_PARTICIPATION_LIFECYCLE, participationLifecycle.wireValue)
            put(KEY_BARRIER_STATE, barrierState.wireValue)
            put(KEY_COLLABORATION_LIFECYCLE, collaborationLifecycle.wireValue)
            put(KEY_CONSTRAINED_REASONS, constrainedReasons)
        }
    }

    // ── Wire keys ────────────────────────────────────────────────────────────

    const val KEY_PARTICIPATION_LIFECYCLE = "mesh_participation_lifecycle_state"
    const val KEY_BARRIER_STATE = "barrier_participation_state"
    const val KEY_COLLABORATION_LIFECYCLE = "collaboration_lifecycle_state"
    const val KEY_CONSTRAINED_REASONS = "mesh_constrained_reasons"

    // ── Constrained-reason constants ─────────────────────────────────────────

    const val REASON_CROSS_DEVICE_NOT_ALLOWED = "cross_device_not_allowed"
    const val REASON_DELEGATED_EXECUTION_NOT_ALLOWED = "delegated_execution_not_allowed"
    const val REASON_HEALTH_DEGRADED = "participant_health_degraded"
    const val REASON_HEALTH_RECOVERING = "participant_health_recovering"
    const val REASON_HEALTH_FAILED = "participant_health_failed"
    const val REASON_FALLBACK_ACTIVE = "fallback_execution_tier_active"
    const val REASON_BARRIER_TIMED_OUT = "barrier_wait_timed_out"
    const val REASON_NOT_ACTIVE_PARTICIPANT = "not_active_participant"

    // ── Center-side mesh state alignment ─────────────────────────────────────

    /**
     * Canonical mapping of [MeshParticipationLifecycleState] to center-side mesh state
     * semantics.  This alignment ensures that V2's mesh runtime model and Android's
     * participation lifecycle state use a consistent vocabulary.
     *
     * V2 consumers should treat these semantics as stable contracts between the Android
     * participant-side report and the center-side mesh runtime state aggregator.
     */
    val CENTER_SIDE_ALIGNMENT: Map<String, String> = mapOf(
        MeshParticipationLifecycleState.INACTIVE.wireValue to
            "v2_mesh_node_absent_or_not_yet_joined",
        MeshParticipationLifecycleState.JOINING.wireValue to
            "v2_mesh_node_join_in_progress",
        MeshParticipationLifecycleState.ACTIVE.wireValue to
            "v2_mesh_node_active_participant_accepting_subtasks",
        MeshParticipationLifecycleState.BARRIER_WAITING.wireValue to
            "v2_mesh_node_at_barrier_awaiting_coordinator_release",
        MeshParticipationLifecycleState.BARRIER_RELEASED.wireValue to
            "v2_mesh_node_barrier_cleared_execution_resuming",
        MeshParticipationLifecycleState.DEGRADED.wireValue to
            "v2_mesh_node_degraded_reduced_dispatch_priority_recommended",
        MeshParticipationLifecycleState.CONSTRAINED.wireValue to
            "v2_mesh_node_constrained_by_rollout_or_policy_gate",
        MeshParticipationLifecycleState.FALLBACK.wireValue to
            "v2_mesh_node_on_fallback_execution_path",
        MeshParticipationLifecycleState.LEAVING.wireValue to
            "v2_mesh_node_leaving_session_no_new_assignments"
    )

    // ── Report derivation ─────────────────────────────────────────────────────

    /**
     * Derives the current [MeshRuntimeStateReport] from live Android runtime state.
     *
     * This is the canonical entry point for state snapshot emission and test assertions.
     *
     * @param rollout              Current rollout control snapshot.
     * @param healthState          Current participant health state.
     * @param barrierState         Current barrier participation state.
     * @param collaborationState   Current collaboration lifecycle state.
     * @param fallbackActive       Whether a fallback execution tier is active.
     * @param participationState   Current host participation state.
     */
    fun derive(
        rollout: RolloutControlSnapshot,
        healthState: ParticipantHealthState,
        barrierState: BarrierParticipationState,
        collaborationState: CollaborationLifecycleState,
        fallbackActive: Boolean,
        participationState: RuntimeHostDescriptor.HostParticipationState
    ): MeshRuntimeStateReport {

        val lifecycleState = MeshParticipationLifecycleState.derive(
            rollout = rollout,
            healthState = healthState,
            barrierState = barrierState,
            fallbackActive = fallbackActive,
            participationState = participationState
        )

        val constrainedReasons = buildConstrainedReasons(
            rollout = rollout,
            healthState = healthState,
            barrierState = barrierState,
            fallbackActive = fallbackActive,
            participationState = participationState,
            lifecycleState = lifecycleState
        )

        return MeshRuntimeStateReport(
            participationLifecycle = lifecycleState,
            barrierState = barrierState,
            collaborationLifecycle = collaborationState,
            constrainedReasons = constrainedReasons
        )
    }

    // ── Barrier response helpers ──────────────────────────────────────────────

    /**
     * Returns the [MeshParticipationLifecycleState] transition triggered by a barrier
     * wait event.  Android calls this to determine its participation lifecycle state after
     * receiving a barrier signal from V2.
     *
     * The transition is:
     *  - Any active state → [MeshParticipationLifecycleState.BARRIER_WAITING]
     *  - Inactive state → remains inactive (a barrier wait signal on an inactive node
     *    is anomalous; log a warning and return INACTIVE).
     */
    fun onBarrierWait(
        currentLifecycle: MeshParticipationLifecycleState
    ): MeshParticipationLifecycleState {
        return if (MeshParticipationLifecycleState.isAcceptingSubtasks(currentLifecycle)) {
            MeshParticipationLifecycleState.BARRIER_WAITING
        } else {
            currentLifecycle
        }
    }

    /**
     * Returns the [MeshParticipationLifecycleState] transition triggered by a barrier
     * release event from V2.
     *
     * The transition is:
     *  - [BARRIER_WAITING] → [MeshParticipationLifecycleState.BARRIER_RELEASED]
     *  - Any other state → unchanged (release on non-waiting node is a no-op).
     */
    fun onBarrierRelease(
        currentLifecycle: MeshParticipationLifecycleState
    ): MeshParticipationLifecycleState {
        return if (currentLifecycle == MeshParticipationLifecycleState.BARRIER_WAITING) {
            MeshParticipationLifecycleState.BARRIER_RELEASED
        } else {
            currentLifecycle
        }
    }

    /**
     * Returns the [MeshParticipationLifecycleState] transition triggered by a barrier
     * timeout event (Android gave up waiting for a V2 release signal).
     *
     * The transition is:
     *  - [BARRIER_WAITING] → [MeshParticipationLifecycleState.FALLBACK]
     *    (barrier timed out; Android falls back to local execution if available)
     *  - Any other state → unchanged.
     */
    fun onBarrierTimeout(
        currentLifecycle: MeshParticipationLifecycleState
    ): MeshParticipationLifecycleState {
        return if (currentLifecycle == MeshParticipationLifecycleState.BARRIER_WAITING) {
            MeshParticipationLifecycleState.FALLBACK
        } else {
            currentLifecycle
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildConstrainedReasons(
        rollout: RolloutControlSnapshot,
        healthState: ParticipantHealthState,
        barrierState: BarrierParticipationState,
        fallbackActive: Boolean,
        participationState: RuntimeHostDescriptor.HostParticipationState,
        lifecycleState: MeshParticipationLifecycleState
    ): List<String> = buildList {
        if (!rollout.crossDeviceAllowed) add(REASON_CROSS_DEVICE_NOT_ALLOWED)
        if (!rollout.delegatedExecutionAllowed) add(REASON_DELEGATED_EXECUTION_NOT_ALLOWED)
        when (healthState) {
            ParticipantHealthState.DEGRADED -> add(REASON_HEALTH_DEGRADED)
            ParticipantHealthState.RECOVERING -> add(REASON_HEALTH_RECOVERING)
            ParticipantHealthState.FAILED -> add(REASON_HEALTH_FAILED)
            else -> Unit
        }
        if (fallbackActive) add(REASON_FALLBACK_ACTIVE)
        if (barrierState == BarrierParticipationState.TIMED_OUT) add(REASON_BARRIER_TIMED_OUT)
        if (participationState != RuntimeHostDescriptor.HostParticipationState.ACTIVE &&
            lifecycleState != MeshParticipationLifecycleState.INACTIVE
        ) {
            add(REASON_NOT_ACTIVE_PARTICIPANT)
        }
    }
}
