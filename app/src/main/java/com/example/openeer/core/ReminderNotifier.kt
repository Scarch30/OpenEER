package com.example.openeer.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.openeer.R
import com.example.openeer.core.AlarmTonePlayer
import com.example.openeer.data.reminders.ReminderEntity
import com.example.openeer.receiver.ReminderReceiver
import com.example.openeer.receiver.ReminderReceiver.Companion.ACTION_MARK_DONE
import com.example.openeer.receiver.ReminderReceiver.Companion.ACTION_SNOOZE_10
import com.example.openeer.receiver.ReminderReceiver.Companion.ACTION_SNOOZE_5
import com.example.openeer.receiver.ReminderReceiver.Companion.ACTION_SNOOZE_60
import com.example.openeer.receiver.ReminderReceiver.Companion.ACTION_STOP_ALARM
import com.example.openeer.receiver.ReminderReceiver.Companion.EXTRA_NOTE_ID
import com.example.openeer.receiver.ReminderReceiver.Companion.EXTRA_OPEN_NOTE_ID
import com.example.openeer.receiver.ReminderReceiver.Companion.EXTRA_REMINDER_ID
import com.example.openeer.ui.MainActivity
import com.example.openeer.ui.alarm.AlarmAlertActivity

object ReminderNotifier {

    private const val REQUEST_CODE_CONTENT = 41002
    private const val REQUEST_CODE_SNOOZE_5 = 41003
    private const val REQUEST_CODE_SNOOZE_60 = 41004
    private const val REQUEST_CODE_MARK_DONE = 41005
    private const val REQUEST_CODE_SNOOZE_10 = 41006
    private const val REQUEST_CODE_STOP = 41007
    private const val REQUEST_CODE_FULL_SCREEN = 41008

    fun showReminder(
        ctx: Context,
        noteId: Long,
        reminderId: Long,
        label: String?,
        noteTitle: String?,
        preview: String?,
        delivery: String,
        triggerAt: Long,
    ) {
        if (delivery == ReminderEntity.DELIVERY_ALARM) {
            showAlarmReminder(ctx, noteId, reminderId, label, noteTitle, preview, triggerAt)
        } else {
            showNotificationReminder(ctx, noteId, reminderId, label, noteTitle, preview)
        }
    }

    private fun showNotificationReminder(
        ctx: Context,
        noteId: Long,
        reminderId: Long,
        label: String?,
        noteTitle: String?,
        preview: String?,
    ) {
        val notificationTitle = ctx.getString(R.string.notif_title_reminder)
        val resolvedTitle = notificationTitle.ifBlank { "Rappel OpenEER" }
        val notificationText = label?.takeIf { it.isNotBlank() } ?: when {
            !noteTitle.isNullOrBlank() -> ctx.getString(R.string.notif_text_note_title, noteTitle)
            !preview.isNullOrBlank() -> preview
            else -> ctx.getString(R.string.notif_text_no_title)
        }

        val contentIntent = Intent(ctx, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_NOTE_ID, noteId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val contentPendingIntent = PendingIntent.getActivity(
            ctx,
            REQUEST_CODE_CONTENT,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snooze5Intent = Intent(ctx, ReminderReceiver::class.java).apply {
            action = ACTION_SNOOZE_5
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_NOTE_ID, noteId)
        }
        val snooze5PendingIntent = PendingIntent.getBroadcast(
            ctx,
            REQUEST_CODE_SNOOZE_5,
            snooze5Intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snooze60Intent = Intent(ctx, ReminderReceiver::class.java).apply {
            action = ACTION_SNOOZE_60
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_NOTE_ID, noteId)
        }
        val snooze60PendingIntent = PendingIntent.getBroadcast(
            ctx,
            REQUEST_CODE_SNOOZE_60,
            snooze60Intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val markDoneIntent = Intent(ctx, ReminderReceiver::class.java).apply {
            action = ACTION_MARK_DONE
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_NOTE_ID, noteId)
        }
        val markDonePendingIntent = PendingIntent.getBroadcast(
            ctx,
            REQUEST_CODE_MARK_DONE,
            markDoneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(ctx, ReminderChannels.CHANNEL_REMINDERS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(resolvedTitle)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(0, ctx.getString(R.string.notif_action_snooze_5), snooze5PendingIntent)
            .addAction(0, ctx.getString(R.string.notif_action_snooze_60), snooze60PendingIntent)
            .addAction(0, ctx.getString(R.string.notif_action_mark_done), markDonePendingIntent)

        NotificationManagerCompat.from(ctx).notify(reminderId.toInt(), builder.build())
    }

    private fun showAlarmReminder(
        ctx: Context,
        noteId: Long,
        reminderId: Long,
        label: String?,
        noteTitle: String?,
        preview: String?,
        triggerAt: Long,
    ) {
        AlarmTonePlayer.start(ctx)

        val notificationTitle = ctx.getString(R.string.notif_title_reminder)
        val resolvedTitle = notificationTitle.ifBlank { "Rappel OpenEER" }
        val notificationText = label?.takeIf { it.isNotBlank() } ?: when {
            !noteTitle.isNullOrBlank() -> ctx.getString(R.string.notif_text_note_title, noteTitle)
            !preview.isNullOrBlank() -> preview
            else -> ctx.getString(R.string.notif_text_no_title)
        }

        val fullScreenIntent = AlarmAlertActivity.newIntent(
            ctx,
            noteId = noteId,
            reminderId = reminderId,
            label = notificationText,
            noteTitle = noteTitle,
            preview = preview,
            triggerAt = triggerAt,
        )

        val fullScreenPendingIntent = PendingIntent.getActivity(
            ctx,
            REQUEST_CODE_FULL_SCREEN,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val snooze5PendingIntent = snoozeIntent(ctx, reminderId, noteId, ACTION_SNOOZE_5, REQUEST_CODE_SNOOZE_5)
        val snooze10PendingIntent = snoozeIntent(ctx, reminderId, noteId, ACTION_SNOOZE_10, REQUEST_CODE_SNOOZE_10)
        val snooze60PendingIntent = snoozeIntent(ctx, reminderId, noteId, ACTION_SNOOZE_60, REQUEST_CODE_SNOOZE_60)
        val stopPendingIntent = PendingIntent.getBroadcast(
            ctx,
            REQUEST_CODE_STOP,
            Intent(ctx, ReminderReceiver::class.java).apply {
                action = ACTION_STOP_ALARM
                putExtra(EXTRA_REMINDER_ID, reminderId)
                putExtra(EXTRA_NOTE_ID, noteId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(ctx, ReminderChannels.CHANNEL_ALARMS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(resolvedTitle)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDeleteIntent(stopPendingIntent)
            .addAction(0, ctx.getString(R.string.alarm_snooze_5), snooze5PendingIntent)
            .addAction(0, ctx.getString(R.string.alarm_snooze_10), snooze10PendingIntent)
            .addAction(0, ctx.getString(R.string.alarm_snooze_60), snooze60PendingIntent)
            .addAction(0, ctx.getString(R.string.notif_action_stop_alarm), stopPendingIntent)

        NotificationManagerCompat.from(ctx).notify(reminderId.toInt(), builder.build())
    }

    private fun snoozeIntent(
        ctx: Context,
        reminderId: Long,
        noteId: Long,
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_NOTE_ID, noteId)
        }
        return PendingIntent.getBroadcast(
            ctx,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
