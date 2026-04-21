package com.ufo.galaxy.runtime

/**
 * PR-60 — Explicit hybrid/distributed participant capability model for the Android runtime.
 *
 * Models the current support level of hybrid and distributed execution capabilities on
 * the Android participant side.  This enum makes limitations **explicit and structured**
 * rather than silent: when Android cannot fully support a hybrid or distributed execution
 * request, it reports the specific [HybridParticipantCapability] that limits participation
 * instead of silently downgrading without explanation.
 *
 * ## Motivation
 *
 * Before PR-60, the only hybrid-related response was a silent `sendHybridDegrade` reply
 * with `reason = "hybrid_executor_not_implemented"`.  This was correct but opaque:
 *  - V2 could not distinguish "capability not available" from "not ready" from
 *    "intentionally scoped out".
 *  - Reviewers could not verify which hybrid capabilities were being intentionally deferred
 *    vs accidentally missing.
 *
 * [HybridParticipantCapability] provides a structured vocabulary that:
 *  1. Makes every limitation explicit and named.
 *  2. Distinguishes "not yet implemented" from "intentionally deferred" from "available".
 *  3. Allows V2 to make informed fallback decisions rather than treating all degrade replies
 *     as equivalent.
 *
 * ## Hybrid execution model
 *
 * In the V2 runtime model, "hybrid execution" refers to task dispatch patterns that
 * combine local Android execution with center-side V2 execution:
 *  - **HYBRID_EXECUTE**: V2 requests Android to execute a task that involves
 *    partial-local + partial-remote coordination.
 *  - **Staged mesh subtask**: V2 assigns a subtask within a parallel mesh (supported via
 *    [StagedMeshExecutionTarget] — see [STAGED_MESH_SUBTASK]).
 *  - **Peer-to-peer WebRTC**: Direct Android-to-Android transport for real-time media
 *    or state sync (currently in minimal-compat mode).
 *
 * ## Current Android hybrid capability status
 *
 * | Capability | Status |
 * |---|---|
 * | [STAGED_MESH_SUBTASK] | `AVAILABLE` — fully implemented in [StagedMeshExecutionTarget] |
 * | [PARALLEL_SUBTASK] | `AVAILABLE` — handled via [com.ufo.galaxy.service.GalaxyConnectionService] |
 * | [HYBRID_EXECUTE_FULL] | `NOT_YET_IMPLEMENTED` — degrade reply sent; intentional deferral |
 * | [WEBRTC_PEER_TRANSPORT] | `MINIMAL_COMPAT` — stubs present; not production-ready |
 * | [BARRIER_COORDINATION] | `NOT_YET_IMPLEMENTED` — V2-coordinated; Android not a barrier authority |
 *
 * ## Wire values
 *
 * [wireValue] is the stable serialisable representation used in structured log entries
 * and `hybrid_degrade` reply payloads.  Do **not** rename after this PR ships.
 *
 * @property wireValue Stable lowercase string identifying the capability in structured logs
 *                     and degrade reply payloads.
 * @property supportLevel Current [SupportLevel] for this capability on Android.
 * @property description Human-readable description of the capability and its current status.
 */
enum class HybridParticipantCapability(
    val wireValue: String,
    val supportLevel: SupportLevel,
    val description: String
) {

    /**
     * Full hybrid-execute coordination (inbound `hybrid_execute` from V2 requesting
     * partial-local + partial-remote task execution with V2-side coordination).
     *
     * **Current status**: [SupportLevel.NOT_YET_IMPLEMENTED] — Android sends a
     * structured `hybrid_degrade` reply indicating the hybrid executor is not
     * implemented.  V2 must treat this as an explicit capability unavailability signal
     * and apply its own fallback policy (e.g., full remote execution).
     *
     * **Intentional deferral**: full hybrid-execute requires a dedicated Android-side
     * hybrid executor component that is not yet in scope.  This is NOT an accidental
     * omission; it is a tracked intentional limitation.
     */
    HYBRID_EXECUTE_FULL(
        wireValue = "hybrid_execute_full",
        supportLevel = SupportLevel.NOT_YET_IMPLEMENTED,
        description = "Full hybrid_execute with partial-local + partial-remote V2 coordination: " +
            "degrade reply sent; hybrid executor intentionally deferred"
    ),

    /**
     * Staged mesh subtask participation (inbound `parallel_subtask` within a V2-coordinated
     * mesh session, routed through [StagedMeshExecutionTarget]).
     *
     * **Current status**: [SupportLevel.AVAILABLE] — fully implemented.  Android can
     * accept staged mesh subtasks, execute them via the existing goal-execution pipeline,
     * and return structured [StagedMeshParticipationResult] outcomes.
     */
    STAGED_MESH_SUBTASK(
        wireValue = "staged_mesh_subtask",
        supportLevel = SupportLevel.AVAILABLE,
        description = "Staged mesh subtask via StagedMeshExecutionTarget and parallel_subtask: fully implemented"
    ),

    /**
     * Parallel subtask execution (inbound `parallel_subtask` dispatched directly to Android
     * as a parallel participant in a multi-device execution).
     *
     * **Current status**: [SupportLevel.AVAILABLE] — handled in
     * [com.ufo.galaxy.service.GalaxyConnectionService] via the `handleParallelSubtask` pipeline.
     */
    PARALLEL_SUBTASK(
        wireValue = "parallel_subtask",
        supportLevel = SupportLevel.AVAILABLE,
        description = "Parallel subtask via parallel_subtask pipeline: fully implemented"
    ),

    /**
     * WebRTC peer-to-peer transport for real-time media or state-sync between Android
     * devices in a distributed execution session.
     *
     * **Current status**: [SupportLevel.MINIMAL_COMPAT] — protocol stubs and TURN/ICE
     * configuration are present ([com.ufo.galaxy.webrtc]), but the WebRTC transport is not
     * production-ready for distributed participant use.  V2 should not rely on WebRTC
     * transport availability for correctness-critical flows.
     */
    WEBRTC_PEER_TRANSPORT(
        wireValue = "webrtc_peer_transport",
        supportLevel = SupportLevel.MINIMAL_COMPAT,
        description = "WebRTC P2P transport: stubs present; not production-ready for distributed participant"
    ),

    /**
     * Barrier coordination participation (Android acting as a barrier participant in a V2
     * multi-device barrier/merge coordination protocol).
     *
     * **Current status**: [SupportLevel.NOT_YET_IMPLEMENTED] — Android is a participant,
     * not a barrier authority.  Barrier coordination decisions are made by V2.  Android
     * can report task completion (which V2 uses to evaluate barrier conditions) but does
     * not directly participate in barrier signalling or merge coordination.
     *
     * **Intentional scope constraint**: Android is deliberately excluded from barrier
     * authority to avoid creating a second orchestration authority.
     */
    BARRIER_COORDINATION(
        wireValue = "barrier_coordination",
        supportLevel = SupportLevel.NOT_YET_IMPLEMENTED,
        description = "Barrier/merge coordination: V2-side authority; Android not a barrier participant"
    );

    /**
     * Support level of a [HybridParticipantCapability] on the Android side.
     *
     * @property wireValue Stable lowercase wire value.
     */
    enum class SupportLevel(val wireValue: String) {
        /**
         * Capability is fully implemented and available for use.
         * V2 may rely on this capability being present on Android participants.
         */
        AVAILABLE("available"),

        /**
         * Capability has minimal compatibility stubs (protocol models, logging) but is
         * not production-ready.  V2 must not rely on this for correctness-critical flows.
         */
        MINIMAL_COMPAT("minimal_compat"),

        /**
         * Capability is intentionally deferred to a future PR.  Android responds with a
         * structured limitation reply rather than silently ignoring the request.
         * V2 must apply its own fallback policy on receiving a degrade/limitation reply.
         */
        NOT_YET_IMPLEMENTED("not_yet_implemented")
    }

    companion object {

        /**
         * Returns the [HybridParticipantCapability] matching [wireValue], or `null` when
         * the wire value does not match any known capability.
         *
         * Unknown wire values MUST be tolerated by callers (future capabilities will be
         * added without necessarily incrementing a schema version).
         */
        fun fromWireValue(wireValue: String): HybridParticipantCapability? =
            entries.firstOrNull { it.wireValue == wireValue }

        /**
         * Returns all capabilities that are currently [SupportLevel.AVAILABLE].
         *
         * V2 consumers can query this to know which hybrid capabilities the Android
         * participant currently supports without inspecting individual entries.
         */
        fun availableCapabilities(): List<HybridParticipantCapability> =
            entries.filter { it.supportLevel == SupportLevel.AVAILABLE }

        /**
         * Returns all capabilities that are currently [SupportLevel.NOT_YET_IMPLEMENTED].
         *
         * These are the intentional limitations that reviewers should be able to enumerate
         * as evidence that the deferral is tracked and explicit.
         */
        fun deferredCapabilities(): List<HybridParticipantCapability> =
            entries.filter { it.supportLevel == SupportLevel.NOT_YET_IMPLEMENTED }
    }
}
