package com.ufo.galaxy.grounding

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.ufo.galaxy.inference.LocalGroundingService
import com.ufo.galaxy.inference.WarmupResult
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * SeeClick on-device GUI grounding engine.
 *
 * Communicates with an NCNN or MNN inference server running locally at [endpointUrl]
 * (default: http://127.0.0.1:8081). The server accepts POST /ground requests and
 * returns grounded screen coordinates.
 *
 * Model:   njucckevin/SeeClick (HuggingFace)
 * Runtime: NCNN (ARM NEON/Vulkan) or MNN
 * Model file paths exposed via [modelParamPath] / [modelBinPath] for the server to locate.
 *
 * Request format (POST /ground):
 * ```json
 * {"screenshot":"<base64-jpeg>","intent":"click the login button","width":1080,"height":2340}
 * ```
 *
 * Response format:
 * ```json
 * {"x":540,"y":1200,"confidence":0.92,"element":"login button"}
 * ```
 *
 * Coordinates are produced exclusively on-device; the gateway never supplies x/y values.
 *
 * @param endpointUrl    Local inference server URL.
 * @param modelParamPath Absolute path to the NCNN param file (empty = server locates itself).
 * @param modelBinPath   Absolute path to the NCNN bin file (empty = server locates itself).
 * @param timeoutMs      HTTP connect/read timeout in milliseconds.
 */
class SeeClickGroundingEngine(
    private val endpointUrl: String = "http://127.0.0.1:8081",
    val modelParamPath: String = "",
    val modelBinPath: String = "",
    private val timeoutMs: Int = 15_000
) : LocalGroundingService {

    private val gson = Gson()
    private var modelLoaded = false

    /** Stores the last structured warmup failure for diagnostics; null when loaded. */
    @Volatile
    var lastWarmupResult: WarmupResult = WarmupResult.failure(
        WarmupResult.WarmupStage.HEALTH_CHECK, "loadModel not yet called"
    )
        private set

    companion object {
        private const val GROUND_PATH = "/ground"
        private const val HEALTH_PATH = "/health"
    }

    override fun loadModel(): Boolean {
        val result = warmupWithResult()
        return result.success
    }

    override fun unloadModel() {
        modelLoaded = false
    }

    override fun isModelLoaded(): Boolean = modelLoaded

    /**
     * Pre-warms the SeeClick inference server.
     * Prefer [warmupWithResult] for structured failure reporting.
     */
    override fun prewarm(): Boolean = warmupWithResult().success

    /**
     * Hardened warmup that validates:
     * 1. Health endpoint reachability.
     * 2. Dry-run grounding round-trip with a 1×1 synthetic image.
     * 3. Valid response shape (x and y fields present).
     *
     * Sets [modelLoaded] and [lastWarmupResult] as a side-effect.
     */
    override fun warmupWithResult(): WarmupResult {
        // Stage 1: health check
        if (!pingEndpoint()) {
            val result = WarmupResult.failure(
                WarmupResult.WarmupStage.HEALTH_CHECK,
                "SeeClick health endpoint not reachable at $endpointUrl"
            )
            modelLoaded = false
            lastWarmupResult = result
            return result
        }

        // Stage 2: dry-run grounding with a minimal synthetic screenshot
        val responseText = try {
            httpPost(buildDryRunRequest())
        } catch (e: Exception) {
            val result = WarmupResult.failure(
                WarmupResult.WarmupStage.DRY_RUN_INFERENCE,
                "SeeClick dry-run grounding failed: ${e.message}"
            )
            modelLoaded = false
            lastWarmupResult = result
            return result
        }

        // Stage 3: response shape validation — x and y must be present
        val valid = try {
            val root = gson.fromJson(responseText, JsonObject::class.java)
            root?.get("x") != null && root.get("y") != null
        } catch (e: Exception) {
            false
        }

        if (!valid) {
            val result = WarmupResult.failure(
                WarmupResult.WarmupStage.RESPONSE_VALIDATION,
                "SeeClick dry-run response missing x/y coordinates"
            )
            modelLoaded = false
            lastWarmupResult = result
            return result
        }

        val result = WarmupResult.success()
        modelLoaded = true
        lastWarmupResult = result
        return result
    }

    override fun ground(
        intent: String,
        screenshotBase64: String,
        width: Int,
        height: Int
    ): LocalGroundingService.GroundingResult {
        return try {
            val requestJson = buildRequest(intent, screenshotBase64, width, height)
            val responseText = httpPost(requestJson)
            parseResponse(responseText)
        } catch (e: IOException) {
            LocalGroundingService.GroundingResult(
                x = 0,
                y = 0,
                confidence = 0f,
                element_description = "",
                error = "SeeClick grounding failed: ${e.message}"
            )
        } catch (e: Exception) {
            LocalGroundingService.GroundingResult(
                x = 0,
                y = 0,
                confidence = 0f,
                element_description = "",
                error = "SeeClick grounding error: ${e.message}"
            )
        }
    }

    private fun buildRequest(
        intent: String,
        screenshotBase64: String,
        width: Int,
        height: Int
    ): String = gson.toJson(JsonObject().apply {
        addProperty("screenshot", screenshotBase64)
        addProperty("intent", intent)
        addProperty("width", width)
        addProperty("height", height)
        if (modelParamPath.isNotEmpty()) addProperty("model_param_path", modelParamPath)
        if (modelBinPath.isNotEmpty()) addProperty("model_bin_path", modelBinPath)
    })

    private fun httpPost(requestJson: String): String {
        val url = URL("$endpointUrl$GROUND_PATH")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.outputStream.use { it.write(requestJson.toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                throw IOException("HTTP $code from SeeClick endpoint")
            }
            return conn.inputStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(responseText: String): LocalGroundingService.GroundingResult {
        return try {
            val root = gson.fromJson(responseText, JsonObject::class.java)
            val x = root.get("x")?.asInt
                ?: return LocalGroundingService.GroundingResult(
                    x = 0, y = 0, confidence = 0f,
                    element_description = "",
                    error = "SeeClick: missing x coordinate in response"
                )
            val y = root.get("y")?.asInt
                ?: return LocalGroundingService.GroundingResult(
                    x = 0, y = 0, confidence = 0f,
                    element_description = "",
                    error = "SeeClick: missing y coordinate in response"
                )
            LocalGroundingService.GroundingResult(
                x = x,
                y = y,
                confidence = root.get("confidence")?.asFloat ?: 0f,
                element_description = root.get("element")?.asString ?: ""
            )
        } catch (e: JsonSyntaxException) {
            LocalGroundingService.GroundingResult(
                x = 0, y = 0, confidence = 0f,
                element_description = "",
                error = "SeeClick: failed to parse response JSON: ${e.message}"
            )
        }
    }

    private fun pingEndpoint(): Boolean {
        return try {
            val url = URL("$endpointUrl$HEALTH_PATH")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 3_000
                conn.readTimeout = 3_000
                conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Builds a minimal dry-run grounding request using a 1×1 transparent PNG encoded as
     * base64. The response content is used only for shape validation; coordinates are
     * discarded.
     *
     * The synthetic image is the smallest valid JPEG that most NCNN/MNN backends will
     * accept without rejecting the request for a malformed screenshot.
     */
    private fun buildDryRunRequest(): String = gson.toJson(JsonObject().apply {
        // Minimal 1×1 white JPEG (raw base64, no line-wrapping needed)
        addProperty("screenshot", DRY_RUN_SCREENSHOT_B64)
        addProperty("intent", "dry-run")
        addProperty("width", 1)
        addProperty("height", 1)
        if (modelParamPath.isNotEmpty()) addProperty("model_param_path", modelParamPath)
        if (modelBinPath.isNotEmpty()) addProperty("model_bin_path", modelBinPath)
    })
}

private const val DRY_RUN_SCREENSHOT_B64 =
    "/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a" +
        "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFgABAQEAAAAAAAAA" +
        "AAAAAAAAAAf/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAA/ACoAAA=="
