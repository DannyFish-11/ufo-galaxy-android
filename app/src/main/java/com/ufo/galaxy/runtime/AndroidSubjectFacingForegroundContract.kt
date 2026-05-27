package com.ufo.galaxy.runtime

import com.google.gson.JsonObject

/**
 * PR-129 — Android subject-facing foreground contract.
 *
 * Advances the Android default foreground path from control-plane / scattered-response
 * organisation to **subject-facing foreground**: the primary foreground object is now the
 * subject itself — its current action phase, presence mode, blocker state,
 * confirmation state, and result completion — rather than a flat technical text payload.
 *
 * ## Why this contract exists
 *
 * Prior to this PR the Android default foreground was composed as a `summary` string
 * built from lifecycle stage names, recovery phase strings, and text suffixes appended
 * when a blocker or confirmation was present.  Blocker and confirmation were secondary
 * annotations; the primary expression was a textual runtime description.
 *
 * This contract changes the foreground organisational backbone:
 * - [SubjectFacingForegroundCard] is the new primary foreground object.
 * - [ForegroundPrimaryObject] declares which subject dimension drives the foreground in
 *   any given moment — the `BLOCKER` tier takes priority over `ACTION_PHASE`, which takes
 *   priority over `RESULT`, and so on.
 * - Control-plane / operator metadata (recovery phase strings, reconciliation detail,
 *   participant_state labels) is **not** promoted to a primary foreground dimension; it
 *   remains available in the raw payload but is demoted from the subject-facing card.
 *
 * ## Subject-facing foreground dimensions
 *
 * | Dimension           | Class                   | Foreground priority |
 * |---------------------|-------------------------|---------------------|
 * | Blocker state       | [BlockerState]          | 1 (highest)         |
 * | Confirmation state  | [ConfirmationState]     | 2                   |
 * | Action phase        | [ActionPhase]           | 3                   |
 * | Result state        | [ResultState]           | 4                   |
 * | Presence mode       | [PresenceMode]          | 5 (lowest default)  |
 *
 * The [ForegroundPrimaryObject] value on every [SubjectFacingForegroundCard] reflects
 * this priority ordering.
 *
 * ## Control-plane demotion
 *
 * The following signal types are **excluded** from subject-facing foreground composition:
 * - `recovery_replaying` / `recovery_reconciliation_pending` / `participant_state` stages
 *   → these are runtime-internal; the subject-facing card surfaces them only as
 *   [ActionPhase.RECOVERY] or [ActionPhase.UNKNOWN] without promoting the raw label.
 * - `reconciliation_snapshot` stages → not elevated to a primary foreground dimension.
 * - Any technical blocker without concrete subject-facing evidence (governed by
 *   [AndroidDualRepoHiddenVisibleBoundaryContract]) → excluded from [BlockerState.isBlocked].
 *
 * ## V2 alignment
 *
 * [V2_SUBJECT_FOREGROUND_ALIGNMENT_MAP] maps each [ForegroundPrimaryObject] to the
 * corresponding V2-side foreground handling path, ensuring cross-repo consistency.
 *
 * ## Invariants
 *
 * [SUBJECT_FOREGROUND_INVARIANTS] are machine-verifiable regression anchors. Any change
 * that falsifies an invariant is a foreground governance regression.
 */
object AndroidSubjectFacingForegroundContract {

    const val INTRODUCED_PR = 129
    const val SCHEMA_VERSION = "1.0"

    // ─────────────────────────────────────────────────────────────────────────
    // Subject-facing foreground dimensions
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The current action lifecycle phase of the subject.
     *
     * Maps from [unified_action_lifecycle_surface.stage] in a subject-facing way:
     * control-plane-only stages (reconciliation_snapshot, participant_state) collapse
     * into [UNKNOWN] so they are not promoted to a foreground primary dimension.
     */
    enum class ActionPhase(
        val wireValue: String,
        /**
         * True when this phase is user-visible as a foreground primary dimension.
         * False for purely runtime-internal phases.
         */
        val isSubjectFacing: Boolean
    ) {
        ACCEPTED("accepted", true),
        EXECUTING("executing", true),
        RESULT_EMITTED("result_emitted", true),
        FAILED("failed", true),
        CANCELLED("cancelled", true),
        RECOVERY("recovery", true),
        /**
         * Catch-all for control-plane / runtime-internal stage values that must not be
         * promoted to a subject-facing primary dimension.
         */
        UNKNOWN("unknown", false);

        companion object {
            fun fromStage(stage: String?): ActionPhase = when (stage) {
                "accepted" -> ACCEPTED
                "executing" -> EXECUTING
                "result_emitted" -> RESULT_EMITTED
                "failed" -> FAILED
                "cancelled" -> CANCELLED
                "recovery_replaying",
                "recovery_recovered",
                "recovery_failed",
                "recovery_reconciliation_pending" -> RECOVERY
                // Control-plane / operator-only stages are demoted to UNKNOWN so they do
                // not drive the subject-facing foreground primary object.
                else -> UNKNOWN
            }
        }
    }

    /**
     * The current presence mode of the subject on the Android device.
     *
     * Derived from [AndroidPresenceParticipationProjection] when available; falls back
     * to [UNKNOWN] so callers never need a null check.
     */
    enum class PresenceMode(val wireValue: String) {
        FOREGROUND_ENGAGED("foreground_engaged"),
        BACKGROUND_PARTICIPANT("background_participant"),
        HANDOFF_INTERACTIVE("handoff_interactive"),
        ABSENT("absent"),
        UNKNOWN("unknown");

        companion object {
            fun fromParticipationKind(kind: AndroidPresenceParticipationKind?): PresenceMode =
                when (kind) {
                    AndroidPresenceParticipationKind.FOREGROUND_ENGAGED -> FOREGROUND_ENGAGED
                    AndroidPresenceParticipationKind.BACKGROUND_PARTICIPANT -> BACKGROUND_PARTICIPANT
                    AndroidPresenceParticipationKind.HANDOFF_INTERACTIVE -> HANDOFF_INTERACTIVE
                    AndroidPresenceParticipationKind.ABSENT -> ABSENT
                    null -> UNKNOWN
                }
        }
    }

    /**
     * The blocker state of the subject as a first-class foreground object.
     *
     * [isBlocked] reflects the boundary-adjudicated foreground blocker value (from
     * [AndroidDualRepoHiddenVisibleBoundaryContract]): a blocker without concrete
     * subject-facing evidence is NOT promoted ([isBlocked]=false).
     */
    data class BlockerState(
        val isBlocked: Boolean,
        val reason: String?
    ) {
        companion object {
            val NONE = BlockerState(isBlocked = false, reason = null)

            fun from(isBlocked: Boolean, reason: String?): BlockerState =
                BlockerState(isBlocked = isBlocked, reason = reason?.takeIf { it.isNotBlank() })
        }
    }

    /**
     * The confirmation state of the subject as a first-class foreground object.
     *
     * [confirmationNeeded] reflects the boundary-adjudicated foreground value: a
     * confirmation on a post-closure or non-terminal signal is NOT promoted.
     */
    data class ConfirmationState(
        val confirmationNeeded: Boolean,
        val closureReadyForAcceptance: Boolean
    ) {
        companion object {
            val NONE = ConfirmationState(confirmationNeeded = false, closureReadyForAcceptance = false)
        }
    }

    /**
     * The result completion state of the subject as a foreground dimension.
     *
     * [isComplete] is true only when the subject has reached a terminal result state
     * that has not been blocked or overridden by a pending confirmation.
     */
    data class ResultState(
        val isComplete: Boolean,
        val resultSummary: String?
    ) {
        companion object {
            val NONE = ResultState(isComplete = false, resultSummary = null)
        }
    }

    /**
     * Declares which subject-facing dimension is the primary foreground expression for a
     * given [SubjectFacingForegroundCard].
     *
     * Priority (highest first): BLOCKER → CONFIRMATION → ACTION_PHASE → RESULT → PRESENCE_ONLY.
     * CONTROL_PLANE_DEMOTED is assigned when the raw lifecycle stage belongs to the
     * control-plane / operator-only set and cannot drive a subject-facing primary expression.
     */
    enum class ForegroundPrimaryObject(val wireValue: String) {
        /**
         * A concrete, boundary-verified blocker is the primary expression.
         * The subject is stuck and the user should see why.
         */
        BLOCKER("blocker"),
        /**
         * A confirmation-needed state is the primary expression.
         * The subject cannot proceed without user input.
         */
        CONFIRMATION("confirmation"),
        /**
         * The current action lifecycle phase (accepted / executing / failed / cancelled)
         * is the primary expression.  The user sees what the subject is doing.
         */
        ACTION_PHASE("action_phase"),
        /**
         * A completed result is the primary expression.
         * The subject finished; the user sees the outcome.
         */
        RESULT("result"),
        /**
         * Presence mode is the only available foreground dimension; no active task.
         */
        PRESENCE_ONLY("presence_only"),
        /**
         * The lifecycle stage is a control-plane / runtime-internal stage.
         * The card is still produced, but no subject-facing primary dimension is promoted.
         * Callers SHOULD NOT display a prominent subject-facing card for this value.
         */
        CONTROL_PLANE_DEMOTED("control_plane_demoted");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SubjectFacingForegroundCard — primary foreground object
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * The primary foreground object for the Android default foreground path.
     *
     * This card is derived from [unified_action_lifecycle_surface] by
     * [fromUnifiedLifecycleSurface] and becomes the organisational backbone of the
     * foreground instead of a scattered text payload.
     *
     * **What has changed vs. prior foreground:**
     * - `actionPhase` replaces stage strings as the foreground action dimension.
     * - `blockerState` is a first-class foreground object, not a text suffix.
     * - `confirmationState` is a first-class foreground object, not a text suffix.
     * - `presenceMode` is the subject's current participation state, not a tag.
     * - `foregroundPrimaryObject` declares explicitly what the foreground is expressing.
     * - Control-plane / operator stages do NOT drive `foregroundPrimaryObject`.
     */
    data class SubjectFacingForegroundCard(
        val actionPhase: ActionPhase,
        val presenceMode: PresenceMode,
        val currentActionDetail: String?,
        val blockerState: BlockerState,
        val confirmationState: ConfirmationState,
        val resultState: ResultState,
        val foregroundPrimaryObject: ForegroundPrimaryObject
    ) {
        /**
         * Wire-map representation suitable for embedding in canonical uplink payloads.
         */
        fun toWireMap(): Map<String, Any?> = mapOf(
            "schema_version" to SCHEMA_VERSION,
            "foreground_primary_object" to foregroundPrimaryObject.wireValue,
            "action_phase" to actionPhase.wireValue,
            "action_phase_is_subject_facing" to actionPhase.isSubjectFacing,
            "presence_mode" to presenceMode.wireValue,
            "current_action_detail" to currentActionDetail,
            "blocker" to mapOf(
                "is_blocked" to blockerState.isBlocked,
                "reason" to blockerState.reason
            ),
            "confirmation" to mapOf(
                "confirmation_needed" to confirmationState.confirmationNeeded,
                "closure_ready_for_acceptance" to confirmationState.closureReadyForAcceptance
            ),
            "result" to mapOf(
                "is_complete" to resultState.isComplete,
                "result_summary" to resultState.resultSummary
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Factory
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Builds a [SubjectFacingForegroundCard] from a `unified_action_lifecycle_surface`
     * [JsonObject] (the direct surface object, not a wrapper).
     *
     * The factory enforces the [ForegroundPrimaryObject] priority ordering and demotes
     * control-plane stages from the primary dimension.
     *
     * @param surface  The `unified_action_lifecycle_surface` JSON object.
     * @param presence Optional presence participation kind from device state; when null
     *                 [PresenceMode.UNKNOWN] is used.
     */
    fun fromUnifiedLifecycleSurface(
        surface: JsonObject,
        presence: AndroidPresenceParticipationKind? = null
    ): SubjectFacingForegroundCard {
        val stage = surface.stringField("stage")
        val actionPhase = ActionPhase.fromStage(stage)
        val presenceMode = PresenceMode.fromParticipationKind(presence)

        val blockerObj = surface.getAsJsonObject("blocker")
        val confirmationObj = surface.getAsJsonObject("confirmation")
        val executionObj = surface.getAsJsonObject("execution")
        val resultObj = surface.getAsJsonObject("result_completion")

        // Blocker: use boundary-adjudicated values from the foreground payload
        val rawIsBlocked = blockerObj.booleanField("is_blocked")
        val blockerReason = blockerObj.stringField("reason")
        val blockerState = BlockerState.from(rawIsBlocked, blockerReason)

        // Confirmation: use boundary-adjudicated values from the foreground payload
        val confirmationNeeded = confirmationObj.booleanField("confirmation_needed")
        val closureReady = confirmationObj.booleanField("closure_ready_for_acceptance")
        val confirmationState = ConfirmationState(
            confirmationNeeded = confirmationNeeded,
            closureReadyForAcceptance = closureReady
        )

        // Current action detail from execution sub-object
        val currentActionDetail = executionObj.stringField("progress_detail")
            ?: executionObj.stringField("current_action")

        // Result state
        val resultSummary = surface.stringField("result_summary")
            ?: resultObj.stringField("summary")
        val resultIsComplete = (actionPhase == ActionPhase.RESULT_EMITTED ||
            actionPhase == ActionPhase.RECOVERY) &&
            !blockerState.isBlocked &&
            !confirmationState.confirmationNeeded &&
            !resultSummary.isNullOrBlank()
        val resultState = ResultState(
            isComplete = resultIsComplete,
            resultSummary = resultSummary.takeIf { !it.isNullOrBlank() }
        )

        // Determine foreground primary object using priority ordering
        val foregroundPrimaryObject = when {
            !actionPhase.isSubjectFacing -> ForegroundPrimaryObject.CONTROL_PLANE_DEMOTED
            blockerState.isBlocked -> ForegroundPrimaryObject.BLOCKER
            confirmationState.confirmationNeeded -> ForegroundPrimaryObject.CONFIRMATION
            actionPhase != ActionPhase.UNKNOWN -> ForegroundPrimaryObject.ACTION_PHASE
            resultState.isComplete -> ForegroundPrimaryObject.RESULT
            else -> ForegroundPrimaryObject.PRESENCE_ONLY
        }

        return SubjectFacingForegroundCard(
            actionPhase = actionPhase,
            presenceMode = presenceMode,
            currentActionDetail = currentActionDetail,
            blockerState = blockerState,
            confirmationState = confirmationState,
            resultState = resultState,
            foregroundPrimaryObject = foregroundPrimaryObject
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // V2 alignment map
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Maps each [ForegroundPrimaryObject] to the corresponding V2-side foreground
     * handling path for cross-repo alignment.
     */
    val V2_SUBJECT_FOREGROUND_ALIGNMENT_MAP: Map<ForegroundPrimaryObject, String> = mapOf(
        ForegroundPrimaryObject.BLOCKER to
            "v2.subject_foreground.blocker_primary_path",
        ForegroundPrimaryObject.CONFIRMATION to
            "v2.subject_foreground.confirmation_primary_path",
        ForegroundPrimaryObject.ACTION_PHASE to
            "v2.subject_foreground.action_phase_primary_path",
        ForegroundPrimaryObject.RESULT to
            "v2.subject_foreground.result_primary_path",
        ForegroundPrimaryObject.PRESENCE_ONLY to
            "v2.subject_foreground.presence_only_path",
        ForegroundPrimaryObject.CONTROL_PLANE_DEMOTED to
            "v2.subject_foreground.control_plane_demoted_not_subject_primary"
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Machine-verifiable invariants
    // ─────────────────────────────────────────────────────────────────────────

    data class SubjectForegroundInvariant(val id: String, val description: String)

    val SUBJECT_FOREGROUND_INVARIANTS: List<SubjectForegroundInvariant> = listOf(
        SubjectForegroundInvariant(
            id = "BLOCKER_FOREGROUND_PRIORITY_OVER_ACTION_PHASE",
            description = "When blockerState.isBlocked=true, foregroundPrimaryObject must be " +
                "BLOCKER regardless of actionPhase value"
        ),
        SubjectForegroundInvariant(
            id = "CONFIRMATION_PRIORITY_OVER_ACTION_PHASE",
            description = "When confirmationState.confirmationNeeded=true and no blocker is " +
                "present, foregroundPrimaryObject must be CONFIRMATION"
        ),
        SubjectForegroundInvariant(
            id = "CONTROL_PLANE_STAGE_NEVER_ACTION_PHASE_PRIMARY",
            description = "A stage with actionPhase.isSubjectFacing=false must never produce " +
                "foregroundPrimaryObject=ACTION_PHASE; it must be CONTROL_PLANE_DEMOTED"
        ),
        SubjectForegroundInvariant(
            id = "RESULT_EMITTED_WITHOUT_BLOCK_IS_ACTION_PHASE",
            description = "stage=result_emitted with no blocker and no confirmation must produce " +
                "foregroundPrimaryObject=ACTION_PHASE (not RESULT), because the phase itself " +
                "IS the primary expression of subject completion"
        ),
        SubjectForegroundInvariant(
            id = "BLOCKER_REQUIRES_SUBJECT_FACING_REASON_OR_FALSE",
            description = "BlockerState.isBlocked may only be true when the value was derived " +
                "from boundary-adjudicated foreground blocker evidence (not operator-only blockers)"
        ),
        SubjectForegroundInvariant(
            id = "SUBJECT_FACING_CARD_ALWAYS_PRODUCED",
            description = "fromUnifiedLifecycleSurface must always return a non-null card; " +
                "callers must never receive null when a lifecycle surface is present"
        ),
        SubjectForegroundInvariant(
            id = "WIRE_MAP_CONTAINS_FOREGROUND_PRIMARY_OBJECT",
            description = "SubjectFacingForegroundCard.toWireMap() must always contain a " +
                "non-null foreground_primary_object key"
        ),
        SubjectForegroundInvariant(
            id = "PRESENCE_ONLY_ONLY_WHEN_NO_SUBJECT_PHASE",
            description = "foregroundPrimaryObject=PRESENCE_ONLY must only occur when " +
                "actionPhase=UNKNOWN and no blocker or confirmation is present"
        ),
        SubjectForegroundInvariant(
            id = "CONTROL_PLANE_STAGE_VALUES_ARE_DEMOTED",
            description = "Stages reconciliation_snapshot and participant_state (and any " +
                "unrecognised stage) map to ActionPhase.UNKNOWN (isSubjectFacing=false) and produce " +
                "CONTROL_PLANE_DEMOTED as foregroundPrimaryObject; recovery stages such as " +
                "recovery_replaying and recovery_reconciliation_pending map to ActionPhase.RECOVERY " +
                "(isSubjectFacing=true) and produce ACTION_PHASE instead"
        ),
        SubjectForegroundInvariant(
            id = "V2_ALIGNMENT_MAP_COVERS_ALL_PRIMARY_OBJECTS",
            description = "V2_SUBJECT_FOREGROUND_ALIGNMENT_MAP must have an entry for every " +
                "ForegroundPrimaryObject value"
        )
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Private JSON helpers (avoid pulling in a full extension dependency)
    // ─────────────────────────────────────────────────────────────────────────

    private fun JsonObject?.stringField(key: String): String? {
        val value = this?.get(key) ?: return null
        if (!value.isJsonPrimitive) return null
        val p = value.asJsonPrimitive
        return when {
            p.isString -> p.asString.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun JsonObject?.booleanField(key: String): Boolean {
        val element = this?.get(key) ?: return false
        return when {
            element.isJsonPrimitive && element.asJsonPrimitive.isBoolean -> element.asBoolean
            element.isJsonPrimitive && element.asJsonPrimitive.isString ->
                element.asString.equals("true", ignoreCase = true)
            element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asInt != 0
            else -> false
        }
    }
}
