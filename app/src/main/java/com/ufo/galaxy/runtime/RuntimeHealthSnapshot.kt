package com.ufo.galaxy.runtime

/**
 * Immutable snapshot of the health of both local inference runtimes at a point in time.
 *
 * Obtained via [LocalInferenceRuntimeManager.healthCheck]. Consumers (diagnostics UI,
 * readiness providers, recovery logic) should treat this as a point-in-time view and
 * re-query if they need fresh data.
 *
 * @property plannerHealth   Health of the MobileVLM planner runtime.
 * @property groundingHealth Health of the SeeClick grounding runtime.
 * @property snapshotTimeMs  Wall-clock time when this snapshot was taken (ms since epoch).
 * @property plannerError    Failure message for the planner; null when healthy.
 * @property groundingError  Failure message for the grounding engine; null when healthy.
 */
data class RuntimeHealthSnapshot(
    val plannerHealth: ComponentHealth,
    val groundingHealth: ComponentHealth,
    val snapshotTimeMs: Long = System.currentTimeMillis(),
    val plannerError: String? = null,
    val groundingError: String? = null
) {

    /**
     * Health state of a single runtime component.
     */
    enum class ComponentHealth {
        /** Component passed health check and dry-run inference. */
        HEALTHY,

        /**
         * Component is partially available: health endpoint reachable but
         * dry-run or response validation failed.
         */
        DEGRADED,

        /** Component is not reachable or has failed. */
        UNHEALTHY,

        /** Component state has not been checked yet. */
        UNKNOWN
    }

    /** True when both planner and grounding are [ComponentHealth.HEALTHY]. */
    val isFullyHealthy: Boolean
        get() = plannerHealth == ComponentHealth.HEALTHY &&
            groundingHealth == ComponentHealth.HEALTHY

    /**
     * Summarises both components into a single [ComponentHealth]:
     * - [ComponentHealth.HEALTHY] when both are healthy.
     * - [ComponentHealth.UNHEALTHY] when either is unhealthy.
     * - [ComponentHealth.DEGRADED] otherwise (at least one degraded, none unhealthy).
     * - [ComponentHealth.UNKNOWN] when both are unknown.
     */
    val overallHealth: ComponentHealth
        get() = when {
            plannerHealth == ComponentHealth.HEALTHY &&
                groundingHealth == ComponentHealth.HEALTHY ->
                ComponentHealth.HEALTHY

            plannerHealth == ComponentHealth.UNHEALTHY ||
                groundingHealth == ComponentHealth.UNHEALTHY ->
                ComponentHealth.UNHEALTHY

            plannerHealth == ComponentHealth.UNKNOWN &&
                groundingHealth == ComponentHealth.UNKNOWN ->
                ComponentHealth.UNKNOWN

            else -> ComponentHealth.DEGRADED
        }

    companion object {
        /** Returns an all-UNKNOWN snapshot. Useful as an initial/default value. */
        fun unknown(): RuntimeHealthSnapshot = RuntimeHealthSnapshot(
            plannerHealth = ComponentHealth.UNKNOWN,
            groundingHealth = ComponentHealth.UNKNOWN
        )
    }
}
