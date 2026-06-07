package com.ufo.galaxy.runtime

/**
 * PR-4Android — Android 工程边界可靠性合约。
 *
 * ## 解决的问题
 *
 * 在此合约引入之前，Android 侧上行消息（`device_execution_event`、`device_state_snapshot`）
 * 缺少对以下三类工程边界属性的机器可读声明：
 *
 * 1. **异步范围不透明**：GalaxyConnectionService 中的协程启动方式多种多样
 *    （`serviceScope.launch`、`withTimeout`、生命周期绑定范围等），
 *    但 V2 无法从上行消息中判断事件是从哪种异步边界发出的，
 *    导致网络断开时丢失事件的风险无法被 V2 侧识别和处理。
 *
 * 2. **来源字段覆盖等级不可观测**：V2 消费 Android 上行消息时依赖
 *    `device_id`、`source_component`、`task_id`、`runtime_session_id` 等字段进行
 *    路由和审计关联，但这些字段的完整性在不同发送路径上不一致，
 *    V2 无法区分"字段可信填充"与"字段防御性默认缺省"。
 *
 * 3. **权限检查模式隐式**：Android 侧在执行委托任务、接管任务或处理 operator action 时
 *    均已通过治理合约进行权限检查，但上行事件中没有显式声明检查模式，
 *    V2 下游审计链无法区分"通过形式化合约门控"与"依赖上下文隐式假设"。
 *
 * ## 合约目标
 *
 * [AndroidBoundaryReliabilityContract] 在现有运行时信号之上引入三类声明式分类：
 *
 * - [AsyncScopeClass]：4 值枚举，声明协程的生命周期绑定类型，
 *   使 V2 可识别来自受限范围（[SERVICE_SCOPED]）与潜在脱管范围（[DETACHED_FIRE_AND_FORGET]）的事件。
 * - [SourceFieldCoverageClass]：3 值枚举，声明上行消息中来源字段的完整性等级，
 *   使 V2 可区分高置信度事件（[COMPLETE]）与部分填充事件（[PARTIAL]）。
 * - [AuthorityBoundaryCheckMode]：4 值枚举，声明权限检查的显式程度，
 *   使 V2 审计链可区分"形式化合约门控"（[EXPLICIT_CONTRACT_GATE]）与"隐式假设"（[ASSUMED_IMPLICIT]）。
 *
 * ## 与现有合约的关系
 *
 * | 现有合约 | 本合约的补充关系 |
 * |---|---|
 * | [AndroidRuntimeObservabilityAuditContract] | 本合约补充了可观测性审计的工程边界维度，观测性审计关注"什么路径"，本合约关注"如何边界" |
 * | [AndroidParticipationSemanticNormalizationContract] | 本合约在参与语义之外声明发送边界属性，两者互补 |
 * | [AndroidUnifiedTruthUplinkContract] | 本合约为上行真相消息添加边界元数据，不修改真相推导逻辑 |
 * | [AndroidExecutionGovernanceContract] | 本合约将治理合约的检查结果以 [AuthorityBoundaryCheckMode] 形式暴露到上行消息中 |
 *
 * ## V2 消费合约
 *
 * V2 的以下消费路径 MUST 利用本合约字段改善消费质量：
 * - `core/android_device_state_store.py`：读取 `source_field_coverage_class`，过滤 ABSENT 等级的快照
 * - `core/problem_solving_audit_chain.py`：读取 `authority_boundary_check_mode`，区分形式化门控与隐式检查
 * - `core/android_runtime_transition_reducer.py`：读取 `async_scope_class`，识别 DETACHED_FIRE_AND_FORGET 事件并应用额外延迟容忍
 * - `metrics/android_reliability_metrics.py`：读取 `source_field_coverage_class` 计算 Android 上报完整率 SLO
 *
 * @see AndroidRuntimeObservabilityAuditContract
 * @see AndroidParticipationSemanticNormalizationContract
 * @see AndroidUnifiedTruthUplinkContract
 * @see AndroidExecutionGovernanceContract
 */
object AndroidBoundaryReliabilityContract {

    /** Wire-map schema 版本。 */
    const val SCHEMA_VERSION = "1"

    // ── Wire 字段 key 常量 ─────────────────────────────────────────────────────

    /** Wire 字段：协程异步范围分类。 */
    const val KEY_ASYNC_SCOPE_CLASS = "async_scope_class"

    /** Wire 字段：来源字段覆盖等级。 */
    const val KEY_SOURCE_FIELD_COVERAGE_CLASS = "source_field_coverage_class"

    /** Wire 字段：权限检查模式。 */
    const val KEY_AUTHORITY_BOUNDARY_CHECK_MODE = "authority_boundary_check_mode"

    /** Wire 字段：合约 schema 版本。 */
    const val KEY_BOUNDARY_RELIABILITY_SCHEMA_VERSION = "boundary_reliability_schema_version"

    // ── 必需来源字段声明 ─────────────────────────────────────────────────────────

    /**
     * V2 消费 `device_execution_event` 时所需的最小来源字段集合。
     *
     * 这些字段缺失会导致 V2 路由、审计或关联失败。
     * [classifySourceFieldCoverage] 使用这些字段计算 [SourceFieldCoverageClass]。
     */
    val REQUIRED_EXECUTION_EVENT_SOURCE_FIELDS: Set<String> = setOf(
        "device_id",
        "source_component",
        "task_id",
        "event_id"
    )

    /**
     * V2 消费 `device_state_snapshot` 时所需的最小来源字段集合。
     *
     * 快照没有 `source_component` 和 `task_id`，
     * 因此来源字段集合仅包含设备和会话标识。
     */
    val REQUIRED_SNAPSHOT_SOURCE_FIELDS: Set<String> = setOf(
        "device_id",
        "runtime_session_id"
    )

    // ── 异步范围分类 ───────────────────────────────────────────────────────────

    /**
     * 发送该上行消息的协程的生命周期绑定类型。
     *
     * 使 V2 可识别来自受限范围与潜在脱管范围的事件，
     * 并在网络故障或进程终止场景下应用不同的延迟容忍策略。
     *
     * | [AsyncScopeClass]          | wireValue                    | 生命周期绑定 | 显式超时 |
     * |---------------------------|------------------------------|------------|--------|
     * | [SERVICE_SCOPED]          | `service_scoped`             | 服务生命周期 | 否 |
     * | [TIMEOUT_GUARDED]         | `timeout_guarded`            | 超时边界     | 是 |
     * | [LIFECYCLE_BOUND]         | `lifecycle_bound`            | 组件生命周期 | 否 |
     * | [DETACHED_FIRE_AND_FORGET]| `detached_fire_and_forget`   | 无          | 否 |
     *
     * @property wireValue           稳定小写 wire 字符串；出现在 `async_scope_class` 字段中。
     * @property isBounded           是否绑定到明确的生命周期范围。
     * @property hasExplicitTimeout  是否存在显式超时边界（`withTimeout`/`withTimeoutOrNull`）。
     */
    enum class AsyncScopeClass(
        val wireValue: String,
        val isBounded: Boolean,
        val hasExplicitTimeout: Boolean
    ) {
        /**
         * 服务范围协程。
         *
         * 发送此消息的协程启动于 `serviceScope`（SupervisorJob + Dispatchers.Default），
         * 生命周期与 GalaxyConnectionService 绑定。服务销毁时协程取消。
         * 这是 GalaxyConnectionService 中所有上行发送的默认范围。
         */
        SERVICE_SCOPED(
            wireValue = "service_scoped",
            isBounded = true,
            hasExplicitTimeout = false
        ),

        /**
         * 显式超时守护协程。
         *
         * 发送此消息的协程位于 `withTimeout`/`withTimeoutOrNull` 块内，
         * 存在明确的时间边界。超时到达时协程取消，上层任务收到 `TimeoutCancellationException`。
         * V2 在收到此类范围的事件时应应用更宽松的延迟容忍（超时期间事件可能丢失）。
         */
        TIMEOUT_GUARDED(
            wireValue = "timeout_guarded",
            isBounded = true,
            hasExplicitTimeout = true
        ),

        /**
         * 组件生命周期绑定协程。
         *
         * 发送此消息的协程启动于 `viewModelScope`/`lifecycleScope` 等
         * 与 UI 组件生命周期绑定的范围中。
         * 组件销毁（Activity/Fragment/ViewModel clear）时协程取消。
         * 此类事件通常来自 UI 触发的状态更新路径。
         */
        LIFECYCLE_BOUND(
            wireValue = "lifecycle_bound",
            isBounded = true,
            hasExplicitTimeout = false
        ),

        /**
         * 脱管即发即弃协程。
         *
         * 发送此消息的协程无明确的生命周期绑定（如 `GlobalScope.launch`），
         * 或属于在父作用域取消后继续执行的脱离协程。
         * V2 MUST 对此类范围的事件应用额外的幂等性和重复检测。
         *
         * **INV-BR-01**：执行事件 MUST NOT 使用此范围作为终态结果的发送范围。
         */
        DETACHED_FIRE_AND_FORGET(
            wireValue = "detached_fire_and_forget",
            isBounded = false,
            hasExplicitTimeout = false
        );

        companion object {
            /** 从 wire 值解析 [AsyncScopeClass]；未知值返回 null。 */
            fun fromWireValue(wireValue: String?): AsyncScopeClass? =
                entries.firstOrNull { it.wireValue == wireValue }

            /** 所有稳定 wire 值集合。 */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── 来源字段覆盖等级 ────────────────────────────────────────────────────────

    /**
     * 上行消息中 V2 关键路由和审计字段的完整性等级。
     *
     * V2 消费方可使用此字段在摄取阶段过滤低质量消息，
     * 避免因来源字段缺失导致的路由错误或审计链断裂。
     *
     * | [SourceFieldCoverageClass] | wireValue    | 可信度 |
     * |--------------------------|--------------|------|
     * | [COMPLETE]               | `complete`   | 高    |
     * | [PARTIAL]                | `partial`    | 中    |
     * | [ABSENT]                 | `absent`     | 低    |
     *
     * @property wireValue  稳定 wire 字符串；出现在 `source_field_coverage_class` 字段中。
     * @property isReliable 是否满足 V2 最低路由可信度要求。
     */
    enum class SourceFieldCoverageClass(
        val wireValue: String,
        val isReliable: Boolean
    ) {
        /**
         * 完整覆盖。
         *
         * 所有 V2 路由和审计所需的来源字段均存在且非空。
         * 对于执行事件：device_id + source_component + task_id 均存在。
         * 对于快照：device_id + runtime_session_id 均存在。
         */
        COMPLETE(
            wireValue = "complete",
            isReliable = true
        ),

        /**
         * 部分覆盖。
         *
         * 关键标识字段（device_id）存在，但补充来源字段（如 runtime_session_id
         * 或 source_component）部分缺失。V2 可路由此消息但审计链完整性降低。
         */
        PARTIAL(
            wireValue = "partial",
            isReliable = true
        ),

        /**
         * 覆盖缺失。
         *
         * 一个或多个关键 V2 路由字段（device_id 或 task_id）缺失或为空。
         * V2 MUST NOT 将此类消息用于权威状态更新；
         * 应记录为异常并触发诊断流程。
         *
         * **INV-BR-02**：交付至 V2 的执行事件 MUST NOT 具有 ABSENT 等级。
         */
        ABSENT(
            wireValue = "absent",
            isReliable = false
        );

        companion object {
            /** 从 wire 值解析；未知值返回 null。 */
            fun fromWireValue(wireValue: String?): SourceFieldCoverageClass? =
                entries.firstOrNull { it.wireValue == wireValue }

            /** 所有稳定 wire 值集合。 */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── 权限检查模式 ───────────────────────────────────────────────────────────

    /**
     * 在发送此上行消息之前，Android 侧对执行权限进行检查的显式程度。
     *
     * V2 的问题解决审计链（`problem_solving_audit_chain.py`）可以利用此字段
     * 区分经过形式化合约门控的执行与依赖隐式上下文假设的执行，
     * 从而为形式化门控路径提供更高的置信度评分。
     *
     * | [AuthorityBoundaryCheckMode] | wireValue               | 显式检查 |
     * |-----------------------------|------------------------|--------|
     * | [EXPLICIT_CONTRACT_GATE]    | `explicit_contract_gate`| 是     |
     * | [GOVERNANCE_VALIDATED]      | `governance_validated`  | 是     |
     * | [AUDIT_TRAIL_ONLY]          | `audit_trail_only`      | 否     |
     * | [ASSUMED_IMPLICIT]          | `assumed_implicit`      | 否     |
     *
     * @property wireValue           稳定 wire 字符串；出现在 `authority_boundary_check_mode` 字段中。
     * @property isExplicitlyChecked 是否在发送前进行了显式的权限/治理检查。
     */
    enum class AuthorityBoundaryCheckMode(
        val wireValue: String,
        val isExplicitlyChecked: Boolean
    ) {
        /**
         * 形式化合约门控。
         *
         * 执行前通过形式化 Android 治理合约（如 [AndroidExecutionGovernanceContract]、
         * [AndroidOperatorActionGovernanceContract]）进行了显式门控检查。
         * 拒绝情形已生成结构化错误响应，而非静默通过。
         * 这是最高级别的权限检查模式。
         */
        EXPLICIT_CONTRACT_GATE(
            wireValue = "explicit_contract_gate",
            isExplicitlyChecked = true
        ),

        /**
         * 治理层验证。
         *
         * 执行前通过 [DelegatedRuntimeAcceptanceEvaluator]、
         * [DelegatedRuntimeGovernanceSnapshot] 等治理评估器进行了显式验证，
         * 但未通过单独的合约门（合约检查内嵌于治理评估器中）。
         * 这是 GalaxyConnectionService 中大多数委托执行路径的标准模式。
         */
        GOVERNANCE_VALIDATED(
            wireValue = "governance_validated",
            isExplicitlyChecked = true
        ),

        /**
         * 仅审计追踪。
         *
         * 权限信息被记录到审计链中，但未在发送前进行门控检查。
         * 执行依赖于先前已建立的权限上下文，当前发送路径未重新验证。
         */
        AUDIT_TRAIL_ONLY(
            wireValue = "audit_trail_only",
            isExplicitlyChecked = false
        ),

        /**
         * 隐式假设。
         *
         * 未进行显式的权限检查；执行依赖于对运行时上下文的隐式假设
         * （如"如果我们已连接则已授权"）。这是最弱的权限检查模式，
         * 应在可行时升级为 [GOVERNANCE_VALIDATED] 或 [EXPLICIT_CONTRACT_GATE]。
         *
         * **INV-BR-03**：携带治理字段（governance_state 非 null）的执行事件
         * MUST NOT 使用 [ASSUMED_IMPLICIT] 模式。
         */
        ASSUMED_IMPLICIT(
            wireValue = "assumed_implicit",
            isExplicitlyChecked = false
        );

        companion object {
            /** 从 wire 值解析；未知值返回 null。 */
            fun fromWireValue(wireValue: String?): AuthorityBoundaryCheckMode? =
                entries.firstOrNull { it.wireValue == wireValue }

            /** 所有稳定 wire 值集合。 */
            val ALL_WIRE_VALUES: Set<String> = entries.map { it.wireValue }.toSet()
        }
    }

    // ── 推导输入 ──────────────────────────────────────────────────────────────

    /**
     * 工程边界可靠性快照的推导输入。
     *
     * 所有字段均来自 Android 现有运行时状态，无需新探针。
     *
     * @property isServiceScoped        是否在 serviceScope（SupervisorJob）内执行。
     * @property hasTimeoutGuard        是否位于 withTimeout/withTimeoutOrNull 块内。
     * @property isLifecycleBound       是否在 viewModelScope/lifecycleScope 等组件生命周期范围内。
     * @property deviceId               设备 ID（来源字段覆盖检查用）。
     * @property sourceComponent        来源组件名（执行事件特有；快照为 null）。
     * @property taskId                 任务 ID（执行事件特有；快照为 null）。
     * @property runtimeSessionId       运行时会话 ID（快照和事件均有）。
     * @property hasGovernanceContext   是否已存在治理上下文（governance_state 非 null）。
     * @property hasExplicitContractGate 是否在发送前通过了形式化合约门控。
     */
    data class BoundaryReliabilityDerivationInput(
        val isServiceScoped: Boolean,
        val hasTimeoutGuard: Boolean,
        val isLifecycleBound: Boolean,
        val deviceId: String?,
        val sourceComponent: String?,
        val taskId: String?,
        val runtimeSessionId: String?,
        val hasGovernanceContext: Boolean,
        val hasExplicitContractGate: Boolean = false
    )

    // ── 快照输出 ──────────────────────────────────────────────────────────────

    /**
     * 工程边界可靠性推导快照。
     *
     * 包含三个维度的分类结果，可通过 [toWireMap] 序列化为 wire map
     * 嵌入上行消息的边界可靠性字段组。
     */
    data class BoundaryReliabilitySnapshot(
        val asyncScopeClass: AsyncScopeClass,
        val sourceFieldCoverageClass: SourceFieldCoverageClass,
        val authorityBoundaryCheckMode: AuthorityBoundaryCheckMode
    ) {
        /**
         * 将快照序列化为 snake_case key wire map，适合嵌入 DeviceExecutionEventPayload
         * 或 DeviceStateSnapshotPayload 的边界可靠性字段组。
         */
        fun toWireMap(): Map<String, Any?> = mapOf(
            KEY_ASYNC_SCOPE_CLASS to asyncScopeClass.wireValue,
            KEY_SOURCE_FIELD_COVERAGE_CLASS to sourceFieldCoverageClass.wireValue,
            KEY_AUTHORITY_BOUNDARY_CHECK_MODE to authorityBoundaryCheckMode.wireValue,
            KEY_BOUNDARY_RELIABILITY_SCHEMA_VERSION to SCHEMA_VERSION
        )
    }

    // ── 推导函数 ──────────────────────────────────────────────────────────────

    /**
     * 从运行时信号推导 [AsyncScopeClass]。
     *
     * 优先级：显式超时 > 生命周期绑定 > 服务范围 > 脱管即发即弃。
     */
    fun classifyAsyncScope(
        isServiceScoped: Boolean,
        hasTimeoutGuard: Boolean,
        isLifecycleBound: Boolean
    ): AsyncScopeClass {
        if (hasTimeoutGuard) return AsyncScopeClass.TIMEOUT_GUARDED
        if (isLifecycleBound) return AsyncScopeClass.LIFECYCLE_BOUND
        if (isServiceScoped) return AsyncScopeClass.SERVICE_SCOPED
        return AsyncScopeClass.DETACHED_FIRE_AND_FORGET
    }

    /**
     * 从来源字段的存在性推导 [SourceFieldCoverageClass]。
     *
     * 推导逻辑：
     * - deviceId 缺失 → [SourceFieldCoverageClass.ABSENT]（关键路由字段缺失）
     * - 执行事件路径（taskId 非 null）：source_component + task_id 均存在 → [SourceFieldCoverageClass.COMPLETE]
     * - 快照路径（taskId 为 null）：runtime_session_id 存在 → [SourceFieldCoverageClass.COMPLETE]
     * - 其他情况 → [SourceFieldCoverageClass.PARTIAL]
     */
    fun classifySourceFieldCoverage(
        deviceId: String?,
        sourceComponent: String?,
        taskId: String?,
        runtimeSessionId: String?
    ): SourceFieldCoverageClass {
        // INV-BR-06: device_id 是最关键的来源字段，缺失即为 ABSENT
        if (deviceId.isNullOrBlank()) return SourceFieldCoverageClass.ABSENT
        // 执行事件路径：需要 source_component + task_id 两个字段均存在
        if (taskId != null) {
            return if (!taskId.isBlank() && !sourceComponent.isNullOrBlank()) {
                SourceFieldCoverageClass.COMPLETE
            } else {
                SourceFieldCoverageClass.PARTIAL
            }
        }
        // 快照路径：需要 runtime_session_id 存在
        return if (!runtimeSessionId.isNullOrBlank()) {
            SourceFieldCoverageClass.COMPLETE
        } else {
            SourceFieldCoverageClass.PARTIAL
        }
    }

    /**
     * 从治理上下文推导 [AuthorityBoundaryCheckMode]。
     *
     * 优先级：形式化合约门 > 治理层验证 > 隐式假设。
     */
    fun classifyAuthorityBoundaryCheckMode(
        hasGovernanceContext: Boolean,
        hasExplicitContractGate: Boolean
    ): AuthorityBoundaryCheckMode {
        if (hasExplicitContractGate) return AuthorityBoundaryCheckMode.EXPLICIT_CONTRACT_GATE
        if (hasGovernanceContext) return AuthorityBoundaryCheckMode.GOVERNANCE_VALIDATED
        return AuthorityBoundaryCheckMode.ASSUMED_IMPLICIT
    }

    /**
     * 从 [BoundaryReliabilityDerivationInput] 推导完整的 [BoundaryReliabilitySnapshot]。
     */
    fun derive(input: BoundaryReliabilityDerivationInput): BoundaryReliabilitySnapshot {
        val asyncScopeClass = classifyAsyncScope(
            isServiceScoped = input.isServiceScoped,
            hasTimeoutGuard = input.hasTimeoutGuard,
            isLifecycleBound = input.isLifecycleBound
        )
        val sourceFieldCoverageClass = classifySourceFieldCoverage(
            deviceId = input.deviceId,
            sourceComponent = input.sourceComponent,
            taskId = input.taskId,
            runtimeSessionId = input.runtimeSessionId
        )
        val authorityBoundaryCheckMode = classifyAuthorityBoundaryCheckMode(
            hasGovernanceContext = input.hasGovernanceContext,
            hasExplicitContractGate = input.hasExplicitContractGate
        )
        return BoundaryReliabilitySnapshot(
            asyncScopeClass = asyncScopeClass,
            sourceFieldCoverageClass = sourceFieldCoverageClass,
            authorityBoundaryCheckMode = authorityBoundaryCheckMode
        )
    }

    // ── 形式化不变量 ───────────────────────────────────────────────────────────

    /**
     * 工程边界可靠性不变量。
     *
     * @property id           不变量唯一标识符（格式：INV-BR-NN）。
     * @property description  机器可读的不变量描述，V2 可直接用于合规检查。
     */
    data class BoundaryReliabilityInvariant(
        val id: String,
        val description: String
    )

    /**
     * PR-4Android 工程边界可靠性合约的 8 条形式化不变量。
     *
     * 这些不变量声明 V2 可依赖的 Android 侧行为保证，
     * 同时作为 Android 侧测试的验收标准。
     */
    val BOUNDARY_RELIABILITY_INVARIANTS: List<BoundaryReliabilityInvariant> = listOf(
        BoundaryReliabilityInvariant(
            id = "INV-BR-01",
            description = "async_scope_class MUST NOT be detached_fire_and_forget for any execution " +
                "event carrying a terminal outcome (lifecycle_terminal_phase=true); terminal results " +
                "must be emitted from a bounded scope to prevent silent loss on service restart."
        ),
        BoundaryReliabilityInvariant(
            id = "INV-BR-02",
            description = "source_field_coverage_class MUST NOT be absent for any execution event " +
                "delivered to V2; absent coverage means device_id or task_id is missing, making " +
                "the event unroutable in V2 canonical pipelines."
        ),
        BoundaryReliabilityInvariant(
            id = "INV-BR-03",
            description = "authority_boundary_check_mode MUST be explicit_contract_gate or " +
                "governance_validated for all execution events where governance_state is non-null; " +
                "assumed_implicit is not acceptable when governance context is present."
        ),
        BoundaryReliabilityInvariant(
            id = "INV-BR-04",
            description = "timeout_guarded async scope MUST only appear on execution events whose " +
                "blocking_reason contains a timeout indicator (e.g. 'timeout_ms='); using " +
                "timeout_guarded scope on non-timeout-bounded paths is misleading."
        ),
        BoundaryReliabilityInvariant(
            id = "INV-BR-05",
            description = "service_scoped MUST be the default async scope for all uplink events " +
                "emitted from GalaxyConnectionService.deviceExecutionEventSink and " +
                "GalaxyConnectionService.sendDeviceStateSnapshot."
        ),
        BoundaryReliabilityInvariant(
            id = "INV-BR-06",
            description = "complete source field coverage requires device_id to be non-blank AND " +
                "either (source_component + task_id both non-blank) for execution events, or " +
                "(runtime_session_id non-blank) for state snapshots."
        ),
        BoundaryReliabilityInvariant(
            id = "INV-BR-07",
            description = "source_field_coverage_class on DeviceStateSnapshotPayload MUST be at " +
                "least partial (device_id present) when cross_device_eligibility is true; " +
                "absent coverage on active cross-device snapshots is a reliability regression."
        ),
        BoundaryReliabilityInvariant(
            id = "INV-BR-08",
            description = "boundary_reliability_schema_version MUST be present and equal to " +
                "SCHEMA_VERSION on all emitted payloads that include boundary reliability fields; " +
                "missing schema version prevents V2 from applying version-aware consumption logic."
        )
    )
}
