# WebRTC Android Integration

This document describes the WebRTC screen-streaming integration in the UFO Galaxy Android client, including required permissions, the MediaProjection flow, the gateway signaling endpoint, and configuration keys.

---

## Overview

The integration enables the Android device to **stream its screen** to a remote viewer (Node_95 or any WebRTC-capable client) via the Galaxy Gateway signaling proxy.

```
Android device
  └─ ScreenCaptureService  (foreground service – satisfies Android 14+ MediaProjection requirement)
  └─ WebRTCManager         (PeerConnectionFactory, PeerConnection, VideoSource, ScreenCapturerAndroid)
       └─ WebRTCSignalingClient  ──WS──▶  Galaxy Gateway  ──▶  Remote Peer
```

### Components

| Class | Package | Purpose |
|-------|---------|---------|
| `WebRTCManager` | `com.ufo.galaxy.webrtc` | PeerConnectionFactory init, PeerConnection, VideoSource/Track, ScreenCapturerAndroid, SDP offer/answer, ICE handling |
| `WebRTCSignalingClient` | `com.ufo.galaxy.webrtc` | OkHttp WebSocket to `/ws/webrtc/{device_id}` |
| `SignalingMessage` | `com.ufo.galaxy.webrtc` | JSON serialization for offer / answer / ICE |
| `ScreenCaptureService` | `com.ufo.galaxy.webrtc` | Foreground service: holds the `mediaProjection` foreground-service type required on Android 14+ |
| `ServerConfig` | `com.ufo.galaxy.config` | URL builders for WS and REST endpoints; `DEFAULT_STUN_URL` |
| `TaskExecutor` | `com.ufo.galaxy.task` | Task dispatch: `screen_stream_start` / `screen_stream_stop` |

---

## Required Dependencies

Add to `app/build.gradle.kts`:

```kotlin
// WebRTC — community-maintained build of libwebrtc hosted on mavenCentral
implementation("io.github.webrtc-sdk:android:104.5112.07")
```

No additional Maven repository configuration is needed; `mavenCentral()` is already declared in the root `build.gradle`.

---

## Required Permissions

Add the following to `AndroidManifest.xml` (already included):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
```

The `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission is required by Android 14+ (API 34) for foreground services that use `mediaProjection` as the `foregroundServiceType`.

Declare the service in `AndroidManifest.xml` with the correct `foregroundServiceType`:

```xml
<service
    android:name=".webrtc.ScreenCaptureService"
    android:foregroundServiceType="mediaProjection"
    android:exported="false" />
```

### Runtime Permission

Before starting screen capture the app must obtain the user's consent via `MediaProjectionManager`:

```kotlin
val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
```

Pass the resulting `resultCode` and `data` Intent to `WebRTCManager.startScreenSharing()`.

---

## MediaProjection Flow

```
1. Activity calls startActivityForResult(manager.createScreenCaptureIntent(), …)
2. User approves → onActivityResult(resultCode, data)
3. Activity calls WebRTCManager.startScreenSharing(resultCode, data)
4. WebRTCManager initialises PeerConnectionFactory + EglBase + VideoSource + VideoTrack
5. WebRTCManager creates PeerConnection with STUN ICE server from ServerConfig.DEFAULT_STUN_URL
6. WebRTCManager starts ScreenCaptureService (foreground) with resultCode + data
7. ScreenCaptureService calls startForeground() then WebRTCManager.onCaptureServiceReady(data)
8. WebRTCManager creates ScreenCapturerAndroid(data, …), which internally calls
   MediaProjectionManager.getMediaProjection() and creates a VirtualDisplay
9. ScreenCapturerAndroid feeds video frames into the VideoSource → VideoTrack → PeerConnection
10. WebRTCManager connects WebRTCSignalingClient to the gateway
11. On WebSocket open, PeerConnection.createOffer() is called; the real SDP is sent via SignalingClient
12. Gateway relays offer/answer/ICE between peers
13. Incoming answer → PeerConnection.setRemoteDescription()
14. Incoming ICE candidates → PeerConnection.addIceCandidate()
15. Local ICE candidates (from PeerConnection.Observer.onIceCandidate) → sent via SignalingClient
```

---

## Gateway Signaling Endpoint

### WebSocket path

```
ws://<gateway_host>:<port>/ws/webrtc/{device_id}
```

Use `ServerConfig.buildWebRtcWsUrl(baseUrl, deviceId)` to construct this URL.

### Endpoint discovery (optional)

The gateway exposes a REST endpoint to return the active signaling WS URL:

```
GET /api/v1/webrtc/endpoint
```

Use `ServerConfig.buildWebRtcEndpointUrl(httpBase)` to construct this URL.

**Response JSON** (when available):
```json
{ "ws_url": "ws://host:8765/ws/webrtc/android_abc123" }
```

`WebRTCManager` queries this endpoint on `startScreenSharing()` and falls back to the default path when the endpoint is unreachable or returns a non-2xx response.

### Signaling message format

All messages are JSON objects with a `type` field:

```json
// Offer (Android → Gateway → Peer)
{
  "type": "offer",
  "sdp": "<real SDP from PeerConnection.createOffer()>",
  "device_id": "android_abc123"
}

// Answer (Peer → Gateway → Android)
{
  "type": "answer",
  "sdp": "<real SDP from remote peer>",
  "device_id": "android_abc123"
}

// ICE candidate (bidirectional)
{
  "type": "ice_candidate",
  "candidate": {
    "candidate": "candidate:1 1 UDP 2130706431 192.168.1.5 54321 typ host",
    "sdpMid": "0",
    "sdpMLineIndex": 0
  },
  "device_id": "android_abc123"
}
```

---

## Configuration Keys

| Key | Location | Description |
|-----|----------|-------------|
| `DEFAULT_BASE_URL` | `ServerConfig` | Default WS base, e.g. `ws://100.123.215.126:8765` |
| `DEFAULT_STUN_URL` | `ServerConfig` | Default STUN server, e.g. `stun:stun.l.google.com:19302` |
| `WEBRTC_WS_PATH` | `ServerConfig` | `/ws/webrtc/{id}` – signaling WS path template |
| `WEBRTC_ENDPOINT_REST_PATH` | `ServerConfig` | `/api/v1/webrtc/endpoint` – discovery REST path |

Override `DEFAULT_BASE_URL` via `app/src/main/assets/config.properties`:
```properties
# WS, REST and WebRTC all run on the same port (8765) in the same Galaxy Gateway process.
galaxy.gateway.url=ws://your-gateway-host:8765
```

---

## Task Executor Integration

Two task types are handled by `com.ufo.galaxy.task.TaskExecutor`:

### `screen_stream_start`

Start a WebRTC streaming session.

**Payload:**
```json
{
  "result_code": 42,
  "gateway_url": "ws://host:8765",
  "device_id": "android_abc123"
}
```

> **Note:** When invoked via `TaskExecutor.executeTask()` the `result_code` is required. When invoked from an Activity result handler, use the convenience method `TaskExecutor.startScreenStream(taskId, resultCode, projectionData, gatewayWsBase, deviceId)` to pass the actual `Intent` object.

### `screen_stream_stop`

Stop the current WebRTC streaming session.

**Payload:** `{}` (empty)

---

## Testing

Unit tests (no device required):

```bash
./gradlew :app:test
```

Tests included:
- `ServerConfigTest` — `buildWebRtcWsUrl`, `buildWebRtcEndpointUrl`, and `DEFAULT_STUN_URL` helpers.
- `SignalingMessageTest` — JSON round-trip for offer, answer, and ice_candidate messages.

