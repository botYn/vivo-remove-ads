package com.vivonoads.core.rule

/**
 * 单条拦截规则。
 *
 * 一条规则描述"如何识别一个广告元素"，由若干个 [Condition] 组合而成。
 * 全部条件满足即视为命中（AND 语义）。
 */
data class Rule(
    /** 规则 ID，用于持久化与用户自定义 */
    val id: String,
    /** 规则名称，展示在 UI 上 */
    val name: String,
    /** 作用范围：命中哪些包名（空集合表示不限定包名） */
    val packageNames: Set<String> = emptySet(),
    /** 文本匹配条件 */
    val textConditions: List<TextCondition> = emptyList(),
    /** viewId 匹配条件 */
    val idConditions: List<IdCondition> = emptyList(),
    /** 类名匹配条件 */
    val classConditions: List<ClassCondition> = emptyList(),
    /** 命中后的动作 */
    val action: Action = Action.CLICK_CLOSE,
    /** 规则启用状态 */
    val enabled: Boolean = true,
    /** 内置规则不可删除 */
    val builtin: Boolean = false,
    /** 备注说明 */
    val note: String = "",
) {

    /**
     * 判断规则是否限定了作用包名。
     */
    fun appliesTo(packageName: String?): Boolean {
        if (packageNames.isEmpty()) return true
        if (packageName == null) return false
        return packageNames.any { packageName == it || packageName.startsWith("$it.") }
    }
}

/**
 * 命中后执行的动作。
 */
enum class Action {
    /** 点击命中元素的关闭按钮（找"关闭"/"跳过"/"X"等），并尝试隐藏整个弹窗 */
    CLICK_CLOSE,

    /** 直接从父容器移除该 View */
    REMOVE_VIEW,

    /** 通过返回键关闭当前窗口 */
    PRESS_BACK,

    /** 隐藏整个广告容器（设 GONE） */
    HIDE_CONTAINER,
}

/**
 * 文本匹配条件。
 *
 * @param patterns 匹配的模式
 * @param matchType 匹配方式
 * @param negative 是否为否定条件（"不含这些文本"也算命中）
 */
data class TextCondition(
    val patterns: List<String>,
    val matchType: MatchType = MatchType.CONTAINS,
    val negative: Boolean = false,
)

/**
 * viewId 匹配条件。
 */
data class IdCondition(
    val patterns: List<String>,
    val matchType: MatchType = MatchType.REGEX,
    val negative: Boolean = false,
)

/**
 * 类名匹配条件。
 */
data class ClassCondition(
    val patterns: List<String>,
    val matchType: MatchType = MatchType.REGEX,
    val negative: Boolean = false,
)

/**
 * 匹配方式。
 */
enum class MatchType {
    /** 完全相等 */
    EXACT,

    /** 包含子串 */
    CONTAINS,

    /** 正则匹配 */
    REGEX,
}

/**
 * 对一个字符串执行 [MatchType] 匹配。
 */
fun MatchType.matches(text: String, pattern: String): Boolean = when (this) {
    MatchType.EXACT -> text == pattern
    MatchType.CONTAINS -> text.contains(pattern, ignoreCase = true)
    MatchType.REGEX -> try {
        Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
    } catch (_: Exception) {
        false
    }
}
