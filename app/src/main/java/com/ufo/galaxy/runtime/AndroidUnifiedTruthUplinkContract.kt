package com.ufo.galaxy.runtime

/**
 * Android 统一真相上行合约（Android Unified Truth Uplink Contract）
 *
 * 本合约建立了 Android 侧向 V2 中心上报运行时真相的**统一、稳定、机器可消费**语义。
 * Android 不是被动客户端，而是中心控制型分布式 AI 系统中的：
 *  - 运行时参与节点（runtime participation node）
 *  - 委托执行节点（delegated execution node）
 *  - 连续性节点（continuity node）
 *  - 真相上报节点（truth-reporting node）
 *
 * ## 解决的核心问题
 *
 * 在本合约之前，Android 的运行时真相分散在多个互相部分重叠的载体中：
 *  - [com.ufo.galaxy.protocol.DeviceStateSnapshotPayload]：能力/状态快照
 *  - [com.ufo.galaxy.protocol.DeviceExecutionEventPayload]：执行事件
 *  - [com.ufo.galaxy.protocol.GoalResultPayload]：结果上报
 *  - [com.ufo.galaxy.protocol.DeviceRegisterPayload]：注册载体
 *
 * 这导致以下问题（本合约逐一闭合）：
 *  1. 真相字段在各载体中**不一致地分布**，V2 无法依赖单一来源。
 *  2. `participation_tier` 在部分委托结果路径中**无法保证存在**。
 *  3. 本地模式与本地能力真相**不完整**，非完全机器可读。
 *  4. 受限/延迟/持有/本地模式门/执行压力等语义**未统一为正式合约**。
 *  5. Android 侧运行时真相仍存在**路由专用行为**而非单一长期合约。
 *
 * ## 七类真相分类
 *
 * | [TruthCategory]                    | 描述                                    |
 * |------------------------------------|-----------------------------------------|
 * | [TruthCategory.PARTICIPATION]      | 参与状态：tier、eligible、distributed   |
 * | [TruthCategory.MODE]               | 执行模式：local/cross-device/constrained|
 * | [TruthCategory.GOVERNANCE]         | 治理状态：delegated/takeover/blocked    |
 * | [TruthCategory.EXECUTION]          | 执行标识：task/device/session/phase     |
 * | [TruthCategory.CLOSURE_UPSTREAM]   | 接受/闭合相关上行真相                   |
 * | [TruthCategory.CONTINUITY]         | 持久身份与会话连续性                    |
 * | [TruthCategory.LOCAL_CAPABILITY]   | 本地能力：推理、可访问性、本地模式      |
 *
 * ## V2 消费约定
 *
 * V2 消费方 MUST：
 *  1. 从 [TruthCategory.PARTICIPATION] 中读取 `participation_tier`，而非从状态组合推断。
 *  2. 从 [TruthCategory.MODE] 中读取 `execution_mode_state` 作为模式权威字段。
 *  3. 在所有重要委托结果路径中检验 `participation_tier` 非空。
 *  4. 从 [TruthCategory.LOCAL_CAPABILITY] 中读取 `local_mode_capable`/`local_llm_ready` 等字段。
 *  5. 将 `runtime_constrained`/`runtime_deferred` 作为形式约束信号，而非通过字段组合推断。
 *
 * @see AndroidAuthoritativeParticipationTruth
 * @see LocalExecutionModeGate
 * @see AndroidCanonicalRuntimeTruthContract
 */
object AndroidUnifiedTruthUplinkContract {

    // ── 合约元数据 ──────────────────────────────────────────────────────────────

    /** 本合约的引入 PR 号。 */
    const val INTRODUCED_PR: Int = 90

    /** 本合约 wire map 的 schema 版本。 */
    const val SCHEMA_VERSION: String = "1"

    // ── 真相分类枚举 ─────────────────────────────────────────────────────────────

    /**
     * Android 上行真相的七大分类。
     *
     * 每个分类对应 V2 中独立的消费语境（路由/调度/闭合/治理/连续性/能力评分）。
     *
     * @property wireValue    稳定的 wire 字符串，出现在 JSON 载体与日志中。
     * @property description  一句话语义描述。
     */
    enum class TruthCategory(val wireValue: String, val description: String) {

        /**
         * 参与真相（Participation Truth）。
         *
         * 涵盖 Android 在中心控制分布式系统中的当前参与地位：
         * tier 层级、dispatch 资格、分布式参与状态、会话是否已附加。
         */
        PARTICIPATION(
            wireValue = "participation",
            description = "Android 参与状态：tier、dispatch 资格、分布式参与、会话附加"
        ),

        /**
         * 模式真相（Mode Truth）。
         *
         * 涵盖 Android 执行模式（本地/跨设备/受限/延迟）及其切换状态，
         * 包括本地模式门状态与跨设备资格。
         */
        MODE(
            wireValue = "mode",
            description = "Android 执行模式：local/cross-device/constrained/deferred/gate"
        ),

        /**
         * 治理真相（Governance Truth）。
         *
         * 涵盖 Android 当前由谁治理、是否正处于委托执行中、接管是否待处理/活跃、
         * 以及是否被治理层阻塞。
         */
        GOVERNANCE(
            wireValue = "governance",
            description = "Android 治理状态：authority/delegated/takeover/blocked"
        ),

        /**
         * 执行真相（Execution Truth）。
         *
         * 涵盖与特定任务/执行相关的标识与状态字段：
         * task_id、device_id、runtime_session_id、执行阶段、委托状态、结果摘要。
         */
        EXECUTION(
            wireValue = "execution",
            description = "Android 执行标识与状态：task/device/session/phase/delegated/result"
        ),

        /**
         * 闭合上行真相（Closure-Relevant Upstream Truth）。
         *
         * 涵盖 V2 acceptance/closure 消费所需的 Android 侧候选真相字段：
         * 接受候选、truth-chain 相关字段、委托结果/执行事件/快照字段。
         */
        CLOSURE_UPSTREAM(
            wireValue = "closure_upstream",
            description = "V2 acceptance/closure 消费的 Android 上行候选真相"
        ),

        /**
         * 连续性真相（Continuity Truth）。
         *
         * 涵盖持久身份与会话连续性相关字段：
         * durable_participant_id、runtime_session_id、重连/接管/连续性身份字段。
         */
        CONTINUITY(
            wireValue = "continuity",
            description = "Android 持久身份与会话连续性：durable_id/session/reattach/takeover"
        ),

        /**
         * 本地能力真相（Local Capability Truth）。
         *
         * 涵盖 Android 本地推理、可访问性及本地模式能力状态：
         * local_inference_available、local_llm_ready、accessibility_ready、local_mode_capable。
         */
        LOCAL_CAPABILITY(
            wireValue = "local_capability",
            description = "Android 本地能力：inference/llm/accessibility/local-mode"
        );

        companion object {
            private val BY_WIRE = values().associateBy { it.wireValue }

            /** 根据 wire 字符串查找分类，未知值返回 null。 */
            fun fromWireValue(wireValue: String): TruthCategory? = BY_WIRE[wireValue]

            /** 所有 wire value 的有序集合（用于合约验证测试）。 */
            val ALL_WIRE_VALUES: List<String> = values().map { it.wireValue }
        }
    }

    // ── 约束语义枚举 ─────────────────────────────────────────────────────────────

    /**
     * Android 侧运行时约束与延迟语义的正式枚举。
     *
     * 本枚举统一了此前分散在多个字段组合中的约束/延迟/持有/本地模式门/执行压力语义，
     * 使 V2 可通过读取单一字段而非字段组合来确定 Android 的约束状态。
     *
     * @property wireValue 稳定的 wire 字符串。
     * @property isConstraint  是否为约束状态（阻止新任务派遣）。
     * @property isDeferred    是否为延迟状态（临时不可用，可等待恢复）。
     */
    enum class ConstraintSemantics(
        val wireValue: String,
        val isConstraint: Boolean,
        val isDeferred: Boolean
    ) {

        /** 无约束：Android 完全可用，可接受派遣。 */
        NONE("none", isConstraint = false, isDeferred = false),

        /**
         * 运行时受限（Runtime Constrained）。
         *
         * Android 因资源限制、健康降级或能力门控而受限。
         * V2 MUST NOT 在此状态下向 Android 派遣全能力任务。
         */
        RUNTIME_CONSTRAINED("runtime_constrained", isConstraint = true, isDeferred = false),

        /**
         * 运行时延迟（Runtime Deferred）。
         *
         * Android 处于临时不可用状态，预期可在不重置会话的情况下恢复。
         * V2 应扩展超时预算并等待后续 `NONE` 状态信号。
         */
        RUNTIME_DEFERRED("runtime_deferred", isConstraint = false, isDeferred = true),

        /**
         * 本地模式门延迟（Local Mode Gate Deferred）。
         *
         * [LocalExecutionModeGate] 已处于持有状态，
         * 即执行模式正在切换中，新任务须等待门状态解除。
         */
        LOCAL_MODE_GATE_DEFERRED(
            "local_mode_gate_deferred",
            isConstraint = false,
            isDeferred = true
        ),

        /**
         * 执行压力（Execution Pressure）。
         *
         * Android 当前有活跃执行正在运行，新任务将排队而非立即执行。
         * V2 应考虑将新任务路由到其他节点。
         */
        EXECUTION_PRESSURE("execution_pressure", isConstraint = true, isDeferred = false),

        /**
         * 持有状态（Hold）。
         *
         * Android 被 V2 或本地策略主动置于持有状态，
         * 在中心显式解除前不接受新任务。
         */
        HOLD("hold", isConstraint = true, isDeferred = false);

        companion object {
            private val BY_WIRE = values().associateBy { it.wireValue }

            /** 根据 wire 字符串查找约束语义，未知值返回 null。 */
            fun fromWireValue(wireValue: String): ConstraintSemantics? = BY_WIRE[wireValue]

            /** 所有 wire value 的有序集合。 */
            val ALL_WIRE_VALUES: List<String> = values().map { it.wireValue }

            /**
             * 从布尔标志集合推断 [ConstraintSemantics]。
             *
             * 此方法将离散的布尔标志折叠为单一的约束语义枚举值，
             * 使调用方无需手动实现优先级逻辑。
             *
             * @param isConstrained        是否受限（资源/健康/能力门控）。
             * @param isDeferred           是否处于运行时延迟状态。
             * @param isLocalModeGateHold  [LocalExecutionModeGate] 是否处于持有状态。
             * @param isExecutionBusy      是否有活跃执行正在运行（执行压力）。
             * @param isHold               是否被显式置于持有状态。
             */
            fun derive(
                isConstrained: Boolean = false,
                isDeferred: Boolean = false,
                isLocalModeGateHold: Boolean = false,
                isExecutionBusy: Boolean = false,
                isHold: Boolean = false
            ): ConstraintSemantics = when {
                isHold -> HOLD
                isConstrained -> RUNTIME_CONSTRAINED
                isLocalModeGateHold -> LOCAL_MODE_GATE_DEFERRED
                isExecutionBusy -> EXECUTION_PRESSURE
                isDeferred -> RUNTIME_DEFERRED
                else -> NONE
            }
        }
    }

    // ── 治理状态枚举 ──────────────────────────────────────────────────────────────

    /**
     * Android 当前治理状态的正式枚举。
     *
     * 与 [TruthCategory.MODE] 分离：mode 描述“运行在哪里/是否受限”，
     * governance 描述“当前由谁治理以及是否被治理层阻塞”。
     */
    enum class GovernanceState(
        val wireValue: String,
        val v2Governed: Boolean,
        val blocked: Boolean
    ) {

        /** Android 当前处于本地自主治理状态。 */
        LOCAL_AUTONOMOUS("local_autonomous", v2Governed = false, blocked = false),

        /** Android 已接入中心治理，但当前没有活跃委托执行。 */
        V2_GOVERNED("v2_governed", v2Governed = true, blocked = false),

        /** Android 当前正在执行中心委托的执行单元。 */
        DELEGATED_EXECUTION("delegated_execution", v2Governed = true, blocked = false),

        /** Android 已接入治理面，但因治理隔离/挂起而被阻塞。 */
        GOVERNANCE_BLOCKED("governance_blocked", v2Governed = true, blocked = true);

        companion object {
            private val BY_WIRE = values().associateBy { it.wireValue }

            fun fromWireValue(wireValue: String): GovernanceState? = BY_WIRE[wireValue]

            val ALL_WIRE_VALUES: List<String> = values().map { it.wireValue }
        }
    }

    /**
     * Android 当前接管生命周期状态。
     */
    enum class TakeoverState(val wireValue: String) {
        INACTIVE("inactive"),
        PENDING("pending"),
        ACTIVE("active");

        companion object {
            private val BY_WIRE = values().associateBy { it.wireValue }

            fun fromWireValue(wireValue: String): TakeoverState? = BY_WIRE[wireValue]

            val ALL_WIRE_VALUES: List<String> = values().map { it.wireValue }
        }
    }

    /**
     * 治理真相的聚合推导结果。
     */
    data class GovernanceTruth(
        val governance_state: String,
        val governance_blocked: Boolean,
        val delegated_execution_active: Boolean,
        val takeover_state: String
    )

    /**
     * 从 Android 运行时的原始治理信号推导稳定治理真相。
     */
    fun deriveGovernanceTruth(
        crossDeviceEnabled: Boolean,
        sessionAttached: Boolean,
        activeTaskId: String?,
        activeTakeoverId: String?,
        operatorSuspendedOrIsolated: Boolean
    ): GovernanceTruth {
        val delegatedExecutionActive = !activeTaskId.isNullOrBlank()
        val takeoverState = when {
            activeTakeoverId.isNullOrBlank() -> TakeoverState.INACTIVE
            delegatedExecutionActive -> TakeoverState.ACTIVE
            else -> TakeoverState.PENDING
        }
        val governanceState = when {
            operatorSuspendedOrIsolated -> GovernanceState.GOVERNANCE_BLOCKED
            delegatedExecutionActive -> GovernanceState.DELEGATED_EXECUTION
            sessionAttached -> GovernanceState.V2_GOVERNED // An attached runtime session is already center-governed.
            // Explicit cross-device opt-in or an in-flight takeover both mean governance
            // authority has already moved to the center even before a task becomes active.
            crossDeviceEnabled || !activeTakeoverId.isNullOrBlank() ->
                GovernanceState.V2_GOVERNED
            else -> GovernanceState.LOCAL_AUTONOMOUS
        }
        return GovernanceTruth(
            governance_state = governanceState.wireValue,
            governance_blocked = governanceState.blocked,
            delegated_execution_active = delegatedExecutionActive,
            takeover_state = takeoverState.wireValue
        )
    }

    // ── 本地能力状态枚举 ──────────────────────────────────────────────────────────

    /**
     * Android 本地能力状态的正式枚举。
     *
     * 统一了此前分散在多个载体中的本地推理可用性语义，
     * 提供单一机器可读字段供 V2 决策使用。
     *
     * @property wireValue         稳定的 wire 字符串。
     * @property isLocalModeCapable 是否具备本地模式运行能力。
     */
    enum class LocalCapabilityState(
        val wireValue: String,
        val isLocalModeCapable: Boolean
    ) {

        /** 本地能力完整：推理可用、可访问性就绪、本地模式可运行。 */
        FULL("full", isLocalModeCapable = true),

        /** 本地能力部分可用：推理可用但部分子系统（如可访问性）未就绪。 */
        PARTIAL("partial", isLocalModeCapable = true),

        /** 本地能力降级：推理可用但处于降级/回退状态。 */
        DEGRADED("degraded", isLocalModeCapable = false),

        /** 本地能力不可用：推理不可用或关键前置条件未满足。 */
        UNAVAILABLE("unavailable", isLocalModeCapable = false),

        /** 本地能力状态未知：尚未探测或状态不可观测。 */
        UNKNOWN("unknown", isLocalModeCapable = false);

        companion object {
            private val BY_WIRE = values().associateBy { it.wireValue }

            /** 根据 wire 字符串查找能力状态，未知值返回 null。 */
            fun fromWireValue(wireValue: String): LocalCapabilityState? = BY_WIRE[wireValue]

            /** 所有 wire value 的有序集合。 */
            val ALL_WIRE_VALUES: List<String> = values().map { it.wireValue }

            /**
             * 从布尔标志集合推断 [LocalCapabilityState]。
             *
             * @param localLlmReady          本地 LLM 是否就绪。
             * @param localInferenceAvailable 本地推理是否可用。
             * @param accessibilityReady      可访问性服务是否就绪。
             * @param isDegraded              是否处于降级状态。
             */
            fun derive(
                localLlmReady: Boolean?,
                localInferenceAvailable: Boolean?,
                accessibilityReady: Boolean?,
                isDegraded: Boolean = false
            ): LocalCapabilityState {
                val inferenceAvail = localLlmReady == true || localInferenceAvailable == true
                return when {
                    !inferenceAvail -> UNAVAILABLE
                    isDegraded -> DEGRADED
                    accessibilityReady == true -> FULL
                    accessibilityReady == false -> PARTIAL
                    else -> PARTIAL  // 可访问性状态未知时视为部分可用
                }
            }
        }
    }

    // ── 统一真相快照（核心合约类型） ───────────────────────────────────────────────

    /**
     * Android 侧统一真相快照。
     *
     * 将七大真相分类的关键字段聚合为一个稳定的、版本化的快照类型，
     * 使 V2 可从单一结构消费所有重要的 Android 运行时真相，
     * 而无需在多个载体类型间重新组装。
     *
     * ## 字段命名约定
     *
     * 所有字段名与 wire map 键保持一致（snake_case），以便 `toWireMap()` 无歧义地序列化。
     *
     * ## 合约保证
     *
     * 本类型保证以下不变量（见 [UPLINK_INVARIANTS]）：
     *  - `participation_tier` **永不为 null**：默认为 `pre_attach`。
     *  - `execution_mode_state` **永不为 null**：默认为 `inactive`。
     *  - `constraint_semantics` **永不为 null**：默认为 `none`。
     *  - `local_capability_state` **永不为 null**：默认为 `unknown`。
     *  - `schema_version` **永不为 null**：始终为 [SCHEMA_VERSION]。
     */
    data class UnifiedTruthSnapshot(

        // ── 元数据 ──────────────────────────────────────────────────────────────
        /** 本快照的 schema 版本，取自 [SCHEMA_VERSION]。 */
        val schema_version: String = SCHEMA_VERSION,

        // ── 参与真相（PARTICIPATION） ────────────────────────────────────────────

        /**
         * Android 的权威参与状态 wire 值。
         * 取自 [AndroidAuthoritativeParticipationTruth.State.wireValue]。
         */
        val authoritative_participation_state: String,

        /**
         * Android 的参与层级 wire 值。
         * 取自 [AndroidAuthoritativeParticipationTruth.ParticipationTier.wireValue]。
         * **本字段永不为 null**：当无法推断时默认为 `pre_attach`。
         */
        val participation_tier: String,

        /**
         * Android 当前是否处于 dispatch_eligible 层级（dispatch 资格）。
         * 当 [participation_tier] 为 `dispatch_eligible` 或 `distributed_participant` 时为 true。
         */
        val dispatch_eligible: Boolean,

        /**
         * Android 当前是否为分布式参与者。
         * 当 [participation_tier] 为 `distributed_participant` 时为 true。
         */
        val distributed_participant: Boolean,

        /**
         * Android 当前是否已附加运行时会话。
         * 当 [participation_tier] 为 `fully_attached` 或更高时为 true。
         */
        val session_attached: Boolean,

        // ── 模式真相（MODE） ─────────────────────────────────────────────────────

        /**
         * Android 当前执行模式状态 wire 值。
         * 取自 [LocalExecutionModeGate.ExecutionModeState.wireValue]。
         * **本字段永不为 null**：当无法推断时默认为 `inactive`。
         */
        val execution_mode_state: String,

        /**
         * Android 是否具备跨设备任务接受资格。
         * 当 [LocalExecutionModeGate.ExecutionModeState.acceptsCrossDeviceTasks] 为 true 时为 true。
         */
        val cross_device_eligibility: Boolean,

        /**
         * Android 是否当前处于本地模式（本地优先执行，不接受跨设备任务）。
         * 当 [execution_mode_state] 为 `local_only` 时为 true。
         */
        val local_mode_active: Boolean,

        /**
         * Android 是否因本地模式门处于持有状态而延迟派遣。
         * 对应 [LocalExecutionModeGate] 的 hold/transitioning 状态。
         */
        val local_mode_gate_deferred: Boolean,

        /**
         * Android 是否处于运行时受限状态。
         * 当 [constraint_semantics] 为 [ConstraintSemantics.RUNTIME_CONSTRAINED] 时为 true。
         */
        val runtime_constrained: Boolean,

        /**
         * Android 是否处于运行时延迟状态。
         * 当 [constraint_semantics] 为 [ConstraintSemantics.RUNTIME_DEFERRED] 或
         * [ConstraintSemantics.LOCAL_MODE_GATE_DEFERRED] 时为 true。
         */
        val runtime_deferred: Boolean,

        /**
         * 统一约束语义 wire 值。
         * 取自 [ConstraintSemantics.wireValue]。
         * **本字段永不为 null**：当无法推断时默认为 `none`。
         */
        val constraint_semantics: String,

        // ── 治理真相（GOVERNANCE） ───────────────────────────────────────────────

        /**
         * Android 当前治理状态 wire 值。
         * 取自 [GovernanceState.wireValue]。
         */
        val governance_state: String,

        /**
         * Android 当前是否因治理隔离/挂起而被阻塞。
         */
        val governance_blocked: Boolean,

        /**
         * Android 当前是否存在活跃委托执行。
         */
        val delegated_execution_active: Boolean,

        /**
         * Android 当前接管生命周期状态 wire 值。
         * 取自 [TakeoverState.wireValue]。
         */
        val takeover_state: String,

        // ── 执行真相（EXECUTION） ────────────────────────────────────────────────

        /** 当前任务标识符；无活跃任务时为 null。 */
        val task_id: String?,

        /** Android 设备标识符。 */
        val device_id: String,

        /** 每次进程启动的运行时会话 UUID；未初始化时为 null。 */
        val runtime_session_id: String?,

        /** 当前执行阶段 wire 值（如 `execution_started`、`completed`）；无活跃执行时为 null。 */
        val execution_phase: String?,

        /**
         * 委托执行状态 wire 值。
         * 对应 [DelegatedExecutionSignal.Kind.wireValue]；无委托执行时为 null。
         */
        val delegated_execution_state: String?,

        /** 规范化的结果状态（如 `completed`、`failed`）；仅在结果上报时有值。 */
        val normalized_status: String?,

        /** 人类可读的结果摘要；仅在结果上报时有值。 */
        val result_summary: String?,

        // ── 闭合上行真相（CLOSURE_UPSTREAM） ────────────────────────────────────

        /**
         * 问题求解闭合类别 wire 值。
         * 取自 [AndroidNlDrivenExecutionSpineContract.ProblemSolvingClosureClass.wireValue]。
         */
        val problem_solving_closure_class: String?,

        /**
         * 执行脊柱参与种类 wire 值。
         * 取自 [AndroidNlDrivenExecutionSpineContract.ExecutionSpineParticipationKind.wireValue]。
         */
        val execution_spine_participation_kind: String?,

        // ── 连续性真相（CONTINUITY） ─────────────────────────────────────────────

        /**
         * 持久参与者身份 UUID（稳定跨进程重启/WS 重连，仅卸载后重置）。
         * 取自 [com.ufo.galaxy.data.AppSettings.durableParticipantId]。
         */
        val durable_participant_id: String?,

        /**
         * 持久参与者身份新鲜度 wire 值。
         * 取自 [com.ufo.galaxy.session.DurableParticipantIdentity.IdentityFreshness.wireValue]。
         */
        val participant_identity_freshness: String?,

        /**
         * 已附加运行时会话的 UUID。
         * 稳定于单次附加事件内；无附加会话时为 null。
         */
        val attached_session_id: String?,

        // ── 本地能力真相（LOCAL_CAPABILITY） ─────────────────────────────────────

        /**
         * 本地推理是否可用（来自 Android 运行时/能力真相）。
         * 对应之前 [GoalResultPayload] 中的 `local_inference_available` 字段语义。
         */
        val local_inference_available: Boolean?,

        /**
         * 本地 LLM 是否就绪（llama.cpp / NCNN 已加载、模型权重已验证）。
         * true 意味着 Android 可立即进行本地语言模型推理，无需网络。
         */
        val local_llm_ready: Boolean?,

        /**
         * 可访问性服务是否就绪（屏幕交互能力）。
         * true 意味着 Android 可执行 UI 自动化任务。
         */
        val accessibility_ready: Boolean?,

        /**
         * Android 是否具备完整的本地模式运行能力。
         * 当本地推理可用、可访问性就绪、无关键降级时为 true。
         */
        val local_mode_capable: Boolean,

        /**
         * 统一本地能力状态 wire 值。
         * 取自 [LocalCapabilityState.wireValue]。
         * **本字段永不为 null**：当无法推断时默认为 `unknown`。
         */
        val local_capability_state: String
    ) {

        /**
         * 将本快照序列化为可嵌入 AIP v3 载体的 wire map。
         *
         * 所有 null 值字段将被省略（不设置为 null 字符串），
         * 以保持 wire 格式精简并兼容 V2 可选字段语义。
         */
        fun toWireMap(): Map<String, Any> = buildMap {
            put(KEY_SCHEMA_VERSION, schema_version)
            put(KEY_AUTHORITATIVE_PARTICIPATION_STATE, authoritative_participation_state)
            put(KEY_PARTICIPATION_TIER, participation_tier)
            put(KEY_DISPATCH_ELIGIBLE, dispatch_eligible)
            put(KEY_DISTRIBUTED_PARTICIPANT, distributed_participant)
            put(KEY_SESSION_ATTACHED, session_attached)
            put(KEY_EXECUTION_MODE_STATE, execution_mode_state)
            put(KEY_CROSS_DEVICE_ELIGIBILITY, cross_device_eligibility)
            put(KEY_LOCAL_MODE_ACTIVE, local_mode_active)
            put(KEY_LOCAL_MODE_GATE_DEFERRED, local_mode_gate_deferred)
            put(KEY_RUNTIME_CONSTRAINED, runtime_constrained)
            put(KEY_RUNTIME_DEFERRED, runtime_deferred)
            put(KEY_CONSTRAINT_SEMANTICS, constraint_semantics)
            put(KEY_GOVERNANCE_STATE, governance_state)
            put(KEY_GOVERNANCE_BLOCKED, governance_blocked)
            put(KEY_DELEGATED_EXECUTION_ACTIVE, delegated_execution_active)
            put(KEY_TAKEOVER_STATE, takeover_state)
            task_id?.let { put(KEY_TASK_ID, it) }
            put(KEY_DEVICE_ID, device_id)
            runtime_session_id?.let { put(KEY_RUNTIME_SESSION_ID, it) }
            execution_phase?.let { put(KEY_EXECUTION_PHASE, it) }
            delegated_execution_state?.let { put(KEY_DELEGATED_EXECUTION_STATE, it) }
            normalized_status?.let { put(KEY_NORMALIZED_STATUS, it) }
            result_summary?.let { put(KEY_RESULT_SUMMARY, it) }
            problem_solving_closure_class?.let { put(KEY_PROBLEM_SOLVING_CLOSURE_CLASS, it) }
            execution_spine_participation_kind?.let {
                put(KEY_EXECUTION_SPINE_PARTICIPATION_KIND, it)
            }
            durable_participant_id?.let { put(KEY_DURABLE_PARTICIPANT_ID, it) }
            participant_identity_freshness?.let { put(KEY_PARTICIPANT_IDENTITY_FRESHNESS, it) }
            attached_session_id?.let { put(KEY_ATTACHED_SESSION_ID, it) }
            local_inference_available?.let { put(KEY_LOCAL_INFERENCE_AVAILABLE, it) }
            local_llm_ready?.let { put(KEY_LOCAL_LLM_READY, it) }
            accessibility_ready?.let { put(KEY_ACCESSIBILITY_READY, it) }
            put(KEY_LOCAL_MODE_CAPABLE, local_mode_capable)
            put(KEY_LOCAL_CAPABILITY_STATE, local_capability_state)
        }
    }

    // ── Wire map 键常量 ──────────────────────────────────────────────────────────

    const val KEY_SCHEMA_VERSION = "unified_truth_schema_version"
    const val KEY_AUTHORITATIVE_PARTICIPATION_STATE = "authoritative_participation_state"
    const val KEY_PARTICIPATION_TIER = "participation_tier"
    const val KEY_DISPATCH_ELIGIBLE = "dispatch_eligible"
    const val KEY_DISTRIBUTED_PARTICIPANT = "distributed_participant"
    const val KEY_SESSION_ATTACHED = "session_attached"
    const val KEY_EXECUTION_MODE_STATE = "execution_mode_state"
    const val KEY_CROSS_DEVICE_ELIGIBILITY = "cross_device_eligibility"
    const val KEY_LOCAL_MODE_ACTIVE = "local_mode_active"
    const val KEY_LOCAL_MODE_GATE_DEFERRED = "local_mode_gate_deferred"
    const val KEY_RUNTIME_CONSTRAINED = "runtime_constrained"
    const val KEY_RUNTIME_DEFERRED = "runtime_deferred"
    const val KEY_CONSTRAINT_SEMANTICS = "constraint_semantics"
    const val KEY_GOVERNANCE_STATE = "governance_state"
    const val KEY_GOVERNANCE_BLOCKED = "governance_blocked"
    const val KEY_DELEGATED_EXECUTION_ACTIVE = "delegated_execution_active"
    const val KEY_TAKEOVER_STATE = "takeover_state"
    const val KEY_TASK_ID = "task_id"
    const val KEY_DEVICE_ID = "device_id"
    const val KEY_RUNTIME_SESSION_ID = "runtime_session_id"
    const val KEY_EXECUTION_PHASE = "execution_phase"
    const val KEY_DELEGATED_EXECUTION_STATE = "delegated_execution_state"
    const val KEY_NORMALIZED_STATUS = "normalized_status"
    const val KEY_RESULT_SUMMARY = "result_summary"
    const val KEY_PROBLEM_SOLVING_CLOSURE_CLASS = "problem_solving_closure_class"
    const val KEY_EXECUTION_SPINE_PARTICIPATION_KIND = "execution_spine_participation_kind"
    const val KEY_DURABLE_PARTICIPANT_ID = "durable_participant_id"
    const val KEY_PARTICIPANT_IDENTITY_FRESHNESS = "participant_identity_freshness"
    const val KEY_ATTACHED_SESSION_ID = "attached_session_id"
    const val KEY_LOCAL_INFERENCE_AVAILABLE = "local_inference_available"
    const val KEY_LOCAL_LLM_READY = "local_llm_ready"
    const val KEY_ACCESSIBILITY_READY = "accessibility_ready"
    const val KEY_LOCAL_MODE_CAPABLE = "local_mode_capable"
    const val KEY_LOCAL_CAPABILITY_STATE = "local_capability_state"

    // ── 合约不变量 ───────────────────────────────────────────────────────────────

    /**
     * Android 统一真相上行合约的不变量集合。
     *
     * 每条不变量对应一个可在集成测试中机器验证的约束。
     * V2 消费方应将这些不变量视为 Android 侧的正式保证。
     */
    val UPLINK_INVARIANTS: List<String> = listOf(
        "INV-UTU-01: participation_tier 在所有统一真相快照中永不为 null，未知时默认为 pre_attach",
        "INV-UTU-02: execution_mode_state 在所有统一真相快照中永不为 null，未知时默认为 inactive",
        "INV-UTU-03: constraint_semantics 在所有统一真相快照中永不为 null，默认为 none",
        "INV-UTU-04: local_capability_state 在所有统一真相快照中永不为 null，默认为 unknown",
        "INV-UTU-05: schema_version 始终为合约声明的版本字符串",
        "INV-UTU-06: dispatch_eligible 当且仅当 participation_tier 为 dispatch_eligible 或 distributed_participant 时为 true",
        "INV-UTU-07: distributed_participant 当且仅当 participation_tier 为 distributed_participant 时为 true",
        "INV-UTU-08: session_attached 当 participation_tier 不为 pre_attach 时为 true",
        "INV-UTU-09: local_mode_active 当且仅当 execution_mode_state 为 local_only 时为 true",
        "INV-UTU-10: runtime_constrained 当且仅当 constraint_semantics 为 runtime_constrained、execution_pressure 或 hold 时为 true",
        "INV-UTU-11: runtime_deferred 当且仅当 constraint_semantics 为 runtime_deferred 或 local_mode_gate_deferred 时为 true",
        "INV-UTU-12: local_mode_capable 当且仅当 local_capability_state 为 full 或 partial 时为 true",
        "INV-UTU-13: toWireMap() 中所有非 null 字段使用合约声明的 KEY_ 常量作为键",
        "INV-UTU-14: ConstraintSemantics.derive() 结果与 runtime_constrained/runtime_deferred 布尔值一致",
        "INV-UTU-15: governance_state/runtime governance 字段与 delegated_execution_active/takeover_state 一致",
        "INV-UTU-16: takeover_state 当且仅当 active_takeover_id 存在时为 pending 或 active",
        "INV-UTU-17: governance_blocked 当且仅当 governance_state 为 governance_blocked 时为 true",
        "INV-UTU-18: 所有七大 TruthCategory 在 TruthCategory.ALL_WIRE_VALUES 中各有唯一 wire 值"
    )

    // ── 工厂方法：从离散信号构建统一快照 ─────────────────────────────────────────

    /**
     * 从离散运行时信号构建 [UnifiedTruthSnapshot]。
     *
     * 此方法是 Android 运行时生产代码（GalaxyConnectionService / AutonomousExecutionPipeline）
     * 构建统一真相快照的**唯一入口**，确保不变量在构造时被强制执行。
     *
     * @param authoritativeParticipationStateWire 权威参与状态 wire 值，来自
     *        [AndroidAuthoritativeParticipationTruth.State.wireValue]。
     * @param participationTierWire 参与层级 wire 值，来自
     *        [AndroidAuthoritativeParticipationTruth.ParticipationTier.wireValue]；
     *        null 时默认为 `pre_attach`（不变量 INV-UTU-01）。
     * @param executionModeStateWire 执行模式状态 wire 值，来自
     *        [LocalExecutionModeGate.ExecutionModeState.wireValue]；
     *        null 时默认为 `inactive`（不变量 INV-UTU-02）。
     * @param crossDeviceEligibility 跨设备任务接受资格。
     * @param isHoldState 是否因 [LocalExecutionModeGate] 而处于持有/transitioning 状态。
     * @param isConstrained 是否因资源/健康/能力门控而受限。
     * @param isDeferred 是否处于运行时延迟状态。
     * @param isExecutionBusy 是否有活跃执行正在运行。
     * @param isHold 是否被显式置于持有状态。
     * @param taskId 当前任务标识符；无活跃任务时为 null。
     * @param deviceId Android 设备标识符。
     * @param runtimeSessionId 运行时会话 UUID。
     * @param executionPhase 当前执行阶段 wire 值。
     * @param delegatedExecutionState 委托执行信号种类 wire 值。
     * @param normalizedStatus 规范化结果状态。
     * @param resultSummary 结果摘要文本。
     * @param problemSolvingClosureClass 问题求解闭合类别 wire 值。
     * @param executionSpineParticipationKind 执行脊柱参与种类 wire 值。
     * @param durableParticipantId 持久参与者身份 UUID。
     * @param participantIdentityFreshness 身份新鲜度 wire 值。
     * @param attachedSessionId 已附加会话 UUID。
     * @param localInferenceAvailable 本地推理是否可用。
     * @param localLlmReady 本地 LLM 是否就绪。
     * @param accessibilityReady 可访问性服务是否就绪。
     * @param isDegraded 是否处于降级状态（影响 local_capability_state 推断）。
     */
    fun build(
        authoritativeParticipationStateWire: String,
        participationTierWire: String?,
        executionModeStateWire: String?,
        crossDeviceEligibility: Boolean?,
        isHoldState: Boolean = false,
        isConstrained: Boolean = false,
        isDeferred: Boolean = false,
        isExecutionBusy: Boolean = false,
        isHold: Boolean = false,
        crossDeviceEnabled: Boolean = false,
        activeTaskId: String? = null,
        activeTakeoverId: String? = null,
        operatorSuspendedOrIsolated: Boolean = false,
        taskId: String? = null,
        // deviceId defaults to empty string for test-only callers that do not yet have a
        // device identifier available (e.g. unit tests exercising semantic logic in isolation).
        // Production callers in GalaxyConnectionService MUST pass a non-empty deviceId from
        // localDeviceId / UFOGalaxyApplication.appSettings.deviceId.
        deviceId: String = "",
        runtimeSessionId: String? = null,
        executionPhase: String? = null,
        delegatedExecutionState: String? = null,
        normalizedStatus: String? = null,
        resultSummary: String? = null,
        problemSolvingClosureClass: String? = null,
        executionSpineParticipationKind: String? = null,
        durableParticipantId: String? = null,
        participantIdentityFreshness: String? = null,
        attachedSessionId: String? = null,
        localInferenceAvailable: Boolean? = null,
        localLlmReady: Boolean? = null,
        accessibilityReady: Boolean? = null,
        isDegraded: Boolean = false
    ): UnifiedTruthSnapshot {
        // ── 参与真相推断 ──
        val resolvedTier = participationTierWire
            ?: AndroidAuthoritativeParticipationTruth.ParticipationTier.PRE_ATTACH.wireValue
        val dispatchEligible = resolvedTier ==
            AndroidAuthoritativeParticipationTruth.ParticipationTier.DISPATCH_ELIGIBLE.wireValue ||
            resolvedTier ==
            AndroidAuthoritativeParticipationTruth.ParticipationTier.DISTRIBUTED_PARTICIPANT.wireValue
        val distributedParticipant = resolvedTier ==
            AndroidAuthoritativeParticipationTruth.ParticipationTier.DISTRIBUTED_PARTICIPANT.wireValue
        val sessionAttached = resolvedTier !=
            AndroidAuthoritativeParticipationTruth.ParticipationTier.PRE_ATTACH.wireValue

        // ── 模式真相推断 ──
        val resolvedModeState = executionModeStateWire
            ?: LocalExecutionModeGate.ExecutionModeState.INACTIVE.wireValue
        val crossDeviceElig = crossDeviceEligibility ?: false
        val localModeActive = resolvedModeState ==
            LocalExecutionModeGate.ExecutionModeState.LOCAL_ONLY.wireValue

        // ── 约束语义推断 ──
        val constraintSem = ConstraintSemantics.derive(
            isConstrained = isConstrained,
            isDeferred = isDeferred,
            isLocalModeGateHold = isHoldState,
            isExecutionBusy = isExecutionBusy,
            isHold = isHold
        )
        val runtimeConstrained = constraintSem.isConstraint
        val runtimeDeferred = constraintSem.isDeferred

        // ── 治理真相推断 ──
        val governanceTruth = deriveGovernanceTruth(
            crossDeviceEnabled = crossDeviceEnabled,
            sessionAttached = sessionAttached,
            activeTaskId = activeTaskId,
            activeTakeoverId = activeTakeoverId,
            operatorSuspendedOrIsolated = operatorSuspendedOrIsolated
        )

        // ── 本地能力推断 ──
        val localCapState = LocalCapabilityState.derive(
            localLlmReady = localLlmReady,
            localInferenceAvailable = localInferenceAvailable,
            accessibilityReady = accessibilityReady,
            isDegraded = isDegraded
        )
        val localModeCapable = localCapState.isLocalModeCapable

        return UnifiedTruthSnapshot(
            schema_version = SCHEMA_VERSION,
            authoritative_participation_state = authoritativeParticipationStateWire,
            participation_tier = resolvedTier,
            dispatch_eligible = dispatchEligible,
            distributed_participant = distributedParticipant,
            session_attached = sessionAttached,
            execution_mode_state = resolvedModeState,
            cross_device_eligibility = crossDeviceElig,
            local_mode_active = localModeActive,
            local_mode_gate_deferred = isHoldState,
            runtime_constrained = runtimeConstrained,
            runtime_deferred = runtimeDeferred,
            constraint_semantics = constraintSem.wireValue,
            governance_state = governanceTruth.governance_state,
            governance_blocked = governanceTruth.governance_blocked,
            delegated_execution_active = governanceTruth.delegated_execution_active,
            takeover_state = governanceTruth.takeover_state,
            task_id = taskId,
            device_id = deviceId,
            runtime_session_id = runtimeSessionId,
            execution_phase = executionPhase,
            delegated_execution_state = delegatedExecutionState,
            normalized_status = normalizedStatus,
            result_summary = resultSummary,
            problem_solving_closure_class = problemSolvingClosureClass,
            execution_spine_participation_kind = executionSpineParticipationKind,
            durable_participant_id = durableParticipantId,
            participant_identity_freshness = participantIdentityFreshness,
            attached_session_id = attachedSessionId,
            local_inference_available = localInferenceAvailable,
            local_llm_ready = localLlmReady,
            accessibility_ready = accessibilityReady,
            local_mode_capable = localModeCapable,
            local_capability_state = localCapState.wireValue
        )
    }
}
