# UFO Galaxy Android — Execution Flows

This document maps how work moves through the system in both operating modes. Use it to understand task dispatch, on-device execution, gateway coordination, and runtime lifecycle transitions.

---

## 1. Local execution chain

Triggered when `cross_device_enabled = false` (the default) or when `InputRouter` determines the device is in local-only mode.

```
User text/voice
       │
       ▼
NaturalLanguageInputManager
  (speech package)
  — collects input, no routing decisions
       │
       ▼
InputRouter.route(text)
  (input package) ← SOLE routing decision point
  crossDeviceEnabled = false  →  launch local execution
       │
       ▼
LocalLoopExecutor.execute(LocalLoopOptions)
  (local package)
  — creates sessionId, delegates to LoopController
       │
       ▼
LoopController.execute(instruction)
  (loop package)
  ┌─────────────────────────────────────────────┐
  │  Per-step loop (up to maxSteps)             │
  │                                             │
  │  1. Capture screenshot (JPEG)               │
  │       ↓                                     │
  │  2. MobileVLM 1.7B planner                  │
  │     LocalPlannerService (inference/)        │
  │       ↓                                     │
  │  3. Stagnation / plan-repeat guard          │
  │     StagnationDetector (local/)             │
  │       ↓                                     │
  │  4. SeeClick grounding                      │
  │     LocalGroundingService (inference/)      │
  │     — resolves x/y on-device; never from    │
  │       gateway                               │
  │       ↓                                     │
  │  5. AccessibilityService action dispatch    │
  │     AccessibilityActionExecutor (agent/)    │
  │       ↓                                     │
  │  6. Post-action screenshot + observer       │
  │     PostActionObserver (local/)             │
  │       ↓                                     │
  │  Repeat or terminate (success/budget/       │
  │  stagnation/timeout)                        │
  └─────────────────────────────────────────────┘
       │
       ▼
LocalLoopResult
  — delivered to UI via MainViewModel / EnhancedFloatingService
  — trace persisted to LocalLoopTraceStore (trace/)
  — session summary persisted to SessionHistoryStore (history/)
```

**Observability on this path:**
- `GalaxyLogger` tags: `TAG_LOCAL_LOOP_*`
- `MetricsRecorder` counters: `local_loop.*` family (10 metrics)
- `LocalLoopTrace` per-step records emitted for the debug panel

---

## 2. Cross-device chain — user-initiated uplink

Triggered when `cross_device_enabled = true` and the WS connection is active. The user submits a task; it is sent to the gateway for central dispatch.

```
User text/voice
       │
       ▼
NaturalLanguageInputManager → InputRouter.route(text)
  crossDeviceEnabled = true AND WS connected
       │
       ▼
GatewayClient.sendJson(TaskSubmitPayload)
  (network/)
  — wraps text in AIP v3 task_submit envelope
  — source_node = localDeviceId, target_node = "Galaxy"
       │  WS uplink
       ▼
Galaxy Gateway
  — assigns task to capable device(s)
  — sends task_assign / goal_execution back to this device
         or to another device in the network
```

If WS is NOT connected when `crossDeviceEnabled = true`, `InputRouter` calls `onError` with a human-readable reason. It does **not** silently fall back to local execution.

---

## 3. Cross-device chain — gateway-assigned task inbound

The gateway sends a task to this device; it is executed locally and the result is returned.

```
Galaxy Gateway
  — sends task_assign / goal_execution / parallel_subtask / task_cancel
       │  WS downlink
       ▼
GalaxyWebSocketClient.onMessage()
  (network/) — notifies registered Listener
       │
       ▼
GalaxyConnectionService (WS listener)
  (service/)
  — parses AipMessage type
  — calls runtimeController.onRemoteTaskStarted()
    (cancels any running local LoopController session;
     sets LoopController.isRemoteTaskActive = true)

  ┌─────────── task routing ──────────────────────────────┐
  │                                                        │
  │  task_assign                                           │
  │    crossDeviceEnabled=true                             │
  │    AND require_local_agent=false                       │
  │    AND exec_mode ∈ {remote, both}?                     │
  │      YES → AgentRuntimeBridge.handoff(HandoffRequest)  │
  │      NO  → EdgeExecutor.handleTaskAssign(payload)      │
  │                                                        │
  │  goal_execution / parallel_subtask                     │
  │      → LocalGoalExecutor.executeGoal(payload)          │
  │        (wraps EdgeExecutor internally)                 │
  │                                                        │
  │  task_cancel                                           │
  │      → TaskCancelRegistry.cancel(task_id)              │
  │        (cooperative coroutine cancellation)            │
  └────────────────────────────────────────────────────────┘
       │
       ▼
EdgeExecutor / AgentRuntimeBridge produces result
  — TaskResultPayload / GoalResultPayload / CancelResultPayload
       │
       ▼
GalaxyWebSocketClient.sendJson(result)
  — if WS disconnected: enqueued in OfflineTaskQueue
    (max 50 msgs, 24 h TTL; replayed FIFO on reconnect)
       │
       ▼
runtimeController.onRemoteTaskFinished()
  — clears isRemoteTaskActive; local execution may resume

OpenClawdMemoryBackflow
  — route_mode = "cross_device"
```

### AgentRuntimeBridge handoff detail

```
AgentRuntimeBridge.handoff(HandoffRequest)
  — checks idempotency cache (ConcurrentHashMap, 200 entries)
  — eligible if: crossDeviceEnabled=true AND exec_mode ∈ {remote, both}
  — sends bridge_handoff AIP v3 message via GatewayClient
    with: trace_id, task_id, exec_mode, route_mode, capability, session_id, context
  — timeout 30 s, 3 retries, backoff 1/2/4 s
  — MetricsRecorder: handoffSuccesses / handoffFailures / handoffFallbacks
```

---

## 4. EdgeExecutor pipeline (on-device)

Both the local chain (via `LoopController`) and the cross-device inbound chain (via `GalaxyConnectionService`) ultimately use `EdgeExecutor` for on-device execution.

```
EdgeExecutor.handleTaskAssign(TaskAssignPayload)
       │
       ├─ require_local_agent == false?  → return CANCELLED immediately
       ├─ planner / grounding not loaded? → return ERROR (model gating)
       │
       ▼
  Per-step loop (capped by payload.max_steps)
  ┌──────────────────────────────────────────┐
  │  1. screenshotProvider.captureJpeg()     │
  │  2. Optional: imageScaler (max edge 720) │
  │  3. LocalPlannerService.plan()           │
  │     → ActionSequence                     │
  │  4. LocalGroundingService.ground()       │
  │     → screen coordinates (on-device)     │
  │  5. imageScaler.remapCoordinates()       │
  │     (full-res before AccessibilityService)│
  │  6. AccessibilityActionExecutor.execute()│
  │  7. Build StepResult + CommandResultPayload│
  │  Repeat or terminate                     │
  └──────────────────────────────────────────┘
       │
       ▼
  TaskResultPayload
    correlation_id = task_id (always)
    status ∈ {success, error, timeout, cancelled}
```

Exceptional conditions (any uncaught exception) are mapped to `ERROR` status; `EdgeExecutor` never throws.

---

## 5. Runtime lifecycle (cross-device)

`RuntimeController` is the **sole authority** for WS connect/disconnect transitions. No other component makes these decisions.

```
                  ┌──────────────────────────────┐
                  │       RuntimeState           │
                  │                              │
      ┌──────────►│  LocalOnly (default)         │◄──────────────┐
      │           │  WS disconnected             │               │
      │           └──────────┬───────────────────┘               │
      │                      │ startWithTimeout() / start()       │
      │                      ▼                                    │
      │           ┌──────────────────────┐                        │
      │           │  Connecting          │ timeout or failure     │
      │           │  WS connecting       │ ─────────────────────► emits registrationError
      │           └──────────┬───────────┘    ↓                   │
      │                      │ onOpen +       fallback to LocalOnly│
      │                      │ registration_ack                   │
      │                      ▼                                    │
      │           ┌──────────────────────┐                        │
      │           │  Active              │                        │
      │           │  WS open, cross-     │ stop() ───────────────►│
      │           │  device enabled      │                        │
      │           └──────────────────────┘                        │
      │                                                           │
      └── connectIfEnabled()  (background restore on service      │
          restart / activity resume; best-effort, no error emit)  │
```

**Key lifecycle contract:**
- `startWithTimeout()` → `start()`: first runs a pre-flight capability check (accessibility, overlay); on capability failure emits `registrationError` as `CrossDeviceEnablementError.CapabilityError` immediately. If capabilities are satisfied, enables WS and must register within `registrationTimeoutMs`; on network failure emits `CrossDeviceEnablementError.NetworkError` and resets `crossDeviceEnabled = false`.
- `stop()`: cleanly disconnects WS, sets `crossDeviceEnabled = false`, transitions to `LocalOnly`.
- `connectIfEnabled()`: called on service restart / resume; syncs WS state without modifying settings or emitting errors on transient failure.
- Both `MainActivity` and `EnhancedFloatingService` observe `registrationError: SharedFlow<CrossDeviceEnablementError>` to show a typed dialog — `CAPABILITY` errors show "去设置", `NETWORK` errors show "重试" + "查看诊断/设置".

---

## 6. Cancellation model

```
Gateway → task_cancel { task_id }
                │
                ▼
GalaxyConnectionService.handleTaskCancel()
                │
                ▼
TaskCancelRegistry.cancel(task_id)
  — looks up coroutine Job by task_id
  — calls job.cancel(CancellationException)
  — Job was registered inside launch{} via coroutineContext[Job]
  — deregistered in finally block of executing coroutine
                │
                ▼
GalaxyConnectionService sends CancelResultPayload { task_id, status="cancelled" }
```

Timeout cancellation uses `GoalExecutionPayload.effectiveTimeoutMs`:
- default 30 s
- maximum 5 min
- enforced via `withTimeout { … }` around the execution coroutine

---

## 7. Offline replay

```
WS disconnected (onFailure / onClosed)
       │
       ▼
OfflineTaskQueue.enqueue(json)   [if message type is task_result or goal_result]
  — max 50 messages (drop-oldest policy)
  — persisted to SharedPreferences
  — messages older than 24 h are dropped on load

WS reconnected (onOpen)
       │
       ▼
OfflineTaskQueue.drainTo { json → GalaxyWebSocketClient.sendJson(json) }
  — FIFO order
  — queue size exposed via StateFlow<Int> queueSize
    (visible in DiagnosticsScreen)
```
