package com.ufo.galaxy.inference

import android.util.Log
import com.ufo.galaxy.BuildConfig
import com.ufo.galaxy.observability.GalaxyLogger
import java.io.File
import java.security.MessageDigest

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
 * ## 完整性校验
 * 模型权重的 SHA-256 早已构建期钉死([com.ufo.galaxy.model.ModelAssetManager]),而这个
 * **由本 App 亲自 exec、跑在 App 自己 UID 下的可执行文件**此前没有任何校验就直接启动 ——
 * 数据比可执行文件管得还严,方向反了。
 *
 * 现在 [expectedSha256](默认取 `BuildConfig.MODEL_LLAMA_SERVER_SHA256`)非空时,启动前
 * 逐字节校验:
 *  · 摘要不符 → [StartOutcome.IntegrityFailed],**拒绝执行**,不做任何"也许没事"的猜测;
 *  · 未钉死   → 照常启动,但结构化日志明确记 `integrity=unpinned` —— 承认没校验,
 *              而不是假装校验过。这与权重那边"留空 = 显式承认走 TOFU"的口径一致。
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
    private val launcher: ProcessLauncher = DefaultProcessLauncher(),
    /**
     * 期望的 `llama-server` SHA-256(64 位十六进制,大小写不敏感)。
     * null/空/格式非法 = 未钉死,启动前不校验(会在日志里明说)。
     */
    private val expectedSha256: String? = PINNED_SHA256
) {

    companion object {
        private const val TAG = "GALAXY:LLAMA:SERVER"
        const val DEFAULT_PORT = 8080

        /**
         * 上下文窗口:2B 模型 + 截图 prefill + 元素清单。
         *
         * 规划步送图的长边由这个数反推(见
         * [com.ufo.galaxy.inference.VisionContextBudget] 与
         * [com.ufo.galaxy.agent.scaleForPlanning])—— 拉起服务用的窗口与算预算用的窗口
         * 必须是同一个常量,否则预算是对着一个想象中的窗口算的。
         */
        const val DEFAULT_CONTEXT_SIZE = 4096

        private val HEX64 = Regex("[0-9a-fA-F]{64}")

        /**
         * 构建期注入的 `llama-server` 摘要(`galaxy.llamaServer.sha256`)。
         * 空/格式非法一律按"未钉死"处理 —— 编造一个摘要比留空更危险。
         */
        val PINNED_SHA256: String? = BuildConfig.MODEL_LLAMA_SERVER_SHA256
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { raw ->
                if (HEX64.matches(raw)) {
                    raw.lowercase()
                } else {
                    Log.e(TAG, "构建期注入的 llama-server SHA-256 格式非法(需 64 位十六进制),按未钉死处理")
                    null
                }
            }

        /** 计算 [file] 的 SHA-256(小写十六进制);读取失败返回 null。 */
        fun sha256Of(file: File): String? = try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "sha256Of failed for ${file.path}: ${e.message}")
            null
        }
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

        /**
         * 二进制摘要与钉死值不符 —— **拒绝执行**。
         *
         * 这不是降级,是拒绝:一个不是我们钉死的那份的可执行文件,要跑在 App 自己的
         * UID 下。宁可本地推理不可用(推理回落到 V2 网关),也不 exec 来路不明的东西。
         */
        data class IntegrityFailed(val reason: String) : StartOutcome()
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
        // 完整性校验在标记可执行之后、exec 之前 —— 这是最后一道能拦住的地方。
        val integrity = verifyIntegrity(binary)
        if (integrity != null) return logged(integrity)
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

    /**
     * 启动前完整性校验。
     *
     * @return 非 null 时表示**不可启动**,该值即为要上报的结果;null 表示放行。
     */
    private fun verifyIntegrity(binary: File): StartOutcome? {
        val expected = expectedSha256?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (expected == null) {
            // 未钉死:照常启动,但把"没校验"这件事记下来,不假装校验过。
            Log.w(TAG, "llama-server 摘要未钉死(galaxy.llamaServer.sha256 未设置)—— 本次不校验即执行")
            return null
        }
        val actual = sha256Of(binary)
            ?: return StartOutcome.IntegrityFailed("cannot read llama-server binary for hashing: $binaryPath")
        if (actual != expected) {
            // 只记前 12 位:摘要本身不是秘密,但完整摘要写满日志既没用又碍事。
            return StartOutcome.IntegrityFailed(
                "llama-server sha256 mismatch: expected ${expected.take(12)}…, got ${actual.take(12)}…"
            )
        }
        return null
    }

    private fun logged(outcome: StartOutcome): StartOutcome {
        GalaxyLogger.log(
            TAG, mapOf(
                "event" to "llama_server_ensure",
                "outcome" to outcome::class.simpleName.orEmpty(),
                // 每次启动都如实报一次校验口径:pinned = 校验过,unpinned = 没校验。
                "integrity" to if (expectedSha256.isNullOrBlank()) "unpinned" else "pinned",
                "detail" to when (outcome) {
                    is StartOutcome.NotProvisioned -> outcome.reason
                    is StartOutcome.ModelsMissing -> outcome.reason
                    is StartOutcome.Failed -> outcome.reason
                    is StartOutcome.IntegrityFailed -> outcome.reason
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
