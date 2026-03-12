# Android Device-Side Observability (PR15)

This document describes the structured logging system added in PR15 for monitoring
key lifecycle events on the Android client.

---

## Log Tags

All structured log entries are identified by one of the following stable tag constants
(defined in `GalaxyLogger.kt`):

| Tag | Constant | When emitted |
|-----|----------|--------------|
| `GALAXY:CONNECT` | `GalaxyLogger.TAG_CONNECT` | WebSocket connection established (`onOpen`) |
| `GALAXY:DISCONNECT` | `GalaxyLogger.TAG_DISCONNECT` | WebSocket closed or failed (`onClosed`, `onFailure`) |
| `GALAXY:RECONNECT` | `GalaxyLogger.TAG_RECONNECT` | Reconnect attempt scheduled (exponential back-off) |
| `GALAXY:TASK:RECV` | `GalaxyLogger.TAG_TASK_RECV` | `task_assign` or `goal_execution` message received |
| `GALAXY:TASK:EXEC` | `GalaxyLogger.TAG_TASK_EXEC` | Task execution started by `EdgeExecutor` |
| `GALAXY:TASK:RETURN` | `GalaxyLogger.TAG_TASK_RETURN` | Task result returned (status + step count) |
| `GALAXY:READINESS` | `GalaxyLogger.TAG_READINESS` | Readiness self-check completed (`ReadinessChecker`) |
| `GALAXY:DEGRADED` | `GalaxyLogger.TAG_DEGRADED` | Device is in degraded mode (any readiness flag is false) |

---

## Log Entry Format

Each entry is a single-line JSON object:

```json
{"ts":1710000000000,"tag":"GALAXY:CONNECT","fields":{"url":"ws://host:8080","attempt":0}}
```

| Field | Type | Description |
|-------|------|-------------|
| `ts` | `long` | Unix timestamp in milliseconds |
| `tag` | `string` | Stable tag (see table above) |
| `fields` | `object` | Event-specific key→value pairs |

### Example entries

```jsonl
{"ts":1710001000000,"tag":"GALAXY:CONNECT","fields":{"url":"ws://192.168.1.10:8080","attempt":0}}
{"ts":1710002000000,"tag":"GALAXY:TASK:RECV","fields":{"task_id":"task-abc123","type":"task_assign"}}
{"ts":1710002001000,"tag":"GALAXY:TASK:EXEC","fields":{"task_id":"task-abc123","max_steps":10}}
{"ts":1710002015000,"tag":"GALAXY:TASK:RETURN","fields":{"task_id":"task-abc123","status":"success","steps":3,"error":null}}
{"ts":1710003000000,"tag":"GALAXY:DISCONNECT","fields":{"type":"closed","code":1000,"reason":"user disconnect"}}
{"ts":1710004000000,"tag":"GALAXY:READINESS","fields":{"model_ready":true,"accessibility_ready":false,"overlay_ready":true,"degraded_mode":true}}
{"ts":1710004000001,"tag":"GALAXY:DEGRADED","fields":{"model_ready":true,"accessibility_ready":false,"overlay_ready":true}}
{"ts":1710005000000,"tag":"GALAXY:RECONNECT","fields":{"attempt":1,"max_attempts":10,"delay_ms":1342}}
```

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

---

## Implementation Files

| File | Purpose |
|------|---------|
| `app/src/main/java/com/ufo/galaxy/observability/GalaxyLogger.kt` | Singleton structured logger (ring buffer + file) |
| `app/src/main/java/com/ufo/galaxy/ui/components/DiagnosticsScreen.kt` | Diagnostics Composable + `shareLogs()` helper |
| `app/src/main/res/xml/file_provider_paths.xml` | FileProvider path config for log sharing |
| `app/src/test/java/com/ufo/galaxy/observability/GalaxyLoggerTest.kt` | JVM unit tests for GalaxyLogger |
| `docs/OBSERVABILITY.md` | This document |
