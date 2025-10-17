package com.example.openeer.receiver

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.example.openeer.R
import com.example.openeer.core.ReminderChannels
import com.example.openeer.core.ReminderNotifier
import com.example.openeer.data.AppDatabase
import com.example.openeer.domain.ReminderUseCases
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE_ALARM: String = "com.example.openeer.REMINDER_FIRE_ALARM"
        const val ACTION_FIRE_GEOFENCE: String = "com.example.openeer.REMINDER_FIRE_GEOFENCE"
        const val ACTION_SNOOZE_5: String = "com.example.openeer.REMINDER_ACTION_SNOOZE_5"
        const val ACTION_SNOOZE_60: String = "com.example.openeer.REMINDER_ACTION_SNOOZE_60"
        const val ACTION_MARK_DONE: String = "com.example.openeer.REMINDER_ACTION_MARK_DONE"

        const val EXTRA_NOTE_ID: String = "extra_note_id"
        const val EXTRA_REMINDER_ID: String = "extra_reminder_id"
        const val EXTRA_GEOFENCE_EVENT: String = "extra_geofence_event"
        const val EXTRA_OPEN_NOTE_ID: String = "extra_open_note_id"

        private const val TAG = "ReminderReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        ReminderChannels.ensureCreated(context)

        val reminderId = intent?.getLongExtra(EXTRA_REMINDER_ID, -1L)?.takeIf { it >= 0L }
        val noteId = intent?.getLongExtra(EXTRA_NOTE_ID, -1L)?.takeIf { it >= 0L }
        val openNoteId = intent?.getLongExtra(EXTRA_OPEN_NOTE_ID, -1L)?.takeIf { it >= 0L }
        val geofenceEvent = intent?.getStringExtra(EXTRA_GEOFENCE_EVENT)
        val action = intent?.action

        Log.d(
            TAG,
            "Received action=$action noteId=$noteId reminderId=$reminderId " +
                "openNoteId=$openNoteId geofenceEvent=$geofenceEvent"
        )

        when (action) {
            ACTION_FIRE_ALARM -> handleFireAlarm(context, reminderId, noteId)
            ACTION_SNOOZE_5 -> handleSnooze(context, reminderId, 5)
            ACTION_SNOOZE_60 -> handleSnooze(context, reminderId, 60)
            ACTION_MARK_DONE -> handleMarkDone(context, reminderId)
            ACTION_FIRE_GEOFENCE -> Log.d(TAG, "Geofence trigger not implemented yet")
            else -> Log.w(TAG, "Unhandled action=$action")
        }
    }

    private fun handleFireAlarm(context: Context, reminderId: Long?, noteId: Long?) {
        if (reminderId == null || noteId == null) {
            Log.w(TAG, "handleFireAlarm: missing ids reminderId=$reminderId noteId=$noteId")
            Toast.makeText(context, R.string.notif_error_missing_ids, Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "ALARM fired for reminderId=$reminderId noteId=$noteId")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                val db = AppDatabase.getInstance(appContext)
                val noteDao = db.noteDao()
                val note = noteDao.getByIdOnce(noteId)
                val preview = note?.body
                    ?.lineSequence()
                    ?.firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.take(160)

                ReminderNotifier.showReminder(
                    appContext,
                    noteId,
                    reminderId,
                    note?.title,
                    preview
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Error displaying reminderId=$reminderId", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleSnooze(context: Context, reminderId: Long?, minutes: Int) {
        if (reminderId == null) {
            Log.w(TAG, "handleSnooze($minutes): missing reminderId")
            Toast.makeText(context, R.string.notif_error_missing_ids, Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "Snooze $minutes min for reminderId=$reminderId")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                val db = AppDatabase.getInstance(appContext)
                val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val useCases = ReminderUseCases(appContext, db, alarmManager)
                useCases.snooze(reminderId, minutes)
            } catch (t: Throwable) {
                Log.e(TAG, "Error snoozing reminderId=$reminderId", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleMarkDone(context: Context, reminderId: Long?) {
        if (reminderId == null) {
            Log.w(TAG, "handleMarkDone: missing reminderId")
            Toast.makeText(context, R.string.notif_error_missing_ids, Toast.LENGTH_SHORT).show()
            return
        }

        Log.d(TAG, "Mark done for reminderId=$reminderId")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext
                val db = AppDatabase.getInstance(appContext)
                val reminderDao = db.reminderDao()
                val reminder = reminderDao.getById(reminderId)
                if (reminder == null) {
                    Log.w(TAG, "handleMarkDone: reminderId=$reminderId not found")
                    return@launch
                }

                val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val useCases = ReminderUseCases(appContext, db, alarmManager)
                useCases.cancel(reminderId)
                reminderDao.markDone(reminderId, System.currentTimeMillis())

                NotificationManagerCompat.from(appContext).cancel(reminderId.toInt())
            } catch (t: Throwable) {
                Log.e(TAG, "Error marking reminderId=$reminderId as done", t)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
