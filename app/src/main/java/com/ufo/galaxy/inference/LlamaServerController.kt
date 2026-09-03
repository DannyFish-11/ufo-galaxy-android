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
 *   binary([binaryPath],随 APK 发的 arm64 llama-server 可执行文件)
 *     + -m [modelPath](MAI-UI-2B Q4_K_M GGUF)
 *     + --mmproj [mmprojPath](视觉投影,多模态必需)
 *     → 127.0.0.1:[port] 的 OpenAI 兼容服务,VlmPlanner/VlmGroundingEngine 直连。
 *
 * ## 二进制供给
 * [binaryPath] 必须落在 `applicationInfo.nativeLibraryDir` 下,由
 * [NativeExecutable.llamaServerPath] 给出。**不能是 `filesDir` 之类的可写私有目录**:
 * targetSdk ≥ 29 的应用不允许对自己数据目录里的文件 execve,这是 SELinux 的强制访问
 * 控制,权限位改不动。该约束的完整说明见 [NativeExecutable]。
 *
 * 供给方式相应地从"每台设备 adb push 一次"变成"构建前把文件放进
 * `app/src/main/jniLibs/<abi>/libllama-server.so`",装机时随 APK 落地。本仓不含该
 * 二进制、也无法在 CI 里构建它,所以未供给依然是常态:此时 [ensureRunning] 返回
 * [StartOutcome.NotProvisioned],链路保持既有降级行为(DegradedService / 跨设备路径),
 * 不影响 App 其它功能。
 *
 * ## 完整性校验
 * 二进制改由 APK 携带之后,**APK 的 v2/v3 签名已经覆盖了它**,且安装后所在目录只读 ——
 * 装机时由系统校验,运行时无从篡改。所以 [expectedSha256] 不再是这条路的必需品。
 *
 * 它仍然保留,因为供给渠道未必只有一条(例如把二进制换成从可信位置侧载的形态)。
 * 语义不变:
 *  · 摘要不符 → [StartOutcome.IntegrityFailed],**拒绝执行**,不做任何"也许没事"的猜测;
 *  · 未钉死   → 照常启动,结构化日志记 `integrity=unpinned`。在 jniLibs 供给下这一行
 *              不再意味着"没人校验过",而是"这一层没再校验一遍" —— 保留它是为了不让
 *              日志读者误以为多了一道其实不存在的检查。
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
            return logged(
                StartOutcome.NotProvisioned(
                    "llama-server binary absent at $binaryPath — " +
                        "把 arm64 可执行文件放进 app/src/main/jniLibs/<abi>/" +
                        "${NativeExecutable.LLAMA_SERVER_SO} 后重新构建"
                )
            )
        }
        // 这里刻意不再 setExecutable(true):二进制来自只读的 nativeLibraryDir,安装时
        // 就已是可执行且改不动。此前那句是为 filesDir 供给写的,而在 filesDir 上它既
        // 改不了 SELinux 标签、也从来没能让 exec 成功 —— 留着只会让人以为权限位是
        // 可调的。canExecute() 为假在这里是真实故障,直接如实上报。
        if (!binary.canExecute()) {
            return logged(
                StartOutcome.NotProvisioned(
                    "llama-server binary not executable: $binaryPath — " +
                        "该路径若不在 applicationInfo.nativeLibraryDir 下,execve 会被 SELinux 拒绝"
                )
            )
        }
        // 完整性校验在 exec 之前 —— 这是最后一道能拦住的地方。
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
