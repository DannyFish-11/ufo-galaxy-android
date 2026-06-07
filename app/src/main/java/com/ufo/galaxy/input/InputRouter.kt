package com.ufo.galaxy.input

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.ufo.galaxy.UFOGalaxyApplication
import com.ufo.galaxy.data.AppSettings
import com.ufo.galaxy.local.LocalLoopExecutor
import com.ufo.galaxy.local.LocalLoopOptions
import com.ufo.galaxy.local.LocalLoopResult
import com.ufo.galaxy.network.GatewayClient
import com.ufo.galaxy.observability.GalaxyLogger
import com.ufo.galaxy.protocol.AipMessage
import com.ufo.galaxy.protocol.MsgType
import com.ufo.galaxy.protocol.TaskSubmitContext
import com.ufo.galaxy.protocol.TaskSubmitPayload
import com.ufo.galaxy.runtime.AndroidNlInitiationContract
import com.ufo.galaxy.runtime.SourceRuntimePosture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Natural-language input captured from the user (text or voice transcript).
 */
data class NaturalLanguageInput(
    val text: String,
    val source: String = "voice" // "voice" or "text"
)

/**
 * Routing decision produced by [InputRouter.routeDecision].
 *
 * - [LocalLoop]: execute locally via [LocalLoopExecutor].
 * - [CrossDeviceSubmit]: send uplink to V2 center (default routing).
 * - [CrossDeviceControl]: send to a specific target device (e.g., "control PC").
 */
sealed class RouteDecision {
    /** Execute locally on this Android device. */
    data class LocalLoop(val input: NaturalLanguageInput) : RouteDecision()

    /** Submit to V2 center for default routing. */
    data class CrossDeviceSubmit(val input: NaturalLanguageInput) : RouteDecision()

    /** Control a specific target device (PR-BIDIRECTIONAL). */
    data class CrossDeviceControl(
        val targetDevice: String,
        val task: String
    ) : RouteDecision()
}

/**
 * **Unified task/input dispatch authority** for both [com.ufo.galaxy.ui.viewmodel.MainViewModel]
 * and [com.ufo.galaxy.service.EnhancedFloatingService].
 *
 * This class is the **single point where the local-vs-cross-device mode decision is made**.
 * All user input — text and voice — must pass through [route]; no component may bypass it
 * to call [LocalLoopExecutor.execute] or [GatewayClient.sendJson] directly for user-initiated
 * task submission.
 *
 * Routing rules (P2 strong-consistency):
 *  - [AppSettings.crossDeviceEnabled] = false →
 *    **always** local; [LocalLoopExecutor.execute] is launched in [coroutineScope];
 *    result delivered to [onLocalResult]. Task-submit uplink is strictly forbidden.
 *    This is the Android-local NL path (device-local execution semantics).
 *  - [AppSettings.crossDeviceEnabled] = true **and** WS connected →
 *    wraps [text] in a [TaskSubmitPayload] AIP v3 envelope and sends it uplink via
 *    [GatewayClient]. Result arrives later via the WS task_assign/goal_result flow.
 *    This is the Android handoff-to-V2 path where Android is source/carrier and V2
 *    remains semantic authority.
 *  - [AppSettings.crossDeviceEnabled] = true **and** WS NOT connected →
 *    [onError] is invoked with a human-readable reason; does NOT silently fall back to local.
 *
 * Both entry points must route all user input (text and voice) through **this single class**
 * to guarantee consistent behaviour and avoid duplicate remote-handoff logic.
 *
 * @param settings           Persistent settings; [AppSettings.crossDeviceEnabled] gates the WS path.
 * @param webSocketClient    Live gateway client; used to check connectivity and send messages.
 *                           [com.ufo.galaxy.network.GalaxyWebSocketClient] implements this interface;
 *                           tests inject a lightweight fake.
 * @param localLoopExecutor  Canonical local execution entrypoint; invoked when cross-device is OFF.
 *                           The production implementation is [com.ufo.galaxy.local.DefaultLocalLoopExecutor]
 *                           (wired in [com.ufo.galaxy.UFOGalaxyApplication]).
 * @param coroutineScope     Scope in which local [LocalLoopExecutor.execute] is launched
 *                           (e.g. viewModelScope).
 * @param onLocalResult      Called on the IO thread when a local task completes or fails.
 *                           Use to update UI or log results. Optional.
 * @param onError            Called with a human-readable reason when cross-device routing fails
 *                           (WS unavailable or send error). Callers must surface this in the UI.
 */
class InputRouter(
    private val settings: AppSettings,
    private val webSocketClient: GatewayClient,
    private val localLoopExecutor: LocalLoopExecutor,
    private val coroutineScope: CoroutineScope,
    private val onLocalResult: ((LocalLoopResult) -> Unit)? = null,
    private val onError: ((reason: String) -> Unit)? = null
) {
    private val gson = Gson()

    /**
     * Thread-safety / double-submit guard.
     *
     * [route] uses [AtomicBoolean.compareAndSet] so that a concurrent or re-entrant call
     * while a synchronous routing decision is in progress is detected and dropped immediately
     * rather than racing on [AppSettings.crossDeviceEnabled] or [GatewayClient.sendJson].
     *
     * The guard is held only for the duration of the synchronous routing logic; the async
     * [LocalLoopExecutor.execute] coroutine launched for LOCAL paths runs outside the guard so it
     * does not prevent the callers' own in-flight check (e.g. [MainViewModel] `isLoading`)
     * from working correctly.
     */
    private val _isRouting = AtomicBoolean(false)

    /**
     * Describes which routing path was taken by [route].
     */
    enum class RouteMode {
        /** Input was dispatched to the canonical [LocalLoopExecutor] (crossDeviceEnabled=false). */
        LOCAL,
        /** Input was sent uplink via WebSocket as an AIP v3 task_submit (crossDeviceEnabled=true, WS connected). */
        CROSS_DEVICE,
        /** Cross-device was requested but WS was unavailable; error surfaced via [onError]. */
        ERROR
    }

    /**
     * Routes [text] to the correct execution path.
     *
     * - `crossDeviceEnabled=false` → local only; launches [LocalLoopExecutor.execute] in [coroutineScope];
     *   returns [RouteMode.LOCAL].
     * - `crossDeviceEnabled=true && connected` → AIP v3 task_submit WS uplink; returns [RouteMode.CROSS_DEVICE].
     * - `crossDeviceEnabled=true && !connected` → [onError] invoked; returns [RouteMode.ERROR].
     *   Does NOT fall back to local execution.
     *
     * Blank / whitespace-only input is ignored and [RouteMode.LOCAL] is returned without
     * invoking any callbacks.
     *
     * When `crossDeviceEnabled=true` and WS is connected, [AndroidNlInitiationContract] metadata
     * is automatically built and embedded into the outbound [TaskSubmitPayload] so that the V2
     * central authority can admit this Android-originated NL initiation to the main chain.
     *
     * @param text                 Natural-language input from the user (text or voice transcript).
     * @param deviceId             Stable device identifier. Defaults to the Android build identity.
     * @param sourceRuntimePosture Canonical source-device participation posture for this task,
     *                             aligned with the PR #533 / PR #106 posture contract.
     *                             Valid values: [SourceRuntimePosture.CONTROL_ONLY] or
     *                             [SourceRuntimePosture.JOIN_RUNTIME]. Unknown or blank values are
     *                             normalised to [SourceRuntimePosture.DEFAULT] (`"control_only"`).
     *                             Defaults to [SourceRuntimePosture.DEFAULT] — callers that do not
     *                             specify posture are treated as control-only for backwards safety.
     * @return The [RouteMode] describing which path was taken.
     */
    fun route(
        text: String,
        deviceId: String = UUID.nameUUIDFromBytes(
            (Build.MANUFACTURER + Build.MODEL + Build.ID).toByteArray()
        ).toString(),
        sourceRuntimePosture: String = SourceRuntimePosture.DEFAULT
    ): RouteMode {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return RouteMode.LOCAL

        // Thread-safe double-submit guard: if a concurrent synchronous routing call is already
        // in progress on another thread, drop the duplicate immediately instead of racing.
        if (!_isRouting.compareAndSet(false, true)) {
            Log.d(TAG, "[ROUTE] Concurrent route() call dropped (double-submit guard)")
            return RouteMode.LOCAL
        }

        return try {
            routeInternal(trimmed, deviceId, SourceRuntimePosture.fromValue(sourceRuntimePosture))
        } finally {
            _isRouting.set(false)
        }
    }

    /**
     * PR-BIDIRECTIONAL: Advanced routing that detects cross-device control commands
     * (e.g., "control PC", "在电脑上...") and returns a [RouteDecision] that includes
     * the target device identifier.
     *
     * This method does NOT execute or send anything — the caller decides how to act
     * on the returned [RouteDecision] (e.g., call [GalaxyWebSocketClient.sendTaskWithTarget]
     * for [RouteDecision.CrossDeviceControl]).
     *
     * @param input Natural-language input wrapped in [NaturalLanguageInput].
     * @return A [RouteDecision] describing the intended execution path.
     */
    fun routeDecision(input: NaturalLanguageInput): RouteDecision {
        val isCrossDevice = settings.crossDeviceEnabled
        val isControlCommand = detectControlCommand(input.text)

        return when {
            // 用户说"控制电脑"或"在电脑上..."
            isControlCommand && isCrossDevice -> {
                RouteDecision.CrossDeviceControl(
                    targetDevice = extractTargetDevice(input.text),
                    task = input.text
                )
            }
            // 本地执行
            !isCrossDevice -> RouteDecision.LocalLoop(input)
            // 跨设备默认（发给V2处理）
            isCrossDevice -> RouteDecision.CrossDeviceSubmit(input)
            else -> RouteDecision.LocalLoop(input)
        }
    }

    /**
     * Detects whether [text] contains a cross-device control command pattern.
     *
     * Matches Chinese and English patterns such as "控制电脑", "在电脑上",
     * "control pc", "desktop", etc.
     */
    private fun detectControlCommand(text: String): Boolean {
        val controlPatterns = listOf(
            "控制电脑", "在电脑上", "电脑帮我", "PC",
            "control pc", "on computer", "desktop"
        )
        return controlPatterns.any { text.contains(it, ignoreCase = true) }
    }

    /**
     * Extracts the target device identifier from [text] based on keyword matching.
     *
     * @return A device identifier such as "v2_desktop", "tablet_1", or "v2_center" (default).
     */
    private fun extractTargetDevice(text: String): String {
        return when {
            text.contains("电脑") || text.contains("PC", ignoreCase = true) || text.contains("desktop", ignoreCase = true) -> "v2_desktop"
            text.contains("平板") || text.contains("tablet", ignoreCase = true) -> "tablet_1"
            else -> "v2_center"  // 默认发给V2中心
        }
    }

    private fun routeInternal(trimmed: String, deviceId: String, posture: String): RouteMode {
        val taskId = UUID.randomUUID().toString()
        val crossDevice = settings.crossDeviceEnabled
        val wsConnected = webSocketClient.isConnected()

        return when {
            !crossDevice -> {
                Log.i(TAG, "[ROUTE] route_mode=local task_id=$taskId device_id=$deviceId posture=$posture")
                GalaxyLogger.log(
                    TAG,
                    mapOf("event" to "route_local", "task_id" to taskId, "posture" to posture) +
                        AndroidNlSemanticContract.localRouteMetadata(posture)
                )
                launchLocal(trimmed, posture)
                RouteMode.LOCAL
            }
            wsConnected -> {
                sendViaWebSocket(trimmed, deviceId, taskId, posture, crossDevice)
            }
            else -> {
                // crossDeviceEnabled=true but WS not connected → explicit error, no silent fallback.
                val reason = "跨设备模式已开启，但 WebSocket 未连接，无法发送任务。请检查网络或关闭跨设备模式。"
                Log.e(TAG, "[ROUTE] route_mode=error task_id=$taskId device_id=$deviceId reason=$reason")
                GalaxyLogger.log(TAG, mapOf("event" to "route_error", "task_id" to taskId, "reason" to reason))
                onError?.invoke(reason)
                RouteMode.ERROR
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Launches [LocalLoopExecutor.execute] for [text] in [coroutineScope] on [Dispatchers.IO].
     * On completion, [onLocalResult] is invoked with the [LocalLoopResult].
     */
    private fun launchLocal(text: String, posture: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val result = localLoopExecutor.execute(
                    LocalLoopOptions(instruction = text, sourceRuntimePosture = posture)
                )
                GalaxyLogger.log(
                    TAG, mapOf(
                        "event" to "local_done",
                        "session_id" to result.sessionId,
                        "status" to result.status,
                        "steps" to result.stepCount
                    )
                )
                onLocalResult?.invoke(result)
            } catch (e: Exception) {
                Log.e(TAG, "[ROUTE] local execution error: ${e.message}", e)
                GalaxyLogger.log(TAG, mapOf("event" to "local_error", "error" to (e.message ?: "unknown")))
            }
        }
    }

    /**
     * Wraps [text] in an AIP v3 [TaskSubmitPayload] envelope and sends it via [webSocketClient].
     *
     * Two distinct identifiers are used (matching the AIP v3 schema):
     *  - [taskId]: the message-level correlation identifier included in [AipMessage.correlation_id]
     *    so that the reply (`task_assign`) can be matched to this submission.  [taskId] is also
     *    copied into [TaskSubmitPayload.task_id] so the gateway can see it in the payload.
     *  - `conversationSessionId`: the conversation/control-session identifier in
     *    [TaskSubmitPayload.session_id] that the Gateway uses to group steps within a single
     *    user request. It is distinct from runtime attachment session identity
     *    ([com.ufo.galaxy.runtime.AttachedRuntimeSession.sessionId]).
     *
     * [posture] is propagated into both [TaskSubmitPayload.source_runtime_posture] and the
     * [AipMessage] envelope's `source_runtime_posture` field so the gateway receives it at both
     * the payload level and the envelope level — matching the dual-field convention established
     * by the main-repo PR #533 contract.
     *
     * PR-993: [AndroidNlInitiationContract] metadata is built and embedded into:
     *  - [TaskSubmitPayload.nl_initiation_origin], [TaskSubmitPayload.nl_initiation_mode],
     *    [TaskSubmitPayload.nl_initiation_authority_scope], [TaskSubmitPayload.nl_initiation_lineage]
     *  - [TaskSubmitContext.extra] (full wire map including schema_version, lineage, correlation_id)
     *
     * This ensures V2's intake layer can identify and admit this Android-originated NL initiation
     * to the main intent/governance/execution/truth/reconciliation/closure chain.
     *
     * The payload is validated via [TaskSubmitPayload.validate] before sending; a validation
     * failure is treated as an internal error and surfaced via [onError].
     *
     * @param crossDeviceEnabled The already-snapshotted value of [AppSettings.crossDeviceEnabled]
     *                           from [routeInternal]. Passed explicitly to prevent a race condition
     *                           where the setting could be toggled between the gate check in
     *                           [routeInternal] and the [AndroidNlInitiationContract.build] call here,
     *                           which would cause NL initiation metadata to be silently absent from
     *                           the outbound payload (metadata drift).
     */
    private fun sendViaWebSocket(text: String, deviceId: String, taskId: String, posture: String, crossDeviceEnabled: Boolean): RouteMode {
        // PR-993: Build Android NL initiation metadata. crossDeviceEnabled is the value already
        // captured in routeInternal (not re-read from settings) to prevent metadata drift.
        val nlInitiation = AndroidNlInitiationContract.build(
            crossDeviceEnabled = crossDeviceEnabled,
            deviceId = deviceId,
            runtimeSessionId = UFOGalaxyApplication.runtimeSessionId,
            correlationId = taskId
        )

        val canonicalSubjectInput = AndroidNlSemanticContract.buildCanonicalSubjectInput(
            text = text,
            deviceId = deviceId,
            posture = posture,
            crossDeviceEnabled = crossDeviceEnabled,
            websocketConnected = true,
            runtimeSessionId = UFOGalaxyApplication.runtimeSessionId
        )
        val packagingStrategy = AndroidNlSemanticContract.deriveRequestPackagingStrategy(posture)
        val conversationSessionId = AndroidNlSemanticContract.deriveConversationSessionId(taskId, packagingStrategy)
        val contextExtra = AndroidNlSemanticContract.nlInitiationMetadata(posture, nlInitiation) +
            AndroidNlSemanticContract.canonicalSubjectInputMetadata(
                subjectInputJson = gson.toJson(canonicalSubjectInput),
                strategy = packagingStrategy,
                modalities = canonicalSubjectInput.mixedContext.modalities
            )

        val payload = TaskSubmitPayload(
            task_text = text,
            device_id = deviceId,
            session_id = conversationSessionId,
            task_id = taskId,
            context = TaskSubmitContext(
                locale = canonicalSubjectInput.deviceContext.localeTag,
                extra = contextExtra
            ),
            source_runtime_posture = posture,
            // PR-993: embed NL initiation fields directly in the payload for V2 intake
            nl_initiation_origin = nlInitiation?.origin,
            nl_initiation_mode = nlInitiation?.initiationMode?.wireValue,
            nl_initiation_authority_scope = nlInitiation?.authorityScope?.wireValue,
            nl_initiation_lineage = nlInitiation?.lineage
        )
        if (!payload.validate()) {
            val fieldError = payload.validationError() ?: "unknown field"
            val reason = "TaskSubmitPayload 验证失败：$fieldError。"
            Log.e(TAG, "[ROUTE] route_mode=error task_id=$taskId reason=payload_validation_failed ($fieldError)")
            GalaxyLogger.log(TAG, mapOf("event" to "route_error", "task_id" to taskId, "reason" to "payload_validation_failed", "field" to fieldError))
            onError?.invoke(reason)
            return RouteMode.ERROR
        }
        val envelope = AipMessage(
            type = MsgType.TASK_SUBMIT,
            payload = payload,
            correlation_id = taskId,
            device_id = deviceId,
            trace_id = taskId,      // use task_id as the initial trace_id for this submission
            route_mode = "cross_device",
            runtime_session_id = UFOGalaxyApplication.runtimeSessionId,
            idempotency_key = "${AndroidNlSemanticContract.idempotencyKeyPrefix(packagingStrategy)}-${UUID.randomUUID()}",
            source_runtime_posture = posture
        )
        val json = gson.toJson(envelope)
        val sent = AipTransportManager.getInstance().sendJson(json)
        return if (sent) {
            Log.i(TAG, "[ROUTE] route_mode=cross_device task_id=$taskId device_id=$deviceId posture=$posture text=${text.take(60)} nl_initiation=${nlInitiation != null}")
            GalaxyLogger.log(
                TAG, mapOf(
                    "event" to "route_cross_device",
                    "task_id" to taskId,
                    "session_id" to conversationSessionId,
                    "posture" to posture,
                    "nl_initiation_mode" to (nlInitiation?.initiationMode?.wireValue ?: "none"),
                    "nl_initiation_authority_scope" to (nlInitiation?.authorityScope?.wireValue ?: "none")
                ) + AndroidNlSemanticContract.nlInitiationMetadata(posture, nlInitiation)
            )
            RouteMode.CROSS_DEVICE
        } else {
            val reason = "跨设备发送失败（连接已断开），请重试或关闭跨设备模式。"
            Log.e(TAG, "[ROUTE] route_mode=error task_id=$taskId reason=send_failed")
            GalaxyLogger.log(TAG, mapOf("event" to "route_error", "task_id" to taskId, "reason" to "send_failed"))
            onError?.invoke(reason)
            RouteMode.ERROR
        }
    }

    companion object {
        private const val TAG = "InputRouter"
        const val ENTRYPOINT_ROLE = "sub_entry"
    }
}
