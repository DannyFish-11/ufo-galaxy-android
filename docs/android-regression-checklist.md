# Android Regression Checklist

Run this checklist before merging any PR that touches the agent pipeline, services,
network layer, or UI. Each item maps to a specific class or log tag.

---

## 1. Model / Accessibility / Overlay Readiness

| # | Check | Pass criteria | Log tag / UI path |
|---|-------|---------------|-------------------|
| 1.1 | MobileVLM 1.7B model file present on device | `ReadinessChecker.checkModelReady()` returns `true` | `ReadinessChecker` |
| 1.2 | SeeClick grounding model file present | Same as 1.1 (both models verified by `ModelAssetManager.verifyAll()`) | `ModelAssetManager` |
| 1.3 | Model checksum passes | `ModelAssetManager.ModelStatus` is `READY` or `LOADED` for all entries | `ModelAssetManager` |
| 1.4 | `HardwareKeyListener` accessibility service enabled | `ReadinessChecker.checkAccessibilityReady()` returns `true`; or `HardwareKeyListener.instance != null` | Settings → Accessibility → UFO Galaxy |
| 1.5 | `SYSTEM_ALERT_WINDOW` overlay permission granted | `ReadinessChecker.checkOverlayReady()` returns `true` | Settings → Apps → UFO Galaxy → Display over other apps |
| 1.6 | `ReadinessState.degradedMode` is `false` | All three checks pass | `ReadinessChecker` log: `degradedMode=false` |
| 1.7 | Capability report metadata reflects readiness | `AppSettings.toMetadataMap()` includes `model_ready=true`, `accessibility_ready=true`, `overlay_ready=true` | `GalaxyConnectionService` log |

**Logcat command:**
```bash
adb logcat -s ReadinessChecker ModelAssetManager ModelDownloader
```

---

## 2. Foreground Service Alive

| # | Check | Pass criteria | Log tag / UI path |
|---|-------|---------------|-------------------|
| 2.1 | `GalaxyConnectionService` starts as foreground service | Persistent notification visible in status bar | Notification channel "Galaxy Connection" |
| 2.2 | Service survives app background | PID unchanged after pressing Home | `adb shell pidof com.ufo.galaxy` |
| 2.3 | Service survives screen-off + 5 min | WS heartbeat continues (`heartbeat` / `heartbeat_ack` in logcat) | `GalaxyWebSocket` |
| 2.4 | `EnhancedFloatingService` floating bubble visible | Overlay window shown after service start | `EnhancedFloatingService` |
| 2.5 | Service restarts on crash (START_STICKY) | Kill service via `adb shell kill <pid>`; service restarts within ~5 s | `GalaxyConnectionService` |
| 2.6 | `BootReceiver` re-starts service on reboot | Reboot device; service starts automatically | `BootReceiver` / `HardwareKeyReceiver` |

**Logcat command:**
```bash
adb logcat -s GalaxyConnectionService EnhancedFloatingService BootReceiver
```

**ADB service check:**
```bash
adb shell dumpsys activity services com.ufo.galaxy | grep -E "ServiceRecord|running"
```

---

## 3. Reconnect Behaviour When Toggle Changes

| # | Check | Pass criteria | Log tag / UI path |
|---|-------|---------------|-------------------|
| 3.1 | Toggle OFF → WebSocket disconnects | `GalaxyWebSocket: onDisconnected` in logcat; no reconnect attempts | `GalaxyWebSocket` |
| 3.2 | Toggle OFF → no `device_register` sent | Gateway receives no registration frames | Gateway access log |
| 3.3 | Toggle ON → WebSocket connects | `GalaxyWebSocket: onConnected` in logcat within ~3 s | `GalaxyWebSocket` |
| 3.4 | Toggle ON → `capability_report` sent immediately | Report logged by `GalaxyConnectionService` after connect | `GalaxyConnectionService` |
| 3.5 | Toggle OFF → ON → OFF cycle (3×) | No duplicate connections; socket state consistent | `GalaxyWebSocket` |
| 3.6 | Toggle ON while offline → auto-reconnects when network available | Reconnect attempt logged after network comes back | `GalaxyWebSocket` |
| 3.7 | `MainViewModel.crossDeviceEnabled` state mirrors `AppSettings` | UI toggle state persists across Activity re-create (rotation) | `MainViewModel` |

**Logcat command:**
```bash
adb logcat -s GalaxyWebSocket GalaxyConnectionService MainViewModel
```

---

## 4. Offline Handling

| # | Check | Pass criteria | Log tag / UI path |
|---|-------|---------------|-------------------|
| 4.1 | Task submitted while offline → local execution | Task handled by `EdgeOrchestrator` / `LocalGoalExecutor` without network | `EdgeOrchestrator`, `LocalGoalExecutor` |
| 4.2 | Result shown in UI even when offline | Chat screen shows result from on-device execution | `MainViewModel` |
| 4.3 | No crash / ANR on network loss mid-task | `EdgeExecutor` handles `onError` gracefully; returns `status=error` | `EdgeOrchestrator` |
| 4.4 | `capability_report` not sent when offline | No `W/GalaxyWebSocket: send failed` spam when `crossDeviceEnabled=false` | `GalaxyWebSocket` |
| 4.5 | Airplane mode → toggle ON → no crash | `GalaxyWebSocketClient.connect()` handles connection failure silently | `GalaxyWebSocket` |
| 4.6 | Reconnect back-off does not drain battery | No tight retry loop; exponential back-off or 30 s interval | `GalaxyWebSocket` |

**Simulate offline:**
```bash
adb shell svc wifi disable
adb shell svc data disable
# Re-enable:
adb shell svc wifi enable
adb shell svc data enable
```

---

## 5. AIP v3 Protocol Contract

| # | Check | Pass criteria |
|---|-------|---------------|
| 5.1 | `TaskAssignPayload` contains no `x`/`y` fields | `TaskAssignPayload` class has no coordinate fields (enforced by unit test) |
| 5.2 | `GoalResultPayload.correlation_id` equals `task_id` | Verified by `LocalGoalExecutorTest` |
| 5.3 | `parallel_subtask` result echoes `group_id` and `subtask_index` | Verified by `LocalGoalExecutorTest` |
| 5.4 | `GoalResultPayload.latency_ms` is non-negative | Verified by `LocalGoalExecutorTest` |
| 5.5 | `CapabilityReport.metadata` contains all 8 required keys | Verified by `CrossDeviceSwitchTest` |

Run unit tests:
```bash
./gradlew :app:test --tests "com.ufo.galaxy.*" --info
```

---

## 6. Quick Smoke Test (Manual ADB flow)

Use this flow after installing a new build to confirm core contracts without a full
v2 gateway deployment.

```bash
# 1. Install debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Launch app
adb shell am start -n com.ufo.galaxy/.ui.MainActivity

# 3. Grant overlay permission (if not already granted)
adb shell appops set com.ufo.galaxy SYSTEM_ALERT_WINDOW allow

# 4. Enable accessibility service (must be done manually in Settings the first time)
# Settings → Accessibility → UFO Galaxy → ON

# 5. Check readiness
adb logcat -s ReadinessChecker -d | grep -E "modelReady|accessibilityReady|overlayReady"

# 6. Verify foreground service is running
adb shell dumpsys activity services com.ufo.galaxy | grep ServiceRecord

# 7. Simulate goal_execution via port-forward (gateway must be running locally on port 8765)
adb reverse tcp:8765 tcp:8765
wscat -c ws://localhost:8765 \
  -x '{"type":"goal_execution","payload":{"task_id":"smoke-001","goal":"Open Clock app","max_steps":5},"version":"3.0"}'

# Expected logcat output:
adb logcat -s LocalGoalExecutor -d | grep "smoke-001"
# → D/LocalGoalExecutor: executing goal task_id=smoke-001
# → D/LocalGoalExecutor: goal complete status=success latency_ms=<n>

# 8. Simulate parallel_subtask
wscat -c ws://localhost:8765 \
  -x '{"type":"parallel_subtask","payload":{"task_id":"smoke-sub-001","goal":"Open Settings","group_id":"grp-smoke","subtask_index":0,"max_steps":5},"version":"3.0"}'

# Expected: goal_result with group_id=grp-smoke, subtask_index=0
```

---

## Sign-Off

| Area | Reviewer | Date | Pass / Fail |
|------|----------|------|-------------|
| Model / readiness | | | |
| Foreground service | | | |
| Toggle reconnect | | | |
| Offline handling | | | |
| Protocol contracts (unit tests) | | | |

---

## PR14 — Reconnect + Offline Queue (New Checks)

### 7. Exponential Backoff Reconnect

| # | Check | Pass criteria | Log tag |
|---|-------|---------------|---------|
| 7.1 | Reconnect uses exponential backoff | Logcat shows increasing delay: 1 s → 2 s → 4 s → 8 s … | `GalaxyWebSocket [WS:RETRY]` |
| 7.2 | Jitter is applied | Two consecutive runs show slightly different delays | `GalaxyWebSocket [WS:RETRY]` |
| 7.3 | Reconnect stops after 10 attempts | `Max reconnect attempts` log appears; no further attempts | `GalaxyWebSocket [WS:RETRY]` |
| 7.4 | Toggle enable triggers immediate reconnect | After toggle ON, connect attempt logged within < 1 s | `GalaxyWebSocket [WS:CONNECT]` |
| 7.5 | `notifyNetworkAvailable()` resets backoff | After network restored, connect attempt is immediate (not waiting full backoff) | `GalaxyWebSocket [WS:RETRY]` |
| 7.6 | Reconnect counter resets to 0 after successful open | After reconnect succeeds, `[WS:CONNECT] resetting backoff counter` appears | `GalaxyWebSocket [WS:CONNECT]` |

**Logcat command:**
```bash
adb logcat -s GalaxyWebSocket | grep -E "\[WS:CONNECT\]|\[WS:DISCONNECT\]|\[WS:RETRY\]"
```

---

### 8. Offline Task Queue

| # | Check | Pass criteria | Log tag |
|---|-------|---------------|---------|
| 8.1 | `task_result` queued when disconnected | `[WS:OfflineQueue] Enqueued type=task_result` in logcat when WS is down | `WS:OfflineQueue` |
| 8.2 | `goal_result` queued when disconnected | `[WS:OfflineQueue] Enqueued type=goal_result` in logcat | `WS:OfflineQueue` |
| 8.3 | Heartbeat NOT queued when disconnected | No `Enqueued type=heartbeat` entry | `WS:OfflineQueue` |
| 8.4 | Queue flushed in order on reconnect | `[WS:OfflineQueue] Flushing N offline message(s)` after reconnect; server receives in order | `WS:OfflineQueue` |
| 8.5 | Queue size shown in UI | Status bar below top bar shows "队列: N" when N > 0 and `crossDeviceEnabled=true` | UI status bar |
| 8.6 | Queue capped at 50 entries | With > 50 messages offline, `Queue full; dropped oldest` appears; size stays ≤ 50 | `WS:OfflineQueue` |
| 8.7 | Queue persisted across restart | Close app with queued messages; reopen; `Restored N offline messages from prefs` in logcat | `WS:OfflineQueue` |
| 8.8 | Stale messages (> 24 h) discarded on load | Manually set old `queuedAt` in prefs; messages not loaded | `WS:OfflineQueue` |

> **Persistence limitation**: Queue persistence uses `SharedPreferences` (JSON). This survives normal app restarts but will be lost if the app data is cleared or the app is uninstalled.  Process kill does *not* lose the queue because SharedPreferences is flushed asynchronously after every mutation.  Messages older than 24 hours are automatically discarded on next launch.

**Logcat command:**
```bash
adb logcat -s WS:OfflineQueue
```

---

### 9. Connection Status UI

| # | Check | Pass criteria | UI path |
|---|-------|---------------|---------|
| 9.1 | Status bar shows "● 已连接" when connected | Green dot + label visible below top app bar | `MainActivity` status bar |
| 9.2 | Status bar shows "● 断开" when disconnected | Red dot + label visible | `MainActivity` status bar |
| 9.3 | "队列: N" visible when offline messages queued | Label appears with correct count | `MainActivity` status bar |
| 9.4 | "重试: N" visible during reconnect backoff | Label shows attempt count | `MainActivity` status bar |
| 9.5 | Status bar hidden when cross-device is OFF | No status bar when toggle is off | `MainActivity` |

---

### PR14 Sign-Off

| Area | Reviewer | Date | Pass / Fail |
|------|----------|------|-------------|
| Exponential backoff + jitter | | | |
| Offline queue enqueue/flush | | | |
| Queue persistence | | | |
| Connection status UI | | | |
| Unit tests pass | | | |
