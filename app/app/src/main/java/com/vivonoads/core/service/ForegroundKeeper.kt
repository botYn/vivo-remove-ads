package com.vivonoads.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.vivonoads.core.R
import com.vivonoads.core.ui.MainActivity

/**
 * 前台保活服务。
 *
 * 常驻一条低优先级通知，防止系统在后台回收无障碍服务的宿主进程。
 */
class ForegroundKeeper : Service() {

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val channelId = "keeper"
        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "服务保活",
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = "保持广告拦截服务常驻"
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }

        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText("广告拦截服务运行中")
            .setSmallIcon(R.drawable.ic_stat_noads)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 0x4E41
    }
}
