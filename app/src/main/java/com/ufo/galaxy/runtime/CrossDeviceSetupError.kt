package com.ufo.galaxy.runtime

/**
 * PR-27 — Product-grade cross-device setup, retry, and recovery UX.
 *
 * Typed error classification emitted on [RuntimeController.setupError] whenever a
 * cross-device enablement attempt fails.  Consumers use [category] to present
 * context-appropriate recovery actions:
 *
 * | [Category]                        | Root cause                                              | Recovery action                           |
 * |-----------------------------------|---------------------------------------------------------|-------------------------------------------|
 * | [Category.CONFIGURATION]          | Gateway not configured or has an invalid/placeholder URL.| Open network settings to enter URL.       |
 * | [Category.NETWORK]                | Network unreachable, WS timeout, or server refused.     | Retry connection; verify gateway is live. |
 * | [Category.CAPABILITY_NOT_SATISFIED] | Required permissions or readiness flags are absent.    | Grant missing permissions / readiness.    |
 *
 * @property category   Machine-readable error category; drives recovery UI branch selection.
 * @property reason     Human-readable description forwarded from [RuntimeController]'s internal
 *                      failure handler — identical to the string emitted on
 *                      [RuntimeController.registrationError].
 * @property canRetry   True when retrying the same configuration may succeed (transient failure);
 *                      false for [Category.CONFIGURATION] errors that require a settings change
 *                      before a retry can succeed.
 */
data class CrossDeviceSetupError(
    val category: Category,
    val reason: String,
    val canRetry: Boolean
) {
    /**
     * Machine-readable category of a cross-device setup failure.
     *
     * @property wireValue Stable string safe for structured telemetry payloads.
     */
    enum class Category(val wireValue: String) {
        /**
         * Gateway address is blank or is still a placeholder URL (e.g. "100.x.x.x").
         *
         * Recovery: the user must open network settings and provide a real gateway address
         * before cross-device can be enabled.
         */
        CONFIGURATION("configuration_error"),

        /**
         * Network unreachable, WS connection refused, or WS connection timed out.
         *
         * Recovery: the user may retry immediately (gateway may have been temporarily
         * unavailable) or verify that the gateway service is running.
         */
        NETWORK("network_error"),

        /**
         * Required device capabilities or permissions are absent:
         * accessibility service disabled, SYSTEM_ALERT_WINDOW not granted, or model
         * files not present.
         *
         * Recovery: the user must grant the missing permission or install the required
         * capability before cross-device setup can succeed.
         */
        CAPABILITY_NOT_SATISFIED("capability_not_satisfied")
    }

    companion object {
        /**
         * Classifies a raw failure reason string into a [CrossDeviceSetupError].
         *
         * Called by [RuntimeController.handleFailure] after every registration attempt; the
         * [isGatewayConfigured] flag is derived from [com.ufo.galaxy.data.AppSettings] by
         * the controller before calling this method.
         *
         * Classification rules (applied in priority order):
         *  1. If [isGatewayConfigured] is `false` → [Category.CONFIGURATION].
         *  2. If [reason] contains capability-related keywords (permission, accessibility,
         *     overlay, model) → [Category.CAPABILITY_NOT_SATISFIED].
         *  3. Otherwise → [Category.NETWORK] (connection failure / timeout).
         *
         * @param reason              Human-readable failure reason from [RuntimeController].
         * @param isGatewayConfigured Whether the gateway URL/host has been configured with
         *                            a real (non-placeholder) value.
         * @return A fully populated [CrossDeviceSetupError] ready for emission on
         *         [RuntimeController.setupError].
         */
        fun classify(reason: String, isGatewayConfigured: Boolean): CrossDeviceSetupError {
            val category = when {
                !isGatewayConfigured -> Category.CONFIGURATION
                reason.contains("capability", ignoreCase = true) ||
                    reason.contains("permission", ignoreCase = true) ||
                    reason.contains("accessibility", ignoreCase = true) ||
                    reason.contains("overlay", ignoreCase = true) ||
                    reason.contains("model", ignoreCase = true) -> Category.CAPABILITY_NOT_SATISFIED
                else -> Category.NETWORK
            }
            return CrossDeviceSetupError(
                category = category,
                reason = reason,
                canRetry = category != Category.CONFIGURATION
            )
        }
    }
}
