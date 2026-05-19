package com.ufo.galaxy.runtime

/**
 * PR-02v2 (Android) — Android 侧跨设备 dispatch 边界收束合约。
 *
 * ## 解决的问题
 *
 * 在此合约引入之前，Android 侧上行消息中缺少对当前 **cross-device dispatch 边界类型**
 * 的机器可读声明。V2 已通过 PR-02v2 在 `DeviceRouter`、`CapabilityOrchestrator`、
 * `CrossDeviceCoordinator` 等核心路由层引入了 `dispatch_path`、`route_mode`、
 * `fallback_reason` 等 dispatch contract 语义字段，用于区分：
 *
 * - **canonical dispatch** — 经 V2 canonical 路径派发的主链执行
 * - **controlled canonical fallback** — V2 识别的受控回退路径
 * - **compat fallback** — 通过 CapabilityOrchestrator 兼容层进行的回退
 * - **legacy bypass** — 绕过 canonical 路径的遗留兼容通道
 *
 * 但在 Android 侧，对应的 dispatch 边界消费分类一直以来是隐式的：Android 上行的
 * `device_execution_event` 和 `device_state_snapshot` 并不携带"此次执行消费的是哪类
 * dispatch 边界"的声明，导致 V2 无法直接通过 Android 上行消息与自己的 dispatch_path
 * 字段建立跨仓关联。
 *
 * ## 合约目标
 *
 * [AndroidCrossDeviceDispatchBoundaryContract] 在现有执行路径和运行时信号之上引入：
 *
 * - [DispatchBoundaryClass]：5 值枚举，声明 Android 当前消费的 dispatch 边界类型，
 *   与 V2 PR-02v2 的 `dispatch_path` 词汇直接对应，使 V2 可在不进行字段组合推断的情况下
 *   建立跨仓 dispatch 叙事一致性。
 * - [DispatchPathConsumptionKind]：3 值枚举，声明 Android 在当前 dispatch 边界中
 *   作为执行链中的哪种消费方角色（入站主链消费、回退消费、兼容通道消费）。
 * - [DispatchBoundaryDerivationInput]：汇聚来自现有合约的 8 个信号，无需新增运行时探针。
 * - [derive]：按 5 级优先级推导 [DispatchBoundarySnapshot]，保证单一、权威、无歧义输出。
 * - [DispatchBoundarySnapshot.toWireMap]：生成可嵌入上行消息的标准 wire map。
 * - [DISPATCH_BOUNDARY_INVARIANTS]：7 条形式化不变量，声明 V2 可依赖的 Android 侧保证。
 *
 * ## 与 V2 PR-02v2 的对应关系
 *
 * | Android [DispatchBoundaryClass]         | V2 `dispatch_path` (PR-02v2) |
 * |-----------------------------------------|------------------------------|
 * | [CANONICAL_CROSS_DEVICE]                | `canonical_dispatch`         |
 * | [CONTROLLED_CANONICAL_FALLBACK]         | `canonical_fallback`         |
 * | [COMPAT_FALLBACK]                       | `compat_fallback`            |
 * | [LEGACY_BYPASS]                         | `legacy_bypass`              |
 * | [NOT_CROSS_DEVICE]                      | N/A（Android 本地模式）       |
 *
 * ## 与现有合约的关系
 *
 * | 现有合约 | 本合约的补充关系 |
 * |---------|----------------|
 * | [CanonicalDispatchChain] | 本合约消费 CanonicalDispatchChain.DispatchPathMode 定义的路径，并将其映射为 V2 对齐的边界类型 |
 * | [AndroidRuntimeObservabilityAuditContract] | 本合约使用 ExecutionPathTag 作为推导输入之一 |
 * | [AndroidParticipationSemanticNormalizationContract] | 本合约使用参与模式分类驱动边界推导优先级 |
 * | [AndroidMainchainEntryContract] | COMPAT_FALLBACK 路径对应 agent_runtime_bridge_compat_fallback 入口；LEGACY_BYPASS 对应 LEGACY_ENTRY |
 * | [AndroidBoundaryReliabilityContract] | 本合约关注 dispatch 边界类型，后者关注工程边界可靠性；两者在 GalaxyConnectionService 中并行填充 |
 *
 * ## V2 消费合约
 *
 * V2 的以下消费路径 MUST 利用本合约字段建立 dispatch 叙事一致性：
 * - `core/device_router.py`：读取 `dispatch_boundary_class`，与自身 `dispatch_path` 字段对齐
 * - `core/cross_device_coordinator.py`：读取 `dispatch_path_consumption_kind`，识别 Android
 *   在 dispatch 链中的角色（主链执行节点 vs 兼容降级节点）
 * - `core/android_device_state_store.py`：读取 `dispatch_boundary_class`，用于 dispatch 来源溯源
 * - `metrics/dispatch_boundary_metrics.py`：读取 `dispatch_boundary_class`，
 *   统计 canonical / fallback / compat 路径的执行分布
 *
 * @see CanonicalDispatchChain
 * @see AndroidRuntimeObservabilityAuditContract
 * @see AndroidMainchainEntryContract
 * @see AndroidBoundaryReliabilityContract
 */
object AndroidCrossDeviceDispatchBoundaryContract {

    /** Wire-map schema 版本。 */
    const val SCHEMA_VERSION = "1"

    // ── Wire 字段 key 常量 ─────────────────────────────────────────────────────

    /** Wire 字段：Android 当前消费的 dispatch 边界类型分类。 */
    const val KEY_DISPATCH_BOUNDARY_CLASS = "dispatch_boundary_class"

    /** Wire 字段：Android 在当前 dispatch 边界中的消费角色。 */
    const val KEY_DISPATCH_PATH_CONSUMPTION_KIND = "dispatch_path_consumption_kind"

    /** Wire 字段：合约 schema 版本。 */
    const val KEY_DISPATCH_BOUNDARY_SCHEMA_VERSION = "dispatch_boundary_schema_version"

    // ── Dispatch 边界类型分类 ──────────────────────────────────────────────────

    /**
     * Android 当前消费的 cross-device dispatch 边界类型。
     *
     * 与 V2 PR-02v2 的 `dispatch_path` 词汇直接对应，使 V2 可跨仓建立 dispatch 叙事一致性。
     *
     * | [DispatchBoundaryClass]             | wireValue                       | V2 dispatch_path 对应     |
     * |-------------------------------------|---------------------------------|--------------------------|
     * | [CANONICAL_CROSS_DEVICE]            | `canonical_cross_device`        | `canonical_dispatch`     |
     * | [CONTROLLED_CANONICAL_FALLBACK]     | `controlled_canonical_fallback` | `canonical_fallback`     |
     * | [COMPAT_FALLBACK]                   | `compat_fallback`               | `compat_fallback`        |
     * | [LEGACY_BYPASS]                     | `legacy_bypass`                 | `legacy_bypass`          |
     * | [NOT_CROSS_DEVICE]                  | `not_cross_device`              | N/A                      |
     *
     * @property wireValue  稳定小写 wire 字符串；出现在 `dispatch_boundary_class` 字段中。
     * @property isCanonicalPath  此边界类型是否属于 canonical dispatch 主链。
     * @property isFallbackPath  此边界类型是否属于 fallback 路径（受控或兼容）。
     * @property v2DispatchPath  对应的 V2 `dispatch_path` 词汇值；仅作参考，非 wire 字段。
     */
    enum class DispatchBoundaryClass(
        val wireValue: String,
        val isCanonicalPath: Boolean,
        val isFallbackPath: Boolean,
        val v2DispatchPath: String?
    ) {
        /**
         * 主链 canonical cross-device dispatch 路径。
         *
         * Android 正在消费 V2 canonical dispatch contract 的主链路径：
         * - 入站任务来自 V2 canonical dispatch（`task_assign` / `goal_execution` / `takeover_request`）
         * - 通过 `GalaxyConnectionService` → `EdgeExecutor` / `AutonomousExecutionPipeline` / 
         *   `DelegatedTakeoverExecutor` 执行
         * - 结果通过标准 result uplink 返回
         *
         * 对应 V2 `dispatch_path = "canonical_dispatch"`。
         */
        CANONICAL_CROSS_DEVICE(
            wireValue = "canonical_cross_device",
            isCanonicalPath = true,
            isFallbackPath = false,
            v2DispatchPath = "canonical_dispatch"
        ),

        /**
         * 受控 canonical fallback 路径。
         *
         * Android 在 canonical 路径尝试失败后执行的受控本地回退：
         * - `AgentRuntimeBridge` 耗尽全部重试次数（3 次，指数退避 1/2/4 s）后触发
         * - 显式记录日志，计入 `MetricsRecorder.handoffFallbacks`
         * - 结果标记 `ExecutionRouteTag.FALLBACK`
         * - **不是静默回退**：Android 知道这次是回退，并主动上报
         *
         * 此路径仍有工程价值：它是 V2 dispatch 主链失败时 Android 侧的"救命路径"，
         * 保障任务在主链不可达时不被完全丢弃。
         *
         * 对应 V2 `dispatch_path = "canonical_fallback"`。
         */
        CONTROLLED_CANONICAL_FALLBACK(
            wireValue = "controlled_canonical_fallback",
            isCanonicalPath = false,
            isFallbackPath = true,
            v2DispatchPath = "canonical_fallback"
        ),

        /**
         * 兼容回退路径（compat fallback）。
         *
         * Android 正在消费通过兼容层进行的 dispatch：
         * - 来自 `AgentRuntimeBridge.handoff()` 的 compat 入口
         *   （对应 `AndroidMainchainEntryContract.EntryRole.COMPAT_FALLBACK`）
         * - 或者入站消息为遗留 `task_execute` / `task_status_query` 类型，
         *   经 `GalaxyWebSocketClient` 自动映射至 `task_assign`
         *   （无分叉逻辑；canonical `task_assign` 路径处理所有映射后的消息）
         * - 结果经 canonical path 正常返回，但 dispatch 来源标记为 compat
         *
         * 此路径仍有工程价值：它确保使用旧协议的 V2 发送方不会因消息类型不匹配而被拒绝。
         *
         * 对应 V2 `dispatch_path = "compat_fallback"`。
         */
        COMPAT_FALLBACK(
            wireValue = "compat_fallback",
            isCanonicalPath = false,
            isFallbackPath = true,
            v2DispatchPath = "compat_fallback"
        ),

        /**
         * 遗留绕过路径（legacy bypass）。
         *
         * Android 正在消费绕过 canonical dispatch 链的遗留路径：
         * - 对应 `AndroidMainchainEntryContract.EntryRole.LEGACY_ENTRY` 标记的入口
         *   （如 `FloatingWindowService.onCreate`、`RuntimeController.registrationError`）
         * - 这类路径已经被标记为"不得扩展、等待退役"
         * - V2 SHOULD NOT 依赖此路径参与 canonical dispatch 主链
         *
         * **工程价值评估**：`FloatingWindowService` 的遗留通道已无路径扩展必要，
         * 但在现有老版本客户端中仍可能被触发。`registrationError` 消费方已迁移至 `setupError`。
         * 保留此分类是为了使 V2 可识别遗留绕过事件而非将其误分类为 canonical。
         *
         * 对应 V2 `dispatch_path = "legacy_bypass"`。
         */
        LEGACY_BYPASS(
            wireValue = "legacy_bypass",
            isCanonicalPath = false,
            isFallbackPath = false,
            v2DispatchPath = "legacy_bypass"
        ),

        /**
         * 非跨设备模式（Android 处于本地模式）。
         *
         * Android 当前未处于跨设备模式，不消费任何 cross-device dispatch 边界。
         * - 对应 `execution_mode_state = "local_only"` 或 `mode_state = "local_only"`
         * - 本地闭环执行路径（`LocalLoopExecutor`）无对应 V2 dispatch_path
         *
         * V2 MUST NOT 在此分类下向 Android 派发跨设备任务。
         */
        NOT_CROSS_DEVICE(
            wireValue = "not_cross_device",
            isCanonicalPath = false,
            isFallbackPath = false,
            v2DispatchPath = null
        );

        companion object {
            /**
             * 从 wire 值解析 [DispatchBoundaryClass]；未知值返回 [NOT_CROSS_DEVICE]（防御性默认）。
             */
            fun fromWireValue(wire: String): DispatchBoundaryClass =
                values().firstOrNull { it.wireValue == wire } ?: NOT_CROSS_DEVICE
        }
    }

    // ── Dispatch 路径消费角色 ──────────────────────────────────────────────────

    /**
     * Android 在当前 dispatch 边界中作为执行链消费方的角色。
     *
     * 与 [DispatchBoundaryClass] 互补：[DispatchBoundaryClass] 描述"消费的是哪条路径"，
     * [DispatchPathConsumptionKind] 描述"Android 在这条路径中扮演什么角色"。
     *
     * @property wireValue  稳定小写 wire 字符串。
     * @property isActiveConsumer  Android 是否是当前 dispatch 的活跃执行消费方。
     */
    enum class DispatchPathConsumptionKind(
        val wireValue: String,
        val isActiveConsumer: Boolean
    ) {
        /**
         * 主链入站执行消费。
         *
         * Android 正作为 V2 canonical / compat dispatch 链的入站执行节点，
         * 接受并执行来自 V2 的 dispatch 任务（`task_assign`、`goal_execution`、`takeover_request`）。
         * 这是 Android 作为强运行时节点的主要角色。
         */
        INBOUND_EXECUTION(
            wireValue = "inbound_execution",
            isActiveConsumer = true
        ),

        /**
         * 本地回退消费。
         *
         * Android 在 canonical dispatch 主链失败后执行本地回退。
         * dispatch 来源是 V2，但执行发生在 Android 本地，结果仍通过 result uplink 返回。
         * 此角色对应 [DispatchBoundaryClass.CONTROLLED_CANONICAL_FALLBACK]。
         */
        LOCAL_FALLBACK_EXECUTION(
            wireValue = "local_fallback_execution",
            isActiveConsumer = true
        ),

        /**
         * 无活跃 dispatch 消费。
         *
         * Android 当前未消费任何 cross-device dispatch 边界。
         * 对应 [DispatchBoundaryClass.NOT_CROSS_DEVICE] 或无活跃执行的等待状态。
         */
        NONE(
            wireValue = "none",
            isActiveConsumer = false
        );

        companion object {
            /**
             * 从 wire 值解析 [DispatchPathConsumptionKind]；未知值返回 [NONE]（防御性默认）。
             */
            fun fromWireValue(wire: String): DispatchPathConsumptionKind =
                values().firstOrNull { it.wireValue == wire } ?: NONE
        }
    }

    // ── 推导输入 ───────────────────────────────────────────────────────────────

    /**
     * [derive] 的输入，汇聚来自现有合约的运行时信号。
     *
     * 所有输入均来自现有 Android 运行时状态，无需新增探针。
     *
     * @param isCrossDeviceMode         当前是否处于跨设备模式
     *                                  （来自 `execution_mode_state != "local_only"` 或
     *                                  `mode_state == "cross_device"`）。
     * @param executionPathTag          当前执行路径标签
     *                                  （来自 [AndroidRuntimeObservabilityAuditContract.ExecutionPathTag]）。
     * @param isFallbackTierActive      当前是否存在活跃的 fallback tier
     *                                  （来自 `current_fallback_tier != null`）。
     * @param isAgentBridgeFallback     `AgentRuntimeBridge` 是否已触发 fallback
     *                                  （来自 `ExecutionRouteTag.FALLBACK` 或
     *                                  `phase == PHASE_FALLBACK_TRANSITION`）。
     * @param isLegacyCompatRemapped    入站消息是否为遗留 `task_execute` / `task_status_query`
     *                                  经 compat 映射至 `task_assign`
     *                                  （来自 `GalaxyWebSocketClient` 消息类型检测）。
     * @param isAgentBridgeCompatEntry  是否通过 `AgentRuntimeBridge` compat 入口分发
     *                                  （对应 `AndroidMainchainEntryContract.COMPAT_FALLBACK` 入口）。
     * @param isLegacyBypassEntry       是否通过遗留绕过路径执行
     *                                  （对应 `AndroidMainchainEntryContract.EntryRole.LEGACY_ENTRY`）。
     * @param hasDelegatedOrTakeoverExecution  是否为委托/接管路径的活跃执行
     *                                  （来自 `delegated_execution_active == true` 或
     *                                  `takeover_state == "active"`）。
     */
    data class DispatchBoundaryDerivationInput(
        val isCrossDeviceMode: Boolean,
        val executionPathTag: AndroidRuntimeObservabilityAuditContract.ExecutionPathTag,
        val isFallbackTierActive: Boolean,
        val isAgentBridgeFallback: Boolean,
        val isLegacyCompatRemapped: Boolean,
        val isAgentBridgeCompatEntry: Boolean,
        val isLegacyBypassEntry: Boolean,
        val hasDelegatedOrTakeoverExecution: Boolean
    )

    // ── 派生快照 ───────────────────────────────────────────────────────────────

    /**
     * [derive] 的输出，包含推导得出的 dispatch 边界分类与路径消费角色。
     *
     * @param dispatchBoundaryClass      推导得出的 [DispatchBoundaryClass]。
     * @param dispatchPathConsumptionKind 推导得出的 [DispatchPathConsumptionKind]。
     */
    data class DispatchBoundarySnapshot(
        val dispatchBoundaryClass: DispatchBoundaryClass,
        val dispatchPathConsumptionKind: DispatchPathConsumptionKind
    ) {
        /**
         * 生成可嵌入上行消息的 wire map。
         *
         * 包含 [KEY_DISPATCH_BOUNDARY_CLASS]、[KEY_DISPATCH_PATH_CONSUMPTION_KIND]
         * 和 [KEY_DISPATCH_BOUNDARY_SCHEMA_VERSION] 三个键值对。
         */
        fun toWireMap(): Map<String, Any> = mapOf(
            KEY_DISPATCH_BOUNDARY_CLASS to dispatchBoundaryClass.wireValue,
            KEY_DISPATCH_PATH_CONSUMPTION_KIND to dispatchPathConsumptionKind.wireValue,
            KEY_DISPATCH_BOUNDARY_SCHEMA_VERSION to SCHEMA_VERSION
        )
    }

    // ── 推导函数 ───────────────────────────────────────────────────────────────

    /**
     * 从 [DispatchBoundaryDerivationInput] 推导 [DispatchBoundarySnapshot]。
     *
     * 优先级规则（从高到低）：
     *
     * 1. **非跨设备模式** → [DispatchBoundaryClass.NOT_CROSS_DEVICE]
     * 2. **遗留绕过路径** → [DispatchBoundaryClass.LEGACY_BYPASS]
     * 3. **兼容回退路径**（compat 消息映射或 AgentBridge compat 入口）
     *    → [DispatchBoundaryClass.COMPAT_FALLBACK]
     * 4. **受控 canonical fallback**（AgentBridge 重试耗尽或 fallback tier 活跃）
     *    → [DispatchBoundaryClass.CONTROLLED_CANONICAL_FALLBACK]
     * 5. **canonical cross-device 路径**（跨设备 + 非上述特殊路径）
     *    → [DispatchBoundaryClass.CANONICAL_CROSS_DEVICE]
     *
     * [DispatchPathConsumptionKind] 基于 [DispatchBoundaryClass] 和执行活跃状态推导：
     * - [DispatchBoundaryClass.CONTROLLED_CANONICAL_FALLBACK] + 路径活跃
     *   → [DispatchPathConsumptionKind.LOCAL_FALLBACK_EXECUTION]
     * - 其他 cross-device 边界且处于活跃执行
     *   → [DispatchPathConsumptionKind.INBOUND_EXECUTION]
     * - 无活跃执行 / NOT_CROSS_DEVICE → [DispatchPathConsumptionKind.NONE]
     */
    fun derive(input: DispatchBoundaryDerivationInput): DispatchBoundarySnapshot {
        // ── 优先级 1：非跨设备模式 ──────────────────────────────────────────
        if (!input.isCrossDeviceMode) {
            return DispatchBoundarySnapshot(
                dispatchBoundaryClass = DispatchBoundaryClass.NOT_CROSS_DEVICE,
                dispatchPathConsumptionKind = DispatchPathConsumptionKind.NONE
            )
        }

        // ── 优先级 2：遗留绕过路径 ──────────────────────────────────────────
        if (input.isLegacyBypassEntry) {
            return DispatchBoundarySnapshot(
                dispatchBoundaryClass = DispatchBoundaryClass.LEGACY_BYPASS,
                dispatchPathConsumptionKind = DispatchPathConsumptionKind.NONE
            )
        }

        // ── 优先级 3：兼容回退路径 ──────────────────────────────────────────
        if (input.isLegacyCompatRemapped || input.isAgentBridgeCompatEntry) {
            val consumptionKind = if (isActiveExecution(input)) {
                DispatchPathConsumptionKind.INBOUND_EXECUTION
            } else {
                DispatchPathConsumptionKind.NONE
            }
            return DispatchBoundarySnapshot(
                dispatchBoundaryClass = DispatchBoundaryClass.COMPAT_FALLBACK,
                dispatchPathConsumptionKind = consumptionKind
            )
        }

        // ── 优先级 4：受控 canonical fallback ─────────────────────────────
        if (input.isAgentBridgeFallback ||
            (input.isFallbackTierActive &&
                input.executionPathTag == AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.DEGRADED_PATH)
        ) {
            val consumptionKind = if (isActiveExecution(input)) {
                DispatchPathConsumptionKind.LOCAL_FALLBACK_EXECUTION
            } else {
                DispatchPathConsumptionKind.NONE
            }
            return DispatchBoundarySnapshot(
                dispatchBoundaryClass = DispatchBoundaryClass.CONTROLLED_CANONICAL_FALLBACK,
                dispatchPathConsumptionKind = consumptionKind
            )
        }

        // ── 优先级 5：canonical cross-device 主链 ────────────────────────
        val consumptionKind = if (isActiveExecution(input)) {
            DispatchPathConsumptionKind.INBOUND_EXECUTION
        } else {
            DispatchPathConsumptionKind.NONE
        }
        return DispatchBoundarySnapshot(
            dispatchBoundaryClass = DispatchBoundaryClass.CANONICAL_CROSS_DEVICE,
            dispatchPathConsumptionKind = consumptionKind
        )
    }

    /** 判断是否存在活跃的跨设备执行（委托、接管或 cross_device/delegated/takeover 路径）。 */
    private fun isActiveExecution(input: DispatchBoundaryDerivationInput): Boolean =
        input.hasDelegatedOrTakeoverExecution ||
            input.executionPathTag == AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.CROSS_DEVICE_PATH ||
            input.executionPathTag == AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.DELEGATED_PATH ||
            input.executionPathTag == AndroidRuntimeObservabilityAuditContract.ExecutionPathTag.TAKEOVER_PATH

    // ── 形式化不变量 ──────────────────────────────────────────────────────────

    /**
     * Android 侧 dispatch 边界合约的形式化不变量。
     *
     * V2 可依赖这些不变量来消费 Android 上行的 dispatch 边界字段。
     *
     * @param id      不变量标识符（INV-DB-XX 格式）。
     * @param summary 不变量描述。
     */
    data class DispatchBoundaryInvariant(val id: String, val summary: String)

    val DISPATCH_BOUNDARY_INVARIANTS: List<DispatchBoundaryInvariant> = listOf(
        DispatchBoundaryInvariant(
            id = "INV-DB-01",
            summary = "dispatch_boundary_class 在 device_execution_event 和 device_state_snapshot " +
                "中永不缺失（Null 仅作为防御性默认值，GalaxyConnectionService 在发送层填充）。"
        ),
        DispatchBoundaryInvariant(
            id = "INV-DB-02",
            summary = "当 execution_mode_state = 'local_only' 或 mode_state = 'local_only' 时，" +
                "dispatch_boundary_class MUST 为 'not_cross_device'；" +
                "V2 在此分类下 MUST NOT 向 Android 派发跨设备任务。"
        ),
        DispatchBoundaryInvariant(
            id = "INV-DB-03",
            summary = "当 dispatch_boundary_class = 'canonical_cross_device' 时，" +
                "dispatch_path_consumption_kind MUST 为 'inbound_execution'（如存在活跃执行）" +
                "或 'none'（如等待派发）；不得为 'local_fallback_execution'。"
        ),
        DispatchBoundaryInvariant(
            id = "INV-DB-04",
            summary = "当 dispatch_boundary_class = 'controlled_canonical_fallback' 时，" +
                "dispatch_path_consumption_kind MUST 为 'local_fallback_execution'（如存在活跃执行）；" +
                "V2 MUST 将此分类关联至 V2 canonical_fallback 路径，而非 canonical_dispatch。"
        ),
        DispatchBoundaryInvariant(
            id = "INV-DB-05",
            summary = "'compat_fallback' 和 'legacy_bypass' 分类 MUST NOT 被视为 canonical dispatch 主链；" +
                "V2 MUST 对这两类来源的结果采用降级置信度。"
        ),
        DispatchBoundaryInvariant(
            id = "INV-DB-06",
            summary = "Android 不允许新增平行 dispatch 体系：本合约的 5 个分类覆盖全部合法路径。" +
                "任何不在此分类中的新 dispatch 路径 MUST 先更新本合约后方可部署。"
        ),
        DispatchBoundaryInvariant(
            id = "INV-DB-07",
            summary = "dispatch_boundary_schema_version MUST 始终为 SCHEMA_VERSION 常量值，" +
                "确保 V2 可检测版本漂移。"
        )
    )

    // ── V2 contract 对应词汇表 ───────────────────────────────────────────────

    /**
     * Android [DispatchBoundaryClass] 与 V2 `dispatch_path` 词汇的权威映射。
     *
     * V2 消费方 MUST 使用此映射建立跨仓 dispatch 叙事一致性，
     * 而非对 Android 上行的 `dispatch_boundary_class` 做独立的词汇推断。
     */
    val V2_DISPATCH_PATH_MAP: Map<DispatchBoundaryClass, String?> =
        DispatchBoundaryClass.values().associate { it to it.v2DispatchPath }
}
