package com.ufo.galaxy.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机启动接收器
 * 在设备启动时自动启动 Galaxy 服务与增强悬浮窗服务
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