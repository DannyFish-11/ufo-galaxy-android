package com.ufo.galaxy.runtime

/**
 * PR-128 — Android Dual-Repo Hidden / Visible Boundary Contract.
 *
 * Establishes a unified hidden-visible runtime decision that governs Android-originated
 * information across the Android → V2 dual-repo boundary.  This contract is the first
 * place where Android-originated blocker, confirmation, result-visibility, device-state,
 * and lifecycle information is routed through a shared [VisibilityTier] classification
 * rather than interpreted locally inside individual handlers.
 *
 * ## Problem addressed
 *
 * Prior to this contract, Android-originated blocker and confirmation_needed signals were
 * resolved inside [ReconciliationSignal]'s `withUnifiedActionLifecycleSurface` function
 * through handler-local logic.  The foreground payload exposed whatever those local
 * computations produced without a shared governance decision.  This meant:
 *  - V2 had no machine-readable signal about whether a blocker was user-facing (FOREGROUND)
 *    or diagnostic-only (OPERATOR_ONLY).
 *  - Android device recovery state appeared in foreground payloads regardless of whether
 *    the recovery detail was meaningful to an end user.
 *  - Confirmation-needed signals from non-terminal Android stages were promoted to foreground
 *    without boundary adjudication.
 *
 * ## What this contract does
 *
 * [classify] takes a [BoundaryInput] describing an Android-originated information item and
 * returns a [BoundaryDecision] assigning it to one of three [VisibilityTier] values:
 *
 * | [VisibilityTier] | Meaning                                                  | Foreground effect              |
 * |-----------------|----------------------------------------------------------|-------------------------------|
 * | [FOREGROUND]    | User-facing; must appear in default response payload     | Payload exposed as-is          |
 * | [BACKGROUND]    | Android-internal only; not shown to the end user         | Payload suppressed in foreground |
 * | [OPERATOR_ONLY] | Diagnostic / operator channel only                      | Payload suppressed; surfaced only in operator channel |
 *
 * ## Dual-repo shared path
 *
 * [ReconciliationSignal.withUnifiedActionLifecycleSurface] is the first runtime path where
 * Android-originated information (blocker, confirmation, recovery state) and V2 local
 * information (result, stage, terminal_outcome_kind) are **both** governed by this
 * boundary contract.  The resulting `unified_action_lifecycle_surface` foreground payload
 * is composed from the boundary-adjudicated values — Android-originated items classified as
 * [OPERATOR_ONLY] or [BACKGROUND] are suppressed from the foreground sub-maps.
 *
 * ## Wire integration
 *
 * Each call to [classify] produces a [BoundaryDecision] whose [BoundaryDecision.toWireMap]
 * is embedded in the `visibility_boundary` section of `unified_action_lifecycle_surface`.
 * The wire map includes [KEY_VISIBILITY_TIER], [KEY_ANDROID_INFO_ORIGIN_CLASS],
 * [KEY_FOREGROUND_SUPPRESSED], and [KEY_BOUNDARY_SCHEMA_VERSION].
 *
 * ## Boundary constraint
 *
 * Android MUST NOT promote an [OPERATOR_ONLY] or [BACKGROUND] decision to [FOREGROUND] in
 * order to surface operator-internal detail to end users.  V2 owns the final foreground
 * composition policy; Android supplies structured boundary evidence.
 *
 * @see ReconciliationSignal
 */
object AndroidDualRepoHiddenVisibleBoundaryContract {

    /** Android PR that introduced this contract. */
    const val INTRODUCED_PR = 128

    /** Wire schema version for this contract's boundary fields. */
    const val SCHEMA_VERSION = "1"

    // ── Wire key constants ─────────────────────────────────────────────────────

    /**
     * Wire key for the [VisibilityTier.wireValue] in boundary decision maps.
     *
     * V2 MUST read this key to determine whether to surface Android-originated information
     * in the user-facing response, background state, or operator-only channel.
     */
    const val KEY_VISIBILITY_TIER = "visibility_tier"

    /**
     * Wire key for the [AndroidInfoOriginClass.wireValue] in boundary decision maps.
     *
     * Identifies which kind of Android-originated information this boundary decision governs.
     */
    const val KEY_ANDROID_INFO_ORIGIN_CLASS = "android_info_origin_class"

    /**
     * Wire key (Boolean) indicating whether the foreground payload for this item has been
     * suppressed by the boundary decision.
     *
     * When `true`, V2 MUST NOT surface this item in the default user-facing response.
     * The raw item may still appear in operator-channel or diagnostics payloads.
     */
    const val KEY_FOREGROUND_SUPPRESSED = "foreground_suppressed"

    /** Wire key for the suppression reason string, or `null` when not suppressed. */
    const val KEY_SUPPRESSION_REASON = "suppression_reason"

    /** Wire key for the schema version of this contract's boundary fields. */
    const val KEY_BOUNDARY_SCHEMA_VERSION = "dual_repo_visibility_boundary_schema_version"

    // ── Visibility tier ────────────────────────────────────────────────────────

    /**
     * The three visibility tiers that govern Android-originated information across the
     * Android → V2 dual-repo boundary.
     */
    enum class VisibilityTier(val wireValue: String) {
        /** User-facing; must appear in the default user-visible response payload. */
        FOREGROUND("foreground"),

        /**
         * Android-internal only; not shown to the end user.  V2 may retain this
         * information in background state but MUST NOT expose it to users.
         */
        BACKGROUND("background"),

        /**
         * Diagnostic or operator channel only.  V2 MUST route this information
         * exclusively to operator / debug surfaces and MUST NOT expose it in any
         * user-facing or background-visible channel.
         */
        OPERATOR_ONLY("operator_only")
    }

    // ── Android info origin classes ────────────────────────────────────────────

    /**
     * The classes of Android-originated information that this boundary contract governs.
     *
     * Each class corresponds to a distinct category of Android-originated signal that was
     * previously interpreted locally inside individual handlers without shared boundary
     * adjudication.
     */
    enum class AndroidInfoOriginClass(val wireValue: String) {
        /** Android-originated blocker signal (canonical closure blocked or execution failed). */
        BLOCKER("android_blocker"),

        /** Android-originated confirmation-needed signal (terminal but closure not yet ready). */
        CONFIRMATION_NEEDED("android_confirmation_needed"),

        /** Android-originated result visibility (whether a result has been returned). */
        RESULT_VISIBILITY("android_result_visibility"),

        /**
         * Android device state / snapshot visibility (foreground vs. background vs. recovery
         * states that carry operator-only detail).
         */
        DEVICE_STATE_VISIBILITY("android_device_state_visibility"),

        /** Android action lifecycle stage (accepted, executing, result_emitted, etc.). */
        LIFECYCLE_STATE("android_lifecycle_state")
    }

    // ── Boundary input ─────────────────────────────────────────────────────────

    /**
     * Input to the boundary classification function.
     *
     * Only the fields relevant to [infoOriginClass] need to be populated; others default
     * to their zero / null values.
     */
    data class BoundaryInput(
        /** Which category of Android-originated information is being classified. */
        val infoOriginClass: AndroidInfoOriginClass,

        // ── BLOCKER fields ─────────────────────────────────────────────────────
        /** Concrete blocker reason string, or `null` when the block has no stated reason. */
        val blockerReason: String? = null,
        /** `true` when canonical closure routing is explicitly blocked on the Android side. */
        val isCanonicalClosureBlocked: Boolean = false,
        /** `true` when the signal kind is TASK_FAILED (execution-level failure). */
        val isExecutionFailed: Boolean = false,

        // ── CONFIRMATION_NEEDED fields ─────────────────────────────────────────
        /** `true` when confirmation is required before canonical closure. */
        val confirmationNeeded: Boolean = false,
        /** `true` when the signal is a terminal kind (TASK_RESULT / TASK_CANCELLED / TASK_FAILED). */
        val isTerminalSignal: Boolean = false,
        /** `true` when the closure boundary has already accepted the result. */
        val closureReadyForAcceptance: Boolean = false,

        // ── RESULT_VISIBILITY fields ───────────────────────────────────────────
        /** `true` when a result payload has been returned from Android execution. */
        val resultReturned: Boolean = false,
        /** `true` when the result surface is declared first-class in the signal payload. */
        val isFirstClassResult: Boolean = false,

        // ── DEVICE_STATE_VISIBILITY fields ─────────────────────────────────────
        /**
         * The device / recovery stage string that describes the current device state.
         * Matches the `stage` values produced by [ReconciliationSignal]'s
         * `withUnifiedActionLifecycleSurface` for RUNTIME_TRUTH_SNAPSHOT signals.
         */
        val deviceStateStage: String? = null,

        // ── LIFECYCLE_STATE fields ─────────────────────────────────────────────
        /**
         * The lifecycle stage string (e.g. "accepted", "executing", "result_emitted",
         * "cancelled", "failed") that identifies the Android action lifecycle position.
         */
        val lifecycleStage: String? = null
    )

    // ── Boundary decision ──────────────────────────────────────────────────────

    /**
     * The classification result produced by [classify].
     *
     * [visibilityTier] is the authoritative tier assignment for [infoOriginClass].
     * [foregroundSuppressed] is always `true` when tier is [VisibilityTier.BACKGROUND] or
     * [VisibilityTier.OPERATOR_ONLY], and always `false` for [VisibilityTier.FOREGROUND].
     */
    data class BoundaryDecision(
        val visibilityTier: VisibilityTier,
        val infoOriginClass: AndroidInfoOriginClass,
        val foregroundSuppressed: Boolean,
        val suppressionReason: String?
    ) {
        /**
         * Wire map suitable for embedding in the `visibility_boundary` section of
         * `unified_action_lifecycle_surface`.
         */
        fun toWireMap(): Map<String, Any?> = mapOf(
            KEY_VISIBILITY_TIER to visibilityTier.wireValue,
            KEY_ANDROID_INFO_ORIGIN_CLASS to infoOriginClass.wireValue,
            KEY_FOREGROUND_SUPPRESSED to foregroundSuppressed,
            KEY_SUPPRESSION_REASON to suppressionReason,
            KEY_BOUNDARY_SCHEMA_VERSION to SCHEMA_VERSION
        )
    }

    // ── Classification function ────────────────────────────────────────────────

    /**
     * Classifies an Android-originated information item into a [VisibilityTier].
     *
     * This is the single shared boundary resolution function that governs both Android-side
     * and V2-side interpretation of Android-originated signals.  It MUST be called for every
     * Android-originated item that appears in `unified_action_lifecycle_surface`.
     */
    fun classify(input: BoundaryInput): BoundaryDecision = when (input.infoOriginClass) {
        AndroidInfoOriginClass.BLOCKER -> classifyBlocker(input)
        AndroidInfoOriginClass.CONFIRMATION_NEEDED -> classifyConfirmation(input)
        AndroidInfoOriginClass.RESULT_VISIBILITY -> classifyResult(input)
        AndroidInfoOriginClass.DEVICE_STATE_VISIBILITY -> classifyDeviceState(input)
        AndroidInfoOriginClass.LIFECYCLE_STATE -> classifyLifecycle(input)
    }

    // ── Per-class classification rules ─────────────────────────────────────────

    private fun classifyBlocker(input: BoundaryInput): BoundaryDecision {
        return when {
            // Canonical closure blocked is a concrete, user-affecting blocker → FOREGROUND
            input.isCanonicalClosureBlocked -> BoundaryDecision(
                visibilityTier = VisibilityTier.FOREGROUND,
                infoOriginClass = AndroidInfoOriginClass.BLOCKER,
                foregroundSuppressed = false,
                suppressionReason = null
            )
            // Execution failure is a user-facing outcome → FOREGROUND
            input.isExecutionFailed -> BoundaryDecision(
                visibilityTier = VisibilityTier.FOREGROUND,
                infoOriginClass = AndroidInfoOriginClass.BLOCKER,
                foregroundSuppressed = false,
                suppressionReason = null
            )
            // Blocker with a concrete reason is foreground-eligible
            input.blockerReason != null -> BoundaryDecision(
                visibilityTier = VisibilityTier.FOREGROUND,
                infoOriginClass = AndroidInfoOriginClass.BLOCKER,
                foregroundSuppressed = false,
                suppressionReason = null
            )
            // Blocker with no concrete reason is operator-only diagnostic noise
            else -> BoundaryDecision(
                visibilityTier = VisibilityTier.OPERATOR_ONLY,
                infoOriginClass = AndroidInfoOriginClass.BLOCKER,
                foregroundSuppressed = true,
                suppressionReason = "no_concrete_blocker_reason"
            )
        }
    }

    private fun classifyConfirmation(input: BoundaryInput): BoundaryDecision {
        return when {
            // Closure already accepted — confirmation is no longer a user concern → BACKGROUND
            input.closureReadyForAcceptance -> BoundaryDecision(
                visibilityTier = VisibilityTier.BACKGROUND,
                infoOriginClass = AndroidInfoOriginClass.CONFIRMATION_NEEDED,
                foregroundSuppressed = true,
                suppressionReason = "closure_already_accepted"
            )
            // confirmation_needed on a terminal signal that is not closure-ready → FOREGROUND
            input.confirmationNeeded && input.isTerminalSignal -> BoundaryDecision(
                visibilityTier = VisibilityTier.FOREGROUND,
                infoOriginClass = AndroidInfoOriginClass.CONFIRMATION_NEEDED,
                foregroundSuppressed = false,
                suppressionReason = null
            )
            // Non-terminal confirmation signals are not user-facing → BACKGROUND
            input.confirmationNeeded -> BoundaryDecision(
                visibilityTier = VisibilityTier.BACKGROUND,
                infoOriginClass = AndroidInfoOriginClass.CONFIRMATION_NEEDED,
                foregroundSuppressed = true,
                suppressionReason = "non_terminal_confirmation_is_background"
            )
            // No confirmation required → BACKGROUND (nothing to surface)
            else -> BoundaryDecision(
                visibilityTier = VisibilityTier.BACKGROUND,
                infoOriginClass = AndroidInfoOriginClass.CONFIRMATION_NEEDED,
                foregroundSuppressed = true,
                suppressionReason = "no_confirmation_required"
            )
        }
    }

    private fun classifyResult(input: BoundaryInput): BoundaryDecision {
        return if (input.resultReturned && input.isFirstClassResult) {
            BoundaryDecision(
                visibilityTier = VisibilityTier.FOREGROUND,
                infoOriginClass = AndroidInfoOriginClass.RESULT_VISIBILITY,
                foregroundSuppressed = false,
                suppressionReason = null
            )
        } else {
            BoundaryDecision(
                visibilityTier = VisibilityTier.BACKGROUND,
                infoOriginClass = AndroidInfoOriginClass.RESULT_VISIBILITY,
                foregroundSuppressed = true,
                suppressionReason = "result_not_yet_returned_or_not_first_class"
            )
        }
    }

    private fun classifyDeviceState(input: BoundaryInput): BoundaryDecision {
        return when (input.deviceStateStage) {
            // Recovery replay / reconciliation detail is operator-only — not user-facing
            "recovery_replaying", "recovery_reconciliation_pending" -> BoundaryDecision(
                visibilityTier = VisibilityTier.OPERATOR_ONLY,
                infoOriginClass = AndroidInfoOriginClass.DEVICE_STATE_VISIBILITY,
                foregroundSuppressed = true,
                suppressionReason = "recovery_state_operator_only"
            )
            // Recovery failure is user-facing (execution has failed to recover) → FOREGROUND
            "recovery_failed" -> BoundaryDecision(
                visibilityTier = VisibilityTier.FOREGROUND,
                infoOriginClass = AndroidInfoOriginClass.DEVICE_STATE_VISIBILITY,
                foregroundSuppressed = false,
                suppressionReason = null
            )
            // All other device state is background unless it represents a failure
            else -> BoundaryDecision(
                visibilityTier = VisibilityTier.BACKGROUND,
                infoOriginClass = AndroidInfoOriginClass.DEVICE_STATE_VISIBILITY,
                foregroundSuppressed = true,
                suppressionReason = "device_state_is_background_unless_failure"
            )
        }
    }

    private fun classifyLifecycle(input: BoundaryInput): BoundaryDecision {
        return when (input.lifecycleStage) {
            "accepted", "result_emitted", "cancelled", "failed" -> BoundaryDecision(
                visibilityTier = VisibilityTier.FOREGROUND,
                infoOriginClass = AndroidInfoOriginClass.LIFECYCLE_STATE,
                foregroundSuppressed = false,
                suppressionReason = null
            )
            // Mid-execution progress updates are background — not user-facing
            "executing" -> BoundaryDecision(
                visibilityTier = VisibilityTier.BACKGROUND,
                infoOriginClass = AndroidInfoOriginClass.LIFECYCLE_STATE,
                foregroundSuppressed = true,
                suppressionReason = "mid_execution_lifecycle_is_background"
            )
            else -> BoundaryDecision(
                visibilityTier = VisibilityTier.FOREGROUND,
                infoOriginClass = AndroidInfoOriginClass.LIFECYCLE_STATE,
                foregroundSuppressed = false,
                suppressionReason = null
            )
        }
    }

    // ── V2 alignment map ───────────────────────────────────────────────────────

    /**
     * Maps each [VisibilityTier] to the V2-side handling path.
     *
     * V2 consumers MUST route Android-originated information to the path corresponding
     * to the [VisibilityTier] embedded in the `visibility_boundary` wire map.
     */
    val V2_VISIBILITY_ALIGNMENT_MAP: Map<VisibilityTier, String> = mapOf(
        VisibilityTier.FOREGROUND to "v2:visible_action_surface/user_facing_response",
        VisibilityTier.BACKGROUND to "v2:background_state/android_internal_only",
        VisibilityTier.OPERATOR_ONLY to "v2:operator_board_diagnostics/operator_channel_only"
    )

    // ── Boundary invariants ────────────────────────────────────────────────────

    data class BoundaryInvariant(val id: String, val description: String)

    /**
     * Machine-verifiable regression anchors for the dual-repo hidden-visible boundary.
     *
     * Tests MUST verify every invariant listed here.  Any change that would falsify an
     * invariant is a governance regression.
     */
    val BOUNDARY_INVARIANTS: List<BoundaryInvariant> = listOf(
        BoundaryInvariant(
            id = "FOREGROUND_REQUIRES_EXPLICIT_BLOCKER_EVIDENCE",
            description = "Android-originated blocker reaches FOREGROUND only when " +
                "isCanonicalClosureBlocked=true, isExecutionFailed=true, " +
                "or a non-null blockerReason is present"
        ),
        BoundaryInvariant(
            id = "BLOCKER_WITHOUT_EVIDENCE_IS_OPERATOR_ONLY",
            description = "An Android-originated blocker with no canonical closure block, " +
                "no execution failure, and no concrete reason is always classified " +
                "as OPERATOR_ONLY with foregroundSuppressed=true"
        ),
        BoundaryInvariant(
            id = "CONFIRMATION_FOREGROUND_REQUIRES_TERMINAL_UNCLOSED",
            description = "Android-originated confirmation_needed reaches FOREGROUND only " +
                "when confirmationNeeded=true AND isTerminalSignal=true AND " +
                "closureReadyForAcceptance=false"
        ),
        BoundaryInvariant(
            id = "CLOSURE_ACCEPTED_DEMOTES_CONFIRMATION_TO_BACKGROUND",
            description = "When closureReadyForAcceptance=true, confirmation_needed is always " +
                "classified as BACKGROUND regardless of isTerminalSignal or confirmationNeeded"
        ),
        BoundaryInvariant(
            id = "DEVICE_RECOVERY_DETAIL_IS_OPERATOR_ONLY",
            description = "Android device state from recovery_replaying or " +
                "recovery_reconciliation_pending stage is always OPERATOR_ONLY " +
                "with foregroundSuppressed=true"
        ),
        BoundaryInvariant(
            id = "DEVICE_RECOVERY_FAILURE_IS_FOREGROUND",
            description = "Android device state from recovery_failed stage is always FOREGROUND " +
                "because recovery failure is a user-visible execution outcome"
        ),
        BoundaryInvariant(
            id = "OPERATOR_ONLY_AND_BACKGROUND_ALWAYS_SUPPRESS",
            description = "Any BoundaryDecision with VisibilityTier OPERATOR_ONLY or BACKGROUND " +
                "must always have foregroundSuppressed=true"
        ),
        BoundaryInvariant(
            id = "FOREGROUND_NEVER_SUPPRESSES",
            description = "Any BoundaryDecision with VisibilityTier FOREGROUND must always have " +
                "foregroundSuppressed=false and suppressionReason=null"
        ),
        BoundaryInvariant(
            id = "SCHEMA_VERSION_IS_STABLE",
            description = "SCHEMA_VERSION is '1' for the duration of this contract version; " +
                "a breaking schema change requires a new contract version"
        ),
        BoundaryInvariant(
            id = "V2_ALIGNMENT_MAP_COVERS_ALL_TIERS",
            description = "V2_VISIBILITY_ALIGNMENT_MAP must contain an entry for every " +
                "VisibilityTier value"
        )
    )
}
