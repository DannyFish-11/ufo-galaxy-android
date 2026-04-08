package com.ufo.galaxy.runtime

/**
 * PR-27: Typed error for cross-device enablement failures.
 *
 * Replaces the plain-string registration failure reason with a structured error that
 * lets surface layers ([com.ufo.galaxy.ui.MainActivity] dialog,
 * [com.ufo.galaxy.service.EnhancedFloatingService] floating dialog) offer appropriate
 * recovery actions — rather than always showing the same generic "Retry" button
 * regardless of why enablement failed.
 *
 * Three categories are distinguished:
 *  - [Category.CONFIGURATION] — the Gateway address is missing or obviously wrong;
 *    recovery is to open the network settings screen and configure the host.
 *  - [Category.NETWORK] — the device could not reach the Gateway (connection refused,
 *    WebSocket error, or connection timeout); recovery is to retry (and optionally view
 *    diagnostics).
 *  - [Category.CAPABILITY] — a required device capability is not satisfied (Accessibility
 *    service not enabled, overlay permission not granted, etc.); recovery is to navigate
 *    to the relevant system settings.
 *
 * Consumers should **never** inspect the raw [message] string to branch on error type.
 * Always use the [category] field for conditional recovery logic.
 */
sealed class CrossDeviceEnablementError {

    /** Machine-readable category for surface layers to gate recovery actions. */
    abstract val category: Category

    /** Human-readable failure description for display in a dialog or notification. */
    abstract val message: String

    /**
     * The Gateway host is blank or carries a placeholder URL; the user must configure
     * it in the network settings screen before cross-device mode can be enabled.
     *
     * Recovery action: open network settings screen.
     */
    data class ConfigurationError(override val message: String) : CrossDeviceEnablementError() {
        override val category: Category get() = Category.CONFIGURATION
    }

    /**
     * The device attempted to connect to the Gateway but failed (TCP error, WebSocket
     * protocol error, or the connection timed out). The Gateway may be offline,
     * unreachable, or configured with the wrong host/port/TLS settings.
     *
     * Recovery actions: retry registration; optionally view diagnostics/settings.
     */
    data class NetworkError(override val message: String) : CrossDeviceEnablementError() {
        override val category: Category get() = Category.NETWORK
    }

    /**
     * A required device capability — such as the Accessibility service or the
     * SYSTEM_ALERT_WINDOW overlay permission — is not satisfied. The user must visit
     * the relevant system settings to grant the required permission before enabling
     * cross-device mode.
     *
     * Recovery action: open system permission settings (Accessibility or Overlay).
     */
    data class CapabilityError(override val message: String) : CrossDeviceEnablementError() {
        override val category: Category get() = Category.CAPABILITY
    }

    /**
     * Machine-readable category enum used by surface layers to determine which
     * recovery buttons to show without inspecting raw error message strings.
     */
    enum class Category {
        /** Gateway address is missing or obviously misconfigured. */
        CONFIGURATION,
        /** Connection to the Gateway failed (network unreachable, timeout, WS error). */
        NETWORK,
        /** A required device capability is not satisfied (permissions / services). */
        CAPABILITY
    }
}
