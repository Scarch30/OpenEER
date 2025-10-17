package com.example.openeer.core

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.openeer.R
import com.example.openeer.receiver.ReminderReceiver
import com.example.openeer.receiver.ReminderReceiver.Companion.ACTION_MARK_DONE
import com.example.openeer.receiver.ReminderReceiver.Companion.ACTION_SNOOZE_5
import com.example.openeer.receiver.ReminderReceiver.Companion.ACTION_SNOOZE_60
import com.example.openeer.receiver.ReminderReceiver.Companion.EXTRA_NOTE_ID
import com.example.openeer.receiver.ReminderReceiver.Companion.EXTRA_OPEN_NOTE_ID
import com.example.openeer.receiver.ReminderReceiver.Companion.EXTRA_REMINDER_ID
import com.example.openeer.ui.MainActivity

object ReminderNotifier {

    private const val REQUEST_CODE_CONTENT = 41002
    private const val REQUEST_CODE_SNOOZE_5 = 41003
    private const val REQUEST_CODE_SNOOZE_60 = 41004
    private const val REQUEST_CODE_MARK_DONE = 41005

    fun showReminder(
        ctx: Context,
        noteId: Long,
        reminderId: Long,
        title: String?,
        preview: String?,
        overrideText: String? = null,
    ) {
        val notificationTitle = ctx.getString(R.string.notif_title_reminder)
        val resolvedTitle = notificationTitle.ifBlank { "Rappel OpenEER" }
        val notificationText = overrideText?.takeIf { it.isNotBlank() } ?: when {
            !title.isNullOrBlank() -> ctx.getString(R.string.notif_text_note_title, title)
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
}
