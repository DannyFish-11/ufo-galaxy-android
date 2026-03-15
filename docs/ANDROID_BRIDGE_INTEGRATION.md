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
