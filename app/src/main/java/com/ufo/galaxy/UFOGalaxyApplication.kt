package com.ufo.galaxy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.ufo.galaxy.agent.AutonomousExecutionPipeline
import com.ufo.galaxy.agent.AgentRuntimeBridge
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.LocalCollaborationAgent
import com.ufo.galaxy.agent.LocalGoalExecutor
import com.ufo.galaxy.config.LocalLoopConfig
import com.ufo.galaxy.data.AppConfig
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.data.SharedPrefsAppSettings
import com.ufo.galaxy.grounding.SeeClickGroundingEngine
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.local.DefaultLocalLoopExecutor
import com.ufo.galaxy.local.DefaultLocalLoopReadinessProvider
import com.ufo.galaxy.local.LocalLoopExecutor
import com.ufo.galaxy.local.LocalLoopReadinessProvider
import com.ufo.galaxy.loop.ExecutorBridge
import com.ufo.galaxy.loop.LocalPlanner
import com.ufo.galaxy.loop.LoopController
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.network.NetworkDiagnostics
import com.ufo.galaxy.network.OfflineTaskQueue
import com.ufo.galaxy.network.TailscaleAdapter
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.observability.MetricsRecorder
import com.ufo.galaxy.planner.MobileVlmPlanner
import com.ufo.galaxy.runtime.RuntimeController
import com.ufo.galaxy.runtime.LocalInferenceRuntimeManager
import com.ufo.galaxy.service.AccessibilityActionExecutor
import com.ufo.galaxy.service.AccessibilityScreenshotProvider
import com.ufo.galaxy.service.AndroidBitmapScaler
import com.ufo.galaxy.service.ReadinessChecker
import com.ufo.galaxy.service.ReadinessState
import com.ufo.galaxy.history.SessionHistoryStore
import com.ufo.galaxy.trace.LocalLoopTraceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

        // LocalLoopReadinessProvider: single source of truth for local-loop subsystem readiness
        lateinit var localLoopReadinessProvider: LocalLoopReadinessProvider
            private set

        // LocalLoopTraceStore: in-memory store of recent local-loop execution traces (PR-E / PR-G)
        val localLoopTraceStore: LocalLoopTraceStore = LocalLoopTraceStore()

        /**
         * Persistent session history store (PR-H).
         *
         * Retains lightweight [com.ufo.galaxy.history.SessionHistorySummary] records for
         * completed local-loop sessions across app restarts. Backed by SharedPreferences
         * (initialised in [initSessionHistoryStore]); falls back to in-memory mode before
         * [onCreate] completes.
         */
        @Volatile
        var sessionHistoryStore: SessionHistoryStore = SessionHistoryStore()
            private set

        /**
         * Active [LocalLoopConfig] for the local-loop pipeline; `null` before initialisation.
         * Exposed for the debug panel to inspect the current config at runtime.
         */
        @Volatile
        var localLoopConfig: LocalLoopConfig? = null
            private set

        // LocalLoopExecutor: canonical entrypoint for UI/voice-driven local goal execution.
        // Gateway goal execution (goal_execution / parallel_subtask) flows exclusively through
        // AutonomousExecutionPipeline → LocalGoalExecutor to ensure runtime and feature gates.
        lateinit var localLoopExecutor: LocalLoopExecutor
            private set

        // RuntimeController: manages cross-device ON/OFF lifecycle, registration, and fallback
        lateinit var runtimeController: RuntimeController
            private set

        // LocalInferenceRuntimeManager: lifecycle authority for the on-device planner+grounding pair
        lateinit var localInferenceRuntimeManager: LocalInferenceRuntimeManager
            private set

        // AgentRuntimeBridge: bridges eligible tasks to Agent Runtime / OpenClawd when cross-device is ON
        lateinit var agentRuntimeBridge: AgentRuntimeBridge
            private set

        // 全局配置
        lateinit var appConfig: AppConfig
            private set

        // 持久化应用设置（跨设备开关及能力标志）
        lateinit var appSettings: AppSettings
            private set

        // TailscaleAdapter: Tailscale 网络检测与自动发现（网络与诊断增强包）
        lateinit var tailscaleAdapter: TailscaleAdapter
            private set

        // NetworkDiagnostics: 网络诊断模块（网络与诊断增强包）
        lateinit var networkDiagnostics: NetworkDiagnostics
            private set

        // MetricsRecorder: 指标记录器（网络与诊断增强包）
        lateinit var metricsRecorder: MetricsRecorder
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

        // Initialise RuntimeController (requires webSocketClient, appSettings, loopController).
        initRuntimeController()

        // 初始化网络与诊断增强模块
        initNetworkDiagnosticsModules()

        // Initialise AgentRuntimeBridge (requires webSocketClient, appSettings, metricsRecorder).
        initAgentRuntimeBridge()

        // Initialise persistent session history store (PR-H).
        initSessionHistoryStore()

        // Ensure model files are present at startup so that local loop and cross-device
        // runtime are both ready regardless of whether GalaxyConnectionService has started.
        // Runs in a background scope; failures are logged but never block the app.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            ensureModelsAtStartup()
        }
        
        Log.i(TAG, "UFO Galaxy Application 初始化完成")
    }

    override fun onTerminate() {
        super.onTerminate()
        // Cancel ModelDownloader coroutine scope to release resources.
        // Note: onTerminate() is not guaranteed to be called on real devices,
        // but is called in tests/emulators. Process termination cleans up anyway.
        modelDownloader.cancel()
        runtimeController.cancel()
        localInferenceRuntimeManager.cancel()
        metricsRecorder.stop()
        Log.i(TAG, "UFO Galaxy Application 终止")
    }
    
    /**
     * 初始化配置
     *
     * [appSettings] is initialised first so that the persisted [AppSettings.crossDeviceEnabled]
     * value (default: false) can be used to populate [AppConfig] and the WebSocket client.
     *
     * [AppConfig] now holds only build-time / identity fields.  All local-chain execution
     * settings (planner tokens, timeouts, scaling) are read from [appSettings], which seeds
     * its defaults from `assets/config.properties` and falls back to compile-time constants.
     * Build-time [BuildConfig] fields for those settings serve as last-resort fallbacks only.
     */
    private fun initConfig() {
        appSettings = SharedPrefsAppSettings(this)
        appConfig = AppConfig(
            serverUrl = BuildConfig.GALAXY_SERVER_URL,
            apiVersion = BuildConfig.API_VERSION,
            isDebug = BuildConfig.DEBUG,
            crossDeviceEnabled = appSettings.crossDeviceEnabled
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
     *
     * Config priority for the server URL: SharedPreferences (gateway_host/port/tls) →
     * assets/config.properties → compile-time default (via [AppSettings.effectiveGatewayWsUrl]).
     */
    private fun initWebSocketClient() {
        val queuePrefs = getSharedPreferences(OfflineTaskQueue.TAG, MODE_PRIVATE)
        val offlineQueue = OfflineTaskQueue(prefs = queuePrefs)
        val wsUrl = appSettings.effectiveGatewayWsUrl()
        webSocketClient = GalaxyWebSocketClient(
            serverUrl = wsUrl,
            crossDeviceEnabled = appSettings.crossDeviceEnabled,
            offlineQueue = offlineQueue,
            allowSelfSigned = appSettings.allowSelfSigned,
            gatewayToken = appSettings.gatewayToken
        )
        webSocketClient.setDeviceMetadata(appSettings.toMetadataMap())
        Log.d(TAG, "WebSocket 客户端已初始化: url=$wsUrl allowSelfSigned=${appSettings.allowSelfSigned} (offlineQueue restored size=${offlineQueue.size})")
    }

    /**
     * Toggles the cross-device collaboration switch at runtime.
     *
     * Delegates entirely to [RuntimeController], which is the sole lifecycle authority for
     * the cross-device runtime:
     *  - When [enabled] is false: [RuntimeController.stop] disconnects the WS, resets
     *    [AppSettings.crossDeviceEnabled], and transitions to LocalOnly.
     *  - When [enabled] is true: [AppSettings.crossDeviceEnabled] is updated and
     *    [RuntimeController.connectIfEnabled] syncs the WS client state and initiates a
     *    best-effort reconnect. Callers that need the full registration flow with timeout and
     *    failure notification should use [RuntimeController.startWithTimeout] directly.
     *
     * Must only be called after [initRuntimeController] has completed (i.e., post-[onCreate]).
     */
    fun setCrossDeviceEnabled(enabled: Boolean) {
        Log.i(TAG, "setCrossDeviceEnabled $enabled")
        if (!enabled) {
            // RuntimeController.stop() handles WS disconnect, settings update, and state transition.
            runtimeController.stop()
        } else {
            // Persist the intent first so connectIfEnabled() reads the correct value.
            appSettings.crossDeviceEnabled = true
            // RuntimeController.connectIfEnabled() syncs the WS client and triggers reconnect.
            runtimeController.connectIfEnabled()
        }
    }

    /**
     * 初始化本地推理服务和 EdgeExecutor
     * Model loading (server ping / prewarm) is performed by GalaxyConnectionService on start.
     * Model file paths from ModelAssetManager are passed to each engine so that the
     * inference server can locate the weight files on first launch.
     *
     * All local-chain execution settings are sourced from [appSettings] — the single
     * effective settings authority — rather than from [AppConfig] or [BuildConfig] directly.
     */
    private fun initInferenceServices() {
        plannerService = MobileVlmPlanner(
            modelPath = modelAssetManager.mobileVlmPath,
            maxTokens = appSettings.plannerMaxTokens,
            temperature = appSettings.plannerTemperature,
            timeoutMs = appSettings.plannerTimeoutMs
        )
        groundingService = SeeClickGroundingEngine(
            modelParamPath = modelAssetManager.seeClickParamPath,
            modelBinPath = modelAssetManager.seeClickBinPath,
            timeoutMs = appSettings.groundingTimeoutMs
        )
        edgeExecutor = EdgeExecutor(
            screenshotProvider = AccessibilityScreenshotProvider(),
            plannerService = plannerService,
            groundingService = groundingService,
            accessibilityExecutor = AccessibilityActionExecutor(),
            imageScaler = AndroidBitmapScaler(),
            scaledMaxEdge = appSettings.scaledMaxEdge
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
                scaledMaxEdge = appSettings.scaledMaxEdge
            ),
            screenshotProvider = AccessibilityScreenshotProvider(),
            modelAssetManager = modelAssetManager,
            modelDownloader = modelDownloader
        )
        localLoopReadinessProvider = DefaultLocalLoopReadinessProvider(
            modelAssetManager = modelAssetManager,
            plannerService = plannerService,
            groundingService = groundingService
        )
        // Build LocalLoopConfig from the effective settings authority so that runtime
        // planner/grounding parameters reflect the full configuration hierarchy.
        localLoopConfig = LocalLoopConfig.from(appSettings)
        localLoopExecutor = DefaultLocalLoopExecutor(
            loopController = loopController,
            goalExecutor = localGoalExecutor,
            readinessProvider = localLoopReadinessProvider
        )
        localInferenceRuntimeManager = LocalInferenceRuntimeManager(
            plannerService = plannerService,
            groundingService = groundingService,
            modelAssetManager = modelAssetManager
        )
        Log.d(TAG, "推理服务已初始化")
    }

    /**
     * Initialises [RuntimeController], which manages the cross-device ON/OFF lifecycle.
     *
     * Must be called after [initInferenceServices] and [initWebSocketClient] because both
     * [loopController] and [webSocketClient] must be available.
     */
    private fun initRuntimeController() {
        runtimeController = RuntimeController(
            webSocketClient = webSocketClient,
            settings = appSettings,
            loopController = loopController
        )
        Log.d(TAG, "RuntimeController 已初始化")
    }

    /**
     * Initialises the network-diagnostics enhancement modules:
     * - [TailscaleAdapter]: Tailscale detection and auto-discovery
     * - [NetworkDiagnostics]: HTTP + WS health checks
     * - [MetricsRecorder]: WS reconnect / registration failure / task metrics
     *
     * Must be called after [initConfig] (needs [appSettings]).
     */
    private fun initNetworkDiagnosticsModules() {
        tailscaleAdapter = TailscaleAdapter(appSettings)
        networkDiagnostics = NetworkDiagnostics(appSettings)
        metricsRecorder = MetricsRecorder(appSettings)
        metricsRecorder.start()
        Log.d(TAG, "网络与诊断增强模块已初始化")
    }

    /**
     * Initialises [AgentRuntimeBridge], which delegates eligible tasks to Agent Runtime /
     * OpenClawd when the cross-device switch is ON.
     *
     * Must be called after [initWebSocketClient] and [initNetworkDiagnosticsModules]
     * because both [webSocketClient] and [metricsRecorder] must be available.
     */
    private fun initAgentRuntimeBridge() {
        agentRuntimeBridge = AgentRuntimeBridge(
            gatewayClient = webSocketClient,
            settings = appSettings,
            metricsRecorder = metricsRecorder
        )
        Log.d(TAG, "AgentRuntimeBridge 已初始化")
    }

    /**
     * Initialises the [SessionHistoryStore] singleton backed by SharedPreferences (PR-H).
     *
     * Called after [initConfig] so that [getSharedPreferences] is available. The
     * in-memory-only default assigned at companion-object init time is replaced with the
     * SharedPreferences-backed instance here.
     */
    private fun initSessionHistoryStore() {
        val prefs = getSharedPreferences("ufo_galaxy_session_history", Context.MODE_PRIVATE)
        sessionHistoryStore = SessionHistoryStore(prefs = prefs)
        Log.d(TAG, "SessionHistoryStore 已初始化 (${sessionHistoryStore.size()} entries loaded)")
    }

    /**
     * Verifies local model files at startup and downloads any that are missing or corrupted.
     *
     * This ensures that both the local [LoopController] and the cross-device runtime
     * have access to local models as soon as the app starts — regardless of whether
     * [com.ufo.galaxy.service.GalaxyConnectionService] has been started yet.
     *
     * Download failures are logged but never block app startup.
     */
    private suspend fun ensureModelsAtStartup() {
        Log.i(TAG, "ensureModelsAtStartup: verifying local model files")
        try {
            val statuses = modelAssetManager.verifyAll()
            val allPresent = statuses.values.none {
                it == com.ufo.galaxy.model.ModelAssetManager.ModelStatus.MISSING ||
                    it == com.ufo.galaxy.model.ModelAssetManager.ModelStatus.CORRUPTED
            }
            if (allPresent) {
                Log.d(TAG, "ensureModelsAtStartup: all model files present")
                return
            }
            val specs = modelAssetManager.downloadSpecsForMissing()
            if (specs.isEmpty()) {
                Log.d(TAG, "ensureModelsAtStartup: no download URLs configured; skipping")
                return
            }
            Log.i(TAG, "ensureModelsAtStartup: downloading ${specs.size} model file(s)")
            for (spec in specs) {
                val ok = modelDownloader.downloadSync(spec) {}
                Log.i(TAG, "ensureModelsAtStartup: ${spec.modelId} ok=$ok")
            }
            modelAssetManager.verifyAll()
            // Re-run readiness checks so UI/capability_report reflect updated model state.
            refreshReadiness()
        } catch (e: Exception) {
            Log.e(TAG, "ensureModelsAtStartup failed: ${e.message}", e)
        }
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
