# Android Lifecycle, Recovery, and Hybrid-Participant Integration

> **PR-60**
> Introduced in: `ufo-galaxy-android`
> Audience: V2 orchestration engineers, Android platform engineers, distributed runtime reviewers

---

## Overview

This document provides the canonical reviewable reference for:

1. **How Android behaves across lifecycle changes and reconnect scenarios**
2. **What Android can recover locally vs what must be re-synchronized from V2**
3. **Whether Android participant behavior supports the hybrid/distributed runtime model**
4. **What lifecycle/recovery limitations remain intentionally out of scope**

Android remains a **participant-side runtime component**, not an orchestration authority.
All recovery coordination, task retry, and formation decisions belong to V2.

---

## 1. Android App Lifecycle Transitions

### Explicit lifecycle model

`AndroidAppLifecycleTransition` names every app-level lifecycle event relevant to the
cross-device participant runtime.  Each transition has a documented, testable runtime response.

| Transition | Wire value | Runtime action | Call site |
|---|---|---|---|
| `FOREGROUND` | `foreground` | `connectIfEnabled()` — restore WS if cross-device on | `MainViewModel.onResume()`, `GalaxyConnectionService.onStartCommand()` |
| `BACKGROUND` | `background` | **No-op** — WS preserved; background execution continues | `MainViewModel.onPause()` |
| `PROCESS_RECREATED` | `process_recreated` | `connectIfEnabled()` via background-restore; new attachment era | Application.onCreate() or equivalent |
| `RUNTIME_STOPPED` | `runtime_stopped` | `RuntimeController.stop()` — disconnect WS, detach session | User toggle off / kill-switch |
| `CONFIGURATION_CHANGE` | `configuration_change` | **No-op** — `RuntimeController` is process-scoped | Activity recreation (rotation etc.) |

All transitions are logged on `GalaxyLogger.TAG_APP_LIFECYCLE` (`"GALAXY:APP:LIFECYCLE"`).

### Why BACKGROUND is a no-op

Android does not disconnect when backgrounded.  The runtime (held by
`UFOGalaxyApplication`) continues accepting delegated tasks via the foreground
service (`GalaxyConnectionService`) while the app UI is invisible.  This is intentional:
terminating the WS on every background transition would create unnecessary reconnect
latency for users who quickly background+foreground the app.

---

## 2. Reconnect and Recovery Semantics

### Transient WS disconnect (auto-reconnect)

When the WS drops while the runtime is `Active`, Android:

1. Closes the `AttachedRuntimeSession` with `DetachCause.DISCONNECT`
2. Sets `reconnectRecoveryState` → `RECOVERING` (UI shows "Recovering…")
3. WS client's exponential-backoff reconnect runs automatically
4. On reconnect: `openAttachedSession(RECONNECT_RECOVERY)` is called
   - Same `runtime_attachment_session_id` is reused
   - `DurableSessionContinuityRecord.sessionContinuityEpoch` is incremented
   - `V2MultiDeviceLifecycleEvent.DeviceReconnected` is emitted
5. `reconnectRecoveryState` → `RECOVERED`

**Continuity ordering guarantee**: when a consumer observes `RECOVERED`,
`durableSessionContinuityRecord` already carries the incremented epoch, and
`V2MultiDeviceLifecycleEvent.DeviceReconnected` has already been emitted.

### Reconnect failure

When all reconnect attempts are exhausted:

1. `reconnectRecoveryState` → `FAILED`
2. UI shows "Connection failed — please reconnect" CTA
3. **User action required**: user must explicitly tap reconnect or re-enable cross-device
4. Android does NOT autonomously restart the runtime after reconnect failure

**Intentional limitation**: autonomous restart on reconnect failure is out of scope.
V2 must treat the participant as unavailable until `DeviceConnected` is re-observed.

### Process recreation

When Android's low-memory killer kills and recreates the process:

| State | Survives? | Notes |
|---|---|---|
| `crossDeviceEnabled` (AppSettings) | ✅ Yes | Persisted in SharedPreferences |
| `gatewayHost`/`gatewayPort`/`useTls` | ✅ Yes | Persisted in SharedPreferences |
| `deviceId` | ✅ Yes | Persisted in SharedPreferences |
| `DurableSessionContinuityRecord` | ❌ No | Process-scoped; new era on restart |
| `_runtimeAttachmentSessionId` | ❌ No | Regenerated fresh; new attachment era |
| `ReconnectRecoveryState` | ❌ No | Resets to IDLE |
| `RuntimeController.RuntimeState` | ❌ No | Resets to Idle/Starting |
| In-flight task state | ❌ No | Lost; V2 must handle via timeout/retry |

After process recreation, Android emits `V2MultiDeviceLifecycleEvent.DeviceConnected`
(NOT `DeviceReconnected`) because the attachment identity is fresh.  V2 must treat
process recreation as a **new attachment era**, not a resume.

---

## 3. Hybrid / Distributed Participant Capabilities

### Current capability status

`HybridParticipantCapability` provides the structured, explicit model for every
hybrid/distributed execution capability.  Status is `AVAILABLE`, `MINIMAL_COMPAT`,
or `NOT_YET_IMPLEMENTED`.

| Capability | Wire value | Status | Notes |
|---|---|---|---|
| Full `hybrid_execute` | `hybrid_execute_full` | `NOT_YET_IMPLEMENTED` | Degrade reply sent; V2 must apply fallback |
| Staged mesh subtask | `staged_mesh_subtask` | `AVAILABLE` | `StagedMeshExecutionTarget`, fully tested |
| Parallel subtask | `parallel_subtask` | `AVAILABLE` | `GalaxyConnectionService` pipeline |
| WebRTC peer transport | `webrtc_peer_transport` | `MINIMAL_COMPAT` | Stubs only; not production-ready |
| Barrier coordination | `barrier_coordination` | `NOT_YET_IMPLEMENTED` | V2 authority; Android not a barrier participant |

### `HYBRID_EXECUTE` limitation — non-silent degrade

When Android receives `hybrid_execute`, it:
1. Logs `GalaxyLogger.TAG_HYBRID_PARTICIPANT` (`"GALAXY:HYBRID:PARTICIPANT"`) with:
   - `capability = "hybrid_execute_full"`
   - `support_level = "not_yet_implemented"`
   - `task_id` (from the inbound payload)
2. Sends a structured `hybrid_degrade` reply with `reason = "hybrid_executor_not_implemented"`

V2 must apply its own fallback policy (e.g., full-remote execution) on receiving this reply.

This is an **intentional deferral**, not an accidental omission.

### What Android explicitly does NOT do

Android deliberately avoids these to prevent becoming a second orchestration authority:

- ❌ Android does NOT make barrier/merge coordination decisions
- ❌ Android does NOT retry tasks after failure (V2 decides retry policy)
- ❌ Android does NOT coordinate formation rebalancing (V2 decides; Android reports health/readiness)
- ❌ Android does NOT generate `continuity_token` (V2 generates; Android echoes back)
- ❌ Android does NOT resume interrupted tasks unilaterally after reconnect

---

## 4. Durability and Readiness

### What state is durable (persisted)

- `AppSettings` (SharedPreferences): connection config, device identity, feature flags
- All `AppSettings` fields — these survive process recreation

### What state is volatile (in-memory, process-scoped)

- `DurableSessionContinuityRecord`: lost on process death; new era starts on restart
- `_runtimeAttachmentSessionId`: regenerated on each new activation era
- `_reconciliationEpoch`: resets to 0 on process restart
- `AttachedRuntimeSession`: in-memory; re-established on reconnect
- `ReconnectRecoveryState`: resets to IDLE on stop/restart
- In-flight task state: not persisted; V2 is the canonical task-state authority

### Durability intentional limitation

Android does not persist the `DurableSessionContinuityRecord` to disk.  This is
intentional: the record is an in-process coordination aid, not a canonical source of
truth.  V2 is the canonical source of session and task truth.  After process recreation,
V2 re-establishes the session based on the fresh `DeviceConnected` signal.

---

## 5. V2 Lifecycle Event → Android Action Mapping

```
V2 receives                             Android emitted
──────────────────────────────────────────────────────
DeviceConnected (user_activation)     ← start() or connectIfEnabled(crossDeviceOn=true)
DeviceConnected (background_restore)  ← connectIfEnabled() after process recreate / service start
DeviceReconnected                     ← transparent WS reconnect (RECONNECT_RECOVERY)
DeviceDisconnected (disconnect)       ← WS drop (isResumable=true → V2 suspends session)
DeviceDisconnected (disable/invalid)  ← stop() or invalidateSession() (isResumable=false → V2 terminates)
DeviceHealthChanged / Degraded        ← notifyParticipantHealthChanged(DEGRADED/FAILED)
ParticipantReadinessChanged           ← notifyParticipantHealthChanged(readiness changed)
```

---

## 6. Intentional Limitations (Out of Scope for PR-60)

The following are **intentional** and remain out of scope:

| Limitation | Reason |
|---|---|
| Full `HYBRID_EXECUTE` executor | Requires dedicated Android hybrid executor component; explicitly deferred |
| WebRTC peer transport for distributed participant | Not production-ready; minimal-compat stubs only |
| Barrier/merge coordination participation | Android is not a barrier authority; V2 owns this |
| Persisted `DurableSessionContinuityRecord` (survive process death) | V2 is the canonical session authority; no need for Android-side persistence |
| Autonomous runtime restart after reconnect failure | User action required; autonomous restart is out of scope |
| Task state persistence across process recreation | V2 is the canonical task state authority; Android does not duplicate this |

---

## Related Surfaces

| Surface | Purpose | PR |
|---|---|---|
| `AndroidAppLifecycleTransition` | Explicit app lifecycle event model | PR-60 |
| `HybridParticipantCapability` | Structured hybrid capability status | PR-60 |
| `AndroidLifecycleRecoveryContract` | Authoritative recovery boundary documentation | PR-60 |
| `GalaxyLogger.TAG_APP_LIFECYCLE` | Structured log tag for app lifecycle transitions | PR-60 |
| `GalaxyLogger.TAG_HYBRID_PARTICIPANT` | Structured log tag for hybrid capability limitations | PR-60 |
| `RuntimeController.onAppLifecycleTransition` | Explicit lifecycle handler method | PR-60 |
| `ReconnectRecoveryState` | WS reconnect recovery lifecycle phases | PR-33 |
| `DurableSessionContinuityRecord` | Durable session identity across WS reconnects | PR-1 |
| `V2MultiDeviceLifecycleEvent` | V2-consumable lifecycle events | PR-43/PR-44 |
| `ContinuityRecoveryContext` | Recovery participant role and token boundary docs | PR-F |
| `RUNTIME_TRUTH_RECONCILIATION.md` | Android runtime truth and V2 reconciliation | PR-51/PR-52 |
