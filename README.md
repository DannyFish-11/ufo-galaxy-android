# UFO Galaxy Android Sub-Agent

[![Android CI/CD Build](https://github.com/DannyFish-11/ufo-galaxy-android/actions/workflows/build.yml/badge.svg)](https://github.com/DannyFish-11/ufo-galaxy-android/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/DannyFish-11/ufo-galaxy-android?include_prereleases)](https://github.com/DannyFish-11/ufo-galaxy-android/releases)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

## 📝 **最新状态 (2026-02-11)**

- **最新提交:** `构建系统统一 - 移除重复 Groovy 文件，使用 Kotlin DSL`
- **构建系统:** ✅ 已统一为 Kotlin DSL（移除 Groovy 构建文件冲突）
- **代码质量:** ✅ 通过静态检查
- **CI/CD 状态:** ✅ GitHub Actions 自动构建已启用
- **自动构建:** 每次 push 和 PR 自动构建 Debug APK
- **自动发布:** 打 tag 时自动发布到 GitHub Releases
- **下一步计划:** A-2 轮次将进行协议对齐（AIP 协议版本统一）

---




安卓子 Agent 原生 App，与 PC 主 Agent 协同工作。

## 🎉 最新更新（v1.1.0）

### ✅ 已实现无障碍服务

**重大更新**：现已支持系统级自动化操控！

- ✅ **无障碍服务**（AccessibilityService）- 系统级权限
- ✅ **点击和滑动**（performClick, performSwipe）
- ✅ **界面内容读取**（getScreenContent）
- ✅ **智能元素查找**（findElementByText, findElementById）
- ✅ **文本输入**（inputText）
- ✅ **系统导航**（Home, Back, Recents）

**与豆包手机对比**：
- ✅ 同样使用无障碍服务
- ✅ 同样支持系统级操控
- ✅ 更开放的架构（可自由扩展）

---

## 功能特性

### 核心节点

- **Node 00**: 状态机 - 本地状态管理
- **Node 04**: 工具路由器 - 智能工具发现与调用
- **Node 33**: ADB 自控 - **使用无障碍服务实现系统级操控**
- **Node 41**: MQTT 通信 - 与 PC 主 Agent 通信
- **Node 58**: 模型路由 - 本地/云端模型选择

### 无障碍服务功能

#### 1. 坐标操作
```json
// 点击
{
  "action": "click",
  "x": 500,
  "y": 1000
}

// 滑动
{
  "action": "swipe",
  "start_x": 500,
  "start_y": 1500,
  "end_x": 500,
  "end_y": 500,
  "duration": 300
}

// 滚动
{
  "action": "scroll",
  "direction": "down",
  "amount": 500
}
```

#### 2. 智能操作
```json
// 根据文本点击
{
  "action": "click_text",
  "text": "确定",
  "exact": false
}

// 根据 View ID 点击
{
  "action": "click_id",
  "view_id": "com.example:id/button"
}

// 文本输入
{
  "action": "input_text",
  "finder_text": "搜索",
  "input_text": "Hello World"
}
```

#### 3. 界面分析
```json
// 获取屏幕内容
{
  "action": "get_screen"
}

// 返回示例
{
  "success": true,
  "element_count": 42,
  "elements": [
    {
      "class": "android.widget.Button",
      "text": "确定",
      "clickable": true,
      "bounds": {
        "left": 100,
        "top": 200,
        "right": 300,
        "bottom": 280,
        "center_x": 200,
        "center_y": 240
      }
    }
  ]
}
```

#### 4. 系统导航
```json
// 返回主屏幕
{"action": "home"}

// 返回上一页
{"action": "back"}

// 打开最近任务
{"action": "recents"}
```

---

### 智能工具发现

- 自动扫描已安装的 Android App
- 推断 App 能力（相机、自动化、编程等）
- 支持 Termux 命令行工具
- AI 驱动的工具选择

### 跨设备协同

- MQTT 协议与 PC 通信
- 接收远程任务指令
- 上报执行结果
- 文件传输支持

---

## 构建与安装

### 构建系统说明

**v1.1.1 更新 (2026-02-11)**：项目已统一使用 Kotlin DSL 构建脚本。

- ✅ **已移除**：重复的 Groovy 构建文件（`build.gradle`、`settings.gradle`）
- ✅ **已保留**：Kotlin DSL 文件（`build.gradle.kts`、`settings.gradle.kts`）
- ✅ **构建工具**：Gradle 8.2 + Android Gradle Plugin 8.2.0
- ✅ **Kotlin 版本**：1.9.20

这次清理解决了构建配置冲突问题，为后续协议对齐和功能开发打下了清晰的基础。

### 前置要求

- Android Studio Hedgehog | 2023.1.1 或更高版本
- Android SDK 34
- Kotlin 1.9+
- Gradle 8.2+ (项目已包含 wrapper)

### 构建步骤

```bash
# 1. 克隆项目
git clone https://github.com/DannyFish-11/ufo-galaxy-android.git
cd ufo-galaxy-android

# 2. 在 Android Studio 中打开项目

# 3. 构建 APK
./gradlew assembleDebug

# 4. 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 直接安装

如果您已有编译好的 APK：

```bash
adb install ufo-galaxy-agent.apk
```

### 自动构建 (CI/CD)

本项目已配置 GitHub Actions 自动构建：

#### 触发方式
- **Push 到 main/master/develop 分支**: 自动构建 Debug APK
- **Pull Request**: 自动运行测试和构建
- **打 Tag (v*)**: 自动构建 Release APK 并发布到 GitHub Releases

#### 下载构建产物
1. 进入 [Actions](https://github.com/DannyFish-11/ufo-galaxy-android/actions) 页面
2. 选择最新的成功构建
3. 在 Artifacts 区域下载 `debug-apk`

#### 发布新版本
```bash
# 1. 更新版本号 (app/build.gradle.kts)
# 2. 提交更改
git add .
git commit -m "Bump version to v1.2.0"

# 3. 打标签并推送
git tag v1.2.0
git push origin v1.2.0

# 4. GitHub Actions 将自动构建并发布到 Releases
```

---

## 配置说明

### 连接配置

UFO Galaxy Android 需要与主系统（PC Agent/Gateway）进行通信。以下是推荐的配置方式：

#### 方式一：通过配置文件

在设备上创建配置文件 `/sdcard/Android/data/com.ufo.galaxy/files/config.json`：

```json
{
  "gateway_url": "http://your-server-ip:8000",
  "mqtt_broker": "mqtt://your-server-ip:1883",
  "device_id": "android-device-001",
  "enable_webrtc": true
}
```

#### 方式二：通过 App 设置界面

在 App 中配置以下参数：

| 配置项 | 说明 | 示例值 |
|--------|------|--------|
| Gateway URL | 主系统网关地址 | `http://192.168.1.100:8000` |
| MQTT Broker | MQTT 消息代理 | `mqtt://192.168.1.100:1883` |
| Device ID | 设备唯一标识 | `android-device-001` |
| WebRTC | 屏幕共享支持 | `启用/禁用` |

#### 关于 AIP 协议版本

**重要说明**：当前 Android 端使用的协议版本将在后续轮次中与主系统统一。本轮重点是确保构建系统清晰、代码可编译。

- **当前版本**：使用自定义消息格式进行通信
- **计划升级**：将在 A-2 轮次中对齐到主系统的 AIP 协议规范
- **兼容性**：现有功能不受影响，升级时会保持向后兼容

### 与主系统对齐

为了与 PC 主 Agent 正确协同工作，请确保：

1. **网络连通性**：Android 设备与主系统在同一网络或可互相访问
2. **端口开放**：确保防火墙允许配置的端口（默认 8000, 1883）
3. **协议版本**：当前使用基础协议，后续将统一为 AIP 协议
4. **设备注册**：首次连接时会自动向主系统注册设备

---

## 使用方法

### 1. 启动 Agent

1. 打开 UFO Galaxy App
2. 点击 "Start Agent Service" 启动后台服务
3. Agent 将在后台持续运行

### 2. 启用无障碍服务（重要！）

**必须启用无障碍服务才能使用系统级操控功能**

1. 打开 **设置 → 辅助功能 → 无障碍**
2. 找到 **UFO Galaxy**
3. 启用服务
4. 授予权限

**首次使用会弹出授权提示，请点击"允许"**

### 3. 配置与 PC 通信

在 App 设置中配置：

```
MQTT Broker: mqtt://your-pc-ip:1883
OneAPI URL: http://your-pc-ip:3000
```

### 4. 测试无障碍服务

点击 "Test Agent" 按钮，输入任务描述，如：

- "点击屏幕中心"
- "点击'确定'按钮"
- "向下滚动"
- "输入文本到搜索框"

---

## 架构设计

```
UFO Galaxy Android Sub-Agent
├── AgentCore (核心引擎)
│   ├── Node Registry (节点注册)
│   └── Tool Registry (工具发现)
├── Nodes (节点层)
│   ├── Node00StateMachine
│   ├── Node04ToolRouter
│   ├── Node33ADBSelf (无障碍服务)
│   ├── Node41MQTT
│   └── Node58ModelRouter
├── Service (后台服务)
│   ├── AgentService (前台服务)
│   └── UFOAccessibilityService (无障碍服务)
└── UI (用户界面)
    └── MainActivity
```

---

## 与 PC 主 Agent 协同

### 通信协议

使用 MQTT 进行双向通信：

**PC → Android (任务下发)**
```json
{
  "type": "task",
  "task_id": "uuid",
  "description": "点击'确定'按钮",
  "params": {
    "action": "click_text",
    "text": "确定"
  }
}
```

**Android → PC (结果上报)**
```json
{
  "type": "result",
  "task_id": "uuid",
  "success": true,
  "data": {
    "message": "Clicked element with text: 确定"
  }
}
```

### 跨设备任务示例

1. **PC 触发安卓点击**
   ```
   用户 → PC Agent → MQTT → Android Agent → 无障碍服务 → 点击元素 → 结果返回 PC
   ```

2. **安卓触发 PC 工具**
   ```
   安卓 App → MQTT → PC Agent → 打开 OpenCode → 编辑文件 → 结果返回安卓
   ```

3. **智能界面操作**
   ```
   PC Agent → 发送任务 → Android Agent → 读取界面 → 查找元素 → 点击 → 验证结果
   ```

---

## 权限说明

App 需要以下权限：

- `INTERNET`: 网络通信
- `FOREGROUND_SERVICE`: 后台服务
- `QUERY_ALL_PACKAGES`: 工具发现
- `PACKAGE_USAGE_STATS`: App 使用统计
- `BIND_ACCESSIBILITY_SERVICE`: 无障碍服务（系统级操控）

---

## 开发计划

### ✅ 已完成
- [x] 完整的无障碍服务实现
- [x] 系统级点击和滑动
- [x] 界面内容读取
- [x] 智能元素查找
- [x] 文本输入
- [x] 系统导航（Home, Back, Recents）

### ⏳ 进行中
- [ ] 完整的 MQTT 客户端实现
- [ ] 本地 HTTP 服务器（用于 PC 直接调用）

### 🔮 计划中
- [ ] Shizuku 集成（免 root 的 ADB 能力）
- [ ] 截图和 OCR 集成
- [ ] VLM 集成（GUI 理解）
- [ ] 更多节点支持
- [ ] UI 增强（极简极客风 + 灵动岛）

---

## 与豆包手机对比

| 功能 | 豆包手机 | UFO³ Android | 说明 |
| :--- | :---: | :---: | :--- |
| **无障碍服务** | ✅ | ✅ | 都使用系统级权限 |
| **系统级操控** | ✅ | ✅ | 点击、滑动、输入 |
| **界面理解** | ✅ (VLM) | ⏳ (计划中) | 豆包更智能 |
| **跨设备协同** | ❌ | ✅ | UFO³ 独有 |
| **开放架构** | ❌ | ✅ | UFO³ 可自由扩展 |
| **硬件集成** | ❌ | ✅ | UFO³ 支持更多设备 |

---

## 常见问题

### Q: 为什么需要无障碍服务？

A: 无障碍服务是 Android 提供的系统级权限，允许应用：
- 读取屏幕内容
- 模拟用户操作（点击、滑动）
- 自动化任务执行

这是实现智能 Agent 的核心能力。

### Q: 无障碍服务安全吗？

A: 是的。无障碍服务：
- 需要用户手动授权
- 可以随时在设置中关闭
- 所有操作都在本地执行
- 不会上传任何数据

### Q: 与 ADB 有什么区别？

A: 
- **ADB**: 需要 USB 连接或网络 ADB，延迟高
- **无障碍服务**: 直接在设备上运行，延迟低，更稳定

### Q: 能否在没有 root 的设备上使用？

A: 可以！无障碍服务不需要 root 权限。

---

## 许可证

MIT License

---

## 相关项目

- [UFO Galaxy PC Agent](https://github.com/DannyFish-11/ufo-galaxy)
- [Microsoft UFO](https://github.com/microsoft/UFO)

---

## 更新日志

### v1.1.0 (2026-01-24)
- ✅ 实现完整的无障碍服务
- ✅ 支持系统级点击和滑动
- ✅ 支持界面内容读取
- ✅ 支持智能元素查找
- ✅ 支持文本输入
- ✅ 更新 Node_33 集成无障碍服务

### v1.0.0 (2026-01-22)
- ✅ 初始版本
- ✅ 基础节点实现
- ✅ MQTT 通信
- ✅ 工具发现
