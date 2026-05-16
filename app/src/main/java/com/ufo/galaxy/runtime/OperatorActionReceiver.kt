package com.ufo.galaxy.runtime

/**
 * PR-B2 (Android) — V2 下行 operator action 接收器与参与上下文保留组件。
 *
 * ## 解决的核心问题
 *
 * 在本组件之前，Android 侧对 V2 下行 directed operator action 的处理逻辑分散在
 * [com.ufo.galaxy.service.GalaxyConnectionService] 内部，存在以下问题：
 *
 *  1. **无独立接收器路径**：V2 下行 operator action 的治理验证与执行分派逻辑
 *     以内联方式嵌入服务层，缺少可独立测试的接收器组件。
 *
 *  2. **参与上下文丢失**：operator action 的决策结果载体（[com.ufo.galaxy.protocol.OperatorActionResultPayload]）
 *     缺少关键参与上下文字段（`participation_tier`、`local_mode_active`、
 *     `runtime_constrained`、`runtime_deferred`、`delegated_execution_active`），
 *     导致 V2 下游消费方无法对 operator action 的完整运行时语境作出判断。
 *
 *  3. **本地/分布式/执行活跃状态歧义**：未区分本地参与状态、分布式参与状态与执行活跃状态，
 *     使 V2 无法通过单条结果消息精确推断 Android 侧的参与层级。
 *
 * ## 本组件职责
 *
 *  - 提供 [buildParticipationContext] ——在 action 接收时刻一次性捕获完整参与上下文快照，
 *    并保留至 DECISION 和 EXECUTION 两个阶段的上行结果载体中。
 *  - 提供 [evaluateGovernanceDecision] ——将 V2 下行 action 路由至
 *    [AndroidOperatorActionGovernanceContract] 治理门控，并将治理决策与参与上下文封装为
 *    [GovernanceDecision]，供调用方构建携带完整语境的 [com.ufo.galaxy.protocol.OperatorActionResultPayload]。
 *  - 声明 [RECEIVER_INVARIANTS] ——形式化本接收器的行为约束，为 V2 侧集成提供稳定语义保证。
 *
 * ## V2 消费约定
 *
 * V2 接收到 `operator_action_result` 时 MUST：
 *  1. 读取 `participation_tier` 而非通过 `authoritative_participation_state` 组合推断层级。
 *  2. 将 `delegated_execution_active` 作为委托执行状态的权威信号。
 *  3. 将 `runtime_constrained`/`runtime_deferred` 作为形式约束信号，而非通过字段组合推断。
 *  4. 在 DECISION 与 EXECUTION 两阶段结果之间，`participation_tier` 应保持一致；
 *     如出现变化，表示 action 执行触发了参与状态迁移，V2 应相应更新本地视图。
 *
 * @see AndroidOperatorActionGovernanceContract
 * @see AndroidAuthoritativeParticipationTruth
 * @see AndroidUnifiedTruthUplinkContract
 */
object OperatorActionReceiver {

    // ── 合约元数据 ──────────────────────────────────────────────────────────────

    /** 本接收器引入 PR 号（PR-B2）。 */
    const val INTRODUCED_PR: String = "B2"

    /**
     * 参与上下文字段的 schema 版本。
     *
     * V2 可通过 `operator_action_result` 载体中的 `participation_context_schema_version`
     * 字段感知本版本，以支持向后兼容的字段演进。
     */
    const val PARTICIPATION_CONTEXT_SCHEMA_VERSION: String = "1"

    // ── 参与上下文快照 ──────────────────────────────────────────────────────────

    /**
     * action 接收时刻捕获的参与上下文快照。
     *
     * 此快照在 DECISION 阶段捕获，并原样传入 EXECUTION 阶段的上行结果载体，
     * 确保 V2 下游消费方可以将 operator action 的治理决策与执行结果关联到
     * 同一参与语境，而无需跨消息重建上下文。
     *
     * @param authoritativeParticipationState Android 权威参与状态（wire 值）。
     * @param participationTier               从 [authoritativeParticipationState] 派生的七级参与层级（wire 值）。
     * @param localModeActive                 Android 当前是否处于本地执行模式（非跨设备）。
     * @param runtimeConstrained              Android 是否因运行时约束而无法正常分发。
     * @param runtimeDeferred                 Android 是否处于延迟/保持状态（hold state）。
     * @param delegatedExecutionActive        Android 当前是否有活跃的委托执行任务。
     */
    data class ReceivedActionParticipationContext(
        val authoritativeParticipationState: String,
        val participationTier: String,
        val localModeActive: Boolean,
        val runtimeConstrained: Boolean,
        val runtimeDeferred: Boolean,
        val delegatedExecutionActive: Boolean
    )

    // ── 治理决策结果 ────────────────────────────────────────────────────────────

    /**
     * 经治理门控后的 V2 directed operator action 决策结果。
     *
     * 封装了 [AndroidOperatorActionGovernanceContract] 的资格决策与接收时刻捕获的
     * [ReceivedActionParticipationContext]，供调用方在构建
     * [com.ufo.galaxy.protocol.OperatorActionResultPayload] 时直接填充参与上下文字段。
     *
     * @param eligibility          治理资格决策：[AndroidOperatorActionGovernanceContract.EligibilityDecision.Accepted]
     *                             或 [AndroidOperatorActionGovernanceContract.EligibilityDecision.Rejected]。
     * @param participationContext 接收时刻捕获的参与上下文快照。
     */
    data class GovernanceDecision(
        val eligibility: AndroidOperatorActionGovernanceContract.EligibilityDecision,
        val participationContext: ReceivedActionParticipationContext
    ) {
        /** 当前治理决策是否为接受（Accepted）。 */
        val isAccepted: Boolean
            get() = eligibility is AndroidOperatorActionGovernanceContract.EligibilityDecision.Accepted

        /** 拒绝原因字符串；仅在 [isAccepted] 为 false 时非 null。 */
        val rejectionReason: String?
            get() = (eligibility as? AndroidOperatorActionGovernanceContract.EligibilityDecision.Rejected)?.reason
    }

    // ── 核心 API ────────────────────────────────────────────────────────────────

    /**
     * 在 action 接收时刻构建参与上下文快照。
     *
     * 此方法应在 [com.ufo.galaxy.service.GalaxyConnectionService] 的
     * `handleOperatorActionRequest` 入口处调用，以确保 DECISION 和 EXECUTION
     * 两个阶段均携带一致且完整的参与上下文。
     *
     * @param participationSnapshot       由 [AndroidAuthoritativeParticipationTruth.Tracker.evaluate] 返回的参与快照。
     * @param isLocalModeActive           当前 [LocalExecutionModeGate.ExecutionModeState] 是否为 LOCAL_ONLY。
     * @param isRuntimeConstrained        是否因运行时约束（CONSTRAINED 降级条件）无法正常分发。
     * @param isRuntimeDeferred           是否处于延迟/保持状态（isHoldState == true）。
     * @param isDelegatedExecutionActive  是否有活跃委托执行任务（来自 [AndroidUnifiedTruthUplinkContract.GovernanceTruth]）。
     * @return 参与上下文快照 [ReceivedActionParticipationContext]。
     */
    fun buildParticipationContext(
        participationSnapshot: AndroidAuthoritativeParticipationTruth.Snapshot,
        isLocalModeActive: Boolean,
        isRuntimeConstrained: Boolean,
        isRuntimeDeferred: Boolean,
        isDelegatedExecutionActive: Boolean
    ): ReceivedActionParticipationContext = ReceivedActionParticipationContext(
        authoritativeParticipationState = participationSnapshot.state.wireValue,
        participationTier = AndroidAuthoritativeParticipationTruth
            .participationTierFor(participationSnapshot.state).wireValue,
        localModeActive = isLocalModeActive,
        runtimeConstrained = isRuntimeConstrained,
        runtimeDeferred = isRuntimeDeferred,
        delegatedExecutionActive = isDelegatedExecutionActive
    )

    /**
     * V2 下行 operator action 的治理门控入口。
     *
     * 此方法是 Android 侧接收 V2 directed operator action 的真实治理入口点，
     * 确保所有 V2 下行指令均经过 [AndroidOperatorActionGovernanceContract] 验证，
     * 且验证结果携带完整参与上下文，使 V2 下游消费方可以无歧义地解读决策语境。
     *
     * ## 调用规范
     *
     * 调用方必须在调用此方法前已完成 [buildParticipationContext]，以确保
     * 参与上下文反映 action 接收时刻的真实运行时状态。
     *
     * @param actionKind           已解析的 [AndroidOperatorActionGovernanceContract.ActionKind]。
     * @param context              治理资格上下文，由 [com.ufo.galaxy.runtime.RuntimeController.buildOperatorActionEligibilityContext] 提供。
     * @param taskId               关联任务 ID（可选）。
     * @param participationContext 由 [buildParticipationContext] 构建的参与上下文快照。
     * @return 携带参与上下文的 [GovernanceDecision]。
     */
    fun evaluateGovernanceDecision(
        actionKind: AndroidOperatorActionGovernanceContract.ActionKind,
        context: AndroidOperatorActionGovernanceContract.EligibilityContext,
        taskId: String?,
        participationContext: ReceivedActionParticipationContext
    ): GovernanceDecision {
        val eligibility = AndroidOperatorActionGovernanceContract.evaluateEligibility(
            action = actionKind,
            context = context,
            taskId = taskId
        )
        return GovernanceDecision(
            eligibility = eligibility,
            participationContext = participationContext
        )
    }

    // ── 形式化不变量 ─────────────────────────────────────────────────────────────

    /**
     * OperatorActionReceiver 的形式化行为约束。
     *
     * 这些不变量为 V2 侧集成 `operator_action_result` 消费逻辑提供稳定的语义保证。
     * V2 集成方 MUST 满足所有带 "MUST" 标记的约束；Android 侧实现 MUST NOT 违反
     * 任何带 "MUST NOT" 标记的约束。
     */
    val RECEIVER_INVARIANTS: List<String> = listOf(
        "INV-OAR-01: 每个 V2 下行 operator action MUST 经过 AndroidOperatorActionGovernanceContract 治理门控，" +
            "MUST NOT 在缺少治理验证的情况下直接执行。",
        "INV-OAR-02: OperatorActionResultPayload MUST 在 DECISION 和 EXECUTION 两个阶段携带一致的参与上下文；" +
            "若上下文在两阶段之间发生变化，表示 action 执行触发了参与状态迁移。",
        "INV-OAR-03: participation_tier MUST 从 authoritative_participation_state 通过 " +
            "AndroidAuthoritativeParticipationTruth.participationTierFor() 派生，" +
            "MUST NOT 单独存储或手动填充。",
        "INV-OAR-04: delegated_execution_active MUST 反映 AndroidUnifiedTruthUplinkContract.GovernanceTruth " +
            "中的委托执行状态，MUST NOT 使用静态配置或能力元数据作为来源。",
        "INV-OAR-05: local_mode_active MUST 反映 LocalExecutionModeGate 的实时决策（ExecutionModeState == LOCAL_ONLY），" +
            "MUST NOT 使用能力元数据中的 local_mode_active 字段。",
        "INV-OAR-06: runtime_constrained MUST 为 true 当且仅当设备处于受限状态无法正常分发，" +
            "包括但不限于：dispatch_eligible == false 或 degraded_condition_class == CONSTRAINED。",
        "INV-OAR-07: runtime_deferred MUST 为 true 当且仅当设备处于保持/延迟状态（isHoldState == true）。"
    )
}
