# Android Bridge Integration Guide – Round 5

## Overview

This document describes the **Agent bridge & runtime takeover** feature added in Round 5
of the UFO Galaxy Android client. When the cross-device switch is **ON**, eligible tasks
arriving via the Gateway are delegated to **Agent Runtime / OpenClawd** through a
dedicated bridge layer instead of executing entirely on-device.

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
| `FALLBACK_BACKOFF_MS` | `[1 000, 2 000, 4 000]` ms | Exponential backoff between attempts. |
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
