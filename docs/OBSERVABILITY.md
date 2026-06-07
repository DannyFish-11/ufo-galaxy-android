# Android Device-Side Observability & Tracing

This document describes the structured logging, trace propagation, metrics, and
sampling controls for the UFO Galaxy Android runtime.

---

## Trace Context

Every outbound AIP v3 message carries two trace identifiers for end-to-end
log correlation:

| Field | Type | Description |
|-------|------|-------------|
| `trace_id` | `string` (UUID v4) | Session-level trace identifier. Generated once per WS session; reused in every message within that session. If the server provides a `trace_id` in a downlink message, the client accepts and echoes it in all subsequent uplink messages. |
| `span_id`  | `string` (16 hex chars) | Operation-level span identifier. Identifies a single logical operation (e.g. one task execution, one signaling handshake). Scoped within a `trace_id`. |

### TraceContext API

```kotlin
// Session start (WS open):
TraceContext.reset()                          // generate fresh trace_id; clear span

// Outbound message:
val traceId = TraceContext.currentTraceId()
val spanId  = TraceContext.currentSpanId()    // null when no active span

// Accept server-provided trace_id from a downlink message:
TraceContext.acceptServerTraceId(serverTraceId)

// Task execution span:
val spanId = TraceContext.startSpan()
try { executeTask() } finally { TraceContext.endSpan() }
```

### Server ↔ Client trace_id contract

- Client generates a fresh `trace_id` on every WS session open (`reset()`).
- If the server sends a `trace_id` in a downlink message (`task_assign`, etc.),
  `TraceContext.acceptServerTraceId()` is called to adopt it.
- Subsequent uplink messages (`task_result`, `goal_result`, signaling frames)
  echo the accepted `trace_id` so gateway and runtime logs correlate correctly.
- A blank or null server `trace_id` is ignored — the client-generated ID is kept.

---

## Log Tags

All structured log entries are identified by one of the following stable tag constants
(defined in `GalaxyLogger.kt`):

### Connection lifecycle

| Tag | Constant | When emitted |
|-----|----------|--------------|
| `GALAXY:CONNECT` | `GalaxyLogger.TAG_CONNECT` | WebSocket connection established (`onOpen`) |
| `GALAXY:DISCONNECT` | `GalaxyLogger.TAG_DISCONNECT` | WebSocket closed or failed (`onClosed`, `onFailure`) |
| `GALAXY:RECONNECT` | `GalaxyLogger.TAG_RECONNECT` | Reconnect attempt scheduled (exponential back-off) |

### Task lifecycle

| Tag | Constant | When emitted |
|-----|----------|--------------|
| `GALAXY:TASK:RECV` | `GalaxyLogger.TAG_TASK_RECV` | `task_assign` or `goal_execution` message received |
| `GALAXY:TASK:EXEC` | `GalaxyLogger.TAG_TASK_EXEC` | Task execution started by `EdgeExecutor` |
| `GALAXY:TASK:RETURN` | `GalaxyLogger.TAG_TASK_RETURN` | Task result returned (status + step count) |
| `GALAXY:TASK:TIMEOUT` | `GalaxyLogger.TAG_TASK_TIMEOUT` | Running task exceeded configured timeout budget |
| `GALAXY:TASK:CANCEL` | `GalaxyLogger.TAG_TASK_CANCEL` | `task_cancel` instruction received and processed |

### WebRTC / signaling lifecycle

| Tag | Constant | When emitted |
|-----|----------|--------------|
| `GALAXY:SIGNAL:START` | `GalaxyLogger.TAG_SIGNAL_START` | WebRTC signaling WS connected, session started |
| `GALAXY:SIGNAL:STOP` | `GalaxyLogger.TAG_SIGNAL_STOP` | WebRTC signaling WS closed or disconnected |
| `GALAXY:WEBRTC:TURN` | `GalaxyLogger.TAG_WEBRTC_TURN` | TURN config received, relay candidates applied, or TURN fallback triggered |

### Dispatcher / bridge

| Tag | Constant | When emitted |
|-----|----------|--------------|
| `GALAXY:DISPATCHER:SELECT` | `GalaxyLogger.TAG_DISPATCHER_SELECT` | Dispatcher selected for a task (route_mode + exec_mode resolved) |
| `GALAXY:BRIDGE:HANDOFF` | `GalaxyLogger.TAG_BRIDGE_HANDOFF` | Bridge handoff to Agent Runtime initiated |

### V2 observability / cross-system tracing (PR-G)

| Tag | Constant | When emitted |
|-----|----------|--------------|
| `GALAXY:DISPATCH:DECISION` | `GalaxyLogger.TAG_DISPATCH_DECISION` | Android dispatch decision recorded with full observability context (`dispatch_trace_id`, `executor_target_type`, route) |
| `GALAXY:LIFECYCLE:OBSERVE` | `GalaxyLogger.TAG_LIFECYCLE_OBSERVE` | Device lifecycle event forwarded to V2 observability model (`device_attach`, `device_reconnect`, `device_detach`, etc.) |
| `GALAXY:RECOVERY:OBSERVE` | `GalaxyLogger.TAG_RECOVERY_OBSERVE` | Recovery execution event recorded with cross-system tracing context (`dispatch_trace_id`, `continuity_token`, `is_resumable`) |

### Errors

| Tag | Constant | When emitted |
|-----|----------|--------------|
| `GALAXY:ERROR` | `GalaxyLogger.TAG_ERROR` | Any error; **always** includes `trace_id` and `cause` |

### Readiness

| Tag | Constant | When emitted |
|-----|----------|--------------|
| `GALAXY:READINESS` | `GalaxyLogger.TAG_READINESS` | Readiness self-check completed (`ReadinessChecker`) |
| `GALAXY:DEGRADED` | `GalaxyLogger.TAG_DEGRADED` | Device is in degraded mode (any readiness flag is false) |

### Local loop lifecycle

| Tag | Constant | When emitted |
|-----|----------|--------------|
| `GALAXY:LOCAL_LOOP:START` | `GalaxyLogger.TAG_LOCAL_LOOP_START` | Local-loop goal session starts (instruction received) |
| `GALAXY:LOCAL_LOOP:STEP` | `GalaxyLogger.TAG_LOCAL_LOOP_STEP` | Single step completed within a local-loop session |
| `GALAXY:LOCAL_LOOP:PLAN` | `GalaxyLogger.TAG_LOCAL_LOOP_PLAN` | Planner produced an initial plan or replan |
| `GALAXY:LOCAL_LOOP:DONE` | `GalaxyLogger.TAG_LOCAL_LOOP_DONE` | Local-loop goal session ended (success / failure / cancel) |

---

## Log Entry Schema

Each entry is a single-line JSON object:

```json
{"ts":1710000000000,"tag":"GALAXY:SIGNAL:START","fields":{"trace_id":"abc-123","url":"ws://host:8765","device_id":"samsung_s23","route_mode":"cross_device"}}
```

| Field | Type | Description |
|-------|------|-------------|
| `ts` | `long` | Unix timestamp in milliseconds |
| `tag` | `string` | Stable tag (see tables above) |
| `fields` | `object` | Event-specific key→value pairs |

### Common fields (present in most events)

| Key | Type | Present in |
|-----|------|-----------|
| `trace_id` | `string` | All events (mandatory in `GALAXY:ERROR`) |
| `span_id`  | `string` | Events within a bridge/task span |
| `task_id`  | `string` | Task lifecycle events |
| `device_id`| `string` | Signaling and error events (when available) |
| `session_id`| `string` | Bridge handoff, error events (when available) |
| `route_mode`| `string` | Signaling, dispatcher, bridge events |
| `exec_mode` | `string` | Dispatcher and bridge events |
| `capability`| `string` | Dispatcher and bridge events (when available) |

### `GALAXY:ERROR` required fields

Every `GALAXY:ERROR` entry **must** include:

| Key | Type | Description |
|-----|------|-------------|
| `trace_id` | `string` | Current trace identifier for log correlation |
| `cause` | `string` | Human-readable error description |

### Example entries

```jsonl
{"ts":1710001000000,"tag":"GALAXY:CONNECT","fields":{"url":"ws://192.168.1.10:8080","attempt":0}}
{"ts":1710001100000,"tag":"GALAXY:SIGNAL:START","fields":{"trace_id":"abc-xyz-123","url":"ws://100.64.0.1:8765/ws/webrtc/dev1","device_id":"samsung_s23","route_mode":"cross_device"}}
{"ts":1710001200000,"tag":"GALAXY:DISPATCHER:SELECT","fields":{"trace_id":"abc-xyz-123","span_id":"a1b2c3d4e5f6a7b8","task_id":"task-42","exec_mode":"remote","route_mode":"cross_device","cross_device_on":true}}
{"ts":1710001201000,"tag":"GALAXY:BRIDGE:HANDOFF","fields":{"trace_id":"abc-xyz-123","span_id":"a1b2c3d4e5f6a7b8","task_id":"task-42","exec_mode":"remote","route_mode":"cross_device"}}
{"ts":1710001250000,"tag":"GALAXY:WEBRTC:TURN","fields":{"trace_id":"abc-xyz-123","event":"turn_config_received","urls":2,"device_id":"samsung_s23"}}
{"ts":1710001300000,"tag":"GALAXY:SIGNAL:STOP","fields":{"trace_id":"abc-xyz-123","code":1000,"reason":"Client disconnect","normal":true,"session_ms":200,"device_id":"samsung_s23"}}
{"ts":1710002000000,"tag":"GALAXY:TASK:RECV","fields":{"task_id":"task-abc123","type":"task_assign"}}
{"ts":1710002001000,"tag":"GALAXY:TASK:EXEC","fields":{"task_id":"task-abc123","max_steps":10}}
{"ts":1710002015000,"tag":"GALAXY:TASK:RETURN","fields":{"task_id":"task-abc123","status":"success","steps":3,"error":null}}
{"ts":1710003000000,"tag":"GALAXY:ERROR","fields":{"trace_id":"abc-xyz-123","cause":"signaling_ws_failure","device_id":"samsung_s23","route_mode":"cross_device"}}
{"ts":1710004000000,"tag":"GALAXY:READINESS","fields":{"model_ready":true,"accessibility_ready":false,"overlay_ready":true,"degraded_mode":true}}
{"ts":1710005000000,"tag":"GALAXY:RECONNECT","fields":{"attempt":1,"max_attempts":10,"delay_ms":1342}}
```

---

## Sampling Controls

High-frequency production environments can use `SamplingConfig` to reduce log noise
while preserving full fidelity for critical events.

### Configuration

```kotlin
// Application.onCreate():
GalaxyLogger.samplingConfig = if (BuildConfig.DEBUG) SamplingConfig.debug()
                              else SamplingConfig.production()
```

### Predefined configurations

| Factory | `errorRate` | `taskRate` | `connectionRate` | `signalingRate` | `dispatcherRate` | `turnRate` | `metricsFlushRate` | `defaultRate` |
|---------|------------|-----------|-----------------|----------------|-----------------|-----------|-------------------|--------------|
| `SamplingConfig.debug()` | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 | 1.0 |
| `SamplingConfig.production()` | 1.0 | 1.0 | 0.1 | 0.1 | 1.0 | 0.1 | 0.0 | 0.1 |

### Custom configuration

```kotlin
GalaxyLogger.samplingConfig = SamplingConfig(
    errorRate        = 1.0,   // always capture errors
    taskRate         = 1.0,   // always capture task lifecycle
    connectionRate   = 0.25,  // 25% of WS connect/disconnect/reconnect events
    signalingRate    = 0.5,   // 50% of signaling start/stop events
    dispatcherRate   = 1.0,   // always capture dispatch decisions
    turnRate         = 0.5,   // 50% of TURN events
    metricsFlushRate = 0.0,   // suppress periodic metrics flush in logs
    defaultRate      = 0.1    // 10% of all other events
)
```

### Sampling rules

- `GALAXY:ERROR` is **always** logged regardless of `errorRate` when using `logSampled()`.
- Task lifecycle events (`GALAXY:TASK:*`) use `taskRate`.
- Connection events (`GALAXY:CONNECT`, `GALAXY:DISCONNECT`, `GALAXY:RECONNECT`) use `connectionRate`.
- Signaling events (`GALAXY:SIGNAL:START`, `GALAXY:SIGNAL:STOP`) use `signalingRate`.
- Dispatcher / bridge events (`GALAXY:DISPATCHER:SELECT`, `GALAXY:BRIDGE:HANDOFF`) use `dispatcherRate`.
- TURN events (`GALAXY:WEBRTC:TURN`) use `turnRate`.

---

## Metrics / Telemetry

`MetricsRecorder` tracks the following counters. All metric names use the
`galaxy.*` prefix for namespace isolation in external pipelines.

### Counter metrics

| Metric name | Description |
|-------------|-------------|
| `galaxy.ws.reconnects` | WebSocket reconnection attempts |
| `galaxy.registration.failures` | Cross-device registration failures |
| `galaxy.task.successes` | Tasks completed successfully |
| `galaxy.task.failures` | Tasks failed or errored |
| `galaxy.signaling.successes` | WebRTC signaling sessions completed successfully |
| `galaxy.signaling.failures` | WebRTC signaling sessions ended in error/timeout |
| `galaxy.turn.usages` | WebRTC connections using TURN relay |
| `galaxy.turn.fallbacks` | ICE failures triggering TURN-only fallback |
| `galaxy.handoff.successes` | Bridge handoffs accepted by Agent Runtime |
| `galaxy.handoff.failures` | Bridge handoffs exhausting all retries |
| `galaxy.handoff.fallbacks` | Local executions following a failed bridge handoff |

### Local-loop counter metrics

| Metric name | Description |
|-------------|-------------|
| `local_loop.goal.total` | Total local-loop goal sessions started |
| `local_loop.goal.success` | Goal sessions completed successfully |
| `local_loop.goal.failure` | Goal sessions that ended in failure |
| `local_loop.replan.count` | Replan events triggered within a session |
| `local_loop.fallback.count` | Fallback-tier activations (planner or grounding ladder) |
| `local_loop.no_ui_change.count` | No-UI-change stagnation events detected |
| `local_loop.timeout.count` | Sessions that exceeded their timeout budget |

### Latency metrics

| Metric name | Description |
|-------------|-------------|
| `galaxy.signaling.latency_ms` | Observed signaling session duration in ms |

### Local-loop latency metrics

| Metric name | Description |
|-------------|-------------|
| `local_loop.step.latency_ms` | Per-step wall-clock duration in the local loop |
| `local_loop.planner.latency_ms` | MobileVLM planner inference latency |
| `local_loop.grounding.latency_ms` | SeeClick grounding engine latency |

### TelemetryExporter stub

Attach a real pipeline (OTel, StatsD, custom HTTP):

```kotlin
// In Application.onCreate():
metricsRecorder.telemetryExporter = object : TelemetryExporter {
    override fun incrementCounter(name: String, delta: Int, tags: Map<String, String>) {
        statsd.count(name, delta.toLong(), *tags.entries.map { "${it.key}:${it.value}" }.toTypedArray())
    }
    override fun recordLatency(name: String, valueMs: Long, tags: Map<String, String>) {
        statsd.time(name, valueMs)
    }
    override fun flush() = Unit
}
```

The default is `NoOpTelemetryExporter` (all methods are no-ops), so the app
compiles and runs without any external pipeline configured.

---

## In-Memory Ring Buffer

The logger maintains an in-memory ring buffer of the **last 500 entries**.  
The buffer is accessible via `GalaxyLogger.getEntries()` for programmatic use
(e.g. the diagnostics screen).

Entries older than the buffer capacity are silently dropped.

---

## Log File Location

When `GalaxyLogger.init(context)` has been called (done automatically in
`UFOGalaxyApplication.onCreate()`), entries are also appended to a plain-text
file of JSON lines:

```
<app internal storage>/files/galaxy_observability.log
```

On a development device you can pull the file with:

```bash
adb shell run-as com.ufo.galaxy cat files/galaxy_observability.log
# or pull to your workstation:
adb exec-out run-as com.ufo.galaxy cat files/galaxy_observability.log > /tmp/galaxy.log
```

The file is capped at **2 MB**.  When it reaches that size it is automatically
deleted and a new file is started (simple rotation — no archive is kept).

---

## One-Click Log Export

From the app's main screen, tap the **ⓘ (Info) icon** in the top-right of the
toolbar to open the **Diagnostics** panel.

From the Diagnostics panel:
- Tap the **share icon** (↑) in the top-right, **or**
- Tap the **"Export Logs"** button at the bottom of the panel.

Both actions open the standard Android share-sheet with the log file attached so
you can forward it via e-mail, Slack, ADB pull, etc.

If the log file does not yet exist (e.g. the app was just installed and no events
have been recorded), a toast notification will inform you.

---

## Diagnostics Screen

The **Diagnostics** panel (accessible via the ⓘ icon in the main toolbar) shows
a live snapshot of:

| Section | Fields shown |
|---------|-------------|
| **Connection** | State (Connected / Disconnected), Reconnect attempts, Offline queue depth |
| **Last Task** | Task ID of the most recently received task, Last error reason |
| **Readiness Flags** | Model files, Accessibility service, Overlay permission, Degraded mode |

---

## Wiring Overview

| Source class | Tags emitted |
|---|---|
| `GalaxyWebSocketClient` | `GALAXY:CONNECT`, `GALAXY:DISCONNECT`, `GALAXY:RECONNECT` |
| `GalaxyConnectionService` | `GALAXY:TASK:RECV` (task_assign, goal_execution) |
| `EdgeExecutor` | `GALAXY:TASK:RECV`, `GALAXY:TASK:EXEC`, `GALAXY:TASK:RETURN` |
| `ReadinessChecker` | `GALAXY:READINESS`, `GALAXY:DEGRADED` |
| `WebRTCSignalingClient` | `GALAXY:SIGNAL:START`, `GALAXY:SIGNAL:STOP`, `GALAXY:WEBRTC:TURN`, `GALAXY:ERROR` |
| `AgentRuntimeBridge` | `GALAXY:DISPATCHER:SELECT`, `GALAXY:BRIDGE:HANDOFF`, `GALAXY:ERROR` |
| `DefaultLocalLoopExecutor` (or custom caller) | `GALAXY:LOCAL_LOOP:START`, `GALAXY:LOCAL_LOOP:STEP`, `GALAXY:LOCAL_LOOP:PLAN`, `GALAXY:LOCAL_LOOP:DONE` |

---

## Session Trace / Replay Scaffold

The `trace` package provides a lightweight model to record individual local-loop
execution sessions for diagnostics and post-mortem replay analysis.

### LocalLoopTrace

`LocalLoopTrace` is created at the start of a session and progressively populated:

```kotlin
val trace = LocalLoopTrace(
    sessionId = UUID.randomUUID().toString(),
    originalGoal = goal,
    normalizedGoal = normalizedGoal,        // optional
    readinessSnapshot = readiness           // optional
)

// During execution:
trace.recordPlan(PlanOutput(stepIndex = 0, isReplan = false, actionCount = 3, latencyMs = 250L))
trace.recordGrounding(GroundingOutput(stepId = "step_1", actionType = "tap", confidence = 0.9f, targetFound = true, latencyMs = 80L))
trace.recordAction(ActionRecord(stepId = "step_1", actionType = "tap", intent = "Tap the icon", dispatchedAt = System.currentTimeMillis(), succeeded = true))
trace.recordStep(stepObservation)

// Session end:
trace.complete(TerminalResult(status = TerminalResult.STATUS_SUCCESS, stopReason = "task_complete", error = null, totalSteps = trace.stepCount))
```

**Design constraints**: raw screenshots are never stored. Only summarised metadata
(step counts, latencies, observations) is kept to minimise memory usage.

### LocalLoopTraceStore

`LocalLoopTraceStore` is an in-memory bounded store that retains the last
`maxTraces` (default: 20) session traces:

```kotlin
val traceStore = LocalLoopTraceStore()   // or LocalLoopTraceStore(maxTraces = 50)

traceStore.beginTrace(trace)             // register at session start
traceStore.completeTrace(sessionId)      // log completion at session end

val recent  = traceStore.allTraces()     // newest-first snapshot
val done    = traceStore.completedTraces()
val running = traceStore.runningTraces()
val single  = traceStore.getTrace(sessionId)
```

Traces are held in memory only; they are not persisted across process restarts.
Use `GalaxyLogger` / the log file for durable session history.

---

## Local-Loop Config Centralization

`LocalLoopConfig` is the central configuration model for the local execution
pipeline, consolidating values that were previously scattered across call sites.

```kotlin
// Use all defaults (matches prior behavior):
val config = LocalLoopConfig.defaults()

// Custom override:
val config = LocalLoopConfig(
    maxSteps = 15,
    goalTimeoutMs = 120_000L,
    planner = PlannerConfig(maxTokens = 1024, temperature = 0.05),
    grounding = GroundingConfig(scaledMaxEdge = 1080),
    fallback = FallbackConfig(enableRemoteHandoff = true)
)
```

### Sub-configs

| Type | Purpose |
|------|---------|
| `PlannerConfig` | MobileVLM token budget, temperature, inference timeout |
| `GroundingConfig` | SeeClick timeout, screenshot scaling limit |
| `FallbackConfig` | Planner/grounding fallback ladder toggles, remote handoff flag |

---

## Implementation Files

| File | Purpose |
|------|---------|
| `app/src/main/java/com/ufo/galaxy/observability/GalaxyLogger.kt` | Singleton structured logger (ring buffer + file + new tags) |
| `app/src/main/java/com/ufo/galaxy/observability/TraceContext.kt` | Thread-safe trace_id/span_id holder; accepts server IDs |
| `app/src/main/java/com/ufo/galaxy/observability/SamplingConfig.kt` | Per-tag sampling controls; debug/production presets |
| `app/src/main/java/com/ufo/galaxy/observability/TelemetryExporter.kt` | Pluggable telemetry export interface + NoOp default |
| `app/src/main/java/com/ufo/galaxy/observability/MetricsRecorder.kt` | Counters + latency + exporter integration |
| `app/src/main/java/com/ufo/galaxy/webrtc/WebRTCSignalingClient.kt` | Structured logging; trace passthrough from server messages |
| `app/src/main/java/com/ufo/galaxy/agent/AgentRuntimeBridge.kt` | Dispatcher-select + bridge-handoff logs; span_id support |
| `app/src/main/java/com/ufo/galaxy/ui/components/DiagnosticsScreen.kt` | Diagnostics Composable + `shareLogs()` helper |
| `app/src/main/res/xml/file_provider_paths.xml` | FileProvider path config for log sharing |
| `app/src/main/java/com/ufo/galaxy/config/LocalLoopConfig.kt` | Central local-loop config model |
| `app/src/main/java/com/ufo/galaxy/trace/LocalLoopTrace.kt` | Session trace model with append helpers |
| `app/src/main/java/com/ufo/galaxy/trace/LocalLoopTraceStore.kt` | Bounded in-memory trace store |
| `app/src/test/java/com/ufo/galaxy/observability/GalaxyLoggerTest.kt` | JVM unit tests for GalaxyLogger |
| `app/src/test/java/com/ufo/galaxy/observability/TracePropagationTest.kt` | JVM unit tests for TraceContext |
| `app/src/test/java/com/ufo/galaxy/observability/SamplingConfigTest.kt` | JVM unit tests for SamplingConfig + logSampled/logError |
| `app/src/test/java/com/ufo/galaxy/observability/ObservabilityMetricsTest.kt` | JVM unit tests for cross-device counters and TelemetryExporter |
| `app/src/test/java/com/ufo/galaxy/observability/LocalLoopMetricsTest.kt` | JVM unit tests for local-loop metrics |
| `app/src/test/java/com/ufo/galaxy/trace/LocalLoopTraceTest.kt` | JVM unit tests for LocalLoopTrace |
| `app/src/test/java/com/ufo/galaxy/trace/LocalLoopTraceStoreTest.kt` | JVM unit tests for LocalLoopTraceStore |
| `app/src/test/java/com/ufo/galaxy/config/LocalLoopConfigTest.kt` | JVM unit tests for LocalLoopConfig |
| `docs/OBSERVABILITY.md` | This document |

