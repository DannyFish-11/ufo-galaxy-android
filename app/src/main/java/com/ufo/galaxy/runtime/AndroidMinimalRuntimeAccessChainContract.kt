package com.ufo.galaxy.runtime

/**
 * PR-06v2 (Android) — Android 侧最小真实运行接入链固定合约。
 *
 * 本合约是 V2 仓库 PR-06v2 的 Android 侧联动 PR，对应 V2 侧对
 * `DesktopPresenceRuntime`、`OpenClawd`、`CommandRouter`、`DeviceRouter` 最小真实主链的固定。
 *
 * ## 解决的问题
 *
 * 在本合约引入之前，Android 仓库中存在以下层级模糊问题：
 *
 * 1. **层级语义不清晰**：facade / helper / compat / legacy 路径与 Android 最小真实运行接入链
 *    共存，没有机器可读的层级声明，导致后续贡献者难以判断"Android 到底从哪条链真正接入"。
 *
 * 2. **compat / legacy 路径冒充主链**：`AgentRuntimeBridge`、`FloatingWindowService` 等
 *    compat / legacy 入口没有被显式标注为非主链结构，存在被误认为主接入路径的风险。
 *
 * 3. **主链缺少机器可验证的 V2 对应关系声明**：Android 主链各阶段与 V2 侧
 *    `DesktopPresenceRuntime` / `OpenClawd` / `CommandRouter` / `DeviceRouter` 的
 *    对应关系没有被固定成可回归的合约，双仓叙事一致性依赖非正式约定。
 *
 * ## 合约目标
 *
 * [AndroidMinimalRuntimeAccessChainContract] 在现有 [AndroidMainchainEntryContract] 基础上引入：
 *
 * - [RuntimeAccessLayerClass]：5 值枚举，对 Android 运行时模块的层级进行机器可读分类。
 *   主链（MAIN_CHAIN）与 facade / compat / legacy / 诊断层明确区分，
 *   任何非 MAIN_CHAIN 模块不得在语义上充当主接入路径。
 * - [ChainModuleRole]：每个主链模块在链中的具体职责角色。
 * - [AccessChainEntry]：为每个 Android 运行时模块记录层级、角色、所有者类名、V2 对应关系，
 *   使双仓接入叙事有明确、稳定、机器可验证的 contract 依据。
 * - [minimalMainChainEntries]：最小真实运行接入链的有序模块列表（lifecycle → transport →
 *   inbound dispatch → execution → result uplink），对应 V2 主链顺序。
 * - [CHAIN_INVARIANTS]：9 条形式化不变量，声明 V2 可依赖的 Android 侧主链保证。
 *
 * ## Android 最小真实运行接入链
 *
 * 以下是 Android 侧最小真实运行接入链的有序阶段：
 *
 * | 阶段 | [ChainModuleRole]                     | Android 模块                           | V2 对应主链模块                           |
 * |------|---------------------------------------|----------------------------------------|-------------------------------------------|
 * |  1   | [ChainModuleRole.LIFECYCLE_TRIGGER]   | `GalaxyConnectionService.onStartCommand` | `DesktopPresenceRuntime` (lifecycle init)|
 * |  2   | [ChainModuleRole.LIFECYCLE_MANAGER]   | `RuntimeController`                    | `OpenClawd` (lifecycle/session mgmt)     |
 * |  3   | [ChainModuleRole.TRANSPORT_LAYER]     | `GalaxyWebSocketClient`                | transport / WS layer                     |
 * |  4   | [ChainModuleRole.INBOUND_DISPATCHER]  | `GalaxyConnectionService` (dispatch)   | `CommandRouter` / `DeviceRouter`         |
 * |  5   | [ChainModuleRole.EXECUTION_RUNTIME]   | `AutonomousExecutionPipeline`          | canonical execution handler              |
 * |  6   | [ChainModuleRole.LOCAL_EXECUTOR]      | `LocalLoopExecutor`                    | on-device local execution subsystem      |
 * |  7   | [ChainModuleRole.RESULT_UPLINK]       | `GalaxyConnectionService` (uplink)     | result ingress / truth chain             |
 *
 * ## 层级边界声明
 *
 * | [RuntimeAccessLayerClass]       | 含义                                                                  | 是否为主接入链 |
 * |---------------------------------|-----------------------------------------------------------------------|---------------|
 * | [MAIN_CHAIN]                    | Android 最小真实运行接入链的直接组成模块                               | ✅ 是          |
 * | [FACADE_HELPER]                 | 对主链提供辅助能力的 helper / facade，本身不是主接入路径               | ❌ 否          |
 * | [COMPAT_FALLBACK]               | 兼容层回退路径，仅在主链不可用时激活，不得被视为主接入路径             | ❌ 否          |
 * | [LEGACY_BRIDGE]                 | 历史遗留桥接层，保留以维护向后兼容，不得扩展为主接入路径               | ❌ 否          |
 * | [DIAGNOSTICS_PROJECTION]        | 可观测性 / 诊断 / UI summary 层，仅为消费面，不参与接入链控制          | ❌ 否          |
 *
 * ## 与 V2 PR-06v2 的对应关系
 *
 * | Android [RuntimeAccessLayerClass] | V2 层级角色                                        |
 * |-----------------------------------|----------------------------------------------------|
 * | [MAIN_CHAIN]                      | `DesktopPresenceRuntime` / `OpenClawd` / `CommandRouter` / `DeviceRouter` 主链 |
 * | [FACADE_HELPER]                   | coordinator / helper / spine（非主控中心）         |
 * | [COMPAT_FALLBACK]                 | compat fallback / capability orchestrator compat 层|
 * | [LEGACY_BRIDGE]                   | legacy bypass / legacy wire adapter                |
 * | [DIAGNOSTICS_PROJECTION]          | projection / board / operator surface              |
 *
 * ## 与现有合约的关系
 *
 * | 现有合约 | 本合约的补充关系 |
 * |---------|----------------|
 * | [AndroidMainchainEntryContract] | 本合约在 EntryRole 基础上增加 RuntimeAccessLayerClass，使层级分类可独立于入口角色消费 |
 * | [AndroidMinimalOperabilityContract] | 本合约固定主链模块边界；前者定义可操作性路径步骤，两者正交且互补 |
 * | [AndroidCrossDeviceDispatchBoundaryContract] | 本合约声明 dispatch 阶段在主链中的位置；前者关注 dispatch 边界类型 |
 * | [AndroidResultUplinkBoundaryContract] | 本合约声明 result uplink 阶段在主链中的位置；前者关注结果信号分类 |
 * | [AndroidGovernanceExecutionPolicyIngressContract] | 本合约声明主链 governance ingress 与非主链 governance 的边界 |
 * | [CanonicalDispatchChain] | 本合约主链声明与 CanonicalDispatchChain 路径描述一致；两者互为叙事基础 |
 *
 * ## V2 消费合约
 *
 * V2 的以下模块 MUST 将 `runtime_access_layer_class` 作为 Android 接入层级的权威来源：
 * - `core/command_router.py`：识别 MAIN_CHAIN 接入为 canonical dispatch 消费方
 * - `galaxy_gateway/device_router.py`：仅向 MAIN_CHAIN 模块派发执行任务
 * - `core/android_device_state_store.py`：区分来自主链 vs compat/legacy 路径的状态快照
 * - `metrics/android_chain_boundary_metrics.py`：统计主链 vs 非主链的执行分布
 *
 * @see AndroidMainchainEntryContract
 * @see AndroidMinimalOperabilityContract
 * @see AndroidCrossDeviceDispatchBoundaryContract
 * @see AndroidResultUplinkBoundaryContract
 * @see CanonicalDispatchChain
 */
object AndroidMinimalRuntimeAccessChainContract {

    /** Wire-map schema 版本。 */
    const val SCHEMA_VERSION = "1"

    // ── Wire 字段 key 常量 ─────────────────────────────────────────────────────

    /** Wire 字段：Android 模块所属的运行时接入层级分类。 */
    const val KEY_RUNTIME_ACCESS_LAYER_CLASS = "runtime_access_layer_class"

    /** Wire 字段：Android 模块在接入链中的角色。 */
    const val KEY_CHAIN_MODULE_ROLE = "chain_module_role"

    /** Wire 字段：合约 schema 版本。 */
    const val KEY_SCHEMA_VERSION = "runtime_access_chain_schema_version"

    // ── 层级分类枚举 ──────────────────────────────────────────────────────────

    /**
     * Android 运行时模块的层级分类。
     *
     * 只有 [MAIN_CHAIN] 层是 Android 最小真实运行接入链的直接组成部分。
     * 其他层级仅为辅助、兼容或诊断角色，不得在语义上充当主接入路径。
     *
     * @property wireValue 稳定的 wire 字符串，可嵌入上行消息。
     */
    enum class RuntimeAccessLayerClass(val wireValue: String) {

        /**
         * Android 最小真实运行接入链的直接组成模块。
         *
         * 包括：`GalaxyConnectionService`（lifecycle trigger / inbound dispatch / result uplink）、
         * `RuntimeController`（lifecycle management）、`GalaxyWebSocketClient`（transport）、
         * `AutonomousExecutionPipeline`（execution runtime）、`LocalLoopExecutor`（local execution）。
         *
         * V2 canonical 消费路径 MUST 将此层级视为 Android 的真实接入主体。
         */
        MAIN_CHAIN("main_chain"),

        /**
         * 对主链提供辅助能力的 helper / facade 模块。
         *
         * 这些模块增强或辅助主链，但本身不是接入路径上的必须节点。
         * V2 MUST NOT 将此层级视为 Android 的主接入路径。
         *
         * 示例：`DelegatedRuntimeReadinessEvaluator`、`DelegatedRuntimeAcceptanceEvaluator`。
         */
        FACADE_HELPER("facade_helper"),

        /**
         * 兼容层回退路径。
         *
         * 仅在主链特定条件不满足时激活的回退接入路径。
         * V2 MUST 将此层级标记为非标准降级路径，不得作为正常接入基准。
         *
         * 示例：`AgentRuntimeBridge`（compat fallback bridge）。
         */
        COMPAT_FALLBACK("compat_fallback"),

        /**
         * 历史遗留桥接层。
         *
         * 为向后兼容而保留的旧接入路径。
         * 不得被扩展为新的主接入路径，也不得被新的消费方依赖。
         *
         * 示例：`FloatingWindowService`（legacy UI bridge）、
         * `RuntimeController.registrationError`（legacy error bridge）。
         */
        LEGACY_BRIDGE("legacy_bridge"),

        /**
         * 可观测性 / 诊断 / UI summary / outward projection 层。
         *
         * 这些模块仅为运行时状态的消费面（读取侧），不参与接入链的控制流。
         * V2 MUST NOT 依赖此层级的输入作为接入准入判断依据。
         *
         * 示例：operator surface、board、diagnostics payload、`RuntimeClosureAudit`。
         */
        DIAGNOSTICS_PROJECTION("diagnostics_projection")
    }

    // ── 主链模块角色枚举 ──────────────────────────────────────────────────────

    /**
     * Android 最小真实运行接入链中每个主链模块的具体职责角色。
     *
     * 仅对 [RuntimeAccessLayerClass.MAIN_CHAIN] 模块有语义意义。
     * 其他层级模块使用 [NOT_APPLICABLE]。
     *
     * @property wireValue 稳定的 wire 字符串。
     */
    enum class ChainModuleRole(val wireValue: String) {

        /**
         * 生命周期触发入口。
         *
         * Android 运行时的首个接入点，由系统（onStartCommand / BootReceiver）触发，
         * 委托给 lifecycle manager 完成后续初始化。
         * 对应 V2 侧 `DesktopPresenceRuntime` 的 lifecycle init 角色。
         */
        LIFECYCLE_TRIGGER("lifecycle_trigger"),

        /**
         * 生命周期管理器。
         *
         * 管理 WebSocket 连接生命周期（connect / start / stop / reconnect）和
         * 会话状态真相（state / hostSessionSnapshot / reconnectRecoveryState）。
         * 对应 V2 侧 `OpenClawd` 的生命周期与会话管理角色。
         */
        LIFECYCLE_MANAGER("lifecycle_manager"),

        /**
         * 传输层。
         *
         * 管理底层 WebSocket 连接（connect / disconnect / sendHandshake / sendJson）。
         * 不包含业务逻辑；仅是 Android ↔ V2 的可靠消息传输通道。
         */
        TRANSPORT_LAYER("transport_layer"),

        /**
         * 入站任务分发器。
         *
         * 接收来自 V2 的入站任务消息（`task_assign`、`goal_execution`、`parallel_subtask`），
         * 执行准入检查后委托给 execution runtime。
         * 对应 V2 侧 `CommandRouter` / `DeviceRouter` 的任务路由角色。
         */
        INBOUND_DISPATCHER("inbound_dispatcher"),

        /**
         * 执行运行时。
         *
         * 接收入站任务并驱动完整的目标执行流程（NL → planning → execution → result）。
         * 是 Android 作为 first-class runtime host 的核心运行时节点。
         */
        EXECUTION_RUNTIME("execution_runtime"),

        /**
         * 本地执行器。
         *
         * 在本地设备上执行闭环任务（local planner + grounding + accessibility）。
         * 当 crossDeviceEnabled=false 或 posture=join_runtime 时激活。
         */
        LOCAL_EXECUTOR("local_executor"),

        /**
         * 结果上行器。
         *
         * 将执行结果（goal_execution_result）和状态快照（device_state_snapshot）
         * 通过 transport 层上报给 V2，完成主链结果闭环。
         * 对应 V2 侧 result ingress / truth chain 的接收角色。
         */
        RESULT_UPLINK("result_uplink"),

        /**
         * 不适用（非主链模块）。
         *
         * 所有非 [RuntimeAccessLayerClass.MAIN_CHAIN] 模块使用此角色标记。
         */
        NOT_APPLICABLE("not_applicable")
    }

    // ── 接入链条目 ────────────────────────────────────────────────────────────

    /**
     * Android 运行时接入链中一个模块的完整记录。
     *
     * @param moduleId         稳定的模块标识符（kebab-case）。
     * @param displayName      人类可读的模块名称。
     * @param ownerClass       完整的 Kotlin 类名。
     * @param layerClass       此模块所属的 [RuntimeAccessLayerClass]。
     * @param role             此模块在主链中的 [ChainModuleRole]（非主链模块使用 NOT_APPLICABLE）。
     * @param v2Counterpart    与此模块对应的 V2 侧主链模块路径（可为 null）。
     * @param rationale        一句话说明此分类的理由。
     */
    data class AccessChainEntry(
        val moduleId: String,
        val displayName: String,
        val ownerClass: String,
        val layerClass: RuntimeAccessLayerClass,
        val role: ChainModuleRole,
        val v2Counterpart: String? = null,
        val rationale: String
    )

    // ── 完整接入链条目注册表 ──────────────────────────────────────────────────

    /**
     * Android 运行时所有已知模块的完整层级分类注册表。
     *
     * 主链模块（[RuntimeAccessLayerClass.MAIN_CHAIN]）按接入顺序排列。
     * Facade / compat / legacy / diagnostics 模块附后。
     */
    val entries: List<AccessChainEntry> = listOf(

        // ── 主链阶段 1：生命周期触发 ──────────────────────────────────────────

        AccessChainEntry(
            moduleId = "galaxy-connection-service-lifecycle-trigger",
            displayName = "GalaxyConnectionService (lifecycle trigger)",
            ownerClass = "com.ufo.galaxy.service.GalaxyConnectionService",
            layerClass = RuntimeAccessLayerClass.MAIN_CHAIN,
            role = ChainModuleRole.LIFECYCLE_TRIGGER,
            v2Counterpart = "core.desktop_presence_runtime.DesktopPresenceRuntime",
            rationale = "首个接入入口：onStartCommand 触发运行时启动，委托给 RuntimeController。"
        ),

        // ── 主链阶段 2：生命周期管理 ──────────────────────────────────────────

        AccessChainEntry(
            moduleId = "runtime-controller-lifecycle-manager",
            displayName = "RuntimeController (lifecycle manager)",
            ownerClass = "com.ufo.galaxy.runtime.RuntimeController",
            layerClass = RuntimeAccessLayerClass.MAIN_CHAIN,
            role = ChainModuleRole.LIFECYCLE_MANAGER,
            v2Counterpart = "core.openclawd.OpenClawd",
            rationale = "主链生命周期管理器：connectIfEnabled/start/stop/reconnect 管理 WS 连接生命周期与会话真相。"
        ),

        // ── 主链阶段 3：传输层 ────────────────────────────────────────────────

        AccessChainEntry(
            moduleId = "galaxy-websocket-client-transport",
            displayName = "GalaxyWebSocketClient (transport layer)",
            ownerClass = "com.ufo.galaxy.network.GalaxyWebSocketClient",
            layerClass = RuntimeAccessLayerClass.MAIN_CHAIN,
            role = ChainModuleRole.TRANSPORT_LAYER,
            v2Counterpart = null,
            rationale = "主链传输层：connect/disconnect/sendHandshake/sendJson 提供 Android ↔ V2 可靠消息通道。"
        ),

        // ── 主链阶段 4：入站分发 ──────────────────────────────────────────────

        AccessChainEntry(
            moduleId = "galaxy-connection-service-inbound-dispatcher",
            displayName = "GalaxyConnectionService (inbound dispatcher)",
            ownerClass = "com.ufo.galaxy.service.GalaxyConnectionService",
            layerClass = RuntimeAccessLayerClass.MAIN_CHAIN,
            role = ChainModuleRole.INBOUND_DISPATCHER,
            v2Counterpart = "core.command_router.CommandRouter / galaxy_gateway.device_router.DeviceRouter",
            rationale = "主链入站分发：handleGoalExecution/handleParallelSubtask 接收 V2 任务后委托给 AutonomousExecutionPipeline。"
        ),

        // ── 主链阶段 5：执行运行时 ────────────────────────────────────────────

        AccessChainEntry(
            moduleId = "autonomous-execution-pipeline-runtime",
            displayName = "AutonomousExecutionPipeline (execution runtime)",
            ownerClass = "com.ufo.galaxy.agent.AutonomousExecutionPipeline",
            layerClass = RuntimeAccessLayerClass.MAIN_CHAIN,
            role = ChainModuleRole.EXECUTION_RUNTIME,
            v2Counterpart = null,
            rationale = "主链执行运行时：handleGoalExecution/handleParallelSubtask 驱动完整目标执行流程，是 Android first-class runtime host 的核心节点。"
        ),

        // ── 主链阶段 6：本地执行器 ────────────────────────────────────────────

        AccessChainEntry(
            moduleId = "local-loop-executor-local-execution",
            displayName = "LocalLoopExecutor (local execution)",
            ownerClass = "com.ufo.galaxy.local.LocalLoopExecutor",
            layerClass = RuntimeAccessLayerClass.MAIN_CHAIN,
            role = ChainModuleRole.LOCAL_EXECUTOR,
            v2Counterpart = null,
            rationale = "主链本地执行器：execute() 在设备本地执行闭环任务（planner + grounding + accessibility）。"
        ),

        // ── 主链阶段 7：结果上行 ──────────────────────────────────────────────

        AccessChainEntry(
            moduleId = "galaxy-connection-service-result-uplink",
            displayName = "GalaxyConnectionService (result uplink)",
            ownerClass = "com.ufo.galaxy.service.GalaxyConnectionService",
            layerClass = RuntimeAccessLayerClass.MAIN_CHAIN,
            role = ChainModuleRole.RESULT_UPLINK,
            v2Counterpart = "core.task_result_canonical_truth_chain / core.android_device_state_store",
            rationale = "主链结果上行：sendGoalResult/sendDeviceStateSnapshot 完成主链结果闭环。"
        ),

        // ── Facade / Helper 层 ────────────────────────────────────────────────

        AccessChainEntry(
            moduleId = "delegated-runtime-readiness-evaluator-helper",
            displayName = "DelegatedRuntimeReadinessEvaluator (readiness helper)",
            ownerClass = "com.ufo.galaxy.runtime.DelegatedRuntimeReadinessEvaluator",
            layerClass = RuntimeAccessLayerClass.FACADE_HELPER,
            role = ChainModuleRole.NOT_APPLICABLE,
            rationale = "Facade/helper：为主链 inbound dispatcher 提供 readiness 评估辅助，本身不是接入路径节点。"
        ),
        AccessChainEntry(
            moduleId = "delegated-runtime-acceptance-evaluator-helper",
            displayName = "DelegatedRuntimeAcceptanceEvaluator (acceptance helper)",
            ownerClass = "com.ufo.galaxy.runtime.DelegatedRuntimeAcceptanceEvaluator",
            layerClass = RuntimeAccessLayerClass.FACADE_HELPER,
            role = ChainModuleRole.NOT_APPLICABLE,
            rationale = "Facade/helper：为主链结果上行提供 acceptance 评估辅助，本身不是接入路径节点。"
        ),
        AccessChainEntry(
            moduleId = "local-execution-mode-gate-helper",
            displayName = "LocalExecutionModeGate (mode gate helper)",
            ownerClass = "com.ufo.galaxy.runtime.LocalExecutionModeGate",
            layerClass = RuntimeAccessLayerClass.FACADE_HELPER,
            role = ChainModuleRole.NOT_APPLICABLE,
            rationale = "Facade/helper：为主链执行运行时提供执行模式判断辅助，本身不是接入路径节点。"
        ),

        // ── Compat / Fallback 层 ─────────────────────────────────────────────

        AccessChainEntry(
            moduleId = "agent-runtime-bridge-compat-fallback",
            displayName = "AgentRuntimeBridge (compat fallback bridge)",
            ownerClass = "com.ufo.galaxy.agent.AgentRuntimeBridge",
            layerClass = RuntimeAccessLayerClass.COMPAT_FALLBACK,
            role = ChainModuleRole.NOT_APPLICABLE,
            v2Counterpart = "core.capability_orchestrator.CapabilityOrchestrator._execute_builtin (compat)",
            rationale = "Compat fallback：仅在主链降级时激活的兼容桥接层，不得被视为主接入路径，不得扩展为新的接入节点。"
        ),

        // ── Legacy 层 ─────────────────────────────────────────────────────────

        AccessChainEntry(
            moduleId = "floating-window-service-legacy-bridge",
            displayName = "FloatingWindowService (legacy UI bridge)",
            ownerClass = "com.ufo.galaxy.service.FloatingWindowService",
            layerClass = RuntimeAccessLayerClass.LEGACY_BRIDGE,
            role = ChainModuleRole.NOT_APPLICABLE,
            rationale = "Legacy bridge：历史遗留 UI 服务，仅为向后兼容保留，不得扩展为主接入路径或新的接入节点。"
        ),
        AccessChainEntry(
            moduleId = "runtime-controller-registration-error-legacy",
            displayName = "RuntimeController.registrationError (legacy error bridge)",
            ownerClass = "com.ufo.galaxy.runtime.RuntimeController",
            layerClass = RuntimeAccessLayerClass.LEGACY_BRIDGE,
            role = ChainModuleRole.NOT_APPLICABLE,
            rationale = "Legacy bridge：已弃用的旧错误信号桥接，消费方已迁移至 setupError，仅为向后兼容保留。"
        ),

        // ── Diagnostics / Projection 层 ──────────────────────────────────────

        AccessChainEntry(
            moduleId = "android-delegated-runtime-audit-diagnostics",
            displayName = "AndroidDelegatedRuntimeAudit (diagnostics layer)",
            ownerClass = "com.ufo.galaxy.runtime.AndroidDelegatedRuntimeAudit",
            layerClass = RuntimeAccessLayerClass.DIAGNOSTICS_PROJECTION,
            role = ChainModuleRole.NOT_APPLICABLE,
            rationale = "Diagnostics/projection：运行时审计可观测性层，仅为读取侧，不参与接入链控制流。"
        ),
        AccessChainEntry(
            moduleId = "android-operational-state-surface-projection",
            displayName = "AndroidOperationalStateSurfaceContract (projection layer)",
            ownerClass = "com.ufo.galaxy.runtime.AndroidOperationalStateSurfaceContract",
            layerClass = RuntimeAccessLayerClass.DIAGNOSTICS_PROJECTION,
            role = ChainModuleRole.NOT_APPLICABLE,
            rationale = "Diagnostics/projection：Android 操作状态可观测面，是 operator-visible surface，不参与接入链准入判断。"
        )
    )

    // ── 主链条目快捷访问 ──────────────────────────────────────────────────────

    /**
     * Android 最小真实运行接入链的有序模块列表（lifecycle → result uplink）。
     *
     * 此列表是 Android first-class runtime host 地位的机器可验证依据。
     * V2 侧 MUST 将此列表中的模块视为 Android 的真实接入主体。
     */
    val minimalMainChainEntries: List<AccessChainEntry>
        get() = entries.filter { it.layerClass == RuntimeAccessLayerClass.MAIN_CHAIN }

    /**
     * 所有非主链条目（facade / compat / legacy / diagnostics）。
     *
     * 这些模块仍有工程价值，但 MUST NOT 被视为 Android 的主接入路径。
     */
    val nonMainChainEntries: List<AccessChainEntry>
        get() = entries.filter { it.layerClass != RuntimeAccessLayerClass.MAIN_CHAIN }

    /**
     * 按 [RuntimeAccessLayerClass] 返回对应条目列表。
     */
    fun entriesForLayer(layerClass: RuntimeAccessLayerClass): List<AccessChainEntry> =
        entries.filter { it.layerClass == layerClass }

    /**
     * 返回 [moduleId] 对应的条目，若未注册则返回 null。
     */
    fun entryForId(moduleId: String): AccessChainEntry? =
        entries.firstOrNull { it.moduleId == moduleId }

    /**
     * 返回指定 [role] 的主链条目，若不存在则返回 null。
     */
    fun mainChainEntryForRole(role: ChainModuleRole): AccessChainEntry? =
        minimalMainChainEntries.firstOrNull { it.role == role }

    // ── Wire map 生成 ─────────────────────────────────────────────────────────

    /**
     * 将指定 [entry] 的层级分类序列化为可嵌入上行消息的 wire map。
     *
     * @param entry 要序列化的 [AccessChainEntry]。
     * @return 包含 [KEY_RUNTIME_ACCESS_LAYER_CLASS]、[KEY_CHAIN_MODULE_ROLE]、
     *         [KEY_SCHEMA_VERSION] 的 map。
     */
    fun toWireMap(entry: AccessChainEntry): Map<String, String> = mapOf(
        KEY_RUNTIME_ACCESS_LAYER_CLASS to entry.layerClass.wireValue,
        KEY_CHAIN_MODULE_ROLE to entry.role.wireValue,
        KEY_SCHEMA_VERSION to SCHEMA_VERSION
    )

    // ── V2 消费路径映射表 ─────────────────────────────────────────────────────

    /**
     * Android 主链阶段 → V2 消费路径的稳定对应关系表。
     *
     * 此映射表是双仓接入叙事一致性的机器可验证锚点。
     *
     * @see AccessChainEntry.v2Counterpart
     */
    val V2_CONSUMPTION_PATH_MAP: Map<ChainModuleRole, String> = mapOf(
        ChainModuleRole.LIFECYCLE_TRIGGER to
            "core.desktop_presence_runtime.DesktopPresenceRuntime (lifecycle init)",
        ChainModuleRole.LIFECYCLE_MANAGER to
            "core.openclawd.OpenClawd (lifecycle + session management)",
        ChainModuleRole.INBOUND_DISPATCHER to
            "core.command_router.CommandRouter + galaxy_gateway.device_router.DeviceRouter",
        ChainModuleRole.RESULT_UPLINK to
            "core.task_result_canonical_truth_chain + core.android_device_state_store"
    )

    // ── 形式化不变量 ──────────────────────────────────────────────────────────

    /**
     * Android 最小真实运行接入链的形式化不变量。
     *
     * 每条不变量是 V2 消费方可依赖的 Android 侧保证。
     * 任何违反这些不变量的改动都构成主链完整性破坏。
     */
    val CHAIN_INVARIANTS: List<String> = listOf(
        // 不变量 1：唯一主入口
        "INVARIANT-1: GalaxyConnectionService.onStartCommand 是 Android 运行时的唯一 MAIN_CHAIN lifecycle trigger；" +
            "所有其他启动路径（MainActivity.startServices、BootReceiver.onReceive）MUST 委托到此入口，不得绕过。",

        // 不变量 2：唯一生命周期管理器
        "INVARIANT-2: RuntimeController 是 Android 运行时的唯一 MAIN_CHAIN lifecycle manager；" +
            "WebSocket 连接的建立、断开、重连 MUST 通过 RuntimeController 管理，不得由 facade/compat/legacy 层直接控制。",

        // 不变量 3：唯一传输通道
        "INVARIANT-3: GalaxyWebSocketClient 是 Android ↔ V2 的唯一 MAIN_CHAIN transport layer；" +
            "所有上行消息（state snapshot、execution event、goal result）MUST 通过此通道发送。",

        // 不变量 4：主链入站分发
        "INVARIANT-4: GalaxyConnectionService.handleGoalExecution/handleParallelSubtask 是 Android 侧的 MAIN_CHAIN inbound dispatcher；" +
            "所有来自 V2 的任务执行请求 MUST 通过此入口进入，AgentRuntimeBridge 仅为 COMPAT_FALLBACK，" +
            "不得在正常 canonical 路径中替代主链入站分发。",

        // 不变量 5：主链执行运行时
        "INVARIANT-5: AutonomousExecutionPipeline 是 Android 的 MAIN_CHAIN execution runtime；" +
            "所有委托任务的执行 MUST 通过此运行时处理，不得通过 compat/legacy 路径绕过。",

        // 不变量 6：结果上行完整性
        "INVARIANT-6: GalaxyConnectionService.sendGoalResult/sendDeviceStateSnapshot 是 MAIN_CHAIN result uplink；" +
            "所有执行结果 MUST 通过此路径上报，诊断/diagnostics 层 MUST NOT 参与结果上行的控制流。",

        // 不变量 7：compat/legacy 不得冒充主链
        "INVARIANT-7: COMPAT_FALLBACK 和 LEGACY_BRIDGE 层模块 MUST NOT 在 V2 侧被识别为 Android 主接入路径；" +
            "它们的激活场景 MUST 在上行消息中通过 dispatch_boundary_class=compat_fallback/legacy_bypass 声明。",

        // 不变量 8：diagnostics 层不参与准入控制
        "INVARIANT-8: DIAGNOSTICS_PROJECTION 层模块（audit、board、operator surface、projection）" +
            "MUST NOT 参与 Android 运行时接入链的准入判断或控制流；" +
            "V2 MUST NOT 将 DIAGNOSTICS_PROJECTION 层的输出作为 Android 接入准入依据。",

        // 不变量 9：双仓主链叙事一致
        "INVARIANT-9: Android MAIN_CHAIN 的各阶段（lifecycle trigger/manager/transport/inbound dispatch/execution/result uplink）" +
            "MUST 与 V2 PR-06v2 固定的 DesktopPresenceRuntime/OpenClawd/CommandRouter/DeviceRouter 主链形成一致的双仓接入叙事；" +
            "不允许在此合约之外新建平行 Android 运行接入主链。"
    )
}
