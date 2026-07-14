package com.ufo.galaxy.assist

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 默认助理角色(ROLE_ASSISTANT)申请助手。
 *
 * 让用户把 Galaxy 设为系统默认助理:成功后长按电源/侧滑手势唤起 Galaxy。
 * ROLE_ASSISTANT 需 API 29+;低版本如实返回不可用(用户可去系统设置手动
 * 选默认助理 App)。诚实:不假装已成为助理,一切以系统 isRoleHeld 为准。
 */
object AssistantRoleHelper {
    private const val TAG = "AssistantRoleHelper"

    /** 本机是否支持"默认助理"角色申请(API 29+ 且系统提供该角色)。 */
    fun isRoleAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return false
        return runCatching { rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    }

    /** Galaxy 当前是否已是默认助理。 */
    fun isRoleHeld(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return false
        return runCatching { rm.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    }

    /**
     * 构造"申请成为默认助理"的系统弹窗 Intent;不可用/已持有时返回 null。
     * 调用方用 startActivityForResult 承接结果。
     */
    fun createRequestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (isRoleHeld(context)) return null
        val rm = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager ?: return null
        return runCatching {
            if (!rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) null
            else rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        }.getOrElse {
            Log.w(TAG, "创建助理角色申请 Intent 失败: $it")
            null
        }
    }
}
