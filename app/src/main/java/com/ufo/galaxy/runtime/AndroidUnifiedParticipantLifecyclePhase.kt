package com.ufo.galaxy.runtime

/**
 * Android 侧统一参与者生命周期阶段合约。
 *
 * ## 背景与问题
 *
 * 在此合约引入之前，V2 中心要判断 Android 参与者当前处于生命周期的哪个阶段，
 * 必须组合多个分散字段：
 *  - `authoritative_participation_state`（参与层级：7 值）
 *  - `carrier_runtime_state`（运行时状态：5 值）
 *  - `reconnect_recovery_state`（重连恢复状态：4 值）
 *  - `execution_mode_state`（执行模式：5 值）
 *  - `dispatch_eligible` / `distributed_participant`（布尔值）
 *  - `takeover_state`（接管状态：3 值）
 *  - `governance_blocked`（治理阻断布尔值）
 *  - `execution_busy`（执行忙碌布尔值）
 *
 * 这种分散造成了以下问题：
 *  - V2 必须自己实现推导逻辑，容易与 Android 本地逻辑产生语义漂移。
 *  - "可见"（visible）、"已连接"（connected）、"就绪"（ready）、"参与"（participating）、
 *    "接管就绪"（takeover_eligible）之间的边界不明确。
 *  - 生命周期回退（如 READY → DEGRADED）在 V2 侧难以可靠检测。
 *
 * ## 解决方案
 *
 * [AndroidUnifiedParticipantLifecyclePhase] 提供一个单一的 `unified_lifecycle_phase`
 * wire 字段，明确编码 Android 参与者在共享中心—分布式生命周期模型中的精确位置：
 *
 * | [Phase]               | Wire 值                | 含义                                                              |
 * |-----------------------|------------------------|-------------------------------------------------------------------|
 * | [Phase.UNREGISTERED]  | `unregistered`         | 未注册或跨设备未启用；无任何参与可能                              |
 * | [Phase.REGISTERED]    | `registered`           | 已注册，跨设备已启用，但 WS 未连接                                |
 * | [Phase.CONNECTED]     | `connected`            | WS 已连接，但能力尚未通告（未可见）                               |
 * | [Phase.VISIBLE]       | `visible`              | 能力已通告并对 V2 可见；V2 可路由任务                             |
 * | [Phase.READY]         | `ready`                | 运行时健康、会话已附加、就绪条件满足；可接受委托任务              |
 * | [Phase.TAKEOVER_ELIGIBLE] | `takeover_eligible` | READY 且无活跃执行任务且接管门控放行；可接受交互接管             |
 * | [Phase.PARTICIPATING] | `participating`        | 当前有活跃的分布式执行或接管任务在运行                            |
 * | [Phase.DEGRADED]      | `degraded`             | 已连接/已注册但运行时降级；仅基础能力可用                         |
 * | [Phase.RECOVERING]    | `recovering`           | WS 重连中或运行时恢复中；分发被阻断                               |
 * | [Phase.UNAVAILABLE]   | `unavailable`          | 运行时不可用或已停止；无任何执行可能                              |
 *
 * ## 推导优先级
 *
 * [derive] 函数按以下优先级从现有运行时信号推导当前阶段（无需新探针）：
 *
 *  1. 运行时不可用（UNAVAILABLE_FAILED）→ [Phase.UNAVAILABLE]
 *  2. WS 重连中或运行时恢复中 → [Phase.RECOVERING]
 *  3. 治理阻断 → [Phase.DEGRADED]
 *  4. 跨设备未启用 → [Phase.UNREGISTERED]
 *  5. WS 未连接（但跨设备已启用）→ [Phase.REGISTERED]（若曾注册）/ [Phase.UNREGISTERED]
 *  6. 运行时正在启动或降级（但 WS 已连接）→ [Phase.DEGRADED]
 *  7. 能力未可见 → [Phase.CONNECTED]
 *  8. 会话未附加或就绪未满足 → [Phase.VISIBLE]
 *  9. 活跃分布式执行或接管 → [Phase.PARTICIPATING]
 * 10. 执行不忙碌且接管门控放行 → [Phase.TAKEOVER_ELIGIBLE]
 * 11. 就绪且会话已附加 → [Phase.READY]
 * 12. 默认 → [Phase.VISIBLE]（回退值）
 *
 * ## 与现有合约的关系
 *
 * [AndroidUnifiedParticipantLifecyclePhase] 是一个**纯推导合约**，不拥有任何生命周期状态。
 * 它从以下现有权威面推导阶段，不替代它们：
 *  - [FormalParticipantLifecycleState] — 运行时健康层
 *  - [AndroidAuthoritativeParticipationTruth] — 参与层级
 *  - [ReconnectRecoveryState] — WS 重连状态
 *  - [AndroidUnifiedTruthUplinkContract.GovernanceTruth] — 治理层
 *
 * ## V2 消费指导
 *
 * V2 MUST 直接读取 `unified_lifecycle_phase` wire 字段作为 Android 生命周期阶段的
 * 权威来源，而非通过字段组合自行推导。在 `unified_lifecycle_phase` 明确可用时：
 *  - 不得再通过 `participation_tier` + `carrier_runtime_state` 组合推断
 *  - 不得通过 `reconnect_recovery_state` + `execution_busy` 推断接管就绪性
 *  - 不得通过 `dispatch_eligible` 布尔值单独判定"就绪"语义
 *
 * @see FormalParticipantLifecycleState
 * @see AndroidAuthoritativeParticipationTruth
 * @see ReconnectRecoveryState
 */
object AndroidUnifiedParticipantLifecyclePhase {

    /** schema 版本，用于 V2 侧字段兼容性校验。 */
    const val SCHEMA_VERSION: String = "1"

    // ── 阶段枚举 ──────────────────────────────────────────────────────────────

    /**
     * Android 参与者在共享中心—分布式生命周期中的精确位置。
     *
     * @property wireValue   稳定的小写 wire 字符串，用于 V2 载体消费。
     * @property phaseRank   阶段序号；正数表示主路径上的稳步前进，
     *                       负数表示偏离主路径的降级/恢复/不可用状态。
     * @property description 人类可读描述，用于诊断日志和审计面。
     */
    enum class Phase(
        val wireValue: String,
        val phaseRank: Int,
        val description: String
    ) {

        /**
         * 未注册。跨设备未启用，或从未与 V2 网关注册过。
         * 无任何参与可能。这是全新安装或显式退出/注销后的初始状态。
         */
        UNREGISTERED(
            wireValue = "unregistered",
            phaseRank = 0,
            description = "跨设备未启用或未注册；无参与可能"
        ),

        /**
         * 已注册。跨设备已启用，持久参与者身份已建立，但 WS 当前未连接。
         * Android 已具备参与者身份，但尚未与 V2 建立实时链接。
         */
        REGISTERED(
            wireValue = "registered",
            phaseRank = 1,
            description = "跨设备已启用且持有参与者身份，但 WS 未连接"
        ),

        /**
         * 已连接。WS 已连接，但能力通告尚未完成或尚未对 V2 可见。
         * 这是连接建立后、能力报告握手完成前的瞬态阶段。
         */
        CONNECTED(
            wireValue = "connected",
            phaseRank = 2,
            description = "WS 已连接，能力通告尚未完成"
        ),

        /**
         * 已可见。能力已通告并对 V2 可见；V2 可将任务路由到此参与者。
         * 会话可能尚未完全附加，或就绪条件尚未满足。
         */
        VISIBLE(
            wireValue = "visible",
            phaseRank = 3,
            description = "能力已通告，V2 可见；会话附加或就绪可能尚未满足"
        ),

        /**
         * 已就绪。运行时健康（READY 或 DEGRADED 但功能正常），会话已完全附加，
         * 就绪条件满足。可接受委托任务或参与分布式执行。
         */
        READY(
            wireValue = "ready",
            phaseRank = 4,
            description = "运行时健康、会话已附加、就绪满足；可接受委托工作"
        ),

        /**
         * 接管就绪。处于 READY 阶段且当前无活跃执行任务，接管门控已放行。
         * 这是可以发起交互接管的专用状态，对 V2 接管路由决策有明确语义。
         *
         * 注意：接管就绪与就绪（READY）的区别在于：READY 状态可能同时存在活跃的
         * 委托任务，而 TAKEOVER_ELIGIBLE 明确声明当前无执行压力。
         */
        TAKEOVER_ELIGIBLE(
            wireValue = "takeover_eligible",
            phaseRank = 5,
            description = "READY 且无活跃执行；接管门控放行"
        ),

        /**
         * 参与中。当前存在活跃的分布式执行任务或接管任务正在运行。
         * Android 正作为执行节点活跃参与中心—分布式系统的执行主链。
         */
        PARTICIPATING(
            wireValue = "participating",
            phaseRank = 6,
            description = "活跃分布式执行或接管正在运行"
        ),

        /**
         * 降级。WS 已连接或已注册，但运行时处于降级状态（仅基础能力可用，
         * 推理/高级能力不可用），或治理层已阻断参与。
         */
        DEGRADED(
            wireValue = "degraded",
            phaseRank = -1,
            description = "运行时降级或治理阻断；仅基础能力"
        ),

        /**
         * 恢复中。WS 正在重连，或运行时正在从故障中恢复。
         * 分发被阻断；等待恢复完成。
         */
        RECOVERING(
            wireValue = "recovering",
            phaseRank = -2,
            description = "WS 重连中或运行时恢复中；分发阻断"
        ),

        /**
         * 不可用。运行时已失败、已停止或进入安全模式。无任何执行可能。
         */
        UNAVAILABLE(
            wireValue = "unavailable",
            phaseRank = -3,
            description = "运行时不可用或已停止；无执行可能"
        );

        companion object {
            /**
             * 从 [wireValue] 解析对应阶段；未知值返回 null。
             */
            fun fromWireValue(value: String?): Phase? =
                entries.firstOrNull { it.wireValue == value }

            /** 所有稳定 wire 值集合。 */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── 推导输入 ──────────────────────────────────────────────────────────────

    /**
     * [derive] 所需的推导输入，从现有运行时信号汇聚而来。
     *
     * 所有字段均来自 GalaxyConnectionService.sendDeviceStateSnapshot() 或
     * deviceExecutionEventSink 已经计算好的运行时信号，无需额外探针。
     *
     * @property formalLifecycleState      [FormalParticipantLifecycleState]：运行时健康状态。
     * @property reconnectRecoveryStateWire [ReconnectRecoveryState.wireValue]：WS 重连阶段。
     * @property crossDeviceEnabled         跨设备是否已启用（来自 AppSettings）。
     * @property wsConnected                WS 当前是否已连接。
     * @property hasDurableParticipantId    是否持有持久参与者 ID（表明曾成功注册）。
     * @property capabilityVisible          能力是否已通告并对 V2 可见。
     * @property sessionAttached            运行时会话是否已完全附加。
     * @property readinessSatisfied         就绪条件是否满足（来自 crossDeviceEligibility）。
     * @property executionBusy              当前是否有活跃执行任务。
     * @property takeoverActive             当前是否有活跃接管任务。
     * @property interactionSurfaceReady    交互面（无障碍 + 悬浮窗）是否就绪。
     * @property governanceBlocked          治理层是否显式阻断。
     */
    data class DerivationInput(
        val formalLifecycleState: FormalParticipantLifecycleState,
        val reconnectRecoveryStateWire: String,
        val crossDeviceEnabled: Boolean,
        val wsConnected: Boolean,
        val hasDurableParticipantId: Boolean,
        val capabilityVisible: Boolean,
        val sessionAttached: Boolean,
        val readinessSatisfied: Boolean,
        val executionBusy: Boolean,
        val takeoverActive: Boolean,
        val interactionSurfaceReady: Boolean,
        val governanceBlocked: Boolean
    )

    // ── 推导逻辑 ──────────────────────────────────────────────────────────────

    /**
     * 从 [DerivationInput] 推导当前 [Phase]。
     *
     * 推导规则按优先级从高到低评估。每个规则对应生命周期中一个明确的语义边界，
     * 确保任何运行时信号组合都能映射到唯一的阶段。
     *
     * @param input 从现有运行时信号汇聚的推导输入。
     * @return 对应当前运行时状态的 [Phase]。
     */
    fun derive(input: DerivationInput): Phase = when {

        // 优先级 1：运行时不可用（UNAVAILABLE_FAILED 或启动失败）
        input.formalLifecycleState == FormalParticipantLifecycleState.UNAVAILABLE_FAILED ->
            Phase.UNAVAILABLE

        // 优先级 2：WS 重连中或运行时正在恢复
        input.reconnectRecoveryStateWire == ReconnectRecoveryState.RECOVERING.wireValue ||
            input.formalLifecycleState == FormalParticipantLifecycleState.RECOVERING ->
            Phase.RECOVERING

        // 优先级 3：治理层显式阻断
        input.governanceBlocked ->
            Phase.DEGRADED

        // 优先级 4：跨设备未启用
        !input.crossDeviceEnabled ->
            Phase.UNREGISTERED

        // 优先级 5：WS 未连接（跨设备已启用时区分是否曾注册）
        !input.wsConnected ->
            if (input.hasDurableParticipantId) Phase.REGISTERED else Phase.UNREGISTERED

        // 优先级 6：运行时正在启动或降级（WS 已连接但运行时未就绪）
        input.formalLifecycleState == FormalParticipantLifecycleState.STARTING ||
            input.formalLifecycleState == FormalParticipantLifecycleState.DEGRADED ->
            Phase.DEGRADED

        // 优先级 7：能力未通告（WS 已连接、运行时 READY/DEGRADED，但能力报告未送达）
        !input.capabilityVisible ->
            Phase.CONNECTED

        // 优先级 8：会话未附加或就绪未满足（能力已可见，但参与前提未满足）
        !input.sessionAttached || !input.readinessSatisfied ->
            Phase.VISIBLE

        // 优先级 9：活跃分布式执行或接管任务正在运行
        input.executionBusy || input.takeoverActive ->
            Phase.PARTICIPATING

        // 优先级 10：无活跃执行、交互面就绪 → 接管就绪
        input.interactionSurfaceReady ->
            Phase.TAKEOVER_ELIGIBLE

        // 优先级 11：就绪且会话已附加（交互面未必就绪）
        else ->
            Phase.READY
    }

    // ── 阶段不变量 ────────────────────────────────────────────────────────────

    /**
     * 统一生命周期阶段的核心不变量。
     *
     * 这些不变量由 GalaxyConnectionService 在派生阶段时隐式满足，
     * 测试套件可用来验证推导函数的正确性。
     */
    val PHASE_INVARIANTS: List<String> = listOf(

        "PARTICIPATING requires sessionAttached=true and (executionBusy=true or takeoverActive=true)",
        "TAKEOVER_ELIGIBLE requires sessionAttached=true, readinessSatisfied=true, " +
            "executionBusy=false, takeoverActive=false, interactionSurfaceReady=true",
        "READY requires sessionAttached=true, readinessSatisfied=true, " +
            "executionBusy=false, takeoverActive=false",
        "VISIBLE requires capabilityVisible=true and wsConnected=true",
        "CONNECTED requires wsConnected=true and capabilityVisible=false",
        "REGISTERED requires crossDeviceEnabled=true and wsConnected=false and hasDurableParticipantId=true",
        "UNREGISTERED requires crossDeviceEnabled=false or hasDurableParticipantId=false with wsConnected=false",
        "DEGRADED requires not-UNAVAILABLE_FAILED and not-RECOVERING and " +
            "(governanceBlocked=true or formalLifecycleState in [STARTING, DEGRADED])",
        "RECOVERING requires reconnectRecoveryStateWire=recovering or formalLifecycleState=RECOVERING",
        "UNAVAILABLE requires formalLifecycleState=UNAVAILABLE_FAILED",
        "unified_lifecycle_phase must be populated in all DeviceStateSnapshotPayload emissions",
        "unified_lifecycle_phase must be populated in all DeviceExecutionEventPayload emissions"
    )

    // ── 阶段工具方法 ──────────────────────────────────────────────────────────

    /**
     * 返回 [phase] 是否表示 Android 在共享生命周期中正在活跃参与（可接受工作或正在执行）。
     *
     * 这对应 V2 中心模型中"参与者已激活"的语义边界。
     */
    fun isActivelyParticipating(phase: Phase): Boolean =
        phase == Phase.READY ||
            phase == Phase.TAKEOVER_ELIGIBLE ||
            phase == Phase.PARTICIPATING

    /**
     * 返回 [phase] 是否允许 V2 向 Android 分发新任务。
     *
     * 只有 [Phase.READY] 和 [Phase.TAKEOVER_ELIGIBLE] 允许接受新分发。
     * [Phase.PARTICIPATING] 表示当前已有任务在执行，V2 SHOULD NOT 再分发新任务
     * 除非该任务允许并发。
     */
    fun isDispatchAllowed(phase: Phase): Boolean =
        phase == Phase.READY || phase == Phase.TAKEOVER_ELIGIBLE

    /**
     * 返回 [phase] 是否可接受交互接管请求。
     *
     * 只有 [Phase.TAKEOVER_ELIGIBLE] 明确声明可接受接管。
     */
    fun isTakeoverAcceptable(phase: Phase): Boolean =
        phase == Phase.TAKEOVER_ELIGIBLE

    /**
     * 返回从 [from] 到 [to] 的阶段转换是否属于向前进展（phaseRank 提升或保持）。
     *
     * 向后退（phaseRank 下降进入负值域）表示降级/恢复事件，
     * V2 可据此触发重平衡评估。
     */
    fun isForwardTransition(from: Phase, to: Phase): Boolean =
        to.phaseRank > from.phaseRank && to.phaseRank >= 0

    /**
     * 返回从 [from] 到 [to] 的阶段转换是否属于降级事件（进入负值域）。
     *
     * 降级转换要求 V2 触发 formation 重平衡评估。
     */
    fun isDegradationTransition(from: Phase, to: Phase): Boolean =
        from.phaseRank >= 0 && to.phaseRank < 0
}
