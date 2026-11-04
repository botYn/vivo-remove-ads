#!/system/bin/sh
# 公共 shell 函数库

# 打印分隔标题
print_line() {
  echo "======================================"
  echo "  $*"
  echo "======================================"
}

# 检查是否以 root 身份运行
check_root() {
  if [ "$(id -u)" != "0" ]; then
    echo "需要 root 权限，请在已 root 的环境中执行"
    exit 1
  fi
}

# 检查模块目录存在
check_module_dir() {
  if [ ! -d "$MODDIR" ]; then
    echo "模块目录不存在: $MODDIR"
    exit 1
  fi
}

# 列出设备上所有广告相关的包
list_ad_packages() {
  pm list packages 2>/dev/null | sed 's/^package://' | grep -Ei \
    'ads?|advert|gdt|sigmob|mobads|openadsdk|kwad|mbridge|mintegral|inmobi|applovin|smaato|vivo.*ad|bbk.*ad'
}

# 逐个禁用广告包
disable_ad_packages() {
  local pkgs
  pkgs=$(list_ad_packages)
  if [ -z "$pkgs" ]; then
    echo "未发现可禁用的广告包"
    return 0
  fi
  echo "$pkgs" | while read -r pkg; do
    [ -z "$pkg" ] && continue
    echo "  禁用: $pkg"
    pm disable-user --user 0 "$pkg" 2>/dev/null
  done
}

# 结束广告相关进程
kill_ad_processes() {
  pkill -f 'com\.qq\.e\.ads' 2>/dev/null
  pkill -f 'com\.baidu\.mobads' 2>/dev/null
  pkill -f 'openadsdk' 2>/dev/null
  pkill -f 'com\.sigmob' 2>/dev/null
  pkill -f 'com\.kwad\.sdk' 2>/dev/null
  pkill -f 'adview' 2>/dev/null
  return 0
}

# 屏蔽广告域名（写入 hosts）
BLOCKED_DOMAINS_FILE=/data/adblock/hosts
block_domains() {
  local domains="$1"
  mkdir -p /data/adblock 2>/dev/null
  if [ ! -f "$BLOCKED_DOMAINS_FILE" ]; then
    : > "$BLOCKED_DOMAINS_FILE"
  fi
  echo "$domains" | tr ',' '\n' | while read -r d; do
    [ -z "$d" ] && continue
    grep -qF "0.0.0.0 $d" "$BLOCKED_DOMAINS_FILE" 2>/dev/null || \
      echo "0.0.0.0 $d" >> "$BLOCKED_DOMAINS_FILE"
  done
  echo "已屏蔽域名写入: $BLOCKED_DOMAINS_FILE"
}

# 恢复 hosts
unblock_domains() {
  rm -f "$BLOCKED_DOMAINS_FILE"
  echo "已清除域名屏蔽文件"
}
