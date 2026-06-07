package com.ufo.galaxy.service

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Boot-completed receiver.
 *
 * On [Intent.ACTION_BOOT_COMPLETED] (and the HTC/Qualcomm quick-boot equivalent)
 * starts the two canonical background services so the device is ready to receive
 * tasks without requiring the user to open the app:
 *  1. [GalaxyConnectionService] — restores the cross-device WS connection if
 *     [com.ufo.galaxy.data.AppSettings.crossDeviceEnabled] is `true`.
 *  2. [EnhancedFloatingService] — the sole canonical floating-island surface.
 *
 * The legacy [FloatingWindowService] is **not** started here; all floating-UI
 * boot paths must use [EnhancedFloatingService].
 *
 * B2-FIX: Services are started with a staggered delay to avoid ANR on Android 12+.
 * C4-FIX: Added isServiceRunning check to avoid duplicate starts.
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
        const val ENTRYPOINT_ROLE = "sub_entry"
        // B2-FIX: Delay between starting the two foreground services (ms) to avoid ANR
        private const val SECOND_SERVICE_DELAY_MS = 3_000L
    }
    
    override fun onReceive(context: Context, intent: Intent?) {
        // C4-FIX: Guard against null intent and unexpected actions
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Log.w(TAG, "Unexpected intent action: ${intent?.action}")
            return
        }
        
        Log.i(TAG, "设备启动完成，启动 Galaxy 服务")
        
        // B2-FIX: Start GalaxyConnectionService first (primary service)
        if (!isServiceRunning(context, GalaxyConnectionService::class.java)) {
            val serviceIntent = GalaxyConnectionService.createMainEntryIntent(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "GalaxyConnectionService started from boot")
        } else {
            Log.d(TAG, "GalaxyConnectionService already running — skipping duplicate start (C4-FIX)")
        }

        // B2-FIX: Delay EnhancedFloatingService start to avoid Android 12+ ANR
        // when two foreground services are started simultaneously from BOOT_COMPLETED.
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isServiceRunning(context, EnhancedFloatingService::class.java)) {
                val floatingIntent = Intent(context, EnhancedFloatingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(floatingIntent)
                } else {
                    context.startService(floatingIntent)
                }
                Log.i(TAG, "EnhancedFloatingService started from boot (delayed ${SECOND_SERVICE_DELAY_MS}ms)")
            } else {
                Log.d(TAG, "EnhancedFloatingService already running — skipping duplicate start (C4-FIX)")
            }
        }, SECOND_SERVICE_DELAY_MS)
    }
    
    /**
     * C4-FIX: Check if a service is already running to prevent duplicate starts.
     */
    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }
}

/**
 * Hardware media-button receiver stub.
 *
 * Declared in AndroidManifest.xml to receive [Intent.ACTION_MEDIA_BUTTON] broadcasts.
 * This receiver is a **non-executing stub**: it logs the event but takes no action.
 * It is **not** an active entry surface for the canonical runtime pipeline.
 *
 * Any future hardware wake-up feature must be wired through the canonical input
 * path: [com.ufo.galaxy.speech.NaturalLanguageInputManager] →
 * [com.ufo.galaxy.input.InputRouter] → [com.ufo.galaxy.network.GalaxyWebSocketClient]
 * / [com.ufo.galaxy.loop.LoopController].
 */
class HardwareKeyReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "HardwareKeyReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MEDIA_BUTTON) {
            // Non-executing stub: log only. Hardware wake-up is not yet implemented.
            Log.d(TAG, "收到媒体按键事件")
        }
    }
}
