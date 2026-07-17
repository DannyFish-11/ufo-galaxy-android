package com.ufo.galaxy.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * OAuth 管理单例类，负责 Google Sign-In 和 GitHub OAuth 的完整流程。
 *
 * ## 职责
 * - Google 登录：使用 Google Sign-In SDK 获取 idToken，发送到后端换取 JWT
 * - GitHub 登录：使用 Chrome Custom Tabs 打开授权页，拦截回调 code，发送到后端换取 JWT
 * - Token 安全存储：使用 EncryptedSharedPreferences (AES256-GCM) 保存 JWT
 * - Token 刷新和登出：调用后端接口维护会话状态
 *
 * ## 使用方式
 * ```kotlin
 * // 在 Activity 或 ViewModel 中
 * OAuthManager.getInstance(context).signInWithGoogle(activity)
 * OAuthManager.getInstance(context).signInWithGitHub(activity)
 * ```
 *
 * ## 配置方式
 * 在 app/build.gradle 的 defaultConfig 中通过 buildConfigField 设置：
 * ```groovy
 * buildConfigField "String", "OAUTH_GOOGLE_CLIENT_ID", '"your-google-web-client-id.apps.googleusercontent.com"'
 * buildConfigField "String", "OAUTH_GITHUB_CLIENT_ID", '"Ov23lixxxxxxxxx"'
 * ```
 * 或者直接在运行时通过 [configure] 方法设置 Client ID。
 *
 * @param context 应用上下文，用于初始化 EncryptedSharedPreferences 和 GoogleSignInClient
 */
class OAuthManager private constructor(private val context: Context) {

    // ── 可配置参数 ───────────────────────────────────────────────────────────

    /** Google Web Client ID */
    private var googleWebClientId: String = DEFAULT_GOOGLE_CLIENT_ID

    /** GitHub OAuth Client ID */
    private var githubClientId: String = DEFAULT_GITHUB_CLIENT_ID

    /** GitHub OAuth 回调 URI */
    private var githubRedirectUri: String = "ufo-galaxy://auth/callback"

    /** 后端 REST 基础 URL */
    private var customRestBaseUrl: String? = null

    // ── Google Sign-In ──────────────────────────────────────────────────────

    /** Google Sign-In 客户端，延迟初始化 */
    private val googleSignInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestIdToken(googleWebClientId)
            .requestScopes(
                com.google.android.gms.common.api.Scope("openid"),
                com.google.android.gms.common.api.Scope("email"),
                com.google.android.gms.common.api.Scope("profile")
            )
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    // ── 安全存储 ─────────────────────────────────────────────────────────────

    /** AES256-GCM 加密存储，用于保存敏感 Token */
    private val securePrefs: EncryptedSharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ) as EncryptedSharedPreferences
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "EncryptedSharedPreferences init failed: ${e.message}")
            throw OAuthException("安全存储初始化失败: ${e.message}", e)
        } catch (e: KeyStoreException) {
            Log.e(TAG, "Keystore unavailable: ${e.message}")
            throw OAuthException("系统密钥库不可用: ${e.message}", e)
        }
    }

    /** HTTP 客户端，用于后端 API 调用 */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 后端 REST 基础 URL */
    private val restBaseUrl: String
        get() = customRestBaseUrl ?: runCatching {
            com.ufo.galaxy.data.AppSettings.getInstance(context).effectiveRestBaseUrl()
        }.getOrElse { DEFAULT_REST_BASE_URL }

    // ── 公共 API ─────────────────────────────────────────────────────────────

    /**
     * 配置 OAuth Manager 的参数。
     * 在 build.gradle 中没有设置 buildConfigField 时，通过此方法运行时配置。
     *
     * @param googleWebClientId  Google Web Client ID（可选）
     * @param githubClientId     GitHub OAuth App Client ID（可选）
     * @param githubRedirectUri  GitHub 回调 URI（可选，默认 ufo-galaxy://auth/callback）
     * @param restBaseUrl        后端 REST 基础 URL（可选，默认从 AppSettings 读取）
     */
    fun configure(
        googleWebClientId: String? = null,
        githubClientId: String? = null,
        githubRedirectUri: String? = null,
        restBaseUrl: String? = null
    ) {
        googleWebClientId?.let { this.googleWebClientId = it }
        githubClientId?.let { this.githubClientId = it }
        githubRedirectUri?.let { this.githubRedirectUri = it }
        restBaseUrl?.let { this.customRestBaseUrl = it }
        Log.i(TAG, "configure: GoogleClientId=${this.googleWebClientId.isNotBlank()}, " +
            "GitHubClientId=${this.githubClientId.isNotBlank()}")
    }

    /**
     * 启动 Google Sign-In 流程。
     *
     * 流程：
     * 1. 使用 Google Sign-In SDK 弹出账号选择器
     * 2. 获取 idToken
     * 3. POST /auth/oauth/google 发送到后端
     * 4. 保存返回的 JWT 到 EncryptedSharedPreferences
     *
     * @param activity 用于启动 Google Sign-In 的 Activity
     * @return 登录成功的用户信息
     * @throws OAuthException 当任何步骤失败时抛出
     */
    suspend fun signInWithGoogle(activity: Activity): OAuthUser = withContext(Dispatchers.IO) {
        Log.i(TAG, "signInWithGoogle: 启动 Google Sign-In")

        if (googleWebClientId.isBlank() || googleWebClientId == "YOUR_GOOGLE_WEB_CLIENT_ID") {
            throw OAuthException(
                "Google Client ID 未配置。请在 build.gradle 中设置 OAUTH_GOOGLE_CLIENT_ID " +
                    "或调用 OAuthManager.configure() 进行配置。"
            )
        }

        // 步骤 1：执行 Google Sign-In 获取 idToken
        val account = performGoogleSignIn(activity)
        val idToken = account.idToken
            ?: throw OAuthException("Google Sign-In 未返回 idToken，请重试")

        Log.i(TAG, "signInWithGoogle: 获取到 idToken，正在发送到后端")

        // 步骤 2：发送 idToken 到后端
        val response = exchangeGoogleToken(idToken)

        // 步骤 3：保存 token 并构建用户对象
        saveTokenResponse(response, OAuthUser.PROVIDER_GOOGLE)

        val user = OAuthUser(
            id = response.optString("user_id", ""),
            email = response.optString("email", account.email ?: ""),
            name = response.optString("name", account.displayName ?: ""),
            picture = response.optString("picture", account.photoUrl?.toString() ?: "").ifBlank { null },
            provider = OAuthUser.PROVIDER_GOOGLE
        )

        Log.i(TAG, "signInWithGoogle: 登录成功, user=${user.email}")
        user
    }

    /**
     * 启动 GitHub OAuth 流程。
     *
     * 流程：
     * 1. 生成随机 state 防止 CSRF
     * 2. 使用 Chrome Custom Tabs 打开 GitHub 授权页
     * 3. 用户授权后，通过自定义 scheme ufo-galaxy://auth/callback 回调
     * 4. [OAuthCallbackActivity] 拦截回调并提取 code
     * 5. 发送 code 到后端 POST /auth/oauth/github
     * 6. 保存返回的 JWT
     *
     * @param activity 用于启动 Chrome Custom Tabs 的 Activity
     * @return 登录成功的用户信息
     * @throws OAuthException 当任何步骤失败时抛出
     */
    suspend fun signInWithGitHub(activity: Activity): OAuthUser = withContext(Dispatchers.IO) {
        Log.i(TAG, "signInWithGitHub: 启动 GitHub OAuth")

        if (githubClientId.isBlank() || githubClientId == "YOUR_GITHUB_CLIENT_ID") {
            throw OAuthException(
                "GitHub Client ID 未配置。请在 build.gradle 中设置 OAUTH_GITHUB_CLIENT_ID " +
                    "或调用 OAuthManager.configure() 进行配置。"
            )
        }

        // 步骤 1：生成随机 state 防止 CSRF 攻击
        val state = UUID.randomUUID().toString()
        securePrefs.edit().putString(KEY_GITHUB_OAUTH_STATE, state).commit()

        // 步骤 2：构建 GitHub 授权 URL
        val authUrl = buildGitHubAuthUrl(state)
        Log.d(TAG, "signInWithGitHub: authUrl=$authUrl")

        // 步骤 3：使用 Chrome Custom Tabs 打开授权页
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setUrlBarHidingEnabled(true)
            .build()

        // 步骤 4：等待回调（OAuthCallbackActivity 会通过续体通知结果）
        val code = waitForGitHubCallback(activity, customTabsIntent, authUrl, state)

        Log.i(TAG, "signInWithGitHub: 获取到 code，正在发送到后端")

        // 步骤 5：用 code 换取 JWT
        val response = exchangeGitHubCode(code)

        // 步骤 6：保存 token
        saveTokenResponse(response, OAuthUser.PROVIDER_GITHUB)

        // 清除 state
        securePrefs.edit().remove(KEY_GITHUB_OAUTH_STATE).commit()

        val user = OAuthUser(
            id = response.optString("user_id", ""),
            email = response.optString("email", ""),
            name = response.optString("name", ""),
            picture = response.optString("picture", "").ifBlank { null },
            provider = OAuthUser.PROVIDER_GITHUB
        )

        Log.i(TAG, "signInWithGitHub: 登录成功, user=${user.email}")
        user
    }

    /**
     * 处理 GitHub OAuth 回调。
     * 由 [OAuthCallbackActivity] 在拦截到回调 URL 后调用。
     *
     * @param uri 回调 URI，包含 code 和 state 参数
     * @return true 如果 state 验证通过且成功通知了等待中的挂起函数
     */
    fun handleGitHubCallback(uri: Uri): Boolean {
        val code = uri.getQueryParameter("code")
        val state = uri.getQueryParameter("state")

        if (code.isNullOrBlank()) {
            Log.e(TAG, "handleGitHubCallback: 回调 URI 中缺少 code 参数")
            notifyGitHubResult(null, "回调 URI 中缺少 code 参数")
            return false
        }

        // 验证 state 防止 CSRF
        val storedState = securePrefs.getString(KEY_GITHUB_OAUTH_STATE, null)
        if (storedState == null || storedState != state) {
            Log.e(TAG, "handleGitHubCallback: state 验证失败 (CSRF 保护)")
            notifyGitHubResult(null, "State 验证失败，请重试")
            return false
        }

        Log.i(TAG, "handleGitHubCallback: state 验证通过，发送 code")
        notifyGitHubResult(code, null)
        return true
    }

    /**
     * 从安全存储中读取已保存的 JWT Access Token。
     *
     * @return JWT 字符串，如果没有登录则返回 null
     */
    fun getStoredToken(): String? {
        return securePrefs.getString(KEY_ACCESS_TOKEN, null)?.takeIf { it.isNotBlank() }
    }

    /**
     * 获取 Refresh Token。
     */
    fun getStoredRefreshToken(): String? {
        return securePrefs.getString(KEY_REFRESH_TOKEN, null)?.takeIf { it.isNotBlank() }
    }

    /**
     * 获取当前登录用户信息。
     */
    fun getStoredUser(): OAuthUser? {
        val email = securePrefs.getString(KEY_USER_EMAIL, null)?.takeIf { it.isNotBlank() } ?: return null
        return OAuthUser(
            id = securePrefs.getString(KEY_USER_ID, "") ?: "",
            email = email,
            name = securePrefs.getString(KEY_USER_NAME, "") ?: "",
            picture = securePrefs.getString(KEY_USER_PICTURE, null)?.takeIf { it.isNotBlank() },
            provider = securePrefs.getString(KEY_PROVIDER, "") ?: ""
        )
    }

    /**
     * 检查是否存在有效的登录 token。
     *
     * @return true 如果存在未过期的 access token
     */
    fun isLoggedIn(): Boolean {
        val token = getStoredToken()
        if (token.isNullOrBlank()) return false

        // 检查 token 是否过期
        val expiresAt = securePrefs.getLong(KEY_EXPIRES_AT, 0)
        if (expiresAt > 0 && System.currentTimeMillis() >= expiresAt) {
            Log.d(TAG, "isLoggedIn: token 已过期")
            return false
        }

        return true
    }

    /**
     * 登出：清除本地 token 并调用后端登出接口。
     */
    suspend fun logout() = withContext(Dispatchers.IO) {
        Log.i(TAG, "logout: 执行登出")

        val token = getStoredToken()

        // 清除本地存储（无论后端调用是否成功都清除）
        clearAllTokens()

        // 调用后端登出接口
        if (!token.isNullOrBlank()) {
            try {
                val request = Request.Builder()
                    .url("$restBaseUrl/auth/oauth/logout")
                    .header("Authorization", "Bearer $token")
                    .post(EMPTY_BODY)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    Log.i(TAG, "logout: 后端响应 HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "logout: 后端登出调用失败 (忽略): ${e.message}")
            }
        }
    }

    /**
     * 刷新 Token。
     * 使用 refresh_token 换取新的 access_token。
     */
    suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = getStoredRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            Log.w(TAG, "refreshToken: 没有 refresh_token")
            return@withContext false
        }

        Log.i(TAG, "refreshToken: 正在刷新 token")

        try {
            val body = JSONObject().apply {
                put("refresh_token", refreshToken)
            }

            val request = Request.Builder()
                .url("$restBaseUrl/auth/oauth/refresh")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "refreshToken: HTTP ${response.code}")
                    return@withContext false
                }

                val responseBody = response.body?.string()
                    ?: throw OAuthException("刷新 token 响应为空")
                val json = JSONObject(responseBody)

                val provider = securePrefs.getString(KEY_PROVIDER, "") ?: ""
                saveTokenResponse(json, provider)
                Log.i(TAG, "refreshToken: token 刷新成功")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshToken: 失败: ${e.message}")
            false
        }
    }

    // ── 内部方法 ─────────────────────────────────────────────────────────────

    /**
     * 执行 Google Sign-In，返回账号信息。
     * 使用 suspendCancellableCoroutine 将回调式 API 转为挂起函数。
     */
    private suspend fun performGoogleSignIn(activity: Activity): GoogleSignInAccount =
        suspendCancellableCoroutine { continuation ->
            // 存储续体以便在 onActivityResult 中恢复
            googleSignInContinuation = continuation

            // 先清除之前的登录状态，确保弹出账号选择器
            googleSignInClient.signOut().addOnCompleteListener {
                val signInIntent = googleSignInClient.signInIntent
                activity.startActivityForResult(signInIntent, RC_GOOGLE_SIGN_IN)
            }

            // 取消时清理
            continuation.invokeOnCancellation {
                googleSignInContinuation = null
            }
        }

    /**
     * 将 Google idToken 发送到后端换取 JWT。
     */
    private fun exchangeGoogleToken(idToken: String): JSONObject {
        val body = JSONObject().apply {
            put("id_token", idToken)
        }

        val request = Request.Builder()
            .url("$restBaseUrl/auth/oauth/google")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
                ?: throw OAuthException("后端响应为空")

            if (!response.isSuccessful) {
                Log.e(TAG, "exchangeGoogleToken: HTTP ${response.code}, body=$responseBody")
                val error = runCatching {
                    JSONObject(responseBody).optString("error", "未知错误")
                }.getOrDefault("未知错误")
                throw OAuthException("Google 登录失败: $error (HTTP ${response.code})")
            }

            return JSONObject(responseBody)
        }
    }

    /**
     * 构建 GitHub OAuth 授权 URL。
     */
    private fun buildGitHubAuthUrl(state: String): String {
        return Uri.parse("https://github.com/login/oauth/authorize").buildUpon()
            .appendQueryParameter("client_id", githubClientId)
            .appendQueryParameter("redirect_uri", githubRedirectUri)
            .appendQueryParameter("scope", "read:user user:email")
            .appendQueryParameter("state", state)
            .build()
            .toString()
    }

    /**
     * 等待 GitHub OAuth 回调。
     * 启动 Chrome Custom Tabs，然后通过续体等待 [handleGitHubCallback] 传递 code。
     */
    private suspend fun waitForGitHubCallback(
        activity: Activity,
        customTabsIntent: CustomTabsIntent,
        authUrl: String,
        state: String
    ): String = suspendCancellableCoroutine { continuation ->
        // 注册本次请求的等待
        githubOAuthContinuation = continuation

        // 启动 Chrome Custom Tabs（必须在主线程）
        activity.runOnUiThread {
            customTabsIntent.launchUrl(activity, Uri.parse(authUrl))
        }

        // 超时处理：2 分钟后自动失败。
        // 该任务在流程完成（成功/失败）或协程取消时都会被移除，
        // 避免上一轮登录遗留的陈旧超时任务误杀后续登录流程。
        val timeoutRunnable = Runnable {
            notifyGitHubResult(null, "GitHub 授权超时，请重试")
        }
        githubTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, GITHUB_OAUTH_TIMEOUT_MS)

        // 取消时清理：原子取出续体并移除超时任务（不会重复 resume）
        continuation.invokeOnCancellation {
            takeGitHubContinuation()
        }
    }

    /**
     * 将 GitHub code 发送到后端换取 JWT。
     */
    private fun exchangeGitHubCode(code: String): JSONObject {
        val body = JSONObject().apply {
            put("code", code)
            put("redirect_uri", githubRedirectUri)
        }

        val request = Request.Builder()
            .url("$restBaseUrl/auth/oauth/github")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string()
                ?: throw OAuthException("后端响应为空")

            if (!response.isSuccessful) {
                Log.e(TAG, "exchangeGitHubCode: HTTP ${response.code}, body=$responseBody")
                val error = runCatching {
                    JSONObject(responseBody).optString("error", "未知错误")
                }.getOrDefault("未知错误")
                throw OAuthException("GitHub 登录失败: $error (HTTP ${response.code})")
            }

            return JSONObject(responseBody)
        }
    }

    /**
     * 保存后端返回的 token 响应到安全存储。
     */
    private fun saveTokenResponse(response: JSONObject, provider: String) {
        val accessToken = response.optString("access_token", "")
        val refreshToken = response.optString("refresh_token", "")
        val expiresIn = response.optLong("expires_in", DEFAULT_TOKEN_EXPIRY_SECONDS)

        if (accessToken.isBlank()) {
            throw OAuthException("后端未返回 access_token")
        }

        val editor = securePrefs.edit()
        editor.putString(KEY_ACCESS_TOKEN, accessToken)
        if (refreshToken.isNotBlank()) {
            editor.putString(KEY_REFRESH_TOKEN, refreshToken)
        }
        editor.putString(KEY_PROVIDER, provider)
        editor.putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + (expiresIn * 1000))

        // 保存用户信息
        editor.putString(KEY_USER_ID, response.optString("user_id", ""))
        editor.putString(KEY_USER_EMAIL, response.optString("email", ""))
        editor.putString(KEY_USER_NAME, response.optString("name", ""))
        response.optString("picture", "").takeIf { it.isNotBlank() }?.let {
            editor.putString(KEY_USER_PICTURE, it)
        }

        editor.commit()
        Log.i(TAG, "saveTokenResponse: token 已保存 (provider=$provider, expiresIn=${expiresIn}s)")
    }

    /**
     * 清除所有本地保存的 token 和用户信息。
     */
    private fun clearAllTokens() {
        securePrefs.edit().apply {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_REFRESH_TOKEN)
            remove(KEY_USER_ID)
            remove(KEY_USER_EMAIL)
            remove(KEY_USER_NAME)
            remove(KEY_USER_PICTURE)
            remove(KEY_PROVIDER)
            remove(KEY_EXPIRES_AT)
            remove(KEY_GITHUB_OAUTH_STATE)
        }.commit()
        Log.i(TAG, "clearAllTokens: 所有 token 已清除")
    }

    /**
     * 通知等待中的 GitHub OAuth 挂起函数结果。
     * 由 [handleGitHubCallback]、超时任务和 [Companion.handleGitHubOAuthBroadcast] 调用。
     *
     * 通过 [takeGitHubContinuation] 原子地取出并清空续体、同时移除超时任务，
     * 配合 tryResume/tryResumeWithException 保证 resume 至多发生一次，
     * 防止回调 / 超时 / 取消之间的二次 resume 竞态。
     */
    private fun notifyGitHubResult(code: String?, error: String?) {
        // 无论是否有等待中的续体，流程结束都清除 state，避免残留
        clearGitHubOAuthState()
        val continuation = takeGitHubContinuation() ?: return
        if (code != null) {
            continuation.tryResume(code)
        } else {
            continuation.tryResumeWithException(
                OAuthException(error ?: "GitHub 授权失败")
            )
        }
    }

    /** 清除本次 GitHub OAuth 流程保存的 state。 */
    private fun clearGitHubOAuthState() {
        securePrefs.edit().remove(KEY_GITHUB_OAUTH_STATE).commit()
    }

    // ── Companion ───────────────────────────────────────────────────────────

    companion object {
        private const val TAG = "OAuthManager"

        // Google Sign-In 请求码
        const val RC_GOOGLE_SIGN_IN = 9001

        // GitHub OAuth 超时时间（2分钟）
        private const val GITHUB_OAUTH_TIMEOUT_MS = 120_000L

        // 默认 Token 过期时间（1小时）
        private const val DEFAULT_TOKEN_EXPIRY_SECONDS = 3600L

        // 后端 REST 默认地址（fallback）
        private const val DEFAULT_REST_BASE_URL = "https://localhost:9000"

        // 默认 Client ID：优先使用 BuildConfig 中的配置，否则使用占位符
        private val DEFAULT_GOOGLE_CLIENT_ID =
            try { com.ufo.galaxy.BuildConfig.OAUTH_GOOGLE_CLIENT_ID }
            catch (_: Exception) { "YOUR_GOOGLE_WEB_CLIENT_ID" }

        private val DEFAULT_GITHUB_CLIENT_ID =
            try { com.ufo.galaxy.BuildConfig.OAUTH_GITHUB_CLIENT_ID }
            catch (_: Exception) { "YOUR_GITHUB_CLIENT_ID" }

        // ── EncryptedSharedPreferences 存储键名 ──

        /** 加密存储文件名 */
        private const val SECURE_PREFS_NAME = "ufo_galaxy_oauth"

        const val KEY_ACCESS_TOKEN = "oauth_access_token"
        const val KEY_REFRESH_TOKEN = "oauth_refresh_token"
        const val KEY_USER_EMAIL = "oauth_user_email"
        const val KEY_USER_NAME = "oauth_user_name"
        const val KEY_USER_PICTURE = "oauth_user_picture"
        const val KEY_PROVIDER = "oauth_provider"
        const val KEY_EXPIRES_AT = "oauth_expires_at"
        const val KEY_USER_ID = "oauth_user_id"
        const val KEY_GITHUB_OAUTH_STATE = "oauth_github_state"

        // ── 内部挂起续体（用于 Activity 结果回调）──

        @Volatile
        internal var googleSignInContinuation: CancellableContinuation<GoogleSignInAccount>? = null

        @Volatile
        internal var githubOAuthContinuation: CancellableContinuation<String>? = null

        /** 续体读取/清空操作的锁，保证原子性，防止多线程下的二次 resume 竞态 */
        private val continuationLock = Any()

        /** 主线程 Handler，用于 GitHub OAuth 超时任务 */
        private val mainHandler = Handler(Looper.getMainLooper())

        /** 当前 GitHub OAuth 流程的超时任务，完成/取消时移除 */
        @Volatile
        private var githubTimeoutRunnable: Runnable? = null

        /**
         * 原子地取出并清空等待中的 GitHub OAuth 续体，同时移除超时任务。
         * 所有完成路径（回调、超时、取消）都经过此方法，保证 resume 至多发生一次。
         *
         * @return 等待中的续体；如果没有则返回 null
         */
        private fun takeGitHubContinuation(): CancellableContinuation<String>? {
            val continuation: CancellableContinuation<String>?
            val timeout: Runnable?
            synchronized(continuationLock) {
                continuation = githubOAuthContinuation
                timeout = githubTimeoutRunnable
                githubOAuthContinuation = null
                githubTimeoutRunnable = null
            }
            timeout?.let { mainHandler.removeCallbacks(it) }
            return continuation
        }

        /** JSON Content-Type */
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 空请求体 */
        private val EMPTY_BODY = "{}".toRequestBody(JSON_MEDIA_TYPE)

        @Volatile
        private var instance: OAuthManager? = null

        /**
         * 获取 OAuthManager 单例。
         */
        fun getInstance(context: Context): OAuthManager {
            return instance ?: synchronized(this) {
                instance ?: OAuthManager(context.applicationContext).also {
                    instance = it
                }
            }
        }

        /**
         * 处理 Google Sign-In Activity 结果。
         * 在调用 Activity 的 onActivityResult 中调用此方法。
         *
         * @param requestCode 请求码
         * @param resultCode  结果码
         * @param data        Intent 数据
         */
        /**
         * 供 [OAuthCallbackActivity] 直接通知 GitHub OAuth 结果。
         * 通过已存储的 OAuthManager 单例实例通知等待中的挂起函数。
         *
         * @param code  GitHub 授权码，如果授权失败则为 null
         * @param error 错误信息，如果授权成功则为 null
         */
        fun handleGitHubOAuthBroadcast(code: String?, error: String?) {
            val manager = instance
            if (manager != null) {
                manager.notifyGitHubResult(code, error)
            } else {
                // 单例尚未创建时没有等待中的流程，仍需安全清空续体与超时任务
                takeGitHubContinuation()
            }
        }

        /**
         * 处理 Google Sign-In Activity 结果。
         * 在调用 Activity 的 onActivityResult 中调用此方法。
         *
         * @param requestCode 请求码
         * @param resultCode  结果码
         * @param data        Intent 数据
         */
        fun handleGoogleSignInResult(requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode != RC_GOOGLE_SIGN_IN) return

            val task: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(data)
            // 原子取出并清空续体，配合 tryResume 防止与协程取消发生二次 resume 竞态
            val continuation = synchronized(continuationLock) {
                val c = googleSignInContinuation
                googleSignInContinuation = null
                c
            } ?: return

            try {
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    continuation.tryResume(account)
                } else {
                    continuation.tryResumeWithException(OAuthException("Google 登录返回空账号"))
                }
            } catch (e: ApiException) {
                Log.e(TAG, "Google Sign-In 失败: code=${e.statusCode}, message=${e.message}")
                val message = when (e.statusCode) {
                    com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> "用户取消了登录"
                    com.google.android.gms.common.api.CommonStatusCodes.NETWORK_ERROR -> "网络错误，请检查网络连接"
                    else -> "Google 登录失败 (${e.statusCode})"
                }
                continuation.tryResumeWithException(OAuthException(message, e))
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In 异常: ${e.message}")
                continuation.tryResumeWithException(OAuthException("Google 登录异常: ${e.message}", e))
            }
        }
    }
}

/**
 * OAuth 相关异常。
 */
class OAuthException(message: String, cause: Throwable? = null) : Exception(message, cause)
