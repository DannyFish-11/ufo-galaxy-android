package com.ufo.galaxy.runtime

/**
 * Canonical executor target type values introduced by V2's explicit target-typing model (PR-E).
 *
 * V2 makes executor target routing explicit: commands now carry an [executor_target_type] field
 * that unambiguously identifies which execution surface a command is addressed to.  Android-side
 * handlers must accept this field, validate it with a safe-unknown-value fallback, and tolerate
 * its absence (null) for backward compatibility with pre-V2 senders.
 *
 * ## Target types
 * | Constant           | Wire value        | Meaning                                      |
 * |--------------------|-------------------|----------------------------------------------|
 * | [ANDROID_DEVICE]   | `"android_device"`| Task is explicitly addressed to an Android   |
 * |                    |                   | runtime-host device.                         |
 * | [NODE_SERVICE]     | `"node_service"`  | Task addressed to a background node service. |
 * | [WORKER]           | `"worker"`        | Task addressed to a worker/agent process.    |
 * | [LOCAL]            | `"local"`         | Task must run in the local execution context.|
 *
 * ## Android-side contract
 * - Android devices should accept commands where [executor_target_type] is `null` (legacy),
 *   [ANDROID_DEVICE], or [LOCAL].
 * - Commands explicitly addressed to [NODE_SERVICE] or [WORKER] arrive on Android only in
 *   error or cross-routing scenarios; Android logs them but does not reject them, to preserve
 *   forward compatibility.
 * - All values should be parsed via [fromValue]; unknown strings resolve to `null`, which
 *   callers must treat as "unspecified" (backward-compatible pass-through).
 */
object ExecutorTargetType {

    /** Task is explicitly addressed to an Android runtime-host device. */
    const val ANDROID_DEVICE = "android_device"

    /** Task is addressed to a background node service. */
    const val NODE_SERVICE = "node_service"

    /** Task is addressed to a worker / agent process. */
    const val WORKER = "worker"

    /** Task must run in the local execution context. */
    const val LOCAL = "local"

    /** All recognised executor target type wire values. */
    val ALL_VALUES: Set<String> = setOf(ANDROID_DEVICE, NODE_SERVICE, WORKER, LOCAL)

    /**
     * Target type values that Android devices are eligible to execute.
     *
     * A command is considered Android-targeted when its [executor_target_type] is either
     * absent (`null`) — meaning "unspecified / any device" for backward compatibility —
     * or one of the values in this set.
     */
    val ANDROID_ELIGIBLE_VALUES: Set<String?> = setOf(null, ANDROID_DEVICE, LOCAL)

    /**
     * Parses [value] to its canonical string, or returns `null` for unknown / absent values.
     *
     * Callers must treat a `null` return as "unspecified" and apply backward-compatible
     * handling rather than rejecting the command.
     *
     * @param value Raw executor target type string from a JSON payload.
     * @return The canonical string when [value] is a known type, or `null` otherwise.
     */
    fun fromValue(value: String?): String? = if (value != null && value in ALL_VALUES) value else null

    /**
     * Returns `true` when [targetType] identifies this Android device as the intended executor.
     *
     * Specifically, returns `true` when [targetType] is `null` (unspecified, backward-compatible
     * with pre-V2 senders), [ANDROID_DEVICE], or [LOCAL].
     *
     * @param targetType Parsed executor target type string (or `null` for legacy payloads).
     */
    fun isAndroidEligible(targetType: String?): Boolean = targetType in ANDROID_ELIGIBLE_VALUES
}
