package com.ufo.galaxy.runtime

/**
 * PR-3Android — Android 参与语义规范化合约。
 *
 * ## 解决的问题
 *
 * 在此合约引入之前，V2 消费方在解读 Android 上报的参与/模式信号时面临以下歧义：
 *
 * 1. **本地执行活跃** 与 **分布式参与** 的语义混叠：
 *    `distributed_participant`（参与层级）、`delegated_execution_active`（治理状态）、
 *    `execution_busy`（原始执行忙状态）三个字段分布在不同真相域中，
 *    V2 须跨域组合推断才能区分"Android 正在本地跑任务"与"Android 正作为分布式节点执行委托任务"。
 *
 * 2. **模式信号语义漂移**：`local_mode_active`（来自模式门）、`mode_state`（遗留字段）、
 *    `execution_mode_state`（LocalExecutionModeGate 规范字段）三组信号并存，
 *    V2 在本地/跨设备/委托/接管四种执行路径上的路由决策存在不一致。
 *
 * 3. **参与层级 tri-state 解释歧义**：`participation_tier` 的 `dispatch_eligible` 与
 *    `distributed_participant` 值含义不对称——前者表示"可被调度"（接受委托方）、
 *    后者表示"正在分布式执行"（活跃节点），但字段名称本身无法传达此区别。
 *
 * ## 合约目标
 *
 * [AndroidParticipationSemanticNormalizationContract] 在现有信号之上引入一层规范化投影：
 *
 * - [ParticipationModeClass]：8 值枚举，为当前参与模式提供单一、机器可读的分类标签，
 *   V2 可直接用于路由决策，无需字段组合推断。
 * - [LocalExecutionActivityKind]：4 值枚举，明确本地执行活跃的语义种类——
 *   区分"本地辅助执行"、"委托执行参与"、"接管执行"，消除与分布式参与概念的混叠。
 * - [NormalizationSnapshot]：规范化快照，包含上述两个枚举值及派生布尔字段，
 *   确保 V2 可通过单一快照消费完整的参与语义，无需跨消息重建上下文。
 * - [NORMALIZATION_INVARIANTS]：10 条形式化不变量，声明 V2 可依赖的 Android 侧保证。
 *
 * ## 与现有合约的关系
 *
 * | 现有合约 | 本合约的补充关系 |
 * |---|---|
 * | [AndroidAuthoritativeParticipationTruth] | 本合约在其 State/ParticipationTier 之上做语义映射，不修改其推导逻辑 |
 * | [AndroidUnifiedTruthUplinkContract] | 本合约使用其 GovernanceTruth 字段（delegated_execution_active/takeover_state）作为推导输入 |
 * | [LocalExecutionModeGate] | 本合约使用其 ExecutionModeState 作为模式信号输入 |
 * | [AndroidUnifiedParticipantLifecyclePhase] | 本合约与生命周期阶段字段互补；两者均不互相取代 |
 *
 * ## V2 消费合约
 *
 * V2 的以下消费路径 MUST 直接读取本合约的 wire 字段，而非通过组合推断：
 * - `core/participation_tier_router.py`：读取 `participation_mode_class`，替代跨域字段组合
 * - `core/android_device_state_store.py`：读取 `local_execution_active` 作为执行忙指示器
 * - `panels/operator_board.py`：读取 `local_execution_activity_kind` 区分本地辅助与委托执行
 * - `core/android_runtime_transition_reducer.py`：读取 `participation_mode_class` 驱动模式迁移减法器
 *
 * @see AndroidAuthoritativeParticipationTruth
 * @see AndroidUnifiedTruthUplinkContract
 * @see LocalExecutionModeGate
 * @see AndroidUnifiedParticipantLifecyclePhase
 */
object AndroidParticipationSemanticNormalizationContract {

    /** Wire-map schema 版本。 */
    const val SCHEMA_VERSION = "1"

    // ── 参与模式分类 ───────────────────────────────────────────────────────────

    /**
     * Android 当前参与模式的统一语义分类。
     *
     * 为 V2 提供单一、稳定的模式分类标签，消除跨域字段组合推断的需要。
     *
     * | [ParticipationModeClass]        | wireValue                    | 语义 |
     * |--------------------------------|------------------------------|------|
     * | [LOCAL_ONLY_IDLE]              | `local_only_idle`            | 本地模式，无活跃执行 |
     * | [LOCAL_ONLY_EXECUTING]         | `local_only_executing`       | 本地模式，任务执行中 |
     * | [CROSS_DEVICE_READY]           | `cross_device_ready`         | 跨设备模式，等待调度 |
     * | [DISTRIBUTED_EXECUTING]        | `distributed_executing`      | 跨设备模式，委托执行中 |
     * | [TAKEOVER_EXECUTING]           | `takeover_executing`         | 接管执行中 |
     * | [DEGRADED]                     | `degraded`                   | 运行时降级，参与受限 |
     * | [CONSTRAINED]                  | `constrained`                | 受约束，不可分发 |
     * | [UNAVAILABLE]                  | `unavailable`                | 未参与，无执行可能 |
     *
     * @property wireValue  稳定小写 wire 字符串；出现在 `participation_mode_class` 快照字段中。
     * @property isActivelyExecuting  是否存在活跃的本地或分布式执行任务。
     * @property isDistributedParticipant  是否作为分布式执行节点活跃参与。
     * @property acceptsNewDispatch  V2 在此分类下是否可向 Android 派发新任务。
     */
    enum class ParticipationModeClass(
        val wireValue: String,
        val isActivelyExecuting: Boolean,
        val isDistributedParticipant: Boolean,
        val acceptsNewDispatch: Boolean
    ) {
        /**
         * 本地模式，无活跃执行。
         *
         * Android 处于本地（非跨设备）模式，当前无任务正在执行。
         * V2 MUST NOT 向 Android 派发跨设备任务；Android 可接受本地用户操作。
         */
        LOCAL_ONLY_IDLE(
            wireValue = "local_only_idle",
            isActivelyExecuting = false,
            isDistributedParticipant = false,
            acceptsNewDispatch = false
        ),

        /**
         * 本地模式，任务执行中。
         *
         * Android 处于本地模式，且有任务正在执行（本地辅助或本地用户触发）。
         * V2 MUST NOT 派发新跨设备任务；执行完成后 Android 仍处于本地模式。
         */
        LOCAL_ONLY_EXECUTING(
            wireValue = "local_only_executing",
            isActivelyExecuting = true,
            isDistributedParticipant = false,
            acceptsNewDispatch = false
        ),

        /**
         * 跨设备模式，等待调度。
         *
         * Android 处于跨设备模式，参与层级为 `dispatch_eligible`，当前无活跃委托执行。
         * V2 MAY 向 Android 派发新委托任务。
         */
        CROSS_DEVICE_READY(
            wireValue = "cross_device_ready",
            isActivelyExecuting = false,
            isDistributedParticipant = false,
            acceptsNewDispatch = true
        ),

        /**
         * 跨设备模式，委托执行中。
         *
         * Android 正作为分布式执行节点执行 V2 委托的任务，参与层级为
         * `distributed_participant` 或 `delegated_execution_active=true`。
         * V2 SHOULD NOT 派发新任务，直到当前委托任务完成。
         *
         * **关键区分**：与 [LOCAL_ONLY_EXECUTING] 的区别在于，此分类下 Android
         * 是分布式执行主链的活跃节点，执行结果将进入 V2 的统一结果消费路径。
         */
        DISTRIBUTED_EXECUTING(
            wireValue = "distributed_executing",
            isActivelyExecuting = true,
            isDistributedParticipant = true,
            acceptsNewDispatch = false
        ),

        /**
         * 接管执行中。
         *
         * Android 正在执行 V2 发起的接管任务（takeover_state = "active"）。
         * V2 MUST NOT 派发其他类型任务；接管完成后 Android 可回到 [CROSS_DEVICE_READY]。
         */
        TAKEOVER_EXECUTING(
            wireValue = "takeover_executing",
            isActivelyExecuting = true,
            isDistributedParticipant = true,
            acceptsNewDispatch = false
        ),

        /**
         * 运行时降级，参与受限。
         *
         * Android 运行时处于降级状态（LocalCapabilityState = DEGRADED / UNAVAILABLE）或
         * 治理层已阻断参与（governance_blocked = true）。
         * V2 MUST NOT 派发需要完整能力的任务；降级路径可能仍可接受有限任务。
         */
        DEGRADED(
            wireValue = "degraded",
            isActivelyExecuting = false,
            isDistributedParticipant = false,
            acceptsNewDispatch = false
        ),

        /**
         * 受约束，不可分发。
         *
         * Android 当前处于显式运行时约束（runtime_constrained = true）或
         * 保持状态（runtime_deferred = true），分发必须延迟。
         * V2 MUST 等待约束解除后再恢复分发。
         */
        CONSTRAINED(
            wireValue = "constrained",
            isActivelyExecuting = false,
            isDistributedParticipant = false,
            acceptsNewDispatch = false
        ),

        /**
         * 未参与，无执行可能。
         *
         * Android 当前未处于任何有效参与状态（跨设备未启用、WS 未连接、注册未完成等）。
         * V2 MUST NOT 向 Android 派发任何任务。
         */
        UNAVAILABLE(
            wireValue = "unavailable",
            isActivelyExecuting = false,
            isDistributedParticipant = false,
            acceptsNewDispatch = false
        );

        companion object {
            /** 从 wire 值解析 [ParticipationModeClass]；未知值返回 null。 */
            fun fromWireValue(wireValue: String?): ParticipationModeClass? =
                entries.firstOrNull { it.wireValue == wireValue }

            /** 所有稳定 wire 值集合；用于 V2 侧词汇表校验。 */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── 本地执行活跃种类 ───────────────────────────────────────────────────────

    /**
     * Android 本地执行活跃的语义种类。
     *
     * 消除"Android 正在执行任务"与"Android 作为分布式节点参与执行"之间的语义混叠。
     *
     * | [LocalExecutionActivityKind] | wireValue                  | 语义 |
     * |------------------------------|----------------------------|------|
     * | [NONE]                       | `none`                     | 无活跃本地执行 |
     * | [LOCAL_ASSISTIVE]            | `local_assistive`          | 本地辅助执行（非分布式路径） |
     * | [DELEGATED_PARTICIPANT]      | `delegated_participant`    | 委托执行参与（分布式主链） |
     * | [TAKEOVER_PARTICIPANT]       | `takeover_participant`     | 接管执行（V2 接管路径） |
     *
     * @property wireValue            稳定 wire 字符串；出现在 `local_execution_activity_kind` 字段中。
     * @property isLocalExecution     是否存在活跃的本地设备执行（无论路径）。
     * @property isDistributedPathActive 是否处于分布式执行路径（委托或接管）。
     */
    enum class LocalExecutionActivityKind(
        val wireValue: String,
        val isLocalExecution: Boolean,
        val isDistributedPathActive: Boolean
    ) {
        /**
         * 无活跃本地执行。
         *
         * Android 当前无任务执行中（本地和分布式路径均空闲）。
         */
        NONE(
            wireValue = "none",
            isLocalExecution = false,
            isDistributedPathActive = false
        ),

        /**
         * 本地辅助执行。
         *
         * Android 正在执行本地任务（用户直接发起或本地辅助路径），
         * 该执行不属于 V2 委托的分布式主链。
         * V2 MUST NOT 将此类活动计入分布式参与统计。
         */
        LOCAL_ASSISTIVE(
            wireValue = "local_assistive",
            isLocalExecution = true,
            isDistributedPathActive = false
        ),

        /**
         * 委托执行参与。
         *
         * Android 正在执行 V2 委托的任务，作为分布式执行主链的活跃节点。
         * 执行结果将通过 `goal_result` / `device_execution_event` 进入 V2 的
         * 统一结果消费路径（`core/unified_result_ingress.py`）。
         */
        DELEGATED_PARTICIPANT(
            wireValue = "delegated_participant",
            isLocalExecution = true,
            isDistributedPathActive = true
        ),

        /**
         * 接管执行。
         *
         * Android 正在执行 V2 发起的接管任务（takeover 路径）。
         * 与 [DELEGATED_PARTICIPANT] 的区别在于接管任务由 V2 主动控制，
         * Android 在此路径下是 V2 接管语义的执行载体而非委托接收方。
         */
        TAKEOVER_PARTICIPANT(
            wireValue = "takeover_participant",
            isLocalExecution = true,
            isDistributedPathActive = true
        );

        companion object {
            /** 从 wire 值解析；未知值返回 null。 */
            fun fromWireValue(wireValue: String?): LocalExecutionActivityKind? =
                entries.firstOrNull { it.wireValue == wireValue }

            /** 所有稳定 wire 值集合。 */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── 推导输入 ──────────────────────────────────────────────────────────────

    /**
     * 参与语义规范化推导所需的运行时信号输入。
     *
     * 所有字段均来自 Android 现有运行时信号，无需新探针。
     *
     * @param localModeActive             是否处于本地模式（execution_mode_state = "local_only"）。
     * @param executionBusy               Android 是否正在执行任意任务（raw execution_busy 标志）。
     * @param distributedParticipant      参与层级是否为 distributed_participant。
     * @param delegatedExecutionActive    治理状态：是否有活跃委托执行。
     * @param takeoverStateWire           接管状态 wire 值（"inactive" | "pending" | "active"）。
     * @param runtimeConstrained          是否因运行时约束无法正常分发。
     * @param runtimeDeferred             是否处于延迟/保持状态。
     * @param governanceBlocked           治理层是否显式阻断参与。
     * @param crossDeviceEnabled          跨设备是否已启用。
     * @param dispatchEligible            参与层级是否为 dispatch_eligible 或 distributed_participant。
     * @param localCapabilityStateWire    本地能力状态 wire 值（来自 LocalCapabilityState）。
     */
    data class NormalizationDerivationInput(
        val localModeActive: Boolean,
        val executionBusy: Boolean,
        val distributedParticipant: Boolean,
        val delegatedExecutionActive: Boolean,
        val takeoverStateWire: String?,
        val runtimeConstrained: Boolean,
        val runtimeDeferred: Boolean,
        val governanceBlocked: Boolean,
        val crossDeviceEnabled: Boolean,
        val dispatchEligible: Boolean,
        val localCapabilityStateWire: String?
    )

    // ── 规范化快照 ─────────────────────────────────────────────────────────────

    /**
     * 参与语义规范化快照。
     *
     * 由 [derive] 从 [NormalizationDerivationInput] 推导得出。
     *
     * @param participationModeClass      统一参与模式分类。
     * @param localExecutionActivityKind  本地执行活跃种类。
     * @param localExecutionActive        是否存在活跃的本地执行（等价于 [LocalExecutionActivityKind.isLocalExecution]）。
     * @param modeSignalAmbiguityResolved 模式信号歧义是否已解决（输入信号集是否内部一致）。
     * @param schemaVersion               快照 schema 版本；固定为 [SCHEMA_VERSION]。
     */
    data class NormalizationSnapshot(
        val participationModeClass: ParticipationModeClass,
        val localExecutionActivityKind: LocalExecutionActivityKind,
        val localExecutionActive: Boolean,
        val modeSignalAmbiguityResolved: Boolean,
        val schemaVersion: String = SCHEMA_VERSION
    ) {
        /**
         * 将快照序列化为 wire map，用于嵌入快照载体或诊断载体。
         *
         * 所有字段均有保底默认值，永不返回含 null 值的 map。
         */
        fun toWireMap(): Map<String, Any> = mapOf(
            KEY_PARTICIPATION_MODE_CLASS to participationModeClass.wireValue,
            KEY_LOCAL_EXECUTION_ACTIVITY_KIND to localExecutionActivityKind.wireValue,
            KEY_LOCAL_EXECUTION_ACTIVE to localExecutionActive,
            KEY_MODE_SIGNAL_AMBIGUITY_RESOLVED to modeSignalAmbiguityResolved,
            KEY_SCHEMA_VERSION to schemaVersion
        )
    }

    // ── Wire key 常量 ─────────────────────────────────────────────────────────

    /** DeviceStateSnapshotPayload / DeviceExecutionEventPayload 中的 wire 字段键：参与模式分类。 */
    const val KEY_PARTICIPATION_MODE_CLASS = "participation_mode_class"

    /** DeviceStateSnapshotPayload / DeviceExecutionEventPayload 中的 wire 字段键：本地执行活跃种类。 */
    const val KEY_LOCAL_EXECUTION_ACTIVITY_KIND = "local_execution_activity_kind"

    /** DeviceStateSnapshotPayload / DeviceExecutionEventPayload 中的 wire 字段键：本地执行是否活跃。 */
    const val KEY_LOCAL_EXECUTION_ACTIVE = "local_execution_active"

    /** DeviceStateSnapshotPayload / DeviceExecutionEventPayload 中的 wire 字段键：模式信号歧义已解决。 */
    const val KEY_MODE_SIGNAL_AMBIGUITY_RESOLVED = "mode_signal_ambiguity_resolved"

    /** DeviceStateSnapshotPayload / DeviceExecutionEventPayload 中的 wire 字段键：schema 版本。 */
    const val KEY_SCHEMA_VERSION = "participation_semantic_schema_version"

    // ── 推导输入 wire 值常量（避免 derive() 中的硬编码字符串）──────────────────────

    /**
     * 接管状态 wire 值：接管执行活跃。
     *
     * 对应 AndroidUnifiedTruthUplinkContract.TakeoverState.ACTIVE.wireValue。
     * 在 [derive] 中用于检测接管路径。
     */
    const val TAKEOVER_STATE_ACTIVE = "active"

    /**
     * 本地能力状态 wire 值：降级。
     *
     * 对应 AndroidUnifiedTruthUplinkContract.LocalCapabilityState.DEGRADED.wireValue。
     * 在 [derive] 中用于检测降级路径。
     */
    const val LOCAL_CAPABILITY_STATE_DEGRADED = "degraded"

    /**
     * 本地能力状态 wire 值：不可用。
     *
     * 对应 AndroidUnifiedTruthUplinkContract.LocalCapabilityState.UNAVAILABLE.wireValue。
     * 在 [derive] 中用于检测降级路径。
     */
    const val LOCAL_CAPABILITY_STATE_UNAVAILABLE = "unavailable"

    // ── 推导函数 ──────────────────────────────────────────────────────────────

    /**
     * 从运行时信号推导参与语义规范化快照。
     *
     * 推导优先级（高到低）：
     * 1. 不可用（未参与）：[crossDeviceEnabled] = false。
     * 2. 接管执行中：[takeoverStateWire] = "active"。
     * 3. 受约束：[runtimeConstrained] = true。
     * 4. 降级：[governanceBlocked] = true，或 localCapabilityState 为 degraded/unavailable。
     * 5. 分布式委托执行中：[distributedParticipant] = true 或 [delegatedExecutionActive] = true。
     * 6. 本地模式执行中：[localModeActive] = true 且 [executionBusy] = true。
     * 7. 本地模式空闲：[localModeActive] = true 且 [executionBusy] = false。
     * 8. 跨设备等待调度：[dispatchEligible] = true（且不在以上任何活跃执行状态）。
     * 9. 延迟/保持：[runtimeDeferred] = true。
     * 10. 默认：UNAVAILABLE。
     *
     * @param input 推导输入信号集。
     * @return 规范化快照。
     */
    fun derive(input: NormalizationDerivationInput): NormalizationSnapshot {
        val isCapabilityDegraded = input.localCapabilityStateWire == LOCAL_CAPABILITY_STATE_DEGRADED ||
            input.localCapabilityStateWire == LOCAL_CAPABILITY_STATE_UNAVAILABLE

        val participationModeClass = when {
            !input.crossDeviceEnabled && !input.localModeActive ->
                ParticipationModeClass.UNAVAILABLE
            input.takeoverStateWire == TAKEOVER_STATE_ACTIVE ->
                ParticipationModeClass.TAKEOVER_EXECUTING
            input.runtimeConstrained ->
                ParticipationModeClass.CONSTRAINED
            input.governanceBlocked || isCapabilityDegraded ->
                ParticipationModeClass.DEGRADED
            (input.distributedParticipant || input.delegatedExecutionActive) &&
                input.crossDeviceEnabled ->
                ParticipationModeClass.DISTRIBUTED_EXECUTING
            input.localModeActive && input.executionBusy ->
                ParticipationModeClass.LOCAL_ONLY_EXECUTING
            input.localModeActive && !input.executionBusy ->
                ParticipationModeClass.LOCAL_ONLY_IDLE
            input.dispatchEligible && input.crossDeviceEnabled ->
                ParticipationModeClass.CROSS_DEVICE_READY
            input.runtimeDeferred ->
                ParticipationModeClass.CONSTRAINED
            else ->
                ParticipationModeClass.UNAVAILABLE
        }

        val localExecutionActivityKind = when {
            input.takeoverStateWire == TAKEOVER_STATE_ACTIVE && input.executionBusy ->
                LocalExecutionActivityKind.TAKEOVER_PARTICIPANT
            (input.distributedParticipant || input.delegatedExecutionActive) &&
                input.executionBusy ->
                LocalExecutionActivityKind.DELEGATED_PARTICIPANT
            input.executionBusy ->
                LocalExecutionActivityKind.LOCAL_ASSISTIVE
            else ->
                LocalExecutionActivityKind.NONE
        }

        // 模式信号歧义检测：当 localModeActive 与 crossDeviceEnabled 同时为 true，
        // 或 distributedParticipant 为 true 但 executionBusy 为 false，
        // 表明输入信号存在潜在不一致（仍可正常推导，但 V2 需注意可能存在时序问题）。
        val modeSignalAmbiguityResolved = !(
            (input.localModeActive && input.crossDeviceEnabled) ||
                (input.distributedParticipant && !input.executionBusy)
        )

        return NormalizationSnapshot(
            participationModeClass = participationModeClass,
            localExecutionActivityKind = localExecutionActivityKind,
            localExecutionActive = localExecutionActivityKind.isLocalExecution,
            modeSignalAmbiguityResolved = modeSignalAmbiguityResolved
        )
    }

    // ── 形式化不变量 ───────────────────────────────────────────────────────────

    /**
     * 参与语义规范化合约的形式化不变量。
     *
     * V2 消费方 MUST 依赖这些不变量，Android 侧在所有上行路径中 MUST 保证。
     */
    val NORMALIZATION_INVARIANTS: List<String> = listOf(
        // INV-PSN-01：participation_mode_class 永不为 null；当无法推断时默认 unavailable。
        "INV-PSN-01: participation_mode_class is never null; defaults to unavailable when unresolvable.",
        // INV-PSN-02：local_execution_activity_kind 永不为 null；无执行时为 none。
        "INV-PSN-02: local_execution_activity_kind is never null; defaults to none when no execution is active.",
        // INV-PSN-03：local_execution_active = (local_execution_activity_kind != none)。
        "INV-PSN-03: local_execution_active == (local_execution_activity_kind != none).",
        // INV-PSN-04：当 participation_mode_class = DISTRIBUTED_EXECUTING 或 TAKEOVER_EXECUTING 时，
        // local_execution_activity_kind 为 DELEGATED_PARTICIPANT 或 TAKEOVER_PARTICIPANT。
        "INV-PSN-04: when participation_mode_class is distributed_executing or takeover_executing, " +
            "local_execution_activity_kind is delegated_participant or takeover_participant.",
        // INV-PSN-05：当 participation_mode_class = LOCAL_ONLY_EXECUTING 时，
        // local_execution_activity_kind 为 LOCAL_ASSISTIVE。
        "INV-PSN-05: when participation_mode_class is local_only_executing, " +
            "local_execution_activity_kind is local_assistive.",
        // INV-PSN-06：当 participation_mode_class = CROSS_DEVICE_READY 时，
        // local_execution_active = false（等待调度，无执行中任务）。
        "INV-PSN-06: when participation_mode_class is cross_device_ready, local_execution_active is false.",
        // INV-PSN-07：acceptsNewDispatch = true 当且仅当 participation_mode_class = CROSS_DEVICE_READY。
        "INV-PSN-07: acceptsNewDispatch is true if and only if participation_mode_class is cross_device_ready.",
        // INV-PSN-08：isDistributedParticipant = true 当且仅当
        // participation_mode_class 为 DISTRIBUTED_EXECUTING 或 TAKEOVER_EXECUTING。
        "INV-PSN-08: isDistributedParticipant is true if and only if " +
            "participation_mode_class is distributed_executing or takeover_executing.",
        // INV-PSN-09：participation_semantic_schema_version 永不为 null；始终为 SCHEMA_VERSION。
        "INV-PSN-09: participation_semantic_schema_version is never null; always equals SCHEMA_VERSION.",
        // INV-PSN-10：V2 MUST 直接读取 participation_mode_class 而非通过字段组合推断参与模式；
        // 字段组合推断被视为语义漂移来源，违反本合约。
        "INV-PSN-10: V2 MUST read participation_mode_class directly; " +
            "field-combination inference of participation mode is a semantic-drift source and violates this contract."
    )
}
