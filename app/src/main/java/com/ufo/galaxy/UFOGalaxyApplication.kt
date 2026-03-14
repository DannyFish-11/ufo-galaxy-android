package com.ufo.galaxy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.ufo.galaxy.agent.AutonomousExecutionPipeline
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.LocalCollaborationAgent
import com.ufo.galaxy.agent.LocalGoalExecutor
import com.ufo.galaxy.data.AppConfig
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.data.SharedPrefsAppSettings
import com.ufo.galaxy.grounding.SeeClickGroundingEngine
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.loop.ExecutorBridge
import com.ufo.galaxy.loop.LocalPlanner
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.network.OfflineTaskQueue
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.planner.MobileVlmPlanner
import com.ufo.galaxy.service.AccessibilityActionExecutor
import com.ufo.galaxy.service.AccessibilityScreenshotProvider
import com.ufo.galaxy.service.AndroidBitmapScaler
import com.ufo.galaxy.service.ReadinessChecker
import com.ufo.galaxy.service.ReadinessState

/**
 * UFO Galaxy Android Application
 * 应用程序入口，负责全局初始化
 */
class UFOGalaxyApplication : Application() {
    
    companion object {
        private const val TAG = "UFOGalaxyApp"
        
        // 通知渠道 ID
        const val CHANNEL_SERVICE = "ufo_galaxy_service"
        const val CHANNEL_MESSAGES = "ufo_galaxy_messages"
        const val CHANNEL_ALERTS = "ufo_galaxy_alerts"
        
        // 全局实例
        lateinit var instance: UFOGalaxyApplication
            private set
        
        // 全局 WebSocket 客户端
        lateinit var webSocketClient: GalaxyWebSocketClient
            private set
        
        // 本地模型资产管理器
        lateinit var modelAssetManager: ModelAssetManager
            private set

        // 模型下载器：用于在模型文件缺失或损坏时按需下载
        lateinit var modelDownloader: ModelDownloader
            private set

        // 本地推理服务：MobileVLM 1.7B 规划器
        lateinit var plannerService: LocalPlannerService
            private set

        // 本地推理服务：SeeClick grounding 引擎
        lateinit var groundingService: LocalGroundingService
            private set

        // EdgeExecutor（本地 AIP v3 任务执行编排器）
        lateinit var edgeExecutor: EdgeExecutor
            private set

        // LocalGoalExecutor: handles goal_execution and parallel_subtask locally
        lateinit var localGoalExecutor: LocalGoalExecutor
            private set

        // LocalCollaborationAgent: coordinates parallel_subtask via LocalGoalExecutor
        lateinit var localCollaborationAgent: LocalCollaborationAgent
            private set

        // AutonomousExecutionPipeline: gates goal_execution/parallel_subtask behind AppSettings flags
        lateinit var autonomousExecutionPipeline: AutonomousExecutionPipeline
            private set

        // LoopController: local closed-loop automation pipeline (natural language → loop)
        lateinit var loopController: LoopController
            private set

        // 全局配置
        lateinit var appConfig: AppConfig
            private set

        // 持久化应用设置（跨设备开关及能力标志）
        lateinit var appSettings: AppSettings
            private set

        /**
         * Latest readiness snapshot. Reflects the last call to [ReadinessChecker.checkAll].
         * Initialised to all-false on startup; updated after [initModelAssetManager] and
         * again by [GalaxyConnectionService] after models are loaded.
         */
        @Volatile
        var readinessState: ReadinessState = ReadinessState(
            modelReady = false,
            accessibilityReady = false,
            overlayReady = false
        )
            private set
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        
        Log.i(TAG, "UFO Galaxy Application 启动")

        // Initialise structured observability logger (must be first, before any log calls).
        GalaxyLogger.init(this)
        
        // 初始化配置
        initConfig()
        
        // 初始化模型资产管理器
        initModelAssetManager()
        
        // 创建通知渠道
        createNotificationChannels()
        
        // Run capability readiness self-checks (non-blocking; results stored in appSettings).
        runReadinessChecks()
        
        // 初始化推理服务
        initInferenceServices()
        
        // 初始化 WebSocket 客户端
        initWebSocketClient()
        
        Log.i(TAG, "UFO Galaxy Application 初始化完成")
    }

    override fun onTerminate() {
        super.onTerminate()
        // Cancel ModelDownloader coroutine scope to release resources.
        // Note: onTerminate() is not guaranteed to be called on real devices,
        // but is called in tests/emulators. Process termination cleans up anyway.
        modelDownloader.cancel()
        Log.i(TAG, "UFO Galaxy Application 终止")
    }
    
    /**
     * 初始化配置
     *
     * [appSettings] is initialised first so that the persisted [AppSettings.crossDeviceEnabled]
     * value (default: false) can be used to populate [AppConfig] and the WebSocket client.
     * Build-time [BuildConfig.CROSS_DEVICE_ENABLED] serves only as a compile-time default;
     * the runtime toggle stored in [SharedPrefsAppSettings] takes precedence.
     */
    private fun initConfig() {
        appSettings = SharedPrefsAppSettings(this)
        appConfig = AppConfig(
            serverUrl = BuildConfig.GALAXY_SERVER_URL,
            apiVersion = BuildConfig.API_VERSION,
            isDebug = BuildConfig.DEBUG,
            crossDeviceEnabled = appSettings.crossDeviceEnabled,
            plannerMaxTokens = BuildConfig.PLANNER_MAX_TOKENS,
            plannerTemperature = BuildConfig.PLANNER_TEMPERATURE,
            plannerTimeoutMs = BuildConfig.PLANNER_TIMEOUT_MS,
            groundingTimeoutMs = BuildConfig.GROUNDING_TIMEOUT_MS,
            scaledMaxEdge = BuildConfig.SCALED_MAX_EDGE
        )
        Log.d(TAG, "配置已加载: serverUrl=${appConfig.serverUrl} crossDevice=${appSettings.crossDeviceEnabled}")
    }

    /**
     * Runs the three capability readiness self-checks at startup.
     *
     * Results are stored in [appSettings] and in [readinessState] so the UI and
     * [GalaxyWebSocketClient] capability_report can reflect the current state.
     * If any check fails, [AppSettings.degradedMode] will be true and the UI will
     * show a non-blocking status indicator; autonomous execution is still attempted
     * but may produce limited results.
     */
    private fun runReadinessChecks() {
        readinessState = ReadinessChecker.checkAll(this)
        Log.i(
            TAG,
            "Readiness: model=${readinessState.modelReady} " +
                "accessibility=${readinessState.accessibilityReady} " +
                "overlay=${readinessState.overlayReady} " +
                "degraded=${readinessState.degradedMode}"
        )
    }

    /**
     * Re-runs readiness checks and refreshes [readinessState].
     * Call from any component after a permission grant or service state change.
     */
    fun refreshReadiness() {
        runReadinessChecks()
    }

    /**
     * 初始化本地模型资产管理器并在应用启动时预检模型文件。
     * Actual model loading (inference server ping) is deferred to GalaxyConnectionService.
     */
    private fun initModelAssetManager() {
        modelAssetManager = ModelAssetManager(this)
        modelDownloader = ModelDownloader(modelAssetManager.modelsDir)
        val statuses = modelAssetManager.verifyAll()
        Log.d(TAG, "模型文件状态: $statuses")
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // 服务通知渠道
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Galaxy 服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "UFO Galaxy 后台服务通知"
                setShowBadge(false)
            }
            
            // 消息通知渠道
            val messageChannel = NotificationChannel(
                CHANNEL_MESSAGES,
                "消息通知",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "来自 Galaxy 的消息通知"
                enableVibration(true)
            }
            
            // 警报通知渠道
            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "重要警报",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "重要的系统警报"
                enableVibration(true)
                enableLights(true)
            }
            
            notificationManager.createNotificationChannels(
                listOf(serviceChannel, messageChannel, alertChannel)
            )
            
            Log.d(TAG, "通知渠道已创建")
        }
    }
    
    /**
     * 初始化 WebSocket 客户端
     *
     * The client is initialised with [AppSettings.crossDeviceEnabled] so the persisted
     * toggle state is respected from the very first connection attempt.
     * Initial capability metadata is pre-populated from [appSettings] so that the
     * handshake sent on [onOpen] already carries the correct flags even before
     * [GalaxyConnectionService.loadModels] runs.
     *
     * An [OfflineTaskQueue] backed by SharedPreferences is injected so that queued
     * task results survive app restarts (messages older than 24 h are discarded on load).
     */
    private fun initWebSocketClient() {
        val queuePrefs = getSharedPreferences(OfflineTaskQueue.TAG, MODE_PRIVATE)
        val offlineQueue = OfflineTaskQueue(prefs = queuePrefs)
        webSocketClient = GalaxyWebSocketClient(
            serverUrl = appSettings.galaxyGatewayUrl,
            crossDeviceEnabled = appSettings.crossDeviceEnabled,
            offlineQueue = offlineQueue
        )
        webSocketClient.setDeviceMetadata(appSettings.toMetadataMap())
        Log.d(TAG, "WebSocket 客户端已初始化 (offlineQueue restored size=${offlineQueue.size})")
    }

    /**
     * Toggles the cross-device collaboration switch at runtime.
     *
     * When [enabled] is false:
     *  - [GalaxyWebSocketClient.crossDeviceEnabled] is set to false.
     *  - Any active WS connection is disconnected.
     *  - No further registration or capability_report messages will be sent.
     *
     * When [enabled] is true:
     *  - [GalaxyWebSocketClient.crossDeviceEnabled] is set to true.
     *  - The caller should start (or restart) [GalaxyConnectionService] to
     *    establish the connection and send the initial capability_report.
     *
     * Thread-safe: delegates to [GalaxyWebSocketClient.setCrossDeviceEnabled] which is
     * annotated with [@Volatile].
     */
    fun setCrossDeviceEnabled(enabled: Boolean) {
        Log.i(TAG, "setCrossDeviceEnabled $enabled")
        webSocketClient.setCrossDeviceEnabled(enabled)
        if (!enabled && webSocketClient.isConnected()) {
            webSocketClient.disconnect()
        }
    }

    /**
     * 初始化本地推理服务和 EdgeExecutor
     * Model loading (server ping / prewarm) is performed by GalaxyConnectionService on start.
     * Model file paths from ModelAssetManager are passed to each engine so that the
     * inference server can locate the weight files on first launch.
     */
    private fun initInferenceServices() {
        plannerService = MobileVlmPlanner(
            modelPath = modelAssetManager.mobileVlmPath,
            maxTokens = appConfig.plannerMaxTokens,
            temperature = appConfig.plannerTemperature,
            timeoutMs = appConfig.plannerTimeoutMs
        )
        groundingService = SeeClickGroundingEngine(
            modelParamPath = modelAssetManager.seeClickParamPath,
            modelBinPath = modelAssetManager.seeClickBinPath,
            timeoutMs = appConfig.groundingTimeoutMs
        )
        edgeExecutor = EdgeExecutor(
            screenshotProvider = AccessibilityScreenshotProvider(),
            plannerService = plannerService,
            groundingService = groundingService,
            accessibilityExecutor = AccessibilityActionExecutor(),
            imageScaler = AndroidBitmapScaler(),
            scaledMaxEdge = appConfig.scaledMaxEdge
        )
        val deviceId = "${android.os.Build.MANUFACTURER}_${android.os.Build.MODEL}"
        localGoalExecutor = LocalGoalExecutor(
            edgeExecutor = edgeExecutor,
            deviceId = deviceId
        )
        localCollaborationAgent = LocalCollaborationAgent(goalExecutor = localGoalExecutor)
        autonomousExecutionPipeline = AutonomousExecutionPipeline(
            settings = appSettings,
            goalExecutor = localGoalExecutor,
            collaborationAgent = localCollaborationAgent,
            deviceId = deviceId,
            deviceRole = appSettings.deviceRole
        )
        loopController = LoopController(
            localPlanner = LocalPlanner(plannerService = plannerService),
            executorBridge = ExecutorBridge(
                groundingService = groundingService,
                accessibilityExecutor = AccessibilityActionExecutor(),
                imageScaler = AndroidBitmapScaler(),
                scaledMaxEdge = appConfig.scaledMaxEdge
            ),
            screenshotProvider = AccessibilityScreenshotProvider(),
            modelAssetManager = modelAssetManager,
            modelDownloader = modelDownloader
        )
        Log.d(TAG, "推理服务已初始化")
    }
    
    /**
     * 获取 WebSocket 客户端
     */
    fun getWebSocket(): GalaxyWebSocketClient = webSocketClient
    
    /**
     * 获取配置
     */
    fun getConfig(): AppConfig = appConfig
}
