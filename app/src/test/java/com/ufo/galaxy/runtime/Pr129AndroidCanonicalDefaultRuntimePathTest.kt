package com.ufo.galaxy.runtime

import com.ufo.galaxy.data.InMemoryAppSettings
import com.ufo.galaxy.data.SharedPrefsAppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Pr129AndroidCanonicalDefaultRuntimePathTest {

    @Test
    fun `default path behavior change uses canonical cross-device rollout defaults`() {
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = SharedPrefsAppSettings.DEFAULT_CROSS_DEVICE_ENABLED,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = SharedPrefsAppSettings.DEFAULT_FALLBACK_TO_LOCAL_ALLOWED,
            goalExecutionAllowed = SharedPrefsAppSettings.DEFAULT_GOAL_EXECUTION_ENABLED
        )
        assertTrue(rollout.crossDeviceAllowed)
        assertTrue(rollout.goalExecutionAllowed)
        assertTrue(rollout.delegatedExecutionAllowed)
        assertFalse(rollout.fallbackToLocalAllowed)
    }

    @Test
    fun `default capability activation makes canonical dispatch eligible`() {
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = SharedPrefsAppSettings.DEFAULT_CROSS_DEVICE_ENABLED,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = SharedPrefsAppSettings.DEFAULT_FALLBACK_TO_LOCAL_ALLOWED,
            goalExecutionAllowed = SharedPrefsAppSettings.DEFAULT_GOAL_EXECUTION_ENABLED
        )
        val result = RuntimeDispatchReadinessCoordinator.resolve(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = AttachedRuntimeSession.create(hostId = "h", deviceId = "d"),
            rollout = rollout
        )
        assertTrue(result.isEligible)
        assertEquals(DispatchPathMode.CANONICAL, result.recommendedPathMode)
    }

    @Test
    fun `canonical-vs-compat distinction keeps fallback path optional by default`() {
        val rollout = RolloutControlSnapshot(
            crossDeviceAllowed = SharedPrefsAppSettings.DEFAULT_CROSS_DEVICE_ENABLED,
            delegatedExecutionAllowed = true,
            fallbackToLocalAllowed = SharedPrefsAppSettings.DEFAULT_FALLBACK_TO_LOCAL_ALLOWED,
            goalExecutionAllowed = SharedPrefsAppSettings.DEFAULT_GOAL_EXECUTION_ENABLED
        )
        val paths = CanonicalDispatchChain.resolveEligiblePathsForState(
            runtimeState = RuntimeController.RuntimeState.Active,
            attachedSession = AttachedRuntimeSession.create(hostId = "h", deviceId = "d"),
            rollout = rollout
        )
        val modes = paths.map { it.pathMode }.toSet()
        assertTrue(DispatchPathMode.CANONICAL in modes)
        assertFalse(DispatchPathMode.FALLBACK in modes)
    }

    @Test
    fun `user-visible default output reports cross-device canonical mode`() {
        val metadata = InMemoryAppSettings(
            crossDeviceEnabled = SharedPrefsAppSettings.DEFAULT_CROSS_DEVICE_ENABLED,
            goalExecutionEnabled = SharedPrefsAppSettings.DEFAULT_GOAL_EXECUTION_ENABLED,
            fallbackToLocalAllowed = SharedPrefsAppSettings.DEFAULT_FALLBACK_TO_LOCAL_ALLOWED
        ).toMetadataMap()
        assertEquals(true, metadata["cross_device_enabled"])
        assertEquals(true, metadata["goal_execution_enabled"])
        assertEquals("cross_device", metadata["mode_state"])
        assertEquals("cross_device_degraded", metadata[LocalExecutionModeGate.KEY_EXECUTION_MODE_STATE])
    }
}
