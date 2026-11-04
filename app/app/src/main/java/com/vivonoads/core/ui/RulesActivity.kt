package com.vivonoads.core.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vivonoads.core.R
import com.vivonoads.core.rule.BuiltinRules
import com.vivonoads.core.rule.Rule
import com.vivonoads.core.rule.RuleRepository

/**
 * 规则管理页：查看内置规则、切换启用状态、导入导出。
 */
class RulesActivity : AppCompatActivity() {

    private lateinit var repo: RuleRepository
    private lateinit var adapter: RuleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)

        repo = RuleRepository.get(this)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnExport).setOnClickListener { exportRules() }
        findViewById<View>(R.id.btnImport).setOnClickListener { importRules() }
        findViewById<View>(R.id.btnClearRules).setOnClickListener {
            repo.clearUserRules()
            adapter.submit(buildList())
            Toast.makeText(this, "已清空用户规则", Toast.LENGTH_SHORT).show()
        }

        val list = findViewById<RecyclerView>(R.id.ruleList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = RuleAdapter(buildList())
        list.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        adapter.submit(buildList())
    }

    /**
     * 组装内置规则 + 用户规则。
     */
    private fun buildList(): List<Rule> {
        val builtin = BuiltinRules.all.map { rule ->
            rule.copy(enabled = repo.isBuiltinEnabled(rule.id))
        }
        return builtin + repo.userRules()
    }

    private fun exportRules() {
        val json = repo.exportRules()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TEXT, json)
            putExtra(Intent.EXTRA_SUBJECT, "Vivo去广告-规则导出")
        }
        startActivity(Intent.createChooser(intent, "导出规则"))
    }

    private fun importRules() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        runCatching { startActivityForResult(intent, REQ_IMPORT) }
            .onFailure {
                Toast.makeText(this, "未找到文件选择器", Toast.LENGTH_SHORT).show()
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        val json = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull() ?: return

        runCatching {
            val count = repo.importRules(json)
            adapter.submit(buildList())
            Toast.makeText(this, "已导入 $count 条规则", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "导入失败：格式不正确", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 规则列表适配器。
     */
    inner class RuleAdapter(
        private val items: MutableList<Rule>,
    ) : RecyclerView.Adapter<RuleAdapter.VH>() {

        fun submit(newItems: List<Rule>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_rule, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val name: TextView = view.findViewById(R.id.ruleName)
            private val note: TextView = view.findViewById(R.id.ruleNote)
            private val scope: TextView = view.findViewById(R.id.ruleScope)
            private val toggle: SwitchCompat = view.findViewById(R.id.ruleToggle)

            fun bind(rule: Rule) {
                name.text = rule.name
                note.text = rule.note
                scope.text = if (rule.packageNames.isEmpty()) {
                    "作用域: 全部应用"
                } else {
                    "作用域: ${rule.packageNames.joinToString { it.substringAfterLast('.') }}"
                }

                toggle.setOnCheckedChangeListener(null)
                toggle.isChecked = rule.enabled
                toggle.setOnCheckedChangeListener { _, checked ->
                    if (rule.builtin) {
                        repo.setBuiltinEnabled(rule.id, checked)
                    } else {
                        repo.setRuleEnabled(rule.id, checked)
                    }
                }
            }
        }
    }

    companion object {
        private const val REQ_IMPORT = 1001
    }
}
