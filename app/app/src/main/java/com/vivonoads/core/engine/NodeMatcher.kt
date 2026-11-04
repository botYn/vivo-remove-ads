package com.vivonoads.core.engine

import android.view.accessibility.AccessibilityNodeInfo
import com.vivonoads.core.rule.Action
import com.vivonoads.core.rule.IdCondition
import com.vivonoads.core.rule.MatchType
import com.vivonoads.core.rule.Rule
import com.vivonoads.core.rule.TextCondition
import com.vivonoads.core.rule.matches

/**
 * 节点匹配器。
 *
 * 负责把一条 [Rule] 应用到一个 [AccessibilityNodeInfo] 上，判断是否命中。
 */
object NodeMatcher {

    /** 单个节点上可提取的全部文本 */
    fun nodeTexts(node: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()

        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }

        // 收集子节点的文本，广告容器往往自身没有文字
        collectChildTexts(node, out, depth = 0)

        return out.distinct()
    }

    private fun collectChildTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 3) return
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                continue
            } ?: continue

            child.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            child.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { out.add(it) }

            collectChildTexts(child, out, depth + 1)
            child.recycle()
        }
    }

    /** 节点自身的 viewId（完整形如 pkg:id/name） */
    fun nodeId(node: AccessibilityNodeInfo): String =
        node.viewIdResourceName ?: ""

    /** 节点类名 */
    fun nodeClassName(node: AccessibilityNodeInfo): String =
        node.className?.toString() ?: ""

    /**
     * 判断某个节点是否满足规则中的一条文本条件。
     *
     * 否定条件（negative=true）语义：所有文本都不匹配时返回 true。
     */
    private fun matchTextCondition(node: AccessibilityNodeInfo, cond: TextCondition): Boolean {
        val texts = nodeTexts(node)
        if (texts.isEmpty()) return cond.negative

        val anyHit = texts.any { text ->
            cond.patterns.any { p -> cond.matchType.matches(text, p) }
        }
        return if (cond.negative) !anyHit else anyHit
    }

    private fun matchIdCondition(node: AccessibilityNodeInfo, cond: IdCondition): Boolean {
        val id = nodeId(node)
        if (id.isEmpty()) return cond.negative

        val anyHit = cond.patterns.any { p -> cond.matchType.matches(id, p) }
        return if (cond.negative) !anyHit else anyHit
    }

    private fun matchClassCondition(node: AccessibilityNodeInfo, cond: com.vivonoads.core.rule.ClassCondition): Boolean {
        val cls = nodeClassName(node)
        if (cls.isEmpty()) return cond.negative

        val anyHit = cond.patterns.any { p -> cond.matchType.matches(cls, p) }
        return if (cond.negative) !anyHit else anyHit
    }

    /**
     * 规则是否命中该节点。
     *
     * 语义：规则内所有"非否定"条件组之间是 AND 关系。
     * 一组条件内部的多个 pattern 是 OR 关系。
     * 若规则没有定义任何条件，则视为不命中（避免误杀）。
     */
    fun matches(rule: Rule, node: AccessibilityNodeInfo): Boolean {
        val hasConditions = rule.textConditions.isNotEmpty() ||
            rule.idConditions.isNotEmpty() ||
            rule.classConditions.isNotEmpty()
        if (!hasConditions) return false

        // 每种类型：至少有一组条件成立（组内 pattern 是 OR）
        // 否定条件单独成立时视为该组通过
        if (rule.textConditions.isNotEmpty()) {
            val positive = rule.textConditions.filter { !it.negative }
            val negative = rule.textConditions.filter { it.negative }
            if (positive.isNotEmpty() && positive.none { matchTextCondition(node, it) }) return false
            if (negative.any { matchTextCondition(node, it) }) return false
        }

        if (rule.idConditions.isNotEmpty()) {
            val positive = rule.idConditions.filter { !it.negative }
            val negative = rule.idConditions.filter { it.negative }
            if (positive.isNotEmpty() && positive.none { matchIdCondition(node, it) }) return false
            if (negative.any { matchIdCondition(node, it) }) return false
        }

        if (rule.classConditions.isNotEmpty()) {
            val positive = rule.classConditions.filter { !it.negative }
            val negative = rule.classConditions.filter { it.negative }
            if (positive.isNotEmpty() && positive.none { matchClassCondition(node, it) }) return false
            if (negative.any { matchClassCondition(node, it) }) return false
        }

        return true
    }

    /**
     * 在整棵树中寻找最佳命中节点。
     *
     * 优先级：可点击的 > 有文本的 > 深度浅的。
     */
    fun findBestMatch(
        root: AccessibilityNodeInfo,
        rules: List<Rule>,
        packageName: String?,
    ): MatchResult? {
        var best: MatchResult? = null

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            for (rule in rules) {
                if (!rule.appliesTo(packageName)) continue
                if (!matches(rule, node)) continue

                val score = score(node, depth)
                if (best == null || score > best.score) {
                    best = MatchResult(rule, node, score, depth)
                }
            }

            for (i in 0 until node.childCount) {
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    continue
                } ?: continue
                walk(child, depth + 1)
                child.recycle()
            }
        }

        walk(root, 0)
        return best
    }

    /**
     * 给命中节点打分，分高者优先点击。
     */
    private fun score(node: AccessibilityNodeInfo, depth: Int): Int {
        var s = 100 - depth.coerceAtMost(20)

        if (node.isClickable) s += 50
        if (node.isVisibleToUser) s += 20

        val id = nodeId(node)
        if (id.contains("close", true) || id.contains("skip", true)) s += 60

        val text = node.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) s += 10
        if (text.length <= 6) s += 15

        // 精确的短文案（关闭/跳过）权重最高
        val exactClose = setOf("关闭", "跳过", "×", "✕", "X", "close", "skip", "取消")
        if (exactClose.any { it.equals(text, true) }) s += 80

        return s
    }
}

/**
 * 一次命中结果。
 */
data class MatchResult(
    val rule: Rule,
    val node: AccessibilityNodeInfo,
    val score: Int,
    val depth: Int,
) {
    val action: Action get() = rule.action
}
