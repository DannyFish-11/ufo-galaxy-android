package com.ufo.galaxy.transport

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Kotlin 侧 mesh 参与体：让手机成为 AODV 多跳网络里的**中继/可达节点**，
 * 而不只是网关的客户端。
 *
 * 信封与 V2 `core/node_communication.Message` 逐字段对齐（message_type ∈
 * data_request / data_response / rreq / rrep），线协议复用 [TcpFrameCodec] ——
 * V2 侧的 tcp_adapter 信封桥（同端口分流）保证两边只各开一个端口。
 *
 * 语义与 V2 mesh 适配器一致：数据帧逐跳同步中继（等到下游回执再原路回传，
 * success 是端到端结果）；RREQ 洪泛查重 + 逆向路由；RREP 沿逆向路由回传并
 * 记正向路由，转发均递减 TTL。
 */
class MeshRelayNode(
    val nodeId: String,
    private val onDeliver: (aip: JSONObject, meta: JSONObject) -> Unit,
) {
    companion object {
        private const val TAG = "MeshRelayNode"
        private const val ROUTE_TTL_MS = 300_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val RESPONSE_TIMEOUT_MS = 15_000
        private const val SEEN_MAX = 2048
    }

    data class Route(val nextHop: String, val hopCount: Int, val seq: Int, val expireAt: Long)

    private val neighbors = ConcurrentHashMap<String, Pair<String, Int>>() // nodeId → host:port
    private val routes = ConcurrentHashMap<String, Route>()
    private val seen: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private var seq = 0

    fun addNeighbor(id: String, host: String, port: Int) {
        if (id != nodeId) neighbors[id] = host to port
    }

    fun removeNeighbor(id: String) {
        neighbors.remove(id)
    }

    fun neighborIds(): Set<String> = neighbors.keys

    /**
     * 处理一帧 mesh 信封。数据帧返回应答帧（原连接写回），控制帧返回 null。
     */
    fun processFrame(msg: JSONObject): JSONObject? {
        return when (msg.optString("message_type")) {
            "data_request" -> handleData(msg)
            "rreq" -> { handleRreq(msg); null }
            "rrep" -> { handleRrep(msg); null }
            else -> null
        }
    }

    /**
     * 以本机为源发送一条 AIP 消息到 [target]（与 V2 mesh send 同款三级：
     * 直连邻居 → 路由表 → 现场 RREQ 发现）。返回端到端结果。
     */
    fun send(aip: JSONObject, target: String, discoveryTimeoutMs: Long = 2_000): JSONObject {
        var nextHop = resolveNextHop(target)
        if (nextHop == null) {
            discoverRoute(target, discoveryTimeoutMs)
            nextHop = resolveNextHop(target)
        }
        if (nextHop == null) {
            return JSONObject().put("success", false).put("error", "mesh: no route to '$target'")
        }
        val frame = JSONObject()
            .put("message_type", "data_request")
            .put("source_id", nodeId)
            .put("target_id", target)
            .put("payload", JSONObject().put("aip", aip).put("path", JSONArray(listOf(nodeId))))
            .put("message_id", UUID.randomUUID().toString())
            .put("timestamp", System.currentTimeMillis() / 1000.0)
            .put("ttl", 10)
        val resp = sendFrameAwait(nextHop, frame)
            ?: return JSONObject().put("success", false).put("error", "mesh send via '$nextHop' failed")
        return resp.optJSONObject("payload") ?: JSONObject().put("success", false)
    }

    private fun discoverRoute(target: String, timeoutMs: Long) {
        seq += 1
        val rreq = JSONObject()
            .put("message_type", "rreq")
            .put("source_id", nodeId)
            .put("target_id", "*")
            .put("payload", JSONObject().put("originator", nodeId).put("target", target)
                .put("originator_seq", seq).put("hop_count", 0).put("sender_id", nodeId))
            .put("message_id", UUID.randomUUID().toString())
            .put("timestamp", System.currentTimeMillis() / 1000.0)
            .put("ttl", 10)
        markSeen(rreq.optString("message_id"))
        for ((nid, _) in neighbors) fireFrame(nid, rreq)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (routeFor(target) != null) return
            Thread.sleep(50)
        }
    }

    // -- 数据面 --------------------------------------------------------------

    private fun handleData(msg: JSONObject): JSONObject {
        val payload = msg.optJSONObject("payload") ?: JSONObject()
        val path = payload.optJSONArray("path") ?: JSONArray()
        val pathList = (0 until path.length()).map { path.optString(it) }
        val target = msg.optString("target_id")
        val ttl = msg.optInt("ttl", 10)

        if (target == nodeId) {
            val fullPath = JSONArray(pathList + nodeId)
            val meta = JSONObject().put("src", msg.optString("source_id")).put("path", fullPath)
                .put("hops", pathList.size)
            return try {
                onDeliver(payload.optJSONObject("aip") ?: JSONObject(), meta)
                resultFrame(msg, JSONObject().put("success", true).put("src", msg.optString("source_id"))
                    .put("path", fullPath).put("hops", pathList.size))
            } catch (e: Exception) {
                resultFrame(msg, JSONObject().put("success", false).put("error", "handler error: ${e.message}"))
            }
        }
        if (ttl <= 1 || pathList.contains(nodeId)) {
            return resultFrame(msg, JSONObject().put("success", false).put("error", "mesh: ttl exhausted or loop"))
        }
        val nextHop = resolveNextHop(target)
            ?: return resultFrame(msg, JSONObject().put("success", false).put("error", "mesh relay: no route to '$target'"))
        val fwd = JSONObject(msg.toString())
        fwd.put("ttl", ttl - 1)
        fwd.getJSONObject("payload").put("path", JSONArray(pathList + nodeId))
        val downstream = sendFrameAwait(nextHop, fwd)
            ?: return resultFrame(msg, JSONObject().put("success", false).put("error", "mesh relay via '$nextHop' failed"))
        // 下游应答的 payload 原样回传(端到端结果不是「发出去了」)
        return resultFrame(msg, downstream.optJSONObject("payload") ?: JSONObject().put("success", false))
    }

    private fun resultFrame(req: JSONObject, result: JSONObject): JSONObject = JSONObject()
        .put("message_type", "data_response")
        .put("source_id", nodeId)
        .put("target_id", req.optString("source_id"))
        .put("payload", result)
        .put("message_id", UUID.randomUUID().toString())
        .put("timestamp", System.currentTimeMillis() / 1000.0)
        .put("ttl", 10)

    // -- 控制面：AODV ---------------------------------------------------------

    private fun handleRreq(msg: JSONObject) {
        if (!markSeen(msg.optString("message_id"))) return
        val p = msg.optJSONObject("payload") ?: return
        val originator = p.optString("originator")
        val target = p.optString("target")
        val sender = p.optString("sender_id")
        val hopCount = p.optInt("hop_count", 0) + 1
        if (originator.isEmpty() || target.isEmpty() || sender.isEmpty()) return
        if (neighbors.containsKey(sender) && originator != nodeId) {
            addRoute(originator, sender, hopCount, p.optInt("originator_seq", 0))
        }
        if (target == nodeId) {
            sendRrep(originator, target, 0)
            return
        }
        routeFor(target)?.let {
            sendRrep(originator, target, it.hopCount)
            return
        }
        val ttl = msg.optInt("ttl", 10)
        if (ttl > 1) {
            val fwd = JSONObject(msg.toString())
            fwd.put("ttl", ttl - 1)
            fwd.getJSONObject("payload").put("hop_count", hopCount).put("sender_id", nodeId)
            for ((nid, _) in neighbors) if (nid != sender) fireFrame(nid, fwd)
        }
    }

    private fun sendRrep(originator: String, target: String, hopCount: Int) {
        val rrep = JSONObject()
            .put("message_type", "rrep")
            .put("source_id", nodeId)
            .put("target_id", originator)
            .put("payload", JSONObject().put("originator", originator).put("target", target)
                .put("hop_count", hopCount).put("sender_id", nodeId))
            .put("message_id", UUID.randomUUID().toString())
            .put("timestamp", System.currentTimeMillis() / 1000.0)
            .put("ttl", 10)
        forwardRrep(rrep)
    }

    private fun handleRrep(msg: JSONObject) {
        val p = msg.optJSONObject("payload") ?: return
        val target = p.optString("target")
        val sender = p.optString("sender_id")
        val hopCount = p.optInt("hop_count", 0) + 1
        if (neighbors.containsKey(sender) && target.isNotEmpty() && target != nodeId) {
            addRoute(target, sender, hopCount, 0)
        }
        if (p.optString("originator") != nodeId) {
            val ttl = msg.optInt("ttl", 10)
            if (ttl <= 1) return
            val fwd = JSONObject(msg.toString())
            fwd.put("ttl", ttl - 1)
            fwd.getJSONObject("payload").put("hop_count", hopCount).put("sender_id", nodeId)
            forwardRrep(fwd)
        }
    }

    private fun forwardRrep(rrep: JSONObject) {
        val originator = rrep.optJSONObject("payload")?.optString("originator") ?: return
        val nh = if (neighbors.containsKey(originator)) originator else routeFor(originator)?.nextHop
        if (nh == null || !neighbors.containsKey(nh)) {
            Log.d(TAG, "RREP 无逆向路由可回 $originator")
            return
        }
        fireFrame(nh, rrep)
    }

    // -- 路由表 / 查重 -------------------------------------------------------

    private fun addRoute(dest: String, nextHop: String, hopCount: Int, seqNum: Int) {
        val existing = routes[dest]
        if (existing != null && existing.expireAt > System.currentTimeMillis() &&
            (existing.seq > seqNum || (existing.seq == seqNum && existing.hopCount <= hopCount))
        ) return
        routes[dest] = Route(nextHop, hopCount, seqNum, System.currentTimeMillis() + ROUTE_TTL_MS)
    }

    private fun routeFor(dest: String): Route? {
        val r = routes[dest] ?: return null
        if (r.expireAt < System.currentTimeMillis() || !neighbors.containsKey(r.nextHop)) {
            routes.remove(dest)
            return null
        }
        return r
    }

    private fun resolveNextHop(target: String): String? =
        if (neighbors.containsKey(target)) target else routeFor(target)?.nextHop

    private fun markSeen(id: String): Boolean {
        if (id.isEmpty() || seen.contains(id)) return false
        synchronized(seen) {
            if (seen.size >= SEEN_MAX) {
                val it = seen.iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }
            seen.add(id)
        }
        return true
    }

    // -- 帧收发 --------------------------------------------------------------

    /** 同步发送并等待应答帧（数据帧逐跳中继用）。失败返回 null。 */
    private fun sendFrameAwait(neighborId: String, frame: JSONObject): JSONObject? {
        val (host, port) = neighbors[neighborId] ?: return null
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                s.soTimeout = RESPONSE_TIMEOUT_MS
                val out = DataOutputStream(s.getOutputStream())
                out.write(TcpFrameCodec.encode(frame.toString()))
                out.flush()
                TcpFrameCodec.readFrame(DataInputStream(s.getInputStream()))?.let { JSONObject(it) }
            }
        } catch (e: Exception) {
            Log.d(TAG, "mesh 数据帧到 $neighborId 失败: ${e.message}")
            null
        }
    }

    /** 控制帧单向投递（洪泛允许部分失败）。 */
    private fun fireFrame(neighborId: String, frame: JSONObject) {
        val (host, port) = neighbors[neighborId] ?: return
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                val out = DataOutputStream(s.getOutputStream())
                out.write(TcpFrameCodec.encode(frame.toString()))
                out.flush()
            }
        } catch (e: Exception) {
            Log.d(TAG, "mesh 控制帧到 $neighborId 失败: ${e.message}")
        }
    }
}
