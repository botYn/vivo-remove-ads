#!/system/bin/sh
# 开机启动脚本
MODDIR=${0%/*}

. "$MODDIR/common/functions.sh"

print_line "Vivo 去广告模块 启动"

# 禁用广告包
print_line "扫描并禁用广告包"
disable_ad_packages

# 清理残留进程
print_line "结束广告进程"
kill_ad_processes

print_line "执行完毕"
