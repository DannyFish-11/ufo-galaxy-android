# V2 Multi-Device Runtime Integration — Android Lifecycle Signal Reference

> **PR-43**  
> Introduced in: `ufo-galaxy-android`  
> Audience: V2 multi-device runtime harness engineers, Android platform engineers

---

## Overview

This document describes how the V2 multi-device runtime harness (`MultiDeviceRuntimeHarness` in
`ufo-galaxy-realization-v2`) should consume Android-side device lifecycle and health signals.

Android emits all V2-relevant lifecycle events through a single, typed Kotlin
[`SharedFlow`](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-shared-flow/)
on `RuntimeController`:

```kotlin
val v2LifecycleEvents: SharedFlow<V2MultiDeviceLifecycleEvent>
```

Each emitted value is a sealed subclass of `V2MultiDeviceLifecycleEvent`. The class is designed
to provide V2 with a stable, unambiguous event type per lifecycle condition, so V2 does not need
to interpret Android-internal state flows (`reconnectRecoveryState`, `formationRebalanceEvent`,
etc.) directly.

---

## V2 Hook Mapping

| `V2MultiDeviceLifecycleEvent` subclass  | V2 hook target                                       | Condition                                           |
|-----------------------------------------|------------------------------------------------------|-----------------------------------------------------|
| `DeviceConnected`                       | `on_device_health_changed(deviceId, ONLINE/HEALTHY)` | Device successfully attached (initial or restore)   |
| `DeviceReconnected`                     | `on_device_health_changed(deviceId, ONLINE/HEALTHY)` | Device reconnected after transparent WS drop        |
| `DeviceDisconnected`                    | `on_device_health_changed(deviceId, OFFLINE)`        | Device detached (any cause)                         |
| `DeviceDegraded`                        | `on_device_health_changed(deviceId, DEGRADED)`       | Device entered degraded/unavailable state           |
| `DeviceHealthChanged`                   | `on_device_health_changed(deviceId, <currentHealth>)`| Execution environment health changed explicitly     |
| `ParticipantReadinessChanged`           | `on_participant_readiness_changed(deviceId, ...)`    | Participant readiness/participation state changed   |

---

## Wire Values (stable; do not rename without V2 contract update)

| Constant                                  | Wire value                        |
|-------------------------------------------|-----------------------------------|
| `WIRE_DEVICE_CONNECTED`                   | `v2_device_connected`             |
| `WIRE_DEVICE_RECONNECTED`                 | `v2_device_reconnected`           |
| `WIRE_DEVICE_DISCONNECTED`                | `v2_device_disconnected`          |
| `WIRE_DEVICE_DEGRADED`                    | `v2_device_degraded`              |
| `WIRE_DEVICE_HEALTH_CHANGED`              | `v2_device_health_changed`        |
| `WIRE_PARTICIPANT_READINESS_CHANGED`      | `v2_participant_readiness_changed`|

All wire values start with `v2_` to prevent namespace collision with internal Android log tags.

---

## Heartbeat-Miss Semantics

Android emits outbound WS heartbeats every 30 seconds
(`GalaxyWebSocketClient.HEARTBEAT_INTERVAL_MS`). Heartbeat **miss** detection — detecting that
the remote host has not received a heartbeat within a window — is **not available** on the
Android side.

**V2 action:** Treat `DeviceDisconnected` with `detachCause = "disconnect"` as the Android
equivalent of a heartbeat-miss condition. The WS layer triggers the disconnect as soon as the
underlying TCP connection is lost, which is the earliest possible Android-side signal for
connectivity loss.

`WIRE_HEARTBEAT_MISS_UNSUPPORTED` is defined as a documentation constant in
`V2MultiDeviceLifecycleEvent.Companion` but is **never emitted** as an event. Its presence in
the code serves as a stable, machine-readable declaration that this signal type is unsupported.

---

## `DeviceDisconnected.detachCause` Vocabulary

| `detachCause` wire value | Description                                                       | Reconnect expected? |
|--------------------------|-------------------------------------------------------------------|---------------------|
| `"disconnect"`           | Transient WS drop; WS client will auto-reconnect                 | Yes — `DeviceReconnected` follows if recovery succeeds |
| `"disable"`              | Explicit stop / user toggled cross-device OFF / kill-switch       | No                  |
| `"explicit_detach"`      | Operator or user explicitly detached this device                  | No                  |
| `"invalidation"`         | Session invalidated (auth expiry, identity change, etc.)          | No                  |

---

## `DeviceDegraded.degradationKind` Vocabulary

| `degradationKind`       | Source condition                                                    |
|-------------------------|---------------------------------------------------------------------|
| `"ws_recovering"`       | WS reconnect recovery started (`ReconnectRecoveryState.RECOVERING`) |
| `"ws_recovery_failed"`  | WS reconnect attempts exhausted (`ReconnectRecoveryState.FAILED`)   |
| `"health_degraded"`     | `ParticipantHealthState.DEGRADED` reported                          |
| `"health_recovering"`   | `ParticipantHealthState.RECOVERING` reported                        |
| `"health_failed"`       | `ParticipantHealthState.FAILED` reported                            |

---

## Session Identity Fields

Each event carries one or more of the following session identity fields:

| Field                    | Type      | Description                                                           |
|--------------------------|-----------|-----------------------------------------------------------------------|
| `deviceId`               | `String`  | Stable hardware device identifier; matches `AppSettings.deviceId`.   |
| `sessionId`              | `String?` | `AttachedRuntimeSession.sessionId` — unique per activation era.       |
| `runtimeSessionId`       | `String`  | Per-connection UUID; changes on each connect/reconnect.               |
| `durableSessionId`       | `String?` | Durable era ID; stable across reconnects within an activation era.    |
| `sessionContinuityEpoch` | `Int`     | Reconnect count within the durable era (0 on first connect).         |

V2 should use `durableSessionId` as the most stable cross-reconnect session anchor. It is
preserved across `DeviceReconnected` events and increments `sessionContinuityEpoch` on each.

---

## Consuming `v2LifecycleEvents` from V2

```kotlin
// Example V2-side consumer (pseudocode — adapt to V2 coroutine/lifecycle patterns):
runtimeController.v2LifecycleEvents
    .onEach { event ->
        when (event) {
            is V2MultiDeviceLifecycleEvent.DeviceConnected ->
                harness.on_device_health_changed(event.deviceId, V2HealthState.ONLINE)

            is V2MultiDeviceLifecycleEvent.DeviceReconnected ->
                harness.on_device_health_changed(event.deviceId, V2HealthState.ONLINE)

            is V2MultiDeviceLifecycleEvent.DeviceDisconnected ->
                harness.on_device_health_changed(event.deviceId, V2HealthState.OFFLINE)

            is V2MultiDeviceLifecycleEvent.DeviceDegraded ->
                harness.on_device_health_changed(event.deviceId, V2HealthState.DEGRADED)

            is V2MultiDeviceLifecycleEvent.DeviceHealthChanged ->
                harness.on_device_health_changed(event.deviceId, event.currentHealth)

            is V2MultiDeviceLifecycleEvent.ParticipantReadinessChanged ->
                harness.on_participant_readiness_changed(event.deviceId, event.currentReadiness)
        }
    }
    .launchIn(v2HarnessScope)
```

---

## Structured Logging

All `v2LifecycleEvents` emissions are also written to the structured log under the
`GALAXY:V2:LIFECYCLE` tag (`GalaxyLogger.TAG_V2_LIFECYCLE`). The log entry format is:

```json
{
  "ts": 1710000000000,
  "tag": "GALAXY:V2:LIFECYCLE",
  "fields": {
    "event": "v2_device_connected",
    "device_id": "Pixel_8",
    "session_id": "s-1",
    "runtime_session_id": "r-abc",
    "durable_session_id": "d-xyz",
    "session_continuity_epoch": 0,
    "open_source": "user_activation"
  }
}
```

---

## Event Emission Callsites (Android-side production path)

| Event subclass              | Emitting method                               | Excluded cases          |
|-----------------------------|-----------------------------------------------|-------------------------|
| `DeviceConnected`           | `RuntimeController.openAttachedSession`       | `TEST_ONLY` source      |
| `DeviceReconnected`         | `RuntimeController.openAttachedSession`       | `TEST_ONLY` source      |
| `DeviceDisconnected`        | `RuntimeController.closeAttachedSession`      | None                    |
| `DeviceDegraded`            | `RuntimeController.emitFormationRebalanceForRecovery` (WS) and `notifyParticipantHealthChanged` (health) | None |
| `DeviceHealthChanged`       | `RuntimeController.notifyParticipantHealthChanged` | None             |
| `ParticipantReadinessChanged` | `RuntimeController.notifyParticipantHealthChanged` and `emitFormationRebalanceForRecovery` | None |

`TEST_ONLY` session open sources (used in unit tests for synthetic activation) do not emit V2
events so synthetic test activations do not pollute the production event stream.

---

## Stability Guarantees

All wire values listed in `V2MultiDeviceLifecycleEvent.ALL_WIRE_VALUES` are stable as of PR-43
and **must not** be renamed without a corresponding V2 contract update. These surfaces are
registered in `StabilizationBaseline` as `CANONICAL_STABLE`.

---

## Out of Scope

The following are **not** provided by this implementation and are intentionally excluded:

- **Heartbeat-miss events** — not available on the Android side; use `DeviceDisconnected` instead.
- **`on_task_admitted_for_dispatch`** — task dispatch events are not lifecycle events and are not
  carried on this flow. They are governed by the V2 dispatch layer.
- **`recover_sessions`** — session recovery at startup is not an event type on this flow; it is
  driven by Android's `connectIfEnabled` path, which will emit `DeviceConnected` or
  `DeviceReconnected` as appropriate.
- **Formation-level `FormationRebalanceEvent`** — the raw formation rebalance stream
  (`RuntimeController.formationRebalanceEvent`) remains available for consumers that need full
  formation-level detail, but V2 should consume `v2LifecycleEvents` for hook integration.
