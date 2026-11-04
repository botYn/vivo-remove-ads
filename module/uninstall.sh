#!/system/bin/sh
# 卸载清理脚本
MODDIR=${0%/*}

. "$MODDIR/common/functions.sh"

print_line "Vivo 去广告模块 卸载中"

unblock_domains
rm -rf /data/adblock 2>/dev/null

print_line "卸载完成，重启后完全生效"
