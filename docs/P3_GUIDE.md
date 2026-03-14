# P3 Implementation Guide

## Overview

P3 introduces three cross-system features on top of the P2 foundation:

| Feature | Chinese | Package |
|---------|---------|---------|
| Cross-repo integration testing | 跨仓联调 | `com.ufo.galaxy.integration` |
| Multi-device concurrency | 多设备并发 | `com.ufo.galaxy.coordination` |
| OpenClawd memory backflow | OpenClawd 记忆回流 | `com.ufo.galaxy.memory` |

---

## 1. Cross-Repo Integration Testing (跨仓联调)

### Class: `CrossRepoIntegrationValidator`

Validates that the Android app can reach the V2 (`ufo-galaxy-realization-v2`) Gateway before starting cross-device tasks.

**Four checks performed:**

| Key | What is checked |
|-----|----------------|
| `gateway_health` | GET `/api/v1/health` (or `/api/health`) returns HTTP 2xx |
| `devices_endpoint` | GET `/api/v1/devices/list` is reachable (200/401/403 all accepted) |
| `memory_endpoint` | GET `/api/v1/memory/store` is reachable |
| `ws_url_format` | WebSocket URL starts with `ws://` or `wss://` |

**Usage:**

```kotlin
val validator = CrossRepoIntegrationValidator(
    restBaseUrl = settings.restBaseUrl,
    apiKey = "my-api-key"          // optional
)
val report = validator.validate(wsUrl = settings.galaxyGatewayUrl)
if (report.allPassed) {
    // Safe to enable cross-device mode
} else {
    Log.w(TAG, report.summary())
}
```

**Acceptance criteria:**
- All four checks pass against a running V2 gateway.
- `report.summary()` is displayed in the Diagnostics screen.
- Validation can be triggered from the Settings / Diagnostics UI.

---

## 2. Multi-Device Concurrency (多设备并发)

### Class: `MultiDeviceCoordinator` — `dispatchParallel()`

Dispatches the same task to multiple devices simultaneously using Kotlin coroutines (`async/awaitAll`), then aggregates all results into a `ParallelGroupResult`.

**Key data models:**

```
ParallelGroupResult
  ├── groupId: String          // "grp_<taskId>"
  ├── taskId: String
  ├── deviceResults: Map<String, TaskResult>
  ├── allSucceeded: Boolean
  ├── succeeded: Map           // filter: DISPATCHED or COMPLETED
  └── failed: Map              // filter: anything else
```

**Usage:**

```kotlin
val coordinator = MultiDeviceCoordinator(context)
coordinator.initialize(serverUrl, apiKey, myDeviceInfo)
coordinator.connect()

val task = CoordinationTask(
    taskId = UUID.randomUUID().toString(),
    taskType = "execute",
    payload = JSONObject().apply { put("goal", "发送邮件") }
)

val groupResult = coordinator.dispatchParallel(
    task = task,
    targetDeviceIds = listOf("phone_01", "tablet_02", "desktop_03")
)

if (groupResult.allSucceeded) {
    Log.i(TAG, "All ${groupResult.succeeded.size} devices executed successfully")
} else {
    Log.w(TAG, "Failed devices: ${groupResult.failed.keys}")
}
```

**Acceptance criteria:**
- Concurrent dispatch log shows all target device IDs in overlapping time windows.
- `groupResult.succeeded` contains all devices on a healthy network.
- `groupResult.failed` correctly identifies timed-out or unreachable devices.
- Each subtask carries a unique `taskId` (`<original>_<index>`).

---

## 3. OpenClawd Memory Backflow (OpenClawd 记忆回流)

### Class: `OpenClawdMemoryBackflow`

Writes task execution results into the V2 OpenClawd memory/database so the AI agent can retrieve past context for future decisions.

**Write payload fields:**

| Field | Type | Description |
|-------|------|-------------|
| `task_id` | String | Unique task identifier |
| `device_id` | String | Executing device ID |
| `goal` | String | User's natural-language goal |
| `status` | String | `"success"` / `"error"` / `"partial"` |
| `summary` | String | Brief execution summary |
| `steps` | Array | Per-step results (JSON strings) |
| `error_msg` | String | Failure reason when `status=error` |
| `route_mode` | String | `"local"` or `"cross_device"` |
| `timestamp_ms` | Long | Client-side Unix timestamp (ms) |

**V2 endpoints used:**

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/memory/store` | Write a memory entry |
| GET | `/api/v1/memory/query?task_id=<id>` | Retrieve by task ID |

**Usage (after task completes):**

```kotlin
val backflow = OpenClawdMemoryBackflow(
    restBaseUrl = settings.restBaseUrl,
    apiKey = "my-api-key",
    deviceId = myDeviceId
)

val entry = MemoryEntry(
    taskId = completedTaskId,
    goal = userGoal,
    status = if (success) MemoryEntry.STATUS_SUCCESS else MemoryEntry.STATUS_ERROR,
    summary = "Completed 3 steps: open app → navigate → confirm",
    steps = stepResults,
    routeMode = if (crossDevice) MemoryEntry.ROUTE_CROSS_DEVICE else MemoryEntry.ROUTE_LOCAL
)

val stored = backflow.store(entry)
```

**Wiring into GalaxyConnectionService (suggested):**

After a `task_result` or `goal_result` is produced, call `backflow.store(entry)` on the IO dispatcher. This does not block the main execution pipeline.

**Acceptance criteria:**
- Every completed task produces a record in V2 memory DB (verifiable via GET query or V2 admin UI).
- `MemoryEntry` round-trips correctly through `toJson()` / `fromJson()`.
- `store()` returns `false` gracefully when V2 is unreachable (no crash, warning log only).

---

## End-to-End Validation Checklist

```
[ ] CrossRepoIntegrationValidator.validate() returns allPassed=true
[ ] dispatchParallel() dispatches to ≥2 devices concurrently (log timestamps overlap)
[ ] All subtasks have unique taskId (_0, _1, …)
[ ] ParallelGroupResult.succeeded contains all devices on clean run
[ ] MemoryEntry stored after each task completion
[ ] GET /api/v1/memory/query?task_id=<id> returns the correct entry
[ ] Diagnostics screen shows validation report
[ ] App survives missing V2 server (graceful degradation, no crash)
```
