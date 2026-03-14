# P2 + P3 Implementation Guide

This document describes the P2 (Route Strong Consistency, Local Loop Observability, Floating
Task Summary, Keep-alive & Boot Recovery, V2 Integration Hooks) and P3 (Cross-repo Integration
Validation, Multi-device Concurrency, OpenClawd Memory Backflow) work that is present in the
`main` branch.

---

## P2 — Route Strong Consistency

### What changed

| Component | Change |
|-----------|--------|
| `network/MessageRouter` | Strict routing: `crossDeviceEnabled=true` + WS unavailable → explicit error, **no silent local fallback**. `crossDeviceEnabled=false` → local only, task_submit uplink forbidden. All routes emit `route_mode`, `task_id`, `device_id` structured logs. |
| `ui/viewmodel/MainViewModel` | Instantiates `MessageRouter` with an `onError` callback that surfaces the error in the UI via `pushError()` and updates `_uiState.error`. |
| `service/EnhancedFloatingService` | Uses `MessageRouter`; the `onError` callback sets `taskStatus = STATUS_ERROR` and updates the status label. Voice button now shows an explicit Toast redirecting users to the main activity. |

### Routing rules

```
crossDeviceEnabled = false
  → RouteMode.LOCAL  (local fallback called, task_submit NEVER sent)

crossDeviceEnabled = true  AND  WS connected
  → RouteMode.CROSS_DEVICE  (TaskSubmitPayload sent uplink)

crossDeviceEnabled = true  AND  WS NOT connected
  → RouteMode.ERROR  (onError invoked, no silent local fallback)
```

### Log format

```
[ROUTE] route_mode=<local|cross_device|error>  task_id=<uuid>  device_id=<mfr_model>
```

---

## P2 — Local Loop Observability

Per-step results are accumulated by `EdgeExecutor` into `TaskResultPayload.steps`.
Each `StepResult` carries:

| Field | Description |
|-------|-------------|
| `step_id` | 1-based step index string |
| `action` | Symbolic action name (`tap`, `scroll`, …) |
| `success` | Whether the step completed without error |
| `latency_ms` | Wall-clock execution time for this step |
| `error` | Error description when `success=false` |
| `snapshot_ref` | Reference ID for the on-device snapshot |

`GalaxyConnectionService` logs the step count and final status when it sends the
`task_result` uplink.

---

## P2 — Floating Task Summary

`EnhancedFloatingService` displays a three-part status label in the collapsed island:

```
<mode> | <task_id_short> | <status>
```

| Field | Values |
|-------|--------|
| mode | `跨设备` / `本地` |
| task_id_short | first 8 chars of the task ID, or `—` when idle |
| status | `执行中` / `成功` / `错误` / `已连接` / `未连接` |

Status constants (`STATUS_IDLE`, `STATUS_RUNNING`, `STATUS_SUCCESS`, `STATUS_ERROR`)
are defined as `companion object` constants so tests can reference them without
instantiating the service.

---

## P2 — Keep-alive & Boot Recovery

`BootReceiver` starts both `GalaxyConnectionService` and `EnhancedFloatingService`
on `ACTION_BOOT_COMPLETED` / `QUICKBOOT_POWERON`.

`GalaxyConnectionService.onStartCommand` restores `crossDeviceEnabled` from
`AppSettings` into `GalaxyWebSocketClient` before connecting, ensuring the
routing state is consistent after a process restart.

The foreground notification content reflects the active mode
(`"跨设备模式已启用"` vs `"本地模式"`).

---

## P2 — V2 Integration Hooks

`GalaxyWebSocketClient` logs every uplink and downlink message with structured fields:

```
[WS:UPLINK]   type=<msg_type>  task_id=<id>  correlation_id=<id>  device_id=<id>
[WS:DOWNLINK] type=<msg_type>  task_id=<id>  correlation_id=<id>  device_id=<id>
```

Heartbeat messages include device metadata (`device_id`, `device_role`, `capabilities`).
Capability reports are enriched with all eight `AppSettings.toMetadataMap()` fields.

---

## P3 — Cross-repo Integration Validator

**Package:** `com.ufo.galaxy.integration`

`CrossRepoIntegrationValidator` performs four checks against the Galaxy gateway:

| Check | Method | Endpoint |
|-------|--------|----------|
| Gateway liveness | GET | `/api/v1/health` |
| Device registry | GET | `/api/v1/devices/list` |
| Memory write access | POST | `/api/v1/memory/store` |
| WebSocket URL format | — | validates `ws://` / `wss://` prefix |

### Usage

```kotlin
val validator = CrossRepoIntegrationValidator(
    restBaseUrl = appSettings.restBaseUrl,
    wsUrl = appSettings.galaxyGatewayUrl
)
// Run on an IO coroutine or background thread
val report = withContext(Dispatchers.IO) { validator.validate() }
Log.i(TAG, report.summary())
// "Integration: 4/4 passed — OK"
```

`ValidationReport.summary()` returns a single human-readable line suitable for
display in the Diagnostics panel or logcat.

---

## P3 — Multi-device Concurrency

**Package:** `com.ufo.galaxy.coordination`

`MultiDeviceCoordinator` fans a task out to multiple devices in parallel using
Kotlin coroutines (`async` / `awaitAll`).

### Key types

| Type | Description |
|------|-------------|
| `MultiDeviceCoordinator(deviceIds, dispatch, scope)` | Constructor — inject a `dispatch` lambda for testability |
| `dispatchParallel(goal, groupId)` | Suspending function; dispatches to all devices concurrently |
| `SubtaskResult` | Per-device result with `subtaskId`, `deviceId`, `success`, `output`, `error`, `latencyMs` |
| `ParallelGroupResult` | Aggregated result with `succeededCount`, `failedCount`, `succeeded`, `failed`, `allSucceeded` |

### Subtask ID format

```
<groupId>_sub_<index>
```

Subtask IDs are always unique, even when multiple devices share the same ID.

### Usage

```kotlin
val coordinator = MultiDeviceCoordinator(
    deviceIds = listOf("phone-1", "tablet-1"),
    dispatch = { deviceId, subtaskId, goal ->
        // send parallel_subtask to device; return SubtaskResult
        SubtaskResult(subtaskId = subtaskId, deviceId = deviceId, success = true)
    }
)
val group = coordinator.dispatchParallel(
    goal = "take screenshot",
    groupId = "grp-${UUID.randomUUID()}"
)
Log.i(TAG, "succeeded=${group.succeededCount} failed=${group.failedCount}")
```

Exceptions thrown by the `dispatch` lambda are caught internally and returned as
failed `SubtaskResult` entries so the overall dispatch always completes.

---

## P3 — OpenClawd Memory Backflow

**Package:** `com.ufo.galaxy.memory`

`OpenClawdMemoryBackflow` persists task results to the Gateway's memory store and
retrieves them by `task_id`.

### MemoryEntry schema

| Field | Type | Description |
|-------|------|-------------|
| `task_id` | String | Primary key |
| `goal` | String | Natural-language goal |
| `status` | String | `success` / `error` / `cancelled` / `timeout` |
| `summary` | String | Human-readable outcome |
| `steps` | List\<String\> | Ordered step descriptions |
| `route_mode` | String | `local` / `cross_device` / `error` |
| `timestamp_ms` | Long | Unix epoch milliseconds |

### Usage

```kotlin
val backflow = OpenClawdMemoryBackflow(restBaseUrl = appSettings.restBaseUrl)

// Store a completed task result
val entry = MemoryEntry(
    task_id = taskId,
    goal = "open WeChat",
    status = "success",
    summary = "Opened WeChat in 3 steps",
    steps = listOf("screenshot", "tap icon", "wait"),
    route_mode = "cross_device"
)
val stored = withContext(Dispatchers.IO) { backflow.store(entry) }

// Query by task_id
val retrieved = withContext(Dispatchers.IO) { backflow.queryByTaskId(taskId) }
```

Both `store` and `queryByTaskId` are blocking and must be called from a background
thread or IO coroutine.

---

## End-to-End Manual Test Checklist

### P2 Routing

- [ ] Start app with `crossDeviceEnabled=false`; submit a task → routes locally, no WS message sent.
- [ ] Enable `crossDeviceEnabled` while WS is disconnected; submit a task → error Toast appears, no silent fallback.
- [ ] Enable `crossDeviceEnabled` and confirm WS connects; submit a task → `[ROUTE] route_mode=cross_device` visible in logcat.
- [ ] Long-press the floating island → opens `MainActivity` (voice redirect).
- [ ] Floating island collapsed label shows `<mode> | <task_id> | <status>` after a task is dispatched.

### Boot Recovery

- [ ] Enable `crossDeviceEnabled`; reboot device → `GalaxyConnectionService` and `EnhancedFloatingService` start automatically.
- [ ] After reboot, check logcat for `服务重启：恢复 crossDeviceEnabled=true`.
- [ ] Foreground notification shows `跨设备模式已启用`.

### P3 Integration Validation

- [ ] Run `CrossRepoIntegrationValidator.validate()` against a live gateway.
- [ ] Observe `report.summary()` reports `4/4 passed — OK` when all endpoints are reachable.
- [ ] Observe the WS URL format check fails when `galaxyGatewayUrl` is set to an `http://` URL.

### P3 Multi-device Concurrency

- [ ] Call `MultiDeviceCoordinator.dispatchParallel()` with two device IDs.
- [ ] Confirm subtask IDs follow `<groupId>_sub_0`, `<groupId>_sub_1` format in logcat.
- [ ] Confirm `ParallelGroupResult.succeededCount` matches the number of successful device responses.

### P3 Memory Backflow

- [ ] Call `OpenClawdMemoryBackflow.store()` with a `MemoryEntry`; confirm 2xx response.
- [ ] Call `OpenClawdMemoryBackflow.queryByTaskId()` with the same `task_id`; confirm entry is returned.
- [ ] Confirm `MemoryEntry.route_mode` is preserved across store/query.

---

## Unit Test Coverage

| Test class | Scope |
|------------|-------|
| `CrossRepoIntegrationValidatorTest` | WS URL format, HTTP check pass/fail, `ValidationReport.summary()` |
| `MultiDeviceCoordinatorTest` | Parallel dispatch, subtask ID format, exception capture, empty device list |
| `OpenClawdMemoryBackflowTest` | `store()` happy/error paths, `queryByTaskId()` JSON parsing, `MemoryEntry` Gson contract |
| `MessageRouterTest` | P2 routing rules: LOCAL / CROSS_DEVICE / ERROR modes |
