package com.ufo.galaxy.runtime

/**
 * PR-05v2 (Android) — Android 侧结果上行闭环边界合约。
 *
 * 本合约是 V2 仓库 PR-05v2 的 Android 侧联动 PR，与 V2 truth / acceptance / closure
 * 消费主链形成真实闭环。
 *
 * ## 解决的问题
 *
 * 在此合约引入之前，Android 侧上行信号（goal_execution_result、device_execution_event、
 * device_state_snapshot）缺少对以下三类信号语义的机器可读区分：
 *
 * 1. **权威运行时/结果信号（authority runtime/result signal）**：
 *    Android 已完成执行并产生终态结果，V2 MUST 依据此信号关闭任务。
 *    但在此合约之前，V2 无法从上行消息中直接区分"终态权威结果"与"过程中间状态"，
 *    必须依赖 status + result_returned + completion_signaled 字段组合推断，
 *    增加了 V2 侧消费逻辑的复杂度和错误概率。
 *
 * 2. **验收/闭合相关信号（acceptance / closure-related signal）**：
 *    Android 的 closure_ready_for_acceptance 已有布尔字段，但没有统一的机器可读分类
 *    使 V2 acceptance adjudication 路径直接匹配"可接受候选结果"而无需重新推断。
 *
 * 3. **诊断/摘要/UI 可见信号（diagnostics / summary / UI-visible signal）**：
 *    device_state_snapshot 和非终态 device_execution_event 是纯观测性信号，
 *    V2 MUST NOT 用于任务关闭，但此约束此前没有在上行消息中显式声明。
 *
 * ## 合约目标
 *
 * [AndroidResultUplinkBoundaryContract] 在现有结果上行和状态上行信号之上引入：
 *
 * - [ResultSignalClass]：3 值枚举，显式声明每条上行消息属于哪类信号，
 *   使 V2 可无歧义地路由至 truth_closure_chain / acceptance_adjudication / diagnostics_store
 *   三条消费路径之一。
 * - [AcceptanceCandidateClass]：4 值枚举，声明此上行是否为 V2 acceptance 的合法候选，
 *   消除 V2 侧通过布尔字段组合推断验收资格的复杂性。
 * - [UplinkBoundaryDerivationInput]：汇聚来自现有合约的 7 个信号，无需新增运行时探针。
 * - [derive]：按 4 级优先级推导 [UplinkBoundarySnapshot]，保证单一、权威、无歧义输出。
 * - [UplinkBoundarySnapshot.toWireMap]：生成可嵌入上行消息的标准 wire map。
 * - [RESULT_UPLINK_BOUNDARY_INVARIANTS]：6 条形式化不变量，声明 V2 可依赖的 Android 侧保证。
 *
 * ## 信号分类规则
 *
 * | [ResultSignalClass]            | 触发条件                                                          | V2 消费规范                              |
 * |--------------------------------|-------------------------------------------------------------------|------------------------------------------|
 * | [AUTHORITY_RESULT]             | 终态 + result_returned + completion_signaled + 非 hold 状态       | MUST 关闭任务；此结果是 truth anchor     |
 * | [ACCEPTANCE_CLOSURE_SIGNAL]    | closure_ready_for_acceptance=true 但非完整 authority 条件         | MAY 进入 acceptance adjudication         |
 * | [DIAGNOSTICS_INFORMATIONAL]    | 非终态、或纯状态快照、或过程中执行事件                            | MUST NOT 用于任务关闭                    |
 *
 * ## 验收候选分类规则
 *
 * | [AcceptanceCandidateClass]     | 触发条件                                               | V2 acceptance 含义          |
 * |-------------------------------|--------------------------------------------------------|------------------------------|
 * | [ELIGIBLE_FOR_ACCEPTANCE]     | 终态 + closureReady + resultReturned + 非 blocked      | V2 可接受此结果              |
 * | [PENDING_RESULT_RETURN]       | 非终态（执行中）                                       | V2 等待后续终态结果          |
 * | [ACCEPTANCE_BLOCKED]          | 治理阻塞 或 运行时受限 且 closureReady                 | V2 等待约束解除后重新评估    |
 * | [CLOSURE_NOT_APPLICABLE]      | 诊断性上行，无结果/闭合语义                            | V2 记录但不纳入验收链         |
 *
 * ## 与现有合约的关系
 *
 * | 现有合约 | 本合约的补充关系 |
 * |---------|----------------|
 * | [AndroidUnifiedTruthUplinkContract] | 本合约消费 result_returned/completion_signaled/closure_ready_for_acceptance 三个布尔字段并将其收束为单一 ResultSignalClass |
 * | [AndroidNlDrivenExecutionSpineContract] | 本合约消费 ProblemSolvingClosureClass 中的终态语义作为推导输入之一 |
 * | [AndroidMissionCompletionSemanticsContract] | 本合约与 CompletionVisibility 三元组直接对应，提供机器可读的 wire 分类 |
 * | [AndroidBoundaryReliabilityContract] | 本合约关注信号语义分类，后者关注工程边界可靠性；两者在发送层并行填充 |
 * | [AndroidCrossDeviceDispatchBoundaryContract] | 本合约关注结果/闭合信号类型，后者关注 dispatch 来源路径；两者互补 |
 *
 * ## V2 消费合约
 *
 * V2 的以下消费路径 MUST 利用本合约字段建立结果上行一致性：
 * - `core/task_result_canonical_truth_chain.py`：读取 `result_signal_class`，
 *   仅对 `authority_result` 执行任务关闭逻辑
 * - `core/acceptance_adjudication.py`：读取 `acceptance_candidate_class`，
 *   仅对 `eligible_for_acceptance` 进入 acceptance closure 流程
 * - `core/android_device_state_store.py`：读取 `result_signal_class`，
 *   对 `diagnostics_informational` 类信号仅存储而不触发关闭
 * - `core/closure_truth_reconciler.py`：读取 `acceptance_candidate_class`，
 *   对 `acceptance_blocked` 信号保持 task open 并等待约束解除
 *
 * @see AndroidUnifiedTruthUplinkContract
 * @see AndroidMissionCompletionSemanticsContract
 * @see AndroidNlDrivenExecutionSpineContract
 * @see AndroidBoundaryReliabilityContract
 * @see AndroidCrossDeviceDispatchBoundaryContract
 */
object AndroidResultUplinkBoundaryContract {

    /** Wire-map schema 版本。 */
    const val SCHEMA_VERSION = "1"

    // ── Wire 字段 key 常量 ─────────────────────────────────────────────────────

    /** Wire 字段：Android 上行信号的结果信号分类。 */
    const val KEY_RESULT_SIGNAL_CLASS = "result_signal_class"

    /** Wire 字段：Android 上行信号的验收候选分类。 */
    const val KEY_ACCEPTANCE_CANDIDATE_CLASS = "acceptance_candidate_class"

    /** Wire 字段：合约 schema 版本。 */
    const val KEY_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION = "result_uplink_boundary_schema_version"

    // ── 结果信号分类 ─────────────────────────────────────────────────────────────

    /**
     * Android 上行信号的结果信号语义分类。
     *
     * 使 V2 无需字段组合推断即可将上行消息路由至正确的消费路径：
     * truth closure 链、acceptance adjudication，或诊断存储。
     *
     * | [ResultSignalClass]         | wireValue                   | V2 消费行为                              |
     * |-----------------------------|-----------------------------|------------------------------------------|
     * | [AUTHORITY_RESULT]          | `authority_result`          | MUST 关闭任务（truth anchor）            |
     * | [ACCEPTANCE_CLOSURE_SIGNAL] | `acceptance_closure_signal` | MAY 进入 acceptance adjudication         |
     * | [DIAGNOSTICS_INFORMATIONAL] | `diagnostics_informational` | MUST NOT 用于任务关闭                    |
     *
     * @property wireValue         稳定小写 wire 字符串。
     * @property isAuthoritative   此分类是否为权威终态结果（V2 MUST close task）。
     * @property isAcceptanceRelevant 此分类是否与 acceptance closure 相关。
     */
    enum class ResultSignalClass(
        val wireValue: String,
        val isAuthoritative: Boolean,
        val isAcceptanceRelevant: Boolean
    ) {
        /**
         * 权威运行时/结果信号。
         *
         * Android 已完成执行并产生终态结果：
         * - result_returned = true（Android 已产出终态结果）
         * - completion_signaled = true（Android 已发出完成侧信号）
         * - closure_ready_for_acceptance = true（此结果可进入验收闭合）
         * - 非 hold/deferred 状态（非暂态不可用）
         *
         * V2 的 task_result_canonical_truth_chain MUST 将此分类的信号视为
         * 任务关闭的真相锚点（truth anchor），不得忽略或待确认。
         *
         * **这是 Android 作为强运行时节点的首要信号类型。**
         */
        AUTHORITY_RESULT(
            wireValue = "authority_result",
            isAuthoritative = true,
            isAcceptanceRelevant = false
        ),

        /**
         * 验收/闭合相关信号。
         *
         * Android 的 closure_ready_for_acceptance=true，但满足以下任一条件
         * 导致未达到完整 authority 门槛：
         * - governance_blocked=true（治理阻塞，需等待解除后确认）
         * - runtime_constrained=true（资源/健康约束，结果置信度降低）
         * - result_returned 或 completion_signaled 之一为 false
         *
         * V2 的 acceptance_adjudication MUST 将此分类的信号纳入验收流程，
         * 但 MAY 要求额外验证或等待约束解除后再做终态关闭。
         */
        ACCEPTANCE_CLOSURE_SIGNAL(
            wireValue = "acceptance_closure_signal",
            isAuthoritative = false,
            isAcceptanceRelevant = true
        ),

        /**
         * 诊断/摘要/UI 可见信号。
         *
         * 上行消息为纯观测性信号：
         * - 非终态执行事件（过程中间状态：ACTIVATING/ACTIVE/DEGRADED 等）
         * - 设备状态快照（device_state_snapshot）
         * - 执行过程进度更新
         *
         * V2 的 android_device_state_store MUST 存储此类信号用于观测，
         * 但 MUST NOT 将其用于任务关闭或验收判定。
         */
        DIAGNOSTICS_INFORMATIONAL(
            wireValue = "diagnostics_informational",
            isAuthoritative = false,
            isAcceptanceRelevant = false
        );

        companion object {
            /**
             * 从 wire 值解析 [ResultSignalClass]；未知值返回 [DIAGNOSTICS_INFORMATIONAL]（防御性默认）。
             */
            fun fromWireValue(wire: String): ResultSignalClass =
                values().firstOrNull { it.wireValue == wire } ?: DIAGNOSTICS_INFORMATIONAL
        }
    }

    // ── 验收候选分类 ─────────────────────────────────────────────────────────────

    /**
     * Android 上行信号的验收候选资格分类。
     *
     * 消除 V2 通过 result_returned + completion_signaled + closure_ready_for_acceptance
     * + governance_blocked 四个布尔字段组合推断验收资格的复杂性。
     *
     * | [AcceptanceCandidateClass]  | wireValue                 | V2 acceptance 处置       |
     * |-----------------------------|---------------------------|--------------------------|
     * | [ELIGIBLE_FOR_ACCEPTANCE]   | `eligible_for_acceptance` | 进入 acceptance closure  |
     * | [PENDING_RESULT_RETURN]     | `pending_result_return`   | 等待终态结果             |
     * | [ACCEPTANCE_BLOCKED]        | `acceptance_blocked`      | 等待约束解除             |
     * | [CLOSURE_NOT_APPLICABLE]    | `closure_not_applicable`  | 仅存储，不纳入验收链     |
     *
     * @property wireValue  稳定小写 wire 字符串。
     * @property isEligible V2 acceptance adjudication 是否可对此信号执行验收关闭。
     */
    enum class AcceptanceCandidateClass(
        val wireValue: String,
        val isEligible: Boolean
    ) {
        /**
         * 可接受候选结果。
         *
         * Android 上行满足全部验收前置条件：
         * - isTerminalPhase = true
         * - closureReadyForAcceptance = true
         * - resultReturned = true
         * - isGovernanceBlocked = false
         * - isRuntimeConstrained = false（或 resultReturned + completionSignaled 均满足）
         *
         * V2 acceptance_adjudication MUST 对此分类执行 acceptance closure。
         */
        ELIGIBLE_FOR_ACCEPTANCE(
            wireValue = "eligible_for_acceptance",
            isEligible = true
        ),

        /**
         * 等待结果返回（执行中）。
         *
         * Android 仍处于活跃执行阶段，尚未产出终态结果。
         * V2 MUST 维持 task open 状态，等待后续终态结果。
         */
        PENDING_RESULT_RETURN(
            wireValue = "pending_result_return",
            isEligible = false
        ),

        /**
         * 验收被阻塞。
         *
         * Android 已产出结果（closureReadyForAcceptance=true），但存在以下阻塞条件：
         * - governance_blocked = true（治理层阻塞验收）
         * - runtime_constrained = true（运行时约束导致结果置信度降低）
         *
         * V2 MUST 保持 task 处于 pending_acceptance 状态，
         * 等待 Android 后续状态快照中约束解除后重新评估。
         */
        ACCEPTANCE_BLOCKED(
            wireValue = "acceptance_blocked",
            isEligible = false
        ),

        /**
         * 无闭合语义（诊断性上行）。
         *
         * 此上行为纯诊断性信号：
         * - device_state_snapshot（参与状态快照）
         * - 非终态执行事件（过程中间状态）
         * - closureReadyForAcceptance = false 的结果摘要
         *
         * V2 MUST NOT 对此分类执行任何验收或任务关闭操作。
         */
        CLOSURE_NOT_APPLICABLE(
            wireValue = "closure_not_applicable",
            isEligible = false
        );

        companion object {
            /**
             * 从 wire 值解析 [AcceptanceCandidateClass]；未知值返回 [CLOSURE_NOT_APPLICABLE]（防御性默认）。
             */
            fun fromWireValue(wire: String): AcceptanceCandidateClass =
                values().firstOrNull { it.wireValue == wire } ?: CLOSURE_NOT_APPLICABLE
        }
    }

    // ── 推导输入 ───────────────────────────────────────────────────────────────

    /**
     * [derive] 的输入，汇聚来自现有合约的运行时信号。
     *
     * 所有输入均来自现有 Android 运行时状态与已有合约字段，无需新增探针。
     *
     * @param isTerminalPhase           当前是否处于终态执行阶段
     *                                  （来自 [ExecutionUplinkDiscipline] /
     *                                  [AndroidExecutionLifecycleContract.ExecutionLifecyclePhase.isTerminal]，
     *                                  或 `lifecycle_terminal_phase == true`）。
     * @param resultReturned            Android 是否已产出终态结果
     *                                  （来自 [AndroidMissionCompletionSemanticsContract.CompletionVisibility.resultReturned]）。
     * @param completionSignaled        Android 是否已发出完成侧信号
     *                                  （来自 [AndroidMissionCompletionSemanticsContract.CompletionVisibility.completionSignaled]）。
     * @param closureReadyForAcceptance 此结果是否可进入验收闭合
     *                                  （来自 [AndroidMissionCompletionSemanticsContract.CompletionVisibility.closureReadyForAcceptance]）。
     * @param isGovernanceBlocked       Android 当前是否被治理层阻塞
     *                                  （来自 `governance_blocked == true`）。
     * @param isRuntimeConstrained      Android 当前是否处于运行时受限状态
     *                                  （来自 [AndroidUnifiedTruthUplinkContract.ConstraintSemantics.isConstraint]）。
     * @param isHoldState               Android 当前是否处于 hold/deferred 状态
     *                                  （来自 `local_mode_gate_deferred == true` 或
     *                                  [AndroidUnifiedTruthUplinkContract.ConstraintSemantics.isDeferred]）。
     */
    data class UplinkBoundaryDerivationInput(
        val isTerminalPhase: Boolean,
        val resultReturned: Boolean,
        val completionSignaled: Boolean,
        val closureReadyForAcceptance: Boolean,
        val isGovernanceBlocked: Boolean,
        val isRuntimeConstrained: Boolean,
        val isHoldState: Boolean
    )

    // ── 派生快照 ───────────────────────────────────────────────────────────────

    /**
     * [derive] 的输出，包含推导得出的结果信号分类与验收候选分类。
     *
     * @param resultSignalClass        推导得出的 [ResultSignalClass]。
     * @param acceptanceCandidateClass 推导得出的 [AcceptanceCandidateClass]。
     */
    data class UplinkBoundarySnapshot(
        val resultSignalClass: ResultSignalClass,
        val acceptanceCandidateClass: AcceptanceCandidateClass
    ) {
        /**
         * 生成可嵌入上行消息的 wire map。
         *
         * 包含 [KEY_RESULT_SIGNAL_CLASS]、[KEY_ACCEPTANCE_CANDIDATE_CLASS]
         * 和 [KEY_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION] 三个键值对。
         */
        fun toWireMap(): Map<String, Any> = mapOf(
            KEY_RESULT_SIGNAL_CLASS to resultSignalClass.wireValue,
            KEY_ACCEPTANCE_CANDIDATE_CLASS to acceptanceCandidateClass.wireValue,
            KEY_RESULT_UPLINK_BOUNDARY_SCHEMA_VERSION to SCHEMA_VERSION
        )
    }

    // ── 推导函数 ───────────────────────────────────────────────────────────────

    /**
     * 从 [UplinkBoundaryDerivationInput] 推导 [UplinkBoundarySnapshot]。
     *
     * 优先级规则（从高到低）：
     *
     * 1. **诊断性上行**：非终态且无 closureReadyForAcceptance
     *    → [ResultSignalClass.DIAGNOSTICS_INFORMATIONAL] + [AcceptanceCandidateClass.CLOSURE_NOT_APPLICABLE]
     * 2. **验收阻塞**：closureReadyForAcceptance=true 但 governance_blocked 或 runtime_constrained
     *    → [ResultSignalClass.ACCEPTANCE_CLOSURE_SIGNAL] + [AcceptanceCandidateClass.ACCEPTANCE_BLOCKED]
     * 3. **权威结果**：终态 + resultReturned + completionSignaled + closureReadyForAcceptance + 非 hold
     *    → [ResultSignalClass.AUTHORITY_RESULT] + [AcceptanceCandidateClass.ELIGIBLE_FOR_ACCEPTANCE]
     * 4. **验收候选（非完整权威）**：closureReadyForAcceptance=true 但未满足全部 authority 条件
     *    → [ResultSignalClass.ACCEPTANCE_CLOSURE_SIGNAL] + [AcceptanceCandidateClass.ELIGIBLE_FOR_ACCEPTANCE]
     *
     * 不满足以上任何条件（非终态执行中）：
     * → [ResultSignalClass.DIAGNOSTICS_INFORMATIONAL] + [AcceptanceCandidateClass.PENDING_RESULT_RETURN]
     */
    fun derive(input: UplinkBoundaryDerivationInput): UplinkBoundarySnapshot {

        // ── 优先级 1：诊断性上行（非终态 + 无闭合语义）─────────────────────
        // 既未进入终态阶段，也未声明 closureReadyForAcceptance：纯观测/进度更新。
        if (!input.isTerminalPhase && !input.closureReadyForAcceptance) {
            return UplinkBoundarySnapshot(
                resultSignalClass = ResultSignalClass.DIAGNOSTICS_INFORMATIONAL,
                acceptanceCandidateClass = if (input.resultReturned || input.completionSignaled) {
                    // 非常规路径：有结果但不在终态（防御性处理）
                    AcceptanceCandidateClass.CLOSURE_NOT_APPLICABLE
                } else {
                    AcceptanceCandidateClass.PENDING_RESULT_RETURN
                }
            )
        }

        // ── 优先级 2：验收阻塞（有闭合意图但受治理或约束阻塞）─────────────
        if (input.closureReadyForAcceptance && (input.isGovernanceBlocked || input.isRuntimeConstrained)) {
            return UplinkBoundarySnapshot(
                resultSignalClass = ResultSignalClass.ACCEPTANCE_CLOSURE_SIGNAL,
                acceptanceCandidateClass = AcceptanceCandidateClass.ACCEPTANCE_BLOCKED
            )
        }

        // ── 优先级 3：权威终态结果（满足所有 authority 前置条件）─────────────
        if (input.isTerminalPhase &&
            input.resultReturned &&
            input.completionSignaled &&
            input.closureReadyForAcceptance &&
            !input.isHoldState
        ) {
            return UplinkBoundarySnapshot(
                resultSignalClass = ResultSignalClass.AUTHORITY_RESULT,
                acceptanceCandidateClass = AcceptanceCandidateClass.ELIGIBLE_FOR_ACCEPTANCE
            )
        }

        // ── 优先级 4：验收候选（closureReady 但未满足完整 authority 门槛）──
        if (input.closureReadyForAcceptance) {
            return UplinkBoundarySnapshot(
                resultSignalClass = ResultSignalClass.ACCEPTANCE_CLOSURE_SIGNAL,
                acceptanceCandidateClass = AcceptanceCandidateClass.ELIGIBLE_FOR_ACCEPTANCE
            )
        }

        // ── 默认：非终态执行中（等待结果返回）──────────────────────────────
        return UplinkBoundarySnapshot(
            resultSignalClass = ResultSignalClass.DIAGNOSTICS_INFORMATIONAL,
            acceptanceCandidateClass = AcceptanceCandidateClass.PENDING_RESULT_RETURN
        )
    }

    // ── 形式化不变量 ──────────────────────────────────────────────────────────

    /**
     * Android 侧结果上行闭环边界合约的形式化不变量。
     *
     * V2 可依赖这些不变量消费 Android 上行的结果边界字段。
     *
     * @param id      不变量标识符（INV-RUB-XX 格式）。
     * @param summary 不变量描述。
     */
    data class ResultUplinkBoundaryInvariant(val id: String, val summary: String)

    val RESULT_UPLINK_BOUNDARY_INVARIANTS: List<ResultUplinkBoundaryInvariant> = listOf(
        ResultUplinkBoundaryInvariant(
            id = "INV-RUB-01",
            summary = "result_signal_class MUST 在 goal_execution_result 和 device_execution_event " +
                "中永不缺失（Null 仅作为防御性默认值，GalaxyConnectionService 在发送层填充）。"
        ),
        ResultUplinkBoundaryInvariant(
            id = "INV-RUB-02",
            summary = "result_signal_class = 'authority_result' 当且仅当 isTerminalPhase=true AND " +
                "result_returned=true AND completion_signaled=true AND closure_ready_for_acceptance=true " +
                "AND isHoldState=false；V2 MUST 对此分类执行任务关闭，不得降为 advisory 处理。"
        ),
        ResultUplinkBoundaryInvariant(
            id = "INV-RUB-03",
            summary = "result_signal_class = 'diagnostics_informational' 的上行消息 MUST NOT 被 V2 " +
                "用于任务关闭或验收判定；V2 MUST 仅将其存储至 android_device_state_store。"
        ),
        ResultUplinkBoundaryInvariant(
            id = "INV-RUB-04",
            summary = "acceptance_candidate_class = 'eligible_for_acceptance' 当且仅当 " +
                "closureReadyForAcceptance=true AND isGovernanceBlocked=false AND isRuntimeConstrained=false；" +
                "V2 acceptance_adjudication MUST 对此分类执行 acceptance closure。"
        ),
        ResultUplinkBoundaryInvariant(
            id = "INV-RUB-05",
            summary = "acceptance_candidate_class = 'acceptance_blocked' 时，V2 MUST 保持 task open " +
                "并等待后续 device_state_snapshot 或 goal_execution_result 中约束解除的信号，" +
                "不得在阻塞状态下执行验收关闭。"
        ),
        ResultUplinkBoundaryInvariant(
            id = "INV-RUB-06",
            summary = "result_uplink_boundary_schema_version MUST 始终为 SCHEMA_VERSION 常量值，" +
                "确保 V2 可检测版本漂移；不允许新增平行结果上行分类体系。"
        )
    )

    // ── V2 contract 对应词汇表 ───────────────────────────────────────────────

    /**
     * Android [ResultSignalClass] 与 V2 truth/acceptance/diagnostics 消费路径的权威映射。
     *
     * V2 消费方 MUST 使用此映射将 Android result_signal_class 路由至正确的消费路径，
     * 而非对上行字段做独立的语义推断。
     */
    val V2_CONSUMPTION_PATH_MAP: Map<ResultSignalClass, String> = mapOf(
        ResultSignalClass.AUTHORITY_RESULT to "task_result_canonical_truth_chain",
        ResultSignalClass.ACCEPTANCE_CLOSURE_SIGNAL to "acceptance_adjudication",
        ResultSignalClass.DIAGNOSTICS_INFORMATIONAL to "android_device_state_store"
    )
}
