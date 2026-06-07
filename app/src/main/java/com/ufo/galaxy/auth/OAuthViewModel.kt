package com.ufo.galaxy.auth

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * OAuth 登录 ViewModel，管理登录 UI 状态。
 *
 * ## 状态机
 * - [Idle]：初始状态，等待用户操作
 * - [Loading]：登录中（显示菊花）
 * - [Success]：登录成功，包含用户信息
 * - [Error]：登录失败，包含错误信息
 *
 * ## 使用方式
 * ```kotlin
 * val viewModel: OAuthViewModel = viewModel()
 * val uiState by viewModel.uiState.collectAsState()
 *
 * // 根据状态渲染 UI
 * when (val state = uiState) {
 *     is OAuthUiState.Idle -> { /* 显示登录按钮 */ }
 *     is OAuthUiState.Loading -> { /* 显示加载中 */ }
 *     is OAuthUiState.Success -> { /* 跳转到首页 */ }
 *     is OAuthUiState.Error -> { /* 显示错误提示 */ }
 * }
 * ```
 */
class OAuthViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OAuthViewModel"
    }

    /** OAuth 管理器实例 */
    private val oauthManager: OAuthManager by lazy {
        OAuthManager.getInstance(getApplication())
    }

    /** UI 状态，外部只读 */
    private val _uiState = MutableStateFlow<OAuthUiState>(OAuthUiState.Idle)
    val uiState: StateFlow<OAuthUiState> = _uiState.asStateFlow()

    /**
     * 启动 Google Sign-In 登录。
     *
     * @param activity 用于启动 Google Sign-In 的 Activity
     */
    fun signInWithGoogle(activity: Activity) {
        if (_uiState.value is OAuthUiState.Loading) {
            Log.w(TAG, "signInWithGoogle: 已有登录流程在进行中，忽略重复请求")
            return
        }

        Log.i(TAG, "signInWithGoogle: 开始 Google 登录")
        _uiState.value = OAuthUiState.Loading

        viewModelScope.launch {
            try {
                val user = oauthManager.signInWithGoogle(activity)
                Log.i(TAG, "signInWithGoogle: 登录成功, user=${user.email}")
                _uiState.value = OAuthUiState.Success(user)
            } catch (e: OAuthException) {
                Log.e(TAG, "signInWithGoogle: 登录失败: ${e.message}")
                _uiState.value = OAuthUiState.Error(e.message ?: "Google 登录失败")
            } catch (e: Exception) {
                Log.e(TAG, "signInWithGoogle: 未知异常", e)
                _uiState.value = OAuthUiState.Error("登录异常: ${e.message}")
            }
        }
    }

    /**
     * 启动 GitHub OAuth 登录。
     *
     * @param activity 用于启动 Chrome Custom Tabs 的 Activity
     */
    fun signInWithGitHub(activity: Activity) {
        if (_uiState.value is OAuthUiState.Loading) {
            Log.w(TAG, "signInWithGitHub: 已有登录流程在进行中，忽略重复请求")
            return
        }

        Log.i(TAG, "signInWithGitHub: 开始 GitHub 登录")
        _uiState.value = OAuthUiState.Loading

        viewModelScope.launch {
            try {
                val user = oauthManager.signInWithGitHub(activity)
                Log.i(TAG, "signInWithGitHub: 登录成功, user=${user.email}")
                _uiState.value = OAuthUiState.Success(user)
            } catch (e: OAuthException) {
                Log.e(TAG, "signInWithGitHub: 登录失败: ${e.message}")
                _uiState.value = OAuthUiState.Error(e.message ?: "GitHub 登录失败")
            } catch (e: Exception) {
                Log.e(TAG, "signInWithGitHub: 未知异常", e)
                _uiState.value = OAuthUiState.Error("登录异常: ${e.message}")
            }
        }
    }

    /**
     * 检查是否已有有效登录。
     * 在应用启动时调用，如果已登录则直接跳转到首页。
     */
    fun checkExistingLogin() {
        viewModelScope.launch {
            try {
                val manager = OAuthManager.getInstance(getApplication())
                if (manager.isLoggedIn()) {
                    val user = manager.getStoredUser()
                    if (user != null && user.isValid()) {
                        Log.i(TAG, "checkExistingLogin: 已有有效登录, user=${user.email}")
                        _uiState.value = OAuthUiState.Success(user)
                    } else {
                        Log.w(TAG, "checkExistingLogin: token 存在但用户信息不完整")
                        _uiState.value = OAuthUiState.Idle
                    }
                } else {
                    Log.d(TAG, "checkExistingLogin: 未检测到有效登录")
                    _uiState.value = OAuthUiState.Idle
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkExistingLogin: 检查失败: ${e.message}")
                _uiState.value = OAuthUiState.Idle
            }
        }
    }

    /**
     * 执行登出。
     * 清除 token 并调用后端登出接口。
     */
    fun logout() {
        viewModelScope.launch {
            try {
                oauthManager.logout()
                _uiState.value = OAuthUiState.Idle
                Log.i(TAG, "logout: 登出成功")
            } catch (e: Exception) {
                Log.e(TAG, "logout: 登出失败: ${e.message}")
                // 即使后端调用失败，也清除本地状态
                _uiState.value = OAuthUiState.Idle
            }
        }
    }

    /**
     * 将状态重置为 Idle。
     * 在错误提示被用户查看后调用。
     */
    fun resetState() {
        if (_uiState.value is OAuthUiState.Error) {
            _uiState.value = OAuthUiState.Idle
        }
    }

    /**
     * 处理 Google Sign-In 的 Activity 结果。
     * 在调用方的 onActivityResult 中调用。
     */
    fun handleGoogleSignInResult(requestCode: Int, resultCode: Int, data: Intent?) {
        OAuthManager.handleGoogleSignInResult(requestCode, resultCode, data)
    }
}

/**
 * OAuth 登录 UI 状态密封类。
 */
sealed class OAuthUiState {
    /** 初始状态 */
    object Idle : OAuthUiState()

    /** 登录中 */
    object Loading : OAuthUiState()

    /**
     * 登录成功
     * @param user 登录用户信息
     */
    data class Success(val user: OAuthUser) : OAuthUiState()

    /**
     * 登录失败
     * @param message 错误信息，可用于显示 Snackbar
     */
    data class Error(val message: String) : OAuthUiState()
}
