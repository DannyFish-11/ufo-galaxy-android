# UFO Galaxy Android Agent - 部署和配置指南

**版本:** 2.5
**作者:** UFO Galaxy Team
**日期:** 2026-03-07

---

## 1. 概述

本文档将指导您如何部署和配置 UFO Galaxy Android Agent，使其成为您 Galaxy 系统的一个强大移动端节点。

客户端遵循 **AIP v3 规范**：
- WebSocket 优先路径：`/ws/device/{id}`（含设备 ID）
- REST API：`/api/v1/devices/*`（优先），自动回退到 `/api/devices/*`（旧版）
- 所有出站消息由 `AIPMessageBuilder` 统一构建，包含 `protocol`、`version`、`device_id`、`device_type`、`message_id`、`timestamp` 字段

## 2. 准备工作

- 一台 Android 设备 (Android 8.0+)
- Android Studio
- 您的服务端已部署并运行 Galaxy 系统 (ufo-galaxy-realization-v2)
- 您的服务端和 Android 设备之间网络互通（推荐使用 Tailscale）

## 3. 构建 APK

1.  **克隆或拉取最新代码**
    ```bash
    git clone https://github.com/DannyFish-11/ufo-galaxy-android.git
    cd ufo-galaxy-android
    ```

2.  **打开项目**
    在 Android Studio 中打开项目根目录。

3.  **修改配置**
    打开 `app/src/main/assets/config.properties` 文件。

    将 IP 地址修改为您服务端的地址（仅填写主机+端口，**不要**附加路径）：
    ```properties
    # WebSocket 基础地址（仅主机 + 端口，客户端自动拼接路径）
    galaxy.gateway.url=ws://<YOUR_SERVER_IP>:8000

    # REST HTTP 基础地址
    rest.base.url=http://<YOUR_SERVER_IP>:8000
    ```

    > ⚠️ **注意**：`galaxy.gateway.url` 只填写主机+端口，不含路径。
    > 客户端会按优先级自动尝试以下 WebSocket 路径：
    > 1. `/ws/device/{id}` （推荐路径，含设备 ID）
    > 2. `/ws/android` （通用 Android 路径）
    > 3. `/ws/ufo3/{id}` （旧版兼容路径）

4.  **构建 APK**
    - 在 Android Studio 中，点击 `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`。
    - APK 文件将生成在 `app/build/outputs/apk/debug/app-debug.apk`。

## 4. 安装和配置

1.  **安装 APK**
    将 `app-debug.apk` 文件传输到您的 Android 设备并安装。

2.  **授予权限**
    首次打开应用时，请授予所有必要的权限：
    - **悬浮窗权限** (SYSTEM_ALERT_WINDOW)
    - **无障碍服务权限** (Accessibility Service)
    - **麦克风权限** (RECORD_AUDIO)

3.  **启动服务**
    - 在应用主界面，点击 **"启动 Galaxy Agent"** 按钮。
    - 点击 **"启动悬浮窗"** 按钮。

## 5. 验证

1.  **查看 Galaxy Gateway 日志**
    您应该能看到类似以下的日志：
    ```
    INFO:     100.x.x.x:xxxxx - "GET /ws/device/android_xxxxxxxx HTTP/1.1" 101 Switching Protocols
    INFO:     Device 'android_xxxxxxxx' registered successfully.
    ```

2.  **使用悬浮窗**
    - 点击悬浮窗上的麦克风按钮，说出指令，例如"你好"。
    - 您应该能在 Galaxy Gateway 的日志中看到接收到的消息。

3.  **测试自主操纵**
    - 在悬浮窗中输入文本指令，例如：`打开微信`。
    - 您的 Android 设备应该会自动打开微信应用。

## 6. 多设备部署

您可以将同一个 APK 安装到多个 Android 设备（手机、平板）。每个设备都会基于其 Android ID 自动生成唯一的设备 ID（格式：`android_xxxxxxxx`），自动注册为 Galaxy 系统的一个独立节点。

您可以在 `config.properties` 文件中为每个设备设置不同的 `agent.name` 以便区分。

## 7. 接入规范快速参考

| 类型 | 路径/端点 | 优先级 | 说明 |
|------|-----------|--------|------|
| WebSocket | `/ws/device/{id}` | 最高 | 推荐，含设备 ID |
| WebSocket | `/ws/android` | 中 | 通用 Android 路径 |
| WebSocket | `/ws/ufo3/{id}` | 最低 | 旧版兼容 |
| REST 注册 | `POST /api/v1/devices/register` | 优先 | 自动回退到 `/api/devices/register` |
| REST 心跳 | `POST /api/v1/devices/heartbeat` | 优先 | 自动回退到 `/api/devices/heartbeat` |
| REST 发现 | `GET /api/v1/devices/discover` | 优先 | 自动回退到 `/api/devices/discover` |

---

**部署完成！享受您的多设备自动化系统吧！** 🚀
