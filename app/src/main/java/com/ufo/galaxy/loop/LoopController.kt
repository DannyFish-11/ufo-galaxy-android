package com.ufo.galaxy.loop

import android.os.SystemClock
import com.ufo.galaxy.agent.EdgeExecutor
import com.ufo.galaxy.agent.ImageScaler
import com.ufo.galaxy.agent.NoOpImageScaler
import com.ufo.galaxy.agent.scaleForPlanning
import com.ufo.galaxy.inference.LlamaServerController
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Orchestrates the full local closed-loop automation pipeline:
 *
 *   natural-language instruction
 *     → model readiness check / download
 *     → screenshot capture
 *     → [LocalPlanner] inference (unified VLM) via [PlannerFallbackLadder]
 *     → stagnation / plan-repeat guard
 *     → [ExecutorBridge] action dispatch (unified-VLM grounding via [GroundingFallbackLadder]
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
    val goalTimeoutMs: Long = DEFAULT_GOAL_TIMEOUT_MS,
    /**
     * 规划步送图前的缩放器。默认 [NoOpImageScaler](JVM 单测用),生产由
     * `UFOGalaxyApplication` 传入 `AndroidBitmapScaler`。
     */
    private val imageScaler: ImageScaler = NoOpImageScaler(),
    /**
     * 规划服务端的上下文窗口(`llama-server -c`)。与 [EdgeExecutor] 用同一个常量 ——
     * 拉起服务的数与算预算的数必须是同一个。
     */
    private val plannerContextSize: Int = LlamaServerController.DEFAULT_CONTEXT_SIZE,
    /** 规划一次的生成预算(token)。 */
    private val plannerGenerationReserve: Int = EdgeExecutor.DEFAULT_PLANNER_GENERATION_RESERVE
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

        /**
         * 模型明确发了终止动作（[ACTION_FINISH]），也就是它自己声明"目标达成了"。
         * 这是唯一一种**有依据**的完成。
         */
        const val STOP_TASK_COMPLETE = "task_complete"

        /**
         * 计划步走完了，但模型从没说过"办完了"。
         *
         * ## 为什么要把它和 [STOP_TASK_COMPLETE] 分开
         * 此前两者是同一个:只要 `stepIndex` 走到计划末尾就报 `task_complete`。可是
         * "计划步走完了"与"目标达成了"是两件事 —— 规划器完全可能给出一份不足以完成
         * 任务的计划,每一步都执行成功,然后循环报"完成"。这是"每步都成功、什么也没
         * 做成"这一族问题在**最外层**的那一个。
         *
         * 现在两者在日志与 [LoopResult.stopReason] 里分得开，但**判定不变**：两者都仍是
         * [STATUS_SUCCESS]。这是刻意的 —— 规则兜底规划器从不发终止动作，模型也不一定
         * 每次都发，此刻把它收紧成失败，会让一批任务立刻由成功变失败，而现在还没有
         * 任何真机数据能说明那个比例有多大。先让它可见，等有了数据再决定要不要收紧。
         */
        const val STOP_PLAN_EXHAUSTED = "plan_exhausted"

        /**
         * 终止动作:模型用它声明"目标已达成,不用再操作了"。
         *
         * 不落地成任何设备动作 —— 不定位、不派发,由 [ExecutorBridge] 直接判成功。
         * 各家成熟的手机 GUI agent 框架都有这么一个动作(叫 FINISH / DONE /
         * status:complete 不等),作用正是把"我认为办完了"从"我的步骤列表到头了"里
         * 区分出来。
         */
        const val ACTION_FINISH = "finish"
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
     * CRITICAL-13: Atomic state for remote task cancellation.
     * Combines [cancelRequested] and [isRemoteTaskActive] into a single AtomicReference
     * to ensure the two flags are updated atomically and always observed consistently.
     */
    private data class RemoteTaskState(val cancelRequested: Boolean, val isRemoteTaskActive: Boolean)

    private val remoteTaskState = AtomicReference(RemoteTaskState(false, false))

    /**
     * When `true`, a remote (Gateway-assigned) task is executing and local [execute]
     * calls will return immediately with [STATUS_CANCELLED] / [STOP_BLOCKED_BY_REMOTE]
     * rather than starting a new local session.
     *
     * Set by [cancelForRemoteTask]; cleared by [clearRemoteTaskBlock].
     */
    val isRemoteTaskActive: Boolean
        get() = remoteTaskState.get().isRemoteTaskActive

    /**
     * Cancels any currently-running [execute] session and blocks new local sessions
     * until [clearRemoteTaskBlock] is called.
     *
     * Called by [com.ufo.galaxy.runtime.RuntimeController.onRemoteTaskStarted] when the
     * Gateway assigns a `task_assign` or `goal_execution` to this device.
     */
    fun cancelForRemoteTask() {
        remoteTaskState.set(RemoteTaskState(cancelRequested = true, isRemoteTaskActive = true))
    }

    /**
     * Clears the remote-task block set by [cancelForRemoteTask].
     *
     * Called by [com.ufo.galaxy.runtime.RuntimeController.onRemoteTaskFinished] after the
     * device has sent back `task_result` / `goal_result` to the Gateway. After this call,
     * local [execute] sessions may start normally again.
     */
    fun clearRemoteTaskBlock() {
        remoteTaskState.set(RemoteTaskState(cancelRequested = false, isRemoteTaskActive = false))
    }

    /** Resets only the cancel flag (used at the start of a new local session). */
    private fun clearCancelRequested() {
        // 真 bug 修复(竞态):此前是非原子的 get() + set(copy) —— 若在两步之间
        // cancelForRemoteTask() 把状态置为 {cancel=true, remoteActive=true},本方法会用
        // 过期快照整体覆盖,同时丢掉取消请求与远程占用标记,导致本地会话既取消不掉
        // 又绕过远程互斥门。改用 updateAndGet 做 CAS 更新,只翻转 cancelRequested。
        remoteTaskState.updateAndGet { it.copy(cancelRequested = false) }
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
        val goalStartMs = SystemClock.elapsedRealtime()

        // Block new local sessions while a remote (Gateway) task is active.
        if (remoteTaskState.get().isRemoteTaskActive) {
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
        clearCancelRequested()
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
                error = withScreenshotReason("首次截图失败"),
                stepIndex = 0,
                failureCode = FailureCode.SCREENSHOT_CAPTURE_FAILED
            )
        val (initialJpeg, initW, initH) = initialCapture

        // ── Phase 2: initial plan ─────────────────────────────────────────────
        // 真 bug 修复(规划输入没有预算):此前这里把**全分辨率**截图的 base64 直接送进
        // 规划器,而服务端 `-c` 恒 4096。实测(见 VisionContextBudgetTest):1080×2400 原图
        // = 3230 个视觉 token,预算 3456 —— 只剩 226 个 token 给指令;1440×3200 = 5814,
        // 直接装不下。而全链路没有任何一处数过这个数,溢出表现为"模型忽然不听话"
        // (prompt 被静默截断)而不是一个明确的失败。
        val initialBase64 = budgetedPlanningImage(sessionId, initialJpeg, initW, initH, instruction)
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
        /** 模型是否发过终止动作。决定收尾时记 task_complete 还是 plan_exhausted。 */
        var finishDeclared = false

        while (stepIndex < planSteps.size && stepsConsumed < maxSteps) {
            // ── Goal timeout check ──────────────────────────────────────────────
            if (goalTimeoutMs > 0 && (SystemClock.elapsedRealtime() - goalStartMs) >= goalTimeoutMs) {
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
            if (remoteTaskState.get().cancelRequested) {
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
                    summary = withScreenshotReason("动作前截图失败"),
                    screenshotCaptured = false
                )
                observations.add(obs)
                val failedStep = step.copy(
                    status = StepStatus.FAILED,
                    failureReason = withScreenshotReason("动作前截图失败"),
                    failureCode = FailureCode.SCREENSHOT_CAPTURE_FAILED
                )
                executedSteps.add(failedStep)
                return@withContext terminateFailed(
                    sessionId, instruction, executedSteps,
                    stopReason = STOP_SCREENSHOT_FAILED,
                    error = withScreenshotReason("第 $displayIndex 步动作前截图失败"),
                    stepIndex = displayIndex,
                    failureCode = FailureCode.SCREENSHOT_CAPTURE_FAILED,
                    observations = observations
                )
            }

            val (stepJpeg, screenW, screenH) = stepCapture
            val stepStartMs = SystemClock.elapsedRealtime()

            // Execute via ExecutorBridge (grounding fallback ladder + AccessibilityService dispatch).
            val resultStep = executorBridge.execute(step, stepJpeg, screenW, screenH)

            // ── Step timeout check ──────────────────────────────────────────────
            val stepElapsedMs = SystemClock.elapsedRealtime() - stepStartMs
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
                // 终止动作:模型说"办完了"。立刻收尾,不做停滞检查 ——
                // finish 天然不改变界面,再送进停滞检测会被算成"又一步没有进展",
                // 有概率把一次**有依据的完成**判成 stagnation 失败。
                if (step.actionType == ACTION_FINISH) {
                    finishDeclared = true
                    stepIndex = planSteps.size
                    break
                }

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
                // 重规划与首次规划走同一套预算 —— 此前这里同样送全分辨率原图。
                val replanBase64 = replanCapture?.let { (jpeg, w, h) ->
                    budgetedPlanningImage(sessionId, jpeg, w, h, instruction)
                }

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
        // task_complete 与 plan_exhausted 的区别:前者是模型明确说"办完了"(有依据),
        // 后者只是步骤列表到头了(无依据)。判定仍然一致 —— 见 STOP_PLAN_EXHAUSTED 的
        // 文档:此刻收紧会让一批任务立刻由成功变失败,而还没有真机数据能说明比例。
        val stopReason = when {
            budgetExhausted -> STOP_MAX_STEPS
            finishDeclared -> STOP_TASK_COMPLETE
            else -> STOP_PLAN_EXHAUSTED
        }
        val finalStatus = if (budgetExhausted) STATUS_FAILED else STATUS_SUCCESS
        val terminalFailureCode = if (budgetExhausted) FailureCode.LOOP_MAX_STEPS_REACHED else null

        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "session_end",
                "session_id" to sessionId,
                "stop_reason" to stopReason,
                "steps_executed" to stepsConsumed,
                "final_status" to finalStatus,
                // 单独一个字段,便于回流后直接统计"多少比例的任务模型真的说了办完了"。
                // 要不要把 plan_exhausted 收紧成失败,取决于这个数。
                "goal_declared_complete" to finishDeclared
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
            LoopStatus.Done(
                sessionId, stepsConsumed,
                if (finishDeclared) "任务完成（模型声明目标已达成），共 $stepsConsumed 步"
                else "计划步已执行完，共 $stepsConsumed 步（模型未声明目标达成）"
            )
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
            } else if (modelAssetManager.effectiveChecksum(spec.modelId) == null) {
                // 真 bug 修复(校验闭环缺失):TOFU 契约要求"首次下载成功后立即
                // persistComputedChecksum",但生产链路从未调用它 —— 权重文件永远没有
                // 基线摘要,verifyModel 恒跳过校验,后续损坏/篡改无法被发现。此处在
                // 首次下载成功后补上持久化,使后续每次 verify 都强制校验。
                modelAssetManager.persistComputedChecksum(spec.modelId)
            }
        }

        return true
    }

    /**
     * Captures a screenshot and returns ([jpegBytes], [screenWidth], [screenHeight]),
     * or null if the capture fails. Failure is logged but does not throw.
     */
    /**
     * 规划步送图:长边按本次调用的实际预算反推(见 [scaleForPlanning])。
     *
     * 预算不足以支撑最小可读尺寸时返回 null —— 不送图,让规划器只凭指令工作。
     * 送一张糊到读不出图标的图,和送一张会把指令挤掉的图,都是错的。
     */
    private fun budgetedPlanningImage(
        sessionId: String,
        jpegBytes: ByteArray,
        fullWidth: Int,
        fullHeight: Int,
        instruction: String
    ): String? {
        val planned = imageScaler.scaleForPlanning(
            jpegBytes = jpegBytes,
            fullWidth = fullWidth,
            fullHeight = fullHeight,
            contextSize = plannerContextSize,
            generationReserve = plannerGenerationReserve,
            instruction
        )
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to if (planned.hasImage) "planning_image_budgeted" else "planning_image_dropped",
                "session_id" to sessionId,
                "context_size" to plannerContextSize,
                "generation_reserve" to plannerGenerationReserve,
                "vision_token_budget" to planned.visionTokenBudget,
                "max_edge" to planned.maxEdge,
                "scaled_width" to planned.scaledWidth,
                "scaled_height" to planned.scaledHeight,
                "vision_tokens" to planned.visionTokens
            )
        )
        return planned.base64
    }

    /**
     * 最近一次截图失败的原因。截图成功后清空。
     *
     * 此前这里只是 `return null` —— provider 抛出的异常信息(节流 / FLAG_SECURE 窗口 /
     * 能力位被吊销,处置完全不同)在这一层被丢干净,任务最终只报一句
     * `Screenshot capture failed`。真机日志回流回来时无从判断该修什么。
     *
     * 放在实例字段上而不是逐层传参:本类本来就是**单会话**的 —— [_status] 是一条
     * StateFlow,两个并发会话早就会把状态搅乱,并发从来不在契约里。`@Volatile` 只是
     * 保证跨线程可见(execute 在 IO 线程,状态可能被别的线程读)。
     */
    @Volatile
    private var lastScreenshotError: String? = null

    private fun captureScreenshot(sessionId: String): Triple<ByteArray, Int, Int>? {
        return try {
            val jpeg = screenshotProvider.captureJpeg()
            lastScreenshotError = null
            Triple(jpeg, screenshotProvider.screenWidth(), screenshotProvider.screenHeight())
        } catch (e: Exception) {
            lastScreenshotError = e.message ?: e::class.java.simpleName
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "screenshot_error",
                    "session_id" to sessionId,
                    "error" to (lastScreenshotError ?: "unknown")
                )
            )
            null
        }
    }

    /** 把截图失败的具体原因接在给人看的错误信息后面。 */
    private fun withScreenshotReason(message: String): String =
        lastScreenshotError?.let { "$message: $it" } ?: message

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
