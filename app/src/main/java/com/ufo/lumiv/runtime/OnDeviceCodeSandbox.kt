package com.ufo.lumiv.runtime

import android.util.Log
import com.ufo.lumiv.protocol.CodeExecutePayload
import com.ufo.lumiv.protocol.CodeResultPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.script.ScriptEngineManager
import javax.script.ScriptException

/**
 * On-device code execution sandbox for [CodeExecutePayload].
 *
 * Executes JavaScript code inside a sandboxed [javax.script.ScriptEngine] with
 * strict timeout and output-size controls.
 *
 * PR-SANDBOX: Dedicated local executor for CODE_EXECUTE messages.
 *
 * @param deviceId Stable device identifier echoed in [CodeResultPayload.device_id].
 */
class OnDeviceCodeSandbox(private val deviceId: String) {

    companion object {
        private const val TAG = "OnDeviceCodeSandbox"
        private const val DEFAULT_TIMEOUT_MS = 5000L
        private const val DEFAULT_MAX_OUTPUT_CHARS = 10000

        const val STATUS_SUCCESS = "success"
        const val STATUS_ERROR = "error"
        const val STATUS_TIMEOUT = "timeout"
        const val STATUS_BLOCKED = "blocked"

        const val EXIT_CODE_SUCCESS = 0
        const val EXIT_CODE_TIMEOUT = 124
        const val EXIT_CODE_SCRIPT_ERROR = 1
        const val EXIT_CODE_ENGINE_MISSING = 127
        const val EXIT_CODE_SANDBOX_ERROR = -1
    }

    /**
     * Executes the code described by [payload] and returns a [CodeResultPayload].
     *
     * Runs the script engine on an I/O dispatcher and enforces [CodeExecutePayload.timeout_ms]
     * via coroutine timeout.  Any exception (script error, timeout, engine missing) is surfaced
     * as a [CodeResultPayload] with a non-success status rather than being thrown.
     */
    suspend fun execute(payload: CodeExecutePayload): CodeResultPayload {
        val startMs = System.currentTimeMillis()

        return try {
            withContext(Dispatchers.IO) {
                withTimeout(payload.timeout_ms) {
                    runEngine(payload, startMs)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "[execute] task_id=${payload.task_id} timed out after ${payload.timeout_ms}ms")
            CodeResultPayload(
                task_id = payload.task_id,
                correlation_id = payload.correlation_id,
                status = STATUS_TIMEOUT,
                output = null,
                error = "Execution timed out after ${payload.timeout_ms}ms",
                exit_code = EXIT_CODE_TIMEOUT,
                execution_ms = System.currentTimeMillis() - startMs,
                device_id = deviceId
            )
        } catch (e: Exception) {
            Log.e(TAG, "[execute] task_id=${payload.task_id} unexpected error: ${e.message}", e)
            CodeResultPayload(
                task_id = payload.task_id,
                correlation_id = payload.correlation_id,
                status = STATUS_ERROR,
                output = null,
                error = e.message ?: "sandbox_unexpected_error",
                exit_code = EXIT_CODE_SANDBOX_ERROR,
                execution_ms = System.currentTimeMillis() - startMs,
                device_id = deviceId
            )
        }
    }

    private fun runEngine(payload: CodeExecutePayload, startMs: Long): CodeResultPayload {
        val engine = try {
            ScriptEngineManager().getEngineByName("JavaScript")
        } catch (e: Exception) {
            null
        }

        if (engine == null) {
            Log.w(TAG, "[runEngine] JavaScript engine not available")
            return CodeResultPayload(
                task_id = payload.task_id,
                correlation_id = payload.correlation_id,
                status = STATUS_BLOCKED,
                output = null,
                error = "javascript_engine_not_available",
                exit_code = EXIT_CODE_ENGINE_MISSING,
                execution_ms = System.currentTimeMillis() - startMs,
                device_id = deviceId
            )
        }

        // Bind environment variables into the engine context.
        payload.environment_vars.forEach { (key, value) ->
            engine.put(key, value)
        }

        val result = try {
            engine.eval(payload.code)
        } catch (e: ScriptException) {
            Log.w(TAG, "[runEngine] task_id=${payload.task_id} script error: ${e.message}")
            return CodeResultPayload(
                task_id = payload.task_id,
                correlation_id = payload.correlation_id,
                status = STATUS_ERROR,
                output = null,
                error = e.message ?: "script_error",
                exit_code = EXIT_CODE_SCRIPT_ERROR,
                execution_ms = System.currentTimeMillis() - startMs,
                device_id = deviceId
            )
        }

        val output = result?.toString() ?: ""
        val truncatedOutput = if (output.length > payload.max_output_chars) {
            output.substring(0, payload.max_output_chars) + "\n... [truncated]"
        } else {
            output
        }

        CodeResultPayload(
            task_id = payload.task_id,
            correlation_id = payload.correlation_id,
            status = STATUS_SUCCESS,
            output = truncatedOutput,
            error = null,
            exit_code = EXIT_CODE_SUCCESS,
            execution_ms = System.currentTimeMillis() - startMs,
            device_id = deviceId
        )
    }

}
