package com.vivonoads.core.log

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 拦截日志。
 *
 * 保留最近 [MAX_ENTRIES] 条记录，供 UI 展示。
 */
object AdLog {

    private const val TAG = "NoAds"
    private const val MAX_ENTRIES = 300

    /** 一条日志 */
    data class Entry(
        val time: String,
        val level: String,
        val message: String,
    )

    private val entries = ArrayDeque<Entry>()
    private val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    /** 有新日志时的监听器 */
    var onNewEntry: ((Entry) -> Unit)? = null

    fun i(tag: String, message: String) {
        Log.i(TAG, "[$tag] $message")
        add("I", "$tag: $message")
    }

    fun d(tag: String, message: String) {
        Log.d(TAG, "[$tag] $message")
        add("D", "$tag: $message")
    }

    fun w(tag: String, message: String) {
        Log.w(TAG, "[$tag] $message")
        add("W", "$tag: $message")
    }

    private fun add(level: String, message: String) {
        val entry = Entry(formatter.format(Date()), level, message)
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
        }
        onNewEntry?.invoke(entry)
    }

    /**
     * 读取全部日志（最新在前）。
     */
    fun snapshot(): List<Entry> = synchronized(entries) {
        entries.toList().reversed()
    }

    /**
     * 清空日志。
     */
    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
