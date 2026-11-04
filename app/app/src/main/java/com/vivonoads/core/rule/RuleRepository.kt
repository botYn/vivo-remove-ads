package com.vivonoads.core.rule

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 规则仓库。
 *
 * 内置规则始终存在，用户规则以 JSON 形式持久化在 SharedPreferences 中。
 */
class RuleRepository private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val userRules = mutableListOf<Rule>()

    /** 全部生效规则（内置 + 启用的用户规则） */
    fun activeRules(): List<Rule> {
        val builtinEnabled = prefs.getBoolean(KEY_BUILTIN_ENABLED, true)
        val builtins = if (builtinEnabled) BuiltinRules.all else emptyList()
        return builtins + userRules.filter { it.enabled }
    }

    /** 全部用户规则（含禁用） */
    fun userRules(): List<Rule> = userRules.toList()

    /** 某个内置规则是否启用 */
    fun isBuiltinEnabled(ruleId: String): Boolean {
        if (BuiltinRules.all.none { it.id == ruleId }) return true
        return prefs.getBoolean("$KEY_BUILTIN_PREFIX$ruleId", true)
    }

    fun setBuiltinEnabled(ruleId: String, enabled: Boolean) {
        prefs.edit().putBoolean("$KEY_BUILTIN_PREFIX$ruleId", enabled).apply()
    }

    fun isGlobalEnabled(): Boolean = prefs.getBoolean(KEY_GLOBAL_ENABLED, true)

    fun setGlobalEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_GLOBAL_ENABLED, enabled).apply()
    }

    /**
     * 内置规则总开关是否开启。
     */
    fun prefsBuiltinEnabled(): Boolean = prefs.getBoolean(KEY_BUILTIN_ENABLED, true)

    /**
     * 设置内置规则总开关。
     */
    fun setBuiltinEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BUILTIN_ENABLED, enabled).apply()
    }

    fun addRule(rule: Rule) {
        userRules.add(rule)
        persist()
    }

    fun updateRule(rule: Rule) {
        val idx = userRules.indexOfFirst { it.id == rule.id }
        if (idx >= 0) {
            userRules[idx] = rule
            persist()
        }
    }

    fun removeRule(ruleId: String) {
        userRules.removeAll { it.id == ruleId }
        persist()
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        val idx = userRules.indexOfFirst { it.id == ruleId }
        if (idx >= 0) {
            userRules[idx] = userRules[idx].copy(enabled = enabled)
            persist()
        }
    }

    /** 清空所有用户规则 */
    fun clearUserRules() {
        userRules.clear()
        persist()
    }

    /** 从 JSON 导入规则 */
    fun importRules(json: String): Int {
        val arr = JSONArray(json)
        var count = 0
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            userRules.add(ruleFromJson(obj))
            count++
        }
        persist()
        return count
    }

    /** 导出全部用户规则为 JSON */
    fun exportRules(): String {
        val arr = JSONArray()
        userRules.forEach { arr.put(ruleToJson(it)) }
        return arr.toString(2)
    }

    private fun persist() {
        val arr = JSONArray()
        userRules.forEach { arr.put(ruleToJson(it)) }
        prefs.edit().putString(KEY_USER_RULES, arr.toString()).apply()
    }

    private fun load() {
        val raw = prefs.getString(KEY_USER_RULES, null) ?: return
        try {
            val arr = JSONArray(raw)
            userRules.clear()
            for (i in 0 until arr.length()) {
                userRules.add(ruleFromJson(arr.getJSONObject(i)))
            }
        } catch (_: Exception) {
            userRules.clear()
        }
    }

    private fun ruleToJson(rule: Rule): JSONObject = JSONObject().apply {
        put("id", rule.id)
        put("name", rule.name)
        put("packages", JSONArray(rule.packageNames.toList()))
        put("text", JSONArray(rule.textConditions.map { c ->
            JSONObject().apply {
                put("patterns", JSONArray(c.patterns))
                put("type", c.matchType.name)
                put("negative", c.negative)
            }
        }))
        put("ids", JSONArray(rule.idConditions.map { c ->
            JSONObject().apply {
                put("patterns", JSONArray(c.patterns))
                put("type", c.matchType.name)
                put("negative", c.negative)
            }
        }))
        put("classes", JSONArray(rule.classConditions.map { c ->
            JSONObject().apply {
                put("patterns", JSONArray(c.patterns))
                put("type", c.matchType.name)
                put("negative", c.negative)
            }
        }))
        put("action", rule.action.name)
        put("enabled", rule.enabled)
        put("note", rule.note)
    }

    private fun ruleFromJson(obj: JSONObject): Rule = Rule(
        id = obj.optString("id"),
        name = obj.optString("name"),
        packageNames = obj.optJSONArray("packages").toStringSet(),
        textConditions = obj.optJSONArray("text").toConditions(),
        idConditions = obj.optJSONArray("ids").toIdConditions(),
        classConditions = obj.optJSONArray("classes").toClassConditions(),
        action = try {
            Action.valueOf(obj.optString("action", "CLICK_CLOSE"))
        } catch (_: Exception) {
            Action.CLICK_CLOSE
        },
        enabled = obj.optBoolean("enabled", true),
        builtin = false,
        note = obj.optString("note"),
    )

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }.toSet()
    }

    private fun JSONArray?.toConditions(): List<TextCondition> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            TextCondition(
                patterns = o.optJSONArray("patterns").toStringSet().toList(),
                matchType = try {
                    MatchType.valueOf(o.optString("type", "CONTAINS"))
                } catch (_: Exception) {
                    MatchType.CONTAINS
                },
                negative = o.optBoolean("negative", false),
            )
        }
    }

    /** 读取 IdCondition 列表。 */
    private fun JSONArray?.toIdConditions(): List<IdCondition> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            IdCondition(
                patterns = o.optJSONArray("patterns").toStringSet().toList(),
                matchType = try {
                    MatchType.valueOf(o.optString("type", "CONTAINS"))
                } catch (_: Exception) {
                    MatchType.CONTAINS
                },
                negative = o.optBoolean("negative", false),
            )
        }
    }

    /** 读取 ClassCondition 列表。 */
    private fun JSONArray?.toClassConditions(): List<ClassCondition> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            val o = optJSONObject(i) ?: return@mapNotNull null
            ClassCondition(
                patterns = o.optJSONArray("patterns").toStringSet().toList(),
                matchType = try {
                    MatchType.valueOf(o.optString("type", "CONTAINS"))
                } catch (_: Exception) {
                    MatchType.CONTAINS
                },
                negative = o.optBoolean("negative", false),
            )
        }
    }

    companion object {
        private const val PREF_NAME = "vivo_no_ads_rules"
        private const val KEY_USER_RULES = "user_rules"
        private const val KEY_GLOBAL_ENABLED = "global_enabled"
        private const val KEY_BUILTIN_ENABLED = "builtin_enabled"
        private const val KEY_BUILTIN_PREFIX = "builtin_on_"

        @Volatile
        private var instance: RuleRepository? = null

        fun get(context: Context): RuleRepository =
            instance ?: synchronized(this) {
                instance ?: RuleRepository(context.applicationContext).also {
                    it.load()
                    instance = it
                }
            }
    }
}
