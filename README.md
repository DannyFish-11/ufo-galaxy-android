# UFO Galaxy Android

**Version: v3.0.0 · Protocol: AIP v3.0**

UFO Galaxy Android is an L4-autonomy AI agent client for Android. It operates in two distinct modes:

- **Local-only** (default) — all inference and UI-automation runs entirely on the device.
- **Cross-device** (opt-in) — the device connects to a Galaxy Gateway and participates in a distributed task network.

The two modes share the same on-device execution engine; only the input-routing and lifecycle layers differ.

> For architecture details, flow maps, and maintainer guidance see:
> - [`docs/architecture.md`](docs/architecture.md)
> - [`docs/execution-flows.md`](docs/execution-flows.md)
> - [`docs/maintainer-guide.md`](docs/maintainer-guide.md)

---

## Requirements

| Dependency | Version |
|------------|---------|
| Android Studio | Arctic Fox (2020.3.1)+ |
| JDK | 17+ |
| Android SDK | API 26 (Android 8.0)+ |
| Kotlin | 1.9.21 |
| Gradle | 8.4 |

---

## Quick start

### 1. Build a debug APK

```bash
chmod +x build_apk.sh
./build_apk.sh          # interactive; prompts before building release
# or
./gradlew assembleDebug
```

Install:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 2. Configure the gateway (cross-device mode only)

The app ships with `cross_device_enabled=false` (local-only mode). If you want to connect to a Galaxy Gateway:

**Recommended — in-app settings (no repackaging needed):**

Open the app → ⚙ icon → **Network & Diagnostics**:

| Field | Example |
|-------|---------|
| Host / IP | `100.64.0.1` (Tailscale) or `192.168.1.100` |
| Port | `8765` |
| Use TLS | off for dev, on for production |
| Allow self-signed | dev/intranet only |
| Device ID | leave blank for system default |
| Metrics endpoint | optional, e.g. `http://100.64.0.1:9090/metrics` |

Tap **Save & Reconnect**.

**Alternative — `assets/config.properties` (packaged default, no code change):**

```properties
galaxy_gateway_url=ws://100.64.0.1:8765
rest_base_url=http://100.64.0.1:8765
cross_device_enabled=true
```

**Alternative — compile-time default (`app/build.gradle`):**

```gradle
buildConfigField "String", "GALAXY_SERVER_URL", '"ws://192.168.1.100:8765"'
```

**Configuration priority (high → low):**

1. In-app settings (SharedPreferences) — runtime, survives reinstall until cleared
2. `assets/config.properties` — packaged at build time, easy to override without code changes
3. `app/build.gradle` `BuildConfig` fields — compile-time constants

See [`docs/maintainer-guide.md`](docs/maintainer-guide.md#configuration) for the full model.

---

## Release builds

Release builds require a signing keystore. Pass credentials via environment variables — **never hardcode them**:

```bash
export KEYSTORE_PATH=/path/to/release.jks
export KEYSTORE_PASSWORD=<password>
export KEY_ALIAS=<alias>
export KEY_PASSWORD=<password>

./build_apk.sh --release
```

CI environments should set `CI=true` (debug-only, non-interactive):

```bash
CI=true ./build_apk.sh
```

---

## Project structure

```
ufo-galaxy-android/
├── app/src/main/java/com/ufo/galaxy/
│   ├── UFOGalaxyApplication.kt       # Application entry; singleton wiring
│   ├── agent/                        # On-device execution: EdgeExecutor, LocalGoalExecutor,
│   │                                 #   AgentRuntimeBridge, TaskCancelRegistry
│   ├── config/                       # LocalLoopConfig — local pipeline configuration
│   ├── data/                         # AppSettings, data models
│   ├── debug/                        # LocalLoopDebugState/ViewModel — developer tooling
│   ├── history/                      # SessionHistoryStore — completed-session history
│   ├── inference/                    # LocalPlannerService, LocalGroundingService
│   ├── input/                        # InputRouter — canonical user-input dispatch gate
│   ├── local/                        # LocalLoopExecutor, LocalLoopResult, supporting types
│   ├── loop/                         # LoopController — local closed-loop orchestration
│   ├── memory/                       # OpenClawdMemoryBackflow
│   ├── network/                      # GalaxyWebSocketClient, GatewayClient, OfflineTaskQueue,
│   │                                 #   NetworkDiagnostics, TailscaleAdapter
│   ├── observability/                # GalaxyLogger, MetricsRecorder, TraceContext,
│   │                                 #   SamplingConfig, TelemetryExporter
│   ├── protocol/                     # AipModels — AIP v3.0 message types
│   ├── runtime/                      # RuntimeController — cross-device lifecycle authority
│   ├── service/                      # GalaxyConnectionService (inbound WS dispatch),
│   │                                 #   EnhancedFloatingService, ReadinessChecker,
│   │                                 #   BootReceiver
│   ├── speech/                       # NaturalLanguageInputManager
│   ├── trace/                        # LocalLoopTrace, LocalLoopTraceStore
│   ├── ui/                           # MainActivity, composables, RegistrationFailureNotifier
│   └── webrtc/                       # WebRTCSignalingClient, IceCandidateManager, TurnConfig
├── config.properties                 # Build-time default (cross_device_enabled=false)
├── build_apk.sh                      # APK build script
└── docs/                             # Architecture, flow, and maintainer docs
```

---

## Permissions

| Permission | Purpose |
|------------|---------|
| `INTERNET` | Network communication |
| `RECORD_AUDIO` | Voice input |
| `CAMERA` | Camera capability |
| `SYSTEM_ALERT_WINDOW` | Floating overlay window |
| `FOREGROUND_SERVICE` | Background service |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on device boot |
| `VIBRATE` | Haptic feedback |
| `POST_NOTIFICATIONS` | Status notifications |

---

## Key documentation

| Document | What it covers |
|----------|----------------|
| [`docs/architecture.md`](docs/architecture.md) | System overview, canonical component index, legacy components, authority model |
| [`docs/execution-flows.md`](docs/execution-flows.md) | Local and cross-device execution flow maps, gateway task dispatch, runtime lifecycle |
| [`docs/maintainer-guide.md`](docs/maintainer-guide.md) | Onboarding guide, canonical vs deprecated API, configuration model, build guidance |
| [`docs/ugcp/ANDROID_UGCP_CONSTITUTION.md`](docs/ugcp/ANDROID_UGCP_CONSTITUTION.md) | Android-side UGCP Runtime WS Profile declaration, constitution, canonical vocabulary, and control-plane phase/field alignment |
| [`docs/PR_FIX_WORKFLOW.md`](docs/PR_FIX_WORKFLOW.md) | Standard workflow for bug-fix / regression-fix PRs |
| [`docs/OBSERVABILITY.md`](docs/OBSERVABILITY.md) | Logging, metrics, tracing |
| [`docs/LOCAL_LOOP_TESTING.md`](docs/LOCAL_LOOP_TESTING.md) | Local loop test harness |
| [`docs/DEPLOYMENT_GUIDE.md`](docs/DEPLOYMENT_GUIDE.md) | Deployment reference |

---

## Related repositories

| Repository | Role |
|------------|------|
| [ufo-galaxy-realization](https://github.com/DannyFish-11/ufo-galaxy-realization) | Galaxy Gateway server (counterpart to this client) |
