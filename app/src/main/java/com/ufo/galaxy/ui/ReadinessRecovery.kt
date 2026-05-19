package com.ufo.galaxy.ui

enum class ReadinessRecoveryAction {
    OPEN_MODEL_DIAGNOSTICS,
    OPEN_ACCESSIBILITY_SETTINGS,
    OPEN_OVERLAY_SETTINGS
}

data class ReadinessRecoveryIssue(
    val label: String,
    val actionLabel: String,
    val action: ReadinessRecoveryAction
)

fun readinessRecoveryIssues(
    modelReady: Boolean,
    accessibilityReady: Boolean,
    overlayReady: Boolean
): List<ReadinessRecoveryIssue> = buildList {
    if (!modelReady) {
        add(
            ReadinessRecoveryIssue(
                label = "本地模型未就绪",
                actionLabel = "查看诊断",
                action = ReadinessRecoveryAction.OPEN_MODEL_DIAGNOSTICS
            )
        )
    }
    if (!accessibilityReady) {
        add(
            ReadinessRecoveryIssue(
                label = "无障碍服务未启用",
                actionLabel = "开启无障碍",
                action = ReadinessRecoveryAction.OPEN_ACCESSIBILITY_SETTINGS
            )
        )
    }
    if (!overlayReady) {
        add(
            ReadinessRecoveryIssue(
                label = "悬浮窗权限未授予",
                actionLabel = "授权悬浮窗",
                action = ReadinessRecoveryAction.OPEN_OVERLAY_SETTINGS
            )
        )
    }
}
