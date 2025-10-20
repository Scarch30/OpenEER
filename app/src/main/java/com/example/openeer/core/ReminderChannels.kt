package com.example.openeer.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import com.example.openeer.R

object ReminderChannels {
    const val CHANNEL_REMINDERS: String = "reminders"
    const val CHANNEL_ALARMS: String = "reminder_alarms"

    fun ensureCreated(ctx: Context) {
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = ctx.getString(R.string.notif_channel_reminders)
            val channel = NotificationChannel(
                CHANNEL_REMINDERS,
                channelName,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = ctx.getString(R.string.notif_channel_reminders_desc)
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)

            val alarmsChannel = NotificationChannel(
                CHANNEL_ALARMS,
                ctx.getString(R.string.notif_channel_alarms),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = ctx.getString(R.string.notif_channel_alarms_desc)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 400, 500, 400, 800)
                val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(alarmUri, attributes)
            }
            manager.createNotificationChannel(alarmsChannel)
        }
    }
}
