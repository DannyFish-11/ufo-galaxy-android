# Android Task Timeout & Cancel Verification Checklist

This document describes how to manually verify the timeout and cancel features
introduced in PR16. It complements the unit tests in
`TaskTimeoutCancelTest.kt`.

---

## 1. Per-Task Timeout

### 1.1 Default timeout (30 s)

| # | Step | Expected |
|---|------|----------|
| 1 | Send a `goal_execution` message **without** `timeout_ms` in the payload. | Service uses `DEFAULT_TIMEOUT_MS = 30 000 ms`. |
| 2 | Let the execution run longer than 30 s (e.g. mock planner with artificial delay). | `GalaxyLogger` emits a `GALAXY:TASK:TIMEOUT` log entry. |
| 3 | Observe the WebSocket outgoing frame. | `goal_result` payload has `status = "timeout"`, `error` is non-null, `correlation_id == task_id`. |

### 1.2 Custom timeout

| # | Step | Expected |
|---|------|----------|
| 1 | Send a `goal_execution` with `timeout_ms = 5000`. | `effectiveTimeoutMs` == 5 000 ms. |
| 2 | Task takes longer than 5 s. | Timeout fires within ~5 s. |
| 3 | `GoalResultPayload` has `status = "timeout"`. | ✔ |

### 1.3 Timeout capped at MAX (5 min)

| # | Step | Expected |
|---|------|----------|
| 1 | Send `timeout_ms = 999 999 999`. | Capped to `MAX_TIMEOUT_MS = 300 000 ms`. |

### 1.4 Parallel subtask timeout

| # | Step | Expected |
|---|------|----------|
| 1 | Send a `parallel_subtask` with `timeout_ms` that is exceeded. | `GoalResultPayload` still contains `group_id` and `subtask_index` echoed from the payload. |

---

## 2. Cancel / Abort

### 2.1 Cancel running task

| # | Step | Expected |
|---|------|----------|
| 1 | Start a long-running `goal_execution` (task_id = `"t-abc"`). | Task is registered in `TaskCancelRegistry`. |
| 2 | Send a `task_cancel` message with `task_id = "t-abc"`. | `TaskCancelRegistry.cancel("t-abc")` returns `true`. |
| 3 | Logcat shows `[TASK:CANCEL] 已取消运行中的任务 task_id=t-abc`. | ✔ |
| 4 | `GalaxyLogger` emits `GALAXY:TASK:CANCEL` with `was_running=true`. | ✔ |
| 5 | Device sends `cancel_result` with `status = "cancelled"` and `was_running = true`. | ✔ |

### 2.2 Cancel already-completed task (no-op / idempotent)

| # | Step | Expected |
|---|------|----------|
| 1 | Send `task_cancel` for a `task_id` that never started or already completed. | `TaskCancelRegistry.cancel(...)` returns `false`. |
| 2 | Device sends `cancel_result` with `status = "no_op"` and `was_running = false`. | ✔ |
| 3 | `error` field contains `"task not found or already completed"`. | ✔ |
| 4 | Repeat the same cancel request. | Same `no_op` response — idempotent. | ✔ |

### 2.3 Cancel parallel subtask

| # | Step | Expected |
|---|------|----------|
| 1 | Send `task_cancel` with `task_id`, `group_id`, and `subtask_index`. | `CancelResultPayload` echoes `group_id` and `subtask_index`. |

---

## 3. Result Payload Schema

All result payloads returned by these paths must contain the following fields:

| Field | goal_result (timeout) | cancel_result (cancelled) | cancel_result (no_op) |
|-------|-----------------------|---------------------------|-----------------------|
| `task_id` | ✔ | ✔ | ✔ |
| `correlation_id` | == task_id | == task_id | == task_id |
| `status` | `"timeout"` | `"cancelled"` | `"no_op"` |
| `device_id` | non-empty | non-empty | non-empty |
| `group_id` | echoed | echoed | echoed |
| `subtask_index` | echoed | echoed | echoed |
| `was_running` | N/A | `true` | `false` |
| `error` | non-null | null | non-null |

---

## 4. Log Tag Verification

Open `DiagnosticsScreen` (ⓘ icon in MainActivity) and confirm the following tags
appear in the log when the corresponding events fire:

| Tag | Event |
|-----|-------|
| `GALAXY:TASK:TIMEOUT` | Task exceeded its timeout budget |
| `GALAXY:TASK:CANCEL` | A `task_cancel` message was processed |

---

## 5. Unit Test Coverage

Run the following test class to verify model-layer behaviour:

```
./gradlew test --tests "com.ufo.galaxy.agent.TaskTimeoutCancelTest"
```

Expected outcome: all tests in `TaskTimeoutCancelTest` pass.
