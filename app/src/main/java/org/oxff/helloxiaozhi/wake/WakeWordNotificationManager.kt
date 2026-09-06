package org.oxff.helloxiaozhi.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.oxff.helloxiaozhi.R
import org.oxff.helloxiaozhi.ui.MainActivity

/**
 * 唤醒词通知管理器：负责通知与前台服务管理。
 *
 * 职责：
 *  - 创建通知渠道
 *  - 构建前台服务通知
 *  - 启动前台服务
 *
 * 从 WakeWordService 拆分而来，专注于通知管理职责。
 */
class WakeWordNotificationManager(
    private val service: Service,
) {
    /**
     * 创建通知渠道
     */
    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                service.getString(R.string.wake_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = service.getString(R.string.wake_notification_channel_desc)
                setShowBadge(false)
            }
            service.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    /**
     * 启动前台服务并显示通知
     */
    fun startForegroundWithNotification() {
        val pendingIntent = PendingIntent.getActivity(
            service,
            0,
            Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            service,
            1,
            Intent(service, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(service.getString(R.string.wake_notification_title))
            .setContentText(service.getString(R.string.wake_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_delete,
                service.getString(R.string.wake_notification_stop),
                stopIntent,
            )
            .setOngoing(true)
            .build()

        service.startForeground(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "wake_word_detection"
        private const val NOTIFICATION_ID = 1001
    }
}
