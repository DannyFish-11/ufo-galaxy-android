package com.ufo.galaxy.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessRecoveryTest {

    @Test
    fun `no recovery issues when all readiness checks pass`() {
        assertTrue(
            readinessRecoveryIssues(
                modelReady = true,
                accessibilityReady = true,
                overlayReady = true
            ).isEmpty()
        )
    }

    @Test
    fun `model readiness failure opens diagnostics`() {
        val issues = readinessRecoveryIssues(
            modelReady = false,
            accessibilityReady = true,
            overlayReady = true
        )

        assertEquals(1, issues.size)
        assertEquals("本地模型未就绪", issues.single().label)
        assertEquals("查看诊断", issues.single().actionLabel)
        assertEquals(ReadinessRecoveryAction.OPEN_MODEL_DIAGNOSTICS, issues.single().action)
    }

    @Test
    fun `permission readiness failures expose settings actions`() {
        val issues = readinessRecoveryIssues(
            modelReady = true,
            accessibilityReady = false,
            overlayReady = false
        )

        assertEquals(
            listOf(
                ReadinessRecoveryAction.OPEN_ACCESSIBILITY_SETTINGS,
                ReadinessRecoveryAction.OPEN_OVERLAY_SETTINGS
            ),
            issues.map { it.action }
        )
    }

    @Test
    fun `all failures keep stable banner order`() {
        val issues = readinessRecoveryIssues(
            modelReady = false,
            accessibilityReady = false,
            overlayReady = false
        )

        assertEquals(
            listOf("本地模型未就绪", "无障碍服务未启用", "悬浮窗权限未授予"),
            issues.map { it.label }
        )
    }
}
