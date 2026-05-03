# UFO Galaxy — Dual-Repo Setup Guide

**Covers:** `ufo-galaxy-android` (this repo) + `ufo-galaxy-realization` (V2 backend)

This guide walks an operator through starting **both systems together** and verifying
an end-to-end connected state. Complete it once top to bottom on first setup; subsequent
starts can skip straight to [§4 Starting the system](#4-starting-the-system).

---

## Prerequisites

| Component | Requirement |
|-----------|-------------|
| Android device | API 26+ (Android 8.0+); USB debugging enabled |
| Android Studio | Arctic Fox (2020.3.1)+ with Kotlin plugin |
| JDK | 17+ |
| Python | 3.10+ (for V2 backend) |
| Network | Both device and V2 host on the same network (LAN or Tailscale VPN) |

---

## 1. Clone both repositories

```bash
# Clone the Android client
git clone https://github.com/DannyFish-11/ufo-galaxy-android.git

# Clone the V2 backend (counterpart)
git clone https://github.com/DannyFish-11/ufo-galaxy-realization.git
```

---

## 2. Start the V2 backend

```bash
cd ufo-galaxy-realization

# Install Python dependencies
pip install -r requirements.txt

# Start the gateway — replace <host-ip> with your machine's LAN or Tailscale IP
python main.py --host <host-ip> --port 8765
```

Verify V2 is running:

```bash
# Should return {"status":"ok"} or similar
curl http://<host-ip>:8765/api/v1/config
```

> **Tip (Tailscale):** If you use Tailscale, `<host-ip>` is your Tailscale IP
> (e.g. `100.64.x.x`). The Android device must also be in the same Tailscale network.

---

## 3. Configure the Android app

### Option A — In-app settings (recommended, no repackaging needed)

1. Build and install the debug APK:

   ```bash
   cd ufo-galaxy-android
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```

2. Open the app → tap the ⚙ icon → **Network & Diagnostics**.

3. Fill in the fields:

   | Field | Value |
   |-------|-------|
   | Host / IP | `<host-ip>` (your V2 host) |
   | Port | `8765` |
   | Use TLS | Off for dev/LAN; On for production |
   | Allow self-signed | On for dev/Tailscale; **Off for production** |
   | Device ID | Leave blank (system default) |

4. Tap **Save & Reconnect**.

### Option B — `assets/config.properties` (packaged default)

Edit before building:

```properties
# app/src/main/assets/config.properties
galaxy_gateway_url=ws://<host-ip>:8765
rest_base_url=http://<host-ip>:8765
cross_device_enabled=true
```

Rebuild and reinstall:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 4. Grant required permissions

The app requires three permissions before it can execute tasks autonomously. Grant them
in order:

### 4.1 Accessibility service

1. Go to **Settings → Accessibility → Installed apps** (wording varies by OEM).
2. Find **UFO Galaxy** → toggle ON.
3. Accept the permission prompt.

> The `HardwareKeyListener` accessibility service enables:
> - Physical key capture (double-press volume-down to wake)
> - Accessibility-based UI action execution (tap, scroll, type, open app)
> - Screenshot capture (API 30+ via `takeScreenshot`)

### 4.2 Display over other apps (SYSTEM\_ALERT\_WINDOW)

1. Go to **Settings → Apps → UFO Galaxy → Display over other apps**.
2. Toggle ON.

> Required for `EnhancedFloatingService` (the floating island overlay).

### 4.3 Notifications

The app will prompt for notification permission on first launch on Android 13+. Tap **Allow**.

---

## 5. Starting the system

After completing §2–§4 once, the normal start sequence is:

```bash
# Terminal 1 — start V2 backend
cd ufo-galaxy-realization
python main.py --host <host-ip> --port 8765

# Terminal 2 — (optional) watch Android logs
adb logcat -s GalaxyWebSocket:V RuntimeController:V GalaxyConnectionService:V
```

On Android: open the **UFO Galaxy** app. With `cross_device_enabled=true` the app will
connect automatically at startup.

---

## 6. Verifying the connection

### 6.1 Android side

In the app's main screen, the status indicator should show **Connected** (green).

In diagnostics (⚙ → Diagnostics):

- **Network OK:** ✓
- **Reconnect attempts:** 0
- **Attached session:** non-null

### 6.2 V2 side

```bash
# Runtime state projection — should show the Android device registered
curl http://<host-ip>:8765/api/v1/projection/runtime
```

Expected: the Android device's `device_id` appears in the participant list with
`attachment_state: attached`.

### 6.3 Android logcat markers

```
GalaxyWebSocket: [WS:CONNECT] onOpen — device registered
RuntimeController: state → Active
GalaxyConnectionService: attached session opened
```

---

## 7. Running a first end-to-end task

### Via the floating overlay

1. Swipe in from the right edge of the screen to open the floating island.
2. Type or speak a task goal, e.g. *"Open Settings and show Wi-Fi options"*.
3. Tap **Send**.
4. The app executes the task using the on-device pipeline and shows the result.

### Via V2 REST API

```bash
curl -X POST http://<host-ip>:8765/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "Open Settings and show Wi-Fi options", "device_id": "<your-device-id>"}'
```

Expected flow:

1. V2 dispatches a `task_assign` or `goal_execution` WebSocket message to the Android device.
2. Android executes the task (`EdgeExecutor` pipeline: screenshot → plan → ground → action).
3. Android sends `task_result` / `goal_result` back to V2.
4. V2 REST response returns the result.

---

## 8. Local-only mode (no V2 backend)

If you want to use the AI agent without a V2 backend, ensure `cross_device_enabled=false`
(the default) and the local inference server is running:

```bash
# Example: llama.cpp HTTP server for MobileVLM planning
./server -m /path/to/MobileVLM-1.7B.gguf --host 127.0.0.1 --port 8080
```

Then submit tasks via the in-app chat or floating overlay. Results appear in-app;
no WebSocket connection is made.

> **Note:** The local inference server is **not bundled** in the APK. You must start
> it separately on the device (or use a remote inference server via `rest_base_url`).

---

## 9. Troubleshooting

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| Status shows "Not connected" | `cross_device_enabled=false` or wrong gateway URL | Check app Settings → Network |
| Registration error: "Configuration error" | Gateway URL is still the placeholder `100.x.x.x` | Enter a real IP in Settings |
| Tasks fail with "model not ready" | Local inference server not running | Start llama.cpp server or use cross-device mode |
| Floating overlay does not appear | `SYSTEM_ALERT_WINDOW` permission not granted | Grant via Settings → Apps |
| Accessibility actions do nothing | Accessibility service not enabled | Enable in Settings → Accessibility |
| V2 shows device registered but tasks fail | Device `device_id` mismatch | Use the same `device_id` in both systems |
| `ClassNotFoundException: HardwareKeyReceiver` | (Historical concern — class IS present in `service/BootReceiver.kt`) | No action needed |

---

## 10. Configuration reference

Full configuration priority (high → low):

1. **SharedPreferences** (in-app settings, runtime override, survives reinstall until cleared)
2. **`assets/config.properties`** (packaged at build time, easy to override without code changes)
3. **`app/build.gradle` `BuildConfig` fields** (compile-time constants)

Key configuration keys:

| Key | Default | Description |
|-----|---------|-------------|
| `cross_device_enabled` | `false` | Master toggle for cross-device mode |
| `galaxy_gateway_url` | `ws://100.x.x.x:8765` | WebSocket endpoint of the V2 gateway |
| `rest_base_url` | `http://100.x.x.x:8765` | REST endpoint of the V2 gateway |
| `planner_max_tokens` | `512` | MobileVLM max tokens per inference call |
| `planner_timeout_ms` | `30000` | MobileVLM HTTP timeout (ms) |
| `grounding_timeout_ms` | `15000` | SeeClick HTTP timeout (ms) |
| `scaled_max_edge` | `720` | Grounding image max edge (px); 0 = no scaling |

---

## Related documentation

| Document | What it covers |
|----------|----------------|
| [`docs/architecture.md`](architecture.md) | System overview, component index |
| [`docs/execution-flows.md`](execution-flows.md) | Local and cross-device execution flow maps |
| [`docs/maintainer-guide.md`](maintainer-guide.md) | Onboarding, canonical API, configuration |
| [`docs/OBSERVABILITY.md`](OBSERVABILITY.md) | Logging, metrics, tracing |
| [`docs/DEPLOYMENT_GUIDE.md`](DEPLOYMENT_GUIDE.md) | Deployment reference |
| [`docs/SYSTEM_ANALYSIS_ZH.md`](SYSTEM_ANALYSIS_ZH.md) | Full Chinese-language system analysis |
