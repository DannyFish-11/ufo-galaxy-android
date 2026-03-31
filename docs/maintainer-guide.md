# UFO Galaxy Android — Maintainer Guide

This guide helps new contributors get oriented quickly and helps reviewers understand which code paths are canonical, which are legacy, and how to configure and build the app safely.

For architecture detail see [`docs/architecture.md`](architecture.md). For flow diagrams see [`docs/execution-flows.md`](execution-flows.md).

---

## Orientation: what the app does

UFO Galaxy Android is an on-device AI agent. The core loop is:

1. Accept a natural-language goal from the user (text or voice).
2. Capture a screenshot of the current screen.
3. Run MobileVLM 1.7B to plan the next action.
4. Run SeeClick to ground the action to screen coordinates.
5. Dispatch the action via Android AccessibilityService.
6. Repeat until the goal is achieved, the step budget is exhausted, or a timeout fires.

This pipeline runs entirely on-device. Optionally, the device joins a Galaxy Gateway network (`cross_device_enabled=true`) where it can receive tasks from the server or hand off eligible tasks to Agent Runtime.

---

## What to read first

| If you want to understand… | Read… |
|----------------------------|-------|
| Overall system and components | [`docs/architecture.md`](architecture.md) |
| How tasks flow end-to-end | [`docs/execution-flows.md`](execution-flows.md) |
| How user input is dispatched | `app/…/input/InputRouter.kt` |
| How cross-device lifecycle works | `app/…/runtime/RuntimeController.kt` |
| How inbound gateway tasks are handled | `app/…/service/GalaxyConnectionService.kt` |
| How on-device execution works | `app/…/agent/EdgeExecutor.kt` |
| How logging and metrics work | [`docs/OBSERVABILITY.md`](OBSERVABILITY.md) |

---

## Canonical components — what new code should use

These are the stable, authoritative classes. New features and bug fixes should build on them.

### User input dispatch

**Use:** `InputRouter` (`input/`)

```kotlin
// Correct: all user input goes through InputRouter
inputRouter.route(
    text = userText,
    onLocalResult = { result -> /* update UI */ },
    onError = { reason -> /* show error */ }
)
```

`InputRouter` handles the local-vs-cross-device decision internally based on `AppSettings.crossDeviceEnabled` and WS connection state. You should not make this decision yourself.

### Local task execution

**Use:** `LocalLoopExecutor` (`local/`) — public API with `LocalLoopOptions` / `LocalLoopResult`

`LoopController` (`loop/`) is the internal orchestrator; prefer `LocalLoopExecutor` at call sites.

For gateway-assigned `goal_execution` payloads, use `LocalGoalExecutor` (`agent/`).

### Cross-device lifecycle

**Use:** `RuntimeController` (`runtime/`)

```kotlin
// Correct: all lifecycle transitions go through RuntimeController
runtimeController.startWithTimeout()   // enable cross-device
runtimeController.stop()               // disable cross-device
runtimeController.connectIfEnabled()   // background restore
```

Never call `GalaxyWebSocketClient.connect()`, `.disconnect()`, or mutate `AppSettings.crossDeviceEnabled` directly from outside `RuntimeController`.

### Gateway messaging

**Use:** `GatewayClient` for outbound task-submit messages.
`GalaxyWebSocketClient` for all other WS operations (already used internally by `RuntimeController` and `GalaxyConnectionService`).

### Configuration access

**Use:** `AppSettings` (`data/`) for all runtime configuration.

```kotlin
val settings: AppSettings = UFOGalaxyApplication.appSettings
val gatewayUrl = settings.effectiveGatewayWsUrl()
val crossDevice = settings.crossDeviceEnabled
```

`LocalLoopConfig.from(settings)` builds the full local-loop configuration object from `AppSettings`.

### Observability

**Use:** `GalaxyLogger` for structured log events and `MetricsRecorder` for counters/latencies.

```kotlin
GalaxyLogger.log(TAG_LOCAL_LOOP_START, mapOf("task_id" to taskId))
metricsRecorder.recordLocalLoopStep()
```

---

## Legacy / deprecated components — what new code should avoid

| Component | Why it exists | What to use instead |
|-----------|---------------|---------------------|
| `GalaxyApiClient.registerDevice` | Legacy REST registration | `GalaxyWebSocketClient` sends `capability_report` on connect automatically |
| `GalaxyApiClient.sendHeartbeat` | Legacy REST heartbeat | `GalaxyWebSocketClient` sends `heartbeat` every 30 s automatically |
| `MessageRouter` | Was an earlier routing layer; no longer present in the codebase | `InputRouter` |

These classes (`GalaxyApiClient.registerDevice`, `GalaxyApiClient.sendHeartbeat`) are retained because they serve valid diagnostic or compatibility purposes. They are annotated `@Deprecated` in source. Do not add new call sites. `MessageRouter` has been removed from the codebase entirely.

---

## Configuration model

Configuration has three layers. The app reads them in priority order at runtime:

```
Priority 1 (highest): SharedPreferences — in-app settings UI
Priority 2:           assets/config.properties — packaged at build time
Priority 3 (lowest):  app/build.gradle BuildConfig fields — compile-time constants
```

`AppSettings` (`data/AppSettings.kt`) encapsulates this priority logic. Always use `AppSettings` rather than reading layers directly.

### Key settings

| Setting | Default | Notes |
|---------|---------|-------|
| `cross_device_enabled` | `false` | Master switch for gateway connectivity |
| `gatewayHost` / `gatewayPort` | — | Combined by `effectiveGatewayWsUrl()` |
| `useTls` | `false` | `true` → `wss://` / `https://` |
| `allowSelfSigned` | `false` | Dev/intranet only; trust-all TrustManager |
| `deviceId` | system default | Reported in capability_report and heartbeats |
| `metricsEndpoint` | `""` | If non-empty, MetricsRecorder POSTs metrics here |

### `config.properties` (repository root)

This file sets the build-time defaults baked into the APK at assembly time. The canonical default:

```properties
cross_device_enabled=false
```

This default keeps the app in local-only mode out of the box. Override it at any configuration layer before enabling cross-device features.

### `assets/config.properties`

Packaged into the APK. Override this file for pre-configured APK distribution without code changes.

---

## Build guidance

### Debug build (recommended for development)

```bash
./gradlew assembleDebug
# or
./build_apk.sh --debug-only
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### CI build

```bash
CI=true ./build_apk.sh
```

`CI=true` forces debug-only, non-interactive mode. No prompts, no release build.

### Release build

Release builds require a keystore. **Never commit keystore credentials to source control.** Pass them via environment variables:

```bash
export KEYSTORE_PATH=/path/to/release.jks
export KEYSTORE_PASSWORD=<password>
export KEY_ALIAS=<key-alias>
export KEY_PASSWORD=<key-password>

./build_apk.sh --release
```

`build_apk.sh` validates all four variables before invoking Gradle. If any are missing it exits with a clear error.

Gradle invocation (for reference — prefer the script):

```bash
./gradlew assembleRelease \
  -Pandroid.injected.signing.store.file="$KEYSTORE_PATH" \
  -Pandroid.injected.signing.store.password="$KEYSTORE_PASSWORD" \
  -Pandroid.injected.signing.key.alias="$KEY_ALIAS" \
  -Pandroid.injected.signing.key.password="$KEY_PASSWORD"
```

### Environment prerequisites

| Requirement | Notes |
|-------------|-------|
| JDK 17+ | `java -version` must show 17+ |
| Android SDK | `ANDROID_HOME` should be set; `build_apk.sh` warns if missing |
| Gradle wrapper | `./gradlew` downloads the correct version automatically |

---

## Adding new features

### Before you start

1. Check `AppSettings` — does the feature need a configuration knob? Add it there.
2. Check `LocalLoopConfig` — does it affect the local pipeline? Extend `LocalLoopConfig` rather than adding standalone constants.
3. Is it user-input-driven? Route through `InputRouter`.
4. Does it need telemetry? Add a counter or latency list to `MetricsRecorder` and a log tag to `GalaxyLogger`.

### Checklist

- [ ] New routing decisions go through `InputRouter`, not around it.
- [ ] New cross-device lifecycle changes go through `RuntimeController`.
- [ ] New outbound gateway messages use `GatewayClient.sendJson` (or `GalaxyWebSocketClient.sendJson`).
- [ ] New configuration values go in `AppSettings` with the three-layer priority model.
- [ ] Observability hooks added: `GalaxyLogger` log tag + `MetricsRecorder` counter/latency.
- [ ] `ReadinessChecker` consulted if the feature requires model files, accessibility, or overlay.
- [ ] No new calls to deprecated REST methods in `GalaxyApiClient`.

---

## Readiness and degraded mode

`ReadinessChecker` (`service/`) checks three pre-conditions before autonomous execution can proceed:

| Flag | What it checks |
|------|----------------|
| `modelReady` | Model files present and verified on disk |
| `accessibilityReady` | `HardwareKeyListener` accessibility service is enabled in system settings |
| `overlayReady` | `SYSTEM_ALERT_WINDOW` permission is granted |

If any flag is `false`, `ReadinessState.degradedMode == true`. `DiagnosticsScreen` shows this state. Autonomous execution may be limited or blocked in degraded mode.

---

## Observability quick reference

| What to look at | Where |
|-----------------|-------|
| Structured logs (in-process ring buffer, last 500) | `GalaxyLogger` |
| Log file on device | `galaxy_observability.log` (2 MB cap) |
| Share log from app | Diagnostics screen → share button (FileProvider) |
| Counters / latencies | `MetricsRecorder` |
| Local-loop traces (step-by-step) | `LocalLoopTraceStore` → debug panel (🐛 icon) |
| Session history | `SessionHistoryStore` → debug panel |
| Connection state / readiness | Diagnostics screen (ⓘ icon) |

See [`docs/OBSERVABILITY.md`](OBSERVABILITY.md) for the full observability reference including log tags and metric names.

---

## Testing guidance

- Unit-testable interfaces: `EdgeExecutor.ScreenshotProvider`, `GatewayClient`, `LocalPlannerService`, `LocalGroundingService` — all are interface/injectable.
- `LocalLoopExecutor` takes `LoopController` as a constructor parameter; inject a fake in tests.
- `AgentRuntimeBridge` uses `GatewayClient` interface — inject a fake to test handoff logic without network.
- Integration tests: see [`docs/LOCAL_LOOP_TESTING.md`](LOCAL_LOOP_TESTING.md) and [`docs/e2e-verification.md`](e2e-verification.md).
- Regression checklist: [`docs/android-regression-checklist.md`](android-regression-checklist.md).
