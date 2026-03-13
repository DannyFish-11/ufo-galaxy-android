# Android ↔ UFO Galaxy realization-v2 Integration Guide

This document describes the **complete integration** between the Android client
(`ufo-galaxy-android`) and the server-side **AndroidBridge**
(`galaxy_gateway/android_bridge.py` in `ufo-galaxy-realization-v2`).

---

## 1. WebSocket Connection

### Primary Route

| Key | Value |
|-----|-------|
| Path template | `/ws/android/{device_id}` |
| `ServerConfig` constant | `ServerConfig.ANDROID_BRIDGE_WS_PATH` |
| `WS_PATHS` index | **0** (highest priority) |
| Port | `8765` (default, override via `config.properties`) |

The AndroidBridge exposes each device on a **per-device WebSocket endpoint**.
The `{device_id}` segment must be the same ID sent in the `device_register`
message payload.

### Connection Procedure

```
1. Client opens WS to  ws://<host>:8765/ws/android/<device_id>
2. On open → send  device_register  message
3. On open → send  capability_report  message  (immediately after registration)
4. On open → start heartbeat loop (every 30 s)
5. On register ACK → device is active in CapabilityRegistry
6. Server may now send  task_assign  messages to the device
7. Client executes task → sends  command_result  back
```

### Fallback Paths

`ServerConfig.WS_PATHS` lists candidate paths in priority order.  Clients
**automatically advance** to the next entry on connection failure:

| Index | Path | Notes |
|-------|------|-------|
| 0 | `/ws/android/{id}` | **Primary** – AndroidBridge route |
| 1 | `/ws/device/{id}` | Device-specific fallback |
| 2 | `/ws/android` | Generic Android fallback |
| 3 | `/ws/ufo3/{id}` | Legacy UFO³ path |

---

## 2. AIP v3 Message Types

All outbound messages must use the **v3 type names** defined in
`AIPMessageBuilder.MessageType`.  Legacy type strings are **deprecated** – see
the mapping table below.

### Authoritative v3 Types

| `AIPMessageBuilder.MessageType` constant | Wire `type` value | Direction | Description |
|---|---|---|---|
| `DEVICE_REGISTER` | `device_register` | client → server | Initial device registration |
| `HEARTBEAT` | `heartbeat` | client → server | 30-second keep-alive |
| `CAPABILITY_REPORT` | `capability_report` | client → server | Post-registration capability list |
| `TASK_ASSIGN` | `task_assign` | server → client | Server assigns a task |
| `COMMAND_RESULT` | `command_result` | client → server | Result of executed task/command |

### Legacy → v3 Mapping (`AIPMessageBuilder.LEGACY_TYPE_MAP`)

| Legacy string | v3 equivalent |
|---|---|
| `registration` | `device_register` |
| `register` | `device_register` |
| `heartbeat` | `heartbeat` *(unchanged)* |
| `command` | `task_assign` |
| `command_result` | `command_result` *(unchanged)* |

Use `AIPMessageBuilder.toV3Type(legacyString)` for programmatic conversion.

---

## 3. Message Formats

All outbound messages are built via `AIPMessageBuilder.build()`.  The resulting
JSON always contains:

```json
{
  "protocol": "AIP/1.0",
  "version":  "3.0",
  "type":     "<v3 type from AIPMessageBuilder.MessageType>",
  "source_node": "android_<device_id>",
  "target_node": "server",
  "device_id":   "android_<device_id>",
  "device_type": "Android_Agent",
  "message_id":  "<8-char UUID>",
  "timestamp":   1700000000,
  "payload":     { ... }
}
```

### 3.1 `device_register`

```json
{
  "type": "device_register",
  "payload": {
    "device_id":   "android_abc12345",
    "device_name": "Samsung Galaxy S21",
    "device_type": "android",
    "manufacturer": "Samsung",
    "model":        "Galaxy S21",
    "os_version":   "Android 13",
    "sdk_version":  33,
    "app_version":  "2.5.0",
    "capabilities": ["app_control", "camera", "screen_capture", "touch", "ui_automation", "..."],
    "groups":       ["mobile", "android"],
    "tags":         ["android", "mobile", "auto-registered"],
    "metadata": {
      "board": "...", "hardware": "...", "product": "...", "fingerprint": "..."
    }
  }
}
```

### 3.2 `capability_report`

Must be sent **immediately after** registration (before the server sends any
`task_assign`).  The server's `CapabilityRegistry` uses this to expose the
device's tools to the LLM scheduler.

```json
{
  "type": "capability_report",
  "payload": {
    "platform":          "android",
    "supported_actions": [
      "app_control", "camera", "gesture_simulation", "keyboard",
      "natural_language", "screen", "screen_capture", "system_control",
      "text_input", "touch", "ui_automation"
    ],
    "version": "2.5.0"
  }
}
```

### 3.3 `heartbeat`

```json
{
  "type": "heartbeat",
  "payload": {
    "status": "online",
    "capabilities_count": 11
  }
}
```

### 3.4 `command_result`

```json
{
  "type": "command_result",
  "payload": {
    "command": "tap",
    "status":  "success",
    "details": "Tapped element at (100, 200)"
  }
}
```

---

## 4. Registration + Capability Flow

```
Android Client                        Server (AndroidBridge)
─────────────────────────────────────────────────────────────
WS connect /ws/android/<id>
    ────────────────────────────────►
                                      Accept connection
    ◄────────────────────────────────

device_register { payload: DeviceInfo }
    ────────────────────────────────►
                                      Register in DeviceRegistry
                                      Return ACK { action: "device_register" }
    ◄────────────────────────────────

capability_report { payload: { platform, supported_actions, version } }
    ────────────────────────────────►
                                      Sync to CapabilityRegistry
                                      Device now visible to NL scheduler

heartbeat (every 30 s)
    ────────────────────────────────►
                                      ack / heartbeat_ack
    ◄────────────────────────────────

task_assign { payload: { action, params } }        ← via NL command
    ◄────────────────────────────────

command_result { payload: { status, details } }
    ────────────────────────────────►
```

---

## 5. Natural-Language Command Flow

```
User → /api/v1/chat (NL input)
      ↓
  LLM Planner
      ↓
  CapabilityRegistry → finds "android_<id>" device tool
      ↓
  CommandRouter / AndroidBridge
      ↓
  WebSocket → task_assign → Android Client
      ↓
  Android executes task
      ↓
  command_result → WebSocket → AndroidBridge → response to caller
```

---

## 6. Configuration

### `app/src/main/assets/config.properties`

```properties
# WebSocket base (host + port only, no path)
# WS, REST and WebRTC all run in the same Galaxy Gateway process on port 8765.
galaxy.gateway.url=ws://100.x.x.x:8765

# HTTP base for REST endpoints (same port as WebSocket)
rest.base.url=http://100.x.x.x:8765
```

### Key Constants

| Constant | Location | Value |
|---|---|---|
| `ANDROID_BRIDGE_WS_PATH` | `ServerConfig` | `/ws/android/{id}` |
| `DEFAULT_BASE_URL` | `ServerConfig` | `ws://100.123.215.126:8765` |
| `MessageType.DEVICE_REGISTER` | `AIPMessageBuilder.MessageType` | `device_register` |
| `MessageType.CAPABILITY_REPORT` | `AIPMessageBuilder.MessageType` | `capability_report` |
| `MessageType.HEARTBEAT` | `AIPMessageBuilder.MessageType` | `heartbeat` |
| `MessageType.TASK_ASSIGN` | `AIPMessageBuilder.MessageType` | `task_assign` |
| `MessageType.COMMAND_RESULT` | `AIPMessageBuilder.MessageType` | `command_result` |

---

## 7. Validating Registration / Command Flow

### Step 1 – Verify connection

Check the server logs for:
```
AndroidBridge: device android_<id> connected
```

### Step 2 – Verify `device_register`

Server log:
```
DeviceRegistry: registered android_<id> with capabilities [...]
```

### Step 3 – Verify `capability_report`

Server log:
```
CapabilityRegistry: synced android_<id> supported_actions [...]
```

### Step 4 – Verify NL dispatch

Send a natural-language command via `POST /api/v1/chat`:
```json
{ "message": "打开抖音" }
```

Server should route the command to the Android device and return the result.

### Step 5 – Heartbeat stability

Confirm the 30-second heartbeat survives a reconnect:
1. Kill the WebSocket connection.
2. Verify the client reconnects automatically (up to 5 attempts with back-off).
3. Verify `device_register` + `capability_report` are resent on reconnect.

---

## 8. Architecture Reference

```
GalaxyClient  (unified entry point)
    └── DeviceCommunication  (WS management + heartbeat + inbound normalisation)
            ├── AIPMessageBuilder  (message build / parse / normalise)
            └── ServerConfig  (URL path management)

AIPClient / EnhancedAIPClient  (compatibility / fallback)
    └── AIPMessageBuilder
    └── ServerConfig

Node50Client  (legacy Node 50 connection)
    └── AIPMessageBuilder
    └── ServerConfig
    └── AIPProtocol  (constants + device-info utilities; deprecated for msg construction)
```

Prefer `GalaxyClient + DeviceCommunication` for new integrations.
`AIPClient`, `EnhancedAIPClient`, and `Node50Client` are retained for
compatibility with existing deployments.

> **`AIPProtocol`** is deprecated for message construction – use
> `AIPMessageBuilder.build()` exclusively for all outbound messages.
