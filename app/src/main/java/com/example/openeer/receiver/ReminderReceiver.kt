package com.example.openeer.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.openeer.core.ReminderChannels

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
        // TODO: Afficher la notification de rappel et dispatcher les use cases associés.
    }
}
