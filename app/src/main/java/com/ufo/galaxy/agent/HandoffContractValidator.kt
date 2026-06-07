package com.ufo.galaxy.agent

/**
 * **Canonical Android-side handoff contract validator** (PR-9, post-#533 dual-repo
 * runtime unification master plan — Canonical Cross-Repo Delegated-Runtime
 * Handoff Contract Foundations, Android side).
 *
 * [HandoffContractValidator] is the single authoritative component responsible for
 * deciding whether an inbound [TakeoverRequestEnvelope] carries the minimum required
 * fields to constitute a valid delegated-runtime handoff contract on the Android side.
 *
 * ## Design intent
 *
 * Three distinct gate checks exist in the Android delegated-dispatch path:
 *
 * | Gate                          | What it checks                        | Owner                            |
 * |-------------------------------|---------------------------------------|----------------------------------|
 * | Device readiness              | cross-device, goal-execution, a11y…   | [TakeoverEligibilityAssessor]    |
 * | Session state                 | active [AttachedRuntimeSession]        | [DelegatedRuntimeReceiver]       |
 * | **Handoff contract validity** | minimum required contract fields      | **[HandoffContractValidator]**   |
 *
 * [HandoffContractValidator] occupies the third position.  It is intended to run
 * **before** the session gate so that malformed or structurally incomplete envelopes
 * are rejected early with a clear field-level explanation, rather than failing
 * opaquely during domain-model construction.
 *
 * ## Typical call site
 * ```kotlin
 * val validator = HandoffContractValidator()
 * val contractValidity = validator.validate(envelope)
 * if (contractValidity.isInvalid) {
 *     // reject with contractValidity.summary()
 * }
 * ```
 *
 * ## What is validated
 *
 * ### Required identifier fields (must be non-blank)
 *  - [TakeoverRequestEnvelope.takeover_id]
 *  - [TakeoverRequestEnvelope.task_id]
 *  - [TakeoverRequestEnvelope.trace_id]
 *  - [TakeoverRequestEnvelope.goal]
 *
 * ### Coherence checks (may be non-null but must be coherent)
 *  - If [TakeoverRequestEnvelope.handoff_reason] is present it must resolve to a
 *    known [DelegatedHandoffContract.HandoffReason] wire value.
 *  - If [TakeoverRequestEnvelope.originating_formation_role] is present it must
 *    resolve to a known
 *    [com.ufo.galaxy.runtime.RuntimeHostDescriptor.FormationRole] wire value.
 *  - Any value in [TakeoverRequestEnvelope.required_capability_dimensions] that is not
 *    a known [com.ufo.galaxy.capability.AndroidCapabilityVector.ExecutionDimension]
 *    wire value is flagged as an unknown dimension (warning-level problem, does not
 *    invalidate the contract).
 *
 * ## Non-goals
 * This validator does **not** check device readiness, session state, or whether
 * Android actually has the required capabilities.  Those concerns belong to
 * [TakeoverEligibilityAssessor] and [DelegatedRuntimeReceiver] respectively.
 */
class HandoffContractValidator {

    // ── Result types ──────────────────────────────────────────────────────────

    /**
     * Level of a field problem found during validation.
     */
    enum class ProblemLevel {
        /**
         * The field problem is fatal — the contract is structurally invalid and must
         * not be processed.
         */
        ERROR,

        /**
         * The field problem is a warning — the contract can still be processed but
         * the receiving code should degrade gracefully (e.g. unknown dimension values
         * are silently dropped).
         */
        WARNING
    }

    /**
     * A single field-level problem found during contract validation.
     *
     * @property field   The [TakeoverRequestEnvelope] field name (snake_case).
     * @property problem Human-readable description of the problem.
     * @property level   [ProblemLevel.ERROR] when fatal; [ProblemLevel.WARNING] otherwise.
     */
    data class FieldProblem(
        val field: String,
        val problem: String,
        val level: ProblemLevel = ProblemLevel.ERROR
    )

    /**
     * Outcome of a handoff contract validation.
     *
     * @property missingFields  List of required field names that were absent or blank.
     * @property fieldProblems  List of field-level coherence problems (may be warnings).
     */
    data class ValidationResult(
        val missingFields: List<String>,
        val fieldProblems: List<FieldProblem>
    ) {
        /**
         * `true` when the contract is structurally valid: no [missingFields] and no
         * [ProblemLevel.ERROR]-level [fieldProblems].
         */
        val isValid: Boolean
            get() = missingFields.isEmpty() && fieldProblems.none { it.level == ProblemLevel.ERROR }

        /** `true` when [isValid] is `false`. */
        val isInvalid: Boolean
            get() = !isValid

        /**
         * Returns a single-line human-readable summary of this result.
         *
         * Examples:
         *  - `"valid"`
         *  - `"invalid: missing=[takeover_id, goal]"`
         *  - `"invalid: missing=[task_id] problems=[trace_id: blank]"`
         */
        fun summary(): String {
            if (isValid) return "valid"
            val parts = mutableListOf<String>()
            if (missingFields.isNotEmpty()) parts += "missing=${missingFields}"
            val errors = fieldProblems.filter { it.level == ProblemLevel.ERROR }
            if (errors.isNotEmpty()) parts += "problems=${errors.map { "${it.field}: ${it.problem}" }}"
            return "invalid: ${parts.joinToString(" ")}"
        }
    }

    // ── Core validation ───────────────────────────────────────────────────────

    /**
     * Validates the inbound [envelope] against the minimum required handoff contract
     * field set.
     *
     * ## Validation steps
     * 1. **Required identifier fields** — [REQUIRED_IDENTIFIER_FIELDS] must be
     *    non-blank; each absent/blank field is added to [ValidationResult.missingFields].
     * 2. **handoff_reason coherence** — when non-null, the value must be a known
     *    [DelegatedHandoffContract.HandoffReason] wire value; an unknown value is
     *    recorded as an [ProblemLevel.ERROR] [FieldProblem].
     * 3. **originating_formation_role coherence** — when non-null, the value must
     *    resolve via [com.ufo.galaxy.runtime.RuntimeHostDescriptor.FormationRole.fromValue]
     *    to a non-default result (i.e. the raw string must match a known role); an
     *    unrecognised value is recorded as a [ProblemLevel.WARNING] [FieldProblem]
     *    (the contract remains valid — the role will be silently resolved to the
     *    formation-role default).
     * 4. **required_capability_dimensions coherence** — each dimension wire value that
     *    is not recognised by
     *    [com.ufo.galaxy.capability.AndroidCapabilityVector.ExecutionDimension.fromValue]
     *    is recorded as a [ProblemLevel.WARNING] [FieldProblem] (unknown values are
     *    silently dropped by [DelegatedHandoffContract.from]).
     *
     * @param envelope The inbound [TakeoverRequestEnvelope] to validate.
     * @return A [ValidationResult] describing whether the envelope constitutes a valid
     *         handoff contract.
     */
    fun validate(envelope: TakeoverRequestEnvelope): ValidationResult {
        val missing = mutableListOf<String>()
        val problems = mutableListOf<FieldProblem>()

        // ── Step 1: Required identifier fields ────────────────────────────────
        if (envelope.takeover_id.isBlank()) missing += "takeover_id"
        if (envelope.task_id.isBlank()) missing += "task_id"
        if (envelope.trace_id.isBlank()) missing += "trace_id"
        if (envelope.goal.isBlank()) missing += "goal"

        // ── Step 2: handoff_reason coherence ─────────────────────────────────
        val rawReason = envelope.handoff_reason
        if (rawReason != null) {
            val known = DelegatedHandoffContract.HandoffReason.entries.any { it.wireValue == rawReason }
            if (!known) {
                problems += FieldProblem(
                    field = "handoff_reason",
                    problem = "unknown handoff reason wire value: \"$rawReason\"",
                    level = ProblemLevel.ERROR
                )
            }
        }

        // ── Step 3: originating_formation_role coherence ──────────────────────
        val rawRole = envelope.originating_formation_role
        if (rawRole != null) {
            val knownRole = com.ufo.galaxy.runtime.RuntimeHostDescriptor.FormationRole.entries
                .any { it.wireValue == rawRole }
            if (!knownRole) {
                problems += FieldProblem(
                    field = "originating_formation_role",
                    problem = "unrecognised formation role wire value: \"$rawRole\"; " +
                        "will be resolved to default",
                    level = ProblemLevel.WARNING
                )
            }
        }

        // ── Step 4: required_capability_dimensions coherence ──────────────────
        envelope.required_capability_dimensions.forEach { dimValue ->
            val known = com.ufo.galaxy.capability.AndroidCapabilityVector.ExecutionDimension
                .fromValue(dimValue) != null
            if (!known) {
                problems += FieldProblem(
                    field = "required_capability_dimensions",
                    problem = "unrecognised ExecutionDimension wire value: \"$dimValue\"; " +
                        "will be dropped",
                    level = ProblemLevel.WARNING
                )
            }
        }

        return ValidationResult(
            missingFields = missing,
            fieldProblems = problems
        )
    }

    // ── Companion / constants ─────────────────────────────────────────────────

    companion object {

        /**
         * The set of [TakeoverRequestEnvelope] field names that must be non-blank for a
         * contract to be structurally valid.
         *
         * These are the absolute minimum required identifiers; all other fields are
         * optional or have sensible defaults.
         */
        val REQUIRED_IDENTIFIER_FIELDS: Set<String> = setOf(
            "takeover_id",
            "task_id",
            "trace_id",
            "goal"
        )
    }
}
