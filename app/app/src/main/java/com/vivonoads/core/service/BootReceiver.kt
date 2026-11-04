package com.vivonoads.core.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机广播接收器：开机后拉起保活服务。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching {
            context?.startForegroundService(Intent(context, ForegroundKeeper::class.java))
        }
    }
}
