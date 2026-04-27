package com.ufo.galaxy.runtime

/**
 * Formal contract for Android participant capability re-advertisement after recovery.
 *
 * Defines the authoritative semantics for **when** and **how** an Android participant must
 * re-advertise its capabilities to the control plane after any state transition that restores
 * capability advertisement eligibility.  This closes the gap between recovery completion and
 * the control plane's view of participant capabilities.
 *
 * ## Why this matters
 *
 * When a participant transitions through [FormalParticipantLifecycleState.RECOVERING] or
 * [FormalParticipantLifecycleState.UNAVAILABLE_FAILED], capability advertisement is suppressed.
 * After recovery, the participant must re-advertise the correct capability set so that:
 *
 *  - The control plane's routing table reflects the participant's current real capability.
 *  - No stale "zero-capability" or "pre-recovery full-capability" entries remain.
 *  - Heartbeat and re-registration signals are consistent with the re-advertised capability.
 *
 * ## Key concepts
 *
 * - **ReadvertiseAction**: what kind of re-advertisement the participant must perform.
 * - **ReadvertiseDecision**: a binding decision combining [ReadvertiseAction] with the
 *   triggering lifecycle context and rationale.
 * - [decide]: the authoritative factory that derives a [ReadvertiseDecision] from the
 *   current [FormalParticipantLifecycleState] and [LocalIntelligenceCapabilityStatus].
 *
 * ## Heartbeat consistency requirement
 *
 * After any re-advertisement, subsequent heartbeat messages sent to the control plane must
 * carry the same capability set as the re-advertisement.  [heartbeatMustMatchReadvertise]
 * encodes this requirement.
 *
 * @see FormalParticipantLifecycleState
 * @see RecoveryCapabilityAlignmentGuard
 * @see CapabilityHonestyGuard
 */
object CapabilityReadvertiseContract {

    // ── Re-advertise action ───────────────────────────────────────────────────

    /**
     * The action that the participant must take when re-advertising capabilities to the
     * control plane following a recovery transition.
     */
    enum class ReadvertiseAction(val wireValue: String) {

        /**
         * Re-advertise the full capability set, including inference capabilities if available.
         * Applicable when [FormalParticipantLifecycleState] is [FormalParticipantLifecycleState.READY]
         * and inference is active.
         */
        READVERTISE_FULL("readvertise_full"),

        /**
         * Re-advertise base capabilities only; do not include inference capabilities.
         * Applicable when [FormalParticipantLifecycleState] is [FormalParticipantLifecycleState.READY]
         * but inference is not active, or when the state is [FormalParticipantLifecycleState.DEGRADED].
         */
        READVERTISE_BASE_ONLY("readvertise_base_only"),

        /**
         * Suppress all capability advertisement.  The participant is not yet ready.
         * Applicable when the state is [FormalParticipantLifecycleState.STARTING] or
         * [FormalParticipantLifecycleState.RECOVERING].
         */
        SUPPRESS_UNTIL_STABLE("suppress_until_stable"),

        /**
         * No capability advertisement is permitted; the runtime is unavailable or failed.
         * Applicable when the state is [FormalParticipantLifecycleState.UNAVAILABLE_FAILED].
         */
        BLOCK_READVERTISE("block_readvertise")
    }

    // ── Re-advertise decision ─────────────────────────────────────────────────

    /**
     * A binding re-advertisement decision derived from the current runtime state.
     *
     * @property action             The [ReadvertiseAction] that must be taken.
     * @property lifecycleState     The [FormalParticipantLifecycleState] at decision time.
     * @property inferenceAvailable Whether inference capability was active at decision time.
     * @property allowedCapabilities The capability set that may be emitted, consistent with [action].
     * @property rationale          Human-readable rationale for the decision.
     */
    data class ReadvertiseDecision(
        val action: ReadvertiseAction,
        val lifecycleState: FormalParticipantLifecycleState,
        val inferenceAvailable: Boolean,
        val allowedCapabilities: Set<String>,
        val rationale: String
    ) {
        /**
         * Returns `true` when any capability advertisement is permitted by this decision.
         */
        val advertisementPermitted: Boolean
            get() = action == ReadvertiseAction.READVERTISE_FULL ||
                action == ReadvertiseAction.READVERTISE_BASE_ONLY
    }

    // ── Core logic ────────────────────────────────────────────────────────────

    /**
     * Derives the authoritative [ReadvertiseDecision] for the given runtime state.
     *
     * | FormalState         | Inference | Action                  |
     * |---------------------|-----------|-------------------------|
     * | READY               | ACTIVE    | READVERTISE_FULL        |
     * | READY               | !ACTIVE   | READVERTISE_BASE_ONLY   |
     * | DEGRADED            | any       | READVERTISE_BASE_ONLY   |
     * | STARTING            | any       | SUPPRESS_UNTIL_STABLE   |
     * | RECOVERING          | any       | SUPPRESS_UNTIL_STABLE   |
     * | UNAVAILABLE_FAILED  | any       | BLOCK_READVERTISE       |
     *
     * @param lifecycleState    The current [FormalParticipantLifecycleState].
     * @param inferenceAvailable `true` when [LocalIntelligenceCapabilityStatus] is
     *                           [LocalIntelligenceCapabilityStatus.ACTIVE].
     * @return The binding [ReadvertiseDecision] for this state combination.
     */
    fun decide(
        lifecycleState: FormalParticipantLifecycleState,
        inferenceAvailable: Boolean
    ): ReadvertiseDecision = when (lifecycleState) {

        FormalParticipantLifecycleState.READY -> {
            if (inferenceAvailable) {
                ReadvertiseDecision(
                    action = ReadvertiseAction.READVERTISE_FULL,
                    lifecycleState = lifecycleState,
                    inferenceAvailable = true,
                    allowedCapabilities = CapabilityHonestyGuard.BASE_CAPABILITIES +
                        CapabilityHonestyGuard.CAPABILITY_LOCAL_MODEL_INFERENCE,
                    rationale = "Runtime fully ready with active inference: " +
                        "re-advertise full capability set including local_model_inference."
                )
            } else {
                ReadvertiseDecision(
                    action = ReadvertiseAction.READVERTISE_BASE_ONLY,
                    lifecycleState = lifecycleState,
                    inferenceAvailable = false,
                    allowedCapabilities = CapabilityHonestyGuard.BASE_CAPABILITIES,
                    rationale = "Runtime fully ready but inference not active: " +
                        "re-advertise base capabilities only."
                )
            }
        }

        FormalParticipantLifecycleState.DEGRADED ->
            ReadvertiseDecision(
                action = ReadvertiseAction.READVERTISE_BASE_ONLY,
                lifecycleState = lifecycleState,
                inferenceAvailable = false,
                allowedCapabilities = CapabilityHonestyGuard.BASE_CAPABILITIES,
                rationale = "Runtime degraded: re-advertise base capabilities only; " +
                    "inference capability must not be included."
            )

        FormalParticipantLifecycleState.STARTING,
        FormalParticipantLifecycleState.RECOVERING ->
            ReadvertiseDecision(
                action = ReadvertiseAction.SUPPRESS_UNTIL_STABLE,
                lifecycleState = lifecycleState,
                inferenceAvailable = false,
                allowedCapabilities = emptySet(),
                rationale = "Runtime not yet stable ($lifecycleState): " +
                    "suppress capability advertisement until state resolves."
            )

        FormalParticipantLifecycleState.UNAVAILABLE_FAILED ->
            ReadvertiseDecision(
                action = ReadvertiseAction.BLOCK_READVERTISE,
                lifecycleState = lifecycleState,
                inferenceAvailable = false,
                allowedCapabilities = emptySet(),
                rationale = "Runtime unavailable or failed: " +
                    "no capability advertisement is permitted."
            )
    }

    // ── Heartbeat consistency requirement ────────────────────────────────────

    /**
     * Returns `true` when the given capability sets are consistent for heartbeat purposes.
     *
     * After re-advertisement, all subsequent heartbeat messages must carry the same capability
     * set as the most recent re-advertisement.  An inconsistency between the heartbeat
     * capability set and the last re-advertised set indicates a protocol alignment failure.
     *
     * @param readvertisedCapabilities  The capability set from the most recent re-advertisement.
     * @param heartbeatCapabilities     The capability set carried in the current heartbeat.
     * @return `true` if the sets match; `false` if a heartbeat consistency violation is detected.
     */
    fun heartbeatMustMatchReadvertise(
        readvertisedCapabilities: Set<String>,
        heartbeatCapabilities: Set<String>
    ): Boolean = readvertisedCapabilities == heartbeatCapabilities

    // ── Re-registration requirement ──────────────────────────────────────────

    /**
     * Returns `true` when transitioning from [previousState] to [newState] requires
     * the participant to re-register with the control plane.
     *
     * Re-registration (sending a new `capability_report` and heartbeat aligned to the
     * new capability set) is required whenever:
     *  - The participant transitions from a capability-blocked state ([STARTING],
     *    [RECOVERING], [UNAVAILABLE_FAILED]) to a capability-advertising state
     *    ([READY], [DEGRADED]).
     *
     * This ensures the control plane sees a fresh, honest capability report after recovery.
     */
    fun requiresReRegistration(
        previousState: FormalParticipantLifecycleState,
        newState: FormalParticipantLifecycleState
    ): Boolean = FormalParticipantLifecycleState.requiresCapabilityReAdvertise(
        previousState = previousState,
        newState = newState
    )

    // ── Invariant constants ───────────────────────────────────────────────────

    /** Total number of [ReadvertiseAction] variants. */
    const val READVERTISE_ACTION_COUNT: Int = 4

    /** Number of [FormalParticipantLifecycleState] values that block advertisement. */
    const val BLOCKED_STATE_COUNT: Int = 3

    /** Number of [FormalParticipantLifecycleState] values that permit advertisement. */
    const val ADVERTISING_STATE_COUNT: Int = 2
}
