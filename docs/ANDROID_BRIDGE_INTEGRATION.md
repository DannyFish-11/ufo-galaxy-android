# Android Bridge Integration Guide

> **Note:** This document uses "Round N" labels (Round 5, Round 6, Round 7, Round 8) which are
> internal development sequence identifiers, not product version numbers. For the current
> canonical architecture description see [`docs/architecture.md`](architecture.md).

## Overview

This document describes the **Agent bridge & runtime takeover** feature in the UFO Galaxy
Android client. When the cross-device switch is **ON**, eligible tasks arriving via the
Gateway are delegated to **Agent Runtime / OpenClawd** through a dedicated bridge layer
instead of executing entirely on-device.

---

## Architecture

```
User Input
   │
   ▼
InputRouter ──crossDeviceEnabled=true──► Gateway (WS task_submit)
                                               │
                                   task_assign │ (downlink)
                                               ▼
                              GalaxyConnectionService
                                       │
                          require_local_agent=false? ─── YES (+ crossDevice ON) ──►
                          crossDeviceEnabled=true?                              AgentRuntimeBridge
                                       │                                            │
                               NO (local path)                            bridge_handoff (WS)
                                       │                                            │
                                       ▼                                  Agent Runtime / OpenClawd
                                 EdgeExecutor                                       │
                              (local AIP v3 loop)                       result callback (async)
                                       │
                                task_result (WS uplink)
```

---

## Handoff Contract

### When is a task eligible for bridge delegation?

A task_assign message is eligible for bridge delegation when **both** conditions hold:

| Condition | Value |
|-----------|-------|
| `AppSettings.crossDeviceEnabled` | `true` |
| `TaskAssignPayload.require_local_agent` | `false` |

If either condition is false the task is executed locally by `EdgeExecutor` (full backward compatibility).

### bridge_handoff message format

The `AgentRuntimeBridge` sends a `bridge_handoff` JSON message via the existing WebSocket
connection. All required fields are always present; optional fields are omitted when empty.

```json
{
  "type": "bridge_handoff",
  "trace_id": "<UUID>",
  "task_id": "<echo of task_assign.task_id>",
  "exec_mode": "remote",
  "route_mode": "cross_device",
  "goal": "<natural-language goal>",
  "capability": "task_execution",
  "session_id": "<optional UUID>",
  "context": { "locale": "zh-CN" },
  "constraints": ["no audio"]
}
```

### Required metadata fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | String | ✅ | Always `"bridge_handoff"`. |
| `trace_id` | String (UUID) | ✅ | End-to-end trace identifier. Generated once per task; propagated in every hop. Used for idempotency, log correlation, and callback routing. |
| `task_id` | String | ✅ | Echoed from `task_assign.task_id`. Used by the Gateway to match the callback. |
| `exec_mode` | String | ✅ | `"local"` / `"remote"` / `"both"`. Bridge delegation only occurs for `"remote"` and `"both"`. |
| `route_mode` | String | ✅ | `"cross_device"` when the bridge is active; `"local"` on the fallback path. |
| `goal` | String | ✅ | Natural-language objective forwarded to Agent Runtime. |
| `capability` | String | Optional | Capability name required for execution (e.g. `"task_execution"`). |
| `session_id` | String | Optional | Session-level identifier for grouping steps. |
| `context` | Object | Optional | Additional key-value context (e.g. `{ "locale": "zh-CN" }`). |
| `constraints` | Array | Optional | Natural-language constraint strings from the task_assign payload. |

### trace_id propagation in AIP v3 envelopes

From Round 5 onwards, `AipMessage` carries two new optional fields:

```kotlin
data class AipMessage(
    ...
    val trace_id: String? = null,  // end-to-end trace identifier
    val route_mode: String? = null // "local" or "cross_device"
)
```

These are populated in every `task_result` reply sent by `GalaxyConnectionService`:

```json
{
  "type": "task_result",
  "correlation_id": "<task_id>",
  "trace_id": "<same UUID as bridge_handoff>",
  "route_mode": "cross_device",
  "payload": { ... }
}
```

Consumers (Gateway, Agent Runtime, memory indexer) can use `trace_id` for full-chain
log correlation and `route_mode` to distinguish local from remote results.

### span_id for operation-level tracing (Round 7)

`AgentRuntimeBridge.HandoffRequest` now carries an optional `spanId` field.
When blank (the default), a new span is automatically started via `TraceContext.startSpan()`
and ended in a `finally` block.  To nest a handoff under an outer span, pass
the pre-existing span identifier:

```kotlin
val spanId = TraceContext.startSpan()
try {
    bridge.handoff(request.copy(spanId = spanId))
    // ... other operations in the same span ...
} finally {
    TraceContext.endSpan()
}
```

All bridge log events (`GALAXY:DISPATCHER:SELECT`, `GALAXY:BRIDGE:HANDOFF`, etc.)
include both `trace_id` and `span_id` so that distributed traces can be correlated
at both session and operation granularity.

### Server trace_id echo (Round 7)

When a downlink message carries a `trace_id`, the client adopts it via
`TraceContext.acceptServerTraceId(serverTraceId)`.  Subsequent uplink messages
and signaling frames echo the same ID, so server-side traces initiated by the
gateway are visible in client-side logs without any extra configuration.

```
Client generates trace_id="A"    → sends device_register with trace_id="A"
Server responds with trace_id="B" → client accepts "B"
Client sends task_result          → trace_id="B" (echoed)
```

---

## Fallback Behaviour

When cross-device is **OFF**, or when Agent Runtime is unreachable and all retries are
exhausted, the bridge falls back to local execution:

| Condition | Behaviour |
|-----------|-----------|
| `crossDeviceEnabled = false` | Local `EdgeExecutor` runs; no bridge message sent. |
| `exec_mode = "local"` | Bridge is skipped regardless of cross-device flag. |
| WS not connected | Bridge send fails; fallback to local after retries. |
| All retries exhausted | Fallback to local; error surfaced in logs and metrics. |

The fallback is **always explicit** — it is logged at `WARN` level and counted in
`MetricsRecorder.handoffFallbacks`. The app never crashes or hangs on fallback.

---

## Idempotency

The bridge maintains a `trace_id → HandoffResult` idempotency cache (bounded to 200
entries). Repeated calls with the same `trace_id` return the cached result immediately
without re-sending. This prevents double-processing if the Gateway re-delivers the
same task_assign message (e.g. after a brief WS disconnect).

---

## Retry & Backoff

| Parameter | Default | Description |
|-----------|---------|-------------|
| `DEFAULT_HANDOFF_TIMEOUT_MS` | 30 000 ms | Per-attempt timeout. |
| `MAX_RETRY_ATTEMPTS` | 3 | Total send attempts before fallback. |
| `RETRY_DELAYS_MS` | `[1 000, 2 000, 4 000]` ms | Exponential backoff between attempts. |

---

## Telemetry & Observability

### MetricsRecorder counters (added in Round 5)

| Counter | Description |
|---------|-------------|
| `handoffSuccesses` | Tasks successfully sent to Agent Runtime via bridge. |
| `handoffFailures` | Handoff attempts that exhausted all retries. |
| `handoffFallbacks` | Times execution fell back to local after a failed handoff. |

These counters appear in the periodic `[METRICS]` log line and in the JSON snapshot
posted to `AppSettings.metricsEndpoint` (if configured):

```
[METRICS] ws_reconnects=0 reg_failures=0 task_ok=5 task_fail=0
          handoff_ok=3 handoff_fail=1 handoff_fallback=1 uptime_ms=120000
```

### GalaxyLogger structured events (tag: `GALAXY:BRIDGE`)

| Event | When |
|-------|------|
| `handoff_skipped` | Bridge skipped (cross_device=OFF or exec_mode=local). |
| `handoff_retry` | Retry attempt scheduled. |
| `handoff_timeout` | Per-attempt timeout exceeded. |
| `handoff_error` | Send exception on an attempt. |
| `handoff_success` | Bridge message accepted. |
| `handoff_fallback` | All retries exhausted; falling back to local. |
| `handoff_idempotent` | Cached result returned (trace_id already processed). |

---

## Backward Compatibility

All changes are **additive and backward-compatible**:

- `AipMessage.trace_id` and `route_mode` default to `null` — existing consumers
  that do not read these fields are unaffected.
- When `crossDeviceEnabled = false`, the bridge is completely bypassed — existing
  behaviour is preserved end-to-end.
- `require_local_agent = true` in `task_assign` always forces local execution,
  regardless of the cross-device flag.
- No existing WS message types or payload schemas are changed.

---

## Related Files

| File | Role |
|------|------|
| `agent/AgentRuntimeBridge.kt` | Bridge implementation (handoff, idempotency, retry). |
| `protocol/AipModels.kt` | `AipMessage` — added `trace_id`, `route_mode`. |
| `observability/MetricsRecorder.kt` | Added `handoffSuccesses/Failures/Fallbacks` counters. |
| `service/GalaxyConnectionService.kt` | Wires bridge into `task_assign` handler. |
| `UFOGalaxyApplication.kt` | `agentRuntimeBridge` singleton initialisation. |
| `test/agent/AgentRuntimeBridgeTest.kt` | Unit tests for all bridge paths. |

---

# Round 6 – WebRTC / TURN Multi-Candidate Robustness

## Overview

Round 6 adds first-class support for **multi-candidate ICE signaling** and **TURN relay
fallback** to ensure reliable peer-to-peer connections even when direct (host/srflx) paths
are blocked by firewalls or NAT.

---

## New Classes

| Class | Package | Responsibility |
|-------|---------|----------------|
| `SignalingMessage` | `webrtc` | Extended signaling envelope: multi-candidate list, TURN config, `trace_id`, `error`. |
| `TurnConfig` | `webrtc` | TURN server configuration delivered by the gateway (`urls`, `username`, `credential`). |
| `IceCandidateManager` | `webrtc` | Deduplication, priority ordering, TURN fallback with retry/backoff. |
| `WebRTCSignalingClient` | `webrtc` | OkHttp WebSocket signaling client; dispatches candidates to `IceCandidateManager`. |

---

## Signaling Message Formats

### Legacy (single ICE candidate – still supported)

```json
{
  "type": "ice_candidate",
  "candidate": { "candidate": "...", "sdpMid": "0", "sdpMLineIndex": 0 },
  "device_id": "<id>",
  "trace_id": "<UUID>"
}
```

### Round-6 (batch / trickle ICE candidates with TURN)

```json
{
  "type": "ice_candidates",
  "candidates": [
    { "candidate": "candidate:0 1 UDP 2130706431 192.168.1.1 54321 typ host", "sdpMid": "0", "sdpMLineIndex": 0 },
    { "candidate": "candidate:1 1 UDP 1694498815 1.2.3.4 54321 typ srflx ...", "sdpMid": "0", "sdpMLineIndex": 0 },
    { "candidate": "candidate:2 1 UDP 33562623 5.6.7.8 3478 typ relay ...",    "sdpMid": "0", "sdpMLineIndex": 0 }
  ],
  "turn_config": {
    "urls": ["turn:100.64.0.1:3478", "turns:100.64.0.1:5349"],
    "username": "galaxy_user",
    "credential": "s3cr3t"
  },
  "device_id": "<id>",
  "trace_id": "<UUID>"
}
```

### Error message

```json
{ "type": "error", "error": "ICE gathering timeout", "trace_id": "<UUID>" }
```

---

## Candidate Priority Order

Candidates are always applied in the following order regardless of arrival sequence,
consistent with the server-side Round-6 policy:

| Priority | Type | Description |
|----------|------|-------------|
| 1 (highest) | `relay` | TURN relay — traverses NAT and firewalls |
| 2 | `srflx` | STUN server-reflexive |
| 3 (lowest) | `host` | Direct local candidate |

The `IceCandidateManager` sorts incoming batches before applying them and deduplicates
by raw SDP string (first occurrence wins).

---

## TURN Fallback

When direct connectivity (host/srflx) fails, `IceCandidateManager.startTurnFallback()`
switches to **relay-only** mode and re-applies all held TURN candidates:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `MAX_FALLBACK_ATTEMPTS` | 3 | Maximum retry attempts before `onError` is invoked. |
| `FALLBACK_BACKOFF_MS` | `[1000, 2000, 4000]` ms | Exponential backoff between attempts. |
| `CONNECTION_TIMEOUT_MS` | 10 000 ms | Suggested timeout before triggering fallback. |

`WebRTCSignalingClient.triggerTurnFallback()` is the entry point from the peer-connection
ICE failure callback.

---

## Error Surfacing

All errors include the `trace_id` for full-chain log correlation:

```
[trace=<UUID>] TURN fallback exhausted after 3 attempts
[trace=<UUID>] Gateway error: ICE gathering timeout
[trace=<UUID>] Signaling WS failure: Connection refused
```

The `onError` callback in `WebRTCSignalingClient` and `IceCandidateManager` is invoked for:
- WebSocket connection failures
- Gateway-sent `error` messages
- TURN fallback exhaustion
- TURN fallback with no relay candidates available

---

## Backward Compatibility

- Legacy `ice_candidate` (single) messages are handled transparently — no call-site changes required.
- `SignalingMessage.candidate` (single field) and `SignalingMessage.candidates` (list) are combined via `allCandidates`.
- When no `turn_config` is delivered, `lastTurnConfig` is `null` and the client works with STUN-only.
- `traceId`, `error` fields default to `null`; existing consumers are unaffected.

---

## Required Gateway Configuration

To enable TURN relay, configure the Galaxy Gateway with a TURN server and ensure it
sends `turn_config` alongside `ice_candidates` messages:

```properties
# config.properties (or AppSettings in-app)
turn_server_url=turn:100.64.0.1:3478
turn_username=galaxy_user
turn_credential=s3cr3t
```

STUN-only deployments (no TURN) continue to work; relay fallback simply has no effect
when no relay candidates are available.

---

## Related Files

| File | Role |
|------|------|
| `webrtc/SignalingMessage.kt` | Signaling message model (Round 6 extensions). |
| `webrtc/TurnConfig.kt` | TURN server configuration model. |
| `webrtc/IceCandidateManager.kt` | Dedup, priority ordering, TURN fallback. |
| `webrtc/WebRTCSignalingClient.kt` | OkHttp WebSocket signaling client. |
| `test/webrtc/SignalingMessageTest.kt` | JSON round-trip, multi-candidate, TURN config tests. |
| `test/webrtc/IceCandidateManagerTest.kt` | Dedup, priority, fallback, error path tests. |

---

# Round 8 – Cross-Device Stack Alignment with ufo-galaxy-realization-v2

## Overview

Round 8 aligns the Android cross-device stack with the ufo-galaxy-realization-v2 task manager
protocol across four areas: TaskEnvelope/lifecycle, Task Manager unification, Gateway routing
field alignment, and API route v1-first behavior.

---

## 1 – TaskEnvelope / lifecycle alignment

### task_result payload fields

`TaskResultPayload` now carries three additional fields that make the uplink result
self-contained (not requiring the caller to inspect the outer `AipMessage` envelope):

| Field | Type | Description |
|-------|------|-------------|
| `trace_id` | `String?` | Echoed from the originating `task_assign` envelope. |
| `device_id` | `String` | Identifier of the executing device. |
| `result_summary` | `String?` | Human-readable one-line outcome. |

`GalaxyConnectionService.executeLocalTaskAssign` populates all three fields before
sending the reply so that Gateway, Agent Runtime, and memory indexer can consume them
without parsing the outer envelope.

### trace_id propagation

`GalaxyConnectionService.handleTaskAssign` now preserves the `trace_id` field from the
inbound `task_assign` AIP envelope instead of always generating a fresh UUID:

```
task_assign (inbound) → trace_id="A" (from gateway)
    → task_result (uplink)    → trace_id="A"  (echo)
    → memory store            → trace_id="A"  (echo)
    → bridge_handoff          → trace_id="A"  (echo)
```

When the inbound `task_assign` does **not** include a `trace_id`, a new UUID is generated
locally and propagated consistently through the rest of the chain.

---

## 2 – Task Manager unification

### Single execution pipeline

The `task_assign → TaskExecutor → task_result` pipeline is now the **only** execution
path. Legacy `task_execute` and `task_status_query` message types are remapped to
`task_assign` at two layers:

1. **`MsgType.LEGACY_TYPE_MAP`** — maps the string `"task_execute"` and
   `"task_status_query"` to `"task_assign"` for any callers that use `toV3Type()`.
2. **`GalaxyWebSocketClient.handleMessage()`** — inbound `task_execute` /
   `task_status_query` frames are dispatched to `Listener.onTaskAssign` (same handler
   as genuine `task_assign` frames). No separate fork logic is maintained.

```
Inbound: task_execute      ─┐
Inbound: task_status_query ─┤ → onTaskAssign() → handleTaskAssign() → task_result
Inbound: task_assign       ─┘
```

Legacy types are a **compatibility window** only; new server code must send `task_assign`.

---

## 3 – Gateway / Device Router field alignment

### Session trace_id

`GalaxyWebSocketClient` maintains a `sessionTraceId` (UUID) generated on each successful
WebSocket `onOpen`. All outbound messages within a session include this identifier:

| Message | New fields added |
|---------|-----------------|
| `capability_report` (handshake) | `trace_id`, `route_mode`, `device_type` |
| `heartbeat` | `trace_id`, `route_mode`, `device_type` |

`getTraceId()` exposes the current session trace ID for external consumers.

### task_submit outbound envelope

`InputRouter.sendViaWebSocket` now includes `trace_id` (set to `task_id`) and
`route_mode = "cross_device"` in every outbound `TASK_SUBMIT` `AipMessage` envelope:

```json
{
  "type": "task_submit",
  "correlation_id": "<task_id>",
  "device_id": "<device>",
  "trace_id": "<task_id>",
  "route_mode": "cross_device",
  "payload": { ... }
}
```

### device_type field

All outbound messages include `"device_type": "Android_Agent"` so the Gateway can
distinguish Android clients from other node types in the device registry.

---

## 4 – API routes & fields (v1-first behavior)

All REST calls from the Android client use a **v1-first with 404 fallback** strategy:

1. Issue the request to the **v1** path (`/api/v1/...`).
2. If the server returns **HTTP 404 only**, retry against the **legacy** path (`/api/...`).
3. Any other error code (4xx/5xx) or network exception is returned immediately — no second
   attempt is made so that real server errors surface promptly.

This ensures the client works transparently with both current v1 gateway deployments and
older deployments that have not yet migrated to the v1 path prefix.

### Device Register & Heartbeat (`GalaxyApiClient`)

`GalaxyApiClient.registerDevice()` and `sendHeartbeat()` follow the v1-first strategy:

1. Try `POST /api/v1/devices/register` (resp. `POST /api/v1/devices/heartbeat`).
2. If the server returns **HTTP 404** only, retry against the legacy path
   (`/api/devices/register` / `/api/devices/heartbeat`).
3. Any other error code (4xx/5xx/exception) is returned immediately — no second attempt.

### Remote Config (`RemoteConfigFetcher`)

`RemoteConfigFetcher.fetchConfig()` follows the same v1-first strategy:

1. Try `GET /api/v1/config`.
2. If the server returns **HTTP 404** only, retry against the legacy path `GET /api/config`.
3. Any other error (non-404 HTTP code or network exception) is returned as `null`
   immediately — no second attempt.

### OpenClawd Memory Backflow

`OpenClawdMemoryBackflow.store()` and `queryByTaskId()` follow the same v1-first strategy:

1. Try `POST /api/v1/memory/store` (resp. `GET /api/v1/memory/query`).
2. If the server returns **HTTP 404** only, retry against the legacy path
   (`/api/memory/store` / `/api/memory/query`).
3. Any other error code (4xx/5xx/exception) is returned immediately — no second attempt.

### REST endpoint reference

| Operation | v1 path (preferred) | Legacy path (404 fallback) |
|-----------|---------------------|---------------------------|
| Device register | `POST /api/v1/devices/register` | `POST /api/devices/register` |
| Device heartbeat | `POST /api/v1/devices/heartbeat` | `POST /api/devices/heartbeat` |
| Remote config | `GET  /api/v1/config` | `GET  /api/config` |
| Memory store | `POST /api/v1/memory/store` | `POST /api/memory/store` |
| Memory query | `GET  /api/v1/memory/query` | `GET  /api/memory/query` |
| Health | `GET  /api/v1/health` | — |
| Devices list | `GET  /api/v1/devices/list` | — |

---

## Backward Compatibility

All Round 8 changes are **additive and backward-compatible**:

- `TaskResultPayload.trace_id`, `device_id`, `result_summary` default to `null`/`""` —
  existing gateway consumers that do not read these fields are unaffected.
- Legacy `task_execute` / `task_status_query` messages still produce a valid `task_result`
  reply — no client regression.
- The v1-first fallback for register, heartbeat, config, and memory is triggered only on
  HTTP 404; a 200 response from the v1 endpoint bypasses legacy entirely, so there is no
  extra round-trip for up-to-date deployments.
- `GalaxyApiClient` and `RemoteConfigFetcher` are new classes; no existing code is modified.

---

## Related Files (Round 8 + v1-first follow-up)

| File | Role |
|------|------|
| `protocol/AipModels.kt` | `TaskResultPayload` + `MsgType.LEGACY_TYPE_MAP` extensions. |
| `network/GalaxyWebSocketClient.kt` | Session `trace_id`, legacy type remapping, field additions. |
| `service/GalaxyConnectionService.kt` | Inbound `trace_id` propagation; `TaskResultPayload` augmentation. |
| `input/InputRouter.kt` | `trace_id` + `route_mode` in outbound `task_submit` envelope. |
| `memory/OpenClawdMemoryBackflow.kt` | v1-first with 404 legacy fallback for memory endpoints. |
| `api/GalaxyApiClient.kt` | v1-first with 404 fallback for device register + heartbeat. |
| `config/RemoteConfigFetcher.kt` | v1-first with 404 fallback for remote config endpoint. |
| `test/protocol/AipModelsTest.kt` | `TaskResultPayload` new fields coverage. |
| `test/protocol/TaskSubmitV3Test.kt` | `task_execute`/`task_status_query` legacy map coverage. |
| `test/input/InputRouterTest.kt` | `trace_id`/`route_mode` in outbound envelope. |
| `test/memory/OpenClawdMemoryBackflowTest.kt` | 404 fallback path coverage for memory. |
| `test/api/GalaxyApiClientTest.kt` | 404 fallback path coverage for register + heartbeat. |
| `test/config/RemoteConfigFetcherTest.kt` | 404 fallback path coverage for remote config. |
