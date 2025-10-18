package com.example.openeer.receiver

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.example.openeer.R
import com.example.openeer.core.GeofenceDiag
import com.example.openeer.core.ReminderChannels
import com.example.openeer.core.ReminderNotifier
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.reminders.ReminderEntity
import com.example.openeer.domain.ReminderUseCases
import com.example.openeer.ui.sheets.ReminderListSheet
import com.google.android.gms.location.GeofenceStatusCodes
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
        private const val TYPE_LOC_ONCE = "LOC_ONCE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        GeofenceDiag.dumpIntent("onReceive()", intent)
        Log.d(TAG, "Receiver start")

        ReminderChannels.ensureCreated(context)

        Log.d(TAG, "onReceive action=${intent?.action} extras=${intent?.extras?.keySet()}")

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
            ACTION_FIRE_GEOFENCE -> {
                val ev = GeofenceDiag.dumpEvent("onReceive()", intent)
                if (ev == null) {
                    Log.w(TAG, "GeofencingEvent is null; abort")
                    return
                }
                if (ev.hasError()) {
                    Log.w(
                        TAG,
                        "Geofence error=${ev.errorCode} (${GeofenceStatusCodes.getStatusCodeString(ev.errorCode)})"
                    )
                    return
                }

                val ids = ev.triggeringGeofences?.mapNotNull { it.requestId.toLongOrNull() }.orEmpty()
                Log.d(TAG, "Resolved requestIds -> $ids")
                if (ids.isEmpty()) return

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val app = context.applicationContext
                        val db = AppDatabase.getInstance(app)
                        val dao = db.reminderDao()
                        for (rid in ids) {
                            val r = dao.getById(rid)
                            if (r == null) {
                                Log.w(TAG, "No reminder in DB for requestId=$rid")
                                continue
                            }
                            logReminderDump("geofence#$rid", r)
                            Log.d(
                                TAG,
                                "handleGeofence enter: id=${r.id} note=${r.noteId} type=${r.type} status=${r.status} cooldown=${r.cooldownMinutes}"
                            )

                            handleGeofenceInternal(app, r.id, r.noteId)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Receiver geofence handling failed", t)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
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
                val reminderDao = db.reminderDao()
                val note = noteDao.getByIdOnce(noteId)
                val preview = note?.body
                    ?.lineSequence()
                    ?.firstOrNull { it.isNotBlank() }
                    ?.trim()
                    ?.take(160)
                val reminder = reminderDao.getById(reminderId)
                reminder?.let { logReminderDump("handleFireAlarm", it) }
                val overrideText = reminder?.blockId?.let { blockId ->
                    db.blockDao().getById(blockId)?.text
                        ?.lineSequence()
                        ?.firstOrNull()
                        ?.removePrefix("⏰")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }

                ReminderNotifier.showReminder(
                    appContext,
                    noteId,
                    reminderId,
                    note?.title,
                    preview,
                    overrideText
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
                val reminderDao = db.reminderDao()
                val reminder = reminderDao.getById(reminderId)
                if (reminder == null) {
                    Log.w(TAG, "handleSnooze($minutes): reminderId=$reminderId not found")
                    return@launch
                }
                logReminderDump("handleSnooze($minutes)", reminder)
                val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val useCases = ReminderUseCases(appContext, db, alarmManager)
                useCases.snooze(reminderId, minutes)
                ReminderListSheet.notifyChangedBroadcast(appContext, reminder.noteId)
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
                logReminderDump("handleMarkDone", reminder)

                val noteId = reminder.noteId
                val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val useCases = ReminderUseCases(appContext, db, alarmManager)
                useCases.cancel(reminderId)
                reminderDao.markDone(reminderId, System.currentTimeMillis())

                NotificationManagerCompat.from(appContext).cancel(reminderId.toInt())
                ReminderListSheet.notifyChangedBroadcast(appContext, noteId)
            } catch (t: Throwable) {
                Log.e(TAG, "Error marking reminderId=$reminderId as done", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun handleGeofence(context: Context, reminderId: Long?, noteId: Long?) {
        Log.d(TAG, "handleGeofence(reminderId=$reminderId, noteId=$noteId) begin")
        if (reminderId == null || noteId == null) {
            Log.w(TAG, "handleGeofence: missing ids reminderId=$reminderId noteId=$noteId")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handleGeofenceInternal(context.applicationContext, reminderId, noteId)
            } catch (t: Throwable) {
                Log.e(TAG, "Error handling geofence reminderId=$reminderId", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun handleGeofenceInternal(context: Context, reminderId: Long, initialNoteId: Long?) {
        val appContext = context.applicationContext
        val db = AppDatabase.getInstance(appContext)
        val reminderDao = db.reminderDao()
        Log.d(TAG, "handleGeofence: fetching reminder from DB (id=$reminderId)")
        val reminder = reminderDao.getById(reminderId)
        if (reminder == null) {
            Log.w(TAG, "handleGeofence: reminderId=$reminderId not found")
            return
        }
        logReminderDump("handleGeofence", reminder)
        Log.d(
            TAG,
            "handleGeofence: reminder fetched status=${reminder.status} type=${reminder.type} lastFired=${reminder.lastFiredAt}"
        )

        val noteId = initialNoteId ?: reminder.noteId

        val now = System.currentTimeMillis()
        val cooldownMinutes = reminder.cooldownMinutes ?: 0
        Log.d(TAG, "handleGeofence: checking cooldown (minutes=$cooldownMinutes last=${reminder.lastFiredAt})")
        if (cooldownMinutes > 0) {
            val last = reminder.lastFiredAt
            val cooldownMs = cooldownMinutes * 60_000L
            if (last != null && now - last < cooldownMs) {
                Log.d(TAG, "Geofence cooldown active, skipping reminderId=$reminderId")
                return
            }
        }

        val noteDao = db.noteDao()
        Log.d(TAG, "handleGeofence: fetching note preview for noteId=$noteId")
        val note = noteDao.getByIdOnce(noteId)
        val preview = note?.body
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(160)
        val overrideText = reminder.blockId?.let { blockId ->
            db.blockDao().getById(blockId)?.text
                ?.lineSequence()
                ?.firstOrNull()
                ?.removePrefix("⏰")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        Log.d(TAG, "handleGeofence: showing notification for reminderId=$reminderId noteId=$noteId")
        ReminderNotifier.showReminder(
            appContext,
            noteId,
            reminderId,
            note?.title,
            preview,
            overrideText
        )

        Log.d(TAG, "handleGeofence: rescheduling reminderId=$reminderId -> lastFiredAt/nextTriggerAt=$now")
        reminderDao.update(reminder.copy(lastFiredAt = now, nextTriggerAt = now))

        if (reminder.type == TYPE_LOC_ONCE) {
            Log.d(
                TAG,
                "handleGeofence: LOC_ONCE -> removing geofence via useCases (will mark done/cancel inside useCases)"
            )
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val useCases = ReminderUseCases(appContext, db, alarmManager)
            useCases.removeGeofence(reminderId)
        }
    }

    private fun logReminderDump(source: String, reminder: ReminderEntity) {
        Log.d(
            TAG,
            "DB dump reminder ($source): id=${reminder.id} note=${reminder.noteId} type=${reminder.type} status=${reminder.status} next=${reminder.nextTriggerAt} lat=${reminder.lat} lon=${reminder.lon} radius=${reminder.radius} block=${reminder.blockId}"
        )
    }
}
