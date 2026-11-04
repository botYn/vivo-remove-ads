package com.vivonoads.core.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
    private var lastContentScanTime = 0L

    /** 扫描代际：窗口切换后旧的重试任务自动失效 */
    private var scanGeneration = 0

    /** 动作冷却表：key = 包名#规则ID */
    private val lastActionTimes = mutableMapOf<String, Long>()

    /** 诊断日志限流 */
    private var lastDiagTime = 0L

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
            // 必须清空包名白名单，否则空值会被当作"只监听空包名"，导致收不到任何事件
            packageNames = null
            notificationTimeout = 100
        }

        // 摇一摇检测
        shakeDetector = ShakeDetector(this) { shakeInfo ->
            onShakeDetected(shakeInfo)
        }.also { it.start() }

        AdLog.i(TAG, "无障碍服务已连接")
        notifyStatus(true)

        // 周期保底扫描：事件驱动会漏掉"无窗口变化但广告已渲染"的场景
        handler.postDelayed(periodicScanTask, PERIODIC_SCAN_MS)
    }

    /** 周期扫描任务：只在前台是第三方应用时执行，降低开销 */
    private val periodicScanTask = object : Runnable {
        override fun run() {
            try {
                if (repo.isGlobalEnabled()) {
                    val root = rootInActiveWindow
                    val pkg = root?.packageName?.toString()
                    if (pkg != null && shouldScan(pkg)) {
                        scanWindow(pkg)
                    }
                }
            } catch (_: Throwable) {
                // 静默忽略，避免打断周期任务
            }
            handler.postDelayed(this, PERIODIC_SCAN_MS)
        }
    }

    /**
     * 判断某个包是否需要扫描。
     *
     * 跳过自身、系统界面与桌面，避免误点自家控件与无谓开销。
     */
    private fun shouldScan(pkg: String): Boolean {
        if (pkg == selfPackage) return false
        if (pkg == "com.android.systemui") return false
        if (SKIP_PACKAGES.contains(pkg)) return false
        return true
    }

    /** 自身包名 */
    private val selfPackage: String by lazy { applicationContext.packageName }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!repo.isGlobalEnabled()) return

        // 便于排查：记录窗口切换事件
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            AdLog.d(TAG, "窗口事件: ${event.packageName}/${event.className}")
        }

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

                // 同一窗口也允许重新扫描（开屏广告会在窗口内延迟出现），
                // 仅做时间节流，避免事件风暴。
                val now = System.currentTimeMillis()
                if (windowId == lastProcessedWindow && now - lastProcessTime < 400L) return
                lastProcessedWindow = windowId
                lastProcessTime = now

                if (!shouldScan(pkg)) return

                // 开屏/插屏广告通常在窗口出现后延迟绘制，单次扫描必然漏判，
                // 这里在同一窗口上安排多轮重试扫描。
                scheduleScans(pkg)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                // 限流，避免频繁全树扫描
                val now = System.currentTimeMillis()
                if (now - lastContentScanTime < THROTTLE_MS) return
                lastContentScanTime = now
                scheduleScans(pkg, retry = false)
            }
        }
    }

    /**
     * 在当前窗口上安排扫描。
     *
     * @param retry true 时按 [RETRY_DELAYS_MS] 多次重试，用于捕获延迟渲染的广告
     */
    private fun scheduleScans(packageName: String, retry: Boolean = true) {
        val generation = ++scanGeneration
        val delays = if (retry) RETRY_DELAYS_MS else longArrayOf(DELAY_SCAN_MS)

        for (delay in delays) {
            handler.postDelayed({
                // 窗口已切换则放弃这一代任务
                if (generation == scanGeneration) {
                    scanWindow(packageName)
                }
            }, delay)
        }
    }

    /**
     * 扫描当前窗口的节点树并尝试拦截。
     */
    private fun scanWindow(packageName: String) {
        try {
            // 自身应用与系统界面不参与拦截，避免误点自家控件
            if (!shouldScan(packageName)) return

            val root = obtainRoot()
            if (root == null) {
                val now = System.currentTimeMillis()
                if (now - lastDiagTime > DIAG_INTERVAL_MS) {
                    lastDiagTime = now
                    AdLog.w(TAG, "无法获取窗口根节点 (pkg=$packageName)")
                }
                return
            }

            // 只在目标包确实在前台时才处理
            val rootPkg = root.packageName?.toString()
            if (rootPkg != null && rootPkg != packageName && !rootPkg.startsWith(packageName)) {
                // 根节点属于另一个包（例如系统弹窗），仍然继续，但用根包名做规则匹配
            }

            val rules = repo.activeRules()
            if (rules.isEmpty()) return

            val match = NodeMatcher.findBestMatch(root, rules, rootPkg ?: packageName)
            if (match == null) {
                // 诊断日志：限流输出，便于判断是"没匹配上"还是"没扫描"
                val now = System.currentTimeMillis()
                if (now - lastDiagTime > DIAG_INTERVAL_MS) {
                    lastDiagTime = now
                    AdLog.d(
                        TAG,
                        "扫描无命中: pkg=${rootPkg ?: packageName} 规则数=${rules.size} " +
                            "节点=${NodeMatcher.countNodes(root)}",
                    )
                }
                return
            }

            // 弱特征规则需要界面存在"广告"标记才执行，避免正常界面误点
            if (match.rule.requiresAdContext && !NodeMatcher.hasAdContext(root)) {
                return
            }

            // 防抖：同一包 + 同一规则在短时间内只执行一次，避免重复点击
            val key = "${rootPkg ?: packageName}#${match.rule.id}"
            val now = System.currentTimeMillis()
            val last = lastActionTimes[key]
            if (last != null && now - last < ACTION_COOLDOWN_MS) {
                return
            }
            lastActionTimes[key] = now

            val ok = applyAction(match, packageName)
            if (!ok) {
                AdLog.w(
                    TAG,
                    "动作未生效: 规则=${match.rule.name} 动作=${match.action} " +
                        "节点=${describe(match.node)}",
                )
            }
        } catch (t: Throwable) {
            AdLog.w(TAG, "扫描异常: ${t.message}")
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
                    val removed = try {
                        parent?.performAction(AccessibilityNodeInfo.ACTION_COLLAPSE) == true
                    } catch (_: Exception) {
                        false
                    }
                    val hidden = hideContainer(node, match, packageName)
                    AdLog.i(TAG, "[${match.rule.name}] 移除/隐藏: $desc (removed=$removed hidden=$hidden)")
                    removed || hidden
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
     *
     * AccessibilityNodeInfo 没有公开的 setVisibility 接口，这里通过反射调用
     * View.setVisibility(GONE)；拿不到 View 时退化为点击或折叠。
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

    /**
     * 通过反射把节点对应的 View 设置为 GONE。
     */
    private fun setGone(node: AccessibilityNodeInfo): Boolean {
        val view = nodeView(node) ?: return false
        return try {
            view.visibility = View.GONE
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 取出 AccessibilityNodeInfo 对应的 View 对象。
     *
     * Android 隐藏 API：node.getView() 在 API 30+ 已移除，
     * 这里通过反射调用 mView 字段获取。
     */
    private fun nodeView(node: AccessibilityNodeInfo): View? {
        return try {
            val method = node.javaClass.getDeclaredMethod("getView")
            method.isAccessible = true
            method.invoke(node) as? View
        } catch (_: Throwable) {
            try {
                val field = node.javaClass.getDeclaredField("mView")
                field.isAccessible = true
                field.get(node) as? View
            } catch (_: Throwable) {
                null
            }
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

    /**
     * 获取前台窗口根节点。
     *
     * 优先用 rootInActiveWindow；拿不到时退回 windows 列表中最上层窗口。
     */
    private fun obtainRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        return try {
            windows
                .sortedByDescending { it.layer }
                .firstOrNull { it.isActive }
                ?.root
        } catch (_: Throwable) {
            null
        }
    }

    private fun describe(node: AccessibilityNodeInfo): String {
        val pkgName = node.packageName?.toString() ?: "-"
        val id = node.viewIdResourceName?.substringAfterLast('/') ?: "-"
        val text = node.text?.toString()?.take(20) ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: "-"
        return "[$pkgName/$cls] id=$id text='$text'"
    }

    override fun onInterrupt() {
        AdLog.i(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        shakeDetector?.stop()
        handler.removeCallbacksAndMessages(null)
        lastActionTimes.clear()
        notifyStatus(false)
        super.onDestroy()
    }

    private fun notifyStatus(connected: Boolean) {
        try {
            val intent = Intent(ACTION_STATUS_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_CONNECTED, connected)
            // 用自定义权限限制广播只在本应用内流转
            sendBroadcast(intent, packageName + ".PERMISSION")
        } catch (_: Exception) {
            // 忽略
        }
    }

    companion object {
        private const val TAG = "NoAdsSvc"

        /** 窗口事件后延迟扫描，等弹窗完全绘制 */
        private const val DELAY_SCAN_MS = 220L

        /** 内容变化事件的最小扫描间隔 */
        private const val THROTTLE_MS = 350L

        /**
         * 多轮重试扫描的时间点（毫秒）。
         * 开屏广告一般在 0.3~2.5 秒内出现，覆盖这段时间即可。
         */
        private val RETRY_DELAYS_MS = longArrayOf(220L, 500L, 900L, 1500L, 2400L, 3600L)

        /** 周期保底扫描间隔 */
        private const val PERIODIC_SCAN_MS = 700L

        /** 同一包同一规则的执行冷却时间 */
        private const val ACTION_COOLDOWN_MS = 1500L

        /** 诊断日志最小间隔 */
        private const val DIAG_INTERVAL_MS = 4000L

        /** 无需扫描的包（桌面、输入法、系统设置等） */
        private val SKIP_PACKAGES = setOf(
            "com.bbk.launcher2",
            "com.vivo.launcher",
            "com.android.settings",
            "com.sohu.inputmethod.sogou.vivo",
            "com.vivo.secime.service",
        )

        const val ACTION_STATUS_CHANGED = "com.vivonoads.core.STATUS_CHANGED"
        const val EXTRA_CONNECTED = "connected"
    }
}
