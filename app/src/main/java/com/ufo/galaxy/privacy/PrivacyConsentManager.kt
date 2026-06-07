package com.ufo.galaxy.privacy

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Privacy consent manager — GDPR / PIPL compliant.
 *
 * Centralises user consent for data collection, analytics, device-ID tracking,
 * and screenshot processing.  Consent is versioned so that policy updates can
 * trigger a fresh consent dialog.  All choices are persisted in a private
 * SharedPreferences file and exposed as a [StateFlow] for reactive UI.
 */
class PrivacyConsentManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _consentState = MutableStateFlow(loadConsentState())
    val consentState: StateFlow<ConsentState> = _consentState

    data class ConsentState(
        val hasConsented: Boolean = false,
        val dataCollectionConsent: Boolean = false,
        val analyticsConsent: Boolean = false,
        val deviceIdConsent: Boolean = false,
        val screenshotConsent: Boolean = false,
        val consentVersion: String = "",
        val consentDate: Long = 0
    )

    companion object {
        const val CURRENT_CONSENT_VERSION = "1.0"
        private const val PREFS_NAME = "privacy_consent"
        private const val KEY_HAS_CONSENTED = "has_consented"
        private const val KEY_DATA_COLLECTION = "data_collection"
        private const val KEY_ANALYTICS = "analytics"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SCREENSHOT = "screenshot"
        private const val KEY_CONSENT_VERSION = "consent_version"
        private const val KEY_CONSENT_DATE = "consent_date"
    }

    /** Returns true iff the user has consented to the current policy version. */
    fun hasValidConsent(): Boolean {
        val state = _consentState.value
        return state.hasConsented && state.consentVersion == CURRENT_CONSENT_VERSION
    }

    /**
     * Displays a non-cancelable consent dialog.  The caller should check
     * [hasValidConsent] first and only call this when consent is missing or
     * stale.
     */
    fun showConsentDialog(context: Context, onResult: (Boolean) -> Unit) {
        AlertDialog.Builder(context)
            .setTitle("Privacy Policy & Data Consent")
            .setMessage(
                """
                We need your consent to:
                • Collect device ID for service authentication
                • Send anonymized analytics to improve the app
                • Process screenshots (with sensitive areas blurred)

                Your data is encrypted and you can withdraw consent at any time in Settings.
                """.trimIndent()
            )
            .setPositiveButton("I Agree") { _, _ ->
                grantConsent()
                onResult(true)
            }
            .setNegativeButton("Decline") { _, _ ->
                onResult(false)
            }
            .setCancelable(false)
            .show()
    }

    private fun grantConsent() {
        val state = ConsentState(
            hasConsented = true,
            dataCollectionConsent = true,
            analyticsConsent = true,
            deviceIdConsent = true,
            screenshotConsent = true,
            consentVersion = CURRENT_CONSENT_VERSION,
            consentDate = System.currentTimeMillis()
        )
        saveConsentState(state)
        _consentState.value = state
    }

    /** Revokes all consent and clears persisted state. */
    fun revokeConsent() {
        prefs.edit().clear().apply()
        _consentState.value = ConsentState()
    }

    private fun loadConsentState(): ConsentState {
        return ConsentState(
            hasConsented = prefs.getBoolean(KEY_HAS_CONSENTED, false),
            dataCollectionConsent = prefs.getBoolean(KEY_DATA_COLLECTION, false),
            analyticsConsent = prefs.getBoolean(KEY_ANALYTICS, false),
            deviceIdConsent = prefs.getBoolean(KEY_DEVICE_ID, false),
            screenshotConsent = prefs.getBoolean(KEY_SCREENSHOT, false),
            consentVersion = prefs.getString(KEY_CONSENT_VERSION, "") ?: "",
            consentDate = prefs.getLong(KEY_CONSENT_DATE, 0)
        )
    }

    private fun saveConsentState(state: ConsentState) {
        prefs.edit()
            .putBoolean(KEY_HAS_CONSENTED, state.hasConsented)
            .putBoolean(KEY_DATA_COLLECTION, state.dataCollectionConsent)
            .putBoolean(KEY_ANALYTICS, state.analyticsConsent)
            .putBoolean(KEY_DEVICE_ID, state.deviceIdConsent)
            .putBoolean(KEY_SCREENSHOT, state.screenshotConsent)
            .putString(KEY_CONSENT_VERSION, state.consentVersion)
            .putLong(KEY_CONSENT_DATE, state.consentDate)
            .apply()
    }
}
