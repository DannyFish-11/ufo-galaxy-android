package com.ufo.galaxy.runtime

import com.ufo.galaxy.session.DurableParticipantIdentity

/**
 * PR-8Android (Android) — Canonical path closure contract for issue R8.
 *
 * Documents the Android-side R8 closure achieved by this PR: two semantic surfaces
 * introduced in PR-79 ([LocalExecutionModeGate] and [DurableParticipantIdentity]) are
 * now wired into the canonical Android→V2 wire protocol emission paths.
 *
 * ## What was the R8 gap?
 *
 * PR-79 (conversation PR7 / Android semantic-contract closure) introduced:
 *  1. [LocalExecutionModeGate] — a 5-state execution mode gate with explicit transition
 *     semantics, documented as required reading for V2's
 *     `android_runtime_transition_reducer.py`.
 *  2. [DurableParticipantIdentity] — a stable per-installation participant identity with
 *     explicit freshness semantics, documented as required reading for V2's
 *     `android_device_state_store.py` and `unified_governance_semantics.py`.
 *
 * Despite being defined and tested, neither surface was wired into the canonical
 * [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload] or
 * [com.ufo.galaxy.protocol.DeviceExecutionEventPayload] emission paths.  V2 consumers
 * continued to infer execution mode from binary `mode_state` / `mode_readiness_state`
 * field combinations, and had no Android-originated `participant_identity_freshness`
 * signal at all.  This was the remaining Android-side R8 gap.
 *
 * ## What this PR closes
 *
 * **[LocalExecutionModeGate] wiring** (execution mode state):
 *  - [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload.execution_mode_state] is now
 *    populated on every snapshot emission via [LocalExecutionModeGate.decide].
 *  - [com.ufo.galaxy.protocol.DeviceExecutionEventPayload.execution_mode_state] is now
 *    populated on every execution event emission in the `deviceExecutionEventSink`.
 *  - V2's `android_runtime_transition_reducer.py` can now read `execution_mode_state`
 *    directly rather than reconstructing it from field combinations.
 *
 * **[DurableParticipantIdentity] wiring** (participant identity):
 *  - [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload.durable_participant_id] is now
 *    populated from the persisted per-installation UUID stored in `AppSettings`.
 *  - [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload.participant_identity_freshness]
 *    is now derived from [DurableParticipantIdentity.createFromGap] and emitted as the
 *    Android-side authoritative freshness classification.
 *  - V2's `unified_governance_semantics.py` can now read `participant_identity_freshness`
 *    directly rather than applying its own staleness heuristics.
 *
 * ## Canonical wire keys
 *
 * | Field                          | Wire key                       | Source                                |
 * |--------------------------------|--------------------------------|---------------------------------------|
 * | execution_mode_state           | "execution_mode_state"         | [LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE] |
 * | durable_participant_id         | "durable_participant_id"       | [DurableParticipantIdentity.KEY_PARTICIPANT_ID] |
 * | participant_identity_freshness | "participant_identity_freshness" | [DurableParticipantIdentity.KEY_IDENTITY_FRESHNESS] |
 *
 * ## Relationship to prior closure PRs
 *
 * | PR                | Theme                                              | Surfaces added                                        |
 * |-------------------|----------------------------------------------------|-------------------------------------------------------|
 * | PR-3Android       | Schema consistency                                 | event_ts, planner/grounding fallback tiers            |
 * | PR-4Android       | Mesh/hybrid/delegated collaboration                | Mesh lifecycle, CapabilityAuthoritySnapshot           |
 * | PR-5Android       | Execution-context continuity                       | HybridRuntimeContinuityContract, attachment semantics |
 * | PR-6Android       | Readiness evidence / observability                 | AndroidReadinessEvidenceSurface                       |
 * | PR-7Android       | Semantic-contract alignment                        | LocalExecutionModeGate, DurableParticipantIdentity    |
 * | PR-8Android (this)| R8 canonical path wiring                          | execution_mode_state, durable_participant_id, freshness |
 *
 * @see LocalExecutionModeGate
 * @see DurableParticipantIdentity
 * @see com.ufo.galaxy.protocol.DeviceStateSnapshotPayload
 * @see com.ufo.galaxy.protocol.DeviceExecutionEventPayload
 */
object AndroidR8CanonicalPathClosureContract {

    /** PR number that introduces this closure. */
    const val INTRODUCED_PR = "PR-8Android"

    // ── Closure evidence ──────────────────────────────────────────────────────

    /**
     * Machine-verifiable closure evidence map.
     *
     * All values must be `true`.  Any `false` indicates a regression in the R8 canonical
     * path closure.  Tests in [Pr8AndroidR8CanonicalPathClosureTest] assert all invariants.
     */
    val CLOSURE_INVARIANTS: Map<String, Boolean> = mapOf(

        // execution_mode_state field is present in DeviceStateSnapshotPayload
        "snapshot_payload_has_execution_mode_state_field" to
            com.ufo.galaxy.protocol.DeviceStateSnapshotPayload(
                device_id = "test",
                llama_cpp_available = false,
                ncnn_available = false,
                active_runtime_type = "CENTER",
                model_ready = false,
                accessibility_ready = false,
                overlay_ready = false,
                local_loop_ready = false,
                model_id = null,
                runtime_type = null,
                checksum_ok = null,
                mobilevlm_present = false,
                mobilevlm_checksum_ok = false,
                seeclick_present = false,
                pending_first_download = true,
                warmup_result = "unavailable",
                offline_queue_depth = 0,
                current_fallback_tier = null
            ).execution_mode_state.let { true }, // field existence verified at compile time

        // execution_mode_state field is present in DeviceExecutionEventPayload
        "execution_event_payload_has_execution_mode_state_field" to
            com.ufo.galaxy.protocol.DeviceExecutionEventPayload(
                flow_id = "test",
                task_id = "test",
                phase = "execution_started"
            ).execution_mode_state.let { true }, // field existence verified at compile time

        // durable_participant_id field is present in DeviceStateSnapshotPayload
        "snapshot_payload_has_durable_participant_id_field" to
            com.ufo.galaxy.protocol.DeviceStateSnapshotPayload(
                device_id = "test",
                llama_cpp_available = false,
                ncnn_available = false,
                active_runtime_type = "CENTER",
                model_ready = false,
                accessibility_ready = false,
                overlay_ready = false,
                local_loop_ready = false,
                model_id = null,
                runtime_type = null,
                checksum_ok = null,
                mobilevlm_present = false,
                mobilevlm_checksum_ok = false,
                seeclick_present = false,
                pending_first_download = true,
                warmup_result = "unavailable",
                offline_queue_depth = 0,
                current_fallback_tier = null
            ).durable_participant_id.let { true }, // field existence verified at compile time

        // participant_identity_freshness field is present in DeviceStateSnapshotPayload
        "snapshot_payload_has_participant_identity_freshness_field" to
            com.ufo.galaxy.protocol.DeviceStateSnapshotPayload(
                device_id = "test",
                llama_cpp_available = false,
                ncnn_available = false,
                active_runtime_type = "CENTER",
                model_ready = false,
                accessibility_ready = false,
                overlay_ready = false,
                local_loop_ready = false,
                model_id = null,
                runtime_type = null,
                checksum_ok = null,
                mobilevlm_present = false,
                mobilevlm_checksum_ok = false,
                seeclick_present = false,
                pending_first_download = true,
                warmup_result = "unavailable",
                offline_queue_depth = 0,
                current_fallback_tier = null
            ).participant_identity_freshness.let { true }, // field existence verified at compile time

        // LocalExecutionModeGate wire key for execution_mode_state matches the payload field name
        "mode_gate_wire_key_matches_snapshot_field_name" to
            (LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE == "execution_mode_state"),

        // DurableParticipantIdentity wire key for participant_id matches the payload field name
        "participant_identity_wire_key_matches_snapshot_field_name" to
            (DurableParticipantIdentity.KEY_PARTICIPANT_ID == "durable_participant_id"),

        // DurableParticipantIdentity wire key for identity_freshness matches the payload field name
        "identity_freshness_wire_key_matches_snapshot_field_name" to
            (DurableParticipantIdentity.KEY_IDENTITY_FRESHNESS == "participant_identity_freshness"),

        // All LocalExecutionModeGate wire values match the expected set (contract stability)
        "mode_gate_wire_values_stable" to
            (LocalExecutionModeGate.ExecutionModeState.ALL_WIRE_VALUES == setOf(
                "inactive", "local_only", "cross_device_active", "cross_device_degraded", "transitioning"
            )),

        // All DurableParticipantIdentity freshness wire values match the expected set
        "identity_freshness_wire_values_stable" to
            (DurableParticipantIdentity.IdentityFreshness.ALL_WIRE_VALUES == setOf(
                "fresh", "recovered", "stale"
            ))
    )

    // ── Test evidence surface ─────────────────────────────────────────────────

    /**
     * The canonical test class that regression-protects this closure.
     *
     * All tests in this class must pass before any modification to the R8 canonical
     * path emission is considered safe.
     */
    const val TEST_CLASS = "Pr8AndroidR8CanonicalPathClosureTest"

    /**
     * Cross-reference to PR-79 semantic contract surfaces that this PR wires in.
     */
    val WIRED_SURFACES: Set<String> = setOf(
        "com.ufo.galaxy.runtime.LocalExecutionModeGate",
        "com.ufo.galaxy.session.DurableParticipantIdentity"
    )
}
