package com.ufo.galaxy.runtime

/**
 * PR-9（Android）— Android 侧最小可用路径与可操作性合约
 * （Android Minimal Operability Contract）
 *
 * 本合约将 Android 侧的最小可用路径（build → install → configure → connect →
 * report → delegate → uplink → local-mode → diagnose）从"作者记忆工作流"
 * 提升为**明确、可机器验证、可诊断**的形式化可操作性合约。
 *
 * ## 核心目标
 *
 * 本合约使以下行为对开发者、运维和自动化工具更清晰、更可推理：
 *  - APK 构建与安装
 *  - V2 服务端配置与 WebSocket 连接
 *  - 设备状态上报（device truth）
 *  - 接收委托任务与上行结果
 *  - 本地模式（local mode）就绪前提判断
 *  - 关键失败点的诊断
 *
 * ## 十步最小可用路径
 *
 * | 步骤 | [OperabilityPathStep]                             | 说明 |
 * |------|---------------------------------------------------|------|
 * |  1   | [OperabilityPathStep.CLONE_REPO]                  | 克隆 Android 仓库 |
 * |  2   | [OperabilityPathStep.BUILD_APK]                   | 构建 APK |
 * |  3   | [OperabilityPathStep.INSTALL_APK]                 | 安装 APK |
 * |  4   | [OperabilityPathStep.CONFIGURE_V2_SERVER]         | 配置 V2 服务端地址 |
 * |  5   | [OperabilityPathStep.ESTABLISH_WS_CONNECTION]     | 建立 WebSocket 连接 |
 * |  6   | [OperabilityPathStep.REPORT_DEVICE_TRUTH]         | 上报设备真相快照 |
 * |  7   | [OperabilityPathStep.RECEIVE_DELEGATED_TASK]      | 接收委托任务 |
 * |  8   | [OperabilityPathStep.UPLINK_RESULT]               | 上行任务结果 |
 * |  9   | [OperabilityPathStep.ASSESS_LOCAL_MODE_READINESS] | 判断本地模式就绪状态 |
 * | 10   | [OperabilityPathStep.DIAGNOSE_FAILURE]            | 诊断失败点 |
 *
 * ## 关键区分：阻断 vs 降级
 *
 * [PathBlockCondition] 枚举列出会**硬性阻断**对应步骤的前提条件。
 * [CapabilityDegradationKind] 枚举列出仅会**降级**能力（不阻断主路径）的条件。
 *
 * V2 消费方 MUST：
 *  1. 在 [PathBlockCondition.blocksStep] 命中时，不向 Android 派发该步骤相关的任务。
 *  2. 将 [CapabilityDegradationKind] 视为"可接受但能力受限"的运行态，继续尝试
 *     不需要该能力的任务类型。
 *  3. 从 [FailureDiagnosticKind] 中读取 Android 上报的失败类型，而非依赖字符串匹配。
 *
 * ## 权威边界
 *
 * | 关注点                     | 权威方  | 说明 |
 * |---------------------------|---------|------|
 * | 本地构建与安装              | 开发者  | 不在 V2 权威范围内 |
 * | 本地模式就绪判断            | Android | [LocalModeReadinessGate] |
 * | 委托执行阻断 vs 降级分类    | Android | [DelegatedExecutionBlockKind] |
 * | 设备真相快照                | Android | [AndroidUnifiedTruthUplinkContract] |
 * | 连接终态（accept/reject）   | V2      | Android 上报信号；V2 裁决 |
 * | 结果接受与闭合              | V2      | Android 提供证据；V2 决定门控 |
 *
 * @see LocalExecutionModeGate
 * @see AndroidUnifiedTruthUplinkContract
 * @see AndroidOperationalStateSurfaceContract
 * @see AndroidLocalDiagnosticReasonContract
 * @see DelegatedRuntimeReadinessEvaluator
 */
object AndroidMinimalOperabilityContract {

    // ── 合约元数据 ──────────────────────────────────────────────────────────────

    /** 本合约的引入 PR 号（GitHub PR 序号）。 */
    const val INTRODUCED_PR: Int = 9

    /** wire map schema 版本。 */
    const val SCHEMA_VERSION: String = "1"

    // ── 步骤一：十步最小可用路径枚举 ─────────────────────────────────────────────

    /**
     * 最小可用路径的十个有序步骤。
     *
     * 每个步骤对应一个可独立验证的里程碑。
     *
     * @property stepIndex        步骤序号（1–10）。
     * @property wireValue        稳定的 wire 字符串，用于日志与诊断。
     * @property displayName      简短的人类可读名称。
     * @property description      一句话说明该步骤目标。
     * @property isAndroidLocal   `true` 表示该步骤完全在 Android 设备本地执行；
     *                            `false` 表示需要 V2 参与或网络。
     */
    enum class OperabilityPathStep(
        val stepIndex: Int,
        val wireValue: String,
        val displayName: String,
        val description: String,
        val isAndroidLocal: Boolean
    ) {

        /** 步骤 1：克隆 Android 仓库到开发机。 */
        CLONE_REPO(
            stepIndex = 1,
            wireValue = "clone_repo",
            displayName = "克隆仓库",
            description = "从 GitHub 克隆 DannyFish-11/ufo-galaxy-android 仓库。",
            isAndroidLocal = true
        ),

        /**
         * 步骤 2：使用 build_apk.sh 或 Gradle 构建 APK。
         *
         * 前提：JDK 17+，ANDROID_HOME 已设置，Gradle wrapper 可用。
         * 构建产物：`app/build/outputs/apk/debug/app-debug.apk`。
         */
        BUILD_APK(
            stepIndex = 2,
            wireValue = "build_apk",
            displayName = "构建 APK",
            description = "运行 ./build_apk.sh 或 ./gradlew assembleDebug 构建 APK。",
            isAndroidLocal = true
        ),

        /**
         * 步骤 3：通过 ADB 或手动方式将 APK 安装到 Android 设备。
         *
         * 前提：设备已启用 USB 调试或通过 adb connect 连接。
         */
        INSTALL_APK(
            stepIndex = 3,
            wireValue = "install_apk",
            displayName = "安装 APK",
            description = "通过 adb install 或设备侧手动安装将 APK 部署到目标设备。",
            isAndroidLocal = true
        ),

        /**
         * 步骤 4：在 Android App 中配置 V2 服务端地址（gateway URL）。
         *
         * 前提：V2 服务已运行；已知 V2 宿主机 IP 和端口（默认 9000）。
         * 配置路径：App 内 ⚙ 设置页面，或通过 `AppSettings.serverUrl` 程序化设置。
         *
         * 阻断条件：如使用 `config.properties` 中的占位地址（100.x.x.x），
         * 连接必然失败；参见 [PathBlockCondition.GATEWAY_URL_IS_PLACEHOLDER]。
         */
        CONFIGURE_V2_SERVER(
            stepIndex = 4,
            wireValue = "configure_v2_server",
            displayName = "配置 V2 服务端",
            description = "设置 V2 gateway WebSocket URL（ws://<host>:9000/ws/device/<id>）并启用 crossDeviceEnabled。",
            isAndroidLocal = false
        ),

        /**
         * 步骤 5：Android 通过 GalaxyWebSocketClient 向 V2 建立 WebSocket 连接。
         *
         * 成功标志：[LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_ACTIVE] 被激活。
         * 自动重连：指数退避 1–30 秒；[GalaxyWebSocketClient] 永不停止重连。
         */
        ESTABLISH_WS_CONNECTION(
            stepIndex = 5,
            wireValue = "establish_ws_connection",
            displayName = "建立 WS 连接",
            description = "GalaxyWebSocketClient 向 V2 gateway 建立 WebSocket 长连接并完成握手。",
            isAndroidLocal = false
        ),

        /**
         * 步骤 6：Android 向 V2 上报设备真相快照（device state snapshot）。
         *
         * 触发：连接建立后自动通过 `GalaxyConnectionService.sendDeviceStateSnapshot()` 发送。
         * 载体：[com.ufo.galaxy.protocol.DeviceStateSnapshotPayload]，
         *       遵循 [AndroidUnifiedTruthUplinkContract.SCHEMA_VERSION]。
         */
        REPORT_DEVICE_TRUTH(
            stepIndex = 6,
            wireValue = "report_device_truth",
            displayName = "上报设备真相",
            description = "向 V2 发送 device_state_snapshot，携带参与状态、执行模式、能力与约束语义。",
            isAndroidLocal = false
        ),

        /**
         * 步骤 7：Android 接收 V2 下发的委托任务（task_assign / goal_execution / parallel_subtask）。
         *
         * 前提：[LocalExecutionModeGate.ExecutionModeState.CROSS_DEVICE_ACTIVE]；
         *       `AppSettings.crossDeviceEnabled = true`；
         *       `AppSettings.goalExecutionEnabled = true`（对于 goal_execution）。
         *
         * 阻断条件：参见 [DelegatedExecutionBlockKind]。
         */
        RECEIVE_DELEGATED_TASK(
            stepIndex = 7,
            wireValue = "receive_delegated_task",
            displayName = "接收委托任务",
            description = "通过 GalaxyConnectionService 分发器接收并路由 V2 下发的委托任务载体。",
            isAndroidLocal = false
        ),

        /**
         * 步骤 8：Android 上行任务执行结果（goal_execution_result）至 V2。
         *
         * 离线容错：结果通过 OfflineTaskQueue 缓冲；重连后自动排队 FIFO 重放。
         * 完整性：[EmittedSignalLedger] 防止终态 RESULT 后重复发送 ACK/PROGRESS。
         */
        UPLINK_RESULT(
            stepIndex = 8,
            wireValue = "uplink_result",
            displayName = "上行结果",
            description = "将 goal_execution_result（含 problem_solving_closure_class）发送至 V2，完成任务闭合。",
            isAndroidLocal = false
        ),

        /**
         * 步骤 9：评估设备当前是否满足本地模式就绪前提。
         *
         * 判断依据：[LocalModeReadinessGate]。
         * 可通过 [LocalModeReadinessGate.assess] 获取 [LocalModeReadinessSnapshot]。
         */
        ASSESS_LOCAL_MODE_READINESS(
            stepIndex = 9,
            wireValue = "assess_local_mode_readiness",
            displayName = "评估本地模式就绪",
            description = "通过 LocalModeReadinessGate.assess() 判断本地推理、可访问性与覆盖层权限是否满足本地执行前提。",
            isAndroidLocal = true
        ),

        /**
         * 步骤 10：诊断关键失败点。
         *
         * 使用 [FailureDiagnosticKind] 对失败原因分类，通过
         * [AndroidLocalDiagnosticReasonContract] 将诊断信息上报至 V2。
         */
        DIAGNOSE_FAILURE(
            stepIndex = 10,
            wireValue = "diagnose_failure",
            displayName = "诊断失败点",
            description = "通过 FailureDiagnosticKind 分类失败原因，并经 AndroidLocalDiagnosticReasonContract 上报诊断载体。",
            isAndroidLocal = true
        );

        companion object {
            /** 从 wire value 解析对应步骤，不存在时返回 null。 */
            fun fromWireValue(value: String?): OperabilityPathStep? =
                entries.firstOrNull { it.wireValue == value }

            /** 按 stepIndex 升序排列的完整路径。 */
            val orderedPath: List<OperabilityPathStep> = entries.sortedBy { it.stepIndex }

            /** 所有 wire value 集合。 */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── 步骤二：路径阻断条件枚举 ──────────────────────────────────────────────────

    /**
     * 会**硬性阻断**某个路径步骤执行的前提条件。
     *
     * 与 [CapabilityDegradationKind] 的关键区别：
     *  - [PathBlockCondition] 表示步骤**无法执行**，必须先解除该条件才能继续。
     *  - [CapabilityDegradationKind] 表示步骤**可降级执行**，某些功能受限但主路径仍可继续。
     *
     * @property wireValue    稳定的 wire 字符串。
     * @property displayName  简短名称。
     * @property description  一句话说明该阻断条件的含义和解除方法。
     * @property blocksStep   受影响的路径步骤。
     * @property isSelfHealable `true` 表示系统可在无人工干预的情况下自动解除该条件。
     */
    enum class PathBlockCondition(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val blocksStep: OperabilityPathStep,
        val isSelfHealable: Boolean
    ) {

        /** JDK 17+ 未安装或 ANDROID_HOME 未设置，无法运行 Gradle。 */
        MISSING_JDK_OR_SDK(
            wireValue = "missing_jdk_or_sdk",
            displayName = "缺少 JDK/SDK",
            description = "JDK 17+ 或 Android SDK（ANDROID_HOME）未配置；build_apk.sh 会检测并提前失败并给出提示。",
            blocksStep = OperabilityPathStep.BUILD_APK,
            isSelfHealable = false
        ),

        /** `config.properties` 中 gateway URL 为占位地址（100.x.x.x），连接必然失败。 */
        GATEWAY_URL_IS_PLACEHOLDER(
            wireValue = "gateway_url_is_placeholder",
            displayName = "Gateway URL 为占位地址",
            description = "config.properties 中的默认 URL（ws://100.x.x.x:9000）是占位符。必须在 App 设置中替换为真实 V2 地址才能建立连接。",
            blocksStep = OperabilityPathStep.CONFIGURE_V2_SERVER,
            isSelfHealable = false
        ),

        /** `crossDeviceEnabled` 为 `false`；WebSocket 连接不会被建立。 */
        CROSS_DEVICE_DISABLED(
            wireValue = "cross_device_disabled",
            displayName = "跨设备模式未启用",
            description = "AppSettings.crossDeviceEnabled=false；RuntimeController 不会建立 WS 连接。必须在设置中启用跨设备模式。",
            blocksStep = OperabilityPathStep.ESTABLISH_WS_CONNECTION,
            isSelfHealable = false
        ),

        /** WebSocket 连接已达最大重连次数后进入硬失败状态（V2 宿主不可达）。 */
        WS_HOST_UNREACHABLE(
            wireValue = "ws_host_unreachable",
            displayName = "V2 宿主不可达",
            description = "GalaxyWebSocketClient 连续多次重连失败；通常是网络隔离或 V2 服务未运行。检查 V2 服务状态与网络（Tailscale/LAN）。",
            blocksStep = OperabilityPathStep.ESTABLISH_WS_CONNECTION,
            isSelfHealable = false
        ),

        /** 执行模式不为 CROSS_DEVICE_ACTIVE；V2 不会派发委托任务。 */
        MODE_NOT_CROSS_DEVICE_ACTIVE(
            wireValue = "mode_not_cross_device_active",
            displayName = "执行模式非 CROSS_DEVICE_ACTIVE",
            description = "LocalExecutionModeGate 处于 LOCAL_ONLY/INACTIVE/TRANSITIONING 状态；V2 必须等待模式稳定为 CROSS_DEVICE_ACTIVE 后再派发任务。",
            blocksStep = OperabilityPathStep.RECEIVE_DELEGATED_TASK,
            isSelfHealable = true
        ),

        /** `goalExecutionEnabled` 为 `false`；AutonomousExecutionPipeline 拒绝 goal_execution 载体。 */
        GOAL_EXECUTION_DISABLED(
            wireValue = "goal_execution_disabled",
            displayName = "目标执行未启用",
            description = "AppSettings.goalExecutionEnabled=false；AutonomousExecutionPipeline 对所有 goal_execution 消息返回 STATUS_DISABLED。需在设置中启用。",
            blocksStep = OperabilityPathStep.RECEIVE_DELEGATED_TASK,
            isSelfHealable = false
        );

        companion object {
            /** 从 wire value 解析阻断条件，不存在时返回 null。 */
            fun fromWireValue(value: String?): PathBlockCondition? =
                entries.firstOrNull { it.wireValue == value }

            /** 给定路径步骤的所有阻断条件。 */
            fun forStep(step: OperabilityPathStep): List<PathBlockCondition> =
                entries.filter { it.blocksStep == step }

            /** 所有 wire value 集合。 */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── 步骤三：能力降级种类枚举 ──────────────────────────────────────────────────

    /**
     * 仅**降级**设备能力、不硬性阻断主可用路径的条件。
     *
     * 系统在存在这些条件时仍可运行，但部分任务类型可能不可用或执行质量下降。
     * V2 应根据 [affectedCapability] 来决定是否派发需要该能力的任务。
     *
     * @property wireValue        稳定的 wire 字符串。
     * @property displayName      简短名称。
     * @property description      一句话说明降级的影响范围。
     * @property affectedCapability 受影响的能力类别描述。
     * @property isRecoverable    `true` 表示该降级可在运行时自动恢复（如模型加载完成）。
     */
    enum class CapabilityDegradationKind(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val affectedCapability: String,
        val isRecoverable: Boolean
    ) {

        /** 本地推理模型未加载；依赖本地 LLM 的任务无法执行，但纯 UI 自动化任务仍可执行。 */
        LOCAL_LLM_NOT_LOADED(
            wireValue = "local_llm_not_loaded",
            displayName = "本地 LLM 未加载",
            description = "NativeInferenceLoader 或 LocalInferenceRuntimeManager 的模型未就绪；需要本地推理的任务降级为失败，但 Accessibility 路径任务可继续。",
            affectedCapability = "local_model_inference",
            isRecoverable = true
        ),

        /** Accessibility Service 权限未授予；基于 UI 可访问性的动作执行降级。 */
        ACCESSIBILITY_PERMISSION_NOT_GRANTED(
            wireValue = "accessibility_permission_not_granted",
            displayName = "无障碍权限未授予",
            description = "AccessibilityService 未启用；AccessibilityActionExecutor 无法执行 UI 操作。本地推理路径仍可工作（若模型已加载）。",
            affectedCapability = "accessibility_action_execution",
            isRecoverable = false
        ),

        /** SYSTEM_ALERT_WINDOW 权限未授予；悬浮窗 / EnhancedFloatingService 无法展示。 */
        OVERLAY_PERMISSION_NOT_GRANTED(
            wireValue = "overlay_permission_not_granted",
            displayName = "悬浮窗权限未授予",
            description = "SYSTEM_ALERT_WINDOW 权限未授予；EnhancedFloatingService 无法创建覆盖层。不影响后台任务执行路径。",
            affectedCapability = "floating_overlay_display",
            isRecoverable = false
        ),

        /**
         * 外部推理服务器（VlmPlanner 的本地 HTTP 端点）未运行。
         * 使用远程 V2 节点（Node_113_AndroidVLM）作为推理路径时不受影响。
         */
        EXTERNAL_INFERENCE_SERVER_UNAVAILABLE(
            wireValue = "external_inference_server_unavailable",
            displayName = "外部推理服务器不可用",
            description = "VlmPlanner 的本地 HTTP 推理服务器（127.0.0.1:8080）未运行。若已配置远程 V2 VLM 节点，则降级不影响跨设备执行路径。",
            affectedCapability = "local_vlm_planning",
            isRecoverable = true
        ),

        /** 连接处于降级状态（CROSS_DEVICE_DEGRADED）；能力受限但连接仍然有效。 */
        CONNECTION_DEGRADED(
            wireValue = "connection_degraded",
            displayName = "连接降级",
            description = "LocalExecutionModeGate 处于 CROSS_DEVICE_DEGRADED 状态；V2 可继续下发不依赖降级能力的任务类型。",
            affectedCapability = "full_cross_device_capability",
            isRecoverable = true
        );

        companion object {
            /** 从 wire value 解析降级种类，不存在时返回 null。 */
            fun fromWireValue(value: String?): CapabilityDegradationKind? =
                entries.firstOrNull { it.wireValue == value }

            /** 所有 wire value 集合。 */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── 步骤四：本地模式就绪门 ────────────────────────────────────────────────────

    /**
     * 本地模式（local mode）就绪前提评估门。
     *
     * 本地模式允许 Android 在不连接 V2 的情况下独立执行推理与 UI 自动化任务。
     * 其前提条件比跨设备路径更苛刻，因为所有依赖外部服务的能力必须在本地完全就绪。
     *
     * @see LocalExecutionModeGate
     * @see AndroidUnifiedTruthUplinkContract.LocalCapabilityState
     */
    object LocalModeReadinessGate {

        /**
         * 本地模式就绪前提维度。
         *
         * @property wireKey      稳定的 wire 键名。
         * @property displayName  简短名称。
         * @property description  一句话说明该维度的内容。
         * @property isHardGate   `true` 表示该维度不满足时本地模式**无法使用**（硬前提）；
         *                        `false` 表示仅降级部分能力（软前提）。
         */
        enum class ReadinessDimension(
            val wireKey: String,
            val displayName: String,
            val description: String,
            val isHardGate: Boolean
        ) {

            /** 本地 LLM 模型已加载且推理通路就绪（硬前提：本地执行须有推理能力）。 */
            LOCAL_LLM_READY(
                wireKey = "local_llm_ready",
                displayName = "本地 LLM 就绪",
                description = "NativeInferenceLoader 或 LocalInferenceRuntimeManager 已成功加载模型并预热。",
                isHardGate = true
            ),

            /** Accessibility Service 已授权（硬前提：UI 自动化执行必须）。 */
            ACCESSIBILITY_SERVICE_ENABLED(
                wireKey = "accessibility_service_enabled",
                displayName = "无障碍服务已启用",
                description = "AccessibilityService 已在系统设置中授权。缺少此权限则 UI 操作执行路径不可用。",
                isHardGate = true
            ),

            /** SYSTEM_ALERT_WINDOW 已授权（软前提：悬浮窗不影响纯后台执行路径）。 */
            OVERLAY_PERMISSION_GRANTED(
                wireKey = "overlay_permission_granted",
                displayName = "悬浮窗权限已授予",
                description = "SYSTEM_ALERT_WINDOW 权限已授予；EnhancedFloatingService 可正常展示状态覆盖层。",
                isHardGate = false
            ),

            /** `crossDeviceEnabled=false` 已确认（本地模式不应启用跨设备路径）。 */
            CROSS_DEVICE_DISABLED_CONFIRMED(
                wireKey = "cross_device_disabled_confirmed",
                displayName = "跨设备已禁用（本地隔离确认）",
                description = "AppSettings.crossDeviceEnabled=false；RuntimeController 不会尝试建立 WS 连接，确保设备在纯本地模式下运行。",
                isHardGate = false
            );

            companion object {
                /** 所有硬前提维度。 */
                val hardGates: List<ReadinessDimension> = entries.filter { it.isHardGate }

                /** 所有 wire key 集合。 */
                val ALL_WIRE_KEYS: Set<String> = entries.map { it.wireKey }.toSet()
            }
        }

        /**
         * 本地模式就绪快照。
         *
         * 记录各维度的当前评估结果，以及整体就绪结论。
         *
         * @property dimensionResults   各维度名称到 `true/false/null`（null = 无信号）的映射。
         * @property overallReady       `true` 仅当所有 [ReadinessDimension.isHardGate] 维度均为 `true`。
         * @property failingHardGates   不满足的硬前提维度列表（供诊断与用户指引使用）。
         * @property schemaVersion      合约 schema 版本。
         */
        data class LocalModeReadinessSnapshot(
            val dimensionResults: Map<String, Boolean?>,
            val overallReady: Boolean,
            val failingHardGates: List<String>,
            val schemaVersion: String = SCHEMA_VERSION
        ) {
            /**
             * 将快照序列化为 wire map，可嵌入到 DeviceStateSnapshotPayload 或诊断载体中。
             */
            fun toWireMap(): Map<String, Any?> = buildMap {
                put("schema_version", schemaVersion)
                put("overall_ready", overallReady)
                put("failing_hard_gates", failingHardGates)
                dimensionResults.forEach { (k, v) -> put(k, v) }
            }
        }

        /**
         * 根据当前运行时信号评估本地模式就绪状态。
         *
         * @param localLlmReady            `true` 表示本地 LLM 已成功加载并可推理。
         * @param accessibilityServiceEnabled `true` 表示 Accessibility Service 已授权。
         * @param overlayPermissionGranted `true` 表示 SYSTEM_ALERT_WINDOW 已授权。
         * @param crossDeviceEnabled       应为 `false` 以满足本地隔离确认维度。
         * @return [LocalModeReadinessSnapshot]，记录各维度结果与整体就绪结论。
         */
        fun assess(
            localLlmReady: Boolean?,
            accessibilityServiceEnabled: Boolean?,
            overlayPermissionGranted: Boolean?,
            crossDeviceEnabled: Boolean?
        ): LocalModeReadinessSnapshot {
            val results: Map<String, Boolean?> = mapOf(
                ReadinessDimension.LOCAL_LLM_READY.wireKey to localLlmReady,
                ReadinessDimension.ACCESSIBILITY_SERVICE_ENABLED.wireKey to accessibilityServiceEnabled,
                ReadinessDimension.OVERLAY_PERMISSION_GRANTED.wireKey to overlayPermissionGranted,
                ReadinessDimension.CROSS_DEVICE_DISABLED_CONFIRMED.wireKey to
                    crossDeviceEnabled?.let { !it }
            )

            val failingHardGates = ReadinessDimension.hardGates
                .filter { dim -> results[dim.wireKey] != true }
                .map { it.wireKey }

            return LocalModeReadinessSnapshot(
                dimensionResults = results,
                overallReady = failingHardGates.isEmpty(),
                failingHardGates = failingHardGates
            )
        }
    }

    // ── 步骤五：委托执行阻断种类 ──────────────────────────────────────────────────

    /**
     * 会**阻断**委托任务执行的条件分类。
     *
     * 与 [CapabilityDegradationKind] 不同，这些条件会导致
     * [com.ufo.galaxy.agent.AutonomousExecutionPipeline] 立即返回 `STATUS_DISABLED`，
     * 而非降级执行。
     *
     * V2 应在观察到对应信号后停止向该设备派发新任务，直到条件解除。
     *
     * @property wireValue    稳定的 wire 字符串。
     * @property displayName  简短名称。
     * @property description  一句话说明阻断原因及解除方法。
     * @property blocksTaskTypes 受影响的任务类型描述列表（`"*"` 表示全部阻断）。
     */
    enum class DelegatedExecutionBlockKind(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val blocksTaskTypes: List<String>
    ) {

        /** `crossDeviceEnabled=false`；全部委托任务类型均被拒绝。 */
        CROSS_DEVICE_RUNTIME_INACTIVE(
            wireValue = "cross_device_runtime_inactive",
            displayName = "跨设备运行时未激活",
            description = "AppSettings.crossDeviceEnabled=false；AutonomousExecutionPipeline 对 goal_execution 与 parallel_subtask 均返回 STATUS_DISABLED。",
            blocksTaskTypes = listOf("goal_execution", "parallel_subtask", "task_assign")
        ),

        /** `goalExecutionEnabled=false`；goal_execution 类消息被拒绝。 */
        GOAL_EXECUTION_FEATURE_DISABLED(
            wireValue = "goal_execution_feature_disabled",
            displayName = "目标执行特性禁用",
            description = "AppSettings.goalExecutionEnabled=false；goal_execution 消息返回 STATUS_DISABLED。",
            blocksTaskTypes = listOf("goal_execution")
        ),

        /** `parallelExecutionEnabled=false`；parallel_subtask 消息被拒绝。 */
        PARALLEL_EXECUTION_FEATURE_DISABLED(
            wireValue = "parallel_execution_feature_disabled",
            displayName = "并行执行特性禁用",
            description = "AppSettings.parallelExecutionEnabled=false；parallel_subtask 消息返回 STATUS_DISABLED。",
            blocksTaskTypes = listOf("parallel_subtask")
        ),

        /** 载体策略路由结果为 `rejected`；任务在策略层被拒绝，不进入执行。 */
        POLICY_ROUTING_REJECTED(
            wireValue = "policy_routing_rejected",
            displayName = "策略路由拒绝",
            description = "GoalExecutionPayload.policy_routing_outcome == 'rejected'；AutonomousExecutionPipeline 返回 STATUS_DISABLED with REASON_POLICY_REJECTED。",
            blocksTaskTypes = listOf("goal_execution", "parallel_subtask")
        ),

        /** 执行模式为 TRANSITIONING；此期间 V2 不应派发新任务。 */
        MODE_TRANSITIONING_HOLD(
            wireValue = "mode_transitioning_hold",
            displayName = "模式切换持有",
            description = "LocalExecutionModeGate 处于 TRANSITIONING 状态；V2 必须等待模式稳定后再派发任务。",
            blocksTaskTypes = listOf("*")
        );

        companion object {
            /** 从 wire value 解析，不存在时返回 null。 */
            fun fromWireValue(value: String?): DelegatedExecutionBlockKind? =
                entries.firstOrNull { it.wireValue == value }

            /** 所有 wire value 集合。 */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── 步骤六：失败诊断种类 ──────────────────────────────────────────────────────

    /**
     * Android 侧关键失败点的结构化诊断分类。
     *
     * 本枚举为每类失败提供：
     *  - 稳定的 wire value（V2 可以直接匹配，无需字符串解析）
     *  - 影响的路径步骤
     *  - 推荐的操作建议（actionable hint）
     *
     * 与 [AndroidLocalDiagnosticReasonContract.DiagnosticReason] 的关系：
     *  - [AndroidLocalDiagnosticReasonContract] 覆盖**运行时层**（WS 断开、解析错误、执行失败等）。
     *  - 本枚举覆盖**操作路径层**（配置、权限、连接、模式、本地模式前提等）。
     *  - 两者结合可实现从用户操作失败到运行时失败的完整诊断覆盖。
     *
     * @property wireValue      稳定的 wire 字符串。
     * @property displayName    简短名称。
     * @property description    一句话说明失败的根本原因。
     * @property affectedStep   受影响的路径步骤。
     * @property actionableHint 推荐的修复操作建议（机器可读或用于用户引导）。
     */
    enum class FailureDiagnosticKind(
        val wireValue: String,
        val displayName: String,
        val description: String,
        val affectedStep: OperabilityPathStep,
        val actionableHint: String
    ) {

        /** Gradle 构建失败（JDK/SDK 缺失或依赖问题）。 */
        BUILD_FAILED_MISSING_PREREQUISITES(
            wireValue = "build_failed_missing_prerequisites",
            displayName = "构建失败：缺少前提",
            description = "JDK 17+ 未安装、ANDROID_HOME 未设置或 Gradle 配置错误导致 APK 构建失败。",
            affectedStep = OperabilityPathStep.BUILD_APK,
            actionableHint = "运行 build_apk.sh 获取详细错误提示；确认 java -version >= 17 且 ANDROID_HOME 已指向有效 SDK 目录。"
        ),

        /** Gateway URL 为占位符或格式错误，WebSocket 连接无法建立。 */
        CONNECT_FAILED_INVALID_GATEWAY_URL(
            wireValue = "connect_failed_invalid_gateway_url",
            displayName = "连接失败：无效 Gateway URL",
            description = "config.properties 或 App 设置中的 gateway URL 为占位符（100.x.x.x）或格式错误，连接无法建立。",
            affectedStep = OperabilityPathStep.ESTABLISH_WS_CONNECTION,
            actionableHint = "在 App ⚙ 设置中将 Gateway URL 更新为 ws://<V2宿主IP>:9000；确认 V2 服务正在运行。"
        ),

        /** 跨设备模式未启用，WS 连接从不建立。 */
        CONNECT_FAILED_CROSS_DEVICE_DISABLED(
            wireValue = "connect_failed_cross_device_disabled",
            displayName = "连接失败：跨设备模式未启用",
            description = "crossDeviceEnabled=false；RuntimeController 不会尝试建立 WS 连接。",
            affectedStep = OperabilityPathStep.ESTABLISH_WS_CONNECTION,
            actionableHint = "在 App ⚙ 设置中启用「跨设备模式」（crossDeviceEnabled=true）。"
        ),

        /** V2 宿主不可达（防火墙、Tailscale 未连接、V2 服务未启动等）。 */
        CONNECT_FAILED_HOST_UNREACHABLE(
            wireValue = "connect_failed_host_unreachable",
            displayName = "连接失败：V2 宿主不可达",
            description = "GalaxyWebSocketClient 重连多次均失败；通常是 V2 服务未启动或网络隔离。",
            affectedStep = OperabilityPathStep.ESTABLISH_WS_CONNECTION,
            actionableHint = "确认 V2 服务已运行（python main.py --host <ip> --port 9000）；检查网络路径（Tailscale/LAN）。"
        ),

        /** 设备真相快照发送失败（WS 已断开，消息未能发出）。 */
        DEVICE_TRUTH_UPLINK_FAILED(
            wireValue = "device_truth_uplink_failed",
            displayName = "设备真相上报失败",
            description = "sendDeviceStateSnapshot() 在 WS 断开时调用，消息未能发出。",
            affectedStep = OperabilityPathStep.REPORT_DEVICE_TRUTH,
            actionableHint = "等待 WS 重连成功（GalaxyWebSocketClient 会自动重连）；重连后快照将自动重发。"
        ),

        /** 委托任务被拒绝（pipeline 禁用或策略拒绝）。 */
        DELEGATED_TASK_REJECTED_PIPELINE_DISABLED(
            wireValue = "delegated_task_rejected_pipeline_disabled",
            displayName = "委托任务被拒绝：pipeline 禁用",
            description = "AutonomousExecutionPipeline 因 crossDeviceEnabled=false 或 goalExecutionEnabled=false 或策略路由拒绝而返回 STATUS_DISABLED。",
            affectedStep = OperabilityPathStep.RECEIVE_DELEGATED_TASK,
            actionableHint = "确认 crossDeviceEnabled=true 且 goalExecutionEnabled=true；检查 GoalExecutionPayload.policy_routing_outcome 是否为 rejected。"
        ),

        /** 结果上行失败（WS 断开；结果已缓冲至 OfflineTaskQueue）。 */
        RESULT_UPLINK_QUEUED_PENDING_RECONNECT(
            wireValue = "result_uplink_queued_pending_reconnect",
            displayName = "结果上行已缓冲（等待重连）",
            description = "sendJson() 调用时 WS 已断开；goal_execution_result 已写入 OfflineTaskQueue，待重连后 FIFO 重放。",
            affectedStep = OperabilityPathStep.UPLINK_RESULT,
            actionableHint = "等待 WS 自动重连；重连后队列自动排空。检查 OfflineTaskQueue 的队列深度以确认缓冲进度。"
        ),

        /** 本地模式无法就绪：本地 LLM 未加载。 */
        LOCAL_MODE_BLOCKED_LLM_NOT_LOADED(
            wireValue = "local_mode_blocked_llm_not_loaded",
            displayName = "本地模式阻断：LLM 未加载",
            description = "本地 LLM 模型未就绪；LocalModeReadinessGate.assess() 返回 overall_ready=false，failing_hard_gates 包含 local_llm_ready。",
            affectedStep = OperabilityPathStep.ASSESS_LOCAL_MODE_READINESS,
            actionableHint = "确认模型文件存在于预期路径；检查 LocalInferenceRuntimeManager 加载日志；若使用远程推理则将 inferenceMode 设为 REMOTE。"
        ),

        /** 本地模式无法就绪：Accessibility Service 未授权。 */
        LOCAL_MODE_BLOCKED_ACCESSIBILITY_NOT_GRANTED(
            wireValue = "local_mode_blocked_accessibility_not_granted",
            displayName = "本地模式阻断：无障碍服务未授权",
            description = "Accessibility Service 未启用；LocalModeReadinessGate.assess() 返回 overall_ready=false，failing_hard_gates 包含 accessibility_service_enabled。",
            affectedStep = OperabilityPathStep.ASSESS_LOCAL_MODE_READINESS,
            actionableHint = "前往「设置 > 无障碍 > UFO Galaxy」启用 Accessibility Service。"
        );

        companion object {
            /** 从 wire value 解析，不存在时返回 null。 */
            fun fromWireValue(value: String?): FailureDiagnosticKind? =
                entries.firstOrNull { it.wireValue == value }

            /** 给定路径步骤的所有诊断种类。 */
            fun forStep(step: OperabilityPathStep): List<FailureDiagnosticKind> =
                entries.filter { it.affectedStep == step }

            /** 所有 wire value 集合。 */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── 步骤七：可用性不变量声明 ──────────────────────────────────────────────────

    /**
     * Android 侧最小可用路径的形式化不变量。
     *
     * 每条不变量格式：`INV-OPR-<N>: <断言>`。
     *
     * 这些不变量在运行时和测试中均应成立，且不应被任何单独的 PR 打破。
     * 如果代码变更会违反某条不变量，该变更必须同时更新本合约。
     */
    val OPERABILITY_INVARIANTS: List<String> = listOf(

        // 路径结构不变量

        "INV-OPR-01: OperabilityPathStep 恰好包含 10 个步骤，stepIndex 从 1 到 10 不含空缺。",

        "INV-OPR-02: orderedPath 中步骤按 stepIndex 升序唯一排列，不允许跳号或重复。",

        // 阻断 vs 降级分离不变量

        "INV-OPR-03: PathBlockCondition 中的每个条件必须明确对应一个 OperabilityPathStep；无条件可同时阻断两个步骤。",

        "INV-OPR-04: CapabilityDegradationKind 中的每个条件不允许出现在 PathBlockCondition 中；两者语义互斥。",

        // 本地模式门不变量

        "INV-OPR-05: LocalModeReadinessGate.assess() 返回的 overallReady=true 当且仅当 failingHardGates 为空列表。",

        "INV-OPR-06: LocalModeReadinessGate.ReadinessDimension.hardGates 中至少包含 LOCAL_LLM_READY 和 ACCESSIBILITY_SERVICE_ENABLED 两个维度。",

        // 委托执行不变量

        "INV-OPR-07: DelegatedExecutionBlockKind.CROSS_DEVICE_RUNTIME_INACTIVE 的 blocksTaskTypes 必须包含所有三种委托任务类型（goal_execution/parallel_subtask/task_assign）。",

        "INV-OPR-08: DelegatedExecutionBlockKind.MODE_TRANSITIONING_HOLD 的 blocksTaskTypes 必须包含通配符 '*'，表示全部阻断。",

        // 诊断不变量

        "INV-OPR-09: FailureDiagnosticKind 中每个条目的 actionableHint 必须非空；不允许空字符串或仅包含空白字符。",

        "INV-OPR-10: FailureDiagnosticKind 中每个条目的 wireValue 必须全局唯一，不允许与 PathBlockCondition 或 CapabilityDegradationKind 的 wireValue 重复。",

        // Schema 不变量

        "INV-OPR-11: SCHEMA_VERSION 当前为 \"1\"；任何破坏性 wire 字段变更必须递增该版本号。",

        // StabilizationBaseline 注册不变量

        "INV-OPR-12: StabilizationBaseline 必须包含 surfaceId=\"android-minimal-operability-contract\" 且 stability=CANONICAL_STABLE 的条目。"
    )

    // ── 步骤八：wire map 序列化辅助 ───────────────────────────────────────────────

    /**
     * 将本合约的元数据（schema 版本、不变量数量、路径步骤数）序列化为 wire map，
     * 可嵌入诊断载体或设备状态快照中，供 V2 消费方验证合约一致性。
     */
    fun toContractMetaWireMap(): Map<String, Any> = mapOf(
        "operability_contract_schema_version" to SCHEMA_VERSION,
        "operability_path_step_count" to OperabilityPathStep.entries.size,
        "operability_invariant_count" to OPERABILITY_INVARIANTS.size,
        "introduced_pr" to INTRODUCED_PR
    )
}
