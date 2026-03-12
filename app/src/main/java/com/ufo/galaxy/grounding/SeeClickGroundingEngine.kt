package com.ufo.galaxy.grounding

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.ufo.galaxy.inference.LocalGroundingService
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
 * @param endpointUrl Local inference server URL.
 * @param timeoutMs   HTTP connect/read timeout in milliseconds.
 */
class SeeClickGroundingEngine(
    private val endpointUrl: String = "http://127.0.0.1:8081",
    private val timeoutMs: Int = 15_000
) : LocalGroundingService {

    private val gson = Gson()
    private var modelLoaded = false

    companion object {
        private const val GROUND_PATH = "/ground"
        private const val HEALTH_PATH = "/health"
    }

    override fun loadModel(): Boolean {
        modelLoaded = pingEndpoint()
        return modelLoaded
    }

    override fun unloadModel() {
        modelLoaded = false
    }

    override fun isModelLoaded(): Boolean = modelLoaded

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
}
