package com.ufo.galaxy.webrtc

import org.json.JSONArray
import org.json.JSONObject

/**
 * TURN server configuration delivered by the Gateway in a signaling message.
 *
 * Gateway JSON format (inside a `turn_config` object):
 * ```json
 * {
 *   "urls": ["turn:100.64.0.1:3478", "turns:100.64.0.1:5349"],
 *   "username": "galaxy_user",
 *   "credential": "s3cr3t"
 * }
 * ```
 *
 * @param urls       One or more TURN/TURNS server URIs.
 * @param username   TURN username (null for STUN-only servers).
 * @param credential TURN credential / password (null for STUN-only servers).
 */
data class TurnConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null
) {
    /**
     * Serialize this config to a [JSONObject] suitable for embedding in a
     * signaling message or for logging.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        val urlArray = JSONArray()
        urls.forEach { urlArray.put(it) }
        put("urls", urlArray)
        username?.let { put("username", it) }
        credential?.let { put("credential", it) }
    }

    companion object {
        /**
         * Deserialize a [TurnConfig] from the given [JSONObject].
         *
         * Accepts both an array-form `"urls"` field and a single `"url"` string
         * for backward compatibility with older gateway versions.
         */
        fun fromJson(json: JSONObject): TurnConfig {
            val urls = mutableListOf<String>()
            when {
                json.has("urls") -> {
                    val arr = json.getJSONArray("urls")
                    for (i in 0 until arr.length()) {
                        urls.add(arr.getString(i))
                    }
                }
                json.has("url") -> urls.add(json.getString("url"))
            }
            return TurnConfig(
                urls = urls,
                username = json.optString("username").takeIf { it.isNotEmpty() },
                credential = json.optString("credential").takeIf { it.isNotEmpty() }
            )
        }
    }
}
