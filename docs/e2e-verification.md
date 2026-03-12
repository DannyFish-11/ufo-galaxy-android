# Cross-Repo E2E Verification — Android ↔ v2 Gateway

This document describes how to verify the end-to-end flow between the Android client
(`ufo-galaxy-android`) and the v2 gateway (`ufo-galaxy-realization-v2`).

---

## Architecture Overview

```
User tap / voice
      │
      ▼
MainActivity (Compose)
      │  crossDeviceEnabled toggle (AppSettings)
      ▼
GalaxyWebSocketClient  ──ws://─►  v2 Gateway /ws
      │                               │
      │  ◄── task_assign ─────────────┤
      ▼                               │
GalaxyConnectionService               │ POST /api/v1/chat
      │                               │  (aggregates parallel_result)
      ├─► EdgeExecutor (task_assign)   │
      └─► LocalGoalExecutor (goal_execution / parallel_subtask)
```

Log tags referenced below:
| Tag | File |
|-----|------|
| `GalaxyConnectionService` | `service/GalaxyConnectionService.kt` |
| `GalaxyWebSocket` | `network/GalaxyWebSocketClient.kt` |
| `MainViewModel` | `ui/viewmodel/MainViewModel.kt` |
| `EdgeOrchestrator` | `agent/EdgeOrchestrator.kt` |
| `LocalGoalExecutor` | `agent/LocalGoalExecutor.kt` |
| `ReadinessChecker` | `service/ReadinessChecker.kt` |
| `EnhancedFloatingService` | `service/EnhancedFloatingService.kt` |

View live logs:
```bash
adb logcat -s GalaxyConnectionService GalaxyWebSocket MainViewModel \
           EdgeOrchestrator LocalGoalExecutor ReadinessChecker \
           EnhancedFloatingService
```

---

## Scenario 1 — `cross_device_enabled` toggle **OFF** (local-only)

**Expected behaviour:** The client stays fully local. No WebSocket connection is opened,
no `device_register` or `capability_report` message is sent to the gateway.

### Steps

1. Open **MainActivity** → Settings → toggle **"Cloud Collaboration"** to **OFF**.
2. Confirm `AppSettings.crossDeviceEnabled == false` in logcat:
   ```
   D/MainViewModel: crossDeviceEnabled=false
   ```
3. Send any task via the chat input.

### Verification

- `GalaxyWebSocket` log should show **no** `connect()` call.
- No `device_register` or `capability_report` frames appear in gateway access logs.
- Task is handled entirely on-device; `EdgeOrchestrator` or `LocalGoalExecutor` logs
  show local execution.
- Result is returned to the UI without touching the network.

---

## Scenario 2 — `cross_device_enabled` toggle **ON** (registers + reports metadata)

**Expected behaviour:** On toggle-on, the WebSocket connects, sends `capability_report`,
and the gateway registers the device.

### Steps

1. Toggle **"Cloud Collaboration"** to **ON** in Settings.
2. Watch logcat for:
   ```
   D/GalaxyWebSocket: connect() crossDeviceEnabled=true
   D/GalaxyConnectionService: sending capability_report device_id=<id>
   ```

### Verification

- `GalaxyWebSocket` emits `onConnected`.
- A `capability_report` message is sent containing at minimum:

  | Field | Expected |
  |-------|----------|
  | `platform` | `"android"` |
  | `device_id` | non-empty string |
  | `version` | `"3.0"` |
  | `capabilities` | includes `"autonomous_goal_execution"` |
  | `metadata.cross_device_enabled` | `true` |
  | `metadata.goal_execution_enabled` | `true` |
  | `metadata.model_ready` | `true` (when models loaded) |
  | `metadata.accessibility_ready` | `true` (when service enabled) |
  | `metadata.overlay_ready` | `true` (when permission granted) |

- Gateway responds with `device_register` ACK (check gateway logs).

---

## Scenario 3 — `goal_execution` request → local execution, standard result

**Expected behaviour:** Gateway sends a `goal_execution` message; the device executes
locally and returns a `goal_result` with the standard payload.

### Sample gateway → device payload

```json
{
  "type": "goal_execution",
  "payload": {
    "task_id": "ge-test-001",
    "goal": "Open the Clock app and set a 5-minute timer",
    "constraints": ["stay in Clock app"],
    "max_steps": 10
  },
  "version": "3.0"
}
```

### Steps

1. Ensure `cross_device_enabled` is **ON** and models are loaded.
2. Inject the payload via adb (replace `<device>` with your device serial):
   ```bash
   # Forward gateway port locally
   adb -s <device> reverse tcp:8765 tcp:8765

   # Send from gateway test script or wscat:
   wscat -c ws://localhost:8765 -x '{"type":"goal_execution","payload":{"task_id":"ge-test-001","goal":"Open Clock and set 5-minute timer","max_steps":10},"version":"3.0"}'
   ```
3. Watch logcat:
   ```
   D/LocalGoalExecutor: executing goal task_id=ge-test-001
   D/LocalGoalExecutor: goal complete status=success latency_ms=<n>
   ```

### Verification

The device sends back a `goal_result` envelope. Required fields:

| Field | Expected |
|-------|----------|
| `type` | `"goal_result"` |
| `payload.task_id` | `"ge-test-001"` |
| `payload.correlation_id` | `"ge-test-001"` |
| `payload.status` | `"success"` or `"error"` |
| `payload.latency_ms` | non-negative integer |
| `payload.device_id` | non-empty string |
| `payload.device_role` | e.g. `"phone"` |
| `payload.group_id` | `null` (standalone goal) |
| `payload.subtask_index` | `null` (standalone goal) |

---

## Scenario 4 — `parallel_subtask` group → returns parallel result with `group_id`/`subtask_index`

**Expected behaviour:** Gateway assigns subtasks to the device; each returns a
`goal_result` with `group_id` and `subtask_index` echoed, allowing gateway aggregation.

### Sample gateway → device payload

```json
{
  "type": "parallel_subtask",
  "payload": {
    "task_id": "sub-001",
    "goal": "Send a WeChat message to Alice",
    "group_id": "grp-parallel-alpha",
    "subtask_index": 0,
    "max_steps": 8
  },
  "version": "3.0"
}
```

### Steps

1. Inject via wscat (same port-forward as Scenario 3):
   ```bash
   wscat -c ws://localhost:8765 -x '{"type":"parallel_subtask","payload":{"task_id":"sub-001","goal":"Send WeChat message to Alice","group_id":"grp-parallel-alpha","subtask_index":0,"max_steps":8},"version":"3.0"}'
   ```
2. Watch logcat:
   ```
   D/LocalGoalExecutor: parallel subtask group_id=grp-parallel-alpha subtask_index=0
   ```

### Verification

Returned `goal_result` payload must contain:

| Field | Expected |
|-------|----------|
| `payload.task_id` | `"sub-001"` |
| `payload.group_id` | `"grp-parallel-alpha"` |
| `payload.subtask_index` | `0` |
| `payload.status` | `"success"` or `"error"` |

The gateway collects all subtask results and returns a `parallel_result` object in
`POST /api/v1/chat`. Verify the gateway response includes:
```json
{
  "parallel_result": {
    "group_id": "grp-parallel-alpha",
    "subtasks": [
      { "subtask_index": 0, "status": "success", "result": "..." }
    ]
  }
}
```

---

## Scenario 5 — Gateway aggregation via `POST /api/v1/chat`

After parallel subtasks complete, the gateway aggregates results. Verify with:

```bash
curl -X POST http://<gateway-host>/api/v1/chat \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <token>" \
  -d '{"message":"run parallel demo","session_id":"sess-test-001"}'
```

Expected response structure:
```json
{
  "reply": "...",
  "parallel_result": {
    "group_id": "grp-parallel-alpha",
    "subtasks": [...]
  }
}
```

---

## Where to Find Logs

| What to check | Command |
|---------------|---------|
| All tagged logs | `adb logcat -s GalaxyConnectionService GalaxyWebSocket MainViewModel EdgeOrchestrator LocalGoalExecutor ReadinessChecker` |
| WebSocket frames only | `adb logcat -s GalaxyWebSocket` |
| Readiness self-check | `adb logcat -s ReadinessChecker` |
| Full device log (verbose) | `adb logcat *:V` (filter by PID for clarity) |
| App PID | `adb shell pidof com.ufo.galaxy` |
| Logcat for app PID | `adb logcat --pid=$(adb shell pidof com.ufo.galaxy)` |
