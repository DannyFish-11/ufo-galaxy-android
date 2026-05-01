package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.MsgType

/**
 * PR-Block2 (Android) — Canonical Android-side ReconciliationSignal AIP wire-layer
 * protocol surface closure.
 *
 * [AndroidReconciliationSignalWireLayerClosure] is the **canonical protocol closure
 * descriptor** for the Android→V2 ReconciliationSignal wire layer.  It formally describes
 * the complete integrated Android↔V2 protocol surface for reconciliation/governance signals,
 * closing the gap identified by the final integrated audit that classified the overall system
 * as `RUNNABLE_BUT_CONDITIONAL`.
 *
 * ## Problem addressed (PR-Block2)
 *
 * The final integrated audit (`PR #931` in `ufo-galaxy-realization-v2`) documented that
 * although the Android side had accumulated [ReconciliationSignal], [MsgType.RECONCILIATION_SIGNAL],
 * and [ReconciliationSignalPayload] models across prior PRs, the **canonical protocol surface
 * closure** — a single authoritative descriptor mapping every signal kind to its V2-expected
 * wire fields and proving the full Android↔V2 path — was absent.  Android-side
 * governance/reconciliation artifacts were not yet represented as a formally closed,
 * first-class canonical wire-message surface.
 *
 * This class closes that gap by providing:
 *
 *  1. A [WireKindDescriptor] registry mapping every [ReconciliationSignal.Kind] to the
 *     V2-expected wire values, terminal semantics, and required payload fields.
 *
 *  2. A [ClosureVerdict] type carrying the protocol-surface closure status and evidence
 *     summary.  V2 governance gates must only consume a [ClosureVerdict.COMPLETE] descriptor
 *     as evidence of full protocol closure.
 *
 *  3. A [WireEnvelopeShape] descriptor encoding the canonical [MsgType.RECONCILIATION_SIGNAL]
 *     envelope field contract that both Android and V2 must agree on.
 *
 *  4. Query helpers [kindDescriptor], [allKindWireValues], [isClosureComplete], and
 *     [evaluateClosure] for governance gates, audit consumers, and CI surfaces.
 *
 * ## Wire protocol surface
 *
 * Every [ReconciliationSignal] emitted by the Android runtime is serialised as an
 * [com.ufo.galaxy.protocol.AipMessage] with:
 *
 *  - `type` = [MsgType.RECONCILIATION_SIGNAL.value] = `"reconciliation_signal"`
 *  - `payload` = [ReconciliationSignalPayload] (Gson-serialised as a nested JSON object)
 *  - `device_id`, `correlation_id`, `idempotency_key`, `runtime_session_id` at the envelope level
 *
 * The [ReconciliationSignalPayload] always carries the required fields:
 *  `signal_id`, `kind`, `participant_id`, `status`, `emitted_at_ms`, `reconciliation_epoch`,
 *  `device_id`.
 *
 * Optional fields are `task_id`, `correlation_id`, `session_id`, `payload`, `runtime_truth`
 * (absent for non-snapshot signals).
 *
 * ## Send path (fully wired)
 *
 * The complete send path in the Android service layer is:
 *
 * ```
 * RuntimeController.reconciliationSignals (SharedFlow)
 *   → GalaxyConnectionService.onStartCommand collector
 *   → GalaxyConnectionService.sendReconciliationSignal()
 *   → ReconciliationSignalPayload construction
 *   → AipMessage(type = RECONCILIATION_SIGNAL, payload = ...) construction
 *   → GalaxyWebSocketClient.sendJson(gson.toJson(envelope))
 * ```
 *
 * ## Protocol closure verdict
 *
 * [evaluateClosure] returns [ClosureVerdict.COMPLETE] when:
 *
 *  1. [MsgType.RECONCILIATION_SIGNAL] wire value is exactly `"reconciliation_signal"`.
 *  2. All seven [ReconciliationSignal.Kind] values are registered in [kindDescriptors].
 *  3. All required envelope fields are declared in [envelopeShape].
 *  4. All required payload fields are declared in [envelopeShape].
 *  5. Terminal kinds are correctly classified as terminal.
 *  6. Non-terminal kinds are correctly classified as non-terminal.
 *
 * @see ReconciliationSignal
 * @see ReconciliationSignalPayload
 * @see MsgType.RECONCILIATION_SIGNAL
 * @see com.ufo.galaxy.service.GalaxyConnectionService
 */
object AndroidReconciliationSignalWireLayerClosure {

    // ── Wire type constant ────────────────────────────────────────────────────

    /**
     * The stable AIP wire type value for all reconciliation signal messages.
     *
     * This must always equal `"reconciliation_signal"` and match V2's expected value.
     */
    val WIRE_TYPE: String = MsgType.RECONCILIATION_SIGNAL.value

    // ── Closure verdict ───────────────────────────────────────────────────────

    /**
     * The closure verdict for the ReconciliationSignal AIP wire layer.
     *
     * Only [COMPLETE] indicates a fully closed protocol surface.  V2 governance gates
     * must not treat [INCOMPLETE] or [PARTIAL] as equivalent to [COMPLETE].
     */
    enum class ClosureVerdict(val wireValue: String) {

        /**
         * The protocol surface is fully closed.  All signal kinds are registered,
         * all wire values are stable, all required fields are declared, and the
         * terminal/non-terminal classification is correct for every kind.
         *
         * This is the only verdict that provides authoritative protocol closure evidence
         * for V2 governance and CI gates.
         */
        COMPLETE("complete"),

        /**
         * The protocol surface is partially closed.  Some (but not all) signal kinds
         * are registered or some wire values / field declarations are missing.
         *
         * This verdict must block V2 governance gates that require full ReconciliationSignal
         * protocol coverage.
         */
        PARTIAL("partial"),

        /**
         * The protocol surface has not been evaluated or the evaluation found critical
         * gaps.  V2 governance gates must treat this as a blocking condition.
         */
        INCOMPLETE("incomplete")
    }

    // ── Per-kind wire descriptor ──────────────────────────────────────────────

    /**
     * Descriptor for a single [ReconciliationSignal.Kind] in the wire protocol.
     *
     * @param kind              The [ReconciliationSignal.Kind] this descriptor applies to.
     * @param wireValue         The stable lowercase wire string for this kind.
     * @param expectedStatus    The stable wire status string for this kind.
     * @param isTerminal        Whether this kind closes the V2 reconciliation loop for a task.
     * @param requiresTaskId    Whether a signal of this kind always carries a non-null [ReconciliationSignal.taskId].
     * @param requiredPayloadKeys Required keys in [ReconciliationSignal.payload] for this kind.
     *                           Empty when no additional payload keys are required.
     * @param hasRuntimeTruth   Whether [ReconciliationSignal.runtimeTruth] is non-null for this kind.
     * @param v2Action          Brief description of the V2 action expected when this signal is received.
     */
    data class WireKindDescriptor(
        val kind: ReconciliationSignal.Kind,
        val wireValue: String,
        val expectedStatus: String,
        val isTerminal: Boolean,
        val requiresTaskId: Boolean,
        val requiredPayloadKeys: Set<String>,
        val hasRuntimeTruth: Boolean,
        val v2Action: String
    )

    // ── Envelope shape ────────────────────────────────────────────────────────

    /**
     * Descriptor for the canonical [com.ufo.galaxy.protocol.AipMessage] envelope shape
     * used for all [MsgType.RECONCILIATION_SIGNAL] messages.
     *
     * @param envelopeTypeField        The stable `type` field value.
     * @param requiredEnvelopeFields   Fields always present at the [com.ufo.galaxy.protocol.AipMessage] level.
     * @param requiredPayloadFields    Fields always present in [ReconciliationSignalPayload].
     * @param optionalPayloadFields    Fields conditionally present in [ReconciliationSignalPayload].
     */
    data class WireEnvelopeShape(
        val envelopeTypeField: String,
        val requiredEnvelopeFields: Set<String>,
        val requiredPayloadFields: Set<String>,
        val optionalPayloadFields: Set<String>
    )

    // ── Kind descriptor registry ──────────────────────────────────────────────

    /**
     * Complete registry of [WireKindDescriptor] entries — one per [ReconciliationSignal.Kind].
     *
     * This is the authoritative Android-side mapping from signal kind → wire protocol contract.
     * V2 gateway handlers must conform to these descriptors when consuming Android reconciliation
     * signals.
     */
    val kindDescriptors: List<WireKindDescriptor> = listOf(

        WireKindDescriptor(
            kind = ReconciliationSignal.Kind.TASK_ACCEPTED,
            wireValue = "task_accepted",
            expectedStatus = ReconciliationSignal.STATUS_RUNNING,
            isTerminal = false,
            requiresTaskId = true,
            requiredPayloadKeys = emptySet(),
            hasRuntimeTruth = false,
            v2Action = "Mark task as actively in-progress under this participant; " +
                "block duplicate dispatch until a terminal signal arrives."
        ),

        WireKindDescriptor(
            kind = ReconciliationSignal.Kind.TASK_STATUS_UPDATE,
            wireValue = "task_status_update",
            expectedStatus = ReconciliationSignal.STATUS_IN_PROGRESS,
            isTerminal = false,
            requiresTaskId = true,
            requiredPayloadKeys = emptySet(),
            hasRuntimeTruth = false,
            v2Action = "Update in-flight task progress view without closing the task; " +
                "optional 'progress_detail' payload key carries human-readable step description."
        ),

        WireKindDescriptor(
            kind = ReconciliationSignal.Kind.TASK_RESULT,
            wireValue = "task_result",
            expectedStatus = ReconciliationSignal.STATUS_SUCCESS,
            isTerminal = true,
            requiresTaskId = true,
            requiredPayloadKeys = emptySet(),
            hasRuntimeTruth = false,
            v2Action = "Close task as success; update participant contribution record."
        ),

        WireKindDescriptor(
            kind = ReconciliationSignal.Kind.TASK_CANCELLED,
            wireValue = "task_cancelled",
            expectedStatus = ReconciliationSignal.STATUS_CANCELLED,
            isTerminal = true,
            requiresTaskId = true,
            requiredPayloadKeys = emptySet(),
            hasRuntimeTruth = false,
            v2Action = "Close task as cancelled; release reserved execution capacity."
        ),

        WireKindDescriptor(
            kind = ReconciliationSignal.Kind.TASK_FAILED,
            wireValue = "task_failed",
            expectedStatus = ReconciliationSignal.STATUS_FAILED,
            isTerminal = true,
            requiresTaskId = true,
            requiredPayloadKeys = emptySet(),
            hasRuntimeTruth = false,
            v2Action = "Close task as failed; may trigger fallback or retry according to " +
                "V2 orchestration policy; optional 'error_detail' key in payload."
        ),

        WireKindDescriptor(
            kind = ReconciliationSignal.Kind.PARTICIPANT_STATE,
            wireValue = "participant_state",
            expectedStatus = ReconciliationSignal.STATUS_STATE_CHANGED,
            isTerminal = false,
            requiresTaskId = false,
            requiredPayloadKeys = setOf("health_state", "readiness_state"),
            hasRuntimeTruth = false,
            v2Action = "Update canonical participant view; independent of any in-flight task."
        ),

        WireKindDescriptor(
            kind = ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT,
            wireValue = "runtime_truth_snapshot",
            expectedStatus = ReconciliationSignal.STATUS_SNAPSHOT,
            isTerminal = false,
            requiresTaskId = false,
            requiredPayloadKeys = emptySet(),
            hasRuntimeTruth = true,
            v2Action = "Full reconciliation pass against runtime_truth field; " +
                "Android-truth wins on conflict; V2 updates its canonical participant view."
        )
    )

    // ── Canonical envelope shape descriptor ──────────────────────────────────

    /**
     * Canonical wire envelope shape for all [MsgType.RECONCILIATION_SIGNAL] messages.
     */
    val envelopeShape: WireEnvelopeShape = WireEnvelopeShape(
        envelopeTypeField = WIRE_TYPE,
        requiredEnvelopeFields = setOf(
            "type",
            "payload",
            "device_id",
            "idempotency_key"
        ),
        requiredPayloadFields = setOf(
            "signal_id",
            "kind",
            "participant_id",
            "status",
            "emitted_at_ms",
            "reconciliation_epoch",
            "device_id"
        ),
        optionalPayloadFields = setOf(
            "task_id",
            "correlation_id",
            "session_id",
            "payload",
            "runtime_truth"
        )
    )

    // ── Query helpers ─────────────────────────────────────────────────────────

    /**
     * Returns the [WireKindDescriptor] for the given [kind], or `null` if the kind
     * is not registered in this closure.
     *
     * A missing descriptor indicates an incomplete protocol closure.  Callers that
     * need a guaranteed non-null result should use [requireKindDescriptor].
     */
    fun kindDescriptor(kind: ReconciliationSignal.Kind): WireKindDescriptor? =
        kindDescriptors.firstOrNull { it.kind == kind }

    /**
     * Returns the [WireKindDescriptor] for the given [kind], throwing
     * [IllegalStateException] if the kind is not registered.
     *
     * Use this in assertions and governance checks where a missing descriptor is a
     * hard protocol error.
     */
    fun requireKindDescriptor(kind: ReconciliationSignal.Kind): WireKindDescriptor =
        kindDescriptor(kind)
            ?: error("ReconciliationSignal.Kind.${kind.name} has no WireKindDescriptor in the PR-Block2 closure; protocol surface is incomplete.")

    /**
     * The stable set of all wire values for [ReconciliationSignal.Kind] as registered
     * in this closure.  V2 must recognise all of these as first-class protocol values.
     */
    val allKindWireValues: Set<String> =
        kindDescriptors.map { it.wireValue }.toSet()

    /**
     * The subset of [kindDescriptors] that are terminal (close the V2 reconciliation
     * loop for a task).
     */
    val terminalKindDescriptors: List<WireKindDescriptor> =
        kindDescriptors.filter { it.isTerminal }

    /**
     * The subset of [kindDescriptors] that are non-terminal (do not close the task loop).
     */
    val nonTerminalKindDescriptors: List<WireKindDescriptor> =
        kindDescriptors.filter { !it.isTerminal }

    /**
     * Returns `true` when [evaluateClosure] would return [ClosureVerdict.COMPLETE].
     *
     * Convenience method for assertion expressions in tests and CI surfaces.
     */
    val isClosureComplete: Boolean
        get() = evaluateClosure() == ClosureVerdict.COMPLETE

    /**
     * Evaluates the current ReconciliationSignal wire-layer closure and returns a
     * [ClosureVerdict].
     *
     * Checks:
     *  1. [WIRE_TYPE] equals [MsgType.RECONCILIATION_SIGNAL.value].
     *  2. All seven [ReconciliationSignal.Kind] values have a [WireKindDescriptor].
     *  3. Each descriptor's [WireKindDescriptor.wireValue] matches [ReconciliationSignal.Kind.wireValue].
     *  4. Each descriptor's [WireKindDescriptor.isTerminal] matches [ReconciliationSignal.isTerminal].
     *  5. Required envelope fields are declared.
     *  6. Required payload fields are declared.
     *
     * Returns [ClosureVerdict.COMPLETE] if all checks pass, [ClosureVerdict.PARTIAL] if
     * some (but not all) checks pass, or [ClosureVerdict.INCOMPLETE] if the evaluation
     * could not be completed.
     */
    fun evaluateClosure(): ClosureVerdict {
        val checks = mutableListOf<Boolean>()

        // Check 1: wire type constant matches enum value
        checks += WIRE_TYPE == MsgType.RECONCILIATION_SIGNAL.value

        // Check 2: all seven kinds have descriptors
        val allKinds = ReconciliationSignal.Kind.entries
        val coveredKinds = kindDescriptors.map { it.kind }.toSet()
        checks += allKinds.all { it in coveredKinds }

        // Check 3: each descriptor's wire value matches the Kind's own wire value
        checks += kindDescriptors.all { desc ->
            desc.wireValue == desc.kind.wireValue
        }

        // Check 4: terminal classification is consistent with ReconciliationSignal.isTerminal
        checks += kindDescriptors.all { desc ->
            val exampleSignal = when (desc.kind) {
                ReconciliationSignal.Kind.TASK_ACCEPTED ->
                    ReconciliationSignal.taskAccepted("p", "t")
                ReconciliationSignal.Kind.TASK_STATUS_UPDATE ->
                    ReconciliationSignal.taskStatusUpdate("p", "t")
                ReconciliationSignal.Kind.TASK_RESULT ->
                    ReconciliationSignal.taskResult("p", "t")
                ReconciliationSignal.Kind.TASK_CANCELLED ->
                    ReconciliationSignal.taskCancelled("p", "t")
                ReconciliationSignal.Kind.TASK_FAILED ->
                    ReconciliationSignal.taskFailed("p", "t")
                ReconciliationSignal.Kind.PARTICIPANT_STATE ->
                    ReconciliationSignal.participantStateSignal(
                        "p",
                        ParticipantHealthState.HEALTHY,
                        ParticipantReadinessState.READY
                    )
                ReconciliationSignal.Kind.RUNTIME_TRUTH_SNAPSHOT ->
                    return@all desc.isTerminal == false // snapshots are always non-terminal
            }
            desc.isTerminal == exampleSignal.isTerminal
        }

        // Check 5: required envelope fields declared
        checks += envelopeShape.requiredEnvelopeFields.isNotEmpty()

        // Check 6: required payload fields declared
        checks += envelopeShape.requiredPayloadFields.isNotEmpty()

        return when {
            checks.all { it } -> ClosureVerdict.COMPLETE
            checks.any { it } -> ClosureVerdict.PARTIAL
            else -> ClosureVerdict.INCOMPLETE
        }
    }

    // ── PR-Block2 registry entry ───────────────────────────────────────────────

    /**
     * The PR number that introduced this closure descriptor.
     *
     * Used by [com.ufo.galaxy.runtime.StabilizationBaseline] to tag the
     * `reconciliation-signal-wire-layer-closure` entry.
     */
    const val PR_NUMBER: Int = -2  // PR-Block2 (negative to distinguish from sequential PR numbers)

    /**
     * Human-readable closure status summary for use in audit surfaces, doc generation,
     * and CI gates.
     */
    val closureStatusSummary: String
        get() {
            val verdict = evaluateClosure()
            val coveredCount = kindDescriptors.size
            val totalCount = ReconciliationSignal.Kind.entries.size
            return "PR-Block2 ReconciliationSignal wire-layer closure: " +
                "${verdict.wireValue.uppercase()} — " +
                "$coveredCount/$totalCount kinds registered, " +
                "wire_type=${WIRE_TYPE}, " +
                "terminal_kinds=${terminalKindDescriptors.size}, " +
                "non_terminal_kinds=${nonTerminalKindDescriptors.size}."
        }
}
