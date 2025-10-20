package com.example.openeer.domain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.openeer.core.GeofenceDiag
import com.example.openeer.core.LocationPerms
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.reminders.ReminderDao
import com.example.openeer.data.reminders.ReminderEntity
import com.example.openeer.data.reminders.ReminderEntity.Companion.STATUS_ACTIVE
import com.example.openeer.data.reminders.ReminderEntity.Companion.STATUS_CANCELLED
import com.example.openeer.data.reminders.ReminderEntity.Companion.STATUS_PAUSED
import com.example.openeer.receiver.ReminderReceiver
import com.example.openeer.workers.ReminderPeriodicWorker
import com.example.openeer.receiver.ReminderReceiver.Companion.ACTION_FIRE_ALARM
import com.example.openeer.receiver.ReminderReceiver.Companion.ACTION_FIRE_GEOFENCE
import com.example.openeer.receiver.ReminderReceiver.Companion.EXTRA_NOTE_ID
import com.example.openeer.receiver.ReminderReceiver.Companion.EXTRA_REMINDER_ID
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ReminderUseCases(
    private val context: Context,
    private val db: AppDatabase,
    private val alarmManager: AlarmManager
) {

    private val reminderDao: ReminderDao = db.reminderDao()
    private val workManager: WorkManager = WorkManager.getInstance(context)

    suspend fun scheduleAtEpoch(
        noteId: Long,
        timeMillis: Long,
        label: String? = null,
        repeatEveryMinutes: Int? = null,
        delivery: String = ReminderEntity.DELIVERY_NOTIFICATION
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (timeMillis <= now) {
            Log.w(TAG, "Refusing to schedule reminder in the past (timeMillis=$timeMillis, now=$now)")
            throw IllegalArgumentException("timeMillis must be in the future")
        }

        val type = if (repeatEveryMinutes == null) TYPE_TIME_ONE_SHOT else TYPE_TIME_REPEATING
        val intervalLabel = repeatEveryMinutes?.let { "${it}m" } ?: "once"
        Log.d(
            TAG,
            "scheduleAtEpoch(): preparing type=$type interval=$intervalLabel noteId=$noteId label=${label?.take(32)} triggerAt=$timeMillis"
        )

        val sanitizedLabel = label?.trim()?.takeIf { it.isNotEmpty() }

        val reminder = ReminderEntity(
            noteId = noteId,
            label = sanitizedLabel,
            type = type,
            nextTriggerAt = timeMillis,
            status = STATUS_ACTIVE,
            repeatEveryMinutes = repeatEveryMinutes,
            delivery = delivery
        )

        val reminderId = reminderDao.insert(reminder)

        if (usesWorkManager(repeatEveryMinutes)) {
            schedulePeriodicWork(reminderId, noteId, timeMillis, repeatEveryMinutes!!)
        } else {
            scheduleAlarm(reminderId, noteId, timeMillis)
        }

        Log.d(
            TAG,
            "Scheduled reminderId=$reminderId noteId=$noteId type=$type at $timeMillis interval=$intervalLabel"
        )
        reminderId
    }

    suspend fun scheduleGeofence(
        noteId: Long,
        lat: Double,
        lon: Double,
        radiusMeters: Int = DEFAULT_GEOFENCE_RADIUS_METERS,
        every: Boolean = false,
        label: String? = null,
        cooldownMinutes: Int? = DEFAULT_GEO_COOLDOWN_MINUTES,
        triggerOnExit: Boolean = false,
        startingInside: Boolean = false,
        delivery: String = ReminderEntity.DELIVERY_NOTIFICATION
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        Log.d(
            TAG,
            "scheduleGeofence(): note=$noteId every=$every lat=$lat lon=$lon radius=$radiusMeters cooldown=$cooldownMinutes label=${label?.take(32)} triggerOnExit=$triggerOnExit startingInside=$startingInside"
        )
        val armedAt = when {
            triggerOnExit && startingInside -> now
            triggerOnExit -> null
            startingInside -> null
            else -> now
        }
        val sanitizedLabel = label?.trim()?.takeIf { it.isNotEmpty() }
        val exit = triggerOnExit
        val reminder = ReminderEntity(
            noteId = noteId,
            label = sanitizedLabel,
            type = if (every) TYPE_LOC_EVERY else TYPE_LOC_ONCE,
            nextTriggerAt = now,
            lat = lat,
            lon = lon,
            radius = radiusMeters,
            status = STATUS_ACTIVE,
            cooldownMinutes = cooldownMinutes,
            triggerOnExit = exit,
            disarmedUntilExit = exit,
            delivery = delivery,
            armedAt = armedAt
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

    suspend fun updateTimeReminder(
        reminderId: Long,
        nextTriggerAt: Long,
        label: String?,
        repeatEveryMinutes: Int?,
        delivery: String? = null,
    ) = withContext(Dispatchers.IO) {
        val reminder = reminderDao.getById(reminderId)
        if (reminder == null) {
            Log.w(TAG, "updateTimeReminder(): reminderId=$reminderId not found")
            return@withContext
        }
        val now = System.currentTimeMillis()
        if (nextTriggerAt <= now) {
            Log.w(
                TAG,
                "updateTimeReminder(): refusing past trigger reminderId=$reminderId next=$nextTriggerAt now=$now"
            )
            throw IllegalArgumentException("nextTriggerAt must be in the future")
        }
        val sanitizedLabel = label?.trim()?.takeIf { it.isNotEmpty() }
        val oldType = reminder.type
        val newType = if (repeatEveryMinutes == null) TYPE_TIME_ONE_SHOT else TYPE_TIME_REPEATING
        val repeatLabel = repeatEveryMinutes?.let { "${it}m" } ?: "once"
        Log.d(
            TAG,
            "updateTimeReminder(): id=$reminderId note=${reminder.noteId} $oldType→$newType next=$nextTriggerAt repeat=$repeatLabel"
        )
        when (oldType) {
            TYPE_LOC_ONCE, TYPE_LOC_EVERY -> removeGeofenceInternal(reminderId, cancelInDb = false)
            else -> {
                cancelAlarm(reminderId, reminder.noteId)
                if (usesWorkManager(reminder.repeatEveryMinutes)) {
                    cancelPeriodicWork(reminderId)
                }
            }
        }

        val updated = reminder.copy(
            type = newType,
            nextTriggerAt = nextTriggerAt,
            label = sanitizedLabel,
            lat = null,
            lon = null,
            radius = null,
            cooldownMinutes = null,
            triggerOnExit = false,
            disarmedUntilExit = false,
            armedAt = null,
            repeatEveryMinutes = repeatEveryMinutes,
            status = STATUS_ACTIVE,
            delivery = delivery ?: reminder.delivery,
        )
        reminderDao.update(updated)
        if (usesWorkManager(repeatEveryMinutes)) {
            schedulePeriodicWork(reminderId, reminder.noteId, nextTriggerAt, repeatEveryMinutes!!)
        } else {
            scheduleAlarm(reminderId, reminder.noteId, nextTriggerAt)
        }
        Log.d(TAG, "updateTimeReminder(): rescheduled reminderId=$reminderId note=${reminder.noteId}")
    }

    suspend fun updateGeofenceReminder(
        reminderId: Long,
        lat: Double,
        lon: Double,
        radius: Int,
        every: Boolean,
        disarmedUntilExit: Boolean,
        cooldownMinutes: Int?,
        label: String?,
        delivery: String? = null,
    ) = withContext(Dispatchers.IO) {
        require(radius > 0) { "radius must be > 0" }
        if (cooldownMinutes != null) {
            require(cooldownMinutes >= 0) { "cooldownMinutes must be >= 0" }
        }
        val reminder = reminderDao.getById(reminderId)
        if (reminder == null) {
            Log.w(TAG, "updateGeofenceReminder(): reminderId=$reminderId not found")
            return@withContext
        }
        val sanitizedLabel = label?.trim()?.takeIf { it.isNotEmpty() }
        val now = System.currentTimeMillis()
        val newType = if (every) TYPE_LOC_EVERY else TYPE_LOC_ONCE
        val oldType = reminder.type
        Log.d(
            TAG,
            "updateGeofenceReminder(): id=$reminderId note=${reminder.noteId} $oldType→$newType lat=$lat lon=$lon radius=$radius exit=$disarmedUntilExit cooldown=$cooldownMinutes"
        )

        when (oldType) {
            TYPE_TIME_ONE_SHOT, TYPE_TIME_REPEATING -> cancelAlarm(reminderId, reminder.noteId)
            else -> removeGeofenceInternal(reminderId, cancelInDb = false)
        }

        if (oldType == TYPE_TIME_REPEATING && usesWorkManager(reminder.repeatEveryMinutes)) {
            cancelPeriodicWork(reminderId)
        }

        val armedAt = if (disarmedUntilExit) null else now
        val updated = reminder.copy(
            type = newType,
            nextTriggerAt = now,
            lat = lat,
            lon = lon,
            radius = radius,
            cooldownMinutes = cooldownMinutes,
            label = sanitizedLabel,
            triggerOnExit = disarmedUntilExit,
            disarmedUntilExit = disarmedUntilExit,
            armedAt = armedAt,
            repeatEveryMinutes = null,
            status = STATUS_ACTIVE,
            delivery = delivery ?: reminder.delivery,
        )
        reminderDao.update(updated)
        addGeofenceForExisting(updated)
        Log.d(TAG, "updateGeofenceReminder(): rearmed reminderId=$reminderId note=${reminder.noteId}")
    }

    suspend fun removeGeofence(reminderId: Long) = withContext(Dispatchers.IO) {
        removeGeofenceInternal(reminderId)
    }

    suspend fun pause(reminderId: Long) = withContext(Dispatchers.IO) {
        val reminder = reminderDao.getById(reminderId)
        if (reminder == null) {
            Log.w(TAG, "pause: reminderId=$reminderId not found")
            return@withContext
        }

        if (reminder.status != STATUS_ACTIVE) {
            Log.d(
                TAG,
                "pause: reminderId=$reminderId noteId=${reminder.noteId} status=${reminder.status} -> ignore"
            )
            return@withContext
        }

        when (reminder.type) {
            TYPE_LOC_ONCE, TYPE_LOC_EVERY -> removeGeofenceInternal(reminderId, cancelInDb = false)
            else -> {
                cancelAlarm(reminderId, reminder.noteId)
                if (usesWorkManager(reminder.repeatEveryMinutes)) {
                    cancelPeriodicWork(reminderId)
                }
            }
        }

        reminderDao.pause(reminderId)
        Log.d(TAG, "pause: reminderId=$reminderId noteId=${reminder.noteId} paused")
    }

    suspend fun resume(reminderId: Long) = withContext(Dispatchers.IO) {
        val reminder = reminderDao.getById(reminderId)
        if (reminder == null) {
            Log.w(TAG, "resume: reminderId=$reminderId not found")
            return@withContext
        }

        if (reminder.status != STATUS_PAUSED) {
            Log.d(
                TAG,
                "resume: reminderId=$reminderId noteId=${reminder.noteId} status=${reminder.status} -> ignore"
            )
            return@withContext
        }

        when (reminder.type) {
            TYPE_LOC_ONCE, TYPE_LOC_EVERY -> {
                addGeofenceForExisting(reminder)
            }
            else -> {
                val now = System.currentTimeMillis()
                val repeatMinutes = reminder.repeatEveryMinutes
                var triggerAt = reminder.nextTriggerAt

                if (repeatMinutes != null && repeatMinutes > 0) {
                    if (triggerAt <= now) {
                        val advanced = computeNextRepeatingTrigger(now, triggerAt, repeatMinutes)
                        if (advanced != triggerAt) {
                            reminderDao.update(reminder.copy(nextTriggerAt = advanced))
                            triggerAt = advanced
                        }
                    }
                    if (usesWorkManager(repeatMinutes)) {
                        schedulePeriodicWork(reminderId, reminder.noteId, triggerAt, repeatMinutes)
                    } else {
                        scheduleAlarm(reminderId, reminder.noteId, triggerAt)
                    }
                } else {
                    if (triggerAt <= now) {
                        triggerAt = now + ONE_MINUTE_IN_MILLIS
                        reminderDao.update(reminder.copy(nextTriggerAt = triggerAt))
                    }
                    scheduleAlarm(reminderId, reminder.noteId, triggerAt)
                }
            }
        }

        reminderDao.resume(reminderId)
        Log.d(TAG, "resume: reminderId=$reminderId noteId=${reminder.noteId} resumed")
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
        if (usesWorkManager(reminder.repeatEveryMinutes)) {
            cancelPeriodicWork(reminderId)
        }

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
                    if (usesWorkManager(reminder.repeatEveryMinutes)) {
                        cancelPeriodicWork(reminder.id)
                    }
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
        val useWorkManager = usesWorkManager(reminder.repeatEveryMinutes)

        val updated = reminder.copy(nextTriggerAt = nextTriggerAt)
        reminderDao.update(updated)

        scheduleAlarm(reminderId, reminder.noteId, nextTriggerAt)

        if (useWorkManager) {
            Log.d(
                TAG,
                "[WM] snooze(): reminderId=$reminderId scheduled one-shot at $nextTriggerAt minutes=$minutes " +
                    "(periodic work preserved)"
            )
        }

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
            val scheduledIds = mutableSetOf<Long>()

            val dueReminders = reminderDao.getDue(now)
            Log.d(TAG, "restoreAllOnAppStart(): found dueCount=${dueReminders.size}")
            dueReminders.forEach { reminder ->
                val repeatMinutes = reminder.repeatEveryMinutes
                if (repeatMinutes != null && repeatMinutes > 0) {
                    val advancedTrigger = computeNextRepeatingTrigger(now, reminder.nextTriggerAt, repeatMinutes)
                    if (advancedTrigger != reminder.nextTriggerAt) {
                        reminderDao.update(reminder.copy(nextTriggerAt = advancedTrigger))
                        Log.d(
                            TAG,
                            "restoreAllOnAppStart(): advanced repeating reminderId=${reminder.id} interval=${repeatMinutes}m " +
                                "from=${reminder.nextTriggerAt} to=$advancedTrigger"
                        )
                    } else {
                        Log.d(
                            TAG,
                            "restoreAllOnAppStart(): repeating reminderId=${reminder.id} interval=${repeatMinutes}m already future=${reminder.nextTriggerAt}"
                        )
                    }
                    if (usesWorkManager(repeatMinutes)) {
                        schedulePeriodicWork(reminder.id, reminder.noteId, advancedTrigger, repeatMinutes)
                        Log.d(
                            TAG,
                            "[WM] restoreAllOnAppStart(): reminderId=${reminder.id} noteId=${reminder.noteId} next=$advancedTrigger interval=${repeatMinutes}m"
                        )
                    } else {
                        scheduleAlarm(reminder.id, reminder.noteId, advancedTrigger)
                    }
                    scheduledIds += reminder.id
                    Log.d(
                        TAG,
                        "restoreAllOnAppStart(): rescheduled repeating reminderId=${reminder.id} noteId=${reminder.noteId} next=$advancedTrigger"
                    )
                } else {
                    Log.d(
                        TAG,
                        "restoreAllOnAppStart(): skipping overdue one-shot reminderId=${reminder.id} noteId=${reminder.noteId} next=${reminder.nextTriggerAt}"
                    )
                }
            }

            val upcomingReminders = reminderDao.getUpcomingTimeReminders(now)
            Log.d(TAG, "restoreAllOnAppStart(): upcomingCount=${upcomingReminders.size}")
            upcomingReminders.forEach { reminder ->
                if (scheduledIds.add(reminder.id)) {
                    val repeatMinutes = reminder.repeatEveryMinutes
                    if (usesWorkManager(repeatMinutes)) {
                        schedulePeriodicWork(reminder.id, reminder.noteId, reminder.nextTriggerAt, repeatMinutes!!)
                        Log.d(
                            TAG,
                            "[WM] restoreAllOnAppStart(): reminderId=${reminder.id} noteId=${reminder.noteId} next=${reminder.nextTriggerAt} interval=${repeatMinutes}m"
                        )
                    } else {
                        scheduleAlarm(reminder.id, reminder.noteId, reminder.nextTriggerAt)
                    }
                    val repeatLabel = reminder.repeatEveryMinutes?.let { "${it}m" } ?: "once"
                    Log.d(
                        TAG,
                        "restoreAllOnAppStart(): restored reminderId=${reminder.id} noteId=${reminder.noteId} at ${reminder.nextTriggerAt} repeat=$repeatLabel"
                    )
                }
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

    private fun usesWorkManager(repeatEveryMinutes: Int?): Boolean {
        return repeatEveryMinutes != null && repeatEveryMinutes >= MIN_PERIODIC_INTERVAL_MINUTES
    }

    private fun schedulePeriodicWork(
        reminderId: Long,
        noteId: Long,
        triggerAt: Long,
        repeatEveryMinutes: Int
    ) {
        require(repeatEveryMinutes >= MIN_PERIODIC_INTERVAL_MINUTES) {
            "repeatEveryMinutes must be >= $MIN_PERIODIC_INTERVAL_MINUTES for WorkManager"
        }
        val now = System.currentTimeMillis()
        val delayMillis = (triggerAt - now).coerceAtLeast(0L)
        val data = workDataOf(ReminderPeriodicWorker.KEY_REMINDER_ID to reminderId)
        val request = PeriodicWorkRequestBuilder<ReminderPeriodicWorker>(
            repeatEveryMinutes.toLong(),
            TimeUnit.MINUTES
        )
            .setInputData(data)
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .build()
        val name = ReminderPeriodicWorker.workName(reminderId)
        Log.d(
            TAG,
            "[WM] schedule(): reminderId=$reminderId noteId=$noteId interval=${repeatEveryMinutes}m initialDelay=${delayMillis}ms"
        )
        workManager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.REPLACE, request)
    }

    private fun cancelPeriodicWork(reminderId: Long) {
        val name = ReminderPeriodicWorker.workName(reminderId)
        Log.d(TAG, "[WM] cancel(): reminderId=$reminderId workName=$name")
        workManager.cancelUniqueWork(name)
    }

    private fun scheduleAlarm(reminderId: Long, noteId: Long, triggerAt: Long) {
        val pendingIntent = buildAlarmPendingIntent(context, reminderId, noteId)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
    }

    fun scheduleNextTimeReminder(
        reminderId: Long,
        noteId: Long,
        triggerAt: Long,
        repeatEveryMinutes: Int?
    ) {
        Log.d(
            TAG,
            "scheduleNextTimeReminder(): reminderId=$reminderId noteId=$noteId triggerAt=$triggerAt repeat=${repeatEveryMinutes}"
        )
        if (usesWorkManager(repeatEveryMinutes)) {
            schedulePeriodicWork(reminderId, noteId, triggerAt, repeatEveryMinutes!!)
        } else {
            scheduleAlarm(reminderId, noteId, triggerAt)
        }
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
        val lat = reminder.lat ?: run {
            Log.w(TAG, "addGeofence(): missing coordinates id=${reminder.id}")
            return
        }
        val lon = reminder.lon ?: run {
            Log.w(TAG, "addGeofence(): missing coordinates id=${reminder.id}")
            return
        }

        LocationPerms.dump(context)
        val fineOk = LocationPerms.hasFine(context)
        val bgOk = LocationPerms.hasBackground(context)

        if (!fineOk) {
            Log.e(
                TAG,
                "❌ addGeofence(): missing ACCESS_FINE_LOCATION → will not call addGeofences() [id=${reminder.id}]"
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 29 && !bgOk) {
            Log.e(
                TAG,
                "❌ addGeofence(): missing ACCESS_BACKGROUND_LOCATION on API>=29 → will not call addGeofences() [id=${reminder.id}]"
            )
            return
        }

        val client = LocationServices.getGeofencingClient(context)
        val geofence = Geofence.Builder()
            .setRequestId(reminder.id.toString())
            .setCircularRegion(
                lat,
                lon,
                (reminder.radius ?: DEFAULT_GEOFENCE_RADIUS_METERS).toFloat()
            )
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
            )
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(geofence)
            .build()

        val pi = buildGeofencePendingIntent(reminder.id, reminder.noteId)
        val triggerLabel = reminder.transition.name
        Log.d(
            TAG,
            "addGeofence(): id=${reminder.id} note=${reminder.noteId} lat=${reminder.lat} lon=${reminder.lon} radius=${reminder.radius} transitions=ENTER|EXIT fireOn=$triggerLabel initialTrigger=0"
        )
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

    private suspend fun removeGeofenceInternal(reminderId: Long, cancelInDb: Boolean = true) {
        Log.d(TAG, "removeGeofence(): id=$reminderId")
        try {
            val client = LocationServices.getGeofencingClient(context)
            client.removeGeofences(listOf(reminderId.toString()))
                .addOnSuccessListener { Log.d(TAG, "✅ removeGeofences SUCCESS id=$reminderId") }
                .addOnFailureListener { e -> Log.e(TAG, "❌ removeGeofences FAILED id=$reminderId", e) }
        } catch (se: SecurityException) {
            Log.w(TAG, "removeGeofence(): SecurityException id=$reminderId", se)
        }
        if (cancelInDb) {
            reminderDao.cancelById(reminderId)
            Log.d(TAG, "removeGeofence(): DAO cancel id=$reminderId")
        }
    }

    companion object {
        private const val TAG = "ReminderUseCases"
        private const val TYPE_TIME_ONE_SHOT = "TIME_ONE_SHOT"
        private const val TYPE_TIME_REPEATING = "TIME_REPEATING"
        private const val TYPE_LOC_ONCE = "LOC_ONCE"
        private const val TYPE_LOC_EVERY = "LOC_EVERY"
        private const val ONE_MINUTE_IN_MILLIS = 60_000L
        private const val MIN_PERIODIC_INTERVAL_MINUTES = 15
        private const val DEFAULT_GEOFENCE_RADIUS_METERS = 100
        private const val DEFAULT_GEO_COOLDOWN_MINUTES = 30

        internal fun computeNextRepeatingTrigger(
            now: Long,
            scheduledAt: Long,
            repeatEveryMinutes: Int
        ): Long {
            val interval = repeatEveryMinutes.toLong() * ONE_MINUTE_IN_MILLIS
            if (interval <= 0L) {
                Log.w(
                    TAG,
                    "computeNextRepeatingTrigger(): invalid repeatEveryMinutes=$repeatEveryMinutes -> fallback to now + 1min"
                )
                return now + ONE_MINUTE_IN_MILLIS
            }

            var next = scheduledAt
            while (next <= now) {
                next += interval
            }
            return next
        }

        fun transitionLabel(reminder: ReminderEntity): String {
            return when (reminder.transition) {
                ReminderEntity.Transition.ENTER -> "Arrivée"
                ReminderEntity.Transition.EXIT -> "Départ"
            }
        }
    }
}
