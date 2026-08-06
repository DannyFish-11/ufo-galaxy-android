package com.ufo.galaxy.config

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * DevicePairingClient — 设备端配对客户端。
 *
 * 注:KDoc 内不要写出字面 "斜杠星" 序列(例如用 `路径/*` 表示通配)——Kotlin 块注释
 * **可嵌套**,那个序列会开一层新注释,本段的收尾 `*/` 只关掉它,外层一路吞到文件末尾。
 * 这不是假设:本文件就这么坏过一次,整个类变成 Unresolved reference。要表达通配请写
 * 具体端点名,或改用中文省略号。
 *
 * 一步换令牌
 * ==========
 * 桌面面板出示名片(二维码 + 6 位短码),本机把**码**连同**自己的身份**交给
 * `/api/v1/pair/claim`,当场换回一枚属于自己的能力令牌,外加"接下来往哪儿连"的
 * 候选路径清单。没有轮询、没有等待批准 —— 你手里的那个码就是凭证。
 *
 * 换掉了什么
 * ==========
 * 此前走的是 `/api/v1/pairing/enroll` → 轮询 `status` → `claim/{request_id}`
 * 这条「设备申请 · 桌面批准 · 设备领取」的三段式。手表那侧又是另一套
 * (`/auth/oauth/device/start` 起头的 OAuth device flow)。三种设备三条路,行为、失败模式、
 * 凭证形态各不相同,而它们要接的是同一台机器。
 *
 * 现在三仓统一到 `/api/v1/pair/claim` 这一条。
 *
 * 令牌签给谁
 * ==========
 * 签给**本机**。名片只证明"这个人手里有一张桌面签发、还没过期的邀请",不证明
 * "这个人就是名片上那台机器" —— 邀请本来就可转交(口述短码、转发二维码都是设计
 * 里的用法)。所以 [deviceId] 必须是本机**将用于 WS 鉴权握手的那个规范 id**:
 * 服务端把它写进令牌 subject,设备入口再拿 subject 与握手时自报的 id 对一次。
 * 两者不一致就是"已配对却连不上"。
 */
class DevicePairingClient(
    private val restBaseUrl: String,
    private val httpClient: OkHttpClient = defaultClient(),
) {

    /**
     * 配对结果。
     *
     * @param candidates 桌面的可达路径,已按可达性排序(lan → tailscale → funnel)。
     *   **必须存下来**:只留 `endpoints` 里那个内网地址的话,出了网段就是死地址,
     *   带流量单独出门时一条都连不上。
     */
    data class PairingResult(
        val ok: Boolean,
        val token: String? = null,
        val candidates: List<Candidate> = emptyList(),
        val gatewayDeviceId: String? = null,
        val scopes: List<String> = emptyList(),
        val status: String? = null,
        val error: String? = null,
    )

    /** 一条候选路径。[priority] 从 1 起连续编号,设备端按它依次试。 */
    data class Candidate(val kind: String, val url: String, val priority: Int)

    /**
     * 凭短码或链接接纳本机,当场领取令牌。
     *
     * @param code 面板上的 6 位短码(大小写不敏感,服务端会归一)。与 [link] 二选一。
     * @param link 扫码得到的 `galaxy://pair?...` 链接。与 [code] 二选一。
     */
    suspend fun claim(
        deviceId: String,
        deviceName: String?,
        deviceType: String = "android",
        code: String? = null,
        link: String? = null,
        capabilities: List<String>? = null,
    ): PairingResult {
        if (code.isNullOrBlank() && link.isNullOrBlank()) {
            return PairingResult(ok = false, error = "need_code_or_link")
        }
        if (deviceId.isBlank()) {
            return PairingResult(ok = false, error = "missing_device_id")
        }

        val body = JSONObject().apply {
            if (!code.isNullOrBlank()) put("code", code.trim().uppercase())
            if (!link.isNullOrBlank()) put("link", link.trim())
            put("device_id", deviceId)
            if (!deviceName.isNullOrBlank()) put("name", deviceName)
            put("device_type", deviceType)
            if (capabilities != null) put("capabilities", JSONArray(capabilities))
        }

        val res = post("${base()}$PATH_CLAIM", body)
            ?: return PairingResult(ok = false, error = "network_error")
        val json = res.body

        if (res.code == HTTP_TOO_MANY_REQUESTS) {
            // 服务端按来源节流猜错次数。这是**可恢复**的,与"码不对"要分开报,
            // 否则用户看到"码无效"会一直重输,而实际上是要等一会儿。
            return PairingResult(ok = false, status = "throttled", error = "too_many_attempts")
        }
        if (json == null) {
            return PairingResult(ok = false, error = "bad_response_${res.code}")
        }
        if (!json.optBoolean("success", false)) {
            val why = json.optString("error").takeIf { it.isNotBlank() } ?: "claim_rejected"
            Log.w(TAG, "[PAIR] claim rejected: $why")
            return PairingResult(ok = false, error = why)
        }

        val token = json.optString("capability_token").takeIf { it.isNotBlank() }
        if (token == null) {
            // 服务端明确区分了"配对成功"与"令牌签发成功"(token_issued)。
            // 没令牌就是连不上,不能因为 success=true 就当成配对完成。
            return PairingResult(ok = false, error = "no_token_issued")
        }

        return PairingResult(
            ok = true,
            token = token,
            candidates = parseCandidates(json.optJSONArray("candidates")),
            gatewayDeviceId = json.optString("gateway_device_id").takeIf { it.isNotBlank() },
            scopes = parseStrings(json.optJSONArray("token_scopes")),
            status = "paired",
        )
    }

    // ── 解析 ─────────────────────────────────────────────────────────────────

    private fun parseCandidates(arr: JSONArray?): List<Candidate> {
        if (arr == null) return emptyList()
        val out = ArrayList<Candidate>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url")
            if (url.isBlank()) continue
            out.add(
                Candidate(
                    kind = o.optString("kind").ifBlank { "unknown" },
                    url = url,
                    // 缺 priority 时按数组次序兜底,而不是丢掉这一条 ——
                    // 丢掉等于少一条可达路径,而那正是这个字段存在的理由。
                    priority = o.optInt("priority", i + 1),
                )
            )
        }
        return out.sortedBy { it.priority }
    }

    private fun parseStrings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    // ── HTTP 内部 ─────────────────────────────────────────────────────────────
    private data class FetchResult(val code: Int, val success: Boolean, val body: JSONObject?)

    private fun base(): String = restBaseUrl.trimEnd('/')

    private suspend fun post(url: String, json: JSONObject): FetchResult? =
        execute(Request.Builder().url(url).post(json.toString().toRequestBody(JSON_MEDIA_TYPE)).build())

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
                            // body.string() 也可能抛(连接中途断)。OkHttp 已置 signalledCallback,
                            // 不会再回 onFailure,若不在此兜住,continuation 永不 resume → 挂死。
                            val parsed = runCatching { resp.body?.string() }.getOrNull()?.let {
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

        /** 三仓统一的接纳端点。V2 侧对它**免鉴权** —— 还没配对的设备手里没有任何令牌。 */
        const val PATH_CLAIM = "/api/v1/pair/claim"
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
