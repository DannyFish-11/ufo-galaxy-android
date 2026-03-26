# Local-Loop Testing Guide

This document describes the local-loop test harness introduced in PR-F and explains
how to add new regression scenarios as the on-device execution pipeline evolves.

---

## Overview

The local loop is the on-device closed-loop automation pipeline:

```
natural-language instruction
  → DefaultLocalLoopExecutor   (readiness gate)
  → LoopController             (step budget, timeouts, stagnation guards)
  → LocalPlanner               (MobileVLM / rule-based fallback)
  → ExecutorBridge             (SeeClick grounding + AccessibilityService dispatch)
  → PostActionObserver         (UI-change detection)
  → StagnationDetector         (repeated-action / no-UI-change guard)
```

The harness exercises this pipeline end-to-end using lightweight in-memory fakes,
so every test runs on the JVM without a device, emulator, or network.

---

## Key files

| File | Purpose |
|------|---------|
| `app/src/test/java/com/ufo/galaxy/local/LocalLoopTestFakes.kt` | Reusable fake implementations of all pipeline dependencies |
| `app/src/test/java/com/ufo/galaxy/local/LocalLoopScenario.kt` | Data class that describes a single test scenario |
| `app/src/test/java/com/ufo/galaxy/local/LocalLoopScenarioRunner.kt` | Wires fakes into a full pipeline and executes a scenario |
| `app/src/test/java/com/ufo/galaxy/local/LocalLoopCorrectnessTest.kt` | Correctness tests for all major pipeline behaviors |
| `app/src/test/java/com/ufo/galaxy/local/LocalLoopReadinessTest.kt` | Unit tests for `LocalLoopReadiness` and `LocalLoopState` |
| `app/src/test/java/com/ufo/galaxy/loop/LoopControllerTest.kt` | Unit tests for `LoopController` in isolation |

---

## Running the tests

```bash
./gradlew :app:testDebugUnitTest \
    --tests "com.ufo.galaxy.local.LocalLoopCorrectnessTest"
```

Run the full local-loop test suite:

```bash
./gradlew :app:testDebugUnitTest \
    --tests "com.ufo.galaxy.local.*" \
    --tests "com.ufo.galaxy.loop.*"
```

---

## Fake components

All fakes live in `LocalLoopTestFakes.kt` and follow a common pattern:
constructor-level configuration + companion factory methods for the most common shapes.

### FakePlannerService

| Factory method | Behaviour |
|----------------|-----------|
| `singleStep()` | Returns one tap step; planner loaded |
| `multiStep(*steps)` | Returns multiple steps |
| `alwaysEmpty()` | Plan returns no steps (exhausts ladder's first stages; rule-based fires) |
| `planError(message)` | Plan returns an error with empty steps |
| `notLoaded()` | Planner model not loaded; triggers rule-based path in `PlannerFallbackLadder` |
| `withReplan(planStep, replanStep)` | First plan succeeds; replan returns recovery step |
| `replanFails(planStep)` | First plan succeeds; replan returns empty steps |

### FakeGroundingService

| Factory method | Behaviour |
|----------------|-----------|
| `alwaysSucceed()` | Returns centre-screen coordinates with 0.9 confidence |
| `notLoaded()` | Model not loaded; fallback ladder takes over |
| `alwaysFail(error)` | Returns error result; fallback ladder tries heuristic stages |
| `slow(delayMs)` | Adds delay per call; useful for step-timeout tests |

### FakeScreenshotProvider

| Factory method | Behaviour |
|----------------|-----------|
| `varying()` | Each call returns slightly different bytes (UI change detected) |
| `noChange()` | All calls return identical bytes; triggers no-UI-change stagnation |
| `alwaysFail()` | Always throws; triggers `STOP_SCREENSHOT_FAILED` |

### FakeAccessibilityExecutor

| Factory method | Behaviour |
|----------------|-----------|
| `alwaysSucceed()` | Returns `true` on every dispatch |
| `alwaysFail()` | Returns `false` on every dispatch |
| `slow(delayMs)` | Adds delay per call; useful for timeout tests |

### FakeReadinessProvider

| Factory method | Behaviour |
|----------------|-----------|
| `fullyReady()` | All subsystems ready → `LocalLoopState.READY` |
| `unavailable()` | Critical blockers present → `LocalLoopState.UNAVAILABLE` (blocks execution) |
| `degraded()` | Non-critical blockers → `LocalLoopState.DEGRADED` (execution proceeds) |
| `withBlockers(*types)` | Custom blocker set |

---

## Adding a new scenario

1. **Identify the behaviour** you want to cover (e.g., _"planner times out on the
   second step"_).

2. **Choose or compose fakes** from `LocalLoopTestFakes.kt`.  If no existing fake
   covers your case, add a new factory method (or subclass) to the relevant fake class.

3. **Create a `LocalLoopScenario`**:

   ```kotlin
   LocalLoopScenario(
       name = "planner-slow-second-step",
       planner = FakePlannerService.multiStep("tap" to "step 1", "tap" to "step 2"),
       accessibilityExecutor = FakeAccessibilityExecutor.slow(delayMs = 20L),
       stepTimeoutMs = 10L
   )
   ```

4. **Add an assertion** in `LocalLoopCorrectnessTest` (or a new test class):

   ```kotlin
   @Test
   fun `slow second step triggers step timeout`() {
       val result = runner.run(
           LocalLoopScenario(
               name = "slow-second-step",
               planner = FakePlannerService.multiStep("tap" to "s1", "tap" to "s2"),
               accessibilityExecutor = FakeAccessibilityExecutor.slow(delayMs = 20L),
               stepTimeoutMs = 10L
           )
       )
       assertEquals(LocalLoopResult.STATUS_FAILED, result.status)
       assertEquals(LoopController.STOP_STEP_TIMEOUT, result.stopReason)
   }
   ```

5. **Run the test** locally to confirm it passes:

   ```bash
   ./gradlew :app:testDebugUnitTest \
       --tests "com.ufo.galaxy.local.LocalLoopCorrectnessTest.slow second step triggers step timeout"
   ```

---

## Behaviour coverage

The following table maps each acceptance criterion to the test(s) that cover it.

| Behaviour | Test method(s) |
|-----------|----------------|
| Readiness gating — UNAVAILABLE blocks | `readiness UNAVAILABLE blocks execution…`, `…zero steps`, `…stop reason` |
| Readiness DEGRADED does not block | `readiness DEGRADED does not block execution` |
| Happy-path success | `happy path single step returns STATUS_SUCCESS`, `…step count`, `…session id` |
| Planner not loaded → rule-based fallback | `planner not loaded falls back to rule-based and succeeds` |
| Replan on step failure | `replan triggered on step failure succeeds with recovery step` |
| Replan exhausted | `replan exhausted returns STATUS_FAILED` |
| Grounding fails → ladder fallback | `grounding fails but fallback ladder produces coordinates…` |
| Grounding not loaded → heuristic | `grounding model not loaded falls back to heuristic…` |
| Non-coordinate actions bypass grounding | `back action bypasses grounding entirely` |
| Repeated-action stagnation | `repeated identical action triggers stagnation termination` |
| No-UI-change stagnation | `no-UI-change stagnation terminates the session` |
| Step timeout | `step timeout terminates session with STOP_STEP_TIMEOUT` |
| Goal timeout | `goal timeout terminates session with STOP_GOAL_TIMEOUT` |
| Screenshot failure propagation | `screenshot failure propagates STOP_SCREENSHOT_FAILED` |
| Step retries exhausted | `step retries exhausted propagates STOP_STEP_EXHAUSTED` |
| Max steps budget | `max steps budget exceeded propagates STOP_MAX_STEPS` |
| Remote-task block | `remote task block produces STATUS_CANCELLED with STOP_BLOCKED_BY_REMOTE` |
| Remote-task clear resumes execution | `after remote task cleared execution resumes normally` |
| Failure code propagation — session ID | `failure result has non-empty sessionId for tracing` |

---

## Design principles

- **Deterministic.** Fakes are pure functions with no hidden state that persists
  across test runs.  Each test creates a fresh `LocalLoopScenarioRunner` and scenario.

- **No Android framework.** All fakes and the pipeline they wrap are pure Kotlin; no
  `android.*` imports appear in test sources.  Tests run on the host JVM.

- **Lightweight.** The harness only exercises the actual production pipeline.  It does
  not add a second simulation layer on top of `LoopController`; assertions are made
  directly on `LocalLoopResult`.

- **Minimal boilerplate.** Adding a new scenario requires at most 5–10 lines: a
  `LocalLoopScenario(...)` literal plus an assertion.

- **Stable vocabulary.** Assertions use `LoopController.STOP_*` and
  `LocalLoopResult.STATUS_*` constants so test intent is clear and string typos are
  caught at compile-time.
