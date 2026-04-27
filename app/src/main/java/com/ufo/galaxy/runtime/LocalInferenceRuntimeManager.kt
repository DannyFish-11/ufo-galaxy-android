package com.ufo.galaxy.runtime

import android.util.Log
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.LocalPlannerService
import com.ufo.galaxy.inference.WarmupResult
import com.ufo.galaxy.model.ModelAssetManager
import com.ufo.galaxy.observability.GalaxyLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * **Local runtime lifecycle manager** for the on-device inference pair (planner + grounding).
 *
 * This class is the single authority for starting, stopping, health-checking, and recovering
 * the MobileVLM planner and SeeClick grounding runtimes. It enforces **real inference
 * readiness** — not just endpoint liveness — before reporting a component as healthy.
 *
 * ## Lifecycle
 * ```
 * Stopped ──start()──► Starting ──► Running
 *                                 ├──► Degraded   (partial start)
 *                                 └──► Failed     (both runtimes down)
 * Any state ──stop()──► Stopped
 * Any state ──restart()──► Starting ──► (same as start)
 * Any state ──enterSafeMode()──► SafeMode
 * SafeMode ──clearSafeMode()──► Stopped
 * ```
 *
 * ## Recovery hooks
 * - [enterSafeMode]: unloads both runtimes and blocks inference; persists until
 *   [clearSafeMode] or [restart].
 * - [restart]: stop + start in one call; useful after repeated health failures.
 * - [healthCheck]: non-mutating snapshot; call at any time.
 *
 * @param plannerService     MobileVLM planner backend.
 * @param groundingService   SeeClick grounding backend.
 * @param modelAssetManager  Model file registry; consulted during start for file readiness.
 */
class LocalInferenceRuntimeManager(
    private val plannerService: LocalPlannerService,
    private val groundingService: LocalGroundingService,
    private val modelAssetManager: ModelAssetManager
) {

    // ── Observable state ─────────────────────────────────────────────────────

    /**
     * Observable lifecycle state of the local inference runtime pair.
     */
    sealed class ManagerState {
        /** No start has been requested since the last [stop] or manager creation. */
        object Stopped : ManagerState()

        /** [start] is in progress. */
        object Starting : ManagerState()

        /**
         * Both runtimes passed all warmup stages and are ready for inference.
         * [snapshot] reflects health at the moment [start] completed.
         */
        data class Running(val snapshot: RuntimeHealthSnapshot) : ManagerState()

        /**
         * At least one runtime is partially operational; the app may continue in a
         * limited mode (e.g., rule-based planning when the planner is down).
         * [reason] summarises which component(s) failed and at which stage.
         */
        data class Degraded(
            val snapshot: RuntimeHealthSnapshot,
            val reason: String
        ) : ManagerState()

        /**
         * Both runtimes failed to start. [reason] describes the combined failure.
         * Recovery: call [restart] or [enterSafeMode].
         */
        data class Failed(val reason: String) : ManagerState()

        /**
         * Runtimes have been unloaded and inference is blocked.
         * Entered via [enterSafeMode]; exited via [clearSafeMode] (then [restart]).
         */
        object SafeMode : ManagerState()

        /**
         * A prior health check detected one or more unhealthy components; the manager is
         * actively stopping and re-starting the runtimes in an attempt to recover.
         *
         * Consumers that observe [RECOVERING][com.ufo.galaxy.runtime.LocalIntelligenceCapabilityStatus.RECOVERING]
         * should pause any queued inference requests until the state transitions back to
         * [Running] or [Degraded], or falls through to [Failed].
         */
        object Recovering : ManagerState()
    }

    private val _state = MutableStateFlow<ManagerState>(ManagerState.Stopped)

    /**
     * Current lifecycle state of the runtime manager.
     * Observe from the diagnostics UI or recovery logic to react to state changes.
     */
    val state: StateFlow<ManagerState> = _state.asStateFlow()

    /**
     * True while the manager is in [ManagerState.SafeMode].
     * Consumers should check this before dispatching inference requests.
     */
    @Volatile
    var isInSafeMode: Boolean = false
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Primary lifecycle ─────────────────────────────────────────────────────

    /**
     * Starts both the planner and grounding runtimes.
     *
     * Steps:
     * 1. Checks model file readiness via [ModelAssetManager].
     * 2. Calls [startPlanner] and [startGrounding] in sequence.
     * 3. Transitions to [ManagerState.Running], [ManagerState.Degraded], or
     *    [ManagerState.Failed] based on combined results.
     *
     * Returns a [RuntimeStartResult] describing the overall outcome.
     * This is a suspend function; call from a coroutine scope (e.g., an IO scope).
     */
    suspend fun start(): RuntimeStartResult {
        if (isInSafeMode) {
            val reason = "Cannot start runtimes while in safe mode — call clearSafeMode() first"
            Log.w(TAG, reason)
            return RuntimeStartResult.Failure(RuntimeStartResult.StartStage.HEALTH_CHECK, reason)
        }

        _state.value = ManagerState.Starting
        GalaxyLogger.log(TAG, mapOf("event" to "inference_runtime_starting"))

        // Step 1: model file readiness check
        val fileResult = checkModelFiles()
        if (fileResult != null) {
            _state.value = ManagerState.Failed(fileResult.message)
            GalaxyLogger.log(TAG, mapOf("event" to "inference_runtime_failed", "reason" to fileResult.message))
            return fileResult
        }

        // Step 2: warm up each runtime independently
        val plannerResult = startPlanner()
        val groundingResult = startGrounding()

        // Step 3: take a health snapshot and derive manager state
        val snapshot = healthCheck()
        val combinedResult = deriveResult(plannerResult, groundingResult, snapshot)

        when (combinedResult) {
            is RuntimeStartResult.Success -> {
                _state.value = ManagerState.Running(snapshot)
                GalaxyLogger.log(TAG, mapOf("event" to "inference_runtime_running"))
                Log.i(TAG, "Both inference runtimes started successfully")
            }
            is RuntimeStartResult.Degraded -> {
                _state.value = ManagerState.Degraded(snapshot, combinedResult.message)
                GalaxyLogger.log(
                    TAG,
                    mapOf("event" to "inference_runtime_degraded", "reason" to combinedResult.message)
                )
                Log.w(TAG, "Inference runtimes started in degraded state: ${combinedResult.message}")
            }
            is RuntimeStartResult.Failure -> {
                _state.value = ManagerState.Failed(combinedResult.message)
                GalaxyLogger.log(
                    TAG,
                    mapOf("event" to "inference_runtime_failed", "reason" to combinedResult.message)
                )
                Log.e(TAG, "Inference runtimes failed to start: ${combinedResult.message}")
            }
        }

        return combinedResult
    }

    /**
     * Stops both runtimes and transitions to [ManagerState.Stopped].
     * Does not emit any failure events; safe to call at any time.
     */
    fun stop() {
        Log.i(TAG, "Stopping inference runtimes")
        GalaxyLogger.log(TAG, mapOf("event" to "inference_runtime_stop"))
        plannerService.unloadModel()
        groundingService.unloadModel()
        isInSafeMode = false
        _state.value = ManagerState.Stopped
    }

    /**
     * Stops both runtimes and restarts them from scratch.
     * Useful after repeated health failures or model file updates.
     *
     * Returns the [RuntimeStartResult] from the fresh [start] attempt.
     */
    suspend fun restart(): RuntimeStartResult {
        Log.i(TAG, "Restarting inference runtimes")
        GalaxyLogger.log(TAG, mapOf("event" to "inference_runtime_restart"))
        stop()
        return start()
    }

    /**
     * Checks the current health of both runtimes and, if either is unhealthy, performs a
     * controlled stop-then-start cycle to restore them.
     *
     * ## State transitions
     * ```
     * healthy:   (no change)              → returns RuntimeStartResult.Success immediately
     * unhealthy: Running/Degraded/Failed
     *           → Recovering
     *           → Starting
     *           → Running | Degraded | Failed
     * ```
     *
     * ## Safe-mode interaction
     * If the manager is already in [ManagerState.SafeMode], this call returns the same
     * [RuntimeStartResult.Failure] that [start] would return; no recovery is attempted.
     *
     * @return [RuntimeStartResult.Success] when the runtime was already healthy (no action
     *         taken), or the [RuntimeStartResult] from the recovery [start] attempt when a
     *         restart was needed.
     */
    suspend fun recoverIfUnhealthy(): RuntimeStartResult {
        if (isInSafeMode) {
            val reason = "Cannot recover: runtime is in safe mode — call clearSafeMode() first"
            Log.w(TAG, reason)
            return RuntimeStartResult.Failure(RuntimeStartResult.StartStage.HEALTH_CHECK, reason)
        }

        val snapshot = healthCheck()
        if (snapshot.isFullyHealthy) {
            Log.d(TAG, "recoverIfUnhealthy: runtime is healthy, no recovery needed")
            return RuntimeStartResult.Success
        }

        Log.w(
            TAG,
            "recoverIfUnhealthy: unhealthy components detected " +
                "(planner=${snapshot.plannerHealth}, grounding=${snapshot.groundingHealth}) — recovering"
        )
        GalaxyLogger.log(
            TAG,
            mapOf(
                "event" to "inference_runtime_recover_start",
                "plannerHealth" to snapshot.plannerHealth.name,
                "groundingHealth" to snapshot.groundingHealth.name
            )
        )

        // Unload both runtimes and signal the Recovering state before attempting restart.
        plannerService.unloadModel()
        groundingService.unloadModel()
        _state.value = ManagerState.Recovering

        val result = start()
        GalaxyLogger.log(
            TAG,
            mapOf("event" to "inference_runtime_recover_done", "outcome" to result::class.simpleName)
        )
        return result
    }

    // ── Per-component start ───────────────────────────────────────────────────

    /**
     * Warms up the planner runtime via [LocalPlannerService.warmupWithResult].
     *
     * Returns a [RuntimeStartResult] mapped from the [WarmupResult] stage.
     * Does not change [_state]; callers are responsible for state transitions.
     */
    suspend fun startPlanner(): RuntimeStartResult = runCatching {
        val warmup = plannerService.warmupWithResult()
        if (warmup.success) {
            Log.i(TAG, "Planner runtime started successfully")
            RuntimeStartResult.Success
        } else {
            Log.w(TAG, "Planner warmup failed at ${warmup.stage}: ${warmup.error}")
            RuntimeStartResult.Failure(
                stage = warmup.stage.toStartStage(),
                message = warmup.error ?: "Planner warmup failed at ${warmup.stage}"
            )
        }
    }.getOrElse { e ->
        Log.e(TAG, "Planner start threw unexpected exception: ${e.message}", e)
        RuntimeStartResult.Failure(
            stage = RuntimeStartResult.StartStage.HEALTH_CHECK,
            message = "Planner start exception: ${e.message}",
            cause = e
        )
    }

    /**
     * Warms up the grounding runtime via [LocalGroundingService.warmupWithResult].
     *
     * Returns a [RuntimeStartResult] mapped from the [WarmupResult] stage.
     * Does not change [_state]; callers are responsible for state transitions.
     */
    suspend fun startGrounding(): RuntimeStartResult = runCatching {
        val warmup = groundingService.warmupWithResult()
        if (warmup.success) {
            Log.i(TAG, "Grounding runtime started successfully")
            RuntimeStartResult.Success
        } else {
            Log.w(TAG, "Grounding warmup failed at ${warmup.stage}: ${warmup.error}")
            RuntimeStartResult.Failure(
                stage = warmup.stage.toStartStage(),
                message = warmup.error ?: "Grounding warmup failed at ${warmup.stage}"
            )
        }
    }.getOrElse { e ->
        Log.e(TAG, "Grounding start threw unexpected exception: ${e.message}", e)
        RuntimeStartResult.Failure(
            stage = RuntimeStartResult.StartStage.HEALTH_CHECK,
            message = "Grounding start exception: ${e.message}",
            cause = e
        )
    }

    // ── Health check ─────────────────────────────────────────────────────────

    /**
     * Returns a [RuntimeHealthSnapshot] reflecting the current state of both runtimes.
     *
     * This is a **non-mutating** call; it does not change [state] or reload any model.
     * Suitable for periodic background health polling.
     */
    fun healthCheck(): RuntimeHealthSnapshot {
        val plannerHealth = if (plannerService.isModelLoaded())
            RuntimeHealthSnapshot.ComponentHealth.HEALTHY
        else
            RuntimeHealthSnapshot.ComponentHealth.UNHEALTHY

        val groundingHealth = if (groundingService.isModelLoaded())
            RuntimeHealthSnapshot.ComponentHealth.HEALTHY
        else
            RuntimeHealthSnapshot.ComponentHealth.UNHEALTHY

        return RuntimeHealthSnapshot(
            plannerHealth = plannerHealth,
            groundingHealth = groundingHealth
        )
    }

    // ── Safe mode / recovery ─────────────────────────────────────────────────

    /**
     * Enters safe mode: unloads both runtimes and blocks inference until
     * [clearSafeMode] is called followed by [restart].
     *
     * Use when a repeated health failure or incompatible runtime state is detected
     * and the app must not attempt further inference until the issue is resolved.
     */
    fun enterSafeMode() {
        Log.w(TAG, "Entering safe mode — inference blocked")
        GalaxyLogger.log(TAG, mapOf("event" to "inference_safe_mode_enter"))
        plannerService.unloadModel()
        groundingService.unloadModel()
        isInSafeMode = true
        _state.value = ManagerState.SafeMode
    }

    /**
     * Clears safe mode without restarting runtimes.
     *
     * After calling this, invoke [restart] to bring runtimes back online.
     * Transitions to [ManagerState.Stopped].
     */
    fun clearSafeMode() {
        if (!isInSafeMode) return
        Log.i(TAG, "Clearing safe mode — call restart() to bring runtimes online")
        GalaxyLogger.log(TAG, mapOf("event" to "inference_safe_mode_clear"))
        isInSafeMode = false
        _state.value = ManagerState.Stopped
    }

    /**
     * Cancels the internal coroutine scope.
     * Call from [android.app.Application.onTerminate] or equivalent lifecycle cleanup.
     */
    fun cancel() {
        scope.cancel()
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Verifies that model files are present and not corrupted before warming up servers.
     * Returns a [RuntimeStartResult.Failure] at [RuntimeStartResult.StartStage.MODEL_FILES]
     * if any required file is missing or corrupted; null otherwise.
     */
    private fun checkModelFiles(): RuntimeStartResult.Failure? {
        val statuses = modelAssetManager.verifyAll()
        val bad = statuses.entries.filter { (_, status) ->
            status == ModelAssetManager.ModelStatus.MISSING ||
                status == ModelAssetManager.ModelStatus.CORRUPTED
        }
        if (bad.isEmpty()) return null
        val detail = bad.joinToString { (id, status) -> "'$id'=$status" }
        return RuntimeStartResult.Failure(
            stage = RuntimeStartResult.StartStage.MODEL_FILES,
            message = "Model files not ready: $detail"
        )
    }

    /**
     * Derives a combined [RuntimeStartResult] from the individual planner and grounding
     * results plus the current health snapshot.
     */
    private fun deriveResult(
        plannerResult: RuntimeStartResult,
        groundingResult: RuntimeStartResult,
        @Suppress("UNUSED_PARAMETER") snapshot: RuntimeHealthSnapshot
    ): RuntimeStartResult {
        return when {
            plannerResult.isSuccess && groundingResult.isSuccess -> RuntimeStartResult.Success
            plannerResult.isUsable || groundingResult.isUsable -> {
                val msg = buildCombinedMessage(plannerResult, groundingResult)
                RuntimeStartResult.Degraded(msg)
            }
            else -> {
                val msg = buildCombinedMessage(plannerResult, groundingResult)
                    .ifEmpty { "Both inference runtimes failed to start" }
                RuntimeStartResult.Failure(RuntimeStartResult.StartStage.HEALTH_CHECK, msg)
            }
        }
    }

    private fun buildCombinedMessage(
        plannerResult: RuntimeStartResult,
        groundingResult: RuntimeStartResult
    ): String = buildString {
        if (plannerResult !is RuntimeStartResult.Success) {
            append("planner: ${plannerResult.failureMessage()}")
        }
        if (groundingResult !is RuntimeStartResult.Success) {
            if (isNotEmpty()) append("; ")
            append("grounding: ${groundingResult.failureMessage()}")
        }
    }

    private fun RuntimeStartResult.failureMessage(): String = when (this) {
        is RuntimeStartResult.Failure -> message
        is RuntimeStartResult.Degraded -> message
        is RuntimeStartResult.Success -> ""
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "GALAXY:RUNTIME:MGR"
    }
}

// ── Internal mapping extension ───────────────────────────────────────────────

/**
 * Maps a [WarmupResult.WarmupStage] to the closest [RuntimeStartResult.StartStage].
 */
private fun WarmupResult.WarmupStage.toStartStage(): RuntimeStartResult.StartStage = when (this) {
    WarmupResult.WarmupStage.HEALTH_CHECK -> RuntimeStartResult.StartStage.HEALTH_CHECK
    WarmupResult.WarmupStage.DRY_RUN_INFERENCE -> RuntimeStartResult.StartStage.DRY_RUN
    WarmupResult.WarmupStage.RESPONSE_VALIDATION,
    WarmupResult.WarmupStage.PARSE_VALIDATION -> RuntimeStartResult.StartStage.RESPONSE_VALIDATION
    WarmupResult.WarmupStage.SUCCESS -> RuntimeStartResult.StartStage.HEALTH_CHECK
}
