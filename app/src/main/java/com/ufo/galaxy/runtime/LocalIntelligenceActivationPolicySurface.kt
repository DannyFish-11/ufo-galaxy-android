package com.ufo.galaxy.runtime

/**
 * PR-73 (Android) — Runtime surface for consuming the Android local intelligence
 * default activation policy.
 *
 * [LocalIntelligenceActivationPolicySurface] bridges the formal
 * [LocalIntelligenceActivationPolicy] with the live runtime state provided by:
 *  - [LocalInferenceRuntimeManager] (manager state → [FormalParticipantLifecycleState])
 *  - [LocalIntelligenceCapabilityStatus] (inference status)
 *  - [RuntimeHostDescriptor] (runtime host participation state)
 *
 * ## Primary output: [ActivationSnapshot]
 *
 * [buildSnapshot] produces a structured [ActivationSnapshot] that reviewers and
 * acceptance consumers can query to determine:
 *  - Which capabilities are currently active (with their activation tier)
 *  - Which capabilities are default-mainline vs. guarded-active
 *  - Whether the runtime is genuinely fully-active or only structurally present
 *  - The fallback scenario for each inactive capability
 *
 * ## Anti-optimistic-upgrade guarantee
 *
 * [ActivationSnapshot.isStructureOnlyNotMainlineActive] returns `true` whenever the
 * runtime is structurally present (descriptor/components exist) but the formal
 * activation evaluation shows the local intelligence subsystem is NOT default-mainline
 * active.  This surfaces the "structure exists ≠ fully active" distinction that was
 * previously invisible to reviewers.
 *
 * ## Connection to existing surfaces
 *
 * [ActivationSnapshot] is designed to compose with:
 *  - [AndroidReadinessEvidenceSurface] — readiness dimension evidence
 *  - [CapabilityHonestyGuard] — honesty validation before capability advertisement
 *  - [RecoveryCapabilityAlignmentGuard] — post-recovery alignment checks
 *  - [FormalParticipantLifecycleState] — lifecycle-level capability gates
 *
 * @see LocalIntelligenceActivationPolicy
 * @see AndroidReadinessEvidenceSurface
 * @see CapabilityHonestyGuard
 * @see RecoveryCapabilityAlignmentGuard
 */
object LocalIntelligenceActivationPolicySurface {

    // ── Activation snapshot ───────────────────────────────────────────────────

    /**
     * A point-in-time snapshot of all capability activations evaluated against current
     * runtime state.
     *
     * @property results                  Per-capability activation evaluation results.
     * @property activeCapabilities       Capabilities that are currently active.
     * @property defaultMainlineActive    Capabilities that are active AND default-mainline.
     * @property guardedActive            Capabilities that are active AND guarded (guard passed).
     * @property inactiveCapabilities     Capabilities that are currently inactive.
     * @property isFullyActive            `true` when ALL capabilities are active.
     * @property hasAnyActive             `true` when at least one capability is active.
     * @property isStructureOnlyNotMainlineActive `true` when structural components exist but
     *                                   none of the guarded local intelligence capabilities
     *                                   (planner, grounding, local_inference) are active.
     *                                   This is the machine-readable form of the
     *                                   "structure present ≠ default-mainline active" gap.
     * @property formalLifecycleState     The [FormalParticipantLifecycleState] at snapshot time.
     * @property inferenceStatus          The [LocalIntelligenceCapabilityStatus] at snapshot time.
     * @property snapshotAtMs             Epoch-millisecond timestamp of this snapshot.
     */
    data class ActivationSnapshot(
        val results: Map<LocalIntelligenceActivationPolicy.Capability,
            LocalIntelligenceActivationPolicy.CapabilityActivationResult>,
        val formalLifecycleState: FormalParticipantLifecycleState,
        val inferenceStatus: LocalIntelligenceCapabilityStatus,
        val snapshotAtMs: Long = System.currentTimeMillis()
    ) {
        /** Capabilities that are currently active. */
        val activeCapabilities: Set<LocalIntelligenceActivationPolicy.Capability> =
            results.filterValues { it.isActive }.keys

        /** Capabilities that are active AND default-mainline (tier = DEFAULT_ON). */
        val defaultMainlineActive: Set<LocalIntelligenceActivationPolicy.Capability> =
            results.filterValues { it.isDefaultMainline }.keys

        /** Capabilities that are active AND guarded (guard passed, tier = GUARDED_ON). */
        val guardedActive: Set<LocalIntelligenceActivationPolicy.Capability> =
            results.filterValues { it.isGuardedActive }.keys

        /** Capabilities that are currently inactive. */
        val inactiveCapabilities: Set<LocalIntelligenceActivationPolicy.Capability> =
            results.filterValues { !it.isActive }.keys

        /** `true` when ALL capability evaluations are active. */
        val isFullyActive: Boolean =
            results.isNotEmpty() && results.values.all { it.isActive }

        /** `true` when at least one capability is active. */
        val hasAnyActive: Boolean = results.values.any { it.isActive }

        /**
         * `true` when the local intelligence runtime components are structurally present
         * (lifecycle state is READY or DEGRADED) but none of the guarded capabilities
         * (planner, grounding, local_inference) have passed their guard conditions.
         *
         * This is the canonical machine-readable signal that prevents "structure present"
         * from being misread as "default-mainline fully active".
         */
        val isStructureOnlyNotMainlineActive: Boolean =
            (formalLifecycleState == FormalParticipantLifecycleState.READY ||
                formalLifecycleState == FormalParticipantLifecycleState.DEGRADED) &&
                LocalIntelligenceActivationPolicy.guardedCapabilities().none { cap ->
                    results[cap]?.isGuardedActive == true
                }

        /**
         * Returns the [LocalIntelligenceActivationPolicy.CapabilityActivationResult] for
         * the given [capability].
         */
        fun resultFor(
            capability: LocalIntelligenceActivationPolicy.Capability
        ): LocalIntelligenceActivationPolicy.CapabilityActivationResult? = results[capability]

        /**
         * Returns `true` when the given [capability] is currently active.
         */
        fun isActive(capability: LocalIntelligenceActivationPolicy.Capability): Boolean =
            results[capability]?.isActive == true

        /**
         * Returns `true` when the given [capability] is default-mainline active.
         */
        fun isDefaultMainlineActive(
            capability: LocalIntelligenceActivationPolicy.Capability
        ): Boolean = results[capability]?.isDefaultMainline == true

        /**
         * Returns `true` when the given [capability] is guarded-active (guard passed).
         */
        fun isGuardedActive(
            capability: LocalIntelligenceActivationPolicy.Capability
        ): Boolean = results[capability]?.isGuardedActive == true

        /**
         * Returns the [LocalIntelligenceActivationPolicy.FallbackScenario] for an inactive
         * capability, or `null` if the capability is active.
         */
        fun fallbackScenarioFor(
            capability: LocalIntelligenceActivationPolicy.Capability
        ): LocalIntelligenceActivationPolicy.FallbackScenario? =
            results[capability]?.activeFallbackScenario
    }

    // ── Snapshot builder ──────────────────────────────────────────────────────

    /**
     * Builds an [ActivationSnapshot] from a [LocalInferenceRuntimeManager.ManagerState]
     * and supporting runtime context.
     *
     * @param managerState        Current [LocalInferenceRuntimeManager.ManagerState].
     * @param plannerModelLoaded  `true` when the planner model is confirmed loaded and healthy.
     * @param groundingModelLoaded `true` when the grounding model is confirmed loaded and healthy.
     * @param accessibilityReady  `true` when the accessibility service is available.
     * @param overlayReady        `true` when the overlay service is available.
     * @param runtimeHostActive   `true` when the [RuntimeHostDescriptor] indicates ACTIVE participation.
     * @return [ActivationSnapshot] reflecting the current activation truth.
     */
    fun buildSnapshot(
        managerState: LocalInferenceRuntimeManager.ManagerState,
        plannerModelLoaded: Boolean = false,
        groundingModelLoaded: Boolean = false,
        accessibilityReady: Boolean = false,
        overlayReady: Boolean = false,
        runtimeHostActive: Boolean = false
    ): ActivationSnapshot {
        val formalState = FormalParticipantLifecycleState.fromManagerState(managerState)
        val inferenceStatus = LocalIntelligenceCapabilityStatus.from(managerState)
        val inputs = LocalIntelligenceActivationPolicy.ActivationInputs(
            formalLifecycleState = formalState,
            inferenceStatus = inferenceStatus,
            plannerModelLoaded = plannerModelLoaded,
            groundingModelLoaded = groundingModelLoaded,
            accessibilityReady = accessibilityReady,
            overlayReady = overlayReady,
            runtimeHostActive = runtimeHostActive
        )
        val results = LocalIntelligenceActivationPolicy.evaluateAll(inputs)
        return ActivationSnapshot(
            results = results,
            formalLifecycleState = formalState,
            inferenceStatus = inferenceStatus
        )
    }

    /**
     * Builds an [ActivationSnapshot] directly from a [LocalIntelligenceActivationPolicy.ActivationInputs].
     *
     * Use this overload when you already have a fully-populated [ActivationInputs] object
     * (e.g. in tests or when composing with another surface).
     *
     * @param inputs The [LocalIntelligenceActivationPolicy.ActivationInputs] to evaluate.
     * @return [ActivationSnapshot] reflecting the activation truth for the given inputs.
     */
    fun buildSnapshot(
        inputs: LocalIntelligenceActivationPolicy.ActivationInputs
    ): ActivationSnapshot {
        val results = LocalIntelligenceActivationPolicy.evaluateAll(inputs)
        return ActivationSnapshot(
            results = results,
            formalLifecycleState = inputs.formalLifecycleState,
            inferenceStatus = inputs.inferenceStatus
        )
    }

    // ── Readiness evidence integration ────────────────────────────────────────

    /**
     * Returns a map of capability wire values to their activation tier wire values,
     * suitable for inclusion in `capability_report` metadata payloads.
     *
     * This allows V2 governance / release gates to consume the activation policy
     * as part of the existing readiness evidence protocol.
     *
     * @param snapshot The [ActivationSnapshot] to serialize.
     * @return Map of `capability_wire_value → activation_tier_wire_value`.
     */
    fun toCapabilityTierMetadata(
        snapshot: ActivationSnapshot
    ): Map<String, String> =
        LocalIntelligenceActivationPolicy.Capability.entries.associate { cap ->
            val tierWire = cap.tier.wireValue
            cap.wireValue to tierWire
        }

    /**
     * Returns a map of capability wire values to their current activation state,
     * suitable for inclusion in `capability_report` or readiness evidence payloads.
     *
     * @param snapshot The [ActivationSnapshot] to serialize.
     * @return Map of `capability_wire_value → "active" | "inactive"`.
     */
    fun toCapabilityActivationMetadata(
        snapshot: ActivationSnapshot
    ): Map<String, String> =
        snapshot.results.entries.associate { (cap, result) ->
            cap.wireValue to if (result.isActive) "active" else "inactive"
        }

    // ── Invariant constants ───────────────────────────────────────────────────

    /** PR that introduced this surface. */
    const val INTRODUCED_PR: Int = 73

    /** Human-readable surface name. */
    const val SURFACE_NAME: String =
        "LocalIntelligenceActivationPolicySurface: default activation policy runtime surface"
}
