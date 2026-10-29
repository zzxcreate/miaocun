package com.miaocun.app.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.miaocun.app.R

object Notifications {

    const val CHANNEL_FLOAT = "miaocun_float"
    const val CHANNEL_SAVE = "miaocun_save"
    const val ID_FLOAT = 1101
    const val ID_SAVE = 1102

    /** 结果通知用独立 ID：不能被 stopForeground(REMOVE) 一起清掉。 */
    const val ID_DONE = 1103

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_FLOAT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_FLOAT,
                    "悬浮窗",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "悬浮球运行状态"
                    setShowBadge(false)
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_SAVE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SAVE,
                    "保存进度",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "视频/图片保存进度与结果"
                    setShowBadge(false)
                }
            )
        }
    }

    fun builder(context: Context, channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_save)
            .setColor(0xFFFE2C55.toInt())
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)

    fun floatNotification(context: Context): NotificationCompat.Builder =
        builder(context, CHANNEL_FLOAT)
            .setContentTitle("秒存运行中")
            .setContentText("点悬浮球「存」即可保存当前视频或图片")
            .setOngoing(true)

    /**
     * 保存结果通知：会响一声、可点击、可划掉。
     * 用独立 ID（ID_DONE），因此不会被前台服务的 stopForeground 清除。
     */
    fun resultNotification(
        context: Context,
        title: String,
        text: String
    ): NotificationCompat.Builder =
        builder(context, CHANNEL_SAVE)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(false)
            .setSilent(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOngoing(false)
}
