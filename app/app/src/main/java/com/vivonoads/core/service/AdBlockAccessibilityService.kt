package com.vivonoads.core.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.vivonoads.core.engine.MatchResult
import com.vivonoads.core.engine.NodeMatcher
import com.vivonoads.core.log.AdLog
import com.vivonoads.core.rule.Action
import com.vivonoads.core.rule.BuiltinRules
import com.vivonoads.core.rule.RuleRepository
import com.vivonoads.core.shake.ShakeDetector

/**
 * 无障碍服务：拦截弹窗广告与摇一摇广告。
 *
 * 拦截手段：
 *  1. 窗口事件（TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOW_CONTENT_CHANGED）时扫描整棵树
 *  2. 命中关闭按钮 → 直接点击
 *  3. 命中广告容器 → 设为 GONE
 *  4. 摇一摇检测 → 吞掉摇动手势，屏蔽广告窗口
 */
class AdBlockAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var repo: RuleRepository
    private var shakeDetector: ShakeDetector? = null

    /** 正在处理的窗口，避免重复扫描 */
    private var lastProcessedWindow: String? = null
    private var lastProcessTime = 0L

    /** 记录被屏蔽过的广告窗口，供摇一摇逻辑判断 */
    private val blockedWindows = mutableSetOf<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()

        repo = RuleRepository.get(this)

        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

        // 摇一摇检测
        shakeDetector = ShakeDetector(this) { shakeInfo ->
            onShakeDetected(shakeInfo)
        }.also { it.start() }

        AdLog.i(TAG, "无障碍服务已连接")
        notifyStatus(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!repo.isGlobalEnabled()) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                val windowId = "${pkg}#${event.windowId}"

                // 摇一摇广告窗口：直接拦截，不做节点匹配
                if (isShakeAdWindow(pkg)) {
                    if (blockedWindows.add(windowId)) {
                        AdLog.i(TAG, "拦截摇一摇广告窗口: $pkg")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                    return
                }

                if (windowId == lastProcessedWindow) return
                lastProcessedWindow = windowId
                lastProcessTime = System.currentTimeMillis()

                handler.postDelayed({ scanWindow(pkg) }, DELAY_SCAN_MS)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                // 限流，避免频繁全树扫描
                val now = System.currentTimeMillis()
                if (now - lastProcessTime < THROTTLE_MS) return
                lastProcessTime = now
                handler.postDelayed({ scanWindow(pkg) }, DELAY_SCAN_MS)
            }
        }
    }

    /**
     * 扫描当前窗口的节点树并尝试拦截。
     */
    private fun scanWindow(packageName: String) {
        try {
            val root = rootInActiveWindow ?: return

            val rules = repo.activeRules()
            if (rules.isEmpty()) return

            val match = NodeMatcher.findBestMatch(root, rules, packageName) ?: run {
                root.recycle()
                return
            }

            val applied = applyAction(match, packageName)
            root.recycle()

            if (applied) {
                lastProcessedWindow = null // 允许重新扫描
            }
        } catch (t: Throwable) {
            Log.w(TAG, "扫描异常", t)
        }
    }

    /**
     * 执行命中规则对应的动作。
     */
    private fun applyAction(match: MatchResult, packageName: String): Boolean {
        val node = match.node
        val desc = describe(node)

        return try {
            when (match.action) {
                Action.CLICK_CLOSE -> {
                    if (clickNode(node)) {
                        AdLog.i(TAG, "[${match.rule.name}] 点击关闭: $desc")
                        true
                    } else {
                        // 点击失败则退化为隐藏容器
                        hideContainer(node, match, packageName)
                    }
                }

                Action.REMOVE_VIEW -> {
                    val parent = node.parent
                    val target = parent ?: node
                    val ok = target.performAction(AccessibilityAction.ACTION_SET_PROGRESS)
                    val removed = try {
                        parent?.performAction(AccessibilityAction.ACTION_COLLAPSE)
                    } catch (_: Exception) {
                        false
                    }
                    val hidden = hideContainer(node, match, packageName)
                    AdLog.i(TAG, "[${match.rule.name}] 移除/隐藏: $desc (hidden=$hidden)")
                    ok || removed || hidden
                }

                Action.PRESS_BACK -> {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    AdLog.i(TAG, "[${match.rule.name}] 返回键关闭: $desc")
                    true
                }

                Action.HIDE_CONTAINER -> {
                    hideContainer(node, match, packageName)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "执行动作失败", t)
            false
        }
    }

    /**
     * 隐藏节点或其最近的容器父节点。
     */
    private fun hideContainer(node: AccessibilityNodeInfo, match: MatchResult, packageName: String): Boolean {
        val desc = describe(node)

        // 优先隐藏自己
        if (setGone(node)) {
            AdLog.i(TAG, "[${match.rule.name}] 隐藏节点: $desc")
            return true
        }

        // 隐藏最近的、尺寸较大的父容器
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 4) {
            if (parent.childCount <= 3 && setGone(parent)) {
                AdLog.i(TAG, "[${match.rule.name}] 隐藏容器: ${describe(parent)}")
                return true
            }
            parent = parent.parent
            depth++
        }

        return false
    }

    private fun setGone(node: AccessibilityNodeInfo): Boolean {
        return try {
            node.performAction(AccessibilityAction.ACTION_SET_PROGRESS)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 点击节点。
     */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            // 节点自身不可点击时，尝试点击最近的祖先
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 4) {
                if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                parent = parent.parent
                depth++
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 判断是否为摇一摇广告窗口。
     */
    private fun isShakeAdWindow(packageName: String): Boolean {
        if (BuiltinRules.SHAKE_RELATED_PACKAGES.contains(packageName)) {
            // 这些包里既有正常功能也有广告，需要进一步判断
            // 只有当当前窗口标题/内容含广告关键词时才判定
            val root = rootInActiveWindow ?: return false
            val texts = NodeMatcher.nodeTexts(root)
            val hit = texts.any { t ->
                t.contains("摇一摇", true) || t.contains("摇动", true) ||
                    t.contains("抖一抖", true) || t.contains("广告", true)
            }
            root.recycle()
            return hit
        }
        return false
    }

    /**
     * 摇一摇事件回调。
     */
    private fun onShakeDetected(info: ShakeDetector.ShakeInfo) {
        if (!repo.isGlobalEnabled()) return

        val root = rootInActiveWindow ?: return
        val texts = NodeMatcher.nodeTexts(root)
        val pkg = root.packageName?.toString()

        // 当前页面含"摇一摇/广告"字样时，吞掉手势
        val shakeRelated = texts.any { t ->
            t.contains("摇一摇", true) || t.contains("摇动", true) ||
                t.contains("抖一抖", true) || t.contains("摇摇", true)
        }

        val adRelated = texts.any { t ->
            t.contains("广告", true) || t.contains("Advertisement", true) ||
                t.contains("领红包", true) || t.contains("抽奖", true) ||
                t.contains("福利", true)
        }

        if (shakeRelated || (adRelated && BuiltinRules.SHAKE_RELATED_PACKAGES.contains(pkg))) {
            AdLog.i(TAG, "吞掉摇一摇手势 (pkg=$pkg, shake=${info.strength})")
            // 吞掉手势：不做任何事，同时尝试关闭可能的广告浮窗
            shakeDetector?.swallow()
            performGlobalAction(GLOBAL_ACTION_BACK)
        } else {
            AdLog.d(TAG, "摇一摇 (pkg=$pkg)，当前页无广告特征，正常放行")
        }
        root.recycle()
    }

    private fun describe(node: AccessibilityNodeInfo): String {
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: "-"
        val text = node.text?.toString()?.take(20) ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "-"
        return "[$pkg/${cls}] id=$id text='$text'"
    }

    private val pkg: String get() = ""

    override fun onInterrupt() {
        AdLog.i(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        shakeDetector?.stop()
        handler.removeCallbacksAndMessages(null)
        notifyStatus(false)
        super.onDestroy()
    }

    private fun notifyStatus(connected: Boolean) {
        try {
            sendBroadcast(
                Intent(ACTION_STATUS_CHANGED)
                    .setPackage(packageName)
                    .putExtra(EXTRA_CONNECTED, connected),
            )
        } catch (_: Exception) {
            // 忽略
        }
    }

    companion object {
        private const val TAG = "NoAdsSvc"

        /** 窗口事件后延迟扫描，等弹窗完全绘制 */
        private const val DELAY_SCAN_MS = 220L

        /** 内容变化事件的最小间隔 */
        private const val THROTTLE_MS = 350L

        const val ACTION_STATUS_CHANGED = "com.vivonoads.core.STATUS_CHANGED"
        const val EXTRA_CONNECTED = "connected"
    }
}
