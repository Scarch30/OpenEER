package com.example.openeer.domain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.openeer.core.GeofenceDiag
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.reminders.ReminderDao
import com.example.openeer.data.reminders.ReminderEntity
import com.example.openeer.receiver.ReminderReceiver
import com.example.openeer.receiver.ReminderReceiver.Companion.ACTION_FIRE_ALARM
import com.example.openeer.receiver.ReminderReceiver.Companion.ACTION_FIRE_GEOFENCE
import com.example.openeer.receiver.ReminderReceiver.Companion.EXTRA_NOTE_ID
import com.example.openeer.receiver.ReminderReceiver.Companion.EXTRA_REMINDER_ID
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReminderUseCases(
    private val context: Context,
    private val db: AppDatabase,
    private val alarmManager: AlarmManager
) {

    private val reminderDao: ReminderDao = db.reminderDao()

    suspend fun scheduleAtEpoch(
        noteId: Long,
        timeMillis: Long,
        blockId: Long? = null
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (timeMillis <= now) {
            Log.w(TAG, "Refusing to schedule reminder in the past (timeMillis=$timeMillis, now=$now)")
            throw IllegalArgumentException("timeMillis must be in the future")
        }

        val reminder = ReminderEntity(
            noteId = noteId,
            blockId = blockId,
            type = TYPE_TIME_ONE_SHOT,
            nextTriggerAt = timeMillis,
            status = STATUS_ACTIVE
        )

        val reminderId = reminderDao.insert(reminder)

        scheduleAlarm(reminderId, noteId, timeMillis)

        Log.d(TAG, "Scheduled reminderId=$reminderId noteId=$noteId at $timeMillis")
        reminderId
    }

    suspend fun scheduleGeofence(
        noteId: Long,
        lat: Double,
        lon: Double,
        radiusMeters: Int = DEFAULT_GEOFENCE_RADIUS_METERS,
        every: Boolean = false,
        blockId: Long? = null,
        cooldownMinutes: Int? = DEFAULT_GEO_COOLDOWN_MINUTES
    ): Long = withContext(Dispatchers.IO) {
        Log.d(
            TAG,
            "scheduleGeofence(): note=$noteId every=$every lat=$lat lon=$lon radius=$radiusMeters cooldown=$cooldownMinutes block=$blockId"
        )
        val reminder = ReminderEntity(
            noteId = noteId,
            blockId = blockId,
            type = if (every) TYPE_LOC_EVERY else TYPE_LOC_ONCE,
            nextTriggerAt = System.currentTimeMillis(),
            lat = lat,
            lon = lon,
            radius = radiusMeters,
            status = STATUS_ACTIVE,
            cooldownMinutes = cooldownMinutes
        )

        val reminderId = reminderDao.insert(reminder)
        Log.d(TAG, "scheduleGeofence(): inserted id=$reminderId")
        addGeofenceForExisting(reminder.copy(id = reminderId))
        Log.d(
            TAG,
            "Scheduled geo reminderId=$reminderId noteId=$noteId lat=$lat lon=$lon radius=$radiusMeters every=$every"
        )
        reminderId
    }

    suspend fun removeGeofence(reminderId: Long) = withContext(Dispatchers.IO) {
        removeGeofenceInternal(reminderId)
    }

    suspend fun cancel(reminderId: Long) = withContext(Dispatchers.IO) {
        val reminder = reminderDao.getById(reminderId)
        if (reminder == null) {
            Log.w(TAG, "cancel: reminderId=$reminderId not found")
            return@withContext
        }

        if (reminder.type == TYPE_LOC_ONCE || reminder.type == TYPE_LOC_EVERY) {
            removeGeofenceInternal(reminderId)
            return@withContext
        }

        cancelAlarm(reminderId, reminder.noteId)

        if (reminder.status != STATUS_CANCELLED) {
            reminderDao.update(reminder.copy(status = STATUS_CANCELLED))
        }

        Log.d(TAG, "Cancelled reminderId=$reminderId noteId=${reminder.noteId}")
    }

    suspend fun cancelAllForNote(noteId: Long) = withContext(Dispatchers.IO) {
        val reminders = reminderDao.getByNoteId(noteId)
        if (reminders.isEmpty()) {
            Log.d(TAG, "cancelAllForNote: no reminders for noteId=$noteId")
            return@withContext
        }

        reminders.forEach { reminder ->
            when (reminder.type) {
                TYPE_LOC_ONCE, TYPE_LOC_EVERY -> removeGeofenceInternal(reminder.id)
                else -> {
                    cancelAlarm(reminder.id, noteId)
                    if (reminder.status != STATUS_CANCELLED) {
                        reminderDao.update(reminder.copy(status = STATUS_CANCELLED))
                    }
                }
            }
        }

        Log.d(TAG, "Cancelled ${reminders.size} reminders for noteId=$noteId")
    }

    suspend fun snooze(reminderId: Long, minutes: Int) = withContext(Dispatchers.IO) {
        require(minutes > 0) { "Snooze minutes must be > 0" }

        val reminder = reminderDao.getById(reminderId)
        if (reminder == null) {
            Log.w(TAG, "snooze: reminderId=$reminderId not found")
            return@withContext
        }

        val now = System.currentTimeMillis()
        val nextTriggerAt = now + minutes.toLong() * ONE_MINUTE_IN_MILLIS

        val updated = reminder.copy(nextTriggerAt = nextTriggerAt)
        reminderDao.update(updated)

        scheduleAlarm(reminderId, reminder.noteId, nextTriggerAt)

        Log.d(TAG, "Snoozed reminderId=$reminderId noteId=${reminder.noteId} to $nextTriggerAt")
    }

    suspend fun restoreAllOnAppStart(nowProvider: () -> Long = { System.currentTimeMillis() }) =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "restoreGeofences(): start")
            val geoReminders = reminderDao.getActiveGeo()
            Log.d(TAG, "restoreGeofences(): activeGeoCount=${geoReminders.size}")
            geoReminders.forEach { r ->
                Log.d(
                    TAG,
                    "restoreGeofences(): re-add id=${r.id} note=${r.noteId} lat=${r.lat} lon=${r.lon} radius=${r.radius}"
                )
            }

            val now = nowProvider()
            val reminders = reminderDao.getUpcomingTimeReminders(now)

            reminders.forEach { reminder ->
                scheduleAlarm(reminder.id, reminder.noteId, reminder.nextTriggerAt)
                Log.d(
                    TAG,
                    "Restored reminderId=${reminder.id} noteId=${reminder.noteId} at ${reminder.nextTriggerAt}"
                )
            }
        }

    suspend fun restoreGeofences() = withContext(Dispatchers.IO) {
        Log.d(TAG, "restoreGeofences(): start")
        val geoReminders = reminderDao.getActiveGeo()
        Log.d(TAG, "restoreGeofences(): activeGeoCount=${geoReminders.size}")
        geoReminders.forEach { r ->
            Log.d(
                TAG,
                "restoreGeofences(): re-add id=${r.id} note=${r.noteId} lat=${r.lat} lon=${r.lon} radius=${r.radius}"
            )
        }
        geoReminders.forEach { reminder ->
            addGeofenceForExisting(reminder)
            Log.d(
                TAG,
                "Restored geo reminderId=${reminder.id} noteId=${reminder.noteId} type=${reminder.type}"
            )
        }
    }

    private fun scheduleAlarm(reminderId: Long, noteId: Long, triggerAt: Long) {
        val pendingIntent = buildAlarmPendingIntent(context, reminderId, noteId)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }

    private fun cancelAlarm(reminderId: Long, noteId: Long) {
        val pendingIntent = buildAlarmPendingIntent(context, reminderId, noteId)
        alarmManager.cancel(pendingIntent)
    }

    private fun buildAlarmIntent(context: Context, reminderId: Long, noteId: Long): Intent {
        return Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE_ALARM
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_NOTE_ID, noteId)
        }
    }

    private fun buildAlarmPendingIntent(context: Context, reminderId: Long, noteId: Long): PendingIntent {
        val intent = buildAlarmIntent(context, reminderId, noteId)
        return PendingIntent.getBroadcast(
            context,
            requestCodeFor(reminderId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun addGeofenceForExisting(reminder: ReminderEntity) {
        val client = LocationServices.getGeofencingClient(context)
        val geofence = Geofence.Builder()
            .setRequestId(reminder.id.toString())
            .setCircularRegion(reminder.lat!!, reminder.lon!!, (reminder.radius ?: DEFAULT_GEOFENCE_RADIUS_METERS).toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        val pi = buildGeofencePendingIntent(reminder.id, reminder.noteId)
        Log.d(TAG, "addGeofence(): id=${reminder.id} note=${reminder.noteId} lat=${reminder.lat} lon=${reminder.lon} radius=${reminder.radius} initialTrigger=ENTER")
        GeofenceDiag.logProviders(context)
        GeofenceDiag.logPerms(context)

        try {
            client.addGeofences(request, pi)
                .addOnSuccessListener { Log.d(TAG, "✅ addGeofences SUCCESS id=${reminder.id}") }
                .addOnFailureListener { e -> GeofenceDiag.logAddFailure(e) }
        } catch (se: SecurityException) {
            Log.e(TAG, "❌ addGeofences SecurityException (missing permission?) id=${reminder.id}", se)
        }
    }

    private fun buildGeofencePendingIntent(reminderId: Long, noteId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE_GEOFENCE
            // ⚠️ Certains firmwares remplacent totalement les extras. On les met quand même.
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_NOTE_ID, noteId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        Log.d(
            TAG,
            "buildGeofencePI(): id=$reminderId note=$noteId flags=${GeofenceDiag.flagsToString(flags)}"
        )
        return PendingIntent.getBroadcast(context, requestCodeFor(reminderId), intent, flags)
    }

    private fun requestCodeFor(reminderId: Long): Int {
        return (reminderId % Int.MAX_VALUE).toInt()
    }

    private suspend fun removeGeofenceInternal(reminderId: Long) {
        Log.d(TAG, "removeGeofence(): id=$reminderId")
        try {
            val client = LocationServices.getGeofencingClient(context)
            client.removeGeofences(listOf(reminderId.toString()))
                .addOnSuccessListener { Log.d(TAG, "✅ removeGeofences SUCCESS id=$reminderId") }
                .addOnFailureListener { e -> Log.e(TAG, "❌ removeGeofences FAILED id=$reminderId", e) }
        } catch (se: SecurityException) {
            Log.w(TAG, "removeGeofence(): SecurityException id=$reminderId", se)
        }
        reminderDao.cancelById(reminderId)
        Log.d(TAG, "removeGeofence(): DAO cancel id=$reminderId")
    }

    private companion object {
        private const val TAG = "ReminderUseCases"
        private const val TYPE_TIME_ONE_SHOT = "TIME_ONE_SHOT"
        private const val TYPE_LOC_ONCE = "LOC_ONCE"
        private const val TYPE_LOC_EVERY = "LOC_EVERY"
        private const val STATUS_ACTIVE = "ACTIVE"
        private const val STATUS_CANCELLED = "CANCELLED"
        private const val ONE_MINUTE_IN_MILLIS = 60_000L
        private const val DEFAULT_GEOFENCE_RADIUS_METERS = 100
        private const val DEFAULT_GEO_COOLDOWN_MINUTES = 30
    }
}
