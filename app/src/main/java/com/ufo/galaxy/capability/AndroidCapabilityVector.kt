package com.ufo.galaxy.capability

import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.runtime.RuntimeHostDescriptor

/**
 * **Canonical Android device capability vector** (PR-6, post-#533 dual-repo
 * runtime unification master plan — Canonical Device Capability & Scheduling Basis).
 *
 * Consolidates all Android-side execution-capability signals into a single, structured
 * value object so that the main runtime (and future scheduler components) can reason
 * about placement and participation using a stable, well-typed basis rather than
 * inspecting ad-hoc metadata flags scattered across [AppSettings],
 * [RuntimeHostDescriptor], and transport-level hints.
 *
 * ## Design intent
 *
 * Before PR-6 the Android side reported capability through two loosely coupled layers:
 *  1. [com.ufo.galaxy.data.CapabilityReport.REQUIRED_METADATA_KEYS] — eight Boolean/String
 *     flags sent in the `capability_report` handshake (readiness signals).
 *  2. [RuntimeHostDescriptor] — formation role and participation state (PR-5).
 *
 * Neither layer expressed *which concrete execution surfaces* this device exposes, nor did
 * they produce a consolidated view the gateway could use to decide placement.
 * [AndroidCapabilityVector] fills that gap by declaring a structured [ExecutionDimension]
 * set and deriving scheduling-relevant Boolean projections from it.
 *
 * ## [ExecutionDimension]
 * Each dimension maps to a specific execution surface that may be available on this device:
 *  - [ExecutionDimension.LOCAL_INFERENCE]           – on-device model inference is ready.
 *  - [ExecutionDimension.ACCESSIBILITY_EXECUTION]   – accessibility + overlay are ready.
 *  - [ExecutionDimension.PARALLEL_SUBTASK]          – parallel subtask routing is enabled.
 *  - [ExecutionDimension.CROSS_DEVICE_COORDINATION] – cross-device + goal execution are on.
 *
 * ## Wire representation
 * [toSchedulingMetadata] returns a flat [Map] that can be merged into any
 * [com.ufo.galaxy.data.CapabilityReport.metadata] payload.  The keys are stable constants
 * defined on the companion object and mirrored in
 * [com.ufo.galaxy.data.CapabilityReport.SCHEDULING_BASIS_METADATA_KEYS].
 *
 * ## Obtaining an instance
 * Use the [from] factory:
 * ```kotlin
 * val vector = AndroidCapabilityVector.from(
 *     settings        = appSettings,
 *     hostDescriptor  = runtimeHostDescriptor
 * )
 * ```
 *
 * @property executionDimensions  Set of execution surfaces available on this device.
 * @property formationRole        Structural role in the multi-device formation.
 * @property participationState   Current runtime lifecycle readiness.
 * @property deviceRole           Logical device role from settings (e.g. "phone", "tablet").
 * @property hostId               Stable per-process host identifier; empty when no
 *                                [RuntimeHostDescriptor] has been initialised.
 */
data class AndroidCapabilityVector(
    val executionDimensions: Set<ExecutionDimension>,
    val formationRole: RuntimeHostDescriptor.FormationRole,
    val participationState: RuntimeHostDescriptor.HostParticipationState,
    val deviceRole: String,
    val hostId: String
) {

    // ── Enums ─────────────────────────────────────────────────────────────────

    /**
     * Discrete execution surface that may be present on an Android runtime host.
     *
     * A device's capability vector is expressed as the *set* of dimensions it currently
     * satisfies.  Dimensions are derived from [AppSettings] at vector-construction time;
     * they reflect runtime state rather than static device specs.
     *
     * @property wireValue Stable lowercase string used in wire metadata payloads.
     */
    enum class ExecutionDimension(val wireValue: String) {
        /**
         * On-device model inference is available.
         * Requires [AppSettings.localModelEnabled] and [AppSettings.modelReady] to be true.
         */
        LOCAL_INFERENCE("local_inference"),

        /**
         * Accessibility-backed UI automation is available.
         * Requires [AppSettings.accessibilityReady] and [AppSettings.overlayReady] to be true.
         */
        ACCESSIBILITY_EXECUTION("accessibility_execution"),

        /**
         * Parallel subtask routing is enabled on this device.
         * Requires [AppSettings.parallelExecutionEnabled] to be true.
         */
        PARALLEL_SUBTASK("parallel_subtask"),

        /**
         * Cross-device coordination and goal execution are enabled.
         * Requires both [AppSettings.crossDeviceEnabled] and [AppSettings.goalExecutionEnabled]
         * to be true.
         */
        CROSS_DEVICE_COORDINATION("cross_device_coordination");

        companion object {
            /**
             * Parses [value] to an [ExecutionDimension], or returns `null` for unknown values.
             *
             * @param value Wire string from a metadata payload; may be null.
             */
            fun fromValue(value: String?): ExecutionDimension? =
                entries.firstOrNull { it.wireValue == value }
        }
    }

    // ── Derived scheduling helpers ────────────────────────────────────────────

    /**
     * Returns `true` when this device can accept autonomous local-execution tasks.
     *
     * Both [ExecutionDimension.LOCAL_INFERENCE] *and*
     * [ExecutionDimension.ACCESSIBILITY_EXECUTION] must be present **and** the device must
     * be in [RuntimeHostDescriptor.HostParticipationState.ACTIVE] state.
     */
    val isEligibleForLocalExecution: Boolean
        get() = participationState == RuntimeHostDescriptor.HostParticipationState.ACTIVE &&
                ExecutionDimension.LOCAL_INFERENCE in executionDimensions &&
                ExecutionDimension.ACCESSIBILITY_EXECUTION in executionDimensions

    /**
     * Returns `true` when this device can participate in cross-device task routing.
     *
     * [ExecutionDimension.CROSS_DEVICE_COORDINATION] must be present **and** the device
     * must be [RuntimeHostDescriptor.HostParticipationState.ACTIVE].
     */
    val isEligibleForCrossDeviceParticipation: Boolean
        get() = participationState == RuntimeHostDescriptor.HostParticipationState.ACTIVE &&
                ExecutionDimension.CROSS_DEVICE_COORDINATION in executionDimensions

    /**
     * Returns `true` when this device is eligible to receive parallel subtask assignments.
     *
     * [ExecutionDimension.PARALLEL_SUBTASK] must be present **and** the device must be
     * [RuntimeHostDescriptor.HostParticipationState.ACTIVE].
     */
    val isEligibleForParallelSubtask: Boolean
        get() = participationState == RuntimeHostDescriptor.HostParticipationState.ACTIVE &&
                ExecutionDimension.PARALLEL_SUBTASK in executionDimensions

    /**
     * Returns `true` when the device holds the [RuntimeHostDescriptor.FormationRole.PRIMARY]
     * formation role.
     */
    val isPrimaryHost: Boolean
        get() = formationRole == RuntimeHostDescriptor.FormationRole.PRIMARY

    // ── Wire serialisation ────────────────────────────────────────────────────

    /**
     * Builds the canonical scheduling-basis metadata map that must be merged into a
     * [com.ufo.galaxy.data.CapabilityReport.metadata] payload before it is sent to the gateway.
     *
     * Keys (all defined as constants on the companion object):
     *  - [KEY_LOCAL_ELIGIBLE]           – [isEligibleForLocalExecution]
     *  - [KEY_CROSS_DEVICE_ELIGIBLE]    – [isEligibleForCrossDeviceParticipation]
     *  - [KEY_PARALLEL_SUBTASK_ELIGIBLE]– [isEligibleForParallelSubtask]
     *  - [KEY_EXECUTION_DIMENSIONS]     – comma-separated [ExecutionDimension.wireValue] list;
     *                                     empty string when [executionDimensions] is empty.
     *
     * The keys are distinct from (and non-overlapping with) both
     * [com.ufo.galaxy.data.CapabilityReport.REQUIRED_METADATA_KEYS] and the runtime-host keys
     * defined on [RuntimeHostDescriptor].
     */
    fun toSchedulingMetadata(): Map<String, Any> = mapOf(
        KEY_LOCAL_ELIGIBLE to isEligibleForLocalExecution,
        KEY_CROSS_DEVICE_ELIGIBLE to isEligibleForCrossDeviceParticipation,
        KEY_PARALLEL_SUBTASK_ELIGIBLE to isEligibleForParallelSubtask,
        KEY_EXECUTION_DIMENSIONS to executionDimensions.joinToString(",") { it.wireValue }
    )

    // ── Factory / companion ───────────────────────────────────────────────────

    companion object {

        // ── Scheduling metadata key constants ─────────────────────────────────

        /**
         * Metadata key indicating whether the device is eligible for local autonomous execution.
         * Value type: Boolean.
         */
        const val KEY_LOCAL_ELIGIBLE = "scheduling_local_eligible"

        /**
         * Metadata key indicating whether the device is eligible for cross-device participation.
         * Value type: Boolean.
         */
        const val KEY_CROSS_DEVICE_ELIGIBLE = "scheduling_cross_device_eligible"

        /**
         * Metadata key indicating whether the device can receive parallel subtask assignments.
         * Value type: Boolean.
         */
        const val KEY_PARALLEL_SUBTASK_ELIGIBLE = "scheduling_parallel_subtask_eligible"

        /**
         * Metadata key carrying the comma-separated list of active [ExecutionDimension.wireValue]
         * strings (or empty string when no dimensions are active).
         * Value type: String.
         */
        const val KEY_EXECUTION_DIMENSIONS = "scheduling_execution_dimensions"

        /**
         * Builds an [AndroidCapabilityVector] by inspecting the live [AppSettings] and,
         * optionally, the current [RuntimeHostDescriptor].
         *
         * ## Dimension derivation rules
         * | Dimension                    | Condition                                               |
         * |------------------------------|---------------------------------------------------------|
         * | [ExecutionDimension.LOCAL_INFERENCE]           | `settings.localModelEnabled && settings.modelReady` |
         * | [ExecutionDimension.ACCESSIBILITY_EXECUTION]   | `settings.accessibilityReady && settings.overlayReady` |
         * | [ExecutionDimension.PARALLEL_SUBTASK]          | `settings.parallelExecutionEnabled` |
         * | [ExecutionDimension.CROSS_DEVICE_COORDINATION] | `settings.crossDeviceEnabled && settings.goalExecutionEnabled` |
         *
         * When [hostDescriptor] is `null` the vector defaults to
         * [RuntimeHostDescriptor.FormationRole.PRIMARY] formation role and
         * [RuntimeHostDescriptor.HostParticipationState.INACTIVE] participation state.
         *
         * @param settings        Live [AppSettings] snapshot used to derive execution dimensions.
         * @param hostDescriptor  Optional [RuntimeHostDescriptor] providing formation role and
         *                        participation state; defaults to primary/inactive when absent.
         */
        fun from(
            settings: AppSettings,
            hostDescriptor: RuntimeHostDescriptor? = null
        ): AndroidCapabilityVector {
            val dimensions = buildSet {
                if (settings.localModelEnabled && settings.modelReady) {
                    add(ExecutionDimension.LOCAL_INFERENCE)
                }
                if (settings.accessibilityReady && settings.overlayReady) {
                    add(ExecutionDimension.ACCESSIBILITY_EXECUTION)
                }
                if (settings.parallelExecutionEnabled) {
                    add(ExecutionDimension.PARALLEL_SUBTASK)
                }
                if (settings.crossDeviceEnabled && settings.goalExecutionEnabled) {
                    add(ExecutionDimension.CROSS_DEVICE_COORDINATION)
                }
            }
            return AndroidCapabilityVector(
                executionDimensions = dimensions,
                formationRole = hostDescriptor?.formationRole
                    ?: RuntimeHostDescriptor.FormationRole.PRIMARY,
                participationState = hostDescriptor?.participationState
                    ?: RuntimeHostDescriptor.HostParticipationState.INACTIVE,
                deviceRole = settings.deviceRole,
                hostId = hostDescriptor?.hostId ?: ""
            )
        }
    }
}
