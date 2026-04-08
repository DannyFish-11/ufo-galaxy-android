package com.ufo.galaxy.ui.viewmodel

import com.ufo.galaxy.local.LocalLoopResult
import com.ufo.galaxy.runtime.TakeoverFallbackEvent

/**
 * PR-26: Unified result presentation for all Android execution paths.
 *
 * Normalizes the user-facing result summary across local execution, cross-device
 * execution, delegated execution, and fallback paths so that presentation consumers
 * ([MainViewModel], [com.ufo.galaxy.service.EnhancedFloatingService]) produce a single
 * coherent message experience regardless of which internal path originated the result.
 *
 * Callers must use the factory methods in [UnifiedResultPresentation.Companion] — one per
 * execution path — to produce a presentation instance.  No consumer should inspect raw
 * [LocalLoopResult.status] strings or [TakeoverFallbackEvent.cause] values to build its
 * own summary; all formatting logic lives here.
 *
 * @property summary   Human-readable summary suitable for display in the chat message list
 *                     or floating-window status label.  Does **not** mention which internal
 *                     execution path produced the result.
 * @property isSuccess True when the result represents a successful outcome.
 * @property outcome   Wire-level outcome string used for diagnostics and task-ledger entries
 *                     (e.g. [LocalLoopResult.STATUS_SUCCESS], [TakeoverFallbackEvent.Cause.wireValue]).
 */
data class UnifiedResultPresentation(
    val summary: String,
    val isSuccess: Boolean,
    val outcome: String
) {
    companion object {

        /**
         * Normalizes a [LocalLoopResult] into a unified presentation.
         *
         * Used for both local-only execution (crossDeviceEnabled=false) and post-fallback
         * local execution (cross-device failed, device falls back to local loop).
         */
        fun fromLocalResult(result: LocalLoopResult): UnifiedResultPresentation {
            return when (result.status) {
                LocalLoopResult.STATUS_SUCCESS -> UnifiedResultPresentation(
                    summary = "任务完成（${result.stepCount} 步）",
                    isSuccess = true,
                    outcome = LocalLoopResult.STATUS_SUCCESS
                )
                LocalLoopResult.STATUS_CANCELLED -> UnifiedResultPresentation(
                    summary = "任务已取消${result.error?.let { "：$it" } ?: ""}",
                    isSuccess = false,
                    outcome = LocalLoopResult.STATUS_CANCELLED
                )
                else -> UnifiedResultPresentation(
                    summary = "任务执行失败：${result.error ?: result.stopReason ?: "未知错误"}",
                    isSuccess = false,
                    outcome = result.status
                )
            }
        }

        /**
         * Normalizes a cross-device or delegated server reply into a unified presentation.
         *
         * Wraps [serverMessage] in a unified result without exposing the internal routing
         * path.  Server messages arriving via the WebSocket task-assign / goal-result flow
         * are treated as successful outcomes — the server only sends a message when the task
         * has reached the response stage.
         */
        fun fromServerMessage(serverMessage: String): UnifiedResultPresentation {
            return UnifiedResultPresentation(
                summary = serverMessage,
                isSuccess = true,
                outcome = "success"
            )
        }

        /**
         * Normalizes a [TakeoverFallbackEvent] into a unified presentation.
         *
         * Produces a user-facing fallback summary that does not expose the cross-device
         * implementation detail; the user sees a failure message with a style identical to
         * a local execution failure.
         */
        fun fromFallbackEvent(event: TakeoverFallbackEvent): UnifiedResultPresentation {
            val summary = when (event.cause) {
                TakeoverFallbackEvent.Cause.TIMEOUT ->
                    "任务执行超时"
                TakeoverFallbackEvent.Cause.CANCELLED ->
                    "任务已取消"
                TakeoverFallbackEvent.Cause.DISCONNECT ->
                    "连接中断，任务未完成"
                TakeoverFallbackEvent.Cause.FAILED ->
                    "任务执行失败"
            }
            return UnifiedResultPresentation(
                summary = summary,
                isSuccess = false,
                outcome = event.cause.wireValue
            )
        }
    }
}
