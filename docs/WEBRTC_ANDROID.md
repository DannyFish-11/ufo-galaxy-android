# WebRTC Android Integration

This document describes the WebRTC screen-streaming integration in the UFO Galaxy Android client, including required permissions, the MediaProjection flow, the gateway signaling endpoint, and configuration keys.

---

## Overview

The integration enables the Android device to **stream its screen** to a remote viewer (Node_95 or any WebRTC-capable client) via the Galaxy Gateway signaling proxy introduced in Server PR #35.

```
Android device
  └─ ScreenCaptureService  (MediaProjection + MediaCodec H.264)
  └─ WebRTCManager         (lifecycle, signaling orchestration)
       └─ WebRTCSignalingClient  ──WS──▶  Galaxy Gateway  ──▶  Remote Peer
```

### Components

| Class | Package | Purpose |
|-------|---------|---------|
| `WebRTCManager` | `com.ufo.galaxy.webrtc` | Lifecycle, endpoint discovery, SDP offer/answer |
| `WebRTCSignalingClient` | `com.ufo.galaxy.webrtc` | OkHttp WebSocket to `/ws/webrtc/{device_id}` |
| `SignalingMessage` | `com.ufo.galaxy.webrtc` | JSON serialization for offer / answer / ICE |
| `ScreenCaptureService` | `com.ufo.galaxy.webrtc` | Foreground service: MediaProjection + H.264 |
| `ServerConfig` | `com.ufo.galaxy.config` | URL builders for WS and REST endpoints |
| `TaskExecutor` | `com.ufo.galaxy.task` | Task dispatch: `screen_stream_start` / `screen_stream_stop` |

---

## Required Permissions

Add the following to `AndroidManifest.xml` (already included):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
```

The `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission is required by Android 14+ (API 34) for foreground services that use `mediaProjection` as the `foregroundServiceType`.

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
4. WebRTCManager starts ScreenCaptureService (foreground) with the result
5. ScreenCaptureService creates MediaProjection → VirtualDisplay → MediaCodec surface
6. MediaCodec encodes frames as H.264 (baseline, 1280×720 @ 30 fps, 2 Mbps)
7. Encoded frames are available via sendEncodedData() callback
   (wired to WebRTC when org.webrtc is integrated; see Limitations)
8. WebRTCManager connects WebRTCSignalingClient to the gateway
9. SDP offer is sent once the WebSocket is open
10. Gateway relays offer/answer/ICE between peers
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
{ "ws_url": "ws://host:8050/ws/webrtc/android_abc123" }
```

`WebRTCManager` queries this endpoint on `startScreenSharing()` and falls back to the default path when the endpoint is unreachable or returns a non-2xx response.

### Signaling message format

All messages are JSON objects with a `type` field:

```json
// Offer (Android → Gateway → Peer)
{
  "type": "offer",
  "sdp": "<SDP string>",
  "device_id": "android_abc123"
}

// Answer (Peer → Gateway → Android)
{
  "type": "answer",
  "sdp": "<SDP string>",
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
| `DEFAULT_BASE_URL` | `ServerConfig` | Default WS base, e.g. `ws://100.123.215.126:8050` |
| `WEBRTC_WS_PATH` | `ServerConfig` | `/ws/webrtc/{id}` – signaling WS path template |
| `WEBRTC_ENDPOINT_REST_PATH` | `ServerConfig` | `/api/v1/webrtc/endpoint` – discovery REST path |

Override `DEFAULT_BASE_URL` via `app/src/main/assets/config.properties`:
```properties
gateway.base_url=ws://your-gateway-host:8050
```

---

## Task Executor Integration

Two new task types are handled by `com.ufo.galaxy.task.TaskExecutor`:

### `screen_stream_start`

Start a WebRTC streaming session.

**Payload:**
```json
{
  "result_code": 42,
  "gateway_url": "ws://host:8050",
  "device_id": "android_abc123"
}
```

> **Note:** When invoked via `TaskExecutor.executeTask()` the `result_code` is required. When invoked from an Activity result handler, use the convenience method `TaskExecutor.startScreenStream(taskId, resultCode, projectionData, gatewayWsBase, deviceId)` to pass the actual `Intent` object.

### `screen_stream_stop`

Stop the current WebRTC streaming session.

**Payload:** `{}` (empty)

---

## Limitations & Future Work

### Native WebRTC library not bundled

A full peer-to-peer WebRTC media pipeline (PeerConnectionFactory, VideoTrack, RTP sender) requires the `org.webrtc` native library (e.g., `io.github.webrtc-sdk:android`). Adding this library (~20 MB AAR) was out of scope for this PR.

**What works today:**
- Full signaling infrastructure (WS connect, offer/answer/ICE JSON exchange).
- Screen capture with H.264 encoding via `ScreenCaptureService`.
- Gateway endpoint discovery with fallback.
- `screen_stream_start` / `screen_stream_stop` task types.

**To complete the media pipeline (future PR):**
1. Add `implementation 'io.github.webrtc-sdk:android:<version>'` to `app/build.gradle`.
2. In `WebRTCManager.initialize()`:
   - Call `PeerConnectionFactory.initialize()`.
   - Create a `PeerConnectionFactory` with default options.
   - Create a `PeerConnection` with STUN/TURN ICE servers from the gateway.
3. In `ScreenCaptureService`, pass the encoder `Surface` (currently `encoderSurface`) to a `VideoSource` created by the factory.
4. Replace the minimal SDP strings in `buildMinimalSdpOffer()` / `buildMinimalSdpAnswer()` with calls to `peerConnection.createOffer()` / `createAnswer()`.
5. Implement `peerConnection.addIceCandidate()` in `handleIceCandidate()`.

### STUN / TURN

No ICE servers are configured in the current placeholder SDP. Add them to `PeerConnection.RTCConfiguration` once the native library is available.

---

## Testing

Unit tests (no device required):

```bash
./gradlew :app:test
```

Tests added:
- `ServerConfigTest` — `buildWebRtcWsUrl` and `buildWebRtcEndpointUrl` helpers.
- `SignalingMessageTest` — JSON round-trip for offer, answer, and ice_candidate messages.
