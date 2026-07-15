package com.ufo.galaxy.config

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * DevicePairingClient — 设备端配对客户端(与 V2 后端 /api/v1/pairing/ 端点对接)。
 *
 * 注:KDoc 内避免出现字面 "斜杠星" 序列——Kotlin 块注释可嵌套,会吞掉本段的收尾。
 *
 * 这是"别处批准 + 智能体准入"配对流程的设备半边,新设备【零输入】:
 *   1. [enroll]  提交入伙请求(带自己的 device_id/type/name),拿到 request_id;
 *   2. 轮询 [status] 等你在【已信任设备】上(桌面/手机)经智能体判断后批准;
 *   3. 批准后 [claim] 一次性领取本设备【专属 token】,存进加密存储([AppSettings.gatewayToken]);
 *   4. 之后连接 /ws/device/{id} 用自己的 token 鉴权。
 *
 * 服务端不把 token 回给未鉴权设备——token 由服务端在批准后经 claim 交出;request_id
 * 是 uuid4,充当"领取自己 token"的一次性能力凭据。全程本设备不扫码、不打 IP、不填 token。
 *
 * HTTP 风格与 [RemoteConfigFetcher] 一致(OkHttp + suspendCancellableCoroutine enqueue)。
 */
class DevicePairingClient(
    private val restBaseUrl: String,
    private val httpClient: OkHttpClient = defaultClient(),
) {

    /** 配对结果。ok=true 时 token 非空。 */
    data class PairingResult(
        val ok: Boolean,
        val token: String? = null,
        val status: String? = null,
        val error: String? = null,
    )

    /** 提交入伙请求,成功返回 request_id,否则 null。 */
    suspend fun enroll(deviceId: String, deviceType: String?, name: String?): String? {
        val body = JSONObject().apply {
            put("device_id", deviceId)
            if (deviceType != null) put("device_type", deviceType)
            if (name != null) put("name", name)
        }
        val res = post("${base()}$PATH_ENROLL", body) ?: return null
        val json = res.body ?: return null
        if (!json.optBoolean("ok", false)) {
            Log.w(TAG, "[PAIR] enroll rejected: ${json.optString("error")}")
            return null
        }
        return json.optString("request_id").takeIf { it.isNotBlank() }
    }

    /** 查询请求状态(pending/approved/denied/expired/claimed);失败/未知返回 null。 */
    suspend fun status(requestId: String): String? {
        val res = get("${base()}/api/v1/pairing/status/$requestId") ?: return null
        val json = res.body ?: return null
        if (!json.optBoolean("ok", false)) return null
        return json.optString("status").takeIf { it.isNotBlank() }
    }

    /** 领取本设备专属 token(仅批准后、一次性);拿不到返回 null。 */
    suspend fun claim(requestId: String): String? {
        val res = post("${base()}/api/v1/pairing/claim/$requestId", JSONObject()) ?: return null
        val json = res.body ?: return null
        if (!json.optBoolean("ok", false)) return null
        return json.optString("token").takeIf { it.isNotBlank() }
    }

    /**
     * 高层:自发配对——enroll → 有界轮询等你批准 → claim 领 token。
     *
     * @param onWaiting 每次仍处 pending 时回调当前状态(供 UI 显示"等待批准…")。
     * @return 批准并领到 token → ok=true;被拒/过期/超时 → ok=false 且带原因。
     */
    suspend fun pairAndClaim(
        deviceId: String,
        deviceType: String?,
        name: String?,
        timeoutMs: Long = 300_000L,
        pollIntervalMs: Long = 3_000L,
        onWaiting: ((String) -> Unit)? = null,
    ): PairingResult {
        val rid = enroll(deviceId, deviceType, name)
            ?: return PairingResult(ok = false, error = "enroll_failed")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (val st = status(rid)) {
                "approved" -> {
                    val tok = claim(rid)
                    return if (tok != null) PairingResult(ok = true, token = tok, status = "approved")
                    else PairingResult(ok = false, status = st, error = "claim_failed")
                }
                "denied" -> return PairingResult(ok = false, status = "denied", error = "denied_by_owner")
                "expired" -> return PairingResult(ok = false, status = "expired", error = "request_expired")
                else -> {
                    onWaiting?.invoke(st ?: "pending")
                    delay(pollIntervalMs)
                }
            }
        }
        return PairingResult(ok = false, status = "timeout", error = "approval_timeout")
    }

    // ── HTTP 内部 ─────────────────────────────────────────────────────────────
    private data class FetchResult(val code: Int, val success: Boolean, val body: JSONObject?)

    private fun base(): String = restBaseUrl.trimEnd('/')

    private suspend fun post(url: String, json: JSONObject): FetchResult? =
        execute(Request.Builder().url(url).post(json.toString().toRequestBody(JSON_MEDIA_TYPE)).build())

    private suspend fun get(url: String): FetchResult? =
        execute(Request.Builder().url(url).get().build())

    private suspend fun execute(request: Request): FetchResult? {
        return try {
            suspendCancellableCoroutine { continuation ->
                val call = httpClient.newCall(request)
                continuation.invokeOnCancellation { runCatching { call.cancel() } }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        Log.e(TAG, "[PAIR] http error: ${e.message}")
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.use { resp ->
                            val parsed = resp.body?.string()?.let {
                                runCatching { JSONObject(it) }.getOrNull()
                            }
                            if (continuation.isActive) {
                                continuation.resume(
                                    FetchResult(code = resp.code, success = resp.isSuccessful, body = parsed)
                                )
                            }
                        }
                    }
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "[PAIR] request failed: ${e.message}", e)
            null
        }
    }

    companion object {
        private const val TAG = "DevicePairingClient"
        const val PATH_ENROLL = "/api/v1/pairing/enroll"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
