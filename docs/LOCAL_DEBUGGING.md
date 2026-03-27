# Local-Loop Debugging Guide

This guide explains how to use the local-loop debug surface introduced in **PR-G** to inspect and troubleshoot the on-device inference pipeline during local development.

---

## Overview

The local-loop debug panel provides a developer-only view of the internal state of the local closed-loop execution pipeline. It surfaces:

- Current **readiness snapshot** (all six subsystem flags)
- Active **config snapshot** (timeouts, step budgets, fallback flags)
- **Latest trace** summary (session ID, step count, duration, terminal result)
- **Last submitted goal** (for the re-run action)
- **Emitted plain-text diagnostic snapshot** (for copy/paste sharing)

---

## Opening the Debug Panel

The debug panel is accessible from the main screen of the app via the **bug icon (🐛)** in the top action bar (between the diagnostics ⓘ and network settings ⚙ buttons).

Tapping the bug icon:
1. Opens the `LocalLoopDebugPanel` composable as a full-screen overlay.
2. Triggers an automatic initial **Refresh** to populate all fields.

---

## Debug Panel Sections

### Readiness Snapshot

Shows the result of the most recent readiness check, reflecting the live state of all six subsystems:

| Field | What it checks |
|---|---|
| State | Derived overall state: `READY`, `DEGRADED`, `UNAVAILABLE`, `UNINITIALIZED` |
| Model files | Local model weight files present and verified on disk |
| Planner loaded | MobileVLM planner service loaded and reachable |
| Grounding loaded | SeeClick grounding service loaded and reachable |
| Accessibility | `HardwareKeyListener` accessibility service bound |
| Screenshot | Screenshot capture subsystem available |
| Action executor | Action dispatch subsystem available |
| Blockers | List of active blockers when state ≠ `READY` |

### Config Snapshot

Shows the active `LocalLoopConfig` at refresh time:

| Field | Default | Description |
|---|---|---|
| `maxSteps` | 10 | Hard cap on dispatched steps per session |
| `maxRetriesPerStep` | 2 | Max retries for a single failing step |
| `stepTimeoutMs` | 0 (disabled) | Per-step wall-clock timeout |
| `goalTimeoutMs` | 0 (disabled) | Total session wall-clock timeout |
| `plannerFallback` | true | Whether the rule-based planner fallback ladder is active |
| `groundingFallback` | true | Whether the grounding fallback ladder is active |
| `remoteHandoff` | false | Whether failed local executions may escalate remotely |

### Latest Trace

Summary of the most recently started or completed `LocalLoopTrace` retained by `LocalLoopTraceStore`:

- **Session ID** (truncated): unique identifier for the session.
- **Running**: whether the session is still in-flight.
- **Steps**: number of completed steps.
- **Duration ms**: wall-clock duration (or `running` if in-flight).
- **Status / Stop reason / Error**: terminal result fields when the session has ended.

The trace store retains up to 20 recent traces in memory (cleared on process restart).

### Last Goal

The original natural-language goal string from the most recent session or the most recently submitted message. Used by the **Re-run Last Goal** action.

### Emitted Snapshot

Plain-text snapshot produced by the **Emit Snapshot** action. Includes all sections above in a format suitable for copy/paste or inclusion in a bug report.

---

## Developer Actions

| Button | What it does |
|---|---|
| **Readiness Refresh** | Forces a fresh readiness check immediately. Useful after fixing a missing subsystem (e.g. re-enabling the accessibility service). |
| **Clear Trace State** | Clears all traces from the in-memory `LocalLoopTraceStore` and resets the trace fields in the debug panel. Useful before a new test run to get a clean slate. |
| **Re-run Last Goal** | Re-submits the last known goal string through the normal `sendMessage` path. Requires a non-empty last goal. |
| **Emit Snapshot** | Builds a plain-text diagnostic snapshot of the current debug state and displays it in the panel. The snapshot is also written to the `GalaxyLogger` ring buffer and can be exported via the existing "Share Logs" flow on the standard Diagnostics panel. |

The refresh icon (↻) in the top bar performs a full refresh of all fields.

---

## Developer Action Workflows

### Verifying readiness before a test run

1. Open the debug panel (🐛 icon).
2. Check the **Readiness Snapshot** section.
3. If `State` is not `READY`:
   - Review the `Blockers` list.
   - Fix the flagged subsystem (e.g. enable the accessibility service in Android Settings).
   - Tap **Readiness Refresh** to confirm the fix without leaving the panel.

### Inspecting a failed session

1. After a failed local execution, open the debug panel.
2. Check **Latest Trace** for `Status`, `Stop reason`, and `Error`.
3. Cross-reference with the `GalaxyLogger` ring buffer via the **Share Logs** action on the Diagnostics panel.

### Re-running a goal after a code change

1. Compile and install the updated app.
2. Open the debug panel.
3. Tap **Re-run Last Goal** to re-submit the previous goal without re-typing it.

### Sharing a diagnostic snapshot with the team

1. Open the debug panel and tap **Emit Snapshot**.
2. The snapshot text appears in the panel — long-press to copy.
3. Alternatively, switch to the Diagnostics panel (ⓘ icon) and use **Share Logs** to share the full `galaxy_observability.log` which includes the snapshot event.

### Clearing state between test iterations

1. Open the debug panel.
2. Tap **Clear Trace State** to remove all retained traces.
3. Tap **Readiness Refresh** to get a fresh readiness gate result.
4. Proceed with the next test run.

---

## Session History (PR-H)

The debug panel now surfaces **persistent session history**: a chronological record of completed local-loop sessions that survives app restarts.

### What is stored

When a local-loop session ends (success, failure, or cancellation) a lightweight [SessionHistorySummary] is written to the `SessionHistoryStore`. Each summary contains:

| Field | Description |
|---|---|
| `sessionId` | Unique identifier (UUID) |
| `originalGoal` | Natural-language goal string |
| `startTimeMs` / `endTimeMs` | Wall-clock session timestamps |
| `durationMs` | Total session duration |
| `stepCount` | Number of completed steps |
| `status` | `success`, `failed`, or `cancelled` |
| `stopReason` | Machine-readable stop reason (e.g. `timeout`) |
| `error` | Human-readable error (null on success) |
| `planCount` | Number of plans generated |
| `actionCount` | Total actions dispatched |

No raw screenshots, plan text, or large blobs are persisted — the footprint is small.

### Storage details

- **Persistence**: SharedPreferences key `session_history` in the `ufo_galaxy_session_history` file.
- **Capacity**: up to 100 entries (drop-oldest when exceeded).
- **TTL**: entries older than 7 days are automatically evicted.
- **In-memory fallback**: if SharedPreferences is unavailable the store operates in memory only (no persistence across restarts).

### Viewing session history

The "Session History (N persisted)" card in the debug panel shows the most recent sessions (up to 10 by default), newest-first.

### Clearing session history

Tap **Clear Session History (N)** in the Developer Actions section to wipe all persisted entries. The button is disabled when the store is empty.

### Integration point

To persist a completed session from production code, call:

```kotlin
UFOGalaxyApplication.instance.let { app ->
    val debugVm = (app as? UFOGalaxyApplication)?.let { ... }
}
// Or via the MainViewModel:
viewModel.localLoopDebugViewModel.persistCompletedTrace(completedTrace)
```

The `SessionHistoryStore` singleton is exposed as `UFOGalaxyApplication.sessionHistoryStore` for direct access.

---

## Implementation Notes

### Key classes

| Class | Package | Purpose |
|---|---|---|
| `LocalLoopDebugState` | `com.ufo.galaxy.debug` | Immutable data model for the debug panel state |
| `LocalLoopDebugViewModel` | `com.ufo.galaxy.debug` | Aggregates readiness, trace, config, and history; owns developer actions |
| `LocalLoopDebugPanel` | `com.ufo.galaxy.ui.components` | Composable debug panel UI |
| `LocalLoopReadinessProvider` | `com.ufo.galaxy.local` | Source of the six-subsystem readiness snapshot (PR-E) |
| `LocalLoopTraceStore` | `com.ufo.galaxy.trace` | In-memory store of recent execution traces (PR-E) |
| `LocalLoopConfig` | `com.ufo.galaxy.config` | Active pipeline configuration (PR-E) |
| `SessionHistorySummary` | `com.ufo.galaxy.history` | Serializable summary of a completed session (PR-H) |
| `SessionHistoryStore` | `com.ufo.galaxy.history` | SharedPreferences-backed persistent session history store (PR-H) |

### Guard considerations

The debug panel entry point (🐛 button) is currently available in all build variants. To restrict it to debug builds only, wrap the `IconButton` in `MainActivity` with a build-config check:

```kotlin
if (BuildConfig.DEBUG) {
    IconButton(onClick = { viewModel.openLocalLoopDebug() }) {
        Icon(Icons.Default.BugReport, contentDescription = "Local-Loop Debug")
    }
}
```

The `LocalLoopDebugViewModel` itself imposes zero overhead until `openLocalLoopDebug()` is first called (lazy initialization).

### Log correlation

Every developer action emits a structured log event via `GalaxyLogger` under the tag `GALAXY:DEBUG:LOCAL_LOOP`. These events appear in the ring buffer alongside normal trace and readiness events and are included when exporting `galaxy_observability.log`.

---

## Related Documentation

- [`docs/LOCAL_LOOP_TESTING.md`](LOCAL_LOOP_TESTING.md) — local-loop correctness test harness (PR-F)
- [`docs/OBSERVABILITY.md`](OBSERVABILITY.md) — GalaxyLogger, MetricsRecorder, and trace store
- [`docs/DEPLOYMENT_GUIDE.md`](DEPLOYMENT_GUIDE.md) — device setup and readiness checklist
