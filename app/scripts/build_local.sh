#!/usr/bin/env bash
# 本地构建脚本（CI 走 .github/workflows/build-apk.yml）
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$ROOT_DIR/app"

: "${ANDROID_HOME:?请设置 ANDROID_HOME 指向 Android SDK}"

echo "==> 生成图标"
python "$APP_DIR/tools/gen_icons.py"

echo "==> 构建 Debug APK"
cd "$APP_DIR"
./gradlew assembleDebug

echo "==> 产物"
find "$APP_DIR/app/build/outputs/apk" -name '*.apk' -exec ls -lh {} \;
