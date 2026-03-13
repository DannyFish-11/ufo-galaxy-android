package com.ufo.galaxy.client

import android.os.Build
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SystemControlHelper].
 *
 * These tests run on the JVM (no Android device required).  Android stub
 * libraries return default values (`null` for objects, `0` for primitives,
 * `false` for booleans) and all Android API calls inside [SystemControlHelper]
 * are wrapped in `try-catch` blocks, so the tests validate the *JSON contract*
 * – i.e. the required fields and status values of every returned [JSONObject].
 *
 * WiFi on API 29+
 * ───────────────
 * In production, [SystemControlHelper.toggleWifi] opens the system WiFi panel
 * on API ≥ 29 and returns `{ status:"pending_user_action", manual_required:true }`.
 * The reflection-based test below verifies this without requiring a real device.
 *
 * Bluetooth without adapter
 * ─────────────────────────
 * [BluetoothManager] is `null` in the JVM test env, so `toggleBluetooth`
 * returns `{ status:"error", message:"Bluetooth is not available…" }`.
 */
class SystemControlHelperTest {

    // Instantiate with a minimal Context stub (all services return null).
    private val context = MinimalContext()
    private val helper = SystemControlHelper(context)

    // ──────────────────────────────────────────────────────────────
    // toggleWifi – legacy path (SDK_INT == 0 in JVM tests)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `toggleWifi always returns a status field`() {
        val result = helper.toggleWifi(true)
        assertNotNull("Response must contain 'status'", result.opt("status"))
    }

    @Test
    fun `toggleWifi legacy path returns error when WifiManager unavailable`() {
        // Build.VERSION.SDK_INT == 0 < 29 → legacy path
        // WifiManager is null → NPE caught → error JSON
        val result = helper.toggleWifi(true)
        val status = result.optString("status")
        assertTrue(
            "Expected 'error' status when WifiManager is unavailable, got '$status'",
            status == "error"
        )
    }

    // ──────────────────────────────────────────────────────────────
    // toggleWifi – API 29+ path (verified via reflection)
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `toggleWifi API 29+ path returns manual_required true`() {
        val savedSdk = Build.VERSION.SDK_INT
        try {
            setSdkInt(29)
            val result = helper.toggleWifi(true)
            assertTrue("manual_required field must be present", result.has("manual_required"))
            assertTrue("manual_required must be true on API 29+", result.optBoolean("manual_required"))
        } finally {
            setSdkInt(savedSdk)
        }
    }

    @Test
    fun `toggleWifi API 29+ status is pending_user_action or error`() {
        val savedSdk = Build.VERSION.SDK_INT
        try {
            setSdkInt(29)
            val result = helper.toggleWifi(false)
            val status = result.optString("status")
            assertTrue(
                "Status must be 'pending_user_action' or 'error', got '$status'",
                status == "pending_user_action" || status == "error"
            )
        } finally {
            setSdkInt(savedSdk)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // toggleBluetooth
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `toggleBluetooth returns status and message when adapter unavailable`() {
        val result = helper.toggleBluetooth(true)
        assertNotNull("Response must contain 'status'", result.opt("status"))
        assertNotNull("Response must contain 'message'", result.opt("message"))
        assertEquals("error", result.optString("status"))
    }

    @Test
    fun `toggleBluetooth enable and disable both return structured JSON`() {
        val enable = helper.toggleBluetooth(true)
        val disable = helper.toggleBluetooth(false)

        assertNotNull(enable.opt("status"))
        assertNotNull(disable.opt("status"))
        assertNotNull(enable.opt("message"))
        assertNotNull(disable.opt("message"))
    }

    @Test
    fun `toggleBluetooth API 31+ returns permission_required field when permission denied`() {
        val savedSdk = Build.VERSION.SDK_INT
        try {
            setSdkInt(31)
            // MinimalContext.checkSelfPermission returns PERMISSION_DENIED (-1)
            val result = helper.toggleBluetooth(true)
            assertEquals("error", result.optString("status"))
            assertTrue(
                "permission_required field must be present on API 31+",
                result.has("permission_required")
            )
            assertEquals(
                android.Manifest.permission.BLUETOOTH_CONNECT,
                result.optString("permission_required")
            )
        } finally {
            setSdkInt(savedSdk)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // setVolume
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `setVolume returns false when AudioManager unavailable`() {
        // AudioManager is null in JVM tests; try-catch returns false
        assertFalse(helper.setVolume(50))
    }

    // ──────────────────────────────────────────────────────────────
    // setBrightness
    // ──────────────────────────────────────────────────────────────

    @Test
    fun `setBrightness returns false without WRITE_SETTINGS permission`() {
        // Settings.System.canWrite returns false (default) in JVM tests
        assertFalse(helper.setBrightness(80))
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    /** Mutate [Build.VERSION.SDK_INT] via reflection (best-effort). */
    private fun setSdkInt(value: Int) {
        try {
            val field = Build.VERSION::class.java.getDeclaredField("SDK_INT")
            field.isAccessible = true
            field.set(null, value)
        } catch (_: Exception) {
            // Some JVMs prevent mutating final fields – silently skip
        }
    }

    /**
     * Minimal Context stub: all system services return `null`, all permissions
     * are denied.  Only the methods actually invoked by [SystemControlHelper]
     * are overridden; the rest rely on the `returnDefaultValues = true` setting
     * in `testOptions` to avoid throwing on Android stubs.
     */
    @Suppress("DEPRECATION")
    private class MinimalContext : android.content.Context() {
        override fun getApplicationContext(): android.content.Context = this
        override fun getSystemService(name: String): Any? = null
        override fun getContentResolver(): android.content.ContentResolver? = null
        override fun getPackageManager(): android.content.pm.PackageManager? = null
        override fun checkSelfPermission(permission: String): Int = -1 // PERMISSION_DENIED
        override fun startActivity(intent: android.content.Intent) { /* no-op */ }
        override fun startActivity(intent: android.content.Intent, options: android.os.Bundle?) { /* no-op */ }
        // All remaining abstract methods fall through to Android stub defaults.
        override fun getAssets(): android.content.res.AssetManager = throw UnsupportedOperationException()
        override fun getResources(): android.content.res.Resources = throw UnsupportedOperationException()
        override fun getMainLooper(): android.os.Looper = throw UnsupportedOperationException()
        override fun getTheme(): android.content.res.Resources.Theme = throw UnsupportedOperationException()
        override fun getPackageName(): String = "com.ufo.galaxy.test"
        override fun getClassLoader(): ClassLoader = javaClass.classLoader
        override fun getApplicationInfo(): android.content.pm.ApplicationInfo = throw UnsupportedOperationException()
        override fun getFilesDir(): java.io.File = throw UnsupportedOperationException()
        override fun getCacheDir(): java.io.File = throw UnsupportedOperationException()
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences = throw UnsupportedOperationException()
        override fun openFileOutput(name: String, mode: Int): java.io.FileOutputStream = throw UnsupportedOperationException()
        override fun openFileInput(name: String): java.io.FileInputStream = throw UnsupportedOperationException()
        override fun fileList(): Array<String> = emptyArray()
        override fun deleteFile(name: String): Boolean = false
        override fun deleteSharedPreferences(name: String): Boolean = false
        override fun moveSharedPreferencesFrom(sourceContext: android.content.Context, name: String): Boolean = false
        override fun getDir(name: String, mode: Int): java.io.File = throw UnsupportedOperationException()
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?): android.database.sqlite.SQLiteDatabase = throw UnsupportedOperationException()
        override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory?, errorHandler: android.database.DatabaseErrorHandler?): android.database.sqlite.SQLiteDatabase = throw UnsupportedOperationException()
        override fun deleteDatabase(name: String): Boolean = false
        override fun getDatabasePath(name: String): java.io.File = throw UnsupportedOperationException()
        override fun databaseList(): Array<String> = emptyArray()
        override fun getWallpaper(): android.graphics.drawable.Drawable = throw UnsupportedOperationException()
        override fun peekWallpaper(): android.graphics.drawable.Drawable = throw UnsupportedOperationException()
        override fun getWallpaperDesiredMinimumWidth(): Int = 0
        override fun getWallpaperDesiredMinimumHeight(): Int = 0
        override fun setWallpaper(bitmap: android.graphics.Bitmap) {}
        override fun setWallpaper(data: java.io.InputStream) {}
        override fun clearWallpaper() {}
        override fun bindService(intent: android.content.Intent, conn: android.content.ServiceConnection, flags: Int): Boolean = false
        override fun unbindService(conn: android.content.ServiceConnection) {}
        override fun startService(service: android.content.Intent): android.content.ComponentName? = null
        override fun stopService(service: android.content.Intent): Boolean = false
        override fun startForegroundService(service: android.content.Intent): android.content.ComponentName? = null
        override fun sendBroadcast(intent: android.content.Intent) {}
        override fun sendBroadcast(intent: android.content.Intent, receiverPermission: String?) {}
        override fun sendOrderedBroadcast(intent: android.content.Intent, receiverPermission: String?) {}
        override fun sendOrderedBroadcast(intent: android.content.Intent, receiverPermission: String?, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {}
        override fun sendBroadcastAsUser(intent: android.content.Intent, user: android.os.UserHandle) {}
        override fun sendOrderedBroadcastAsUser(intent: android.content.Intent, user: android.os.UserHandle, receiverPermission: String?, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {}
        override fun sendStickyBroadcast(intent: android.content.Intent) {}
        override fun sendStickyOrderedBroadcast(intent: android.content.Intent, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {}
        override fun removeStickyBroadcast(intent: android.content.Intent) {}
        override fun sendStickyBroadcastAsUser(intent: android.content.Intent, user: android.os.UserHandle) {}
        override fun sendStickyOrderedBroadcastAsUser(intent: android.content.Intent, user: android.os.UserHandle, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {}
        override fun removeStickyBroadcastAsUser(intent: android.content.Intent, user: android.os.UserHandle) {}
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?): android.content.Intent? = null
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, flags: Int): android.content.Intent? = null
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, broadcastPermission: String?, scheduler: android.os.Handler?): android.content.Intent? = null
        override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter?, broadcastPermission: String?, scheduler: android.os.Handler?, flags: Int): android.content.Intent? = null
        override fun unregisterReceiver(receiver: android.content.BroadcastReceiver) {}
        override fun startActivities(intents: Array<out android.content.Intent>?) {}
        override fun startActivities(intents: Array<out android.content.Intent>?, options: android.os.Bundle?) {}
        override fun startIntentSender(intent: android.content.IntentSender, fillInIntent: android.content.Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int) {}
        override fun startIntentSender(intent: android.content.IntentSender, fillInIntent: android.content.Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int, options: android.os.Bundle?) {}
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int = -1
        override fun checkCallingPermission(permission: String): Int = -1
        override fun checkCallingOrSelfPermission(permission: String): Int = -1
        override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {}
        override fun enforceCallingPermission(permission: String, message: String?) {}
        override fun enforceCallingOrSelfPermission(permission: String, message: String?) {}
        override fun grantUriPermission(toPackage: String, uri: android.net.Uri, modeFlags: Int) {}
        override fun revokeUriPermission(uri: android.net.Uri, modeFlags: Int) {}
        override fun revokeUriPermission(toPackage: String, uri: android.net.Uri, modeFlags: Int) {}
        override fun checkUriPermission(uri: android.net.Uri?, pid: Int, uid: Int, modeFlags: Int): Int = -1
        override fun checkCallingUriPermission(uri: android.net.Uri?, modeFlags: Int): Int = -1
        override fun checkCallingOrSelfUriPermission(uri: android.net.Uri?, modeFlags: Int): Int = -1
        override fun checkUriPermission(uri: android.net.Uri?, readPermission: String?, writePermission: String?, pid: Int, uid: Int, modeFlags: Int): Int = -1
        override fun enforceUriPermission(uri: android.net.Uri?, pid: Int, uid: Int, modeFlags: Int, message: String?) {}
        override fun enforceCallingUriPermission(uri: android.net.Uri?, modeFlags: Int, message: String?) {}
        override fun enforceCallingOrSelfUriPermission(uri: android.net.Uri?, modeFlags: Int, message: String?) {}
        override fun createPackageContext(packageName: String, flags: Int): android.content.Context = throw UnsupportedOperationException()
        override fun createConfigurationContext(overrideConfiguration: android.content.res.Configuration): android.content.Context = throw UnsupportedOperationException()
        override fun createDisplayContext(display: android.view.Display): android.content.Context = throw UnsupportedOperationException()
        override fun isRestricted(): Boolean = false
        override fun getSystemServiceName(serviceClass: Class<*>): String? = null
        override fun getNoBackupFilesDir(): java.io.File = throw UnsupportedOperationException()
        override fun getCodeCacheDir(): java.io.File = throw UnsupportedOperationException()
        override fun getExternalCacheDir(): java.io.File? = null
        override fun getExternalCacheDirs(): Array<java.io.File> = emptyArray()
        override fun getExternalFilesDirs(type: String?): Array<java.io.File> = emptyArray()
        override fun getObbDirs(): Array<java.io.File> = emptyArray()
        override fun getExternalFilesDir(type: String?): java.io.File? = null
        override fun getObbDir(): java.io.File? = null
        override fun getExternalMediaDirs(): Array<java.io.File> = emptyArray()
        override fun getOpPackageName(): String = "com.ufo.galaxy.test"
        override fun createContextForSplit(splitName: String): android.content.Context = throw UnsupportedOperationException()
        override fun getAttributionTag(): String? = null
    }
}
