package com.ufo.galaxy.bridge

import android.util.Log
import com.ufo.galaxy.history.SessionHistorySummary
import com.ufo.galaxy.local.LocalLoopExecutor
import com.ufo.galaxy.local.LocalLoopOptions
import com.ufo.galaxy.loop.LoopStatus
import com.ufo.galaxy.network.GalaxyWebSocketClient
import com.ufo.galaxy.runtime.SourceRuntimePosture
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 应用层的桥接 Agent:把 [BridgeAgentRuntime] 接到连接、本地循环、历史与界面上。
 *
 * - 收:WebSocket 上的 `a2a` 帧交给运行时;每次重新登记后补发还没确认的。
 * - 交接:最近对话(界面登记的 [dialogProvider])、正在跑的本地循环进度、最近几次本地执行。屏幕不传
 *   (所有者定的:截图只在中心要动手机时才传)。
 * - 委派:中心交来的整段目标用本地循环做([LocalLoopExecutor]),步数变化作为进度报回去。
 * - 回答与交还:交给界面登记的监听者([addListener])。
 *
 * 人说的话要不要走中心由 [com.ufo.galaxy.input.InputRouter] 问 [say]。
 */
object BridgeAgent {
    private const val TAG = "BridgeAgent"
    private const val MAX_DIALOG = 20
    private const val MAX_TRAJECTORY = 5

    /** 界面上的事:回答到了、上下文交还了。 */
    interface Listener {
        fun onReply(text: String, success: Boolean)
        fun onReturned(summary: String, openItems: List<String>) = Unit
    }

    @Volatile
    private var runtime: BridgeAgentRuntime? = null

    private val listeners = CopyOnWriteArrayList<Listener>()

    /** 界面登记:最近的对话,`role` 为 `user` / `assistant`。 */
    @Volatile
    var dialogProvider: (() -> List<Pair<String, String>>)? = null

    fun addListener(l: Listener) { listeners.addIfAbsent(l) }

    fun removeListener(l: Listener) { listeners.remove(l) }

    /** 桥接 Agent 装上了没有。 */
    val installed: Boolean get() = runtime?.installed == true

    /** 人说的话交给中心智能体;没装上返回 false。 */
    fun say(text: String): Boolean = runtime?.say(text) == true

    @Volatile
    private var returned = CompletableDeferred<Unit>()

    /** 关跨设备之前请中心交还上下文;没装上返回 false。 */
    fun requestReturn(): Boolean {
        returned = CompletableDeferred()
        return runtime?.requestReturn() == true
    }

    /** 等中心把上下文交还回来,最多等 [timeoutMs];等到了返回 true。 */
    suspend fun awaitReturned(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { returned.await() } != null

    /**
     * 应用启动时接一次(WebSocket 客户端建好之后)。
     */
    fun install(
        client: GalaxyWebSocketClient,
        scope: CoroutineScope,
        loopExecutor: () -> LocalLoopExecutor?,
        loopStatus: () -> StateFlow<LoopStatus>?,
        history: () -> List<SessionHistorySummary>,
    ) {
        val host = object : BridgeAgentRuntime.Host {
            override fun handoffContext(): JsonObject = buildJsonObject {
                val dialog = runCatching { dialogProvider?.invoke() }.getOrNull().orEmpty().takeLast(MAX_DIALOG)
                put("dialog", buildJsonArray {
                    dialog.forEach { (role, text) -> add(buildJsonObject { put("role", role); put("text", text) }) }
                })
                val running = loopStatus()?.value as? LoopStatus.Running
                if (running != null) {
                    put("loop_progress", buildJsonObject {
                        put("step", running.stepIndex)
                        put("total", running.totalSteps)
                        put("action", running.currentAction)
                    })
                }
                put("trajectory", buildJsonArray {
                    runCatching { history() }.getOrNull().orEmpty().take(MAX_TRAJECTORY).forEach { h ->
                        add(buildJsonObject {
                            put("action", h.originalGoal)
                            put("status", h.status)
                            put("steps", h.stepCount)
                        })
                    }
                })
            }

            override fun onReply(text: String, success: Boolean) {
                if (listeners.isEmpty()) Log.i(TAG, "reply arrived with no screen listening: ${text.take(60)}")
                listeners.forEach { it.onReply(text, success) }
            }

            override fun runGoal(
                taskId: String,
                goal: String,
                constraints: List<String>,
                progress: (JsonObject) -> Unit,
                done: (JsonObject) -> Unit,
            ) {
                val executor = loopExecutor()
                if (executor == null) {
                    done(buildJsonObject { put("success", false); put("error", "local loop not ready") })
                    return
                }
                val instruction = if (constraints.isEmpty()) goal else "$goal(要求:${constraints.joinToString(";")})"
                scope.launch(Dispatchers.IO) {
                    val watch = launch {
                        var lastStep = -1
                        loopStatus()?.collect { s ->
                            if (s is LoopStatus.Running && s.stepIndex != lastStep) {
                                lastStep = s.stepIndex
                                progress(buildJsonObject {
                                    put("step", s.stepIndex)
                                    put("total", s.totalSteps)
                                    put("action", s.currentAction)
                                })
                            }
                        }
                    }
                    val result = try {
                        val r = executor.execute(LocalLoopOptions(instruction = instruction, sourceRuntimePosture = SourceRuntimePosture.JOIN_RUNTIME))
                        buildJsonObject {
                            put("success", r.isSuccess)
                            put("status", r.status)
                            put("steps", r.stepCount)
                            put("summary", r.stopReason ?: r.status)
                            r.error?.let { put("error", it) }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "delegated goal failed: ${e.message}")
                        buildJsonObject { put("success", false); put("error", e.message ?: "local loop failed") }
                    } finally {
                        watch.cancel()
                    }
                    done(result)
                }
            }

            override fun onReturned(summary: String, openItems: List<String>) {
                Log.i(TAG, "context returned by the center (${openItems.size} open item(s))")
                listeners.forEach { it.onReturned(summary, openItems) }
                returned.complete(Unit)
            }
        }
        val rt = BridgeAgentRuntime({ client.currentDeviceId() }, { client.sendJson(it) }, host)
        runtime = rt
        client.addListener(object : GalaxyWebSocketClient.Listener {
            override fun onConnected() = Unit
            override fun onDisconnected() = Unit
            override fun onMessage(message: String) = Unit
            override fun onError(error: String) = Unit
            override fun onA2AFrame(frameJson: String) = rt.onFrame(frameJson)

            // 登记帧发出之后补发:中心按顺序处理同一条连接上的帧,登记先于补发的消息生效
            override fun onDeviceRegisterSent(deviceId: String, traceId: String?) {
                val n = rt.resendUnacked()
                if (n > 0) Log.i(TAG, "resent $n unacknowledged A2A message(s)")
            }
        })
    }
}
