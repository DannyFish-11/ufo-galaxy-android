package com.ufo.galaxy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.ufo.galaxy.core.UnifiedCoreManager
import com.ufo.galaxy.config.GalaxyConfig
import kotlinx.coroutines.*

/**
 * UFO Galaxy Android Application
 * ==============================
 * 
 * 统一启动入口，负责：
 * 1. 初始化 UnifiedCoreManager
 * 2. 创建通知渠道
 * 3. 配置全局异常处理
 * 4. 管理应用生命周期
 * 
 * 作者：Manus AI
 * 日期：2026-02-06
 * 版本：2.0 (融合整合版)
 */
class UFOGalaxyApplication : Application() {

    companion object {
        private const val TAG = "UFOGalaxyApp"
        
        // 通知渠道 ID
        const val CHANNEL_FOREGROUND = "ufo_galaxy_foreground"
        const val CHANNEL_STATUS = "ufo_galaxy_status"
        const val CHANNEL_ALERT = "ufo_galaxy_alert"
        
        @Volatile
        private lateinit var _instance: UFOGalaxyApplication
        
        @JvmStatic
        fun getInstance(): UFOGalaxyApplication {
            return _instance
        }
        
        @get:JvmName("getInstanceProperty")
        val instance: UFOGalaxyApplication
            get() = _instance
    }

    // 核心管理器
    private lateinit var coreManager: UnifiedCoreManager
    
    // 配置
    private lateinit var config: GalaxyConfig
    
    // AgentCore 实例（用于向后兼容）
    val agentCore: com.ufo.galaxy.core.AgentCore by lazy {
        com.ufo.galaxy.core.AgentCore.getInstance(this)
    }
    
    // 协程作用域
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        _instance = this
        
        Log.i(TAG, "UFO Galaxy Application starting...")
        
        // 1. 初始化配置
        initConfig()
        
        // 2. 创建通知渠道
        createNotificationChannels()
        
        // 3. 设置全局异常处理
        setupExceptionHandler()
        
        // 4. 初始化核心管理器
        initCoreManager()
        
        Log.i(TAG, "UFO Galaxy Application started")
    }
    
    /**
     * 初始化配置
     */
    private fun initConfig() {
        config = GalaxyConfig.getInstance(this)
        Log.d(TAG, "Config loaded: serverUrl=${config.getServerUrl()}")
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // 前台服务渠道
            val foregroundChannel = NotificationChannel(
                CHANNEL_FOREGROUND,
                "UFO Galaxy 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "UFO Galaxy 后台服务运行通知"
                setShowBadge(false)
            }
            
            // 状态更新渠道
            val statusChannel = NotificationChannel(
                CHANNEL_STATUS,
                "状态更新",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "设备状态和任务进度通知"
            }
            
            // 警告渠道
            val alertChannel = NotificationChannel(
                CHANNEL_ALERT,
                "重要提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "重要事件和错误通知"
                enableVibration(true)
            }
            
            notificationManager.createNotificationChannels(listOf(
                foregroundChannel,
                statusChannel,
                alertChannel
            ))
            
            Log.d(TAG, "Notification channels created")
        }
    }
    
    /**
     * 设置全局异常处理
     */
    private fun setupExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception in thread ${thread.name}", throwable)
            
            // 保存崩溃日志
            saveCrashLog(throwable)
            
            // 调用默认处理器
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
    
    /**
     * 保存崩溃日志
     */
    private fun saveCrashLog(throwable: Throwable) {
        try {
            val crashLog = buildString {
                appendLine("=== UFO Galaxy Crash Log ===")
                appendLine("Time: ${System.currentTimeMillis()}")
                appendLine("Exception: ${throwable.javaClass.name}")
                appendLine("Message: ${throwable.message}")
                appendLine("Stack trace:")
                throwable.stackTrace.forEach { element ->
                    appendLine("  at $element")
                }
            }
            
            // 保存到文件
            val file = java.io.File(filesDir, "crash_log.txt")
            file.writeText(crashLog)
            
            Log.d(TAG, "Crash log saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log", e)
        }
    }
    
    /**
     * 初始化核心管理器
     */
    private fun initCoreManager() {
        coreManager = UnifiedCoreManager.getInstance(this)
        
        // 异步初始化
        applicationScope.launch {
            try {
                val serverUrl = config.getServerUrl()
                if (serverUrl.isNotEmpty()) {
                    val result = coreManager.initialize()
                    if (result.isSuccess) {
                        Log.i(TAG, "Core manager initialized successfully")
                    } else {
                        Log.e(TAG, "Core manager initialization failed: ${result.exceptionOrNull()?.message}")
                    }
                } else {
                    Log.w(TAG, "Server URL not configured, skipping core manager initialization")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing core manager", e)
            }
        }
    }
    
    /**
     * 获取核心管理器
     */
    fun getCoreManager(): UnifiedCoreManager = coreManager
    
    /**
     * 获取配置
     */
    fun getConfig(): GalaxyConfig = config
    
    /**
     * 应用终止
     */
    override fun onTerminate() {
        Log.i(TAG, "UFO Galaxy Application terminating...")
        
        applicationScope.cancel()
        coreManager.shutdown()
        
        super.onTerminate()
    }
}
