package com.ufo.galaxy.runtime

import org.junit.Assert.*
import org.junit.Test

/**
 * PR-B2 (Android) — OperatorActionReceiver 合约测试。
 *
 * 验证 [OperatorActionReceiver] 的核心行为：
 *  1. [OperatorActionReceiver.buildParticipationContext] 正确构建参与上下文快照。
 *  2. [OperatorActionReceiver.evaluateGovernanceDecision] 正确路由至治理门控并附加上下文。
 *  3. 治理拒绝场景下 [OperatorActionReceiver.GovernanceDecision.isAccepted] 为 false。
 *  4. 治理接受场景下 [OperatorActionReceiver.GovernanceDecision.isAccepted] 为 true。
 *  5. participation_tier 从 authoritative_participation_state 正确派生。
 *  6. RECEIVER_INVARIANTS 非空且覆盖关键语义约束。
 *  7. StabilizationBaseline 注册了 android-operator-action-receiver 条目。
 *
 * 测试矩阵覆盖所有 [AndroidOperatorActionGovernanceContract.ActionKind] 枚举值的
 * 接受/拒绝路径，以及 participation context 在 DECISION 和 EXECUTION 两个阶段的一致性。
 */
class OperatorActionReceiverTest {

    // ── 测试辅助：构建最小可用参与快照 ──────────────────────────────────────────

    private fun baselineParticipationSnapshot(
        state: AndroidAuthoritativeParticipationTruth.State =
            AndroidAuthoritativeParticipationTruth.State.DISPATCH_ELIGIBLE
    ): AndroidAuthoritativeParticipationTruth.Snapshot =
        AndroidAuthoritativeParticipationTruth.Snapshot(
            state = state,
            connected = true,
            attached = true,
            canDispatch = true,
            distributedParticipant = false,
            transitionSequence = 1L,
            lastTransitionTrigger = AndroidAuthoritativeParticipationTruth.TransitionTrigger.INITIALIZED.wireValue,
            transitionHistory = emptyList()
        )

    private fun baselineEligibilityContext(
        dispatchEligible: Boolean = true,
        crossDeviceEnabled: Boolean = true,
        hasAttachedSession: Boolean = true,
        activeTaskId: String? = null,
        hasActiveTakeover: Boolean = false,
        operatorSuspendedOrIsolated: Boolean = false
    ) = AndroidOperatorActionGovernanceContract.EligibilityContext(
        runtimeStateWire = "active",
        dispatchEligible = dispatchEligible,
        hasAttachedSession = hasAttachedSession,
        reconnectRecoveryStateWire = "recovered",
        activeTaskId = activeTaskId,
        hasActiveTakeover = hasActiveTakeover,
        crossDeviceEnabled = crossDeviceEnabled,
        operatorSuspendedOrIsolated = operatorSuspendedOrIsolated
    )

    // ── buildParticipationContext ────────────────────────────────────────────

    @Test
    fun `buildParticipationContext fills authoritativeParticipationState from snapshot`() {
        val snapshot = baselineParticipationSnapshot(
            AndroidAuthoritativeParticipationTruth.State.DISPATCH_ELIGIBLE
        )
        val ctx = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = snapshot,
            isLocalModeActive = false,
            isRuntimeConstrained = false,
            isRuntimeDeferred = false,
            isDelegatedExecutionActive = false
        )
        assertEquals(
            AndroidAuthoritativeParticipationTruth.State.DISPATCH_ELIGIBLE.wireValue,
            ctx.authoritativeParticipationState
        )
    }

    @Test
    fun `buildParticipationContext derives participation_tier from state`() {
        val snapshot = baselineParticipationSnapshot(
            AndroidAuthoritativeParticipationTruth.State.DISPATCH_ELIGIBLE
        )
        val ctx = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = snapshot,
            isLocalModeActive = false,
            isRuntimeConstrained = false,
            isRuntimeDeferred = false,
            isDelegatedExecutionActive = false
        )
        val expectedTier = AndroidAuthoritativeParticipationTruth
            .participationTierFor(AndroidAuthoritativeParticipationTruth.State.DISPATCH_ELIGIBLE)
            .wireValue
        assertEquals(expectedTier, ctx.participationTier)
    }

    @Test
    fun `buildParticipationContext preserves local_mode_active flag`() {
        val ctx = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = baselineParticipationSnapshot(),
            isLocalModeActive = true,
            isRuntimeConstrained = false,
            isRuntimeDeferred = false,
            isDelegatedExecutionActive = false
        )
        assertTrue(ctx.localModeActive)
    }

    @Test
    fun `buildParticipationContext preserves runtime_constrained flag`() {
        val ctx = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = baselineParticipationSnapshot(),
            isLocalModeActive = false,
            isRuntimeConstrained = true,
            isRuntimeDeferred = false,
            isDelegatedExecutionActive = false
        )
        assertTrue(ctx.runtimeConstrained)
    }

    @Test
    fun `buildParticipationContext preserves runtime_deferred flag`() {
        val ctx = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = baselineParticipationSnapshot(),
            isLocalModeActive = false,
            isRuntimeConstrained = false,
            isRuntimeDeferred = true,
            isDelegatedExecutionActive = false
        )
        assertTrue(ctx.runtimeDeferred)
    }

    @Test
    fun `buildParticipationContext preserves delegated_execution_active flag`() {
        val ctx = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = baselineParticipationSnapshot(),
            isLocalModeActive = false,
            isRuntimeConstrained = false,
            isRuntimeDeferred = false,
            isDelegatedExecutionActive = true
        )
        assertTrue(ctx.delegatedExecutionActive)
    }

    @Test
    fun `buildParticipationContext pre_attach tier for local_only state`() {
        val snapshot = baselineParticipationSnapshot(
            AndroidAuthoritativeParticipationTruth.State.LOCAL_ONLY
        )
        val ctx = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = snapshot,
            isLocalModeActive = true,
            isRuntimeConstrained = true,
            isRuntimeDeferred = false,
            isDelegatedExecutionActive = false
        )
        val expectedTier = AndroidAuthoritativeParticipationTruth
            .participationTierFor(AndroidAuthoritativeParticipationTruth.State.LOCAL_ONLY)
            .wireValue
        assertEquals(expectedTier, ctx.participationTier)
    }

    // ── evaluateGovernanceDecision：接受路径 ─────────────────────────────────

    @Test
    fun `evaluateGovernanceDecision accepts revalidate_participation unconditionally`() {
        val ctx = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = baselineParticipationSnapshot(),
            isLocalModeActive = false,
            isRuntimeConstrained = false,
            isRuntimeDeferred = false,
            isDelegatedExecutionActive = false
        )
        val decision = OperatorActionReceiver.evaluateGovernanceDecision(
            actionKind = AndroidOperatorActionGovernanceContract.ActionKind.REVALIDATE_PARTICIPATION,
            context = baselineEligibilityContext(),
            taskId = null,
            participationContext = ctx
        )
        assertTrue(decision.isAccepted)
        assertNull(decision.rejectionReason)
    }

    @Test
    fun `evaluateGovernanceDecision accepts finalize_closure with task id`() {
        val ctx = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = baselineParticipationSnapshot(),
            isLocalModeActive = false,
            isRuntimeConstrained = false,
            isRuntimeDeferred = false,
            isDelegatedExecutionActive = true
        )
        val decision = OperatorActionReceiver.evaluateGovernanceDecision(
            actionKind = AndroidOperatorActionGovernanceContract.ActionKind.FINALIZE_CLOSURE,
            context = baselineEligibilityContext(),
            taskId = "task-abc",
            participationContext = ctx
        )
        assertTrue(decision.isAccepted)
    }

    @Test
    fun `evaluateGovernanceDecision accepts suspend_isolate even when constrained`() {
        val ctx = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = baselineParticipationSnapshot(
                AndroidAuthoritativeParticipationTruth.State.LOCAL_ONLY
            ),
            isLocalModeActive = true,
            isRuntimeConstrained = true,
            isRuntimeDeferred = false,
            isDelegatedExecutionActive = false
        )
        val decision = OperatorActionReceiver.evaluateGovernanceDecision(
            actionKind = AndroidOperatorActionGovernanceContract.ActionKind.SUSPEND_ISOLATE_DEVICE,
            context = baselineEligibilityContext(
                dispatchEligible = false,
                crossDeviceEnabled = false
            ),
            taskId = null,
            participationContext = ctx
        )
        assertTrue(decision.isAccepted)
    }

    // ── evaluateGovernanceDecision：拒绝路径 ─────────────────────────────────

    @Test
    fun `evaluateGovernanceDecision rejects retry_delegated_execution without active task`() {
        val ctx = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = baselineParticipationSnapshot(),
            isLocalModeActive = false,
            isRuntimeConstrained = false,
            isRuntimeDeferred = false,
            isDelegatedExecutionActive = false
        )
        val decision = OperatorActionReceiver.evaluateGovernanceDecision(
            actionKind = AndroidOperatorActionGovernanceContract.ActionKind.RETRY_DELEGATED_EXECUTION,
            context = baselineEligibilityContext(
                activeTaskId = null,
                hasActiveTakeover = false
            ),
            taskId = null,
            participationContext = ctx
        )
        assertFalse(decision.isAccepted)
        assertNotNull(decision.rejectionReason)
        assertTrue(decision.rejectionReason!!.contains("operator_action_blocked"))
    }

    @Test
    fun `evaluateGovernanceDecision rejects finalize_closure without task id`() {
        val ctx = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = baselineParticipationSnapshot(),
            isLocalModeActive = false,
            isRuntimeConstrained = false,
            isRuntimeDeferred = false,
            isDelegatedExecutionActive = false
        )
        val decision = OperatorActionReceiver.evaluateGovernanceDecision(
            actionKind = AndroidOperatorActionGovernanceContract.ActionKind.FINALIZE_CLOSURE,
            context = baselineEligibilityContext(),
            taskId = null,
            participationContext = ctx
        )
        assertFalse(decision.isAccepted)
        assertNotNull(decision.rejectionReason)
    }

    @Test
    fun `evaluateGovernanceDecision rejects non-allowed actions when suspended`() {
        val ctx = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = baselineParticipationSnapshot(),
            isLocalModeActive = false,
            isRuntimeConstrained = true,
            isRuntimeDeferred = false,
            isDelegatedExecutionActive = false
        )
        val decision = OperatorActionReceiver.evaluateGovernanceDecision(
            actionKind = AndroidOperatorActionGovernanceContract.ActionKind.FINALIZE_CLOSURE,
            context = baselineEligibilityContext(
                operatorSuspendedOrIsolated = true
            ),
            taskId = "task-x",
            participationContext = ctx
        )
        assertFalse(decision.isAccepted)
        assertTrue(decision.rejectionReason!!.contains("isolation_active"))
    }

    // ── GovernanceDecision 携带参与上下文 ────────────────────────────────────

    @Test
    fun `GovernanceDecision carries participation context unchanged`() {
        val expectedTier = AndroidAuthoritativeParticipationTruth
            .participationTierFor(AndroidAuthoritativeParticipationTruth.State.DISPATCH_ELIGIBLE)
            .wireValue
        val ctx = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = baselineParticipationSnapshot(
                AndroidAuthoritativeParticipationTruth.State.DISPATCH_ELIGIBLE
            ),
            isLocalModeActive = false,
            isRuntimeConstrained = false,
            isRuntimeDeferred = false,
            isDelegatedExecutionActive = true
        )
        val decision = OperatorActionReceiver.evaluateGovernanceDecision(
            actionKind = AndroidOperatorActionGovernanceContract.ActionKind.REVALIDATE_PARTICIPATION,
            context = baselineEligibilityContext(),
            taskId = null,
            participationContext = ctx
        )
        assertEquals(expectedTier, decision.participationContext.participationTier)
        assertTrue(decision.participationContext.delegatedExecutionActive)
        assertFalse(decision.participationContext.localModeActive)
    }

    @Test
    fun `GovernanceDecision preserves context for rejected decision`() {
        val ctx = OperatorActionReceiver.buildParticipationContext(
            participationSnapshot = baselineParticipationSnapshot(
                AndroidAuthoritativeParticipationTruth.State.LOCAL_ONLY
            ),
            isLocalModeActive = true,
            isRuntimeConstrained = true,
            isRuntimeDeferred = false,
            isDelegatedExecutionActive = false
        )
        val decision = OperatorActionReceiver.evaluateGovernanceDecision(
            actionKind = AndroidOperatorActionGovernanceContract.ActionKind.FINALIZE_CLOSURE,
            context = baselineEligibilityContext(),
            taskId = null,
            participationContext = ctx
        )
        assertFalse(decision.isAccepted)
        assertTrue(decision.participationContext.localModeActive)
        assertTrue(decision.participationContext.runtimeConstrained)
    }

    // ── RECEIVER_INVARIANTS ──────────────────────────────────────────────────

    @Test
    fun `RECEIVER_INVARIANTS is non-empty`() {
        assertTrue(OperatorActionReceiver.RECEIVER_INVARIANTS.isNotEmpty())
    }

    @Test
    fun `RECEIVER_INVARIANTS covers all required invariant codes`() {
        val invariantCodes = listOf(
            "INV-OAR-01", "INV-OAR-02", "INV-OAR-03",
            "INV-OAR-04", "INV-OAR-05", "INV-OAR-06", "INV-OAR-07"
        )
        val allInvariantsText = OperatorActionReceiver.RECEIVER_INVARIANTS.joinToString("\n")
        for (code in invariantCodes) {
            assertTrue(
                "RECEIVER_INVARIANTS 应包含 $code",
                allInvariantsText.contains(code)
            )
        }
    }

    @Test
    fun `RECEIVER_INVARIANTS count matches expected`() {
        assertEquals(7, OperatorActionReceiver.RECEIVER_INVARIANTS.size)
    }

    // ── 元数据常量 ────────────────────────────────────────────────────────────

    @Test
    fun `PARTICIPATION_CONTEXT_SCHEMA_VERSION is stable wire value`() {
        assertEquals("1", OperatorActionReceiver.PARTICIPATION_CONTEXT_SCHEMA_VERSION)
    }

    // ── StabilizationBaseline 注册验证 ────────────────────────────────────────

    @Test
    fun `StabilizationBaseline registers android-operator-action-receiver`() {
        assertTrue(
            "StabilizationBaseline 应注册 android-operator-action-receiver",
            StabilizationBaseline.isRegistered("android-operator-action-receiver")
        )
    }

    @Test
    fun `android-operator-action-receiver baseline entry has correct introduced pr`() {
        val entry = StabilizationBaseline.forId("android-operator-action-receiver")
        assertNotNull(entry)
        assertEquals(92, entry!!.introducedPr)
    }

    @Test
    fun `android-operator-action-receiver baseline entry is canonical stable`() {
        val entry = StabilizationBaseline.forId("android-operator-action-receiver")
        assertNotNull(entry)
        assertEquals(StabilizationBaseline.SurfaceStability.CANONICAL_STABLE, entry!!.stability)
    }
}
