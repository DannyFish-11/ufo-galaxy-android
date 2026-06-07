# Android Lifecycle, Recovery, and Hybrid-Participant Integration

> **PR-60 / PR-7**
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
| `PROCESS_RECREATED` | `process_recreated` | `connectIfEnabled()` via background-restore; new attachment era with prior-session hint | Application.onCreate() or equivalent |
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

### Reconnect failure and watchdog recovery (PR-Block1)

When the initial reconnect-attempt ceiling is reached:

1. `reconnectRecoveryState` → `FAILED`
2. UI shows "Connection failed — retrying…" indicator
3. **Android does NOT stop reconnecting**: the WS client enters a watchdog cycle, scheduling
   further attempts at the capped 30 s backoff interval indefinitely (as long as cross-device is on)
4. After `RuntimeController.WATCHDOG_RECOVERY_REENTRY_DELAY_MS` (~35 s), the runtime
   automatically re-enters `RECOVERING` state so the next watchdog attempt is reflected
5. On watchdog reconnect success: `reconnectRecoveryState` → `RECOVERED`, attached session
   reopened, continuity epoch incremented, `V2MultiDeviceLifecycleEvent.DeviceReconnected` emitted

**No manual intervention required**: a configured Android participant will continue attempting
recovery over any period of gateway unavailability solely due to the reconnect-attempt ceiling.
An explicit `stop()` / `applyKillSwitch()` call, or the user toggling cross-device off, is the
only way to halt the perpetual recovery cycle.

### Process recreation

When Android's low-memory killer kills and recreates the process:

| State | Survives? | Notes |
|---|---|---|
| `crossDeviceEnabled` (AppSettings) | ✅ Yes | Persisted in SharedPreferences |
| `gatewayHost`/`gatewayPort`/`useTls` | ✅ Yes | Persisted in SharedPreferences |
| `deviceId` | ✅ Yes | Persisted in SharedPreferences |
| `lastDurableSessionId` (AppSettings) | ✅ Yes | **PR-7**: Prior-session continuity hint persisted in SharedPreferences |
| `DurableSessionContinuityRecord` (live record) | ❌ No | Process-scoped; new era on restart; prior ID preserved as hint |
| `_runtimeAttachmentSessionId` | ❌ No | Regenerated fresh; new attachment era |
| `ReconnectRecoveryState` | ❌ No | Resets to IDLE |
| `RuntimeController.RuntimeState` | ❌ No | Resets to Idle/Starting |
| In-flight task state | ❌ No | Lost; V2 must handle via timeout/retry |

After process recreation, Android emits `V2MultiDeviceLifecycleEvent.DeviceConnected`
(NOT `DeviceReconnected`) because the attachment identity is fresh.  When a prior
`lastDurableSessionId` is available, the `DeviceConnected` event carries a
`ProcessRecreatedReattachHint` in its metadata, allowing V2 to optionally correlate
the returning device with its prior session.

### Process-recreation re-attach vs other DeviceConnected types

| Scenario | Attachment semantics | `prior_durable_session_id` hint | V2 treatment |
|---|---|---|---|
| True first launch | `FRESH_ATTACH` | Absent | Brand-new device |
| User stop + re-enable | `NEW_ERA_ATTACH` | Absent (hint cleared on clean stop) | New registration |
| Process-recreation re-attach | `PROCESS_RECREATED_REATTACH` | Present (`lastDurableSessionId`) | May optionally correlate with prior session |
| Transient WS reconnect | `RECONNECT_RECOVERY` | N/A — `DeviceReconnected` emitted | Correlates via epoch increment |

**V2 authority boundary**: V2 decides whether to restore participant state based on the hint
and its own participant-loss timeout policy.  Android MUST NOT self-authorize session
continuation.

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
- ❌ Android does NOT self-authorize session continuation based on `lastDurableSessionId` hint

---

## 4. Durability and Readiness

### What state is durable (persisted)

- `AppSettings` (SharedPreferences): connection config, device identity, feature flags
- All `AppSettings` fields — these survive process recreation
- **PR-7**: `AppSettings.lastDurableSessionId` — prior durable session ID hint, persisted
  when a session era ends, read at process-recreation re-attach time

### What state is volatile (in-memory, process-scoped)

- `DurableSessionContinuityRecord` (live record): lost on process death; new era starts on restart;
  the prior `durableSessionId` is preserved in `AppSettings.lastDurableSessionId`
- `_runtimeAttachmentSessionId`: regenerated on each new activation era
- `_reconciliationEpoch`: resets to 0 on process restart
- `AttachedRuntimeSession`: in-memory; re-established on reconnect
- `ReconnectRecoveryState`: resets to IDLE on stop/restart
- In-flight task state: not persisted; V2 is the canonical task-state authority

### Durability design

`DurableSessionContinuityRecord` itself is not persisted to disk (it is an in-process
coordination aid, not a canonical source of truth).  However, the `durableSessionId` is
now persisted to SharedPreferences when the session era ends, providing a thin identity
hint that survives process recreation.

V2 remains the canonical source of session and task truth.  The `lastDurableSessionId`
hint allows V2 to optionally correlate a returning device, but V2 is never obligated to
restore state solely based on this hint.

---

## 5. V2 Lifecycle Event → Android Action Mapping

```
V2 receives                             Android emitted
──────────────────────────────────────────────────────
DeviceConnected (user_activation)     ← start() or connectIfEnabled(crossDeviceOn=true)
DeviceConnected (background_restore)  ← connectIfEnabled() after process recreate / service start
DeviceConnected (process_recreation)  ← process-recreation re-attach with ProcessRecreatedReattachHint
DeviceReconnected                     ← transparent WS reconnect (RECONNECT_RECOVERY)
DeviceDisconnected (disconnect)       ← WS drop (isResumable=true → V2 suspends session)
DeviceDisconnected (disable/invalid)  ← stop() or invalidateSession() (isResumable=false → V2 terminates)
DeviceHealthChanged / Degraded        ← notifyParticipantHealthChanged(DEGRADED/FAILED)
ParticipantReadinessChanged           ← notifyParticipantHealthChanged(readiness changed)
```

---

## 6. Intentional Limitations (Out of Scope for PR-60 / PR-7)

The following are **intentional** and remain out of scope:

| Limitation | Reason |
|---|---|
| Full `HYBRID_EXECUTE` executor | Requires dedicated Android hybrid executor component; explicitly deferred |
| WebRTC peer transport for distributed participant | Not production-ready; minimal-compat stubs only |
| Barrier/merge coordination participation | Android is not a barrier authority; V2 owns this |
| Task state persistence across process recreation | V2 is the canonical task state authority; Android does not duplicate this |
| Self-authorized session continuation from `lastDurableSessionId` | Android presents hint only; V2 decides whether to restore session |

> **Note (PR-Block1)**: "Autonomous runtime restart after reconnect failure" has been resolved.
> Android now performs perpetual watchdog recovery without manual intervention (see §2 above).

---

## Related Surfaces

| Surface | Purpose | PR |
|---|---|---|
| `AndroidAppLifecycleTransition` | Explicit app lifecycle event model | PR-60 |
| `HybridParticipantCapability` | Structured hybrid capability status | PR-60 |
| `AndroidLifecycleRecoveryContract` | Authoritative recovery boundary documentation | PR-60/PR-7 |
| `ProcessRecreatedReattachHint` | Prior-session continuity hint for process-recreation re-attach | PR-7 |
| `AppSettings.lastDurableSessionId` | Persisted prior durable session ID (SharedPreferences) | PR-7 |
| `ParticipantAttachmentTransitionSemantics.PROCESS_RECREATED_REATTACH` | Re-attach semantics for process-recreation | PR-7 |
| `ContinuityRecoveryContext.REASON_PROCESS_RECREATION` | Interruption reason wire value for process recreation | PR-7 |
| `GalaxyLogger.TAG_APP_LIFECYCLE` | Structured log tag for app lifecycle transitions | PR-60 |
| `GalaxyLogger.TAG_HYBRID_PARTICIPANT` | Structured log tag for hybrid capability limitations | PR-60 |
| `RuntimeController.onAppLifecycleTransition` | Explicit lifecycle handler method | PR-60 |
| `ReconnectRecoveryState` | WS reconnect recovery lifecycle phases | PR-33 |
| `DurableSessionContinuityRecord` | Durable session identity across WS reconnects | PR-1 |
| `V2MultiDeviceLifecycleEvent` | V2-consumable lifecycle events | PR-43/PR-44 |
| `ContinuityRecoveryContext` | Recovery participant role and token boundary docs | PR-F |
| `RUNTIME_TRUTH_RECONCILIATION.md` | Android runtime truth and V2 reconciliation | PR-51/PR-52 |
