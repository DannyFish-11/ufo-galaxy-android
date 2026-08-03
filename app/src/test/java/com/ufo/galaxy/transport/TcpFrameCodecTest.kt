package com.ufo.galaxy.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

/**
 * 帧编解码与 V2 `core/adapters/tcp_adapter.py` 线协议逐字节对齐的回归钉。
 *
 * V2 侧协议（tcp_adapter.send / _handle_incoming）：
 *   `len(payload).to_bytes(4, "big") + json.dumps(message).encode("utf-8")`
 * 这里不引用任何 Android API，纯 JVM 直接验证字节布局。
 */
class TcpFrameCodecTest {

    @Test
    fun `encode 产生 4 字节大端长度前缀 + UTF-8 载荷`() {
        val json = """{"type":"heartbeat","device_id":"dev1"}"""
        val frame = TcpFrameCodec.encode(json)
        val payload = json.toByteArray(Charsets.UTF_8)

        assertEquals(TcpFrameCodec.HEADER_BYTES + payload.size, frame.size)
        // 大端长度前缀 —— 与 Python int.to_bytes(4, "big") 一致
        val declared = ((frame[0].toInt() and 0xFF) shl 24) or
            ((frame[1].toInt() and 0xFF) shl 16) or
            ((frame[2].toInt() and 0xFF) shl 8) or
            (frame[3].toInt() and 0xFF)
        assertEquals(payload.size, declared)
        assertEquals(json, String(frame, TcpFrameCodec.HEADER_BYTES, payload.size, Charsets.UTF_8))
    }

    @Test
    fun `中文载荷按 UTF-8 字节数计长而不是字符数`() {
        val json = """{"msg":"多跳中继"}"""
        val frame = TcpFrameCodec.encode(json)
        val expectedBytes = json.toByteArray(Charsets.UTF_8).size
        assertEquals(expectedBytes, ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF))
        // 回读还原
        val decoded = TcpFrameCodec.readFrame(DataInputStream(ByteArrayInputStream(frame)))
        assertEquals(json, decoded)
    }

    @Test
    fun `roundtrip 连续多帧按序解出`() {
        val a = """{"seq":1}"""
        val b = """{"seq":2,"text":"第二帧"}"""
        val stream = DataInputStream(ByteArrayInputStream(TcpFrameCodec.encode(a) + TcpFrameCodec.encode(b)))
        assertEquals(a, TcpFrameCodec.readFrame(stream))
        assertEquals(b, TcpFrameCodec.readFrame(stream))
        assertNull("流耗尽应返回 null（正常关闭语义）", TcpFrameCodec.readFrame(stream))
    }

    @Test
    fun `超限帧在编码侧就被拒绝`() {
        assertThrows(IOException::class.java) {
            TcpFrameCodec.encode("x".repeat(64), maxFrameBytes = 16)
        }
    }

    @Test
    fun `声明长度超限的入站帧被拒绝而不是照单分配内存`() {
        val evil = byteArrayOf(0x7F, -1, -1, -1) // 声称 ~2GB
        assertThrows(IOException::class.java) {
            TcpFrameCodec.readFrame(DataInputStream(ByteArrayInputStream(evil)))
        }
    }

    @Test
    fun `帧中途断流按异常处理而不是静默返回半帧`() {
        val frame = TcpFrameCodec.encode("""{"k":"value"}""")
        val truncated = frame.copyOfRange(0, frame.size - 3)
        assertThrows(IOException::class.java) {
            TcpFrameCodec.readFrame(DataInputStream(ByteArrayInputStream(truncated)))
        }
    }
}
