#!/usr/bin/env bash
# 批量扫描 APK 中的广告组件
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STRIP="$ROOT_DIR/tools/apk_ad_strip.py"

if [ $# -lt 1 ]; then
  echo "用法: bash scripts/scan_ads.sh <apk文件或目录> [更多路径...]"
  echo "示例: bash scripts/scan_ads.sh ~/downloads/app.apk"
  exit 2
fi

PY="${PYTHON:-python3}"
command -v "$PY" >/dev/null 2>&1 || PY=python

scan_one() {
  local apk="$1"
  echo ""
  echo "########################################"
  echo "# 扫描: $apk"
  echo "########################################"
  "$PY" "$STRIP" --list "$apk" || true
}

for target in "$@"; do
  if [ -d "$target" ]; then
    while IFS= read -r apk; do
      scan_one "$apk"
    done < <(find "$target" -maxdepth 3 -type f -name '*.apk')
  else
    scan_one "$target"
  fi
done
