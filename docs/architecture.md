# UFO Galaxy Android — Architecture

This document describes the canonical architecture of UFO Galaxy Android as of v3.0.0. It is the authoritative reference for component roles, package ownership, and authority boundaries.

---

## System overview

UFO Galaxy Android is an on-device AI agent that can execute natural-language goals autonomously using local ML models (MobileVLM 1.7B planner + SeeClick grounding) and Android AccessibilityService. Optionally, it participates in a distributed Galaxy Gateway network where tasks may be assigned by the server or delegated to Agent Runtime.

### Two operating chains

| Chain | Trigger | Task source | Result destination |
|-------|---------|-------------|-------------------|
| **Local** | User text/voice input with `cross_device_enabled=false` | InputRouter → local pipeline | UI (MainViewModel / FloatingService) |
| **Cross-device** | Any of: user input with WS connected; gateway `task_assign`; gateway `goal_execution` | InputRouter → gateway uplink *or* GalaxyConnectionService ← WS | Gateway via WS |

Both chains use the same on-device execution engine (`EdgeExecutor`). Only the entry point and result routing differ.

---

## Canonical component index

### Input and routing layer

| Component | Package | Role |
|-----------|---------|------|
| `NaturalLanguageInputManager` | `speech` | Collects text/voice input; forwards to `InputRouter`. Makes no routing decisions. |
| `InputRouter` | `input` | **Sole user-input dispatch gate.** Decides local vs cross-device routing for every user-initiated task. |

### Local execution pipeline

| Component | Package | Role |
|-----------|---------|------|
| `LocalLoopExecutor` | `local` | Public entry point for local execution. Wraps `LoopController`; exposes stable `LocalLoopResult`. |
| `LoopController` | `loop` | Orchestrates the full local closed-loop: screenshot → MobileVLM planner → stagnation guard → SeeClick grounding → AccessibilityService → repeat. |
| `EdgeExecutor` | `agent` | On-device AIP v3 task execution pipeline. Also used by the cross-device inbound path. |
| `LocalGoalExecutor` | `agent` | Wraps `EdgeExecutor` for `goal_execution` / `parallel_subtask` payloads from the gateway. |
| `LocalLoopConfig` | `config` | Configuration model (timeouts, step budget, planner/grounding params) sourced from `AppSettings`. |

### Cross-device / gateway layer

| Component | Package | Role |
|-----------|---------|------|
| `GalaxyWebSocketClient` | `network` | **Sole cross-device uplink backbone.** Manages WS lifecycle, handshake, heartbeats, offline replay, reconnect backoff. |
| `GatewayClient` | `network` | Thin wrapper around `GalaxyWebSocketClient.sendJson`. Used by `InputRouter` for task-submit uplink. |
| `GalaxyConnectionService` | `service` | Android `Service` that owns the inbound WS message loop. Dispatches `task_assign`, `goal_execution`, `parallel_subtask`, `task_cancel` from the gateway. |
| `AgentRuntimeBridge` | `agent` | Bridges eligible tasks to Agent Runtime / OpenClawd when `crossDeviceEnabled=true` and `exec_mode` is remote/both. Idempotent with a 200-entry cache. |
| `RuntimeController` | `runtime` | **Sole cross-device lifecycle authority.** All WS connect/disconnect and `crossDeviceEnabled` toggle decisions must go through this class. |
| `RegistrationFailureNotifier` | `ui` | Singleton `SharedFlow` that surfaces registration errors as dialogs in MainActivity and EnhancedFloatingService. |

### Observability

| Component | Package | Role |
|-----------|---------|------|
| `GalaxyLogger` | `observability` | Structured ring-buffer logger (500 entries) + file sink (`galaxy_observability.log`, 2 MB cap). |
| `MetricsRecorder` | `observability` | In-process counters and latency lists. Optional POST to `metricsEndpoint`. Logs every 5 min. |
| `TraceContext` | `observability` | Carries `trace_id` / `span_id` for end-to-end log correlation. |
| `SamplingConfig` | `observability` | Per-tag sample rates; ships `debug` and `production` presets. |
| `TelemetryExporter` | `observability` | Pluggable export interface (default: `NoOpTelemetryExporter`). |

### Debug tooling

| Component | Package | Role |
|-----------|---------|------|
| `LocalLoopDebugState` / `LocalLoopDebugViewModel` | `debug` | In-process trace viewer state for the developer panel. |
| `LocalLoopTrace` / `LocalLoopTraceStore` | `trace` | Per-step trace records; ring-buffer store for the debug panel. |
| `SessionHistoryStore` | `history` | Persists completed-session summaries for the history panel. |
| `DiagnosticsScreen` | `ui/components` | Composable showing connection state, readiness flags, offline queue size. Opened via ⓘ icon. |
| `LocalLoopDebugPanel` | `ui/components` | Composable showing local-loop traces and session history. Opened via 🐛 icon. |

### Service / system layer

| Component | Package | Role |
|-----------|---------|------|
| `EnhancedFloatingService` | `service` | Persistent overlay entry point; observes `RegistrationFailureNotifier`. |
| `ReadinessChecker` | `service` | Checks model-files-on-disk, AccessibilityService enabled, and overlay permission; produces `ReadinessState`. |
| `BootReceiver` | `service` | Starts `GalaxyConnectionService` (and cross-device restore if enabled) on device boot. |
| `NetworkDiagnostics` | `network` | DNS / HTTP / WS / AIP connectivity checks. |
| `OfflineTaskQueue` | `network` | Buffers `task_result` / `goal_result` envelopes (max 50, 24 h TTL) for replay on reconnect. |
| `TailscaleAdapter` | `network` | Detects local Tailscale IP via `NetworkInterface.getNetworkInterfaces()`. |

### WebRTC / signaling

| Component | Package | Role |
|-----------|---------|------|
| `WebRTCSignalingClient` | `webrtc` | Signaling channel; accepts server `trace_id` and `span_id`. |
| `IceCandidateManager` | `webrtc` | Dedup, relay > srflx > host ordering, TURN fallback. |
| `TurnConfig` | `webrtc` | TURN server configuration model. |

---

## Authority boundaries

### Input authority

`InputRouter` is the **single point** where the local-vs-cross-device routing decision is made for user-initiated tasks. No component may bypass it to call `LocalLoopExecutor.execute` or `GatewayClient.sendJson` directly for user input.

### Lifecycle authority

`RuntimeController` is the **sole authority** for WS connect/disconnect and `AppSettings.crossDeviceEnabled` mutations. No other component should call `GalaxyWebSocketClient.connect`, `GalaxyWebSocketClient.disconnect`, or toggle `crossDeviceEnabled` directly.

### Uplink authority

`GalaxyWebSocketClient` is the **sole cross-device uplink**. All outbound cross-device messages — device registration, capability reports, heartbeats, task results, goal results, cancel results — flow through this class. Use `GatewayClient` (the thin wrapper) for task-submit calls.

---

## Legacy / compatibility components

These components are present in the codebase for backward compatibility or diagnostic use only. New code must not depend on them for primary functionality.

| Component | Package | Status | Notes |
|-----------|---------|--------|-------|
| `GalaxyApiClient.registerDevice` | `api` | `@Deprecated` | REST-based device registration; superseded by WS `capability_report` on connect. Retained for diagnostic REST endpoint checks only. |
| `GalaxyApiClient.sendHeartbeat` | `api` | `@Deprecated` | REST heartbeat; superseded by WS `heartbeat` messages every 30 s. |
| `MessageRouter` | `network` | Removed | Was superseded by `InputRouter`; no longer present in the codebase. |

If you encounter code that calls these deprecated methods for anything other than diagnostics, prefer the canonical WS-based paths documented above.

---

## Package map

```
com.ufo.galaxy
├── agent/          EdgeExecutor, LocalGoalExecutor, AgentRuntimeBridge, TaskCancelRegistry
├── api/            GalaxyApiClient [LEGACY – diagnostic only]
├── config/         LocalLoopConfig
├── coordination/   (coordination support types)
├── data/           AppSettings, protocol data models
├── debug/          LocalLoopDebugState, LocalLoopDebugViewModel
├── grounding/      Grounding support types
├── history/        SessionHistorySummary, SessionHistoryStore
├── inference/      LocalPlannerService, LocalGroundingService
├── input/          InputRouter ← canonical user-input gate
├── integration/    Integration support
├── local/          LocalLoopExecutor, LocalLoopResult, LocalLoopOptions
├── loop/           LoopController
├── memory/         OpenClawdMemoryBackflow
├── model/          ModelAssetManager, ModelDownloader
├── network/        GalaxyWebSocketClient, GatewayClient, OfflineTaskQueue,
│                   NetworkDiagnostics, TailscaleAdapter
├── nlp/            GoalNormalizer
├── observability/  GalaxyLogger, MetricsRecorder, TraceContext, SamplingConfig,
│                   TelemetryExporter
├── planner/        Planner support types
├── protocol/       AipModels (AIP v3.0 message types)
├── runtime/        RuntimeController ← cross-device lifecycle authority
├── service/        GalaxyConnectionService, EnhancedFloatingService, ReadinessChecker,
│                   BootReceiver
├── speech/         NaturalLanguageInputManager
├── trace/          LocalLoopTrace, LocalLoopTraceStore
├── ui/             MainActivity, MainViewModel, RegistrationFailureNotifier,
│                   composables (DiagnosticsScreen, LocalLoopDebugPanel, …)
└── webrtc/         WebRTCSignalingClient, IceCandidateManager, TurnConfig
```
