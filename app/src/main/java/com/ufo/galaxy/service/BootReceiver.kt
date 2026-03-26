package com.ufo.galaxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
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
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            Log.i(TAG, "设备启动完成，启动 Galaxy 服务")
            
            // 启动连接服务（负责恢复 crossDeviceEnabled 与 WS 连接）
            val serviceIntent = Intent(context, GalaxyConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            // 同步恢复增强悬浮窗服务，使后台功能持续可用
            val floatingIntent = Intent(context, EnhancedFloatingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(floatingIntent)
            } else {
                context.startService(floatingIntent)
            }
        }
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
