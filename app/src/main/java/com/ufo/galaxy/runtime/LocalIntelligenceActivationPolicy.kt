package com.ufo.galaxy.runtime

/**
 * PR-73 (Android) — Formal default activation policy for Android local intelligence capabilities.
 *
 * This object defines the **canonical activation tier** and **fallback semantics** for every
 * Android local intelligence capability dimension.  It resolves the architectural gap between
 * "capability structure exists" and "capability is default-mainline active":
 *
 *  - Previously, reviewers and acceptance consumers could mistake a structurally-present
 *    runtime component for a fully-active default-mainline capability.
 *  - This policy surface makes the distinction explicit and machine-consumable.
 *
 * ## Capability matrix
 *
 * | Capability                  | [ActivationTier]     | Default mainline | Guard condition                            |
 * |-----------------------------|----------------------|------------------|--------------------------------------------|
 * | [Capability.RUNTIME_HOST]   | [ActivationTier.DEFAULT_ON]      | yes | Basic participation conditions met        |
 * | [Capability.GUI_INTERACTION]| [ActivationTier.DEFAULT_ON]      | yes | Accessibility + overlay services ready    |
 * | [Capability.PLANNER]        | [ActivationTier.GUARDED_ON]      | no  | Planner model loaded + runtime healthy    |
 * | [Capability.GROUNDING]      | [ActivationTier.GUARDED_ON]      | no  | Grounding model loaded + runtime healthy  |
 * | [Capability.LOCAL_INFERENCE]| [ActivationTier.GUARDED_ON]      | no  | Both planner AND grounding active         |
 *
 * ## Activation tiers
 *
 * | [ActivationTier]           | Meaning                                                           |
 * |----------------------------|-------------------------------------------------------------------|
 * | [ActivationTier.DEFAULT_ON]           | Active in every healthy session; no extra guard required.         |
 * | [ActivationTier.GUARDED_ON]           | Active only when explicit health/readiness conditions are met.    |
 * | [ActivationTier.OPTIONAL]             | Opt-in; not part of default mainline.                             |
 * | [ActivationTier.EXPERIMENTAL]         | Experimental; not production mainline.                            |
 * | [ActivationTier.DISABLED_BY_DEFAULT]  | Explicitly off; must be explicitly enabled.                       |
 *
 * ## Fallback semantics
 *
 * [FallbackScenario] enumerates the scenarios under which capabilities must be re-evaluated
 * downward.  The [evaluateActivation] method enforces these semantics: degraded / partial-ready /
 * no-model states are never optimistically promoted to fully-active.
 *
 * @see LocalIntelligenceActivationPolicySurface
 * @see FormalParticipantLifecycleState
 * @see LocalIntelligenceCapabilityStatus
 * @see CapabilityHonestyGuard
 */
object LocalIntelligenceActivationPolicy {

    // ── Activation tier ───────────────────────────────────────────────────────

    /**
     * Formal classification of a capability's default activation posture.
     *
     * The tier answers the question: "Is this capability part of the default mainline,
     * and under what conditions does it activate?"
     *
     * @property wireValue  Stable lowercase string for serialisation to metadata payloads.
     * @property isDefaultMainline `true` when the capability is part of the default mainline
     *                             without additional guard conditions.
     */
    enum class ActivationTier(val wireValue: String, val isDefaultMainline: Boolean) {
        /**
         * The capability is active by default in every healthy runtime session.
         * No guard conditions beyond basic participant health are required.
         * This capability is part of the **default mainline**.
         */
        DEFAULT_ON("default_on", isDefaultMainline = true),

        /**
         * The capability is active only when explicit health / readiness guard
         * conditions are satisfied.  It is NOT part of the unconditional default mainline;
         * it becomes mainline only after the guard passes.
         */
        GUARDED_ON("guarded_on", isDefaultMainline = false),

        /**
         * The capability is available as an opt-in feature but is NOT part of
         * the default mainline.  Must be explicitly requested.
         */
        OPTIONAL("optional", isDefaultMainline = false),

        /**
         * The capability is experimental and NOT production mainline.
         * May be unstable or incomplete.
         */
        EXPERIMENTAL("experimental", isDefaultMainline = false),

        /**
         * The capability is explicitly disabled by default.
         * Must be explicitly enabled through policy or configuration.
         */
        DISABLED_BY_DEFAULT("disabled_by_default", isDefaultMainline = false);

        companion object {
            /** Parses [wireValue], returning `null` for unrecognised values. */
            fun fromWireValue(value: String?): ActivationTier? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Capability enumeration ─────────────────────────────────────────────────

    /**
     * Enumeration of all Android local intelligence capability dimensions.
     *
     * Each capability carries its [ActivationTier] and a human-readable description of
     * the guard condition (if any) that must be satisfied before it may activate.
     *
     * @property wireValue           Stable lowercase string for serialisation.
     * @property tier                The formal [ActivationTier] for this capability.
     * @property description         Human-readable description.
     * @property guardDescription    Human-readable guard condition, or `null` for [ActivationTier.DEFAULT_ON].
     */
    enum class Capability(
        val wireValue: String,
        val tier: ActivationTier,
        val description: String,
        val guardDescription: String?
    ) {
        /**
         * Runtime host participation in the multi-device formation.
         * Active by default in every healthy session.
         */
        RUNTIME_HOST(
            wireValue = "runtime_host",
            tier = ActivationTier.DEFAULT_ON,
            description = "Android runtime host participates in the multi-device formation",
            guardDescription = null
        ),

        /**
         * GUI interaction-related intelligence via accessibility + overlay services.
         * Active by default whenever accessibility and overlay services are ready.
         */
        GUI_INTERACTION(
            wireValue = "gui_interaction",
            tier = ActivationTier.DEFAULT_ON,
            description = "GUI interaction intelligence via accessibility and overlay services",
            guardDescription = null
        ),

        /**
         * Local task planner (MobileVLM).
         * Guarded: active only when the planner model is loaded and the runtime is healthy.
         */
        PLANNER(
            wireValue = "local_planner",
            tier = ActivationTier.GUARDED_ON,
            description = "Local task planning via on-device MobileVLM planner",
            guardDescription = "Planner model loaded and planner runtime healthy"
        ),

        /**
         * Local UI grounding (SeeClick).
         * Guarded: active only when the grounding model is loaded and the runtime is healthy.
         */
        GROUNDING(
            wireValue = "local_grounding",
            tier = ActivationTier.GUARDED_ON,
            description = "Local UI grounding via on-device SeeClick model",
            guardDescription = "Grounding model loaded and grounding runtime healthy"
        ),

        /**
         * Full local model inference (planner + grounding combined).
         * Guarded: active only when BOTH planner AND grounding are active.
         */
        LOCAL_INFERENCE(
            wireValue = "local_model_inference",
            tier = ActivationTier.GUARDED_ON,
            description = "Full local model inference combining planner and grounding",
            guardDescription = "Both planner AND grounding runtimes are simultaneously healthy"
        );

        companion object {
            /** All capabilities that are [ActivationTier.DEFAULT_ON]. */
            val DEFAULT_ON_SET: Set<Capability> =
                entries.filter { it.tier == ActivationTier.DEFAULT_ON }.toSet()

            /** All capabilities that are [ActivationTier.GUARDED_ON]. */
            val GUARDED_ON_SET: Set<Capability> =
                entries.filter { it.tier == ActivationTier.GUARDED_ON }.toSet()

            /** Parses [wireValue], returning `null` for unrecognised values. */
            fun fromWireValue(value: String?): Capability? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Fallback scenario ─────────────────────────────────────────────────────

    /**
     * Enumeration of the scenarios under which capability activation must be re-evaluated
     * downward from the nominal active state.
     *
     * These scenarios prevent "optimistic upgrading" — the rule that a capability in a
     * degraded, partial-ready, or no-model state must NEVER be reported as fully active.
     *
     * @property wireValue    Stable lowercase string for serialisation.
     * @property description  Human-readable description.
     */
    enum class FallbackScenario(val wireValue: String, val description: String) {
        /**
         * One or more model files are missing or failed to load.
         * All [ActivationTier.GUARDED_ON] capabilities depending on that model are blocked.
         */
        MODEL_UNAVAILABLE(
            wireValue = "model_unavailable",
            description = "Model file missing or failed to load; guarded capabilities blocked"
        ),

        /**
         * Only a subset of the required runtime components started successfully.
         * Capabilities requiring all components are blocked; partial capabilities may remain.
         */
        PARTIAL_READINESS(
            wireValue = "partial_readiness",
            description = "Partial startup: only a subset of runtime components are ready"
        ),

        /**
         * The runtime is operational but health checks indicate reduced capability.
         * Capabilities beyond the degraded-allowed set are blocked.
         */
        DEGRADED_RUNTIME(
            wireValue = "degraded_runtime",
            description = "Runtime degraded; only base capabilities active"
        ),

        /**
         * The runtime has recovered from a failure but is operating at reduced capacity.
         * Guarded capabilities are re-evaluated conservatively; no optimistic promotion.
         */
        RECOVERED_BUT_LIMITED(
            wireValue = "recovered_but_limited",
            description = "Recovered from failure but operating at reduced capacity"
        ),

        /**
         * The runtime is explicitly disabled, no model is present, or the state is
         * partially ready.  These states MUST NOT be promoted to fully active.
         */
        DISABLED_NO_MODEL_PARTIAL_READY(
            wireValue = "disabled_no_model_partial_ready",
            description = "Disabled / no-model / partial-ready: must not be promoted to fully active"
        );

        companion object {
            /** Parses [wireValue], returning `null` for unrecognised values. */
            fun fromWireValue(value: String?): FallbackScenario? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Activation result ─────────────────────────────────────────────────────

    /**
     * Result of evaluating a single capability's activation under a given runtime state.
     *
     * @property capability          The capability that was evaluated.
     * @property isActive            `true` when the capability is currently active.
     * @property isDefaultMainline   `true` when the capability is [ActivationTier.DEFAULT_ON]
     *                               AND [isActive] is `true`.
     * @property isGuardedActive     `true` when the capability is [ActivationTier.GUARDED_ON]
     *                               AND [isActive] is `true` (guard conditions met).
     * @property activeFallbackScenario The [FallbackScenario] that caused the capability to
     *                               be inactive, or `null` when the capability is active.
     * @property reason              Human-readable explanation for the activation result.
     */
    data class CapabilityActivationResult(
        val capability: Capability,
        val isActive: Boolean,
        val isDefaultMainline: Boolean,
        val isGuardedActive: Boolean,
        val activeFallbackScenario: FallbackScenario?,
        val reason: String
    )

    // ── Evaluation inputs ─────────────────────────────────────────────────────

    /**
     * Runtime inputs required to evaluate capability activation.
     *
     * All fields default to the most conservative (blocked) value so that omitting a
     * field never accidentally enables a guarded capability.
     *
     * @property formalLifecycleState     Current [FormalParticipantLifecycleState].
     * @property inferenceStatus          Current [LocalIntelligenceCapabilityStatus].
     * @property plannerModelLoaded       `true` when the planner model is loaded and healthy.
     * @property groundingModelLoaded     `true` when the grounding model is loaded and healthy.
     * @property accessibilityReady       `true` when accessibility services are available.
     * @property overlayReady             `true` when the overlay service is available.
     * @property runtimeHostActive        `true` when the runtime host descriptor indicates ACTIVE participation.
     */
    data class ActivationInputs(
        val formalLifecycleState: FormalParticipantLifecycleState,
        val inferenceStatus: LocalIntelligenceCapabilityStatus,
        val plannerModelLoaded: Boolean = false,
        val groundingModelLoaded: Boolean = false,
        val accessibilityReady: Boolean = false,
        val overlayReady: Boolean = false,
        val runtimeHostActive: Boolean = false
    )

    // ── Core evaluation ───────────────────────────────────────────────────────

    /**
     * Evaluates the activation of a single [capability] against [inputs].
     *
     * This is the canonical truth function for capability activation.  It enforces
     * the anti-optimistic-upgrade invariant: degraded / partial-ready / no-model states
     * are NEVER promoted to fully active.
     *
     * @param capability The [Capability] to evaluate.
     * @param inputs     The [ActivationInputs] describing current runtime state.
     * @return [CapabilityActivationResult] describing the activation outcome.
     */
    fun evaluateActivation(
        capability: Capability,
        inputs: ActivationInputs
    ): CapabilityActivationResult = when (capability) {

        Capability.RUNTIME_HOST -> evaluateRuntimeHost(inputs)

        Capability.GUI_INTERACTION -> evaluateGuiInteraction(inputs)

        Capability.PLANNER -> evaluatePlanner(inputs)

        Capability.GROUNDING -> evaluateGrounding(inputs)

        Capability.LOCAL_INFERENCE -> evaluateLocalInference(inputs)
    }

    /**
     * Evaluates all capabilities against [inputs] and returns the full activation snapshot.
     *
     * @param inputs The [ActivationInputs] describing current runtime state.
     * @return Map of [Capability] → [CapabilityActivationResult].
     */
    fun evaluateAll(inputs: ActivationInputs): Map<Capability, CapabilityActivationResult> =
        Capability.entries.associateWith { evaluateActivation(it, inputs) }

    // ── Per-capability evaluation ─────────────────────────────────────────────

    private fun evaluateRuntimeHost(inputs: ActivationInputs): CapabilityActivationResult {
        val capability = Capability.RUNTIME_HOST
        val lifecycleBlocked = isLifecycleBlocked(inputs.formalLifecycleState)
        return if (!lifecycleBlocked && inputs.runtimeHostActive) {
            CapabilityActivationResult(
                capability = capability,
                isActive = true,
                isDefaultMainline = true,
                isGuardedActive = false,
                activeFallbackScenario = null,
                reason = "Runtime host is ACTIVE and lifecycle permits participation"
            )
        } else {
            val fallback = deriveFallbackScenario(inputs, requiresInference = false)
            CapabilityActivationResult(
                capability = capability,
                isActive = false,
                isDefaultMainline = false,
                isGuardedActive = false,
                activeFallbackScenario = fallback,
                reason = buildInactiveReason(capability, inputs.formalLifecycleState, fallback)
            )
        }
    }

    private fun evaluateGuiInteraction(inputs: ActivationInputs): CapabilityActivationResult {
        val capability = Capability.GUI_INTERACTION
        val lifecycleBlocked = isLifecycleBlocked(inputs.formalLifecycleState)
        return if (!lifecycleBlocked && inputs.accessibilityReady && inputs.overlayReady) {
            CapabilityActivationResult(
                capability = capability,
                isActive = true,
                isDefaultMainline = true,
                isGuardedActive = false,
                activeFallbackScenario = null,
                reason = "Accessibility and overlay services ready; lifecycle permits"
            )
        } else {
            val fallback = if (lifecycleBlocked) {
                deriveFallbackScenario(inputs, requiresInference = false)
            } else {
                FallbackScenario.DISABLED_NO_MODEL_PARTIAL_READY
            }
            CapabilityActivationResult(
                capability = capability,
                isActive = false,
                isDefaultMainline = false,
                isGuardedActive = false,
                activeFallbackScenario = fallback,
                reason = buildInactiveReason(capability, inputs.formalLifecycleState, fallback)
            )
        }
    }

    private fun evaluatePlanner(inputs: ActivationInputs): CapabilityActivationResult {
        val capability = Capability.PLANNER
        val lifecycleOk = inputs.formalLifecycleState == FormalParticipantLifecycleState.READY ||
            inputs.formalLifecycleState == FormalParticipantLifecycleState.DEGRADED
        return if (lifecycleOk && inputs.plannerModelLoaded) {
            CapabilityActivationResult(
                capability = capability,
                isActive = true,
                isDefaultMainline = false,
                isGuardedActive = true,
                activeFallbackScenario = null,
                reason = "Planner model loaded and lifecycle state permits guarded activation"
            )
        } else {
            val fallback = if (!inputs.plannerModelLoaded) {
                FallbackScenario.MODEL_UNAVAILABLE
            } else {
                deriveFallbackScenario(inputs, requiresInference = false)
            }
            CapabilityActivationResult(
                capability = capability,
                isActive = false,
                isDefaultMainline = false,
                isGuardedActive = false,
                activeFallbackScenario = fallback,
                reason = buildInactiveReason(capability, inputs.formalLifecycleState, fallback)
            )
        }
    }

    private fun evaluateGrounding(inputs: ActivationInputs): CapabilityActivationResult {
        val capability = Capability.GROUNDING
        val lifecycleOk = inputs.formalLifecycleState == FormalParticipantLifecycleState.READY ||
            inputs.formalLifecycleState == FormalParticipantLifecycleState.DEGRADED
        return if (lifecycleOk && inputs.groundingModelLoaded) {
            CapabilityActivationResult(
                capability = capability,
                isActive = true,
                isDefaultMainline = false,
                isGuardedActive = true,
                activeFallbackScenario = null,
                reason = "Grounding model loaded and lifecycle state permits guarded activation"
            )
        } else {
            val fallback = if (!inputs.groundingModelLoaded) {
                FallbackScenario.MODEL_UNAVAILABLE
            } else {
                deriveFallbackScenario(inputs, requiresInference = false)
            }
            CapabilityActivationResult(
                capability = capability,
                isActive = false,
                isDefaultMainline = false,
                isGuardedActive = false,
                activeFallbackScenario = fallback,
                reason = buildInactiveReason(capability, inputs.formalLifecycleState, fallback)
            )
        }
    }

    private fun evaluateLocalInference(inputs: ActivationInputs): CapabilityActivationResult {
        val capability = Capability.LOCAL_INFERENCE
        val inferenceActive = inputs.inferenceStatus == LocalIntelligenceCapabilityStatus.ACTIVE
        val lifecycleReady = inputs.formalLifecycleState == FormalParticipantLifecycleState.READY
        return if (lifecycleReady && inferenceActive && inputs.plannerModelLoaded && inputs.groundingModelLoaded) {
            CapabilityActivationResult(
                capability = capability,
                isActive = true,
                isDefaultMainline = false,
                isGuardedActive = true,
                activeFallbackScenario = null,
                reason = "Both planner and grounding active; lifecycle READY; inference ACTIVE"
            )
        } else {
            val fallback = when {
                !inputs.plannerModelLoaded || !inputs.groundingModelLoaded ->
                    FallbackScenario.MODEL_UNAVAILABLE
                inputs.formalLifecycleState == FormalParticipantLifecycleState.DEGRADED ->
                    FallbackScenario.DEGRADED_RUNTIME
                inputs.formalLifecycleState == FormalParticipantLifecycleState.RECOVERING ->
                    FallbackScenario.RECOVERED_BUT_LIMITED
                inputs.inferenceStatus == LocalIntelligenceCapabilityStatus.DEGRADED ->
                    FallbackScenario.PARTIAL_READINESS
                else -> deriveFallbackScenario(inputs, requiresInference = true)
            }
            CapabilityActivationResult(
                capability = capability,
                isActive = false,
                isDefaultMainline = false,
                isGuardedActive = false,
                activeFallbackScenario = fallback,
                reason = buildInactiveReason(capability, inputs.formalLifecycleState, fallback)
            )
        }
    }

    // ── Fallback scenario derivation ──────────────────────────────────────────

    private fun isLifecycleBlocked(state: FormalParticipantLifecycleState): Boolean =
        !FormalParticipantLifecycleState.capabilityAdvertisementAllowed(state)

    private fun deriveFallbackScenario(
        inputs: ActivationInputs,
        requiresInference: Boolean
    ): FallbackScenario = when (inputs.formalLifecycleState) {
        FormalParticipantLifecycleState.RECOVERING -> FallbackScenario.RECOVERED_BUT_LIMITED
        FormalParticipantLifecycleState.DEGRADED   -> FallbackScenario.DEGRADED_RUNTIME
        FormalParticipantLifecycleState.STARTING   -> FallbackScenario.PARTIAL_READINESS
        FormalParticipantLifecycleState.UNAVAILABLE_FAILED -> FallbackScenario.DISABLED_NO_MODEL_PARTIAL_READY
        FormalParticipantLifecycleState.READY ->
            if (requiresInference &&
                inputs.inferenceStatus != LocalIntelligenceCapabilityStatus.ACTIVE
            ) FallbackScenario.PARTIAL_READINESS
            else FallbackScenario.DISABLED_NO_MODEL_PARTIAL_READY
    }

    private fun buildInactiveReason(
        capability: Capability,
        lifecycleState: FormalParticipantLifecycleState,
        fallback: FallbackScenario?
    ): String {
        val scenario = fallback?.description ?: "no specific fallback scenario"
        return "Capability '${capability.wireValue}' is INACTIVE in lifecycle state " +
            "'${lifecycleState.wireValue}': $scenario"
    }

    // ── Policy query helpers ──────────────────────────────────────────────────

    /**
     * Returns the set of capabilities that are **default mainline** (tier = [ActivationTier.DEFAULT_ON]).
     *
     * These capabilities do NOT require model loading or inference health checks;
     * they are part of the default active set whenever the participant is healthy.
     *
     * Note: a capability being [ActivationTier.DEFAULT_ON] does not mean it is currently
     * active — use [evaluateActivation] with [ActivationInputs] to determine actual activation.
     */
    fun defaultMainlineCapabilities(): Set<Capability> = Capability.DEFAULT_ON_SET

    /**
     * Returns the set of capabilities that are **guarded** (tier = [ActivationTier.GUARDED_ON]).
     *
     * These capabilities activate only when their guard conditions are met.
     * Structure-present alone does NOT qualify as guarded-active.
     */
    fun guardedCapabilities(): Set<Capability> = Capability.GUARDED_ON_SET

    // ── Invariant constants ───────────────────────────────────────────────────

    /** PR that introduced this surface. */
    const val INTRODUCED_PR: Int = 73

    /** Human-readable PR title. */
    const val INTRODUCED_PR_TITLE: String =
        "PR-73 Android: formal default activation policy for local intelligence capabilities"
}
