#!/bin/bash
# UFO³ Galaxy Android 客户端自动配置构建脚本

set -e

echo "========================================"
echo "   UFO³ Galaxy Android 自动构建"
echo "========================================"
echo ""

# 获取 Windows PC 的 Tailscale IP
echo "请输入 Windows PC 的 Tailscale IP 地址:"
read -r WINDOWS_IP

if [ -z "$WINDOWS_IP" ]; then
    echo "[错误] IP 地址不能为空"
    exit 1
fi

echo ""
echo "请输入设备 ID (例如: android-xiaomi-14):"
read -r DEVICE_ID

if [ -z "$DEVICE_ID" ]; then
    DEVICE_ID="android-device"
    echo "[提示] 使用默认设备 ID: $DEVICE_ID"
fi

# 端口统一为 8765（WS、REST 与 WebRTC 同一 Galaxy Gateway 进程同端口）
PORT=8765
WS_URL="ws://${WINDOWS_IP}:${PORT}"
HTTP_URL="http://${WINDOWS_IP}:${PORT}"

echo ""
echo "配置信息:"
echo "  Windows IP: $WINDOWS_IP"
echo "  设备 ID: $DEVICE_ID"
echo "  Gateway URL: $WS_URL  (WS/REST/WebRTC 同端口)"
echo ""
echo "按任意键继续，或 Ctrl+C 取消..."
read -n 1 -s

# 配置文件路径（标准方式，无需修改 Kotlin 源码）
CONFIG_FILE="app/src/main/assets/config.properties"

if [ ! -f "$CONFIG_FILE" ]; then
    echo "[错误] 未找到 $CONFIG_FILE"
    exit 1
fi

# 备份原始配置文件
if [ ! -f "${CONFIG_FILE}.backup" ]; then
    cp "$CONFIG_FILE" "${CONFIG_FILE}.backup"
    echo "[✓] 已备份原始配置文件"
fi

# 修改配置
echo "[1/3] 正在修改配置..."
sed -i.tmp "s|galaxy\.gateway\.url=.*|galaxy.gateway.url=$WS_URL|g" "$CONFIG_FILE"
sed -i.tmp "s|rest\.base\.url=.*|rest.base.url=$HTTP_URL|g" "$CONFIG_FILE"
# 若存在 agent.id 为空则写入设备 ID
sed -i.tmp "s|^agent\.id=$|agent.id=$DEVICE_ID|g" "$CONFIG_FILE"
rm -f "${CONFIG_FILE}.tmp"
echo "[✓] 配置已更新 (端口 $PORT)"

# 清理旧的构建
echo ""
echo "[2/3] 清理旧的构建..."
./gradlew clean
echo "[✓] 清理完成"

# 构建 APK
echo ""
echo "[3/3] 正在构建 APK..."
echo "这可能需要几分钟，请耐心等待..."
./gradlew assembleDebug

# 查找生成的 APK
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
    # 重命名 APK
    NEW_APK_NAME="UFO3_Galaxy_${DEVICE_ID}_$(date +%Y%m%d).apk"
    cp "$APK_PATH" "$NEW_APK_NAME"
    
    echo ""
    echo "========================================"
    echo "   构建成功！"
    echo "========================================"
    echo ""
    echo "APK 文件: $NEW_APK_NAME"
    echo "文件大小: $(du -h "$NEW_APK_NAME" | cut -f1)"
    echo ""
    echo "请将此 APK 传输到您的 Android 设备并安装"
    echo ""
else
    echo ""
    echo "[错误] 构建失败，未找到 APK 文件"
    exit 1
fi

# 恢复原始配置（可选）
echo "是否恢复原始配置文件? (y/n)"
read -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    mv "${CONFIG_FILE}.backup" "$CONFIG_FILE"
    echo "[✓] 已恢复原始配置"
fi
