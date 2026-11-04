package com.vivonoads.core.rule

/**
 * 内置规则库。
 *
 * 规则来源：
 *  - 通用文案规则（关闭 / 跳过 / 稍后再说 等），覆盖绝大多数开屏与插屏广告
 *  - 通用 viewId 规则，覆盖主流广告 SDK（GDT / 优量汇 / 穿山甲 / 快手 / 百度 等）
 *  - vivo 系统实测规则（来自 V2162A / Android 14 / OriginOS 4 的 dumpsys 实测）
 */
object BuiltinRules {

    /** 关闭按钮的通用文案 */
    private val CLOSE_TEXTS = listOf(
        "关闭", "关闭广告", "跳过", "跳过广告", "跳过此广告", "不再显示",
        "取消", "取消广告", "我知道了", "知道了", "稍后再说", "以后再说",
        "暂不", "暂不开通", "残忍拒绝", "残忍离开", "继续浏览", "返回",
        "忽略", "忽略此广告", "暂不需要", "下次再说", "稍后提醒",
        "×", "✕", "✖", "X", "Close", "close", "Skip", "skip", "Cancel", "cancel",
    )

    /** 广告相关的通用 viewId 关键词 */
    private val AD_ID_KEYWORDS = listOf(
        "ad_close", "ad_skip", "close_ad", "skip_ad", "btn_close", "btn_skip",
        "iv_close", "iv_skip", "tv_close", "tv_skip", "btn_ad_close",
        "ad_dismiss", "ad_dismiss_btn", "splash_skip", "splash_close",
        "interstitial_close", "banner_close", "float_close", "feedback_close",
    )

    /** 通用文案：明显的广告标记 */
    private val AD_MARK_TEXTS = listOf(
        "广告", "廣告", "广告位", "赞助内容", "推广", "promotion", "sponsored",
        "Advertisement", "advertisement", "Ad", "ADS",
    )

    private fun closeTextRule(
        id: String,
        name: String,
        packages: Set<String> = emptySet(),
        action: Action = Action.CLICK_CLOSE,
        note: String = "",
    ) = Rule(
        id = id,
        name = name,
        packageNames = packages,
        textConditions = listOf(TextCondition(CLOSE_TEXTS, MatchType.EXACT)),
        action = action,
        builtin = true,
        note = note,
    )

    private fun idRule(
        id: String,
        name: String,
        packages: Set<String> = emptySet(),
        action: Action = Action.CLICK_CLOSE,
        note: String = "",
    ) = Rule(
        id = id,
        name = name,
        packageNames = packages,
        idConditions = listOf(IdCondition(AD_ID_KEYWORDS, MatchType.CONTAINS)),
        action = action,
        builtin = true,
        note = note,
    )

    /**
     * 全部内置规则。
     */
    val all: List<Rule> = listOf(

        // ---------------- 通用：文案关闭按钮 ----------------
        Rule(
            id = "generic_close_text",
            name = "通用-关闭按钮文案",
            textConditions = listOf(TextCondition(CLOSE_TEXTS, MatchType.EXACT)),
            action = Action.CLICK_CLOSE,
            builtin = true,
            note = "匹配「关闭 / 跳过 / 稍后再说」等常见广告关闭文案",
        ),

        // ---------------- 通用：广告标记文本 + 父容器移除 ----------------
        Rule(
            id = "generic_ad_marker",
            name = "通用-广告标记文本",
            textConditions = listOf(TextCondition(AD_MARK_TEXTS, MatchType.EXACT)),
            action = Action.REMOVE_VIEW,
            builtin = true,
            note = "命中纯「广告」标记文本时移除其所在容器",
        ),

        // ---------------- 通用：SDK 关闭按钮 id ----------------
        idRule(
            id = "generic_ad_close_id",
            name = "通用-广告SDK关闭按钮ID",
            note = "覆盖 GDT/优量汇/穿山甲/快手/百度 等主流 SDK 的关闭控件",
        ),

        // ---------------- 通用：插屏/开屏容器隐藏 ----------------
        Rule(
            id = "generic_splash_container",
            name = "通用-开屏广告容器",
            idConditions = listOf(
                IdCondition(
                    listOf(
                        "splash_ad", "ad_splash", "splash_container", "splash_ad_container",
                        "ad_container", "ad_layout", "interstitial_container",
                        "full_screen_ad", "ad_full_screen", "ad_dialog",
                    ),
                    MatchType.CONTAINS,
                ),
            ),
            action = Action.HIDE_CONTAINER,
            builtin = true,
            note = "隐藏整块开屏/插屏广告容器",
        ),

        // ---------------- vivo 系统实测规则 ----------------
        // 实测来源：V2162A (vivo S12) / Android 14 / OriginOS，dumpsys package 全量扫描
        Rule(
            id = "vivo_earngold_ad_activity",
            name = "vivo-看视频赚金币广告页",
            packageNames = setOf("com.android.VideoPlayer"),
            classConditions = listOf(
                ClassCondition(
                    listOf(".*earngold\\.EarnGoldAdActivity.*"),
                    MatchType.REGEX,
                ),
            ),
            action = Action.PRESS_BACK,
            builtin = true,
            note = "实测发现：com.vivo.video.online.earngold.EarnGoldAdActivity",
        ),

        // vivo 浏览器：内容页内嵌广告
        Rule(
            id = "vivo_browser_inline_ad",
            name = "vivo浏览器-内嵌广告位",
            packageNames = setOf("com.vivo.browser"),
            idConditions = listOf(
                IdCondition(
                    listOf("ad_view", "ad_container", "ad_layout", "banner_ad", "splash_ad"),
                    MatchType.CONTAINS,
                ),
            ),
            action = Action.HIDE_CONTAINER,
            builtin = true,
            note = "隐藏浏览器内容页中的广告容器",
        ),

        // vivo 应用商店 / 信息流
        Rule(
            id = "vivo_appstore_feed_ad",
            name = "vivo应用商店-信息流广告",
            packageNames = setOf("com.bbk.appstore", "com.vivo.vhome", "com.vivo.upslide"),
            idConditions = listOf(
                IdCondition(
                    listOf("ad_item", "ad_cell", "feed_ad", "ad_card", "ad_container"),
                    MatchType.CONTAINS,
                ),
            ),
            action = Action.HIDE_CONTAINER,
            builtin = true,
            note = "隐藏应用商店与负一屏信息流中的广告卡片",
        ),

        // ---------------- 摇一摇广告 ----------------
        // 摇一摇广告通常没有可见的关闭按钮，需要在用户摇动时"吞掉"手势
        Rule(
            id = "shake_ad_container",
            name = "摇一摇广告-容器隐藏",
            idConditions = listOf(
                IdCondition(
                    listOf("shake_ad", "ad_shake", "shake_banner", "shake_container"),
                    MatchType.CONTAINS,
                ),
            ),
            action = Action.HIDE_CONTAINER,
            builtin = true,
            note = "摇一摇广告的容器控件",
        ),
    )

    /**
     * 摇一摇广告常见的 Activity / 组件包名，用于加速识别。
     * 这些通常是厂商或第三方投放 SDK 的独立进程。
     */
    val SHAKE_RELATED_PACKAGES: Set<String> = setOf(
        "com.vivo.vms",
        "com.vivo.pushservice",
        "com.vivo.advertising",
        "com.bbk.advertising",
        "com.vivo.videoplayer",
        "com.vivo.hiboard",
        "com.vivo.upslide",
    )
}
