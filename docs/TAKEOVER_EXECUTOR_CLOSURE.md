# Takeover Executor Closure — Android Side

## Summary

This document records the closure of the Android-side takeover executor path and the
evidence proving it is complete enough for V2 to rely on.  It is intended as the
validation artifact called for by the third-round joint review (PR #811).

---

## What is now fully closed (Android side)

### 1. Eligibility gate
`TakeoverEligibilityAssessor` evaluates all readiness preconditions
(`crossDeviceEnabled`, `goalExecutionEnabled`, `accessibilityReady`, `overlayReady`,
concurrent-takeover guard) and returns a structured `EligibilityResult` with a stable
machine-readable `reason` string.  Rejection at this gate sends a
`TakeoverResponseEnvelope(accepted=false, rejection_reason=…)` back to V2.

### 2. Session gate
`DelegatedRuntimeReceiver.receive()` enforces that an `AttachedRuntimeSession` in
`ATTACHED` state and `delegatedExecutionAllowed = true` must both be present before
delegated work is accepted.  Rejection at this gate also sends a structured
`TakeoverResponseEnvelope`.

### 3. Acceptance response with host identity
When both gates pass, `GalaxyConnectionService` sends `TakeoverResponseEnvelope(accepted=true)`
immediately — before the coroutine is launched — so V2 can update its session truth
synchronously.  The response carries `runtime_host_id` and `formation_role` so V2 can
record this Android instance as a formal execution surface.

### 4. Canonical executor — lifecycle and signal emission
`DelegatedTakeoverExecutor.execute()` owns the full lifecycle:

| Step | State transition | Signal emitted |
|------|-----------------|----------------|
| 1 | Creates `DelegatedExecutionTracker` (PENDING) | — |
| 2 | — | ACK (`emissionSeq=1`) |
| 3 | PENDING → ACTIVATING | — |
| 4 | ACTIVATING → ACTIVE | — |
| 5 | — | PROGRESS (`emissionSeq=2`) |
| 6 | Pipeline executes | — |
| 7a (success) | ACTIVE → COMPLETED | RESULT(COMPLETED, `emissionSeq=3`) |
| 7b (failure) | ACTIVE → FAILED | RESULT(FAILED, `emissionSeq=3`) |
| 7c (timeout) | ACTIVE → FAILED | RESULT(TIMEOUT, `emissionSeq=3`) |
| 7d (cancel) | ACTIVE → FAILED | RESULT(CANCELLED, `emissionSeq=3`) |

Every signal carries the full identity set: `unitId`, `taskId`, `traceId`,
`attachedSessionId`, `handoffContractVersion`, `signalId`, `emissionSeq`.

### 5. EmittedSignalLedger (replay-safe identity)
Every emitted signal is recorded in a per-execution `EmittedSignalLedger`.  Callers
can replay any signal via `ledger.replaySignal(kind, replayTimestampMs)` which
preserves the original `signalId` and `emissionSeq` so V2 can detect re-deliveries
without false duplicate alerts.  The ledger is returned in every `ExecutionOutcome`.

### 6. Structured outbound transport
Each signal is immediately serialised to a `DelegatedExecutionSignalPayload` and sent
as a `DELEGATED_EXECUTION_SIGNAL` AIP v3 message via `sendDelegatedExecutionSignal()`.
Send failure never interrupts the execution lifecycle.

### 7. Failure/timeout/cancellation → RuntimeController
On any non-completion outcome `GalaxyConnectionService` calls
`runtimeController.notifyTakeoverFailed(takeoverId, taskId, traceId, reason, cause)`
with a typed `TakeoverFallbackEvent.Cause` derived from the ledger's last RESULT signal:
- `TIMEOUT` for `TimeoutCancellationException`
- `CANCELLED` for `CancellationException` (non-timeout)
- `FAILED` for all other exceptions

`RuntimeController` deduplicates simultaneous failure notifications for the same
`takeoverId` (PR-29) and emits a single `TakeoverFallbackEvent` on the
`takeoverFailure` shared-flow for surface-layer consumption.

### 8. Remote-task flight state
`runtimeController.onRemoteTaskStarted()` is called before the executor coroutine is
launched; `onRemoteTaskFinished()` is called in the `finally` block unconditionally.
This ensures `LoopController.isRemoteTaskActive` is always cleared after the execution
completes or fails.

---

## Test evidence

| Test class | What it proves |
|-----------|---------------|
| `TakeoverEligibilityAssessorTest` | All eligibility conditions; structured rejection reasons |
| `DelegatedRuntimeReceiverTest` | Session gate; null/detaching/detached session rejection |
| `DelegatedTakeoverExecutorTest` | ACK/PROGRESS/RESULT emission; success/failure/timeout/cancel paths; ledger populated correctly on all paths; identity continuity; payload construction |
| `DelegatedExecutionCanonicalPathTest` | Full receipt→count→executor→signal chain; timeout/cancel in canonical order; rejection emits 0 signals |
| `DelegatedSignalReplayIdentityTest` | Ledger replay preserves signalId + emissionSeq on all three signal kinds |
| `CrossDeviceFallbackClosureTest` | `notifyTakeoverFailed` emits on `takeoverFailure`; all Cause variants; dedup guard |
| `HandoffTakeoverCanonicalPathTest` | Request/response envelope parsing; MsgType registration |
| `EmittedSignalLedgerTest` | Ledger record/replay semantics; null guard |

---

## What remains for the V2-side companion PR

The following items are outside the scope of this Android PR and are the responsibility
of the V2 companion PR:

1. **`send_takeover_request()` verification** — V2 must confirm that its outbound
   `takeover_request` path can reach this Android executor and trigger execution.
2. **`takeover_response` absorption** — V2 must confirm that the acceptance/rejection
   `TakeoverResponseEnvelope` is absorbed into V2 session tracking and truth state.
3. **`DELEGATED_EXECUTION_SIGNAL` handler** — V2 must confirm that incoming ACK /
   PROGRESS / RESULT signals from Android update V2 delegated-flow tracking state.
4. **E2E round-trip validation** — A live test confirming that a `takeover_request`
   sent from V2 reaches Android execution and that the RESULT signal is absorbed into
   V2 canonical truth.

---

## Constraints preserved

- No new orchestration authority introduced on Android.
- `DelegatedTakeoverExecutor` is the single lifecycle authority; no parallel paths.
- Local execution and delegated runtime architecture are unchanged.
- Legacy path default-off and CI/release governance are intentionally out of scope.
