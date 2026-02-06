package com.ufo.galaxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ufo.galaxy.R
import com.ufo.galaxy.UFOGalaxyApplication
import kotlinx.coroutines.*

/**
 * Agent 核心服务
 * 负责保持 Agent 在后台运行
 */
class AgentService : Service() {

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "agent_service_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AgentService created")
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        // 启动 Agent 核心
        scope.launch {
            try {
                val agentCore = UFOGalaxyApplication.instance.agentCore
                Log.d(TAG, "Agent core initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize agent core", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "AgentService started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AgentService destroyed")
        scope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "UFO Galaxy Agent Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "UFO Galaxy Agent 后台服务"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UFO Galaxy Agent")
            .setContentText("Agent 正在运行")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
