package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidMeshLifecycleEmissionChainTest {

    @Test
    fun `successful lifecycle transitions close mesh session`() {
        var state = AndroidMeshLifecycleEmissionChain.create(meshId = "mesh-1", taskId = "task-1")
        state = AndroidMeshLifecycleEmissionChain.onJoin(state, emitted = true)
        state = AndroidMeshLifecycleEmissionChain.onResult(state, emitted = true)
        state = AndroidMeshLifecycleEmissionChain.onLeave(state, emitted = true, reason = "task_complete")

        assertEquals(AndroidMeshLifecycleEmissionChain.Phase.CLOSED, state.phase)
        assertTrue(state.joinAttempted)
        assertTrue(state.joinEmitted)
        assertTrue(state.resultAttempted)
        assertTrue(state.resultEmitted)
        assertTrue(state.leaveAttempted)
        assertTrue(state.leaveEmitted)
        assertFalse(state.shouldAttemptLeave)
    }

    @Test
    fun `join attempt still requires leave attempt even when join emission fails`() {
        var state = AndroidMeshLifecycleEmissionChain.create(meshId = "mesh-2", taskId = "task-2")
        state = AndroidMeshLifecycleEmissionChain.onJoin(state, emitted = false)

        assertTrue(state.joinAttempted)
        assertFalse(state.joinEmitted)
        assertTrue(state.shouldAttemptLeave)
    }

    @Test
    fun `failed leave keeps lifecycle in leave attempted phase for diagnosability`() {
        var state = AndroidMeshLifecycleEmissionChain.create(meshId = "mesh-3", taskId = "task-3")
        state = AndroidMeshLifecycleEmissionChain.onJoin(state, emitted = true)
        state = AndroidMeshLifecycleEmissionChain.onLeave(state, emitted = false, reason = "error")

        assertEquals(AndroidMeshLifecycleEmissionChain.Phase.LEAVE_ATTEMPTED, state.phase)
        assertTrue(state.leaveAttempted)
        assertFalse(state.leaveEmitted)
        assertEquals("error", state.leaveReason)
    }
}
