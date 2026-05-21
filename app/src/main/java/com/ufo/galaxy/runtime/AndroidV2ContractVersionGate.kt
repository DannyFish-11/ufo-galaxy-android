package com.ufo.galaxy.runtime

import com.ufo.galaxy.protocol.MsgType

/**
 * PR-14Android — Minimal Android ↔ V2 contract schema / version gate.
 *
 * Establishes machine-verifiable version identifiers and count anchors for the most critical
 * Android ↔ V2 cross-repo contract boundaries:
 *
 *  1. **AIP_MSG_TYPE_SCHEMA** — AIP envelope protocol / version constants + MsgType count anchor.
 *  2. **RUNTIME_TRUTH_UPLINK** — canonical runtime truth + distributed truth ownership schema versions.
 *  3. **COMPLETION_CLOSURE_UPLINK** — completion / closure uplink schema version and key-name stability.
 *  4. **PARTICIPANT_TRUTH_SNAPSHOT** — result uplink boundary + participation semantic schema versions.
 *
 * ## Purpose
 *
 * Existing Android contracts define boundary classes and wire-field vocabularies, but there was no
 * central gate to verify:
 *  - the AIP MsgType set has not grown or shrunk without a version bump;
 *  - AIP envelope protocol / version strings are pinned and drift-free;
 *  - runtime truth and completion/closure contract schema versions match V2 expectations;
 *  - participant truth snapshot schema versions are stable.
 *
 * This contract closes those gaps via:
 *  1. [GATE_SCHEMA_VERSION] — bumped when any gated contract boundary changes.
 *  2. [V2_EXPECTED_VERSIONS] — per-boundary map declaring what V2 must expect for each schema version.
 *  3. [EXPECTED_MSG_TYPE_COUNT] — count anchor; test fails when MsgType grows/shrinks unintentionally.
 *  4. [CRITICAL_CONTRACT_BOUNDARIES] — registered gate entries covering all four boundary categories.
 *  5. [validate] — returns [GateValidationResult] that CI can assert == [GateValidationStatus.PASSED].
 *  6. [GATE_INVARIANTS] — human+machine-readable invariants preventing silent drift.
 *
 * ## What this gate is NOT
 *
 * This gate is Android-local and Android-bounded.  It asserts Android-side shape stability, not
 * V2-side canonical truth.  It does NOT:
 *  - override V2's canonical truth or governance authority;
 *  - replace V2's schema registry;
 *  - claim Android is a canonical truth authority for any cross-repo decision.
 *
 * ## Version increment rules
 *
 * **Increment [GATE_SCHEMA_VERSION] AND update [V2_EXPECTED_VERSIONS] when:**
 *  - A gated contract's own SCHEMA_VERSION constant changes.
 *  - A critical wire key name is renamed or removed.
 *  - [EXPECTED_MSG_TYPE_COUNT] must change because a MsgType was added/removed.
 *  - [CANONICAL_AIP_PROTOCOL_VERSION] or [CANONICAL_AIP_SPEC_VERSION] must change.
 *
 * **Do NOT increment for:**
 *  - Adding optional payload fields that do not affect existing wire-key names.
 *  - Documentation-only changes to this file.
 *  - New non-gated contracts that do not belong to the four boundary categories above.
 *
 * @see AndroidCompletionClosureUplinkContract
 * @see AndroidResultUplinkBoundaryContract
 * @see AndroidDistributedTruthOwnershipUplinkContract
 * @see AndroidParticipationSemanticNormalizationContract
 */
object AndroidV2ContractVersionGate {

    // ── Gate-level schema version ─────────────────────────────────────────────

    /**
     * Top-level gate schema version.  Bump this value AND update [V2_EXPECTED_VERSIONS] whenever
     * any gated contract boundary changes in a way that V2 must be aware of.
     *
     * Current value: "1" (initial gate established in PR-14Android).
     *
     * V2 integration note: V2's android_contract_version_gate.py must compare the value
     * emitted at [V2_GATE_VERSION_KEY] in the device_register or capability_report metadata
     * against the expected value stored in V2's contract registry.  A mismatch signals that
     * the Android side has drifted and V2's consumption logic must be updated.
     */
    const val GATE_SCHEMA_VERSION: String = "1"

    /**
     * Wire key used to emit [GATE_SCHEMA_VERSION] in cross-repo payloads.
     * V2 MUST read this key from any payload that carries contract version metadata.
     */
    const val V2_GATE_VERSION_KEY: String = "android_v2_contract_gate_schema_version"

    // ── AIP envelope version constants ────────────────────────────────────────

    /**
     * The canonical AIP protocol identifier.  Matches [com.ufo.galaxy.protocol.AipMessage.protocol].
     * V2 gateway rejects any inbound message where protocol != this constant.
     * Any change here requires a simultaneous V2 gateway update.
     */
    const val CANONICAL_AIP_PROTOCOL_VERSION: String = "AIP/1.0"

    /**
     * The canonical AIP spec version.  Matches [com.ufo.galaxy.protocol.AipMessage.version].
     * V2 gateway rejects any inbound message where version < "3.0".
     * Any change here requires a simultaneous V2 gateway update.
     */
    const val CANONICAL_AIP_SPEC_VERSION: String = "3.0"

    // ── AIP MsgType count anchor ──────────────────────────────────────────────

    /**
     * The expected number of [com.ufo.galaxy.protocol.MsgType] enum entries at gate introduction.
     *
     * This count is a machine-verifiable drift anchor:
     *  - If [MsgType.entries.size] > [EXPECTED_MSG_TYPE_COUNT], a new type was added without
     *    bumping [GATE_SCHEMA_VERSION].
     *  - If [MsgType.entries.size] < [EXPECTED_MSG_TYPE_COUNT], a type was removed without
     *    bumping [GATE_SCHEMA_VERSION].
     *
     * In both cases the gate test fails, forcing a conscious [GATE_SCHEMA_VERSION] bump and V2
     * alignment review.
     *
     * **To add a new MsgType:** increment [EXPECTED_MSG_TYPE_COUNT] by the number of new entries,
     * bump [GATE_SCHEMA_VERSION] to the next value, update [V2_EXPECTED_VERSIONS], and document the
     * V2 side changes required.
     */
    const val EXPECTED_MSG_TYPE_COUNT: Int = 54

    // ── Per-boundary schema version constants ─────────────────────────────────

    /**
     * Expected schema version for [AndroidCompletionClosureUplinkContract].
     * V2's completion_ingress and execution_evidence_model must read this version to detect drift.
     */
    const val EXPECTED_COMPLETION_CLOSURE_SCHEMA_VERSION: String = "1"

    /**
     * Expected schema version for [AndroidResultUplinkBoundaryContract].
     * V2's task_result_canonical_truth_chain and acceptance_adjudication must validate against this.
     */
    const val EXPECTED_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION: String = "1"

    /**
     * Expected schema version for [AndroidDistributedTruthOwnershipUplinkContract].
     * V2's task_result_canonical_truth_chain and handoff_governance_chain must validate against this.
     */
    const val EXPECTED_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION: String = "1"

    /**
     * Expected schema version for [AndroidParticipationSemanticNormalizationContract].
     * V2's android_device_state_store must validate against this for participation dispatch.
     */
    const val EXPECTED_PARTICIPATION_SEMANTIC_SCHEMA_VERSION: String = "1"

    /**
     * Expected schema version for [AndroidDistributedRuntimeParticipationBoundaryContract].
     * V2's distributed runtime participation chain must validate against this.
     */
    const val EXPECTED_DISTRIBUTED_RUNTIME_PARTICIPATION_SCHEMA_VERSION: String = "1"

    // ── Critical wire key stability anchors ───────────────────────────────────

    /**
     * The stable wire key for the authority runtime completion signal class.
     * Defined by [AndroidCompletionClosureUplinkContract.KEY_AUTHORITY_RUNTIME_COMPLETION_SIGNAL_CLASS].
     * V2 MUST read this exact key from goal_result and device_execution_event uplinks.
     */
    const val KEY_AUTHORITY_RUNTIME_COMPLETION_SIGNAL_CLASS: String =
        "authority_runtime_completion_signal_class"

    /**
     * The stable wire key for the result completion signal class.
     * Defined by [AndroidCompletionClosureUplinkContract.KEY_RESULT_COMPLETION_SIGNAL_CLASS].
     */
    const val KEY_RESULT_COMPLETION_SIGNAL_CLASS: String = "result_completion_signal_class"

    /**
     * The stable wire key for the closure finalization signal class.
     * Defined by [AndroidCompletionClosureUplinkContract.KEY_CLOSURE_FINALIZATION_SIGNAL_CLASS].
     */
    const val KEY_CLOSURE_FINALIZATION_SIGNAL_CLASS: String = "closure_finalization_signal_class"

    /**
     * The stable wire key for the completion closure uplink schema version.
     * Defined by [AndroidCompletionClosureUplinkContract.KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION].
     */
    const val KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION: String =
        "completion_closure_uplink_schema_version"

    /**
     * The stable wire key for the result signal class.
     * Defined by [AndroidResultUplinkBoundaryContract.KEY_RESULT_SIGNAL_CLASS].
     */
    const val KEY_RESULT_SIGNAL_CLASS: String = "result_signal_class"

    /**
     * The stable wire key for the acceptance candidate class.
     * Defined by [AndroidResultUplinkBoundaryContract.KEY_ACCEPTANCE_CANDIDATE_CLASS].
     */
    const val KEY_ACCEPTANCE_CANDIDATE_CLASS: String = "acceptance_candidate_class"

    /**
     * The stable wire key for the result uplink boundary schema version.
     * Defined by [AndroidResultUplinkBoundaryContract.KEY_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION].
     */
    const val KEY_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION: String =
        "result_uplink_boundary_schema_version"

    /**
     * The stable wire key for the distributed truth ownership uplink schema version.
     * Defined by [AndroidDistributedTruthOwnershipUplinkContract.KEY_SCHEMA_VERSION].
     */
    const val KEY_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION: String =
        "distributed_truth_ownership_uplink_schema_version"

    /**
     * The stable wire key for the participation semantic schema version.
     * Defined by [AndroidParticipationSemanticNormalizationContract.KEY_SCHEMA_VERSION].
     */
    const val KEY_PARTICIPATION_SEMANTIC_SCHEMA_VERSION: String =
        "participation_semantic_schema_version"

    // ── Gate boundary categories ──────────────────────────────────────────────

    /**
     * The four critical Android ↔ V2 boundary categories gated by this contract.
     *
     * Any drift in any of these categories without a [GATE_SCHEMA_VERSION] bump is a
     * cross-repo contract violation.
     */
    enum class GateBoundaryCategory(val wireValue: String) {
        /**
         * AIP message type / payload schema:
         * MsgType enum count, AIP envelope protocol version, AIP spec version.
         */
        AIP_MSG_TYPE_SCHEMA("aip_msg_type_schema"),

        /**
         * Android runtime truth uplink shape:
         * canonical runtime truth + distributed truth ownership uplink fields.
         */
        RUNTIME_TRUTH_UPLINK("runtime_truth_uplink"),

        /**
         * Completion / closure uplink shape:
         * authority runtime completion + result completion + closure finalization fields.
         */
        COMPLETION_CLOSURE_UPLINK("completion_closure_uplink"),

        /**
         * Participant truth snapshot shape:
         * result uplink boundary + participation semantic normalization fields.
         */
        PARTICIPANT_TRUTH_SNAPSHOT("participant_truth_snapshot")
    }

    // ── Gate boundary entry ───────────────────────────────────────────────────

    /**
     * A single gate boundary entry declaring which contract it protects and the current expected
     * schema version.
     *
     * @param category          The [GateBoundaryCategory] this entry belongs to.
     * @param contractName      Kotlin object or class name that owns the boundary.
     * @param expectedVersion   The schema version Android currently emits for this boundary.
     * @param wireVersionKey    The wire key used to emit the schema version in uplink payloads.
     * @param v2ConsumptionPath The V2 module/function that must be updated when the version changes.
     * @param breakingChangeMustSyncV2 Whether a breaking change in this boundary requires a
     *                          simultaneous V2 update before Android can be merged.
     */
    data class GateBoundaryEntry(
        val category: GateBoundaryCategory,
        val contractName: String,
        val expectedVersion: String,
        val wireVersionKey: String,
        val v2ConsumptionPath: String,
        val breakingChangeMustSyncV2: Boolean = true
    )

    // ── Critical contract boundaries registry ────────────────────────────────

    /**
     * The canonical registry of all gated contract boundaries.
     *
     * Tests MUST assert that this list contains exactly one entry per [GateBoundaryCategory],
     * and that [GateBoundaryCategory.values().size] equals the number of distinct categories
     * present.
     */
    val CRITICAL_CONTRACT_BOUNDARIES: List<GateBoundaryEntry> = listOf(

        // ── 1. AIP message type schema ────────────────────────────────────────
        GateBoundaryEntry(
            category = GateBoundaryCategory.AIP_MSG_TYPE_SCHEMA,
            contractName = "MsgType",
            expectedVersion = GATE_SCHEMA_VERSION,
            wireVersionKey = V2_GATE_VERSION_KEY,
            v2ConsumptionPath =
                "galaxy_gateway/routes/websocket.py + galaxy_gateway/android_bridge.py: " +
                    "MsgType wire-value registry; MsgType count anchor = $EXPECTED_MSG_TYPE_COUNT",
            breakingChangeMustSyncV2 = true
        ),

        // ── 2. Runtime truth uplink ───────────────────────────────────────────
        GateBoundaryEntry(
            category = GateBoundaryCategory.RUNTIME_TRUTH_UPLINK,
            contractName = "AndroidDistributedTruthOwnershipUplinkContract",
            expectedVersion = EXPECTED_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION,
            wireVersionKey = KEY_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION,
            v2ConsumptionPath =
                "core/task_result_canonical_truth_chain.py (authority_signal_class), " +
                    "core/handoff_governance_chain.py (ownership_uplink_class), " +
                    "board/operator_perception_surface.py (summary_projection routing), " +
                    "core/android_device_state_store.py (diagnostics_audit storage)"
        ),

        // ── 3. Completion / closure uplink ────────────────────────────────────
        GateBoundaryEntry(
            category = GateBoundaryCategory.COMPLETION_CLOSURE_UPLINK,
            contractName = "AndroidCompletionClosureUplinkContract",
            expectedVersion = EXPECTED_COMPLETION_CLOSURE_SCHEMA_VERSION,
            wireVersionKey = KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION,
            v2ConsumptionPath =
                "core/canonical_completion_ingress.py (handoff/completion response binding), " +
                    "core/execution_evidence_model.py (trusted/provisional/quarantine/rejected classes)"
        ),

        // ── 4. Participant truth snapshot ─────────────────────────────────────
        GateBoundaryEntry(
            category = GateBoundaryCategory.PARTICIPANT_TRUTH_SNAPSHOT,
            contractName = "AndroidResultUplinkBoundaryContract",
            expectedVersion = EXPECTED_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION,
            wireVersionKey = KEY_RESULT_SIGNAL_CLASS,
            v2ConsumptionPath =
                "core/task_result_canonical_truth_chain.py (result_signal_class → task close), " +
                    "core/acceptance_adjudication.py (acceptance_candidate_class → acceptance gate), " +
                    "core/multi_device_truth_convergence.py (participation/formation/session domains)"
        )
    )

    // ── V2 expected version alignment map ────────────────────────────────────

    /**
     * Per-boundary map of what V2 must expect for each critical schema version constant.
     *
     * V2-side contract registries must match these expected values.  A mismatch at runtime
     * indicates Android ↔ V2 schema drift that must be resolved before the next deployment.
     *
     * Keys are stable wire-key strings.  Values are the expected schema version strings.
     */
    val V2_EXPECTED_VERSIONS: Map<String, String> = mapOf<String, String>(
        KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION to
            EXPECTED_COMPLETION_CLOSURE_SCHEMA_VERSION,
        KEY_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION to
            EXPECTED_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION,
        KEY_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION to
            EXPECTED_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION,
        KEY_PARTICIPATION_SEMANTIC_SCHEMA_VERSION to
            EXPECTED_PARTICIPATION_SEMANTIC_SCHEMA_VERSION,
        V2_GATE_VERSION_KEY to GATE_SCHEMA_VERSION
    )

    // ── Gate validation ───────────────────────────────────────────────────────

    /** Overall gate validation status. */
    enum class GateValidationStatus { PASSED, FAILED }

    /**
     * A single gate validation violation.
     *
     * @param boundary      The boundary category that failed validation.
     * @param description   Human-readable description of the violation.
     * @param expected      The expected value.
     * @param actual        The actual value observed.
     */
    data class GateValidationViolation(
        val boundary: String,
        val description: String,
        val expected: String,
        val actual: String
    )

    /** Result of a [validate] call. */
    data class GateValidationResult(
        val status: GateValidationStatus,
        val violations: List<GateValidationViolation>
    )

    /**
     * Validates that all gated contract constants are internally consistent.
     *
     * Checks:
     *  1. [EXPECTED_MSG_TYPE_COUNT] matches actual [MsgType.entries.size].
     *  2. [AndroidCompletionClosureUplinkContract.SCHEMA_VERSION] == [EXPECTED_COMPLETION_CLOSURE_SCHEMA_VERSION].
     *  3. [AndroidResultUplinkBoundaryContract.SCHEMA_VERSION] == [EXPECTED_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION].
     *  4. [AndroidDistributedTruthOwnershipUplinkContract.SCHEMA_VERSION] ==
     *     [EXPECTED_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION].
     *  5. [AndroidParticipationSemanticNormalizationContract.SCHEMA_VERSION] ==
     *     [EXPECTED_PARTICIPATION_SEMANTIC_SCHEMA_VERSION].
     *  6. Wire key names emitted by the referenced contracts match the local anchor constants.
     *  7. [CRITICAL_CONTRACT_BOUNDARIES] covers all four [GateBoundaryCategory] values.
     *
     * Returns [GateValidationResult] with [GateValidationStatus.PASSED] when no violations are found.
     * CI-gate pattern: `assertEquals(GateValidationStatus.PASSED, validate().status)`
     */
    fun validate(): GateValidationResult {
        val violations = mutableListOf<GateValidationViolation>()

        // ── 1. MsgType count ─────────────────────────────────────────────────
        val actualMsgTypeCount = MsgType.entries.size
        if (actualMsgTypeCount != EXPECTED_MSG_TYPE_COUNT) {
            violations += GateValidationViolation(
                boundary = GateBoundaryCategory.AIP_MSG_TYPE_SCHEMA.wireValue,
                description = "MsgType count drift: bump GATE_SCHEMA_VERSION, update " +
                    "EXPECTED_MSG_TYPE_COUNT, and align V2 MsgType registry",
                expected = EXPECTED_MSG_TYPE_COUNT.toString(),
                actual = actualMsgTypeCount.toString()
            )
        }

        // ── 2. Completion / closure uplink schema version ────────────────────
        val actualCompletionVersion = AndroidCompletionClosureUplinkContract.SCHEMA_VERSION
        if (actualCompletionVersion != EXPECTED_COMPLETION_CLOSURE_SCHEMA_VERSION) {
            violations += GateValidationViolation(
                boundary = GateBoundaryCategory.COMPLETION_CLOSURE_UPLINK.wireValue,
                description = "AndroidCompletionClosureUplinkContract.SCHEMA_VERSION drift: " +
                    "bump GATE_SCHEMA_VERSION, update EXPECTED_COMPLETION_CLOSURE_SCHEMA_VERSION, " +
                    "and update core/canonical_completion_ingress.py + execution_evidence_model.py",
                expected = EXPECTED_COMPLETION_CLOSURE_SCHEMA_VERSION,
                actual = actualCompletionVersion
            )
        }

        // ── 3. Result uplink boundary schema version ─────────────────────────
        val actualResultUplinkVersion = AndroidResultUplinkBoundaryContract.SCHEMA_VERSION
        if (actualResultUplinkVersion != EXPECTED_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION) {
            violations += GateValidationViolation(
                boundary = GateBoundaryCategory.PARTICIPANT_TRUTH_SNAPSHOT.wireValue,
                description = "AndroidResultUplinkBoundaryContract.SCHEMA_VERSION drift: " +
                    "bump GATE_SCHEMA_VERSION, update EXPECTED_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION, " +
                    "and update core/task_result_canonical_truth_chain.py + acceptance_adjudication.py",
                expected = EXPECTED_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION,
                actual = actualResultUplinkVersion
            )
        }

        // ── 4. Distributed truth ownership uplink schema version ─────────────
        val actualTruthOwnershipVersion =
            AndroidDistributedTruthOwnershipUplinkContract.SCHEMA_VERSION
        if (actualTruthOwnershipVersion != EXPECTED_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION) {
            violations += GateValidationViolation(
                boundary = GateBoundaryCategory.RUNTIME_TRUTH_UPLINK.wireValue,
                description = "AndroidDistributedTruthOwnershipUplinkContract.SCHEMA_VERSION drift: " +
                    "bump GATE_SCHEMA_VERSION, update EXPECTED_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION, " +
                    "and update core/task_result_canonical_truth_chain.py + handoff_governance_chain.py",
                expected = EXPECTED_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION,
                actual = actualTruthOwnershipVersion
            )
        }

        // ── 5. Participation semantic schema version ──────────────────────────
        val actualParticipationVersion =
            AndroidParticipationSemanticNormalizationContract.SCHEMA_VERSION
        if (actualParticipationVersion != EXPECTED_PARTICIPATION_SEMANTIC_SCHEMA_VERSION) {
            violations += GateValidationViolation(
                boundary = GateBoundaryCategory.PARTICIPANT_TRUTH_SNAPSHOT.wireValue,
                description = "AndroidParticipationSemanticNormalizationContract.SCHEMA_VERSION drift: " +
                    "bump GATE_SCHEMA_VERSION, update EXPECTED_PARTICIPATION_SEMANTIC_SCHEMA_VERSION, " +
                    "and update core/android_device_state_store.py participation dispatch logic",
                expected = EXPECTED_PARTICIPATION_SEMANTIC_SCHEMA_VERSION,
                actual = actualParticipationVersion
            )
        }

        // ── 6. Wire key name consistency ─────────────────────────────────────
        val expectedClosureKey =
            AndroidCompletionClosureUplinkContract.KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION
        if (expectedClosureKey != KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION) {
            violations += GateValidationViolation(
                boundary = GateBoundaryCategory.COMPLETION_CLOSURE_UPLINK.wireValue,
                description = "KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION out of sync with " +
                    "AndroidCompletionClosureUplinkContract source",
                expected = expectedClosureKey,
                actual = KEY_COMPLETION_CLOSURE_UPLINK_SCHEMA_VERSION
            )
        }

        val expectedResultKey =
            AndroidResultUplinkBoundaryContract.KEY_RESULT_SIGNAL_CLASS
        if (expectedResultKey != KEY_RESULT_SIGNAL_CLASS) {
            violations += GateValidationViolation(
                boundary = GateBoundaryCategory.PARTICIPANT_TRUTH_SNAPSHOT.wireValue,
                description = "KEY_RESULT_SIGNAL_CLASS out of sync with " +
                    "AndroidResultUplinkBoundaryContract source",
                expected = expectedResultKey,
                actual = KEY_RESULT_SIGNAL_CLASS
            )
        }

        val expectedTruthOwnershipKey =
            AndroidDistributedTruthOwnershipUplinkContract.KEY_SCHEMA_VERSION
        if (expectedTruthOwnershipKey != KEY_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION) {
            violations += GateValidationViolation(
                boundary = GateBoundaryCategory.RUNTIME_TRUTH_UPLINK.wireValue,
                description = "KEY_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION out of sync with " +
                    "AndroidDistributedTruthOwnershipUplinkContract source",
                expected = expectedTruthOwnershipKey,
                actual = KEY_DISTRIBUTED_TRUTH_OWNERSHIP_SCHEMA_VERSION
            )
        }

        val expectedParticipationKey =
            AndroidParticipationSemanticNormalizationContract.KEY_SCHEMA_VERSION
        if (expectedParticipationKey != KEY_PARTICIPATION_SEMANTIC_SCHEMA_VERSION) {
            violations += GateValidationViolation(
                boundary = GateBoundaryCategory.PARTICIPANT_TRUTH_SNAPSHOT.wireValue,
                description = "KEY_PARTICIPATION_SEMANTIC_SCHEMA_VERSION out of sync with " +
                    "AndroidParticipationSemanticNormalizationContract source",
                expected = expectedParticipationKey,
                actual = KEY_PARTICIPATION_SEMANTIC_SCHEMA_VERSION
            )
        }

        // ── 7. All four boundary categories present ───────────────────────────
        val coveredCategories = CRITICAL_CONTRACT_BOUNDARIES.map { it.category }.toSet()
        for (category in GateBoundaryCategory.entries) {
            if (category !in coveredCategories) {
                violations += GateValidationViolation(
                    boundary = category.wireValue,
                    description = "GateBoundaryCategory.${category.name} has no entry in " +
                        "CRITICAL_CONTRACT_BOUNDARIES",
                    expected = "entry present",
                    actual = "missing"
                )
            }
        }

        val status = if (violations.isEmpty()) GateValidationStatus.PASSED
                     else GateValidationStatus.FAILED
        return GateValidationResult(status = status, violations = violations)
    }

    // ── Gate invariants ───────────────────────────────────────────────────────

    /**
     * Machine-readable invariants that must hold at all times.
     *
     * These invariants are tested by [Pr14AndroidV2ContractVersionGateTest]; a test failure
     * signals a drift that must be resolved before merging.
     *
     * | # | Invariant |
     * |---|-----------|
     * | 1 | [GATE_SCHEMA_VERSION] is non-blank and parseable as a version identifier. |
     * | 2 | [EXPECTED_MSG_TYPE_COUNT] equals [MsgType.entries.size] at gate introduction. |
     * | 3 | [CANONICAL_AIP_PROTOCOL_VERSION] equals the [com.ufo.galaxy.protocol.AipMessage] default. |
     * | 4 | [CANONICAL_AIP_SPEC_VERSION] equals the [com.ufo.galaxy.protocol.AipMessage] default. |
     * | 5 | All four [GateBoundaryCategory] values are present in [CRITICAL_CONTRACT_BOUNDARIES]. |
     * | 6 | [validate] returns [GateValidationStatus.PASSED] when no contracts have drifted. |
     * | 7 | [V2_EXPECTED_VERSIONS] contains an entry for every wire key constant defined here. |
     * | 8 | Android is not claiming canonical truth authority; this gate is bounded-side only. |
     */
    val GATE_INVARIANTS: List<String> = listOf(
        "GATE_SCHEMA_VERSION is non-blank and parseable as a version identifier",
        "EXPECTED_MSG_TYPE_COUNT equals MsgType.entries.size at gate introduction",
        "CANONICAL_AIP_PROTOCOL_VERSION equals AipMessage.protocol default (AIP/1.0)",
        "CANONICAL_AIP_SPEC_VERSION equals AipMessage.version default (3.0)",
        "All four GateBoundaryCategory values are present in CRITICAL_CONTRACT_BOUNDARIES",
        "validate() returns GateValidationStatus.PASSED when no contracts have drifted",
        "V2_EXPECTED_VERSIONS contains an entry for every critical wire version key",
        "Android is not claiming canonical truth authority; this gate is bounded-side only"
    )

    /**
     * Count of expected [GATE_INVARIANTS].  Tests compare against this to catch additions or
     * removals without a [GATE_SCHEMA_VERSION] bump.
     */
    const val EXPECTED_GATE_INVARIANT_COUNT: Int = 8
}
