package com.ufo.galaxy.shared.protocol

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Unified device-ID provider shared across Android and Wear OS.
 *
 * Previously the two projects used different identification strategies:
 * - Android: `MANUFACTURER + "_" + MODEL` (unstable across resets / ROM changes)
 * - Wear OS: `Settings.Secure.ANDROID_ID` (deprecated on Android O+, may be reset)
 *
 * This object replaces both with a **self-generated UUID v4 + secure persistent storage**
 * strategy.  The UUID is created once on first access and then stored in
 * [EncryptedSharedPreferences] (or plain SharedPreferences as fallback on devices
 * that lack encrypted-prefs support).
 *
 * ## Usage
 * ```kotlin
 * val deviceId = DeviceIdProvider.getOrCreateDeviceId(context)
 * ```
 *
 * ## Security
 * - The stored UUID is encrypted at rest via AES-256 GCM when EncryptedSharedPreferences
 *   is available.
 * - The UUID itself contains no device-identifiable information (pure random v4).
 * - No ANDROID_ID, hardware serial, or MAC address is ever read.
 */
object DeviceIdProvider {

    private const val PREFS_FILE = "galaxy_device_config"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_DEVICE_TYPE = "device_type"

    /** Device type value for Android phones / tablets. */
    const val DEVICE_TYPE_ANDROID = "android"

    /** Device type value for Wear OS watches. */
    const val DEVICE_TYPE_WEAROS = "wearos"

    @Volatile
    private var cachedDeviceId: String? = null

    @Volatile
    private var cachedDeviceType: String? = null

    /**
     * Returns the stable device identifier for this device.
     *
     * On first call, a new UUID v4 is generated and persisted securely.
     * Subsequent calls return the cached / persisted value.
     *
     * Thread-safe; may be called from any coroutine context.
     *
     * @param context Android [Context] for accessing SharedPreferences.
     * @return Stable UUID string, e.g. `"550e8400-e29b-41d4-a716-446655440000"`.
     */
    @JvmStatic
    fun getOrCreateDeviceId(context: Context): String {
        cachedDeviceId?.let { return it }

        val prefs = getPreferences(context)
        synchronized(this) {
            // Double-checked locking
            cachedDeviceId?.let { return it }

            var deviceId = prefs.getString(KEY_DEVICE_ID, null)
            if (deviceId.isNullOrBlank()) {
                deviceId = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
            }
            cachedDeviceId = deviceId
            return deviceId
        }
    }

    /**
     * Returns the device type for this device ("android" or "wearos").
     *
     * The value is auto-detected on first access using [Context.getPackageManager]
     * system-feature detection.  It is cached in memory and persisted so that it
     * survives app restarts.
     *
     * @param context Android [Context].
     * @return [DEVICE_TYPE_ANDROID] or [DEVICE_TYPE_WEAROS].
     */
    @JvmStatic
    fun getDeviceType(context: Context): String {
        cachedDeviceType?.let { return it }

        val prefs = getPreferences(context)
        synchronized(this) {
            cachedDeviceType?.let { return it }

            var deviceType = prefs.getString(KEY_DEVICE_TYPE, null)
            if (deviceType.isNullOrBlank()) {
                deviceType = detectDeviceType(context)
                prefs.edit().putString(KEY_DEVICE_TYPE, deviceType).apply()
            }
            cachedDeviceType = deviceType
            return deviceType
        }
    }

    /**
     * Clears the cached device ID and type.  Mainly useful in tests or when the
     * user explicitly requests a device identity reset.
     */
    @JvmStatic
    fun clearCache(context: Context) {
        synchronized(this) {
            cachedDeviceId = null
            cachedDeviceType = null
            getPreferences(context).edit()
                .remove(KEY_DEVICE_ID)
                .remove(KEY_DEVICE_TYPE)
                .apply()
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────────────────────

    private fun getPreferences(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Exception) {
            // Fallback: plain SharedPreferences on devices that lack encrypted-prefs support.
            // SECURITY: This is a last-resort fallback; the UUID itself contains no secrets.
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    private fun detectDeviceType(context: Context): String {
        val pm = context.packageManager
        return when {
            pm.hasSystemFeature("android.hardware.type.watch") -> DEVICE_TYPE_WEAROS
            else -> DEVICE_TYPE_ANDROID
        }
    }
}
