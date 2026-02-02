# Android-Server Integration Guide

## End-to-End Voice Command Flow

### 1. Voice Input (Android)
```kotlin
// User speaks: "让无人机起飞"
val voiceText = speechRecognizer.getResult()
```

### 2. Send to Server
```kotlin
// WebSocket message to Galaxy Gateway
val message = AIPMessage(
    type = MessageType.VOICE_COMMAND,
    payload = mapOf(
        "text" to voiceText,
        "timestamp" to System.currentTimeMillis()
    )
)
webSocketClient.send(message)
```

### 3. Server Processing
```python
# EndToEndController.process_voice_command()
result = await controller.process_voice_command("让无人机起飞")
# -> Node_43_MAVLink.takeoff()
```

### 4. Response to Android
```kotlin
// Server sends execution result
val result = AIPMessage(
    type = MessageType.COMMAND_RESULT,
    payload = mapOf(
        "status" to "success",
        "node_id" to "Node_43_MAVLink",
        "action" to "takeoff",
        "result" to {...}
    )
)
```

## Supported Voice Commands

### Drone Commands (Node_43_MAVLink)
- "让无人机起飞" / "drone takeoff"
- "无人机降落" / "drone land"
- "无人机前进10米" / "drone move forward 10 meters"
- "无人机拍照" / "drone capture"
- "无人机状态" / "drone status"

### 3D Printer Commands (Node_49_OctoPrint)
- "开始打印" / "start print"
- "暂停打印" / "pause print"
- "设置喷头温度200度" / "set nozzle temperature to 200"
- "打印状态" / "printer status"

## API Alignment

| Android | Server | Status |
|---------|--------|--------|
| AIPMessageV3 | aip_v3.py | ✅ Aligned |
| VoiceCommand | process_voice_command() | ✅ New |
| CommandResult | ExecutionResult | ✅ New |
| DeviceType | DeviceType | ✅ Aligned |

## Implementation Checklist

- [ ] WebSocket client for voice command sending
- [ ] Speech recognition integration
- [ ] Command result handling
- [ ] Push notification for async results
- [ ] Error handling and retry
