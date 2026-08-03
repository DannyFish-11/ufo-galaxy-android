package com.ufo.galaxy.transport

import android.util.Log
import com.ufo.galaxy.network.GatewayClient
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * LAN TCP 直连传输（阶段 1：断网自组网的设备侧直连通道）。
 *
 * 与 V2 网关 `core/adapters/tcp_adapter.py` 的 TCP P2P 服务（mDNS 名
 * `_galaxy-aip3._tcp.`，默认端口 19421）直接对话，线协议见 [TcpFrameCodec]。
 *
 * 融入而非替代：实现的是与 WebSocket 适配器相同的 [GatewayClient] 接口，
 * 由 [AipTransportManager] 以 `"tcp"` 身份统一调度 —— 不是一条平行通道。
 * 中心网关的 WS 断了、但 LAN 里能直接摸到网关（或未来的对等节点）时，
 * 消息就还有路可走。
 *
 * 纯 JVM 实现（java.net.Socket + 线程），连接/收发行为可以在普通单元测试里
 * 用本地 ServerSocket 真实钉住。
 */
class TcpDirectClient(
    private val deviceId: String,
    private val maxFrameBytes: Int = TcpFrameCodec.DEFAULT_MAX_FRAME_BYTES,
) : GatewayClient, AutoCloseable {

    companion object {
        private const val TAG = "TcpDirectClient"
        private const val CONNECT_TIMEOUT_MS = 5_000
    }

    private val lock = Any()
    private var socket: Socket? = null
    private var output: DataOutputStream? = null
    private var readerThread: Thread? = null
    private val running = AtomicBoolean(false)

    /** 入站消息回调（网关经此连接下发的 AIP JSON）。 */
    @Volatile
    var onMessage: ((String) -> Unit)? = null

    /** 连接断开回调（读线程退出时触发一次）。 */
    @Volatile
    var onDisconnected: (() -> Unit)? = null

    /**
     * 建立到 [host]:[port] 的连接并发送 hello 心跳（携带 device_id，V2 的
     * TCP 服务端靠它把这条连接登记成本设备的 peer）。
     *
     * @return 连接 + hello 都成功才为 true；失败时如实 false，不留半开状态。
     */
    fun connect(host: String, port: Int): Boolean {
        synchronized(lock) {
            if (isConnected()) return true
            closeInternal() // 清掉上一次的残骸
            return try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
                socket = s
                output = DataOutputStream(s.getOutputStream())
                running.set(true)
                startReader(s)
                // hello：让服务端把连接与 device_id 关联（对齐 V2 _handle_incoming
                // 读 message["device_id"] 登记 peer 的行为）
                val hello = JSONObject()
                    .put("type", "heartbeat")
                    .put("device_id", deviceId)
                    .put("transport", "tcp")
                writeFrame(hello.toString())
            } catch (e: IOException) {
                Log.w(TAG, "TCP connect $host:$port failed: ${e.message}")
                closeInternal()
                false
            }
        }
    }

    override fun isConnected(): Boolean {
        val s = socket ?: return false
        return running.get() && s.isConnected && !s.isClosed
    }

    override fun sendJson(json: String): Boolean {
        synchronized(lock) {
            if (!isConnected()) return false
            return try {
                writeFrame(json)
            } catch (e: IOException) {
                Log.w(TAG, "TCP send failed: ${e.message}")
                closeInternal()
                false
            }
        }
    }

    override fun close() {
        synchronized(lock) { closeInternal() }
    }

    // -- 内部 ---------------------------------------------------------------

    /** 调用方须持有 [lock]（或在读线程退出路径上）。 */
    private fun writeFrame(json: String): Boolean {
        val out = output ?: return false
        out.write(TcpFrameCodec.encode(json, maxFrameBytes))
        out.flush()
        return true
    }

    private fun startReader(s: Socket) {
        readerThread = thread(name = "tcp-direct-reader", isDaemon = true) {
            try {
                val input = DataInputStream(s.getInputStream())
                while (running.get()) {
                    val json = TcpFrameCodec.readFrame(input, maxFrameBytes) ?: break
                    try {
                        onMessage?.invoke(json)
                    } catch (e: Exception) {
                        Log.w(TAG, "onMessage handler error: ${e.message}")
                    }
                }
            } catch (e: IOException) {
                if (running.get()) Log.d(TAG, "TCP reader terminated: ${e.message}")
            } finally {
                val wasRunning = running.getAndSet(false)
                try {
                    s.close()
                } catch (_: IOException) {
                }
                if (wasRunning) onDisconnected?.invoke()
            }
        }
    }

    private fun closeInternal() {
        running.set(false)
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        output = null
        readerThread = null
    }
}
