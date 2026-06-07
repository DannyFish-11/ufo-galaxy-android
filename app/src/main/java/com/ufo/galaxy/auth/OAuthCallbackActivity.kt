package com.ufo.galaxy.auth

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity

/**
 * GitHub OAuth 回调拦截 Activity。
 *
 * 在 AndroidManifest.xml 中注册了自定义 scheme 的 intent-filter：
 * ```xml
 * <data android:scheme="ufo-galaxy" android:host="auth" android:path="/callback"/>
 * ```
 *
 * 当 GitHub 授权完成后，会重定向到 `ufo-galaxy://auth/callback?code=...&state=...`，
 * 此 Activity 被启动，提取 code 和 state 参数后，
 * 交给 [OAuthManager.handleGitHubCallback] 处理并通知等待中的登录流程。
 *
 * 此 Activity 使用透明主题，不显示 UI，处理完回调后立即 finish。
 */
class OAuthCallbackActivity : ComponentActivity() {

    companion object {
        private const val TAG = "OAuthCallbackActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.i(TAG, "onCreate: 收到回调, action=${intent?.action}, data=${intent?.data}")

        val uri = intent?.data

        if (uri == null) {
            Log.e(TAG, "onCreate: 回调 URI 为空")
            finish()
            return
        }

        // 处理 GitHub OAuth 回调
        if (uri.scheme == "ufo-galaxy" && uri.host == "auth" && uri.path == "/callback") {
            handleGitHubCallback(uri)
        } else {
            Log.w(TAG, "onCreate: 未知的回调 URI: $uri")
            finish()
        }
    }

    /**
     * 处理 GitHub 回调 URI。
     * 验证 state 后将 code 传递给 OAuthManager。
     */
    private fun handleGitHubCallback(uri: Uri) {
        Log.i(TAG, "handleGitHubCallback: URI=$uri")

        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        val errorDescription = uri.getQueryParameter("error_description")

        // 如果 GitHub 返回了错误
        if (!error.isNullOrBlank()) {
            val message = "GitHub 授权错误: $error - ${errorDescription ?: "未知错误"}"
            Log.e(TAG, "handleGitHubCallback: $message")
            // 直接通知 OAuthManager 的等待续体
            OAuthManager.handleGitHubOAuthBroadcast(null, message)
            finish()
            return
        }

        // 验证必要参数
        if (code.isNullOrBlank()) {
            Log.e(TAG, "handleGitHubCallback: 缺少 code 参数")
            OAuthManager.handleGitHubOAuthBroadcast(null, "授权回调缺少 code 参数")
            finish()
            return
        }

        // 交给 OAuthManager 处理（验证 state 并继续流程）
        val manager = OAuthManager.getInstance(this)
        val success = manager.handleGitHubCallback(uri)

        if (!success) {
            Log.e(TAG, "handleGitHubCallback: OAuthManager 处理失败")
        }

        // 结束此透明 Activity
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: OAuthCallbackActivity 已销毁")
    }
}
