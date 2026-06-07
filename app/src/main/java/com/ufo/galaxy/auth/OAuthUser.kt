package com.ufo.galaxy.auth

/**
 * OAuth 登录成功后的用户信息数据类
 *
 * 由后端在 OAuth 回调完成后返回，包含用户的基本身份信息和登录渠道。
 *
 * @param id       后端分配的用户唯一标识符
 * @param email    用户邮箱地址
 * @param name     用户显示名称
 * @param picture  用户头像 URL（可能为空）
 * @param provider 登录渠道标识："google" 或 "github"
 */
data class OAuthUser(
    val id: String,
    val email: String,
    val name: String,
    val picture: String?,
    val provider: String
) {
    companion object {
        /** 登录渠道：Google */
        const val PROVIDER_GOOGLE = "google"

        /** 登录渠道：GitHub */
        const val PROVIDER_GITHUB = "github"
    }

    /**
     * 返回用户显示名称的友好版本。
     * 如果 name 为空则回退到 email 前缀。
     */
    fun displayName(): String {
        return name.ifBlank { email.substringBefore("@") }
    }

    /**
     * 判断当前用户信息是否完整有效。
     */
    fun isValid(): Boolean {
        return id.isNotBlank() && email.isNotBlank() && (provider == PROVIDER_GOOGLE || provider == PROVIDER_GITHUB)
    }
}
