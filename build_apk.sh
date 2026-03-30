#!/bin/bash
# UFO Galaxy Android - APK 打包脚本
#
# 用法 (Usage):
#   ./build_apk.sh                  # 交互模式 (interactive: prompts for release build)
#   ./build_apk.sh --release        # 构建 Debug + Release (requires signing env vars)
#   ./build_apk.sh --debug-only     # 仅构建 Debug APK
#   CI=true ./build_apk.sh          # CI 模式: 仅 Debug, 非交互
#
# Release 签名所需环境变量 (required for release builds):
#   KEYSTORE_PATH        - keystore 文件路径 (e.g. /path/to/release.jks)
#   KEYSTORE_PASSWORD    - keystore 密码
#   KEY_ALIAS            - key alias
#   KEY_PASSWORD         - key 密码

set -euo pipefail

echo "=========================================="
echo "  UFO Galaxy Android APK 打包"
echo "=========================================="
echo ""

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
BUILD_RELEASE=false
DEBUG_ONLY=false

for arg in "$@"; do
    case "$arg" in
        --release)    BUILD_RELEASE=true ;;
        --debug-only) DEBUG_ONLY=true ;;
        *) echo "未知参数: $arg  (用法: --release | --debug-only)"; exit 1 ;;
    esac
done

# In CI mode always skip the interactive prompt
CI="${CI:-false}"
if [ "$CI" = "true" ]; then
    DEBUG_ONLY=true
fi

# ---------------------------------------------------------------------------
# Environment checks
# ---------------------------------------------------------------------------
if ! command -v java &> /dev/null; then
    echo "❌ 错误: 未找到 Java，请先安装 JDK 17+"
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
echo "✅ Java 版本: $JAVA_VERSION"

if [ -z "${ANDROID_HOME:-}" ]; then
    echo "⚠️  警告: ANDROID_HOME 未设置"
    echo "   请设置 ANDROID_HOME 环境变量指向 Android SDK 目录"
    echo "   例如: export ANDROID_HOME=~/Android/Sdk"
fi

# ---------------------------------------------------------------------------
# Project directory
# ---------------------------------------------------------------------------
cd "$(dirname "$0")"
PROJECT_DIR=$(pwd)
echo "📁 项目目录: $PROJECT_DIR"

# ---------------------------------------------------------------------------
# Clean
# ---------------------------------------------------------------------------
echo ""
echo "🧹 清理旧构建..."
./gradlew clean 2>/dev/null || {
    echo "   首次运行，下载 Gradle..."
}

# ---------------------------------------------------------------------------
# Debug build (always)
# ---------------------------------------------------------------------------
echo ""
echo "🔨 构建 Debug APK..."
./gradlew assembleDebug

DEBUG_APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$DEBUG_APK" ]; then
    echo ""
    echo "✅ Debug APK 构建成功!"
    echo "   路径: $DEBUG_APK"
    echo "   大小: $(du -h "$DEBUG_APK" | cut -f1)"
else
    echo "❌ Debug APK 构建失败"
    exit 1
fi

# ---------------------------------------------------------------------------
# Release build decision
# ---------------------------------------------------------------------------
if [ "$DEBUG_ONLY" = "false" ] && [ "$BUILD_RELEASE" = "false" ]; then
    # Interactive mode: ask the developer
    echo ""
    read -r -p "是否构建 Release APK? (y/n): " USER_INPUT
    if [ "$USER_INPUT" = "y" ] || [ "$USER_INPUT" = "Y" ]; then
        BUILD_RELEASE=true
    fi
fi

# ---------------------------------------------------------------------------
# Release build
# ---------------------------------------------------------------------------
if [ "$BUILD_RELEASE" = "true" ]; then
    echo ""
    echo "🔨 构建 Release APK..."

    # Require all signing parameters via environment variables; no insecure fallbacks.
    MISSING_VARS=""
    [ -z "${KEYSTORE_PATH:-}"     ] && MISSING_VARS="$MISSING_VARS KEYSTORE_PATH"
    [ -z "${KEYSTORE_PASSWORD:-}" ] && MISSING_VARS="$MISSING_VARS KEYSTORE_PASSWORD"
    [ -z "${KEY_ALIAS:-}"         ] && MISSING_VARS="$MISSING_VARS KEY_ALIAS"
    [ -z "${KEY_PASSWORD:-}"      ] && MISSING_VARS="$MISSING_VARS KEY_PASSWORD"

    if [ -n "$MISSING_VARS" ]; then
        echo "❌ Release 构建需要以下环境变量:$MISSING_VARS"
        echo "   请设置后重试，例如:"
        echo "     export KEYSTORE_PATH=/path/to/release.jks"
        echo "     export KEYSTORE_PASSWORD=<密码>"
        echo "     export KEY_ALIAS=<别名>"
        echo "     export KEY_PASSWORD=<密码>"
        exit 1
    fi

    if [ ! -f "$KEYSTORE_PATH" ]; then
        echo "❌ Keystore 文件不存在: $KEYSTORE_PATH"
        exit 1
    fi

    ./gradlew assembleRelease \
        -Pandroid.injected.signing.store.file="$KEYSTORE_PATH" \
        -Pandroid.injected.signing.store.password="$KEYSTORE_PASSWORD" \
        -Pandroid.injected.signing.key.alias="$KEY_ALIAS" \
        -Pandroid.injected.signing.key.password="$KEY_PASSWORD"

    RELEASE_APK="$PROJECT_DIR/app/build/outputs/apk/release/app-release.apk"
    if [ -f "$RELEASE_APK" ]; then
        echo ""
        echo "✅ Release APK 构建成功!"
        echo "   路径: $RELEASE_APK"
        echo "   大小: $(du -h "$RELEASE_APK" | cut -f1)"
    else
        echo "❌ Release APK 构建失败"
        exit 1
    fi
fi

echo ""
echo "=========================================="
echo "  构建完成!"
echo "=========================================="
