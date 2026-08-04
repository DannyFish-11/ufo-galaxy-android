package com.ufo.galaxy.protocol

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.ufo.galaxy.shared.protocol.MsgType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 信封双定义的线格式一致性契约钉。
 *
 * 现状（协议排查定论）：canonical 信封是 shared-protocol 的
 * [com.ufo.galaxy.shared.protocol.AipMessage]（kotlinx 系，Wear OS 直接使用）；
 * 主 App 保留 gson 系门面 [AipMessage]（typed 调用面广、迁移不划算）。
 * 两个类**必须**产出逐键一致的线格式 —— 本测试把这条约束固化：任何一侧
 * 增删字段、改名、改序列化注解，这里就红。
 */
class EnvelopeWireParityTest {

    @Test
    fun `gson 门面与 shared canonical 信封线格式逐键一致`() {
        val payloadJson = """{"k":"v","n":7}"""
        val ts = 1_700_000_000_000L

        // gson 系(主 App)
        val gsonMsg = AipMessage(
            type = MsgType.TASK_RESULT,
            payload = JsonParser.parseString(payloadJson),
            correlation_id = "corr-1",
            timestamp = ts,
            session_id = "sess-1",
            device_id = "dev-1",
            trace_id = "trace-1",
            route_mode = "cross_device",
            runtime_session_id = "rs-1",
            idempotency_key = "idem-1",
            source_runtime_posture = "control_only",
            dispatch_trace_id = "dt-1",
            session_correlation_id = "sc-1",
        )
        val gsonWire = JsonParser.parseString(Gson().toJson(gsonMsg)).asJsonObject

        // kotlinx 系(shared canonical,Wear OS 同款)
        val sharedMsg = com.ufo.galaxy.shared.protocol.AipMessage(
            type = MsgType.TASK_RESULT,
            payload = com.ufo.galaxy.shared.protocol.AipMessage.DefaultJson.parseToJsonElement(payloadJson),
            correlationId = "corr-1",
            timestamp = ts,
            sessionId = "sess-1",
            deviceId = "dev-1",
            traceId = "trace-1",
            routeMode = "cross_device",
            runtimeSessionId = "rs-1",
            idempotencyKey = "idem-1",
            sourceRuntimePosture = "control_only",
            dispatchTraceId = "dt-1",
            sessionCorrelationId = "sc-1",
        )
        val sharedWire = JsonParser.parseString(
            com.ufo.galaxy.shared.protocol.AipMessage.toJson(sharedMsg)
        ).asJsonObject

        assertEquals(
            "两个信封的线格式键集漂移了 —— canonical 是 shared-protocol,请同步另一侧",
            sharedWire.keySet().sorted(),
            gsonWire.keySet().sorted(),
        )
        for (key in sharedWire.keySet()) {
            assertEquals("字段 '$key' 的线值不一致", sharedWire[key], gsonWire[key])
        }
    }
}
