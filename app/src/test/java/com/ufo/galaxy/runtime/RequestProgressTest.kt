package com.ufo.galaxy.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 手机只显示**这台手机自己那件请求**的进度,不显示电脑的三态(V2 `core/cross_device_sync.tell_origin_device`)。
 */
class RequestProgressTest {

    @Test
    fun `request progress frames drive the phase`() {
        assertEquals(PhaseStateMachine.Phase.LIMINAL, RequestProgress.phaseOf("phase", "liminal", "request_progress"))
        assertEquals(PhaseStateMachine.Phase.MANIFEST, RequestProgress.phaseOf("phase", "MANIFEST", "request_progress"))
        assertEquals(PhaseStateMachine.Phase.SILENT, RequestProgress.phaseOf("phase", "silent", "request_progress"))
    }

    @Test
    fun `the desktop tri-state is not shown, whatever an old V2 marks it as`() {
        for (syncType in listOf("cross_device_broadcast", "cross_device_initial_sync", "cross_device_reconnect_sync", "", null)) {
            assertNull(syncType.toString(), RequestProgress.phaseOf("phase", "manifest", syncType))
        }
    }

    @Test
    fun `other categories and unknown phases are not progress`() {
        assertNull(RequestProgress.phaseOf("task", "liminal", "request_progress"))
        assertNull(RequestProgress.phaseOf("phase", "thinking", "request_progress"))
    }

    @Test
    fun `the main screen says what the agent is doing with this phone's request, and nothing when idle`() {
        assertNull(RequestProgress.label(PhaseStateMachine.Phase.SILENT))
        assertEquals("智能体在想你这件事", RequestProgress.label(PhaseStateMachine.Phase.LIMINAL))
        assertEquals("智能体在做你这件事", RequestProgress.label(PhaseStateMachine.Phase.MANIFEST))
    }

    @Test
    fun `the marker matches V2`() {
        assertEquals("request_progress", RequestProgress.SYNC_TYPE)
    }
}
