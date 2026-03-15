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
| Port | `8050` (default, override via `config.properties`) |

The AndroidBridge exposes each device on a **per-device WebSocket endpoint**.
The `{device_id}` segment must be the same ID sent in the `device_register`
message payload.

### Connection Procedure

```
1. Client opens WS to  ws://<host>:8050/ws/android/<device_id>
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
  "protocol":    "AIP/1.0",
  "version":     "3.0",
  "type":        "<v3 type from AIPMessageBuilder.MessageType>",
  "source_node": "android_<device_id>",
  "target_node": "server",
  "device_id":   "android_<device_id>",
  "device_type": "Android_Agent",
  "message_id":  "<8-char UUID>",
  "timestamp":   1700000000,
  "trace_id":    "<UUID generated once per WS session, reused for all messages>",
  "route_mode":  "local" | "cross_device",
  "payload":     { ... }
}
```

> **Required fields (AIP v3):** `protocol`, `version`, `trace_id`, and
> `route_mode` are mandatory in every outbound message.
>
> - `trace_id` is generated via `AIPMessageBuilder.generateTraceId()` **once**
>   when a new WS connection opens and reused for every subsequent message in
>   that session.  Callers (e.g. `WebRTCManager`) can retrieve the current
>   session trace ID from `GalaxyWebSocketClient.getTraceId()`.
>
> - `route_mode` is derived from the cross-device switch:
>   - Switch **OFF** → `"local"` (`AIPMessageBuilder.ROUTE_MODE_LOCAL`)
>   - Switch **ON**  → `"cross_device"` (`AIPMessageBuilder.ROUTE_MODE_CROSS_DEVICE`)
>
> WebRTC signaling frames (offer / answer / ice_candidate) also carry
> `protocol`, `version`, `trace_id`, and `route_mode` in their JSON envelope
> so that gateway and remote peers can correlate them with the main AIP session.

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

When **cross-device mode is ON** the payload is extended with a
`capability_schema` array.  Each entry carries a structured schema so that
the server routing layer can decide whether to dispatch execution locally or
remotely for each capability.  The legacy `supported_actions` list is always
present for backward compatibility with older server deployments.

#### `capability_schema` field semantics

| Field | Type | Required | Description |
|---|---|---|---|
| `action` | string | ✓ | Capability name matching an entry in `supported_actions` |
| `params` | object | ✓ | JSON Schema describing accepted input parameters |
| `returns` | object | ✓ | JSON Schema (or `description` string) for the return value |
| `version` | string | ✓ | Semantic version of the capability interface |
| `exec_mode` | string | ✓ | `"local"` · `"remote"` · `"both"` (see below) |
| `tags` | array | — | Optional device/OS constraint hints (e.g. `"android"`, `"hardware"`) |

#### `exec_mode` semantics

| Value | Meaning |
|---|---|
| `"local"` | The capability runs exclusively on the local Android device (e.g. `touch`, `screen_capture`, `camera`). |
| `"remote"` | The capability requires routing to a remote server for execution. |
| `"both"` | The capability can run either on-device **or** remotely (e.g. `natural_language`, which may be served by a local MobileVlmPlanner or a remote LLM). |

`exec_mode` is determined by `DeviceRegistry.buildCapabilitySchema()` in the
companion object.  The mapping is defined once in source and can be extended
without an app restart by calling `DeviceRegistry.rebuildCapabilities()` after
a runtime permission change.

#### Payload example (cross-device ON)

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
    "version": "2.5.0",
    "cross_device_enabled": true,
    "capability_schema": [
      {
        "action":    "screen_capture",
        "params":    { "type": "object", "properties": {} },
        "returns":   { "type": "string", "description": "Base64-encoded screenshot PNG" },
        "version":   "1.0",
        "exec_mode": "local",
        "tags":      ["android", "ui"]
      },
      {
        "action":    "natural_language",
        "params":    { "type": "object", "properties": { "text": { "type": "string" } }, "required": ["text"] },
        "returns":   { "type": "object", "description": "Parsed command or LLM response" },
        "version":   "1.0",
        "exec_mode": "both",
        "tags":      ["android", "nlp"]
      }
    ]
  }
}
```

> **Note:** `capability_schema` is **only** sent when cross-device mode is ON.
> When cross-device mode is OFF the WebSocket is not connected at all, so no
> `capability_report` is sent.

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

capability_report { payload: { platform, supported_actions, version, capability_schema[] } }
    ────────────────────────────────►
                                      Sync to CapabilityRegistry (exec_mode per capability)
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
gateway.base_url=ws://100.x.x.x:8050

# HTTP base for REST endpoints
rest.base.url=http://100.x.x.x:8050
```

### Key Constants

| Constant | Location | Value |
|---|---|---|
| `ANDROID_BRIDGE_WS_PATH` | `ServerConfig` | `/ws/android/{id}` |
| `DEFAULT_BASE_URL` | `ServerConfig` | `ws://100.123.215.126:8050` |
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
