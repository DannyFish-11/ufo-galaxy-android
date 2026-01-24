# Android 浮动窗口完整开发文档

## 📋 开发概述

本文档记录了 UFO³ Galaxy Android 端浮动窗口 UI 的完整开发过程，包括黑白渐变设计、Dynamic Island 风格、语音和文字双输入模式。

**开发日期**: 2025-01-24  
**版本**: v1.0.0  
**代码行数**: 2,314 行（Kotlin: 2,014 行 + XML: 300 行）

---

## 🎯 核心功能

### 1. 浮动窗口服务 (FloatingWindowService)

**文件**: `app/src/main/java/com/ufo/galaxy/service/FloatingWindowService.kt`  
**代码行数**: 494 行

**核心功能**:
- ✅ 黑白渐变背景（#000000 → #1A1A1A）
- ✅ Dynamic Island 风格（紧凑视图 ↔ 展开视图）
- ✅ 语音识别输入（Android SpeechRecognizer）
- ✅ 文字输入（EditText）
- ✅ 对话历史显示
- ✅ 与 Node_33 (AndroidControl) 集成
- ✅ 拖拽移动支持

**视图模式**:

| 模式 | 尺寸 | 功能 |
| :--- | :--- | :--- |
| **紧凑视图** | 120dp × 40dp | 状态指示器 + UFO³ 标题 |
| **展开视图** | 320dp × 自适应 | 完整交互界面（历史 + 输入 + 按钮） |

---

### 2. 截图辅助类 (ScreenshotHelper)

**文件**: `app/src/main/java/com/ufo/galaxy/utils/ScreenshotHelper.kt`  
**代码行数**: 123 行

**核心功能**:
- ✅ MediaProjection API 截图（Android 11+）
- ✅ 自动保存到应用私有目录
- ✅ 与 Node_113 (AndroidVLM) 集成

---

### 3. 无障碍服务增强 (UFOAccessibilityService)

**文件**: `app/src/main/java/com/ufo/galaxy/service/UFOAccessibilityService.kt`  
**更新**: 添加截图功能

**新增功能**:
- ✅ `takeScreenshot()` - 调用 ScreenshotHelper 截图
- ✅ 与浮动窗口协同工作

---

### 4. MainActivity 更新

**文件**: `app/src/main/java/com/ufo/galaxy/MainActivity.kt`  
**更新**: 添加浮动窗口启动按钮

**新增功能**:
- ✅ 检查 `SYSTEM_ALERT_WINDOW` 权限
- ✅ 启动 FloatingWindowService
- ✅ 权限请求引导

---

### 5. UI 布局文件

#### 5.1 浮动窗口布局

**文件**: `app/src/main/res/layout/floating_window.xml`  
**代码行数**: 145 行

**组件**:
- 紧凑视图 (LinearLayout)
  - 状态指示器 (View)
  - 标题 (TextView)
- 展开视图 (LinearLayout)
  - 标题栏（标题 + 关闭按钮）
  - 历史记录 (ScrollView + TextView)
  - 输入区域 (EditText)
  - 按钮行（语音按钮 + 发送按钮）

#### 5.2 主界面布局更新

**文件**: `app/src/main/res/layout/activity_main.xml`  
**更新**: 添加 "Start Floating Window" 按钮

#### 5.3 Drawable 资源

**文件**:
- `app/src/main/res/drawable/circle_indicator.xml` - 圆形状态指示器（绿色）
- `app/src/main/res/drawable/button_white.xml` - 白色圆角按钮背景

---

## 🔧 权限配置

**文件**: `app/src/main/AndroidManifest.xml`

**新增权限**:
```xml
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

**新增服务**:
```xml
<service
    android:name=".service.FloatingWindowService"
    android:enabled="true"
    android:exported="false" />
```

---

## 🎨 UI 设计规范

### 配色方案（黑白渐变）

| 元素 | 颜色 | 用途 |
| :--- | :--- | :--- |
| **背景渐变** | #000000 → #1A1A1A | 主背景 |
| **主文字** | #FFFFFF | 标题、输入文字 |
| **次要文字** | #CCCCCC | 历史记录 |
| **提示文字** | #555555 | 占位符 |
| **辅助文字** | #888888 | 标签 |
| **分隔线** | #333333 | 分隔线 |
| **状态指示器** | #00FF00 | 在线状态 |
| **按钮背景** | #FFFFFF | 发送按钮 |
| **按钮文字** | #000000 | 发送按钮文字 |

### 字体规范

| 元素 | 字体 | 大小 | 样式 |
| :--- | :--- | :--- | :--- |
| **标题（紧凑）** | monospace | 14sp | bold |
| **标题（展开）** | monospace | 16sp | bold |
| **标签** | monospace | 10sp | normal |
| **输入框** | monospace | 14sp | normal |
| **历史记录** | monospace | 12sp | normal |
| **按钮** | monospace | 12sp | bold |

### 尺寸规范

| 元素 | 尺寸 |
| :--- | :--- |
| **紧凑视图** | 120dp × 40dp |
| **展开视图** | 320dp × 自适应 |
| **状态指示器** | 10dp × 10dp |
| **按钮高度** | 48dp |
| **圆角半径** | 8dp |
| **内边距** | 8dp - 16dp |

---

## 🔄 交互流程

### 1. 启动流程

```
用户点击 "Start Floating Window"
    ↓
检查 SYSTEM_ALERT_WINDOW 权限
    ↓
[无权限] → 跳转到系统设置请求权限
    ↓
[有权限] → 启动 FloatingWindowService
    ↓
显示紧凑视图（Dynamic Island 风格）
```

### 2. 交互流程

```
[紧凑视图]
    ↓
用户点击 → 展开为完整界面
    ↓
[展开视图]
    ├─ 用户输入文字 → 点击 EXECUTE → 发送到 Node_33
    ├─ 用户点击语音按钮 → 语音识别 → 自动填充输入框
    └─ 用户点击关闭按钮 → 收缩为紧凑视图
```

### 3. 命令执行流程

```
用户输入命令（文字/语音）
    ↓
FloatingWindowService 接收
    ↓
调用 AgentCore.executeTask()
    ↓
Node_33 (AndroidControl) 执行
    ↓
返回结果 → 显示在历史记录
```

---

## 📊 代码统计

| 文件类型 | 文件数 | 代码行数 |
| :--- | :---: | :---: |
| **Kotlin** | 8 | 2,014 |
| **XML** | 8 | 300 |
| **总计** | 16 | **2,314** |

### 详细统计

| 文件 | 行数 | 说明 |
| :--- | :---: | :--- |
| `FloatingWindowService.kt` | 494 | 浮动窗口服务 |
| `UFOAccessibilityService.kt` | 494 | 无障碍服务（含截图） |
| `ScreenshotHelper.kt` | 123 | 截图辅助类 |
| `MainActivity.kt` | 133 | 主界面（含浮动窗口启动） |
| `BaseNode.kt` | 368 | Node_33 基础节点（含截图操作） |
| `AgentCore.kt` | 254 | 代理核心 |
| `AgentService.kt` | 98 | 后台服务 |
| `UFOGalaxyApplication.kt` | 50 | 应用类 |
| `floating_window.xml` | 145 | 浮动窗口布局 |
| `activity_main.xml` | 78 | 主界面布局 |
| 其他 XML | 77 | 配置和资源文件 |

---

## ✅ 功能验证清单

### 基础功能

- [x] 浮动窗口显示
- [x] 紧凑视图 ↔ 展开视图切换
- [x] 拖拽移动
- [x] 黑白渐变背景
- [x] Dynamic Island 风格

### 输入功能

- [x] 文字输入
- [x] 语音识别
- [x] 输入框多行支持
- [x] 发送按钮

### 交互功能

- [x] 对话历史显示
- [x] 自动滚动到最新消息
- [x] 状态指示器
- [x] 关闭按钮

### 集成功能

- [x] 与 Node_33 (AndroidControl) 集成
- [x] 与 AgentCore 集成
- [x] 权限管理
- [x] 服务生命周期管理

---

## 🚀 部署说明

### 1. 编译要求

- **Android Studio**: Arctic Fox 或更高版本
- **Kotlin**: 1.8.0 或更高版本
- **Gradle**: 8.0 或更高版本
- **最低 Android 版本**: Android 8.0 (API 26)
- **目标 Android 版本**: Android 14 (API 34)

### 2. 依赖项

```gradle
dependencies {
    // 核心依赖（已在项目中）
    implementation "androidx.core:core-ktx:1.12.0"
    implementation "androidx.appcompat:appcompat:1.6.1"
    implementation "com.google.android.material:material:1.11.0"
    implementation "androidx.constraintlayout:constraintlayout:2.1.4"
    
    // 协程（已在项目中）
    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3"
    
    // JSON（已在项目中）
    implementation "org.json:json:20231013"
}
```

### 3. 运行步骤

1. **克隆仓库**
   ```bash
   git clone https://github.com/DannyFish-11/ufo-galaxy-android.git
   cd ufo-galaxy-android
   ```

2. **打开项目**
   - 使用 Android Studio 打开项目
   - 等待 Gradle 同步完成

3. **运行应用**
   - 连接 Android 设备或启动模拟器
   - 点击 Run (Shift+F10)

4. **授予权限**
   - 启动应用后，点击 "Start Floating Window"
   - 在系统设置中授予 "显示悬浮窗" 权限
   - 在应用设置中授予 "麦克风" 权限（用于语音输入）

5. **测试功能**
   - 点击紧凑视图展开界面
   - 测试文字输入和语音输入
   - 测试命令执行（如 "打开相机"）

---

## 🔮 后续优化方向

### 短期优化（1-2 周）

1. **UI 增强**
   - [ ] 添加更多动画效果
   - [ ] 优化拖拽手势
   - [ ] 添加主题切换（黑白/彩色）

2. **功能增强**
   - [ ] 添加语音播报（TTS）
   - [ ] 添加快捷命令按钮
   - [ ] 添加命令历史记录

3. **性能优化**
   - [ ] 优化内存使用
   - [ ] 优化电池消耗
   - [ ] 优化启动速度

### 中期优化（1-2 月）

1. **智能化增强**
   - [ ] 集成 VLM 进行 GUI 理解
   - [ ] 添加上下文感知
   - [ ] 添加任务规划

2. **跨设备协同**
   - [ ] 完善 MQTT 通信
   - [ ] 与 Windows 端同步
   - [ ] 跨设备任务分发

---

## 📝 已知问题

1. **语音识别**
   - 需要网络连接（使用 Google 语音识别）
   - 某些设备可能不支持

2. **权限管理**
   - 首次启动需要手动授予权限
   - 部分厂商可能限制悬浮窗权限

3. **兼容性**
   - 某些定制 Android 系统可能有兼容性问题
   - 需要在更多设备上测试

---

## 🎉 总结

本次开发完成了 UFO³ Galaxy Android 端的完整浮动窗口 UI，实现了：

- ✅ 黑白渐变 + Dynamic Island 风格设计
- ✅ 语音和文字双输入模式
- ✅ 与现有系统的完整集成
- ✅ 2,314 行高质量代码

**系统现在具备了与豆包手机类似的交互体验，同时保持了 UFO³ Galaxy 的开放架构和跨平台能力。**

---

**开发完成日期**: 2025-01-24  
**提交哈希**: 333b3c2  
**GitHub**: https://github.com/DannyFish-11/ufo-galaxy-android
