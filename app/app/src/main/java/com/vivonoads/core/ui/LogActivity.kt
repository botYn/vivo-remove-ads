package com.vivonoads.core.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vivonoads.core.R
import com.vivonoads.core.log.AdLog

/**
 * 拦截日志页。
 */
class LogActivity : AppCompatActivity() {

    private lateinit var adapter: LogAdapter
    private var empty: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnClearLog).setOnClickListener {
            AdLog.clear()
            adapter.submit(AdLog.snapshot())
        }

        empty = findViewById(R.id.emptyText)

        val list = findViewById<RecyclerView>(R.id.logList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = LogAdapter(AdLog.snapshot().toMutableList())
        list.adapter = adapter

        AdLog.onNewEntry = { entry ->
            runOnUiThread {
                adapter.insert(entry)
                updateEmpty()
            }
        }
    }

    override fun onDestroy() {
        AdLog.onNewEntry = null
        super.onDestroy()
    }

    private fun updateEmpty() {
        empty?.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    /**
     * 日志适配器。
     */
    inner class LogAdapter(
        items: MutableList<AdLog.Entry>,
    ) : RecyclerView.Adapter<LogAdapter.VH>() {

        private val items: MutableList<AdLog.Entry> = items.toMutableList()

        fun submit(newItems: List<AdLog.Entry>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
            updateEmpty()
        }

        fun insert(entry: AdLog.Entry) {
            items.add(0, entry)
            notifyItemInserted(0)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.bind(items[position])
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            private val time: TextView = view.findViewById(R.id.logTime)
            private val msg: TextView = view.findViewById(R.id.logMessage)

            fun bind(entry: AdLog.Entry) {
                time.text = entry.time
                msg.text = entry.message
            }
        }
    }
}
