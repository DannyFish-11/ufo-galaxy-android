package com.ufo.galaxy.transport

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException

/**
 * V2 `core/adapters/tcp_adapter.py` 线协议的 Kotlin 侧编解码器。
 *
 * 协议（与 V2 完全一致，一字节都不能差）：
 *   帧 = 4 字节大端(big-endian)长度前缀 + UTF-8 编码的 JSON 载荷。
 *
 * 纯 JVM 实现（不碰任何 Android API），因此帧的正确性可以在普通单元测试里
 * 直接钉住，不需要设备或模拟器。
 */
object TcpFrameCodec {

    /** 长度前缀字节数。 */
    const val HEADER_BYTES = 4

    /** 默认单帧上限，对齐 V2 侧 GALAXY_MAX_MESSAGE_SIZE 的默认值（10 MB）。 */
    const val DEFAULT_MAX_FRAME_BYTES = 10 * 1024 * 1024

    /**
     * 把一条 JSON 字符串编码成一帧字节。
     *
     * @throws IOException 载荷超过 [maxFrameBytes]（超限帧对端会直接断连，
     *         与其发出去被断，不如在源头如实拒绝）。
     */
    @JvmOverloads
    fun encode(json: String, maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES): ByteArray {
        val payload = json.toByteArray(Charsets.UTF_8)
        if (payload.size > maxFrameBytes) {
            throw IOException("frame too large: ${payload.size} > $maxFrameBytes")
        }
        val out = ByteArray(HEADER_BYTES + payload.size)
        out[0] = (payload.size ushr 24).toByte()
        out[1] = (payload.size ushr 16).toByte()
        out[2] = (payload.size ushr 8).toByte()
        out[3] = payload.size.toByte()
        payload.copyInto(out, HEADER_BYTES)
        return out
    }

    /**
     * 从流里读出一帧的 JSON 字符串。
     *
     * @return 正常帧返回 JSON 字符串；对端正常关闭（帧边界处 EOF）返回 null。
     * @throws IOException 帧长非法（负数或超过 [maxFrameBytes]）或帧中途断流。
     */
    @JvmOverloads
    fun readFrame(input: DataInputStream, maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES): String? {
        val length = try {
            input.readInt() // DataInputStream.readInt 就是 4 字节大端
        } catch (e: EOFException) {
            return null // 帧边界处的 EOF = 对端正常关闭
        }
        if (length < 0 || length > maxFrameBytes) {
            throw IOException("invalid frame length: $length (max $maxFrameBytes)")
        }
        val payload = ByteArray(length)
        input.readFully(payload) // 帧中途 EOF 会抛 EOFException（IOException 子类）—— 那是异常断流,该抛
        return String(payload, Charsets.UTF_8)
    }
}
