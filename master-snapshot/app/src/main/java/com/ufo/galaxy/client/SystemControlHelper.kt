package com.ufo.galaxy.client

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import org.json.JSONObject

/**
 * Centralised system-control helper shared by [AndroidCommandExecutor] and
 * [com.ufo.galaxy.autonomy.AutonomyManager].
 *
 * WiFi toggling (Android 10 / API 29+)
 * ────────────────────────────────────
 * [WifiManager.setWifiEnabled] is a no-op for non-system apps on API 29+.
 * The safe, documented fallback is to open the system Settings Panel for WiFi
 * ([Settings.Panel.ACTION_WIFI]) so the user can confirm the toggle manually.
 * [toggleWifi] always opens the panel on API 29+, and returns a structured
 * response with `manual_required = true` so callers can display a suitable
 * message to the user.
 *
 * Bluetooth toggling (Android 12 / API 31+)
 * ─────────────────────────────────────────
 * [BluetoothAdapter.enable]/[BluetoothAdapter.disable] are deprecated and
 * silently fail on API 33+ without the BLUETOOTH_CONNECT runtime permission.
 * [toggleBluetooth] checks for the permission and falls back gracefully with
 * a clear error message when it is missing.
 */
class SystemControlHelper(private val context: Context) {

    private val TAG = "SystemControlHelper"

    // ──────────────────────────────────────────────────────────────
    // WiFi
    // ──────────────────────────────────────────────────────────────

    /**
     * Toggle WiFi on or off.
     *
     * On API < 29: calls [WifiManager.setWifiEnabled] directly.
     * On API ≥ 29: [WifiManager.setWifiEnabled] is restricted for non-system
     * apps. Instead we open [Settings.Panel.ACTION_WIFI] so the user can
     * confirm the change in the system UI. The returned JSON contains
     * `manual_required = true` to inform the caller.
     *
     * @param enable `true` to enable WiFi, `false` to disable.
     * @return A [JSONObject] with at minimum `status` and `message` fields.
     */
    fun toggleWifi(enable: Boolean): JSONObject {
        Log.i(TAG, "[WIFI] toggleWifi(enable=$enable) sdk=${Build.VERSION.SDK_INT}")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openWifiSettingsPanel()
        } else {
            @Suppress("DEPRECATION")
            toggleWifiLegacy(enable)
        }
    }

    /** Opens the system WiFi Settings Panel (API 29+). */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun openWifiSettingsPanel(): JSONObject {
        return try {
            val intent = Intent(Settings.Panel.ACTION_WIFI).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "[WIFI] Opened Settings Panel (API 29+ restriction)")
            JSONObject().apply {
                put("status", "pending_user_action")
                put("message",
                    "Android 10+ restricts programmatic WiFi toggling. " +
                    "The system WiFi panel has been opened for manual confirmation.")
                put("manual_required", true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[WIFI] Failed to open WiFi settings panel", e)
            JSONObject().apply {
                put("status", "error")
                put("message", "Failed to open WiFi settings: ${e.message}")
                put("manual_required", true)
            }
        }
    }

    /** Directly sets WiFi enabled state on API < 29. */
    @Suppress("DEPRECATION")
    private fun toggleWifiLegacy(enable: Boolean): JSONObject {
        return try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val success = wifiManager.setWifiEnabled(enable)
            Log.i(TAG, "[WIFI] setWifiEnabled($enable) -> $success")
            if (success) {
                JSONObject().apply {
                    put("status", "success")
                    put("message", "WiFi ${if (enable) "enabled" else "disabled"}")
                    put("manual_required", false)
                }
            } else {
                JSONObject().apply {
                    put("status", "error")
                    put("message", "setWifiEnabled($enable) returned false")
                    put("manual_required", false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[WIFI] toggleWifiLegacy failed", e)
            JSONObject().apply {
                put("status", "error")
                put("message", "WiFi toggle failed: ${e.message}")
                put("manual_required", false)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Bluetooth
    // ──────────────────────────────────────────────────────────────

    /**
     * Toggle Bluetooth on or off.
     *
     * On API ≥ 31 (Android 12): requires BLUETOOTH_CONNECT runtime permission.
     *   • If the permission is granted, uses [BluetoothAdapter.enable]/[disable].
     *   • If the permission is missing, returns a structured error so the UI layer
     *     can request the permission and retry.
     * On API < 31: uses the legacy [BluetoothAdapter.enable]/[disable] directly.
     *
     * Note: on API ≥ 33 (Android 13) [BluetoothAdapter.enable]/[disable] are
     * fully deprecated and may be removed in a future release.  At that point
     * the implementation should switch to the system-settings panel approach
     * (similar to WiFi) or a companion-app flow.
     *
     * @param enable `true` to enable Bluetooth, `false` to disable.
     * @return A [JSONObject] with `status` and `message` fields.
     */
    fun toggleBluetooth(enable: Boolean): JSONObject {
        Log.i(TAG, "[BT] toggleBluetooth(enable=$enable) sdk=${Build.VERSION.SDK_INT}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // API 31+ requires BLUETOOTH_CONNECT runtime permission
            if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "[BT] BLUETOOTH_CONNECT permission not granted")
                return JSONObject().apply {
                    put("status", "error")
                    put("message",
                        "BLUETOOTH_CONNECT permission is required on Android 12+. " +
                        "Please grant the permission and retry.")
                    put("permission_required", android.Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
        }

        return try {
            val bluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter
                ?: return JSONObject().apply {
                    put("status", "error")
                    put("message", "Bluetooth is not available on this device")
                }

            @Suppress("DEPRECATION")
            val success = if (enable) adapter.enable() else adapter.disable()
            Log.i(TAG, "[BT] ${if (enable) "enable" else "disable"}() -> $success")
            if (success) {
                JSONObject().apply {
                    put("status", "success")
                    put("message", "Bluetooth ${if (enable) "enabled" else "disabled"}")
                }
            } else {
                JSONObject().apply {
                    put("status", "error")
                    put("message",
                        "Bluetooth ${if (enable) "enable" else "disable"} call returned false. " +
                        "The adapter may already be in the requested state.")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "[BT] SecurityException – missing permission", e)
            JSONObject().apply {
                put("status", "error")
                put("message",
                    "Bluetooth toggle failed due to missing permission: ${e.message}")
                put("permission_required", android.Manifest.permission.BLUETOOTH_CONNECT)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[BT] toggleBluetooth failed", e)
            JSONObject().apply {
                put("status", "error")
                put("message", "Bluetooth toggle failed: ${e.message}")
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Volume
    // ──────────────────────────────────────────────────────────────

    /**
     * Set media volume to [level] (0–100).
     * @return `true` on success.
     */
    fun setVolume(level: Int): Boolean {
        return try {
            val audioManager =
                context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (level * maxVol / 100).coerceIn(0, maxVol)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            Log.i(TAG, "[VOL] setVolume($level) -> stream target=$target/$maxVol")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[VOL] setVolume failed", e)
            false
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Brightness
    // ──────────────────────────────────────────────────────────────

    /**
     * Set screen brightness to [level] (0–100).
     * Requires WRITE_SETTINGS permission (declared in manifest; user must grant
     * it via Settings → Apps → Special app access → Modify system settings).
     * @return `true` on success, `false` if permission is missing or the call fails.
     */
    fun setBrightness(level: Int): Boolean {
        return try {
            if (!Settings.System.canWrite(context)) {
                Log.w(TAG, "[BRIGHT] WRITE_SETTINGS not granted")
                return false
            }
            val brightnessValue = (level * 255 / 100).coerceIn(0, 255)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessValue
            )
            Log.i(TAG, "[BRIGHT] setBrightness($level) -> $brightnessValue/255")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[BRIGHT] setBrightness failed", e)
            false
        }
    }
}
