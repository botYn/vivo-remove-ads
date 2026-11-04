#!/system/bin/sh
# 安装脚本
MODDIR=${0%/*}
SKIPUNZIP=0

. "$MODDIR/common/functions.sh"

check_root
check_module_dir

print_line "Vivo 去广告模块 v1.0.0 安装中"

# 1. 创建工作目录
mkdir -p /data/adblock 2>/dev/null

# 2. 屏蔽常见广告域名
print_line "写入广告域名屏蔽表"
block_domains "gdt.qq.com,adservice.google.com,pagead2.googlesyndication.com,api.mob.com,cn.ad.xiaomi.com,ads.yiyan.com,adsdk.gome.com.cn,config.ad.gome.com.cn,partner.ad.360.cn,ads.union.jd.com,ad.xiaomi.com,track.alipay.com"

# 3. 赋予脚本权限
set_perm_recursive "$MODDIR" 0 0 0755 0644

print_line "安装完成，重启后生效"
