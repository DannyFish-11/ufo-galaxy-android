package com.ufo.galaxy.inference

import android.util.Log
import com.ufo.galaxy.observability.GalaxyLogger
import java.io.File

/**
 * 本地 llama.cpp 推理服务进程的生命周期控制器 —— 补上"手机本地自己跑下来"的
 * 最后一块运行时缺口。
 *
 * 背景(读码实证):App 的规划/定位都调用 127.0.0.1:8080 的 llama.cpp 服务,但仓内
 * JNI 类是空壳(无 cpp/CMake/.so),此前该服务只能靠人手在 Termux 里起 —— 本地闭环
 * 因此从来无法"自启动"。本类让 App 自己拉起并守护 `llama-server` 进程:
 *
 *   binary(arm64 llama-server 可执行文件,置于 [binaryPath])
 *     + -m [modelPath](MAI-UI-2B Q4_K_M GGUF)
 *     + --mmproj [mmprojPath](视觉投影,多模态必需)
 *     → 127.0.0.1:[port] 的 OpenAI 兼容服务,VlmPlanner/VlmGroundingEngine 直连。
 *
 * ## 二进制供给(诚实边界)
 * 本仓不含、也无法在 CI 里构建 Android 原生二进制。`llama-server` 需一次性供给:
 * 从 llama.cpp 官方 release 取 arm64 静态构建(或 Termux `pkg install llama-cpp` 后
 * 拷贝),推到 [binaryPath](如 `adb push llama-server /data/data/<pkg>/files/bin/`)。
 * 二进制缺失时 [ensureRunning] 返回 [StartOutcome.NotProvisioned],链路保持现有
 * 降级行为(DegradedService / 跨设备路径),不影响 App 其它功能。
 *
 * ## 进程可测性
 * 进程创建经 [ProcessLauncher] 注入点,JVM 单测用 fake 验证参数与生命周期语义,
 * 不真正拉起进程。
 */
class LlamaServerController(
    private val binaryPath: String,
    private val modelPath: String,
    private val mmprojPath: String,
    private val port: Int = DEFAULT_PORT,
    private val contextSize: Int = DEFAULT_CONTEXT_SIZE,
    private val launcher: ProcessLauncher = DefaultProcessLauncher()
) {

    companion object {
        private const val TAG = "GALAXY:LLAMA:SERVER"
        const val DEFAULT_PORT = 8080

        /** 上下文窗口:2B 模型 + 截图 prefill + 元素清单,4096 足够单步调用。 */
        const val DEFAULT_CONTEXT_SIZE = 4096
    }

    /** 进程创建注入点。 */
    fun interface ProcessLauncher {
        /** 启动命令,返回进程句柄;失败抛异常。 */
        fun launch(command: List<String>): ManagedProcess
    }

    /** 最小进程句柄抽象(JVM 可 fake)。 */
    interface ManagedProcess {
        fun isAlive(): Boolean
        fun destroy()
    }

    /** [ensureRunning] 的结构化结果。 */
    sealed class StartOutcome {
        /** 进程已在运行或本次成功拉起。 */
        object Running : StartOutcome()

        /** 二进制未供给([binaryPath] 不存在/不可执行)—— 维持既有降级行为。 */
        data class NotProvisioned(val reason: String) : StartOutcome()

        /** 模型文件缺失(先走模型下载/供给流程)。 */
        data class ModelsMissing(val reason: String) : StartOutcome()

        /** 拉起失败(进程创建异常)。 */
        data class Failed(val reason: String) : StartOutcome()
    }

    @Volatile
    private var process: ManagedProcess? = null

    /** 当前控制器视角下服务进程是否存活。 */
    fun isRunning(): Boolean = process?.isAlive() == true

    /**
     * 幂等地确保 llama-server 在跑:已存活直接返回;二进制/模型缺失返回对应
     * 结构化结果(不抛异常);否则拉起进程。
     *
     * 就绪确认(/health 可达)由上层既有 warmup 链路完成
     * ([com.ufo.galaxy.planner.VlmPlanner.warmupWithResult] 的 HEALTH_CHECK 阶段),
     * 此处不重复轮询。
     */
    @Synchronized
    fun ensureRunning(): StartOutcome {
        if (isRunning()) return StartOutcome.Running

        val binary = File(binaryPath)
        if (!binary.isFile) {
            return logged(StartOutcome.NotProvisioned("llama-server binary absent at $binaryPath"))
        }
        if (!binary.canExecute() && !binary.setExecutable(true)) {
            return logged(StartOutcome.NotProvisioned("llama-server binary not executable: $binaryPath"))
        }
        if (!File(modelPath).isFile) {
            return logged(StartOutcome.ModelsMissing("LLM gguf absent at $modelPath"))
        }
        if (!File(mmprojPath).isFile) {
            // mmproj 缺失时坚决不起纯文本服务:那正是旧 MobileVLM"从未看见过屏幕"的
            // 静默故障形态,宁可结构化报缺让供给流程补文件。
            return logged(StartOutcome.ModelsMissing("mmproj gguf absent at $mmprojPath"))
        }

        return try {
            process = launcher.launch(buildCommand())
            logged(StartOutcome.Running)
        } catch (e: Exception) {
            logged(StartOutcome.Failed("launch failed: ${e.message}"))
        }
    }

    /** 停止服务进程(幂等)。 */
    @Synchronized
    fun stop() {
        process?.let {
            try {
                it.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "stop: destroy failed: ${e.message}")
            }
        }
        process = null
        GalaxyLogger.log(TAG, mapOf("event" to "llama_server_stopped"))
    }

    /** 组装启动命令(公开给测试断言参数正确性)。 */
    fun buildCommand(): List<String> = listOf(
        binaryPath,
        "-m", modelPath,
        "--mmproj", mmprojPath,
        "--host", "127.0.0.1",
        "--port", port.toString(),
        "-c", contextSize.toString(),
        "--no-webui"
    )

    private fun logged(outcome: StartOutcome): StartOutcome {
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "llama_server_ensure",
                "outcome" to outcome::class.simpleName.orEmpty(),
                "detail" to when (outcome) {
                    is StartOutcome.NotProvisioned -> outcome.reason
                    is StartOutcome.ModelsMissing -> outcome.reason
                    is StartOutcome.Failed -> outcome.reason
                    StartOutcome.Running -> ""
                }
            )
        )
        return outcome
    }

    private class DefaultProcessLauncher : ProcessLauncher {
        override fun launch(command: List<String>): ManagedProcess {
            val p = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            // 排空子进程输出防止管道缓冲区写满阻塞(llama-server 日志量大)。
            Thread {
                try {
                    p.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { Log.d(TAG, it.take(200)) }
                    }
                } catch (_: Exception) { /* 进程退出时流关闭,正常 */ }
            }.apply { isDaemon = true; name = "llama-server-log-drain" }.start()
            return object : ManagedProcess {
                override fun isAlive(): Boolean = p.isAlive
                override fun destroy() = p.destroy()
            }
        }
    }
}
