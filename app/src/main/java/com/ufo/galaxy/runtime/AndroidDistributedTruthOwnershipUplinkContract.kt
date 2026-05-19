package com.ufo.galaxy.runtime

/**
 * PR-09Android — Android 侧分布式真值与 ownership 上行语义收束合约。
 *
 * 本合约是 V2 仓库 PR-09v2 的 Android 侧联动 PR，基于 Android 侧真实已有 runtime/result/state
 * uplink 结构，对以下上行信号类别进行语义收束：
 *  - authority runtime / device / session signal（权威运行时/设备/会话信号）
 *  - ownership / handoff-related signal（ownership / handoff 相关信号）
 *  - operator-visible summary / projection signal（算子可见摘要 / 投影信号）
 *  - diagnostics / audit signal（诊断 / 审计信号）
 *
 * ## 解决的问题
 *
 * 在本合约引入之前，Android 侧上行信号存在以下边界模糊问题：
 *
 * 1. **authority runtime signal 与 ownership posture signal 混淆**：Android 的实际执行权威状态
 *    （RuntimeController 管理的运行时权威）与 ownership posture 声明（握手时的 join_runtime /
 *    control_only 声明）没有被机器可读地区分，导致 V2 侧消费者可能将 posture 信号误用为
 *    runtime authority truth 的权威依据。
 *
 * 2. **operator-visible summary 被误抬为 authority truth**：Android 的
 *    AndroidOperationalStateSurfaceContract 提供的 operator-visible 摘要是 projection-only 可见面，
 *    不是 runtime authority truth。但在没有机器可读声明的情况下，V2 消费者可能将 operator-visible
 *    summary 误用为 dispatch scoring 或 result closure 的权威依据。
 *
 * 3. **session continuity 信号与 session recovery 状态混淆**：Android 侧的 session ID 连续性
 *    声明（DurableSessionContinuityRecord）与 session 恢复中的临时状态没有被明确区分，导致
 *    V2 侧在 session 恢复过程中可能错误地消费 stale session continuity 信号。
 *
 * 4. **device posture 信号语义不清**：Android 设备作为强运行时节点（runtime node）的 posture 声明
 *    与仅作控制面节点（control-plane-only）的 posture 声明没有被机器可读地区分，V2 侧无法直接
 *    判断 Android 是否具备真实的 distributed execution 能力。
 *
 * 5. **诊断/审计信号与权威信号混用**：Android 的诊断性上行（device_state_snapshot 诊断字段、
 *    observability audit 字段）与权威运行时结果上行共享载体，在没有机器可读边界声明的情况下，
 *    V2 侧必须通过字段内容推断信号类别，容易出错。
 *
 * ## 合约目标
 *
 * [AndroidDistributedTruthOwnershipUplinkContract] 基于现有真实已有代码引入：
 *
 * - [AuthoritySignalClass]：4 值枚举，声明上行信号的权威语义分类。
 *   明确区分权威运行时信号（AUTHORITY_RUNTIME）、ownership/handoff 相关信号
 *   （OWNERSHIP_HANDOFF）、摘要/投影信号（SUMMARY_PROJECTION）与诊断/审计信号（DIAGNOSTICS_AUDIT）。
 *
 * - [OwnershipUplinkClass]：5 值枚举，声明 Android 当前的 ownership 上行语义分类。
 *   明确区分 Android 持有执行权威（AUTHORITY_HELD）、发起 handoff（HANDOFF_INITIATOR）、
 *   参与 handoff（HANDOFF_PARTICIPANT）、归还 ownership（OWNERSHIP_RETURN）与无传递中
 *   ownership（NO_TRANSFER）。
 *
 * - [SessionContinuityClass]：4 值枚举，声明 Android 侧 session continuity 信号的语义分类。
 *   明确区分活跃权威会话（SESSION_LIVE_AUTHORITATIVE）、session 恢复中（SESSION_RECOVERY_PENDING）、
 *   session 连续性声明（SESSION_CONTINUATION）与无活跃会话（NO_ACTIVE_SESSION）。
 *
 * - [DevicePostureSignalClass]：5 值枚举，声明 Android 设备 posture 的语义分类。
 *   明确区分活跃运行时节点（RUNTIME_NODE_ACTIVE）、降级运行时节点（RUNTIME_NODE_DEGRADED）、
 *   恢复中运行时节点（RUNTIME_NODE_RECOVERING）、仅 posture 信号（POSTURE_SIGNAL_ONLY）与
 *   仅控制面节点（CONTROL_PLANE_ONLY）。
 *
 * - [TruthOwnershipUplinkDerivationInput]：汇聚来自现有合约的 12 个信号，无需新增探针。
 * - [derive]：按 5 级优先级推导 [TruthOwnershipUplinkSnapshot]。
 * - [TruthOwnershipUplinkSnapshot.toWireMap]：生成可嵌入上行消息的标准 wire map。
 * - [V2_CANONICAL_TRUTH_CONSUMPTION_PATH_MAP]：声明 Android 各信号类别在 V2 侧对应的消费路径。
 * - [DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS]：8 条形式化不变量，固定 Android distributed truth /
 *   ownership uplink 边界，防止越权或降权。
 *
 * ## 信号类别分类规则
 *
 * | [AuthoritySignalClass]      | 触发条件                                                             | V2 消费规范                                     |
 * |-----------------------------|----------------------------------------------------------------------|-------------------------------------------------|
 * | [AUTHORITY_RUNTIME]         | 真实执行完成的结果上行、RuntimeController 权威状态变更               | V2 MUST 以此作为 task closure / state 权威依据  |
 * | [OWNERSHIP_HANDOFF]         | handoff 参与、takeover 相关信号、ownership 转移声明                  | V2 MUST 纳入 handoff governance chain，MUST NOT 用于 result closure |
 * | [SUMMARY_PROJECTION]        | operator-visible 摘要、AndroidOperationalStateSurfaceContract 投影   | V2 MUST NOT 用于 dispatch scoring 或 authority 决策 |
 * | [DIAGNOSTICS_AUDIT]         | 诊断字段、audit trail、observability 上行                            | V2 MUST NOT 用于 runtime authority 判断         |
 *
 * ## Ownership uplink 分类规则
 *
 * | [OwnershipUplinkClass]      | 触发条件                                                              | 说明                                            |
 * |-----------------------------|-----------------------------------------------------------------------|-------------------------------------------------|
 * | [AUTHORITY_HELD]            | executionBusy + crossDeviceEnabled + join_runtime + 非 fallback       | Android 持有当前执行的 runtime authority        |
 * | [HANDOFF_INITIATOR]         | isHandoffInitiator + takeoverActive                                   | Android 主动发起 handoff/takeover               |
 * | [HANDOFF_PARTICIPANT]       | takeoverActive + 非 HANDOFF_INITIATOR                                 | Android 参与 handoff，但未完整终局               |
 * | [OWNERSHIP_RETURN]          | isOwnershipReturnPending                                              | Android 正在归还 ownership 给 V2               |
 * | [NO_TRANSFER]               | 默认/仅注册/非执行中                                                  | 无当前 ownership 传递动作                        |
 *
 * ## Session continuity 分类规则
 *
 * | [SessionContinuityClass]           | 触发条件                                                       | 说明                                     |
 * |------------------------------------|----------------------------------------------------------------|------------------------------------------|
 * | [SESSION_LIVE_AUTHORITATIVE]       | sessionId != null + executionBusy + 非 sessionRecovery        | 活跃会话且 Android 有执行权威             |
 * | [SESSION_RECOVERY_PENDING]         | isSessionRecoveryActive                                       | session 恢复中，V2 MUST 等待 recovery 完成 |
 * | [SESSION_CONTINUATION]             | sessionId != null + 非 executionBusy                          | session ID 连续性声明，但非执行权威       |
 * | [NO_ACTIVE_SESSION]                | sessionId == null                                             | 无活跃 session                           |
 *
 * ## Device posture 分类规则
 *
 * | [DevicePostureSignalClass]     | 触发条件                                                       | 说明                                           |
 * |-------------------------------|----------------------------------------------------------------|------------------------------------------------|
 * | [RUNTIME_NODE_ACTIVE]         | join_runtime + executionBusy + 非 degraded + 非 recovery       | Android 是活跃的强运行时节点                   |
 * | [RUNTIME_NODE_DEGRADED]       | isCapabilityDegraded                                          | Android 运行时能力降级，仍是运行时节点         |
 * | [RUNTIME_NODE_RECOVERING]     | isRecoveryActive                                              | Android 处于恢复中，仍是运行时节点             |
 * | [POSTURE_SIGNAL_ONLY]         | join_runtime + 非 executionBusy                               | Android 发出 posture 声明，无实际执行权威      |
 * | [CONTROL_PLANE_ONLY]          | control_only posture                                          | Android 仅作控制面节点，不参与执行池           |
 *
 * ## 与 V2 PR-09v2 的叙事对应关系
 *
 * | Android [AuthoritySignalClass]  | V2 canonical truth 链                                              |
 * |---------------------------------|--------------------------------------------------------------------|
 * | [AUTHORITY_RUNTIME]             | V2 core/task_result_canonical_truth_chain.py（权威结果消费）       |
 * | [OWNERSHIP_HANDOFF]             | V2 core/handoff_governance_chain.py（handoff 治理）                |
 * | [SUMMARY_PROJECTION]            | V2 board/operator_perception_surface.py（仅摘要展示）              |
 * | [DIAGNOSTICS_AUDIT]             | V2 core/android_device_state_store.py（诊断存储）                  |
 *
 * ## 与现有合约的关系
 *
 * | 现有合约                                                | 本合约的补充关系                                                    |
 * |--------------------------------------------------------|---------------------------------------------------------------------|
 * | [AndroidDistributedRuntimeParticipationBoundaryContract] | 本合约在 ParticipationBoundaryRole 基础上增加 AuthoritySignalClass  |
 * | [AndroidResultUplinkBoundaryContract]                  | 本合约声明信号类别边界；后者声明结果闭合语义                        |
 * | [AndroidOperationalStateSurfaceContract]               | 本合约明确将 operator-visible surface 标注为 SUMMARY_PROJECTION     |
 * | [AndroidUnifiedTruthUplinkContract]                    | 本合约收束 truth 信号类别边界；后者定义 truth 字段分类              |
 * | [AndroidTakeoverOwnershipTransferContract]             | 本合约声明 ownership uplink 边界；后者声明 takeover completion 语义 |
 *
 * @see AndroidDistributedRuntimeParticipationBoundaryContract
 * @see AndroidResultUplinkBoundaryContract
 * @see AndroidOperationalStateSurfaceContract
 * @see AndroidUnifiedTruthUplinkContract
 * @see AndroidTakeoverOwnershipTransferContract
 */
object AndroidDistributedTruthOwnershipUplinkContract {

    /** PR number that introduced this contract. */
    const val INTRODUCED_PR = 99

    /** Wire-map schema 版本。 */
    const val SCHEMA_VERSION = "1"

    // ── Wire 字段 key 常量 ─────────────────────────────────────────────────────

    /** Wire 字段：Android 上行信号的权威语义分类。 */
    const val KEY_AUTHORITY_SIGNAL_CLASS = "authority_signal_class"

    /** Wire 字段：Android 当前的 ownership 上行语义分类。 */
    const val KEY_OWNERSHIP_UPLINK_CLASS = "ownership_uplink_class"

    /** Wire 字段：Android 侧 session continuity 信号的语义分类。 */
    const val KEY_SESSION_CONTINUITY_CLASS = "session_continuity_class"

    /** Wire 字段：Android 设备 posture 的语义分类。 */
    const val KEY_DEVICE_POSTURE_SIGNAL_CLASS = "device_posture_signal_class"

    /** Wire 字段：合约 schema 版本。 */
    const val KEY_SCHEMA_VERSION = "distributed_truth_ownership_uplink_schema_version"

    // ── 权威信号分类枚举 ─────────────────────────────────────────────────────

    /**
     * Android 上行信号的权威语义分类。
     *
     * V2 canonical consumers MUST read [wireValue] from the wire payload and route
     * accordingly. They MUST NOT infer signal authority class from field combinations.
     */
    enum class AuthoritySignalClass(val wireValue: String) {
        /**
         * 权威运行时信号：Android 已完成真实执行并产生权威状态/结果。
         *
         * 触发条件：真实执行完成的结果上行、RuntimeController 权威状态变更、
         * executionBusy=true 的执行事件上行。
         * V2 MUST 以此作为 task closure / runtime state 的权威依据。
         */
        AUTHORITY_RUNTIME("authority_runtime"),

        /**
         * Ownership/handoff 相关信号：Android 参与 handoff 或 takeover 流程。
         *
         * 触发条件：handoff 参与、takeover 相关信号、ownership 转移声明。
         * V2 MUST 纳入 handoff governance chain；MUST NOT 将此信号用于 result closure
         * 或 runtime authority 判断。
         */
        OWNERSHIP_HANDOFF("ownership_handoff"),

        /**
         * 摘要/投影信号：Android 发出的 operator-visible 摘要或投影性状态。
         *
         * 触发条件：AndroidOperationalStateSurfaceContract 输出的 operator-visible 摘要、
         * device_state_snapshot 中的 projection-only 字段。
         * V2 MUST NOT 将此信号用于 dispatch scoring、ownership authority 或 result closure。
         */
        SUMMARY_PROJECTION("summary_projection"),

        /**
         * 诊断/审计信号：Android 发出的诊断、audit trail 或 observability 上行。
         *
         * 触发条件：诊断字段、audit trail、observability audit 上行（isDiagnosticsSignal=true）。
         * V2 MUST NOT 将此信号用于 runtime authority 判断或 dispatch scoring。
         */
        DIAGNOSTICS_AUDIT("diagnostics_audit");

        companion object {
            fun fromWireValue(value: String?): AuthoritySignalClass =
                entries.firstOrNull { it.wireValue == value } ?: DIAGNOSTICS_AUDIT
        }
    }

    // ── Ownership uplink 语义枚举 ──────────────────────────────────────────────

    /**
     * Android 当前的 ownership 上行语义分类。
     *
     * ownership uplink 声明 Android 在当前上报时刻的 ownership 动作或状态。
     * V2 MUST NOT 将任何 ownership uplink class 直接等同于"V2 已失去 authority"。
     */
    enum class OwnershipUplinkClass(val wireValue: String) {
        /**
         * Android 持有当前执行的 runtime authority。
         *
         * 触发条件：executionBusy + crossDeviceEnabled + join_runtime + 非 fallback。
         * V2 可将 Android 当前上行的结果纳入 task closure 考量。
         */
        AUTHORITY_HELD("authority_held"),

        /**
         * Android 主动发起 handoff/takeover。
         *
         * 触发条件：isHandoffInitiator=true + takeoverActive=true。
         * V2 MUST 开始 handoff governance 流程，MUST NOT 宣称 ownership 已完整转移。
         */
        HANDOFF_INITIATOR("handoff_initiator"),

        /**
         * Android 参与 handoff 流程，但非发起方且未完成完整接管。
         *
         * 触发条件：takeoverActive=true + 非 isHandoffInitiator。
         * V2 MUST 将 Android 纳入 handoff chain，MUST NOT 宣称 ownership 已完整转移。
         */
        HANDOFF_PARTICIPANT("handoff_participant"),

        /**
         * Android 正在归还 ownership 给 V2。
         *
         * 触发条件：isOwnershipReturnPending=true。
         * V2 MUST 等待 ownership return 完成信号，MUST NOT 提前宣称 Android 已不可用。
         */
        OWNERSHIP_RETURN("ownership_return"),

        /**
         * 无当前 ownership 传递动作（默认/仅注册/非执行中）。
         *
         * 触发条件：未执行任务（非 executionBusy）且无 handoff/takeover 活动。
         * V2 可将 Android 视为待机的运行时节点。
         */
        NO_TRANSFER("no_transfer");

        companion object {
            fun fromWireValue(value: String?): OwnershipUplinkClass =
                entries.firstOrNull { it.wireValue == value } ?: NO_TRANSFER
        }
    }

    // ── Session continuity 语义枚举 ──────────────────────────────────────────────

    /**
     * Android 侧 session continuity 信号的语义分类。
     *
     * session continuity class 区分活跃权威会话、session 恢复中的临时状态、
     * session ID 连续性声明与无活跃会话，防止 V2 在 session 恢复过程中消费 stale 信号。
     */
    enum class SessionContinuityClass(val wireValue: String) {
        /**
         * 活跃权威会话：Android 有活跃 session 且持有执行权威。
         *
         * 触发条件：sessionId != null + executionBusy + 非 sessionRecovery。
         * V2 可将此 session 的 Android 上行纳入 session-scoped result 消费。
         */
        SESSION_LIVE_AUTHORITATIVE("session_live_authoritative"),

        /**
         * Session 恢复中：Android 的 session 正在进行恢复（reconnect/recovery）。
         *
         * 触发条件：isSessionRecoveryActive=true。
         * V2 MUST 等待 recovery 完成信号，MUST NOT 依据恢复中的 session 信号做 authority 判断。
         */
        SESSION_RECOVERY_PENDING("session_recovery_pending"),

        /**
         * Session 连续性声明：Android 有活跃 session ID，但当前无执行权威。
         *
         * 触发条件：sessionId != null + 非 executionBusy。
         * V2 可记录 session 连续性，MUST NOT 将此信号用于 task closure。
         */
        SESSION_CONTINUATION("session_continuation"),

        /**
         * 无活跃会话：Android 当前无活跃 session ID。
         *
         * 触发条件：sessionId == null。
         * V2 MUST 将此状态视为 Android 当前未建立会话，不可用于 session-scoped 消费。
         */
        NO_ACTIVE_SESSION("no_active_session");

        companion object {
            fun fromWireValue(value: String?): SessionContinuityClass =
                entries.firstOrNull { it.wireValue == value } ?: NO_ACTIVE_SESSION
        }
    }

    // ── Device posture 语义枚举 ─────────────────────────────────────────────────

    /**
     * Android 设备 posture 的语义分类。
     *
     * device posture class 明确 Android 是否是强运行时节点，防止 V2 将 Android 降格为
     * 普通 transport/reporting 端，或将 posture 信号误抬为 runtime authority。
     */
    enum class DevicePostureSignalClass(val wireValue: String) {
        /**
         * 活跃运行时节点：Android 是活跃的强运行时节点，具备完整 distributed execution 能力。
         *
         * 触发条件：join_runtime + executionBusy + 非 degraded + 非 recovery + crossDeviceEnabled。
         * V2 MUST 将 Android 视为分布式运行中的有效执行节点。
         */
        RUNTIME_NODE_ACTIVE("runtime_node_active"),

        /**
         * 降级运行时节点：Android 运行时能力降级，但仍是运行时节点。
         *
         * 触发条件：isCapabilityDegraded=true。
         * V2 MUST 将 Android 视为临时降级的运行时节点，不是永久不可用；
         * Android 在能力恢复后可重新参与分布式执行。
         */
        RUNTIME_NODE_DEGRADED("runtime_node_degraded"),

        /**
         * 恢复中运行时节点：Android 处于恢复中，仍是运行时节点。
         *
         * 触发条件：isRecoveryActive=true。
         * V2 MUST 等待 Android 恢复完成，不应将恢复中的 Android 从 runtime pool 中移除。
         */
        RUNTIME_NODE_RECOVERING("runtime_node_recovering"),

        /**
         * 仅 posture 信号：Android 发出 posture 声明，但当前无实际执行权威。
         *
         * 触发条件：join_runtime + 非 executionBusy + crossDeviceEnabled。
         * V2 MAY 将 Android 视为待机的运行时节点，可分配新任务。
         */
        POSTURE_SIGNAL_ONLY("posture_signal_only"),

        /**
         * 仅控制面节点：Android 仅作控制面角色，不参与执行池。
         *
         * 触发条件：control_only posture（非 join_runtime）。
         * V2 MUST NOT 向 control_only 的 Android 分配 distributed execution 子任务。
         */
        CONTROL_PLANE_ONLY("control_plane_only");

        companion object {
            fun fromWireValue(value: String?): DevicePostureSignalClass =
                entries.firstOrNull { it.wireValue == value } ?: POSTURE_SIGNAL_ONLY
        }
    }

    // ── 推导输入 ─────────────────────────────────────────────────────────────

    /**
     * 推导 [TruthOwnershipUplinkSnapshot] 所需的 12 个输入信号。
     *
     * 所有字段来自现有已计算的运行时信号，无需新增探针。
     *
     * @param executionBusy            Android 当前是否存在活跃的执行任务。
     * @param crossDeviceEnabled       AppSettings.crossDeviceEnabled。
     * @param sourceRuntimePosture     握手时声明的 source_runtime_posture wire 值
     *                                 （[SourceRuntimePosture.JOIN_RUNTIME] 或 [SourceRuntimePosture.CONTROL_ONLY]）。
     * @param takeoverActive           当前是否有活跃的 takeover（activeTakeoverId != null）。
     * @param isHandoffInitiator       Android 是否是当前 handoff 的发起方。
     * @param isOwnershipReturnPending Android 是否正在归还 ownership（takeover 完成后的回归流程）。
     * @param sessionId                当前 runtime session ID（null 表示无活跃 session）。
     * @param isSessionRecoveryActive  当前是否处于 session 恢复流程（reconnect/recovery）。
     * @param isCapabilityDegraded     Android 本地推理能力是否降级。
     * @param isRecoveryActive         Android 当前是否处于 runtime recovery 流程。
     * @param isDiagnosticsSignal      当前上行信号是否为纯诊断性（如 observability audit、
     *                                 audit trail 记录）。
     * @param isOperatorVisibleSummary 当前上行信号是否为 operator-visible 摘要/投影（如
     *                                 AndroidOperationalStateSurfaceContract 的输出）。
     */
    data class TruthOwnershipUplinkDerivationInput(
        val executionBusy: Boolean,
        val crossDeviceEnabled: Boolean,
        val sourceRuntimePosture: String,
        val takeoverActive: Boolean,
        val isHandoffInitiator: Boolean,
        val isOwnershipReturnPending: Boolean,
        val sessionId: String?,
        val isSessionRecoveryActive: Boolean,
        val isCapabilityDegraded: Boolean,
        val isRecoveryActive: Boolean,
        val isDiagnosticsSignal: Boolean,
        val isOperatorVisibleSummary: Boolean
    )

    // ── 推导快照 ─────────────────────────────────────────────────────────────

    /**
     * 推导结果快照，包含四个维度的分类结果和 schema 版本。
     */
    data class TruthOwnershipUplinkSnapshot(
        val authoritySignalClass: AuthoritySignalClass,
        val ownershipUplinkClass: OwnershipUplinkClass,
        val sessionContinuityClass: SessionContinuityClass,
        val devicePostureSignalClass: DevicePostureSignalClass,
        val schemaVersion: String = SCHEMA_VERSION
    ) {
        /**
         * 生成可嵌入上行消息的 wire map。
         */
        fun toWireMap(): Map<String, String> = mapOf(
            KEY_AUTHORITY_SIGNAL_CLASS to authoritySignalClass.wireValue,
            KEY_OWNERSHIP_UPLINK_CLASS to ownershipUplinkClass.wireValue,
            KEY_SESSION_CONTINUITY_CLASS to sessionContinuityClass.wireValue,
            KEY_DEVICE_POSTURE_SIGNAL_CLASS to devicePostureSignalClass.wireValue,
            KEY_SCHEMA_VERSION to schemaVersion
        )
    }

    // ── 推导函数 ─────────────────────────────────────────────────────────────

    /**
     * 按 5 级优先级推导 [TruthOwnershipUplinkSnapshot]。
     *
     * 推导规则（优先级从高到低）：
     *
     * 1. **诊断/摘要信号**：isDiagnosticsSignal=true 或 isOperatorVisibleSummary=true
     *    → 对应 DIAGNOSTICS_AUDIT / SUMMARY_PROJECTION。
     * 2. **Ownership/handoff 相关**：takeoverActive=true 或 isOwnershipReturnPending=true
     *    → OWNERSHIP_HANDOFF + 对应 OwnershipUplinkClass。
     * 3. **权威运行时信号**：executionBusy=true + join_runtime + crossDeviceEnabled
     *    → AUTHORITY_RUNTIME + AUTHORITY_HELD。
     * 4. **Session continuity 细化**：基于 sessionId 和 isSessionRecoveryActive 推导。
     * 5. **默认/待机/posture 信号**：未执行任务时的默认分类。
     */
    fun derive(input: TruthOwnershipUplinkDerivationInput): TruthOwnershipUplinkSnapshot {
        val isJoinRuntime = SourceRuntimePosture.isJoinRuntime(input.sourceRuntimePosture)

        // ── 优先级 1a: 纯诊断信号 ────────────────────────────────────────────
        if (input.isDiagnosticsSignal) {
            return TruthOwnershipUplinkSnapshot(
                authoritySignalClass = AuthoritySignalClass.DIAGNOSTICS_AUDIT,
                ownershipUplinkClass = OwnershipUplinkClass.NO_TRANSFER,
                sessionContinuityClass = deriveSessionContinuity(input),
                devicePostureSignalClass = deriveDevicePosture(input, isJoinRuntime)
            )
        }

        // ── 优先级 1b: Operator-visible 摘要/投影信号 ───────────────────────
        if (input.isOperatorVisibleSummary) {
            return TruthOwnershipUplinkSnapshot(
                authoritySignalClass = AuthoritySignalClass.SUMMARY_PROJECTION,
                ownershipUplinkClass = OwnershipUplinkClass.NO_TRANSFER,
                sessionContinuityClass = deriveSessionContinuity(input),
                devicePostureSignalClass = deriveDevicePosture(input, isJoinRuntime)
            )
        }

        // ── 优先级 2a: Ownership 归还中 ────────────────────────────────────
        if (input.isOwnershipReturnPending) {
            return TruthOwnershipUplinkSnapshot(
                authoritySignalClass = AuthoritySignalClass.OWNERSHIP_HANDOFF,
                ownershipUplinkClass = OwnershipUplinkClass.OWNERSHIP_RETURN,
                sessionContinuityClass = deriveSessionContinuity(input),
                devicePostureSignalClass = deriveDevicePosture(input, isJoinRuntime)
            )
        }

        // ── 优先级 2b: Handoff / takeover 相关 ─────────────────────────────
        if (input.takeoverActive) {
            val ownershipClass = when {
                input.isHandoffInitiator -> OwnershipUplinkClass.HANDOFF_INITIATOR
                else -> OwnershipUplinkClass.HANDOFF_PARTICIPANT
            }
            return TruthOwnershipUplinkSnapshot(
                authoritySignalClass = AuthoritySignalClass.OWNERSHIP_HANDOFF,
                ownershipUplinkClass = ownershipClass,
                sessionContinuityClass = deriveSessionContinuity(input),
                devicePostureSignalClass = deriveDevicePosture(input, isJoinRuntime)
            )
        }

        // ── 优先级 3: 权威运行时信号 ─────────────────────────────────────────
        if (input.executionBusy && isJoinRuntime && input.crossDeviceEnabled) {
            return TruthOwnershipUplinkSnapshot(
                authoritySignalClass = AuthoritySignalClass.AUTHORITY_RUNTIME,
                ownershipUplinkClass = OwnershipUplinkClass.AUTHORITY_HELD,
                sessionContinuityClass = deriveSessionContinuity(input),
                devicePostureSignalClass = deriveDevicePosture(input, isJoinRuntime)
            )
        }

        // ── 优先级 4/5: 默认/待机/posture 信号 ──────────────────────────────
        val defaultAuthorityClass = when {
            input.executionBusy -> AuthoritySignalClass.AUTHORITY_RUNTIME
            else -> AuthoritySignalClass.OWNERSHIP_HANDOFF
        }
        return TruthOwnershipUplinkSnapshot(
            authoritySignalClass = defaultAuthorityClass,
            ownershipUplinkClass = OwnershipUplinkClass.NO_TRANSFER,
            sessionContinuityClass = deriveSessionContinuity(input),
            devicePostureSignalClass = deriveDevicePosture(input, isJoinRuntime)
        )
    }

    // ── 内部辅助推导函数 ─────────────────────────────────────────────────────

    private fun deriveSessionContinuity(
        input: TruthOwnershipUplinkDerivationInput
    ): SessionContinuityClass = when {
        input.sessionId == null ->
            SessionContinuityClass.NO_ACTIVE_SESSION
        input.isSessionRecoveryActive ->
            SessionContinuityClass.SESSION_RECOVERY_PENDING
        input.executionBusy ->
            SessionContinuityClass.SESSION_LIVE_AUTHORITATIVE
        else ->
            SessionContinuityClass.SESSION_CONTINUATION
    }

    private fun deriveDevicePosture(
        input: TruthOwnershipUplinkDerivationInput,
        isJoinRuntime: Boolean
    ): DevicePostureSignalClass = when {
        !isJoinRuntime ->
            DevicePostureSignalClass.CONTROL_PLANE_ONLY
        input.isCapabilityDegraded ->
            DevicePostureSignalClass.RUNTIME_NODE_DEGRADED
        input.isRecoveryActive ->
            DevicePostureSignalClass.RUNTIME_NODE_RECOVERING
        input.executionBusy && input.crossDeviceEnabled ->
            DevicePostureSignalClass.RUNTIME_NODE_ACTIVE
        else ->
            DevicePostureSignalClass.POSTURE_SIGNAL_ONLY
    }

    // ── V2 canonical truth 消费路径映射 ─────────────────────────────────────

    /**
     * Android 各 authority signal class 在 V2 侧对应的 canonical truth 消费路径。
     *
     * V2 canonical consumers MUST route uplink payloads according to
     * [authoritySignalClass] rather than re-deriving signal authority from field combinations.
     */
    val V2_CANONICAL_TRUTH_CONSUMPTION_PATH_MAP: Map<AuthoritySignalClass, String> = mapOf(
        AuthoritySignalClass.AUTHORITY_RUNTIME to
            "core/task_result_canonical_truth_chain.py（权威运行时结果消费，" +
            "MUST 以此作为 task closure / state 权威依据）",
        AuthoritySignalClass.OWNERSHIP_HANDOFF to
            "core/handoff_governance_chain.py（handoff / ownership 治理，" +
            "MUST NOT 用于 result closure 或 runtime authority 单独判断）",
        AuthoritySignalClass.SUMMARY_PROJECTION to
            "board/operator_perception_surface.py（operator-visible 摘要展示，" +
            "MUST NOT 用于 dispatch scoring 或 authority 决策）",
        AuthoritySignalClass.DIAGNOSTICS_AUDIT to
            "core/android_device_state_store.py（诊断/审计存储，" +
            "MUST NOT 用于 runtime authority 判断）"
    )

    // ── 形式化不变量 ─────────────────────────────────────────────────────────

    /**
     * Android 侧分布式真值与 ownership uplink 边界的 8 条形式化不变量。
     *
     * V2 canonical consumers MUST NOT violate these invariants when consuming
     * Android uplink payloads.
     */
    val DISTRIBUTED_TRUTH_OWNERSHIP_INVARIANTS: List<String> = listOf(
        // INV-1: authority_signal_class 由 derive() 唯一推导，V2 MUST NOT 从字段组合推断。
        "INV-1: authority_signal_class MUST be derived by Android via derive() and emitted " +
            "in wire payloads; V2 MUST read this field directly, MUST NOT re-derive from " +
            "field combinations such as execution_busy + distributed_participant.",

        // INV-2: SUMMARY_PROJECTION 不是 runtime authority truth。
        "INV-2: authority_signal_class == summary_projection indicates an operator-visible " +
            "summary or projection surface. V2 MUST NOT use this signal as the authoritative " +
            "basis for dispatch scoring, ownership determination, or result closure.",

        // INV-3: DIAGNOSTICS_AUDIT 不是 runtime authority truth。
        "INV-3: authority_signal_class == diagnostics_audit indicates a diagnostics or audit " +
            "trail signal. V2 MUST NOT use this signal for runtime authority decisions.",

        // INV-4: OWNERSHIP_HANDOFF 不等于 ownership 已完整转移。
        "INV-4: authority_signal_class == ownership_handoff with ownership_uplink_class == " +
            "handoff_participant does NOT mean complete ownership transfer has been established. " +
            "V2 MUST NOT declare takeover_complete based on handoff_participant alone.",

        // INV-5: SESSION_RECOVERY_PENDING 期间 V2 MUST 等待 recovery 完成。
        "INV-5: session_continuity_class == session_recovery_pending indicates Android session " +
            "is in recovery. V2 MUST NOT make authority decisions based on signals emitted " +
            "during session recovery; V2 MUST wait for SESSION_LIVE_AUTHORITATIVE or " +
            "NO_ACTIVE_SESSION before resuming authority consumption.",

        // INV-6: CONTROL_PLANE_ONLY 的 Android 不可分配分布式执行子任务。
        "INV-6: device_posture_signal_class == control_plane_only means Android declared " +
            "control_only posture. V2 MUST NOT assign distributed execution subtasks to " +
            "an Android node in control_plane_only posture.",

        // INV-7: Android 降级或恢复中时 V2 MUST NOT 将其从 runtime pool 中永久移除。
        "INV-7: device_posture_signal_class == runtime_node_degraded or runtime_node_recovering " +
            "indicates temporary state. V2 MUST NOT permanently remove Android from the " +
            "distributed runtime pool based on these signals alone; Android may recover.",

        // INV-8: Android 仍是强运行时节点，不是普通 transport/reporting 端。
        "INV-8: Android is a runtime node (authority_signal_class == authority_runtime is " +
            "valid). V2 MUST NOT treat Android as a generic transport/reporting endpoint. " +
            "The presence of AUTHORITY_RUNTIME signals means Android has genuine distributed " +
            "execution authority that V2 canonical truth chain MUST consume."
    )
}
