package com.ufo.galaxy.runtime

/**
 * PR-08v2 (Android) — Android 侧分布式运行参与边界收束合约。
 *
 * 本合约是 V2 仓库 PR-08v2 的 Android 侧联动 PR，对应 V2 侧对
 * distributed runtime participation、ownership posture、remote/local execution handoff、
 * session continuity、cross-device runtime mode、authority transition、execution fallback
 * 相关结构的边界收束。
 *
 * ## 解决的问题
 *
 * 在本合约引入之前，Android 侧的分布式运行参与存在以下边界模糊问题：
 *
 * 1. **participation 与 ownership posture 混淆**：Android 的 ownership posture（通过握手
 *    `source_runtime_posture` 字段传达的 join_runtime / control_only 声明）与 Android 在
 *    实际分布式运行中的参与角色（DISTRIBUTED_RUNTIME_PARTICIPANT）没有被机器可读地区分，
 *    导致 V2 侧消费者可能将 posture 信号错误地视为运行时权威真相。
 *
 * 2. **remote/local execution mode 与 fallback 边界不清**：Android 的本地执行模式
 *    （LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY）和分布式执行回退
 *    （FALLBACK_LOCAL）没有被统一分类，V2 无法直接区分"Android 主动声明本地模式"
 *    与"Android 因 fallback 转入本地执行"这两种语义完全不同的状态。
 *
 * 3. **operator-visible mode summary 被误当 runtime authority truth**：Android 的
 *    AndroidOperationalStateSurfaceContract 提供的 operator-visible 摘要是 projection-only
 *    可见面，不是 runtime authority truth。但在没有机器可读声明的情况下，V2 消费者
 *    可能将 `operational_surface_states` 误用为 dispatch scoring 的权威依据。
 *
 * 4. **handoff 参与与完整 takeover 完成混淆**：Android 侧的 handoff participation
 *    （HANDOFF_PARTICIPANT）与"完整双向接管已成立"（complete bidirectional takeover）
 *    没有被明确区分，存在语义夸大风险。
 *
 * ## 合约目标
 *
 * [AndroidDistributedRuntimeParticipationBoundaryContract] 基于现有真实已有代码引入：
 *
 * - [ParticipationBoundaryRole]：5 值枚举，声明上行信号的参与边界角色分类。
 *   明确区分 Android 作为分布式运行参与者（DISTRIBUTED_RUNTIME_PARTICIPANT）、
 *   ownership posture 信号（OWNERSHIP_POSTURE_SIGNAL）、远程/本地模式及回退角色
 *   （REMOTE_LOCAL_MODE_FALLBACK）与仅作诊断/摘要用途（DIAGNOSTICS_SUMMARY_ONLY）。
 * - [OwnershipPostureClass]：4 值枚举，声明 Android 当前的 ownership posture 语义分类。
 *   明确区分 runtime host executor（实际执行中）、control initiator（纯控制发起方）、
 *   handoff participant（参与 handoff 但未完成完整接管）与 posture signal only（仅信号态）。
 * - [RemoteLocalModeClass]：4 值枚举，声明 Android 当前的 remote/local 执行模式分类。
 *   区分分布式执行（DISTRIBUTED_EXECUTING）、本地模式主动声明（LOCAL_ONLY_DECLARED）、
 *   fallback 转入本地（FALLBACK_LOCAL）与降级回退（DEGRADED_FALLBACK）。
 * - [ParticipationBoundaryDerivationInput]：汇聚来自现有合约的 8 个信号，无需新增探针。
 * - [derive]：按 5 级优先级推导 [ParticipationBoundarySnapshot]。
 * - [ParticipationBoundarySnapshot.toWireMap]：生成可嵌入上行消息的标准 wire map。
 * - [V2_OWNERSHIP_GOVERNANCE_PATH_MAP]：声明 Android 各参与角色在 V2 侧对应的消费路径。
 * - [PARTICIPATION_BOUNDARY_INVARIANTS]：7 条形式化不变量，防止模式/姿态/摘要被误解。
 *
 * ## 参与边界角色分类规则
 *
 * | [ParticipationBoundaryRole]        | 触发条件                                                            | V2 消费规范                                 |
 * |------------------------------------|---------------------------------------------------------------------|---------------------------------------------|
 * | [DISTRIBUTED_RUNTIME_PARTICIPANT]  | sourceRuntimePosture=join_runtime + executionBusy=true             | V2 可将 Android 纳入分布式运行结果合并链     |
 * | [OWNERSHIP_POSTURE_SIGNAL]         | sourceRuntimePosture 字段在握手时声明，非执行中实际角色            | V2 MAY 用于 dispatch routing，MUST NOT 用于 authority closure |
 * | [REMOTE_LOCAL_MODE_FALLBACK]       | executionModeState=LOCAL_ONLY 或 fallback 转入本地                 | V2 MUST 认为 Android 不在分布式运行池中      |
 * | [HANDOFF_PARTICIPANT]              | takeoverActive=true 但非完整双向接管终局                           | V2 MUST NOT 宣称完整接管已成立              |
 * | [DIAGNOSTICS_SUMMARY_ONLY]         | operator-visible surface、projection、device_state_snapshot 诊断性 | V2 MUST NOT 用于 dispatch scoring 或结果关闭 |
 *
 * ## Ownership posture 分类规则
 *
 * | [OwnershipPostureClass]    | 触发条件                                              | 说明                                           |
 * |---------------------------|-------------------------------------------------------|------------------------------------------------|
 * | [RUNTIME_HOST_EXECUTOR]   | join_runtime + executionBusy + 非 fallback             | Android 真实执行中，是 runtime host            |
 * | [CONTROL_INITIATOR]       | control_only posture                                  | Android 仅发起控制，不参与执行池               |
 * | [HANDOFF_PARTICIPANT]     | join_runtime + takeoverActive + 非完整接管            | Android 参与 handoff，但接管尚未终局           |
 * | [POSTURE_SIGNAL_ONLY]     | 空闲/非执行中/仅注册态                               | posture 仅是信号，无实际 runtime 角色          |
 *
 * ## Remote/local mode 分类规则
 *
 * | [RemoteLocalModeClass]     | 触发条件                                                        | 说明                                      |
 * |----------------------------|-----------------------------------------------------------------|-------------------------------------------|
 * | [DISTRIBUTED_EXECUTING]    | crossDeviceEnabled + executionBusy + 非 LOCAL_ONLY mode        | Android 正在分布式执行                    |
 * | [LOCAL_ONLY_DECLARED]      | executionModeState == LOCAL_ONLY（主动声明）                   | Android 主动声明本地模式，非 fallback     |
 * | [FALLBACK_LOCAL]           | fallback tier active（fallback 转入本地）                       | Android 因 fallback 转为本地，非主动声明  |
 * | [DEGRADED_FALLBACK]        | capability degraded + fallback                                  | Android 因降级转入 fallback 本地执行      |
 *
 * ## 与 V2 PR-08v2 的叙事对应关系
 *
 * | Android [ParticipationBoundaryRole]    | V2 ownership / handoff 语义                              |
 * |----------------------------------------|----------------------------------------------------------|
 * | [DISTRIBUTED_RUNTIME_PARTICIPANT]      | Android 是 V2 distributed runtime 中的真实执行节点       |
 * | [OWNERSHIP_POSTURE_SIGNAL]             | Android 发出 ownership signal，V2 governance 消费        |
 * | [REMOTE_LOCAL_MODE_FALLBACK]           | Android 退出分布式池，V2 不分配新任务                    |
 * | [HANDOFF_PARTICIPANT]                  | Android 参与 handoff chain，但非完整接管终局             |
 * | [DIAGNOSTICS_SUMMARY_ONLY]            | Android 发出诊断摘要，V2 仅记录不用于 authority 决策     |
 *
 * ## 与现有合约的关系
 *
 * | 现有合约 | 本合约的补充关系 |
 * |---------|----------------|
 * | [AndroidParticipationSemanticNormalizationContract] | 本合约在 ParticipationModeClass 基础上增加边界角色分类，使 V2 可直接区分 participation 类型 |
 * | [AndroidResultUplinkBoundaryContract] | 本合约声明信号的参与边界角色；后者声明信号的结果闭合语义 |
 * | [AndroidCrossDeviceDispatchBoundaryContract] | 本合约声明 ownership posture；后者声明 dispatch 边界类型 |
 * | [LocalExecutionModeGate] | 本合约消费 execution_mode_state 并将其分类为 RemoteLocalModeClass |
 * | [AndroidOperationalStateSurfaceContract] | 本合约明确将 operator-visible surface 标注为 DIAGNOSTICS_SUMMARY_ONLY |
 * | [AndroidMinimalRuntimeAccessChainContract] | 本合约声明各接入层在 participation boundary 中的角色 |
 *
 * @see AndroidParticipationSemanticNormalizationContract
 * @see AndroidResultUplinkBoundaryContract
 * @see AndroidCrossDeviceDispatchBoundaryContract
 * @see LocalExecutionModeGate
 * @see AndroidOperationalStateSurfaceContract
 */
object AndroidDistributedRuntimeParticipationBoundaryContract {

    /** PR number that introduced this contract. */
    const val INTRODUCED_PR = 98

    /** Wire-map schema 版本。 */
    const val SCHEMA_VERSION = "1"

    // ── Wire 字段 key 常量 ─────────────────────────────────────────────────────

    /** Wire 字段：Android 上行信号的参与边界角色分类。 */
    const val KEY_PARTICIPATION_BOUNDARY_ROLE = "participation_boundary_role"

    /** Wire 字段：Android 当前的 ownership posture 语义分类。 */
    const val KEY_OWNERSHIP_POSTURE_CLASS = "ownership_posture_class"

    /** Wire 字段：Android 当前的 remote/local 执行模式分类。 */
    const val KEY_REMOTE_LOCAL_MODE_CLASS = "remote_local_mode_class"

    /** Wire 字段：合约 schema 版本。 */
    const val KEY_SCHEMA_VERSION = "participation_boundary_schema_version"

    // ── 参与边界角色枚举 ─────────────────────────────────────────────────────

    /**
     * Android 上行信号的参与边界角色分类。
     *
     * V2 canonical consumers MUST read [wireValue] from the wire payload and route
     * accordingly. They MUST NOT derive participation boundary semantics from field
     * combinations across multiple payloads.
     */
    enum class ParticipationBoundaryRole(val wireValue: String) {
        /**
         * Android 是分布式运行中的真实参与节点（正在执行任务）。
         *
         * 触发条件：source_runtime_posture=join_runtime + execution_busy=true。
         * V2 可将 Android 纳入分布式运行结果合并链。
         */
        DISTRIBUTED_RUNTIME_PARTICIPANT("distributed_runtime_participant"),

        /**
         * Android 发出的是 ownership posture 信号（握手声明），而非实际执行角色。
         *
         * 触发条件：注册/握手时 source_runtime_posture 字段声明，非执行中。
         * V2 MAY 用于 dispatch routing；MUST NOT 用于 authority closure 或 takeover 终局判断。
         */
        OWNERSHIP_POSTURE_SIGNAL("ownership_posture_signal"),

        /**
         * Android 处于远程/本地执行模式切换或 fallback 状态。
         *
         * 触发条件：executionModeState=LOCAL_ONLY 或 fallback tier 激活。
         * V2 MUST 认为 Android 当前不在分布式运行池中，MUST NOT 分配新的分布式任务。
         */
        REMOTE_LOCAL_MODE_FALLBACK("remote_local_mode_fallback"),

        /**
         * Android 参与 handoff 流程，但尚未完成完整双向接管终局。
         *
         * 触发条件：takeoverActive=true 但 takeover 未完全终局（非 TAKEOVER_COMPLETE）。
         * V2 MUST NOT 宣称完整双向接管已成立；Android 处于接管参与者角色而非完整接管中心。
         */
        HANDOFF_PARTICIPANT("handoff_participant"),

        /**
         * 当前上行信号仅为诊断/摘要用途，不参与 runtime 决策。
         *
         * 触发条件：operator-visible surface、projection 摘要、纯诊断性 device_state_snapshot。
         * V2 MUST NOT 将此信号用于 dispatch scoring、ownership authority 或结果关闭。
         */
        DIAGNOSTICS_SUMMARY_ONLY("diagnostics_summary_only")
    }

    // ── Ownership posture 语义枚举 ──────────────────────────────────────────────

    /**
     * Android 当前的 ownership posture 语义分类。
     *
     * ownership posture 是 Android 在握手和状态上报时对其在分布式运行中角色的声明。
     * 它是一个信号，不是 runtime authority truth。V2 MUST NOT 将 ownership posture
     * 误解为"Android 已经完成完整接管"的权威证据。
     */
    enum class OwnershipPostureClass(val wireValue: String) {
        /**
         * Android 是 runtime host executor：正在参与分布式运行并执行任务。
         *
         * 触发条件：sourceRuntimePosture=join_runtime + executionBusy + 非 fallback。
         * 这是 Android 真实执行中的 ownership posture，V2 可用于结果关联。
         */
        RUNTIME_HOST_EXECUTOR("runtime_host_executor"),

        /**
         * Android 是 control initiator：仅发起控制指令，不参与执行池。
         *
         * 触发条件：sourceRuntimePosture=control_only。
         * V2 MUST NOT 向 control_only 的 Android 分配分布式子任务。
         */
        CONTROL_INITIATOR("control_initiator"),

        /**
         * Android 参与 handoff：参与 handoff 流程，但接管尚未终局。
         *
         * 触发条件：sourceRuntimePosture=join_runtime + takeoverActive。
         * handoff participation ≠ 完整双向接管已成立。
         */
        HANDOFF_PARTICIPANT("handoff_participant"),

        /**
         * Android 的 posture 仅为信号态：空闲、未执行中或仅注册态。
         *
         * 触发条件：未执行任务（非 executionBusy）。
         * posture 字段存在但无实际运行时执行角色，V2 MUST NOT 用于权威判断。
         */
        POSTURE_SIGNAL_ONLY("posture_signal_only")
    }

    // ── Remote/local execution mode 枚举 ────────────────────────────────────────

    /**
     * Android 当前的 remote/local 执行模式分类。
     *
     * 本分类区分 Android 主动声明的本地模式与因 fallback 转入本地的状态，
     * 防止 V2 将两种语义不同的状态当作同一种处理。
     */
    enum class RemoteLocalModeClass(val wireValue: String) {
        /**
         * Android 正在分布式执行：crossDeviceEnabled + executionBusy + 非 LOCAL_ONLY。
         *
         * V2 可将 Android 纳入分布式运行结果合并。
         */
        DISTRIBUTED_EXECUTING("distributed_executing"),

        /**
         * Android 主动声明本地模式：executionModeState=LOCAL_ONLY（非 fallback 触发）。
         *
         * 这是 Android 通过 LocalExecutionModeGate 主动切换到本地模式，不是 fallback。
         * V2 MUST 区分此状态与 FALLBACK_LOCAL：主动声明表示策略性选择，不是能力故障。
         */
        LOCAL_ONLY_DECLARED("local_only_declared"),

        /**
         * Android 因 fallback 转入本地执行：fallback tier 激活（非主动 LOCAL_ONLY 声明）。
         *
         * V2 MUST NOT 将此状态视为 Android 不可用；这是 fallback 机制，
         * Android 在 fallback 解除后可重新加入分布式运行。
         */
        FALLBACK_LOCAL("fallback_local"),

        /**
         * Android 因能力降级转入 fallback 本地执行。
         *
         * 触发条件：capability degraded + fallback tier active。
         * V2 MUST 将此状态视为临时降级，不是永久离开分布式运行。
         */
        DEGRADED_FALLBACK("degraded_fallback")
    }

    // ── 推导输入 ─────────────────────────────────────────────────────────────

    /**
     * 推导 [ParticipationBoundarySnapshot] 所需的 8 个输入信号。
     *
     * 所有字段来自现有已计算的运行时信号，无需新增探针。
     *
     * @param sourceRuntimePosture     握手时声明的 source_runtime_posture wire 值
     *                                 （[SourceRuntimePosture.JOIN_RUNTIME] 或 [SourceRuntimePosture.CONTROL_ONLY]）。
     * @param executionBusy            Android 当前是否存在活跃的执行任务。
     * @param executionModeStateWire   [LocalExecutionModeGate.ExecutionModeState] wire 值。
     * @param crossDeviceEnabled       AppSettings.crossDeviceEnabled。
     * @param isFallbackTierActive     当前 fallback tier 是否激活（非 "center_delegated" 且非 "no_execution"）。
     * @param isCapabilityDegraded     Android 本地推理能力是否降级。
     * @param takeoverActive           当前是否有活跃的 takeover（activeTakeoverId != null）。
     * @param isDiagnosticsSignal      当前上行信号是否为纯诊断性（如 device_state_snapshot 或 projection-only）。
     */
    data class ParticipationBoundaryDerivationInput(
        val sourceRuntimePosture: String,
        val executionBusy: Boolean,
        val executionModeStateWire: String?,
        val crossDeviceEnabled: Boolean,
        val isFallbackTierActive: Boolean,
        val isCapabilityDegraded: Boolean,
        val takeoverActive: Boolean,
        val isDiagnosticsSignal: Boolean
    )

    // ── 推导快照 ─────────────────────────────────────────────────────────────

    /**
     * 推导结果快照，包含三个维度的分类结果和 schema 版本。
     */
    data class ParticipationBoundarySnapshot(
        val participationBoundaryRole: ParticipationBoundaryRole,
        val ownershipPostureClass: OwnershipPostureClass,
        val remoteLocalModeClass: RemoteLocalModeClass,
        val schemaVersion: String = SCHEMA_VERSION
    ) {
        /**
         * 生成可嵌入上行消息的 wire map。
         */
        fun toWireMap(): Map<String, String> = mapOf(
            KEY_PARTICIPATION_BOUNDARY_ROLE to participationBoundaryRole.wireValue,
            KEY_OWNERSHIP_POSTURE_CLASS to ownershipPostureClass.wireValue,
            KEY_REMOTE_LOCAL_MODE_CLASS to remoteLocalModeClass.wireValue,
            KEY_SCHEMA_VERSION to schemaVersion
        )
    }

    // ── 推导函数 ─────────────────────────────────────────────────────────────

    /**
     * 按 5 级优先级推导 [ParticipationBoundarySnapshot]。
     *
     * 推导规则（优先级从高到低）：
     *
     * 1. **诊断性信号**：isDiagnosticsSignal=true → DIAGNOSTICS_SUMMARY_ONLY / POSTURE_SIGNAL_ONLY / (mode by state)
     * 2. **本地模式/fallback**：executionModeState=LOCAL_ONLY 或 isFallbackTierActive → REMOTE_LOCAL_MODE_FALLBACK
     * 3. **Handoff 参与**：takeoverActive=true → HANDOFF_PARTICIPANT / HANDOFF_PARTICIPANT / DISTRIBUTED_EXECUTING
     * 4. **真实分布式执行**：crossDeviceEnabled + executionBusy + join_runtime → DISTRIBUTED_RUNTIME_PARTICIPANT
     * 5. **默认/仅注册态**：OWNERSHIP_POSTURE_SIGNAL / POSTURE_SIGNAL_ONLY / LOCAL_ONLY_DECLARED
     */
    fun derive(input: ParticipationBoundaryDerivationInput): ParticipationBoundarySnapshot {
        val isJoinRuntime = SourceRuntimePosture.isJoinRuntime(input.sourceRuntimePosture)
        val isLocalOnlyMode = input.executionModeStateWire ==
            LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY.wireValue

        // ── 优先级 1: 诊断性信号 ─────────────────────────────────────────────
        if (input.isDiagnosticsSignal) {
            val modeClass = when {
                isLocalOnlyMode && !input.isFallbackTierActive ->
                    RemoteLocalModeClass.LOCAL_ONLY_DECLARED
                input.isFallbackTierActive && input.isCapabilityDegraded ->
                    RemoteLocalModeClass.DEGRADED_FALLBACK
                input.isFallbackTierActive ->
                    RemoteLocalModeClass.FALLBACK_LOCAL
                input.crossDeviceEnabled && input.executionBusy ->
                    RemoteLocalModeClass.DISTRIBUTED_EXECUTING
                else ->
                    RemoteLocalModeClass.LOCAL_ONLY_DECLARED
            }
            return ParticipationBoundarySnapshot(
                participationBoundaryRole = ParticipationBoundaryRole.DIAGNOSTICS_SUMMARY_ONLY,
                ownershipPostureClass = OwnershipPostureClass.POSTURE_SIGNAL_ONLY,
                remoteLocalModeClass = modeClass
            )
        }

        // ── 优先级 2: 本地模式 / fallback ────────────────────────────────────
        if (isLocalOnlyMode || input.isFallbackTierActive) {
            val postureClass = when {
                isJoinRuntime -> OwnershipPostureClass.POSTURE_SIGNAL_ONLY
                else -> OwnershipPostureClass.CONTROL_INITIATOR
            }
            val modeClass = when {
                isLocalOnlyMode && !input.isFallbackTierActive ->
                    RemoteLocalModeClass.LOCAL_ONLY_DECLARED
                input.isCapabilityDegraded ->
                    RemoteLocalModeClass.DEGRADED_FALLBACK
                else ->
                    RemoteLocalModeClass.FALLBACK_LOCAL
            }
            return ParticipationBoundarySnapshot(
                participationBoundaryRole = ParticipationBoundaryRole.REMOTE_LOCAL_MODE_FALLBACK,
                ownershipPostureClass = postureClass,
                remoteLocalModeClass = modeClass
            )
        }

        // ── 优先级 3: Handoff 参与 ────────────────────────────────────────────
        if (input.takeoverActive) {
            return ParticipationBoundarySnapshot(
                participationBoundaryRole = ParticipationBoundaryRole.HANDOFF_PARTICIPANT,
                ownershipPostureClass = if (isJoinRuntime)
                    OwnershipPostureClass.HANDOFF_PARTICIPANT
                else
                    OwnershipPostureClass.POSTURE_SIGNAL_ONLY,
                remoteLocalModeClass = RemoteLocalModeClass.DISTRIBUTED_EXECUTING
            )
        }

        // ── 优先级 4: 真实分布式执行 ──────────────────────────────────────────
        if (input.crossDeviceEnabled && input.executionBusy && isJoinRuntime) {
            return ParticipationBoundarySnapshot(
                participationBoundaryRole = ParticipationBoundaryRole.DISTRIBUTED_RUNTIME_PARTICIPANT,
                ownershipPostureClass = OwnershipPostureClass.RUNTIME_HOST_EXECUTOR,
                remoteLocalModeClass = RemoteLocalModeClass.DISTRIBUTED_EXECUTING
            )
        }

        // ── 优先级 5: 默认/仅注册/posture 信号态 ────────────────────────────
        val defaultPosture = when {
            isJoinRuntime -> OwnershipPostureClass.POSTURE_SIGNAL_ONLY
            else -> OwnershipPostureClass.CONTROL_INITIATOR
        }
        val defaultMode = when {
            input.crossDeviceEnabled -> RemoteLocalModeClass.DISTRIBUTED_EXECUTING
            else -> RemoteLocalModeClass.LOCAL_ONLY_DECLARED
        }
        return ParticipationBoundarySnapshot(
            participationBoundaryRole = ParticipationBoundaryRole.OWNERSHIP_POSTURE_SIGNAL,
            ownershipPostureClass = defaultPosture,
            remoteLocalModeClass = defaultMode
        )
    }

    // ── V2 消费路径映射 ────────────────────────────────────────────────────────

    /**
     * Android 各参与边界角色在 V2 侧对应的消费路径。
     *
     * V2 canonical consumers MUST route uplink payloads according to
     * [participationBoundaryRole] rather than re-deriving boundary semantics
     * from field combinations.
     */
    val V2_OWNERSHIP_GOVERNANCE_PATH_MAP: Map<ParticipationBoundaryRole, String> = mapOf(
        ParticipationBoundaryRole.DISTRIBUTED_RUNTIME_PARTICIPANT to
            "core/distributed_runtime_result_merge_chain.py（纳入结果合并链）",
        ParticipationBoundaryRole.OWNERSHIP_POSTURE_SIGNAL to
            "core/ownership_posture_governance.py（ownership signal 路由，MAY 用于 dispatch，MUST NOT 用于 authority closure）",
        ParticipationBoundaryRole.REMOTE_LOCAL_MODE_FALLBACK to
            "core/android_device_state_store.py（记录模式切换，MUST NOT 向 Android 分配新分布式任务）",
        ParticipationBoundaryRole.HANDOFF_PARTICIPANT to
            "core/handoff_governance_chain.py（handoff 参与记录，MUST NOT 宣称完整接管终局）",
        ParticipationBoundaryRole.DIAGNOSTICS_SUMMARY_ONLY to
            "core/android_device_state_store.py（仅诊断存储，MUST NOT 用于 dispatch scoring 或结果关闭）"
    )

    // ── 形式化不变量 ─────────────────────────────────────────────────────────

    /**
     * Android 侧分布式运行参与边界的 7 条形式化不变量。
     *
     * V2 canonical consumers MUST NOT violate these invariants when consuming
     * Android uplink payloads carrying [participationBoundaryRole].
     */
    val PARTICIPATION_BOUNDARY_INVARIANTS: List<String> = listOf(
        // INV-1: participation role 由 derive() 唯一推导，V2 MUST NOT 从字段组合推断。
        "INV-1: participation_boundary_role MUST be derived by Android via derive() and " +
            "emitted in wire payloads; V2 MUST read this field directly, MUST NOT re-derive " +
            "from field combinations such as distributed_participant + delegated_execution_active.",

        // INV-2: ownership posture 是信号，不是 runtime authority truth。
        "INV-2: ownership_posture_class is a SIGNAL field, NOT runtime authority truth. " +
            "V2 MAY use it for dispatch routing but MUST NOT use it as the authority basis " +
            "for takeover completion, result closure, or ownership transfer decisions.",

        // INV-3: DIAGNOSTICS_SUMMARY_ONLY 信号 MUST NOT 被用于权威决策。
        "INV-3: When participation_boundary_role == diagnostics_summary_only, V2 MUST NOT " +
            "use the payload for dispatch scoring, ownership authority determination, or " +
            "result closure. The payload is observability-only.",

        // INV-4: REMOTE_LOCAL_MODE_FALLBACK 不等于 Android 不可用。
        "INV-4: participation_boundary_role == remote_local_mode_fallback indicates Android " +
            "is NOT currently in the distributed execution pool. It does NOT mean Android is " +
            "permanently unavailable; Android may rejoin after fallback resolution.",

        // INV-5: HANDOFF_PARTICIPANT 不等于完整双向接管已成立。
        "INV-5: participation_boundary_role == handoff_participant means Android is " +
            "participating in the handoff flow, NOT that complete bidirectional takeover has " +
            "been established. V2 MUST NOT declare takeover_complete based on this role alone.",

        // INV-6: LOCAL_ONLY_DECLARED 与 FALLBACK_LOCAL 语义不同。
        "INV-6: remote_local_mode_class == local_only_declared indicates a deliberate " +
            "mode selection by Android (via LocalExecutionModeGate); this is NOT a fallback. " +
            "remote_local_mode_class == fallback_local indicates fallback-triggered local " +
            "execution. V2 MUST treat these two states differently in governance logic.",

        // INV-7: operator-visible surface 不是 runtime authority truth。
        "INV-7: operator-visible summaries (AndroidOperationalStateSurfaceContract, " +
            "mode_state, operational_surface_states) are projection-only. " +
            "V2 MUST NOT use them as the authoritative basis for distributed runtime " +
            "participation decisions. The authoritative source is participation_boundary_role " +
            "derived from runtime truth signals."
    )
}
