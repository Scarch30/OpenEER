package com.example.openeer.workers

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.reminders.ReminderEntity
import com.example.openeer.receiver.ReminderReceiver
import com.example.openeer.data.reminders.ReminderEntity.Companion.STATUS_ACTIVE

class ReminderPeriodicWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val reminderId = inputData.getLong(KEY_REMINDER_ID, -1L)
        if (reminderId <= 0L) {
            Log.w(TAG, "[WM] doWork(): missing reminderId input=$reminderId")
            return Result.success()
        }

        val context = applicationContext
        val db = AppDatabase.getInstance(context)
        val reminder = db.reminderDao().getById(reminderId)
        if (reminder == null) {
            Log.w(TAG, "[WM] doWork(): reminderId=$reminderId not found -> cancel work")
            WorkManager.getInstance(context).cancelUniqueWork(workName(reminderId))
            return Result.success()
        }

        if (reminder.type != ReminderEntity.TYPE_TIME_REPEATING || reminder.status != STATUS_ACTIVE) {
            Log.d(
                TAG,
                "[WM] doWork(): reminderId=$reminderId type=${reminder.type} status=${reminder.status} -> cancel work"
            )
            WorkManager.getInstance(context).cancelUniqueWork(workName(reminderId))
            return Result.success()
        }

        val repeatMinutes = reminder.repeatEveryMinutes
        if (repeatMinutes == null || repeatMinutes < MIN_PERIODIC_MINUTES) {
            Log.d(
                TAG,
                "[WM] doWork(): reminderId=$reminderId unexpected repeatEveryMinutes=$repeatMinutes -> cancel work"
            )
            WorkManager.getInstance(context).cancelUniqueWork(workName(reminderId))
            return Result.success()
        }

        val noteId = reminder.noteId
        Log.d(
            TAG,
            "[WM] doWork(): firing reminderId=$reminderId noteId=$noteId nextTriggerAt=${reminder.nextTriggerAt} interval=${repeatMinutes}m"
        )

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE_ALARM
            putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
            putExtra(ReminderReceiver.EXTRA_NOTE_ID, noteId)
        }
        context.sendBroadcast(intent)

        return Result.success()
    }

    companion object {
        private const val TAG = "ReminderPeriodicW"
        internal const val KEY_REMINDER_ID = "key_reminder_id"
        private const val MIN_PERIODIC_MINUTES = 15

        internal fun workName(reminderId: Long): String = "reminder-periodic-$reminderId"
    }
}
