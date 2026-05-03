package com.ufo.galaxy.loop

import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.local.FailureCode
import com.ufo.galaxy.local.PostActionObserver
import com.ufo.galaxy.local.StagnationDetector
import com.ufo.galaxy.local.StepObservation
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
 *     → [LocalPlanner] inference (MobileVLM) via [PlannerFallbackLadder]
 *     → stagnation / plan-repeat guard
 *     → [ExecutorBridge] action dispatch (SeeClick grounding via [GroundingFallbackLadder]
 *         + AccessibilityService)
 *     → post-action screenshot + [PostActionObserver] observation
 *     → [StagnationDetector] step guard
 *     → repeat until completion or budget / timeout / stagnation termination
 *
 * Progress is exposed via [status] ([StateFlow]) so the UI layer can observe
 * each state transition without polling. All blocking work runs on [Dispatchers.IO];
 * callers must invoke [execute] from a coroutine — never from the main thread.
 *
 * Each step is logged with: step index, action type, intent, execution result,
 * confidence score, grounding stage, UI-change flag, and stop reason (on termination).
 *
 * @param localPlanner           Plans [ActionSequence]s from instruction + screenshot.
 * @param executorBridge         Dispatches each [ActionStep] to the device.
 * @param screenshotProvider     Captures JPEG screenshots of the device screen.
 * @param modelAssetManager      Checks and tracks local model file status.
 * @param modelDownloader        Downloads missing model files on demand.
 * @param maxSteps               Hard cap on total dispatched steps (including replans).
 * @param maxRetriesPerStep      Max retries for a single failing step before giving up.
 * @param stagnationDetector     Detects repeated-action and no-UI-change stalls.
 * @param postActionObserver     Records structured post-action observations.
 * @param stepTimeoutMs          Per-step wall-clock timeout in milliseconds; 0 = disabled.
 * @param goalTimeoutMs          Total session wall-clock timeout in milliseconds; 0 = disabled.
 */
class LoopController(
    private val localPlanner: LocalPlanner,
    private val executorBridge: ExecutorBridge,
    private val screenshotProvider: EdgeExecutor.ScreenshotProvider,
    private val modelAssetManager: ModelAssetManager,
    private val modelDownloader: ModelDownloader,
    val maxSteps: Int = DEFAULT_MAX_STEPS,
    val maxRetriesPerStep: Int = DEFAULT_MAX_RETRIES,
    private val stagnationDetector: StagnationDetector = StagnationDetector(),
    private val postActionObserver: PostActionObserver = PostActionObserver(),
    val stepTimeoutMs: Long = DEFAULT_STEP_TIMEOUT_MS,
    val goalTimeoutMs: Long = DEFAULT_GOAL_TIMEOUT_MS
) {

    companion object {
        const val DEFAULT_MAX_STEPS = 10
        const val DEFAULT_MAX_RETRIES = 2
        const val DEFAULT_STEP_TIMEOUT_MS = 0L   // disabled by default
        const val DEFAULT_GOAL_TIMEOUT_MS = 0L   // disabled by default

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
        const val STOP_STAGNATION = "stagnation_detected"
        const val STOP_STEP_TIMEOUT = "step_timeout"
        const val STOP_GOAL_TIMEOUT = "goal_timeout"
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
        val goalStartMs = System.currentTimeMillis()

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
                steps = emptyList(),
                stopReason = STOP_BLOCKED_BY_REMOTE,
                error = "Local execution blocked while remote task is active",
                failureCode = FailureCode.LOOP_BLOCKED_BY_REMOTE
            )
        }

        // Reset cancel flag and stagnation state at the start of each new session.
        cancelRequested = false
        stagnationDetector.reset()

        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "session_start",
                "session_id" to sessionId,
                "instruction" to instruction.take(120),
                "max_steps" to maxSteps,
                "max_retries_per_step" to maxRetriesPerStep,
                "step_timeout_ms" to stepTimeoutMs,
                "goal_timeout_ms" to goalTimeoutMs
            )
        )

        _status.value = LoopStatus.Running(sessionId, 0, 0, "Initializing")

        // ── Phase 0: ensure local model files are present ─────────────────────
        ensureModels(sessionId)

        // ── Phase 1: initial screenshot ───────────────────────────────────────
        val initialCapture = captureScreenshot(sessionId)
            ?: return@withContext terminateFailed(
                sessionId, instruction,
                stopReason = STOP_SCREENSHOT_FAILED,
                error = "Initial screenshot capture failed",
                stepIndex = 0,
                failureCode = FailureCode.SCREENSHOT_CAPTURE_FAILED
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
                stepIndex = 0,
                failureCode = FailureCode.PLAN_ALL_STAGES_EXHAUSTED
            )
        }

        // Record plan signature for repeated-plan detection.
        val initialPlanSig = stagnationDetector.buildPlanSignature(
            sequence.steps.map { it.actionType to it.intent }
        )
        stagnationDetector.recordPlan(initialPlanSig)

        // ── Phase 3: execution loop ───────────────────────────────────────────
        val executedSteps = mutableListOf<ActionStep>()
        val observations = mutableListOf<StepObservation>()
        var planSteps = sequence.steps
        var stepIndex = 0
        var stepsConsumed = 0
        var prevJpeg: ByteArray? = initialJpeg

        while (stepIndex < planSteps.size && stepsConsumed < maxSteps) {
            // ── Goal timeout check ──────────────────────────────────────────────
            if (goalTimeoutMs > 0 && (System.currentTimeMillis() - goalStartMs) >= goalTimeoutMs) {
                return@withContext terminateFailed(
                    sessionId, instruction, executedSteps,
                    stopReason = STOP_GOAL_TIMEOUT,
                    error = "Goal timeout (${goalTimeoutMs}ms) exceeded at step ${stepsConsumed + 1}",
                    stepIndex = stepsConsumed,
                    failureCode = FailureCode.LOOP_GOAL_TIMEOUT,
                    observations = observations
                )
            }

            // ── Remote-task cancellation check ─────────────────────────────────
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
                    error = "Cancelled by remote task assignment",
                    failureCode = FailureCode.LOOP_CANCELLED_BY_REMOTE,
                    observations = observations
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
                val obs = StepObservation.failure(
                    stepId = step.id,
                    actionType = step.actionType,
                    intent = step.intent,
                    failureCode = FailureCode.SCREENSHOT_CAPTURE_FAILED,
                    summary = "Screenshot capture failed before step",
                    screenshotCaptured = false
                )
                observations.add(obs)
                val failedStep = step.copy(
                    status = StepStatus.FAILED,
                    failureReason = "Screenshot capture failed before step",
                    failureCode = FailureCode.SCREENSHOT_CAPTURE_FAILED
                )
                executedSteps.add(failedStep)
                return@withContext terminateFailed(
                    sessionId, instruction, executedSteps,
                    stopReason = STOP_SCREENSHOT_FAILED,
                    error = "Screenshot capture failed at step $displayIndex",
                    stepIndex = displayIndex,
                    failureCode = FailureCode.SCREENSHOT_CAPTURE_FAILED,
                    observations = observations
                )
            }

            val (stepJpeg, screenW, screenH) = stepCapture
            val stepStartMs = System.currentTimeMillis()

            // Execute via ExecutorBridge (grounding fallback ladder + AccessibilityService dispatch).
            val resultStep = executorBridge.execute(step, stepJpeg, screenW, screenH)

            // ── Step timeout check ──────────────────────────────────────────────
            val stepElapsedMs = System.currentTimeMillis() - stepStartMs
            if (stepTimeoutMs > 0 && stepElapsedMs >= stepTimeoutMs) {
                val obs = StepObservation.failure(
                    stepId = step.id,
                    actionType = step.actionType,
                    intent = step.intent,
                    failureCode = FailureCode.LOOP_STEP_TIMEOUT,
                    summary = "Step exceeded timeout (${stepTimeoutMs}ms)"
                )
                observations.add(obs)
                executedSteps.add(resultStep.copy(
                    status = StepStatus.FAILED,
                    failureReason = "Step timeout (${stepTimeoutMs}ms)",
                    failureCode = FailureCode.LOOP_STEP_TIMEOUT
                ))
                return@withContext terminateFailed(
                    sessionId, instruction, executedSteps,
                    stopReason = STOP_STEP_TIMEOUT,
                    error = "Step $displayIndex exceeded timeout (${stepTimeoutMs}ms)",
                    stepIndex = displayIndex,
                    failureCode = FailureCode.LOOP_STEP_TIMEOUT,
                    observations = observations
                )
            }

            // Capture post-action screenshot for UI-change detection.
            val postCapture = captureScreenshot(sessionId)
            val postJpeg = postCapture?.first

            // Build structured post-action observation.
            val observation = postActionObserver.observe(
                stepId = step.id,
                actionType = step.actionType,
                intent = step.intent,
                actionSucceeded = resultStep.status == StepStatus.SUCCESS,
                failureCode = resultStep.failureCode,
                confidence = resultStep.confidence,
                targetMatched = if (resultStep.confidence > 0f) true else null,
                beforeJpeg = prevJpeg,
                afterJpeg = postJpeg
            )
            observations.add(observation)

            // Update prevJpeg for the next iteration's UI-change comparison.
            if (postJpeg != null) prevJpeg = postJpeg

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
                    "failure_reason" to (resultStep.failureReason ?: ""),
                    "failure_code" to (resultStep.failureCode?.token ?: ""),
                    "ui_changed" to observation.uiChanged
                )
            )

            if (resultStep.status == StepStatus.SUCCESS) {
                // ── Stagnation check after successful step ──────────────────────
                val stagnationCode = stagnationDetector.recordStep(observation)
                if (stagnationCode != null) {
                    return@withContext terminateFailed(
                        sessionId, instruction, executedSteps,
                        stopReason = STOP_STAGNATION,
                        error = "Stagnation detected after step $displayIndex: ${stagnationCode.description}",
                        stepIndex = displayIndex,
                        failureCode = stagnationCode,
                        observations = observations
                    )
                }
                stepIndex++
                continue
            }

            // ── Step failed: record stagnation signal before replanning ─────────
            val stagnationCode = stagnationDetector.recordStep(observation)
            if (stagnationCode != null) {
                return@withContext terminateFailed(
                    sessionId, instruction, executedSteps,
                    stopReason = STOP_STAGNATION,
                    error = "Stagnation detected at step $displayIndex: ${stagnationCode.description}",
                    stepIndex = displayIndex,
                    failureCode = stagnationCode,
                    observations = observations
                )
            }

            // ── Step failed: attempt replan if retries allow ─────────────────────
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
                        stepIndex = displayIndex,
                        failureCode = FailureCode.PLAN_REPLAN_EXHAUSTED,
                        observations = observations
                    )
                }

                // Check repeated-plan stagnation.
                val replanSig = stagnationDetector.buildPlanSignature(
                    replanSequence.steps.map { it.actionType to it.intent }
                )
                val planStagnation = stagnationDetector.recordPlan(replanSig)
                if (planStagnation != null) {
                    return@withContext terminateFailed(
                        sessionId, instruction, executedSteps,
                        stopReason = STOP_STAGNATION,
                        error = "Repeated-plan stagnation at step $displayIndex",
                        stepIndex = displayIndex,
                        failureCode = planStagnation,
                        observations = observations
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
                stepIndex = displayIndex,
                failureCode = FailureCode.LOOP_STEP_RETRIES_EXHAUSTED,
                observations = observations
            )
        }

        // ── Loop exited normally (all steps done or budget exhausted) ─────────
        val budgetExhausted = stepsConsumed >= maxSteps && stepIndex < planSteps.size
        val stopReason = if (budgetExhausted) STOP_MAX_STEPS else STOP_TASK_COMPLETE
        val finalStatus = if (budgetExhausted) STATUS_FAILED else STATUS_SUCCESS
        val terminalFailureCode = if (budgetExhausted) FailureCode.LOOP_MAX_STEPS_REACHED else null

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
            error = if (budgetExhausted) "Maximum step budget ($maxSteps) reached" else null,
            failureCode = terminalFailureCode,
            observations = observations
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
            }
        }

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
        stepIndex: Int,
        failureCode: FailureCode? = null,
        observations: List<StepObservation> = emptyList()
    ): LoopResult {
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "session_end",
                "session_id" to sessionId,
                "stop_reason" to stopReason,
                "error" to error,
                "step_index" to stepIndex,
                "failure_code" to (failureCode?.token ?: "")
            )
        )
        _status.value = LoopStatus.Failed(sessionId, error, stepIndex)
        return LoopResult(
            sessionId = sessionId,
            instruction = instruction,
            status = STATUS_FAILED,
            steps = steps,
            stopReason = stopReason,
            error = error,
            failureCode = failureCode,
            observations = observations
        )
    }
}
