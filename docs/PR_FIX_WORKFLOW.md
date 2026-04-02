# PR Fix Workflow

Use this workflow when preparing a bug-fix or regression-fix PR for this repository.
It is intended to keep fixes scoped to the canonical code paths, tied to reproducible
failures, and verified against the existing Android regression checklist.

---

## 1. Define the fix boundary first

Before editing code, classify the issue by layer and keep the fix inside the owning
component.

| Layer | Canonical entry points / owners |
|------|----------------------------------|
| User-input routing | `InputRouter` |
| Cross-device lifecycle | `RuntimeController` |
| Gateway transport / reconnect / offline queue | `GalaxyWebSocketClient`, `GatewayClient` |
| Runtime configuration | `AppSettings`, `LocalLoopConfig` |
| Gateway task dispatch / cancel / timeout | `GalaxyConnectionService`, `LocalGoalExecutor`, `TaskCancelRegistry`, `AipModels` |
| Local closed-loop execution | `LocalLoopExecutor`, `LoopController`, `local/*` |
| Observability | `GalaxyLogger`, `MetricsRecorder`, `LocalLoopTraceStore`, `SessionHistoryStore` |
| Debug / diagnostics UI | `DiagnosticsScreen`, `LocalLoopDebugPanel`, `MainActivity` |

Rules:

- Fix the issue through the authoritative component for that layer.
- Do not bypass `InputRouter`, `RuntimeController`, `AppSettings`, or the existing
  observability APIs.
- Limit changes to the failing path and tightly coupled guardrails.

---

## 2. Reproduce and record the failure

Start from the repository baseline before making changes.

```bash
cd /home/runner/work/ufo-galaxy-android/ufo-galaxy-android
./gradlew assembleDebug
./gradlew :app:test --tests "com.ufo.galaxy.*" --info
```

If the issue involves networking, reconnect, offline queue, foreground service, or UI
state, also map it to the relevant manual sections in
[`docs/android-regression-checklist.md`](android-regression-checklist.md):

- Sections 2-4: service lifecycle, reconnect, offline handling
- Section 5: AIP protocol contracts
- Sections 7-9: reconnect backoff, offline queue, connection status UI

Write down a minimal failure record:

- Trigger / preconditions
- Expected behavior
- Actual behavior
- Affected modules
- Logs, metrics, traces, or screenshots that prove the failure

If environment issues block local verification, record the blocker explicitly instead of
marking the step as passed.

---

## 3. Do root-cause analysis through one owning path

Trace the failure back through the owning abstraction before touching code.

- Input split issues -> start at `InputRouter`
- Toggle / reconnect / restore issues -> start at `RuntimeController` and
  `GalaxyWebSocketClient`
- Task dispatch / cancel / timeout issues -> start at `GalaxyConnectionService`,
  `LocalGoalExecutor`, `TaskCancelRegistry`, `AipModels`
- Local execution / replanning / grounding issues -> start at `LocalLoopExecutor`,
  `LoopController`, `local/*`
- State or diagnostics mismatch -> start at the source `StateFlow` / `ViewModel` before
  editing UI composables

Avoid “while here” refactors unless the code is directly responsible for the failure.

---

## 4. Apply the smallest complete fix

Implementation rules:

- Preserve the current configuration priority model:
  SharedPreferences -> `assets/config.properties` -> `BuildConfig`
- Preserve the lifecycle authority model:
  `RuntimeController` owns cross-device ON/OFF transitions
- Preserve routing authority:
  `InputRouter` decides local vs cross-device routing
- Preserve existing observability style:
  use `GalaxyLogger` tags and `MetricsRecorder` counters/latencies instead of ad hoc logs

If the fix changes behavior that operators or reviewers need to understand, update the
relevant docs in the same PR.

Recommended edit order:

1. Models / protocol contracts
2. Core logic
3. State synchronization
4. UI display / diagnostics
5. Documentation

---

## 5. Add validation for the failure and the regression

Prefer existing unit tests when possible.

Expected coverage for a real fix:

- happy path
- failing path
- edge or boundary condition
- regression case proving the bug stays fixed

Use the existing test suites closest to the changed area, for example:

- `app/src/test/java/com/ufo/galaxy/input/` for routing
- `app/src/test/java/com/ufo/galaxy/runtime/` and `network/` for lifecycle / reconnect
- `app/src/test/java/com/ufo/galaxy/agent/` and `protocol/` for task dispatch, timeout,
  cancel, and AIP payloads
- `app/src/test/java/com/ufo/galaxy/local/` and `loop/` for local closed-loop behavior
- `app/src/test/java/com/ufo/galaxy/debug/`, `history/`, `trace/`, and `observability/`
  for diagnostics and telemetry

When unit tests cannot fully express the bug, add manual verification steps that point to
specific checklist rows in
[`docs/android-regression-checklist.md`](android-regression-checklist.md),
[`docs/LOCAL_LOOP_TESTING.md`](LOCAL_LOOP_TESTING.md), or
[`docs/LOCAL_DEBUGGING.md`](LOCAL_DEBUGGING.md).

---

## 6. Re-run verification and report only what actually passed

Minimum verification for a fix PR:

```bash
cd /home/runner/work/ufo-galaxy-android/ufo-galaxy-android
./gradlew assembleDebug
./gradlew :app:test --tests "com.ufo.galaxy.*" --info
```

Add targeted manual verification when the fix touches:

- service lifecycle / startup restore -> regression checklist section 2
- reconnect / offline / queue / status UI -> sections 3, 4, 7, 8, 9
- AIP models and payload contracts -> section 5
- local closed-loop execution and debugging -> `LOCAL_LOOP_TESTING.md` and
  `LOCAL_DEBUGGING.md`

Record the final state as:

- Verified locally
- Verified manually
- Not verified due to external blocker

Do not sign off sections that were not actually exercised.

---

## 7. Structure the PR for reviewers

Every fix PR should include:

- Problem statement
- Root cause
- Fix scope
- Affected modules
- Validation performed
- Risks
- Rollback plan

If a behavior, operator workflow, configuration path, or debug surface changed, update:

- [`README.md`](../README.md)
- [`docs/maintainer-guide.md`](maintainer-guide.md)
- [`docs/android-regression-checklist.md`](android-regression-checklist.md)

---

## 8. Deliverables checklist

- Minimal code fix through the canonical owner
- Test coverage or explicit manual verification steps
- Documentation updates when behavior or workflow changed
- Reviewer-ready PR description
- Reproducible validation record
- Clear rollback path
