# vivo 去广告 APK

一套用于清理 vivo / iQOO 系统中预置广告的工程化工具：包含一个 Magisk / LSPosed 模块，以及一个基于 apktool 的 APK 广告组件剥离脚本。

> 仅供研究学习使用。请仅在自己拥有或已获授权的设备上操作本项目。

## 项目结构

```
.
├── module/                 Magisk / LSPosed 模块
│   ├── module.prop         模块元信息
│   ├── customize.sh        安装脚本（安装时执行）
│   ├── service.sh          启动脚本（开机执行）
│   ├── uninstall.sh        卸载清理脚本
│   └── common/
│       └── functions.sh    公共 shell 函数库
├── tools/
│   ├── apk_ad_strip.py     APK 广告组件剥离工具（apktool）
│   └── requirements.txt
├── scripts/
│   ├── build_module.sh     打包 Magisk 模块 zip
│   └── scan_ads.sh         批量扫描 APK 中的广告组件
├── docs/
│   ├── 使用指南.md
│   └── 常见问题.md
└── README.md
```

## 快速开始

### 1. 扫描 APK 中的广告组件

```bash
bash scripts/scan_ads.sh /path/to/app.apk
```

或直接用 Python 工具列出候选广告组件：

```bash
pip install -r tools/requirements.txt
python tools/apk_ad_strip.py --list /path/to/app.apk
```

### 2. 剥离并重打包

```bash
python tools/apk_ad_strip.py --strip --apk /path/to/app.apk -o out/
python tools/apk_ad_strip.py --strip --apk /path/to/app.apk -o out/ --sign
```

`--sign` 会自动使用 `apksigner` + 调试密钥对输出 APK 签名，方便直接安装测试。

### 3. 打包 Magisk 模块

```bash
bash scripts/build_module.sh
# 产物：dist/vivo-remove-ads-<version>.zip
```

在已 root（Magisk / KernelSU / APatch）的设备上刷入该 zip 即可。

## 广告组件识别规则

`tools/apk_ad_strip.py` 通过以下维度定位广告组件：

| 维度 | 判定依据 |
| --- | --- |
| 包名 | 常见的广告 SDK 前缀（`com.qq.e.ads`、`com.bytedance.sdk.openadsdk`、`com.baidu.mobads` 等） |
| 类名 | 资源名 / 活动名中含 `ad` / `ads` / `advert` / `gdt` / `splash` / `banner` / `interstitial` |
| 布局 | 布局文件根节点同时包含 `AdView` / `AdBanner` / `AdSlot` 等关键词 |
| 接口 | 继承 `com.google.android.gms.ads` 或 `com.qq.e.ads` 的类 |

## 环境要求

- Python 3.8+
- JDK 17（apktool / apksigner）
- [apktool](https://ibotpeaches.github.io/Apktool/) ≥ 2.9
- [Android SDK Build-Tools](https://developer.android.com/tools/releases/build-tools)（提供 `apksigner` 与 `zipalign`）

## 许可

MIT，见 [LICENSE](LICENSE)。
