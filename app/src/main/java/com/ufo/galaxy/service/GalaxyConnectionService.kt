package com.ufo.galaxy.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.ufo.galaxy.R
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.TaskAssignPayload
import com.ufo.galaxy.protocol.TaskResultPayload
import com.ufo.galaxy.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Galaxy 连接服务
 * 后台维护与 Galaxy 服务器的 WebSocket 连接
 *
 * On start: loads MobileVLM and SeeClick models; updates [GalaxyWebSocketClient.setModelCapabilities].
 * On destroy: unloads models and removes the WS listener.
 */
class GalaxyConnectionService : Service() {
    
    companion object {
        private const val TAG = "GalaxyConnectionService"
        private const val NOTIFICATION_ID = 1001
    }
    
    private val binder = LocalBinder()
    private lateinit var webSocketClient: GalaxyWebSocketClient
    private val gson = Gson()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var wsListener: GalaxyWebSocketClient.Listener
    
    inner class LocalBinder : Binder() {
        fun getService(): GalaxyConnectionService = this@GalaxyConnectionService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "服务创建")
        
        webSocketClient = UFOGalaxyApplication.webSocketClient
        
        // 设置监听器：处理连接状态和 task_assign 路由
        wsListener = object : GalaxyWebSocketClient.Listener {
            override fun onConnected() {
                Log.d(TAG, "已连接到 Galaxy")
                updateNotification("已连接")
            }
            
            override fun onDisconnected() {
                Log.d(TAG, "与 Galaxy 断开连接")
                updateNotification("已断开")
            }
            
            override fun onMessage(message: String) {
                Log.d(TAG, "收到消息: ${message.take(50)}...")
            }
            
            override fun onError(error: String) {
                Log.e(TAG, "连接错误: $error")
                updateNotification("连接错误")
            }

            override fun onTaskAssign(taskId: String, taskAssignPayloadJson: String) {
                Log.i(TAG, "收到 task_assign: task_id=$taskId")
                serviceScope.launch {
                    handleTaskAssign(taskId, taskAssignPayloadJson)
                }
            }
        }
        webSocketClient.addListener(wsListener)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "服务启动")
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification("正在连接..."))
        
        // 连接到服务器（若 crossDeviceEnabled=false 则为 no-op）
        webSocketClient.connect()

        // 后台加载模型，加载完成后更新能力上报
        serviceScope.launch {
            loadModels()
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "服务销毁")
        webSocketClient.removeListener(wsListener)
        webSocketClient.disconnect()
        unloadModels()
        serviceScope.cancel()
    }

    /**
     * 处理 task_assign：反序列化 payload、执行本地 EdgeExecutor、回传 task_result。
     * 在 IO 线程中执行；EdgeExecutor 内部所有异常均已捕获并映射为 ERROR 结果。
     */
    private fun handleTaskAssign(taskId: String, payloadJson: String) {
        val payload = try {
            gson.fromJson(payloadJson, TaskAssignPayload::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "task_assign payload 解析失败: ${e.message}", e)
            sendTaskError(taskId, "bad_payload: ${e.message}")
            return
        }

        val result = UFOGalaxyApplication.edgeExecutor.handleTaskAssign(payload)

        val envelope = AipMessage(
            type = MsgType.TASK_RESULT,
            payload = result,
            correlation_id = taskId,
            device_id = "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}"
        )
        val sent = webSocketClient.sendJson(gson.toJson(envelope))
        Log.i(TAG, "task_result 已回传 task_id=$taskId status=${result.status} sent=$sent")
    }

    /**
     * 回传 task_result 错误（payload 解析失败时使用）。
     */
    private fun sendTaskError(taskId: String, errorMsg: String) {
        val errorResult = TaskResultPayload(
            task_id = taskId,
            correlation_id = taskId,
            status = com.ufo.galaxy.agent.EdgeExecutor.STATUS_ERROR,
            error = errorMsg
        )
        val envelope = AipMessage(
            type = MsgType.TASK_RESULT,
            payload = errorResult,
            correlation_id = taskId,
            device_id = "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}"
        )
        webSocketClient.sendJson(gson.toJson(envelope))
    }

    /**
     * 加载 MobileVLM 规划器和 SeeClick grounding 模型。
     * 加载结果通过 setModelCapabilities 通知 gateway。
     */
    private fun loadModels() {
        Log.i(TAG, "开始加载本地模型...")
        val plannerLoaded = UFOGalaxyApplication.plannerService.loadModel()
        val groundingLoaded = UFOGalaxyApplication.groundingService.loadModel()
        Log.i(TAG, "模型加载完成: planner=$plannerLoaded grounding=$groundingLoaded")

        val capabilities = mutableListOf<String>()
        if (plannerLoaded) capabilities.add("local_planning")
        if (groundingLoaded) capabilities.add("local_grounding")
        webSocketClient.setModelCapabilities(capabilities)
        Log.i(TAG, "已更新模型能力: $capabilities")
    }

    /**
     * 卸载本地模型以释放设备内存。
     */
    private fun unloadModels() {
        UFOGalaxyApplication.plannerService.unloadModel()
        UFOGalaxyApplication.groundingService.unloadModel()
        Log.i(TAG, "本地模型已卸载")
    }
    
    /**
     * 创建通知
     */
    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, UFOGalaxyApplication.CHANNEL_SERVICE)
            .setContentTitle("UFO Galaxy")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    /**
     * 更新通知
     */
    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * 获取连接状态
     */
    fun isConnected(): Boolean = webSocketClient.isConnected()
}
