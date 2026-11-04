# Vivo 去广告（Android App）

基于 Android 无障碍服务（AccessibilityService）的广告拦截应用，目标是自动关闭
应用内的**弹窗广告、开屏广告与摇一摇广告**，思路与「李跳跳」同类工具一致，
但规则引擎与实现为本项目自主编写。

## 功能

| 能力 | 说明 |
| --- | --- |
| 弹窗/开屏广告自动跳过 | 监听窗口变化，定位「关闭 / 跳过 / ×」按钮并自动点击 |
| 摇一摇广告防护 | 加速度传感器识别摇动，命中广告页时吞掉手势并关闭窗口 |
| 广告容器隐藏 | 按 viewId 特征隐藏整块广告容器（开屏、Banner、信息流卡片） |
| 规则管理 | 内置规则可单独开关，支持自定义规则 JSON 导入 / 导出 |
| 拦截日志 | 实时查看命中记录，用于排查与调优 |
| 前台保活 | 常驻通知防止系统回收，支持开机自启 |

## 架构

```
com.vivonoads.core
├── service/
│   ├── AdBlockAccessibilityService.kt   核心拦截引擎
│   ├── ForegroundKeeper.kt              前台保活服务
│   └── BootReceiver.kt                  开机自启
├── engine/
│   └── NodeMatcher.kt                   节点匹配（文本 / viewId / 类名）
├── rule/
│   ├── Rule.kt                          规则模型与匹配语义
│   ├── BuiltinRules.kt                  内置规则库
│   └── RuleRepository.kt                规则持久化（含导入导出）
├── shake/
│   └── ShakeDetector.kt                 摇一摇检测
├── log/
│   └── AdLog.kt                         内存日志缓冲
└── ui/
    ├── MainActivity.kt                  状态 / 开关 / 统计
    ├── RulesActivity.kt                 规则管理
    └── LogActivity.kt                   拦截日志
```

## 拦截流程

```
窗口事件 / 周期扫描（700ms 保底）
        ↓
获取前台窗口根节点（rootInActiveWindow，失败则退回 windows 列表）
        ↓
跳过自身应用、系统界面、桌面
        ↓
规则匹配：包名过滤 → 文本/viewId/类名条件（AND 组合）
        ↓
弱特征规则额外要求界面存在「广告」标记
        ↓
按得分选出最佳命中节点（可点击 > 有文本 > 层级浅）
        ↓
执行动作：点击关闭 / 隐藏容器 / 返回键
        ↓
1.5 秒冷却，避免重复点击
```

## 规则模型

一条规则由多维条件组成，条件组之间是 AND，组内 pattern 之间是 OR。

```kotlin
Rule(
    id = "vivo_earngold_ad_activity",
    name = "vivo-看视频赚金币广告页",
    packageNames = setOf("com.android.VideoPlayer"),
    classConditions = listOf(
        ClassCondition(listOf(".*earngold\\.EarnGoldAdActivity.*"), MatchType.REGEX),
    ),
    action = Action.PRESS_BACK,
)
```

### 强特征 vs 弱特征

- **强特征**（`关闭广告` / `跳过广告` / `×`）：广告场景专属，命中即执行。
- **弱特征**（`取消` / `返回` / `稍后再说`）：正常界面也常见，
  必须配合 `requiresAdContext = true`，即界面出现「广告」标记才执行，避免误点。

## 内置规则

| ID | 名称 | 说明 |
| --- | --- | --- |
| `generic_close_text` | 通用-关闭按钮文案(强特征) | 广告专属关闭文案 |
| `generic_close_text_weak` | 通用-关闭按钮文案(弱特征) | 需广告上下文 |
| `generic_ad_marker` | 通用-广告标记文本 | 移除纯「广告」标记容器 |
| `generic_ad_close_id` | 通用-广告SDK关闭按钮ID | GDT/优量汇/穿山甲/快手/百度 |
| `generic_splash_container` | 通用-开屏广告容器 | 隐藏整块容器 |
| `vivo_earngold_ad_activity` | vivo-看视频赚金币广告页 | 实测机型规则 |
| `vivo_browser_inline_ad` | vivo浏览器-内嵌广告位 | |
| `vivo_appstore_feed_ad` | vivo应用商店-信息流广告 | |

实测机型：vivo V2162A（vivo S12）/ Android 14 / OriginOS。

## 构建

```bash
cd app
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

要求：JDK 17+、Android SDK Platform 34、Build-Tools 34。

> 项目路径含中文时需在 `gradle.properties` 保留
> `android.overridePathCheck=true`。

也提供 GitHub Actions 工作流（`.github/workflows/build-apk.yml`）用于 CI 构建。

## 安装与启用

```bash
adb install -r app-debug.apk
```

1. 打开 App，点击「开启无障碍服务」
2. 在系统设置中找到「Vivo 去广告服务」并开启
3. 建议把 App 加入电池优化白名单

命令行启用（调试用）：

```bash
adb shell settings put secure enabled_accessibility_services \
  com.vivonoads.core/com.vivonoads.core.service.AdBlockAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

## 已知限制

- WebView 内的按钮能否被无障碍点击，取决于该 WebView 是否响应
  `ACTION_CLICK`，部分浏览器不响应。
- 信息流广告为懒加载，需要滚动触发内容变化事件才会被扫描到。
- 各大 App 的广告控件命名差异极大，通用规则无法覆盖全部，
  建议按需补充自定义规则。
- 厂商 ROM 升级后包名与控件 ID 可能变化，需重新采集。

## 免责声明

本项目仅用于个人设备上的广告拦截研究，不修改、不 Hook、不破解任何第三方应用。
