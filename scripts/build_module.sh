#!/usr/bin/env bash
# 打包 Magisk 模块 zip
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="$ROOT_DIR/module"
DIST_DIR="$ROOT_DIR/dist"

VERSION="$(grep -E '^version=' "$MODULE_DIR/module.prop" | cut -d= -f2 | tr -d '\r')"
VERSION="${VERSION:-v1.0.0}"
ZIP_NAME="vivo-remove-ads-${VERSION}.zip"

mkdir -p "$DIST_DIR"
OUT="$DIST_DIR/$ZIP_NAME"
rm -f "$OUT"

# Magisk 模块 zip 根目录必须直接包含 module.prop
cd "$MODULE_DIR"
zip -r9 "$OUT" \
  module.prop \
  customize.sh \
  service.sh \
  uninstall.sh \
  common/ \
  -x '*.git*' '*.DS_Store'

cd "$ROOT_DIR"
echo "打包完成: $OUT"
ls -lh "$OUT"
