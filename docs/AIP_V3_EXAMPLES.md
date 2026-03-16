# AIP v3 示例载荷文档

本文档提供 Android 客户端所有核心 AIP v3 消息类型的完整示例载荷。示例中的每个字段均与
`AipModels.kt`（`MsgType`、`AipMessage`、各 payload 数据类）和 `AIPMessageBuilder` 的行为保持一致。

> **活文档提示**：`ExamplePayloadsTest` 和 `MicrosoftMappingExampleTest` 测试类通过可运行断言
> 对本文档中的关键字段进行验证，请在修改任何 payload 结构时同步更新上述测试。

---

## 目录

1. [v3 信封通用字段](#v3-信封通用字段)
2. [device_register（设备注册）](#device_register设备注册)
3. [heartbeat（心跳）](#heartbeat心跳)
4. [capability_report（能力上报）](#capability_report能力上报)
5. [task_assign（任务下发）](#task_assign任务下发)
6. [command_result（命令结果）](#command_result命令结果)
7. [task_submit（用户发起任务）](#task_submit用户发起任务)
8. [Microsoft 兼容映射示例](#microsoft-兼容映射示例)
9. [v2 → v3 类型映射表](#v2--v3-类型映射表)

---

## v3 信封通用字段

`AIPMessageBuilder.build()` 为每条出站消息生成以下顶层字段：

| 字段          | 类型     | 固定值 / 说明                                  |
|---------------|----------|------------------------------------------------|
| `protocol`    | String   | 固定 `"AIP/1.0"`                               |
| `version`     | String   | 固定 `"3.0"`                                   |
| `type`        | String   | v3 消息类型名（见 `AIPMessageBuilder.MessageType` / `MsgType`） |
| `source_node` | String   | 发送方设备 ID                                  |
| `target_node` | String   | 目标节点（通常为 `"Galaxy"`）                  |
| `timestamp`   | Long     | Unix 时间戳（秒）                              |
| `message_id`  | String   | 8 位随机标识符                                 |
| `device_id`   | String   | 与 `source_node` 相同                          |
| `device_type` | String   | 固定 `"Android_Agent"`                         |
| `payload`     | Object   | 消息体（详见各小节）                           |

---

## device_register（设备注册）

**MsgType**：`MsgType.DEVICE_REGISTER` → `"device_register"`  
**方向**：上行（Android → Gateway）  
**时机**：WebSocket 连接建立后立即发送

```json
{
  "protocol":    "AIP/1.0",
  "version":     "3.0",
  "type":        "device_register",
  "source_node": "android_pixel8_01",
  "target_node": "Galaxy",
  "timestamp":   1710000000,
  "message_id":  "a1b2c3d4",
  "device_id":   "android_pixel8_01",
  "device_type": "Android_Agent",
  "payload": {
    "platform":   "android",
    "os_version": "14",
    "hardware": {
      "manufacturer": "Google",
      "model":        "Pixel 8",
      "device":       "husky"
    },
    "tools": [
      "location", "camera", "sensor_data", "automation",
      "notification", "sms", "phone_call", "contacts",
      "calendar", "voice_input", "screen_capture", "app_control"
    ],
    "capabilities": {
      "nlu":              false,
      "hardware_control": true,
      "sensor_access":    true,
      "network_access":   true,
      "ui_automation":    true
    }
  }
}
```

**payload 说明**

| 字段           | 必填 | 说明                      |
|----------------|------|---------------------------|
| `platform`     | 是   | 固定 `"android"`          |
| `os_version`   | 否   | `Build.VERSION.RELEASE`   |
| `hardware`     | 否   | 设备硬件信息              |
| `tools`        | 是   | 支持的能力列表（字符串数组）|
| `capabilities` | 否   | 布尔型能力标志对象        |

---

## heartbeat（心跳）

**MsgType**：`MsgType.HEARTBEAT` → `"heartbeat"`  
**方向**：上行（Android → Gateway）  
**时机**：每 30 秒发送一次

```json
{
  "protocol":    "AIP/1.0",
  "version":     "3.0",
  "type":        "heartbeat",
  "source_node": "android_pixel8_01",
  "target_node": "Galaxy",
  "timestamp":   1710000030,
  "message_id":  "b2c3d4e5",
  "device_id":   "android_pixel8_01",
  "device_type": "Android_Agent",
  "payload": {
    "status": "online"
  }
}
```

**payload 说明**

| 字段     | 必填 | 说明                   |
|----------|------|------------------------|
| `status` | 是   | 固定 `"online"`        |

---

## capability_report（能力上报）

**MsgType**：`MsgType.CAPABILITY_REPORT` → `"capability_report"`  
**方向**：上行（Android → Gateway）  
**时机**：`device_register` 发送成功且收到注册确认后立即发送

> **PR-C1 约束**：`payload` 中 `platform`、`supported_actions`、`version` 三个字段为**必填**，
> 缺失时 `EnhancedAIPClient` 将拒绝发送并记录错误日志。

```json
{
  "protocol":    "AIP/1.0",
  "version":     "3.0",
  "type":        "capability_report",
  "source_node": "android_pixel8_01",
  "target_node": "Galaxy",
  "timestamp":   1710000001,
  "message_id":  "c3d4e5f6",
  "device_id":   "android_pixel8_01",
  "device_type": "Android_Agent",
  "payload": {
    "platform": "android",
    "supported_actions": [
      "location", "camera", "sensor_data", "automation",
      "notification", "sms", "phone_call", "contacts",
      "calendar", "voice_input", "screen_capture", "app_control"
    ],
    "version": "3.0.0"
  }
}
```

**payload 说明**

| 字段                | 必填 | 说明                                              |
|---------------------|------|---------------------------------------------------|
| `platform`          | 是   | 固定 `"android"`                                  |
| `supported_actions` | 是   | 支持的动作字符串数组，不可为空                    |
| `version`           | 是   | 能力版本号（建议与 APK 版本对齐）                 |

---

## task_assign（任务下发）

**MsgType**：`MsgType.TASK_ASSIGN` → `"task_assign"`  
**方向**：下行（Gateway → Android）  
**时机**：服务端收到用户指令后下发

```json
{
  "protocol":    "AIP/1.0",
  "version":     "3.0",
  "type":        "task_assign",
  "source_node": "Galaxy",
  "target_node": "android_pixel8_01",
  "timestamp":   1710000060,
  "message_id":  "d4e5f6a7",
  "payload": {
    "task_id":            "task-uuid-001",
    "goal":               "打开微信并发送「你好」",
    "constraints":        ["不得访问联系人列表"],
    "max_steps":          10,
    "require_local_agent": true
  }
}
```

**payload 说明**（对应 `TaskAssignPayload`）

| 字段                  | 必填 | 说明                                      |
|-----------------------|------|-------------------------------------------|
| `task_id`             | 是   | 任务唯一标识；在 `task_result` 中回传     |
| `goal`                | 是   | 自然语言目标描述                          |
| `constraints`         | 否   | 自然语言约束列表                          |
| `max_steps`           | 是   | 最大执行步数                              |
| `require_local_agent` | 是   | `true` 表示必须在本设备执行               |

---

## command_result（命令结果）

**MsgType**：`MsgType.COMMAND_RESULT` → `"command_result"`  
**方向**：上行（Android → Gateway）  
**时机**：每个执行步骤完成后上报

```json
{
  "protocol":    "AIP/1.0",
  "version":     "3.0",
  "type":        "command_result",
  "source_node": "android_pixel8_01",
  "target_node": "Galaxy",
  "timestamp":   1710000062,
  "message_id":  "e5f6a7b8",
  "device_id":   "android_pixel8_01",
  "device_type": "Android_Agent",
  "payload": {
    "task_id": "task-uuid-001",
    "step_id": "1",
    "action":  "tap",
    "status":  "success"
  }
}
```

**payload 说明**（对应 `CommandResultPayload`）

| 字段      | 必填 | 说明                                         |
|-----------|------|----------------------------------------------|
| `task_id` | 是   | 对应 `TaskAssignPayload.task_id`             |
| `step_id` | 是   | 1-based 步骤序号（字符串）                   |
| `action`  | 是   | 执行的动作名称（如 `"tap"`、`"scroll"`）     |
| `status`  | 是   | `"success"` 或 `"error"`                     |
| `error`   | 否   | 错误描述（`status="error"` 时填写）          |
| `snapshot`| 否   | Base64 编码的步骤截图（`Snapshot` 数据类）   |

---

## task_submit（用户发起任务）

**MsgType**：`MsgType.TASK_SUBMIT` → `"task_submit"`  
**方向**：上行（Android → Gateway）  
**时机**：用户通过 UI 或语音输入提交任务时

> **PR-C4 约束**：`payload` 中 `task_text`、`device_id`、`session_id` 为**必填**，
> `task_id` 应设置为与 `AipMessage.correlation_id` 相同的值以便网关关联请求与回复。
> 发送前必须调用 `TaskSubmitPayload.validate()`；校验失败应通过 `onError` 回调通知调用方。

```json
{
  "protocol":    "AIP/1.0",
  "version":     "3.0",
  "type":        "task_submit",
  "source_node": "android_pixel8_01",
  "target_node": "Galaxy",
  "timestamp":   1710000055,
  "message_id":  "f6a7b8c9",
  "device_id":   "android_pixel8_01",
  "device_type": "Android_Agent",
  "payload": {
    "task_text":  "帮我打开导航去最近的星巴克",
    "device_id":  "android_pixel8_01",
    "session_id": "sess-20260316-001",
    "task_id":    "task-uuid-055",
    "context": {
      "locale":         "zh-CN",
      "app_foreground": "com.android.launcher3"
    }
  }
}
```

**payload 说明**（对应 `TaskSubmitPayload` + `TaskSubmitContext`）

| 字段                   | 必填 | 说明                                               |
|------------------------|------|----------------------------------------------------|
| `task_text`            | 是   | 用户自然语言指令（不可为空或仅含空白符）            |
| `device_id`            | 是   | 发送设备唯一标识（不可为空）                        |
| `session_id`           | 是   | 当前会话标识（不可为空）                            |
| `task_id`              | 否*  | 唯一任务 ID；实际发送时应填写（与 `correlation_id` 对应） |
| `context.locale`       | 否   | 设备当前语言地区设置                               |
| `context.app_foreground`| 否  | 前台应用包名                                       |
| `context.extra`        | 否   | 任意扩展键值对                                     |

> \* `task_id` 在 `validate()` 中不作为必填字段（可为空字符串），但生产代码**应当**填写。

---

## Microsoft 兼容映射示例

当 `EnhancedAIPClient.microsoftMappingEnabled = true`（默认）时，`applyMicrosoftMapping()` 在
v3 信封基础上**追加**以下三个 `ms_*` 字段，v3 字段本身**始终不变**。

### 示例：device_register（映射开启）

```json
{
  "protocol":        "AIP/1.0",
  "version":         "3.0",
  "type":            "device_register",
  "source_node":     "android_pixel8_01",
  "target_node":     "Galaxy",
  "timestamp":       1710000000,
  "message_id":      "a1b2c3d4",
  "device_id":       "android_pixel8_01",
  "device_type":     "Android_Agent",
  "payload":         { "platform": "android", "...": "..." },
  "ms_message_type": "REGISTER",
  "ms_agent_id":     "android_pixel8_01",
  "ms_session_id":   1710000000000
}
```

### `ms_*` 字段说明

| 字段              | 来源                                    | 说明                                     |
|-------------------|-----------------------------------------|------------------------------------------|
| `ms_message_type` | `microsoftTypeMapping[v3 type]`         | Microsoft wire-type 字符串               |
| `ms_agent_id`     | `source_node`                           | `source_node` 的别名（Microsoft 字段名） |
| `ms_session_id`   | `timestamp × 1000`                      | 毫秒级时间戳（Microsoft 约定）           |

### v3 类型 → Microsoft 类型对照表

| v3 `type`           | `ms_message_type`   |
|---------------------|---------------------|
| `device_register`   | `REGISTER`          |
| `heartbeat`         | `HEARTBEAT`         |
| `capability_report` | `CAPABILITY_REPORT` |
| `task_assign`       | `TASK`              |
| `command_result`    | `COMMAND_RESULTS`   |

### 关闭 Microsoft 映射

```kotlin
client.microsoftMappingEnabled = false
```

关闭后，出站消息为纯 v3 载荷，不含任何 `ms_*` 字段。

---

## v2 → v3 类型映射表

`MsgType.LEGACY_TYPE_MAP` / `MsgType.toV3Type()` 提供以下规范化映射：

| v2 / Legacy 字符串 | v3 等价              |
|--------------------|----------------------|
| `registration`     | `device_register`    |
| `register`         | `device_register`    |
| `heartbeat`        | `heartbeat`          |
| `command`          | `task_assign`        |
| `command_result`   | `command_result`     |

**使用方式**：

```kotlin
// 读取外部 / 第三方输入时规范化类型字符串
val v3Type = MsgType.toV3Type("registration") // → "device_register"

// 新代码直接使用枚举常量
val type = MsgType.DEVICE_REGISTER.value // → "device_register"
```

> 新代码**必须**直接使用 `MsgType` 枚举常量，`toV3Type()` 仅用于规范化来自旧系统或第三方的输入。
