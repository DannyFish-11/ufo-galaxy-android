package com.ufo.galaxy.loop

import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.model.ModelDownloader
import com.ufo.galaxy.observability.GalaxyLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.UUID

/**
 * Orchestrates the full local closed-loop automation pipeline:
 *
 *   natural-language instruction
 *     → model readiness check / download
 *     → screenshot capture
 *     → [LocalPlanner] inference (MobileVLM)
 *     → [ExecutorBridge] action dispatch (SeeClick grounding + AccessibilityService)
 *     → post-action screenshot
 *     → repeat until completion or [maxSteps] budget exhausted
 *
 * Progress is exposed via [status] ([StateFlow]) so the UI layer can observe
 * each state transition without polling. All blocking work runs on [Dispatchers.IO];
 * callers must invoke [execute] from a coroutine — never from the main thread.
 *
 * Each step is logged with: step index, action type, intent, execution result,
 * confidence score, and stop reason (on termination).
 *
 * @param localPlanner       Plans [ActionSequence]s from instruction + screenshot.
 * @param executorBridge     Dispatches each [ActionStep] to the device.
 * @param screenshotProvider Captures JPEG screenshots of the device screen.
 * @param modelAssetManager  Checks and tracks local model file status.
 * @param modelDownloader    Downloads missing model files on demand.
 * @param maxSteps           Hard cap on total dispatched steps (including replans).
 * @param maxRetriesPerStep  Max retries for a single failing step before giving up.
 */
class LoopController(
    private val localPlanner: LocalPlanner,
    private val executorBridge: ExecutorBridge,
    private val screenshotProvider: EdgeExecutor.ScreenshotProvider,
    private val modelAssetManager: ModelAssetManager,
    private val modelDownloader: ModelDownloader,
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    val maxRetriesPerStep: Int = DEFAULT_MAX_RETRIES
) {

    companion object {
        const val DEFAULT_MAX_STEPS = 10
        const val DEFAULT_MAX_RETRIES = 2

        internal const val TAG = "GALAXY:LOOP"

        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        const val STOP_TASK_COMPLETE = "task_complete"
        const val STOP_MAX_STEPS = "max_steps_reached"
        const val STOP_MODEL_UNAVAILABLE = "model_unavailable"
        const val STOP_SCREENSHOT_FAILED = "screenshot_failed"
        const val STOP_PLAN_FAILED = "plan_failed"
        const val STOP_REPLAN_FAILED = "replan_failed"
        const val STOP_STEP_EXHAUSTED = "step_retries_exhausted"
        const val STOP_CANCELLED_BY_REMOTE = "cancelled_by_remote_task"
        const val STOP_BLOCKED_BY_REMOTE = "blocked_by_remote_task"
    }

    /**
     * Set to `true` by [cancelForRemoteTask] when the Gateway assigns a remote task so
     * that any currently-running [execute] session terminates gracefully at the next
     * step boundary. Reset automatically at the start of [execute].
     */
    @Volatile private var cancelRequested: Boolean = false

    /**
     * When `true`, a remote (Gateway-assigned) task is executing and local [execute]
     * calls will return immediately with [STATUS_CANCELLED] / [STOP_BLOCKED_BY_REMOTE]
     * rather than starting a new local session.
     *
     * Set by [cancelForRemoteTask]; cleared by [clearRemoteTaskBlock].
     */
    @Volatile var isRemoteTaskActive: Boolean = false
        private set

    /**
     * Cancels any currently-running [execute] session and blocks new local sessions
     * until [clearRemoteTaskBlock] is called.
     *
     * Called by [com.ufo.galaxy.runtime.RuntimeController.onRemoteTaskStarted] when the
     * Gateway assigns a `task_assign` or `goal_execution` to this device.
     */
    fun cancelForRemoteTask() {
        cancelRequested = true
        isRemoteTaskActive = true
    }

    /**
     * Clears the remote-task block set by [cancelForRemoteTask].
     *
     * Called by [com.ufo.galaxy.runtime.RuntimeController.onRemoteTaskFinished] after the
     * device has sent back `task_result` / `goal_result` to the Gateway. After this call,
     * local [execute] sessions may start normally again.
     */
    fun clearRemoteTaskBlock() {
        cancelRequested = false
        isRemoteTaskActive = false
    }

    private val _status = MutableStateFlow<LoopStatus>(LoopStatus.Idle)

    /**
     * Current loop session status. Updated on every state transition.
     * Observe from the UI layer to drive progress indicators.
     */
    val status: StateFlow<LoopStatus> = _status.asStateFlow()

    /**
     * Executes the automation pipeline for [instruction].
     *
     * Must be called from a coroutine (not the main thread). Internally suspends on
     * [Dispatchers.IO]; the calling coroutine's dispatcher is not disturbed on return.
     *
     * @param instruction Natural-language task to automate.
     * @return [LoopResult] describing the final outcome.
     */
    suspend fun execute(instruction: String): LoopResult = withContext(Dispatchers.IO) {
        val sessionId = UUID.randomUUID().toString()

        // Block new local sessions while a remote (Gateway) task is active.
        if (isRemoteTaskActive) {
            GalaxyLogger.log(TAG, mapOf(
                "event" to "session_blocked",
                "session_id" to sessionId,
                "reason" to STOP_BLOCKED_BY_REMOTE
            ))
            _status.value = LoopStatus.Idle
            return@withContext LoopResult(
                sessionId = sessionId,
                instruction = instruction,
                status = STATUS_CANCELLED,
                stopReason = STOP_BLOCKED_BY_REMOTE,
                error = "Local execution blocked while remote task is active"
            )
        }

        // Reset cancel flag at the start of each new session.
        cancelRequested = false

        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "session_start",
                "session_id" to sessionId,
                "instruction" to instruction.take(120),
                "max_steps" to maxSteps,
                "max_retries_per_step" to maxRetriesPerStep
            )
        )

        _status.value = LoopStatus.Running(sessionId, 0, 0, "Initializing")

        // ── Phase 0: ensure local model files are present ─────────────────────
        // ensureModels triggers background downloads when URLs are configured; it always
        // returns true so the loop proceeds even if downloads fail — the inference layer
        // will degrade to rule-based planning when models are absent.
        ensureModels(sessionId)

        // ── Phase 1: initial screenshot ───────────────────────────────────────
        val initialCapture = captureScreenshot(sessionId)
            ?: return@withContext terminateFailed(
                sessionId, instruction,
                stopReason = STOP_SCREENSHOT_FAILED,
                error = "Initial screenshot capture failed",
                stepIndex = 0
            )
        val (initialJpeg, initW, initH) = initialCapture
        val initialBase64 = Base64.getEncoder().encodeToString(initialJpeg)

        // ── Phase 2: initial plan ─────────────────────────────────────────────
        val sequence = localPlanner.plan(sessionId, instruction, initialBase64)
        if (sequence.steps.isEmpty()) {
            return@withContext terminateFailed(
                sessionId, instruction,
                stopReason = STOP_PLAN_FAILED,
                error = "Planning produced no steps",
                stepIndex = 0
            )
        }

        // ── Phase 3: execution loop ───────────────────────────────────────────
        val executedSteps = mutableListOf<ActionStep>()
        var planSteps = sequence.steps
        var stepIndex = 0
        var stepsConsumed = 0

        // stepsConsumed tracks total dispatched steps across the initial plan and any replans.
        // maxSteps caps the total budget regardless of how many times replanning occurs.
        while (stepIndex < planSteps.size && stepsConsumed < maxSteps) {
            // Check for remote-task cancellation at each step boundary.
            if (cancelRequested) {
                GalaxyLogger.log(TAG, mapOf(
                    "event" to "session_cancelled",
                    "session_id" to sessionId,
                    "step_index" to stepsConsumed,
                    "reason" to STOP_CANCELLED_BY_REMOTE
                ))
                _status.value = LoopStatus.Idle
                return@withContext LoopResult(
                    sessionId = sessionId,
                    instruction = instruction,
                    status = STATUS_CANCELLED,
                    steps = executedSteps,
                    stopReason = STOP_CANCELLED_BY_REMOTE,
                    error = "Cancelled by remote task assignment"
                )
            }

            val step = planSteps[stepIndex]
            val displayIndex = stepsConsumed + 1

            _status.value = LoopStatus.Running(
                sessionId = sessionId,
                stepIndex = displayIndex,
                totalSteps = planSteps.size,
                currentAction = "${step.actionType}: ${step.intent.take(60)}"
            )

            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "step_start",
                    "session_id" to sessionId,
                    "step_index" to displayIndex,
                    "step_id" to step.id,
                    "action_type" to step.actionType,
                    "intent" to step.intent.take(120)
                )
            )

            // Capture a fresh screenshot for this step.
            val stepCapture = captureScreenshot(sessionId)
            if (stepCapture == null) {
                val failedStep = step.copy(
                    status = StepStatus.FAILED,
                    failureReason = "Screenshot capture failed before step"
                )
                executedSteps.add(failedStep)
                return@withContext terminateFailed(
                    sessionId, instruction, executedSteps,
                    stopReason = STOP_SCREENSHOT_FAILED,
                    error = "Screenshot capture failed at step $displayIndex",
                    stepIndex = displayIndex
                )
            }

            val (stepJpeg, screenW, screenH) = stepCapture

            // Execute via ExecutorBridge (grounding + AccessibilityService dispatch).
            val resultStep = executorBridge.execute(step, stepJpeg, screenW, screenH)
            executedSteps.add(resultStep)
            stepsConsumed++

            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "step_result",
                    "session_id" to sessionId,
                    "step_index" to displayIndex,
                    "step_id" to resultStep.id,
                    "action_type" to resultStep.actionType,
                    "success" to (resultStep.status == StepStatus.SUCCESS),
                    "confidence" to resultStep.confidence,
                    "failure_reason" to (resultStep.failureReason ?: "")
                )
            )

            if (resultStep.status == StepStatus.SUCCESS) {
                stepIndex++
                continue
            }

            // ── Step failed: attempt replan if retries allow ──────────────────
            val retries = resultStep.retries
            if (retries < maxRetriesPerStep && stepsConsumed < maxSteps) {
                val replanCapture = captureScreenshot(sessionId)
                val replanBase64 = replanCapture?.let { Base64.getEncoder().encodeToString(it.first) }

                val replanSequence = localPlanner.replan(
                    sessionId = sessionId,
                    instruction = instruction,
                    failedStep = resultStep,
                    failureReason = resultStep.failureReason ?: "unknown failure",
                    screenshotBase64 = replanBase64
                )

                if (replanSequence.steps.isEmpty()) {
                    return@withContext terminateFailed(
                        sessionId, instruction, executedSteps,
                        stopReason = STOP_REPLAN_FAILED,
                        error = "Replan produced no steps at step $displayIndex",
                        stepIndex = displayIndex
                    )
                }

                // Retry: put the replanned steps at the front of the remaining plan,
                // incrementing the retry counter for the first (current) step.
                val retryStep = replanSequence.steps[0].copy(
                    retries = retries + 1,
                    id = resultStep.id
                )
                planSteps = listOf(retryStep) +
                    replanSequence.steps.drop(1) +
                    planSteps.drop(stepIndex + 1)
                stepIndex = 0
                continue
            }

            // No retries left — session fails.
            return@withContext terminateFailed(
                sessionId, instruction, executedSteps,
                stopReason = STOP_STEP_EXHAUSTED,
                error = "Step $displayIndex failed after $retries retries: ${resultStep.failureReason}",
                stepIndex = displayIndex
            )
        }

        // ── Loop exited normally (all steps done or budget exhausted) ─────────
        val budgetExhausted = stepsConsumed >= maxSteps && stepIndex < planSteps.size
        val stopReason = if (budgetExhausted) STOP_MAX_STEPS else STOP_TASK_COMPLETE
        val finalStatus = if (budgetExhausted) STATUS_FAILED else STATUS_SUCCESS

        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "session_end",
                "session_id" to sessionId,
                "stop_reason" to stopReason,
                "steps_executed" to stepsConsumed,
                "final_status" to finalStatus
            )
        )

        val result = LoopResult(
            sessionId = sessionId,
            instruction = instruction,
            status = finalStatus,
            steps = executedSteps,
            stopReason = stopReason,
            error = if (budgetExhausted) "Maximum step budget ($maxSteps) reached" else null
        )

        _status.value = if (finalStatus == STATUS_SUCCESS) {
            LoopStatus.Done(sessionId, stepsConsumed, "Task completed in $stepsConsumed step(s)")
        } else {
            LoopStatus.Failed(sessionId, "Max steps reached", stepsConsumed)
        }

        result
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Verifies local model files and, when download URLs are configured, downloads any that are
     * missing or corrupted. Download failures are logged but **do not block** the loop; the
     * inference layer will degrade gracefully to rule-based planning if the model is absent.
     *
     * Always returns `true` so the loop can proceed — even with missing models — because
     * [LocalPlanner] has a rule-based fallback that functions without loaded weights.
     */
    private suspend fun ensureModels(sessionId: String): Boolean {
        val statuses = modelAssetManager.verifyAll()
        val allPresent = statuses.values.none {
            it == ModelAssetManager.ModelStatus.MISSING ||
                it == ModelAssetManager.ModelStatus.CORRUPTED
        }
        if (allPresent) return true

        val specs = modelAssetManager.downloadSpecsForMissing()
        if (specs.isEmpty()) {
            // No download URLs configured — treat absent files as non-blocking.
            return true
        }

        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "model_download_start",
                "session_id" to sessionId,
                "spec_count" to specs.size
            )
        )

        for (spec in specs) {
            val ok = modelDownloader.downloadSync(spec) { status ->
                if (status is ModelDownloader.DownloadStatus.Progress) {
                    GalaxyLogger.log(
                        TAG, mapOf(
                            "event" to "model_download_progress",
                            "model_id" to status.modelId,
                            "bytes" to status.bytesDownloaded
                        )
                    )
                }
            }
            if (!ok) {
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "model_download_failed",
                        "session_id" to sessionId,
                        "model_id" to spec.modelId
                    )
                )
                // Continue: inference will fall back to rule-based planning.
            }
        }

        // Always return true; the inference layer handles unavailable models gracefully.
        return true
    }

    /**
     * Captures a screenshot and returns ([jpegBytes], [screenWidth], [screenHeight]),
     * or null if the capture fails. Failure is logged but does not throw.
     */
    private fun captureScreenshot(sessionId: String): Triple<ByteArray, Int, Int>? {
        return try {
            val jpeg = screenshotProvider.captureJpeg()
            Triple(jpeg, screenshotProvider.screenWidth(), screenshotProvider.screenHeight())
        } catch (e: Exception) {
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "screenshot_error",
                    "session_id" to sessionId,
                    "error" to (e.message ?: "unknown")
                )
            )
            null
        }
    }

    /** Builds a failed [LoopResult] and updates [status] to [LoopStatus.Failed]. */
    private fun terminateFailed(
        sessionId: String,
        instruction: String,
        steps: List<ActionStep> = emptyList(),
        stopReason: String,
        error: String,
        stepIndex: Int
    ): LoopResult {
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "session_end",
                "session_id" to sessionId,
                "stop_reason" to stopReason,
                "error" to error,
                "step_index" to stepIndex
            )
        )
        _status.value = LoopStatus.Failed(sessionId, error, stepIndex)
        return LoopResult(
            sessionId = sessionId,
            instruction = instruction,
            status = STATUS_FAILED,
            steps = steps,
            stopReason = stopReason,
            error = error
        )
    }
}
