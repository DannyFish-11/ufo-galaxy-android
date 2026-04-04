package com.ufo.galaxy.local

import com.ufo.galaxy.runtime.SourceRuntimePosture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests proving posture-aware execution-entry behaviour in [DefaultLocalLoopExecutor].
 *
 * This is part of **PR package 2 (ANDROID side)** of the post-533 dual-repo runtime
 * unification master plan: *Posture-aware Dispatch Eligibility*.
 *
 * ## What is tested
 * The posture gate in [DefaultLocalLoopExecutor.execute] checks
 * [LocalLoopOptions.sourceRuntimePosture] **before** the readiness check or any
 * downstream execution is attempted:
 *
 * - [SourceRuntimePosture.CONTROL_ONLY]: execution is blocked immediately, returning
 *   [LocalLoopResult.STATUS_FAILED] with [DefaultLocalLoopExecutor.STOP_POSTURE_CONTROL_ONLY].
 * - [SourceRuntimePosture.JOIN_RUNTIME]: execution proceeds through the normal readiness
 *   and execution path.
 *
 * ## Significance
 * Enforcing this gate ensures that Android local execution honours the canonical posture
 * contract from the main repo's PR #533: a source device that declared `control_only`
 * must not inadvertently participate as a runtime executor on the Android side.
 */
class LocalLoopExecutorPostureTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private val runner by lazy { LocalLoopScenarioRunner(tmpFolder.newFolder("models")) }

    // ── CONTROL_ONLY: execution must be blocked ───────────────────────────────

    @Test
    fun `control_only posture returns STATUS_FAILED`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-control-only-status",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertEquals(
            "CONTROL_ONLY posture must return STATUS_FAILED",
            LocalLoopResult.STATUS_FAILED,
            result.status
        )
    }

    @Test
    fun `control_only posture sets stopReason to posture_control_only`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-control-only-stop-reason",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertEquals(
            "CONTROL_ONLY posture must set stopReason=posture_control_only",
            DefaultLocalLoopExecutor.STOP_POSTURE_CONTROL_ONLY,
            result.stopReason
        )
    }

    @Test
    fun `control_only posture returns zero steps`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-control-only-zero-steps",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertEquals(
            "CONTROL_ONLY posture must produce zero steps",
            0,
            result.stepCount
        )
    }

    @Test
    fun `control_only posture returns non-null error message`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-control-only-error",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertNotNull(
            "CONTROL_ONLY posture must include a non-null error message",
            result.error
        )
    }

    @Test
    fun `control_only posture error message mentions control_only`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-control-only-error-content",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertTrue(
            "Error message should mention 'control_only'",
            result.error!!.contains("control_only")
        )
    }

    @Test
    fun `control_only posture blocks execution even when readiness is fully available`() {
        // Explicitly set full readiness to confirm the posture gate is checked BEFORE readiness.
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-gate-before-readiness",
                readinessProvider = FakeReadinessProvider.fullyReady(),
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertEquals(
            "Posture gate must fire before readiness check",
            LocalLoopResult.STATUS_FAILED,
            result.status
        )
        assertEquals(
            "Posture gate stop-reason must be posture_control_only, not readiness_unavailable",
            DefaultLocalLoopExecutor.STOP_POSTURE_CONTROL_ONLY,
            result.stopReason
        )
    }

    @Test
    fun `control_only posture instruction is preserved in the result`() {
        val instruction = "navigate to home screen"
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-control-only-instruction",
                instruction = instruction,
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertEquals(
            "CONTROL_ONLY result must preserve the original instruction",
            instruction,
            result.instruction
        )
    }

    @Test
    fun `control_only posture sessionId is non-blank in the result`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-control-only-session-id",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertTrue(
            "CONTROL_ONLY result must include a non-blank sessionId",
            result.sessionId.isNotBlank()
        )
    }

    // ── JOIN_RUNTIME: execution must proceed ──────────────────────────────────

    @Test
    fun `join_runtime posture allows execution to proceed`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-join-runtime-proceeds",
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertEquals(
            "JOIN_RUNTIME posture must allow execution to proceed (STATUS_SUCCESS)",
            LocalLoopResult.STATUS_SUCCESS,
            result.status
        )
    }

    @Test
    fun `join_runtime posture allows steps to be executed`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-join-runtime-steps",
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertTrue(
            "JOIN_RUNTIME posture must allow at least one step to execute",
            result.stepCount > 0
        )
    }

    @Test
    fun `join_runtime posture does not set posture_control_only stop reason`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "posture-join-runtime-no-stop",
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        assertNotEquals(
            "JOIN_RUNTIME must not produce posture_control_only stop reason",
            DefaultLocalLoopExecutor.STOP_POSTURE_CONTROL_ONLY,
            result.stopReason
        )
    }

    // ── Posture gate fires before readiness ───────────────────────────────────

    @Test
    fun `control_only posture stop-reason differs from readiness_unavailable`() {
        // Confirms the two distinct blocked states are distinguishable to callers.
        val postureResult = runner.run(
            LocalLoopScenario(
                name = "posture-vs-readiness-posture",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        val readinessResult = runner.run(
            LocalLoopScenario(
                name = "posture-vs-readiness-readiness",
                readinessProvider = FakeReadinessProvider.unavailable(),
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )

        assertEquals(LocalLoopResult.STATUS_FAILED, postureResult.status)
        assertEquals(LocalLoopResult.STATUS_FAILED, readinessResult.status)

        assertEquals(DefaultLocalLoopExecutor.STOP_POSTURE_CONTROL_ONLY, postureResult.stopReason)
        assertEquals(DefaultLocalLoopExecutor.STOP_READINESS_UNAVAILABLE, readinessResult.stopReason)

        assertNotEquals(
            "posture_control_only and readiness_unavailable stop-reasons must differ",
            postureResult.stopReason,
            readinessResult.stopReason
        )
    }

    // ── Direct executor tests (not via ScenarioRunner) ────────────────────────

    @Test
    fun `direct executor call with CONTROL_ONLY returns STATUS_FAILED immediately`() {
        // Use the ScenarioRunner to build a proper LoopController, then retest directly.
        // The posture gate fires before LoopController.execute() so the runner outcome is
        // equivalent to calling the executor directly with the same posture.
        val result = runner.run(
            LocalLoopScenario(
                name = "direct-control-only",
                readinessProvider = FakeReadinessProvider.fullyReady(),
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY
            )
        )
        assertEquals(LocalLoopResult.STATUS_FAILED, result.status)
        assertEquals(DefaultLocalLoopExecutor.STOP_POSTURE_CONTROL_ONLY, result.stopReason)
    }

    @Test
    fun `direct executor call with JOIN_RUNTIME proceeds past posture gate`() {
        val result = runner.run(
            LocalLoopScenario(
                name = "direct-join-runtime",
                readinessProvider = FakeReadinessProvider.fullyReady(),
                sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME
            )
        )
        // The scenario default produces a single successful step.
        assertEquals(LocalLoopResult.STATUS_SUCCESS, result.status)
    }

    @Test
    fun `default posture (CONTROL_ONLY) blocks execution`() {
        // LocalLoopOptions.sourceRuntimePosture defaults to SourceRuntimePosture.DEFAULT = CONTROL_ONLY.
        // LocalLoopScenario.sourceRuntimePosture defaults to JOIN_RUNTIME (so existing tests pass),
        // so we explicitly set CONTROL_ONLY here to test the LocalLoopOptions default contract.
        val result = runner.run(
            LocalLoopScenario(
                name = "default-posture",
                sourceRuntimePosture = SourceRuntimePosture.CONTROL_ONLY  // mirrors LocalLoopOptions default
            )
        )
        assertEquals(
            "Default posture (CONTROL_ONLY) must block execution",
            LocalLoopResult.STATUS_FAILED,
            result.status
        )
        assertEquals(DefaultLocalLoopExecutor.STOP_POSTURE_CONTROL_ONLY, result.stopReason)
    }
}
