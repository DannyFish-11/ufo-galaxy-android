package com.ufo.galaxy.transport

import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 手机侧监听服务：让本机成为 LAN 里**可被直连**的节点（网关或对端手机可主动
 * 连入），配合 [LanServiceAnnouncer] 的 mDNS 广播被 V2 lan_discovery 发现。
 *
 * 与 V2 tcp_adapter 相同的「一个端口说两种帧」分流：
 * - 帧里有 `message_type`（mesh 信封）→ [MeshRelayNode.processFrame]，应答原路写回；
 * - 否则为普通 AIP JSON → [onMessage]（与 WS 入站同一条消息路由）。
 *
 * 纯 JVM（ServerSocket + 线程），行为可在普通单元测试里真实钉住。
 */
class TcpDirectServer(
    private val onMessage: (String) -> Unit,
    private val meshRelay: MeshRelayNode? = null,
    private val maxFrameBytes: Int = TcpFrameCodec.DEFAULT_MAX_FRAME_BYTES,
) : AutoCloseable {

    companion object {
        private const val TAG = "TcpDirectServer"
    }

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    val localPort: Int get() = serverSocket?.localPort ?: -1

    /** 启动监听；port=0 用临时端口。返回实际端口。 */
    fun start(port: Int = 0): Int {
        if (running.get()) return localPort
        val server = ServerSocket(port)
        serverSocket = server
        running.set(true)
        thread(name = "tcp-direct-server-accept", isDaemon = true) {
            while (running.get()) {
                try {
                    val client = server.accept()
                    thread(name = "tcp-direct-server-conn", isDaemon = true) { handleConn(client) }
                } catch (e: Exception) {
                    if (running.get()) Log.d(TAG, "accept 异常: ${e.message}")
                }
            }
        }
        Log.i(TAG, "TCP 服务端监听 :${server.localPort}")
        return server.localPort
    }

    override fun close() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun handleConn(socket: Socket) {
        try {
            socket.use { s ->
                val input = DataInputStream(s.getInputStream())
                val output = DataOutputStream(s.getOutputStream())
                while (running.get()) {
                    val json = TcpFrameCodec.readFrame(input, maxFrameBytes) ?: break
                    val obj = try {
                        JSONObject(json)
                    } catch (e: Exception) {
                        Log.d(TAG, "非 JSON 帧丢弃: ${e.message}")
                        continue
                    }
                    if (obj.has("message_type") && meshRelay != null) {
                        try {
                            meshRelay.processFrame(obj)?.let { resp ->
                                output.write(TcpFrameCodec.encode(resp.toString(), maxFrameBytes))
                                output.flush()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "mesh 信封处理失败(连接保持): ${e.message}")
                        }
                    } else {
                        try {
                            onMessage(json)
                        } catch (e: Exception) {
                            Log.w(TAG, "入站消息处理失败(连接保持): ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "连接结束: ${e.message}")
        }
    }
}
