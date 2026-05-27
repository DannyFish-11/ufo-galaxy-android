package com.ufo.galaxy.ui.viewmodel

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
 * @property uiConsumptionClass Declares this payload as UI-visible consumption only.
 * @property authorityBoundaryClass Declares this payload is projection-only, never authority.
 */
data class UnifiedResultPresentation(
    val summary: String,
    val isSuccess: Boolean,
    val outcome: String,
    val uiConsumptionClass: UiConsumptionClass = UiConsumptionClass.UI_VISIBLE_SUMMARY_DIAGNOSTICS,
    val authorityBoundaryClass: AuthorityBoundaryClass =
        AuthorityBoundaryClass.PROJECTION_ONLY_NOT_AUTHORITY,
    val finalSurfaceBoundaryClass: FinalSurfaceBoundaryClass =
        FinalSurfaceBoundaryClass.PRODUCT_FACING_CONSUMPTION_ONLY
) {
    enum class UiConsumptionClass(val wireValue: String) {
        UI_VISIBLE_SUMMARY_DIAGNOSTICS("ui_visible_summary_diagnostics_consumption")
    }

    enum class AuthorityBoundaryClass(val wireValue: String) {
        PROJECTION_ONLY_NOT_AUTHORITY("projection_only_not_authority")
    }

    enum class FinalSurfaceBoundaryClass(val wireValue: String) {
        PRODUCT_FACING_CONSUMPTION_ONLY("product_facing_consumption_only")
    }

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
                // PR-29: Explicit handling for the DISABLED outcome so consumers see a clear
                // "not executed by policy" message rather than the generic failure string.
                // STATUS_DISABLED is produced when the posture gate or runtime state prevents
                // execution (distinct from STATUS_FAILED which means the device tried and failed).
                LocalLoopResult.STATUS_DISABLED -> UnifiedResultPresentation(
                    summary = "任务未执行（执行路径当前不可用）",
                    isSuccess = false,
                    outcome = LocalLoopResult.STATUS_DISABLED
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
            val root = runCatching {
                JsonParser.parseString(serverMessage).asJsonObject
            }.getOrNull()
            if (root != null) {
                val (firstLevelPayload, secondLevelPayload) = payloadChain(root)
                val lifecycle = extractUnifiedLifecycleSurface(
                    root = root,
                    firstLevelPayload = firstLevelPayload,
                    secondLevelPayload = secondLevelPayload
                )
                if (lifecycle != null) {
                    val stage = stringField(lifecycle, "stage") ?: "unknown"
                    val blocker = lifecycle.getAsJsonObject("blocker")
                    val confirmation = lifecycle.getAsJsonObject("confirmation")
                    val execution = lifecycle.getAsJsonObject("execution")
                    val blockerReason = stringField(blocker, "reason")
                    val isBlocked = booleanField(blocker, "is_blocked")
                    val confirmationNeeded = booleanField(confirmation, "confirmation_needed")
                    val progressDetail = stringField(execution, "progress_detail")
                    val summaryFromPayload = firstNonBlank(
                        stringField(root, "result_summary"),
                        stringField(firstLevelPayload, "result_summary"),
                        stringField(root, "result"),
                        stringField(firstLevelPayload, "result"),
                        stringField(root, "details"),
                        stringField(firstLevelPayload, "details"),
                        stringField(secondLevelPayload, "details")
                    )
                    val baseSummary = when (stage) {
                        "accepted" -> "任务已被接受，开始执行"
                        "executing" -> if (!progressDetail.isNullOrBlank()) {
                            "任务执行中：$progressDetail"
                        } else {
                            "任务执行中"
                        }
                        "result_emitted" -> summaryFromPayload ?: "任务结果已返回"
                        "failed" -> summaryFromPayload ?: "任务执行失败"
                        "cancelled" -> "任务已取消"
                        "reconciliation_snapshot" -> "任务状态已同步"
                        "participant_state" -> "运行时状态已更新"
                        else -> summaryFromPayload ?: "任务状态已更新"
                    }
                    val suffixes = buildList {
                        if (isBlocked && !blockerReason.isNullOrBlank()) {
                            add("阻塞：$blockerReason")
                        }
                        if (confirmationNeeded) {
                            add("等待确认")
                        }
                    }
                    val summary = if (suffixes.isEmpty()) {
                        baseSummary
                    } else {
                        "$baseSummary（${suffixes.joinToString("，")}）"
                    }
                    val isSuccess = stage == "result_emitted" && !isBlocked && !confirmationNeeded
                    return UnifiedResultPresentation(
                        summary = summary,
                        isSuccess = isSuccess,
                        outcome = stage
                    )
                }
                val plainSummary = firstNonBlank(
                    stringField(firstLevelPayload, "content"),
                    stringField(root, "content"),
                    stringField(firstLevelPayload, "result_summary"),
                    stringField(root, "result_summary"),
                    stringField(firstLevelPayload, "result"),
                    stringField(root, "result"),
                    stringField(firstLevelPayload, "details"),
                    stringField(root, "details")
                )
                if (!plainSummary.isNullOrBlank()) {
                    val status = firstNonBlank(
                        stringField(firstLevelPayload, "status"),
                        stringField(root, "status")
                    ) ?: "success"
                    return UnifiedResultPresentation(
                        summary = plainSummary,
                        isSuccess = status !in setOf("error", "failed", "cancelled"),
                        outcome = status
                    )
                }
            }
            return UnifiedResultPresentation(
                summary = serverMessage,
                isSuccess = true,
                outcome = "success"
            )
        }
        private fun extractUnifiedLifecycleSurface(
            root: JsonObject,
            firstLevelPayload: JsonObject?,
            secondLevelPayload: JsonObject?
        ): JsonObject? {
            return root.getAsJsonObject("unified_action_lifecycle_surface")
                ?: firstLevelPayload?.getAsJsonObject("unified_action_lifecycle_surface")
                ?: secondLevelPayload?.getAsJsonObject("unified_action_lifecycle_surface")
        }

        private fun stringField(obj: JsonObject?, key: String): String? {
            val value = obj?.get(key) ?: return null
            if (!value.isJsonPrimitive) {
                return null
            }
            val primitive = value.asJsonPrimitive
            return when {
                primitive.isString -> primitive.asString
                primitive.isBoolean -> primitive.asBoolean.toString()
                primitive.isNumber -> primitive.asNumber.toString()
                else -> null
            }
        }

        private fun booleanField(obj: JsonObject?, key: String): Boolean {
            val element: JsonElement = obj?.get(key) ?: return false
            return when {
                element.isJsonPrimitive && element.asJsonPrimitive.isBoolean -> element.asBoolean
                element.isJsonPrimitive && element.asJsonPrimitive.isString ->
                    element.asString.equals("true", ignoreCase = true)
                element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asInt != 0
                else -> false
            }
        }

        private fun firstNonBlank(vararg values: String?): String? =
            values.firstOrNull { !it.isNullOrBlank() }

        private fun payloadChain(root: JsonObject): Pair<JsonObject?, JsonObject?> {
            val firstLevelPayload = root.getAsJsonObject("payload")
            val secondLevelPayload = firstLevelPayload?.getAsJsonObject("payload")
            return firstLevelPayload to secondLevelPayload
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
