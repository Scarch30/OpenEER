package com.example.openeer.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.openeer.R

object ReminderChannels {
    const val CHANNEL_REMINDERS: String = "reminders"

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
        }
    }
}
