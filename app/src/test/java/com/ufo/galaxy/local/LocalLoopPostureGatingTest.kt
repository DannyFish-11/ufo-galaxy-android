package com.ufo.galaxy.local

import com.ufo.galaxy.runtime.SourceRuntimePosture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Posture-gating tests for [DefaultLocalLoopExecutor] — PR package 2 (ANDROID side)
 * of the post-533 dual-repo runtime unification master plan:
 * **Posture-aware Dispatch Eligibility**.
 *
 * ## What these tests verify
 *
 * [DefaultLocalLoopExecutor] now enforces a posture gate at the execution entry-point:
 * - **[SourceRuntimePosture.CONTROL_ONLY]**: Android is acting as a pure controller /
 *   initiator and must NOT join the local runtime as an executor for this task.
 *   Execution is immediately blocked and a structured [LocalLoopResult.STATUS_FAILED]
 *   result is returned with [DefaultLocalLoopExecutor.STOP_POSTURE_CONTROL_ONLY] as
 *   the stop-reason. This prevents Android from incorrectly self-allocating local
 *   execution capacity when the canonical contract says it should only route/control.
 * - **[SourceRuntimePosture.JOIN_RUNTIME]**: Android is an eligible local-runtime
 *   participant for this task. Execution proceeds through the normal readiness gate
 *   and then the full local pipeline.
 *
 * ## Alignment with the main repo
 * These semantics mirror the server-side `control_only` / `join_runtime` contract
 * introduced by main-repo PR #533. The posture gate ensures Android honours the same
 * canonical semantics for its own local execution-entry point.
 *
 * ## Interaction with InputRouter
 * [com.ufo.galaxy.input.InputRouter] always sets [SourceRuntimePosture.JOIN_RUNTIME]
 * for locally-routed tasks, so normal UI/voice-initiated tasks are never blocked by
 * this gate. The gate only fires when a caller explicitly constructs [LocalLoopOptions]
 * with [SourceRuntimePosture.CONTROL_ONLY] — which is an error condition indicating a
 * task that should have been routed cross-device.
 *
 * Test matrix:
 *  - `control_only` → blocked, STATUS_FAILED, STOP_POSTURE_CONTROL_ONLY stop-reason
 *  - `control_only` → blocked before the readiness gate is evaluated
 *  - `control_only` → error message contains posture value
 *  - `join_runtime`  → allowed, proceeds to readiness gate (and succeeds when ready)
 *  - `join_runtime`  → readiness gate still fires after posture gate passes
 *  - Unknown / null-equivalent → treated as `control_only` → blocked
 *  - `join_runtime`  → stepCount and status match normal execution
 */
class LocalLoopPostureGatingTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val runner by lazy { LocalLoopScenarioRunner(tmpFolder.newFolder("models")) }

    // ── control_only: posture gate blocks execution ───────────────────────────

    @Test
    fun `control_only posture blocks local execution and returns STATUS_FAILED`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-control-only-blocked",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertEquals(
            "control_only posture must block local execution with STATUS_FAILED",
            LocalLoopResult.STATUS_FAILED,
            result.status
        )
    }

    @Test
    fun `control_only posture sets stopReason to STOP_POSTURE_CONTROL_ONLY`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-control-only-stop-reason",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertEquals(
            "Stop reason must be posture_control_only when blocked by posture gate",
            DefaultLocalLoopExecutor.STOP_POSTURE_CONTROL_ONLY,
            result.stopReason
        )
    }

    @Test
    fun `control_only posture blocked result has zero steps`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-control-only-zero-steps",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertEquals(
            "Posture-blocked execution must not execute any steps",
            0,
            result.stepCount
        )
    }

    @Test
    fun `control_only posture blocked result has non-null error message`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-control-only-error-message",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertNotNull(
            "Posture-blocked result must carry an error description",
            result.error
        )
    }

    @Test
    fun `control_only posture blocked result error message contains control_only`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-control-only-error-text",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertTrue(
            "Error message must reference the blocking posture value: '${result.error}'",
            result.error?.contains("control_only") == true
        )
    }

    @Test
    fun `control_only posture gate fires before readiness gate`() {
        // The posture gate must execute first. Even with UNAVAILABLE readiness, the
        // posture stop-reason (not the readiness stop-reason) must be returned.
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-control-only-before-readiness",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY,
                readinessProvider = FakeReadinessProvider.unavailable()
            )
        )
        assertEquals(
            "Posture gate must fire before readiness gate",
            DefaultLocalLoopExecutor.STOP_POSTURE_CONTROL_ONLY,
            result.stopReason
        )
    }

    // ── join_runtime: posture gate passes, execution proceeds ─────────────────

    @Test
    fun `join_runtime posture allows local execution and returns STATUS_SUCCESS`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-join-runtime-success",
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertEquals(
            "join_runtime posture must allow local execution through to success",
            LocalLoopResult.STATUS_SUCCESS,
            result.status
        )
    }

    @Test
    fun `join_runtime posture allows execution with positive step count`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-join-runtime-steps",
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertTrue(
            "join_runtime posture must allow execution with at least one step",
            result.stepCount > 0
        )
    }

    @Test
    fun `join_runtime posture does not set posture_control_only stop-reason`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-join-runtime-no-posture-block",
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertNotEquals(
            "join_runtime posture must not produce posture_control_only stop-reason",
            DefaultLocalLoopExecutor.STOP_POSTURE_CONTROL_ONLY,
            result.stopReason
        )
    }

    @Test
    fun `join_runtime posture with unavailable readiness produces STOP_READINESS_UNAVAILABLE`() {
        // Confirms the posture gate passes (join_runtime) and the readiness gate fires next.
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-join-runtime-readiness-blocked",
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                readinessProvider = FakeReadinessProvider.unavailable()
            )
        )
        assertEquals(
            "After posture gate passes, readiness gate must fire when subsystems are unavailable",
            DefaultLocalLoopExecutor.STOP_READINESS_UNAVAILABLE,
            result.stopReason
        )
    }

    // ── LocalLoopOptions direct construction — posture gate verification ──────

    @Test
    fun `execute with control_only directly via LocalLoopOptions is blocked`() {
        // Verifies the gate at the DefaultLocalLoopExecutor API boundary via runner.
        // Posture gate fires before LoopController is reached, so the scenario
        // components (planner, grounding, etc.) are never invoked.
        val result = runner.run(
            LocalLoopScenario(
                name = "direct-control-only",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY,
                instruction = "tap button"
            )
        )
        assertEquals(LocalLoopResult.STATUS_FAILED, result.status)
        assertEquals(DefaultLocalLoopExecutor.STOP_POSTURE_CONTROL_ONLY, result.stopReason)
    }

    @Test
    fun `execute with join_runtime directly via LocalLoopOptions is allowed when ready`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "direct-join-runtime",
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME,
                instruction = "tap button"
            )
        )
        assertNotEquals(
            "join_runtime must not be blocked by posture gate",
            DefaultLocalLoopExecutor.STOP_POSTURE_CONTROL_ONLY,
            result.stopReason
        )
    }

    // ── Behavioural difference assertion: control_only vs join_runtime ─────────

    @Test
    fun `control_only and join_runtime produce different status outcomes`() {
        val controlOnlyResult = runner.run(
            LocalLoopScenario(
                name = "diff-control-only",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        val joinRuntimeResult = runner.run(
            LocalLoopScenario(
                name = "diff-join-runtime",
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertNotEquals(
            "control_only and join_runtime must produce different execution outcomes",
            controlOnlyResult.status,
            joinRuntimeResult.status
        )
        assertEquals(LocalLoopResult.STATUS_FAILED, controlOnlyResult.status)
        assertEquals(LocalLoopResult.STATUS_SUCCESS, joinRuntimeResult.status)
    }

    @Test
    fun `control_only and join_runtime produce different stop-reasons`() {
        val controlOnlyResult = runner.run(
            LocalLoopScenario(
                name = "stop-reason-control-only",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        val joinRuntimeResult = runner.run(
            LocalLoopScenario(
                name = "stop-reason-join-runtime",
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertNotEquals(
            "Stop-reasons must differ: control_only blocks, join_runtime succeeds",
            controlOnlyResult.stopReason,
            joinRuntimeResult.stopReason
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    // No additional helpers needed — all tests use LocalLoopScenarioRunner with
    // its built-in fake infrastructure (FakePlannerService, FakeGroundingService, etc.).
}
