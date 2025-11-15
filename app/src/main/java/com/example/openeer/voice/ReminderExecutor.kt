package com.example.openeer.voice

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.reminders.PendingVoiceReminderEntity
import com.example.openeer.data.reminders.ReminderEntity
import com.example.openeer.domain.ReminderUseCases
import com.example.openeer.ui.sheets.ReminderListSheet
import com.example.openeer.voice.ReminderIntent.Field
import com.example.openeer.voice.VoiceRouteDecision
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ReminderExecutor(
    context: Context,
    voiceDependencies: VoiceDependencies = VoiceComponents.obtain(context),
    private val databaseProvider: () -> AppDatabase = {
        AppDatabase.getInstance(context.applicationContext)
    },
    private val alarmManagerProvider: () -> AlarmManager = {
        context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    },
    currentLocationResolver: suspend () -> Location = {
        resolveCurrentLocation(context.applicationContext)
    }
) {

    private val appContext = context.applicationContext
    private val placeParser = voiceDependencies.placeParser
    private val currentLocationResolver = currentLocationResolver

    suspend fun createFromVoice(noteId: Long, labelFromWhisper: String): Long {
        val parseResult = LocalTimeIntentParser.parseReminder(labelFromWhisper)
            ?: throw IllegalArgumentException("Unable to parse reminder timing from voice input")
        val triggerAt = parseResult.triggerAtMillis
        val reminderId = scheduleTimeReminder(
            noteId = noteId,
            triggerAt = triggerAt,
            label = parseResult.label,
        )
        Log.d(
            TAG,
            "createFromVoice(): reminderId=$reminderId noteId=$noteId triggerAt=$triggerAt label='${parseResult.label}'"
        )
        return reminderId
    }

    suspend fun createPlaceReminderFromVoice(
        noteId: Long,
        text: String
    ): Long {
        val parseResult = try {
            placeParser.parse(text)
        } catch (error: LocalPlaceIntentParser.FavoriteNotFound) {
            throw FavoriteNotFoundException(error.candidate.raw)
        } ?: throw IncompleteException("No place intent parsed")

        val resolvedPlace = resolvePlace(parseResult.place)
        val sanitizedLabel = sanitizeLabel(parseResult.label)
        Log.d(
            TAG,
            "createPlaceReminderFromVoice(): place=${resolvedPlace.toLogString(parseResult.place)} " +
                "transition=${parseResult.transition} label=\"${sanitizedLabel}\""
        )

        val reminderId = schedulePlaceReminder(
            noteId = noteId,
            intent = ReminderIntent.Place(parseResult),
            resolvedPlace = resolvedPlace,
            label = sanitizedLabel,
        )
        Log.d(
            TAG,
            "createPlaceReminderFromVoice(): reminderId=$reminderId noteId=$noteId transition=${parseResult.transition} " +
                "radius=${parseResult.radiusMeters} cooldown=${parseResult.cooldownMinutes} every=${parseResult.everyTime} " +
                "label='${sanitizedLabel}' placeLabel='${resolvedPlace.placeLabel}' " +
                "favoriteId=${resolvedPlace.favoriteId} favoriteKey=${resolvedPlace.favoriteKey}"
        )
        return reminderId
    }

    suspend fun createEarlyReminderFromVosk(
        noteId: Long,
        rawText: String,
        intent: ReminderIntent,
    ): PendingVoiceReminder {
        val now = System.currentTimeMillis()
        val pending = when (intent) {
            is ReminderIntent.Time -> {
                val reminderId = scheduleTimeReminder(noteId, intent.triggerAtMillis, intent.label)
                PendingVoiceReminder(
                    noteId = noteId,
                    reminderId = reminderId,
                    rawVosk = rawText,
                    parsedAt = now,
                    usedFields = intent.usedFields,
                    intent = intent,
                    placeSnapshot = null,
                ).also {
                    Log.d(TAG, "earlyCreated reminderId=$reminderId noteId=$noteId type=TIME")
                }
            }

            is ReminderIntent.Place -> {
                val resolved = resolvePlace(intent.placeQuery)
                val sanitizedLabel = sanitizeLabel(intent.label)
                val reminderId = schedulePlaceReminder(noteId, intent, resolved, sanitizedLabel)
                val snapshot = intent.toSnapshot(resolved)
                PendingVoiceReminder(
                    noteId = noteId,
                    reminderId = reminderId,
                    rawVosk = rawText,
                    parsedAt = now,
                    usedFields = intent.usedFields,
                    intent = intent,
                    placeSnapshot = snapshot,
                ).also {
                    Log.d(TAG, "earlyCreated reminderId=$reminderId noteId=$noteId type=PLACE")
                }
            }
        }
        withContext(Dispatchers.IO) {
            databaseProvider().pendingVoiceReminderDao().insert(pending.toEntity())
        }
        return pending
    }

    suspend fun reconcileReminderWithWhisper(
        pending: PendingVoiceReminder,
        finalDecision: VoiceRouteDecision,
        finalText: String,
    ): ReconcileResult {
        val db = databaseProvider()
        val reminder = withContext(Dispatchers.IO) { db.reminderDao().getById(pending.reminderId) }
        withContext(Dispatchers.IO) { db.pendingVoiceReminderDao().deleteByReminderId(pending.reminderId) }
        if (reminder == null) {
            Log.w(TAG, "reconcile: reminderId=${pending.reminderId} missing")
            return ReconcileResult.Skipped
        }
        val result = when (finalDecision) {
            is VoiceRouteDecision.ReminderTime -> reconcileTimeReminder(pending, reminder, finalDecision.intent)
            is VoiceRouteDecision.ReminderPlace -> reconcilePlaceReminder(pending, reminder, finalDecision.intent)
            else -> handleNonReminder(pending, reminder)
        }
        val sanitized = finalText.replace("\"", "\\\"")
        Log.d(
            TAG,
            "reconciled reminderId=${pending.reminderId} decision=${finalDecision.logToken} result=${result.javaClass.simpleName} text=\"$sanitized\""
        )
        return result
    }

    private suspend fun scheduleTimeReminder(
        noteId: Long,
        triggerAt: Long,
        label: String,
    ): Long {
        val useCases = ReminderUseCases(appContext, databaseProvider(), alarmManagerProvider())
        val reminderId = useCases.scheduleAtEpoch(
            noteId = noteId,
            timeMillis = triggerAt,
            label = label
        )
        ReminderListSheet.notifyChangedBroadcast(appContext, noteId)
        return reminderId
    }

    private suspend fun schedulePlaceReminder(
        noteId: Long,
        intent: ReminderIntent.Place,
        resolvedPlace: ResolvedPlace,
        label: String,
    ): Long {
        val useCases = ReminderUseCases(appContext, databaseProvider(), alarmManagerProvider())
        val reminderId = useCases.scheduleGeofence(
            noteId = noteId,
            lat = resolvedPlace.latitude,
            lon = resolvedPlace.longitude,
            radiusMeters = intent.radiusMeters,
            every = intent.everyTime,
            label = label,
            cooldownMinutes = intent.cooldownMinutes,
            triggerOnExit = intent.transition == LocalPlaceIntentParser.Transition.EXIT,
            startingInside = resolvedPlace.startingInside
        )
        ReminderListSheet.notifyChangedBroadcast(appContext, noteId)
        return reminderId
    }

    private suspend fun reconcileTimeReminder(
        pending: PendingVoiceReminder,
        reminder: ReminderEntity,
        intent: ReminderIntent.Time,
    ): ReconcileResult {
        val newLabel = intent.label.trim()
        val sanitizedLabel = newLabel.takeIf { it.isNotEmpty() }
        val changedFields = mutableSetOf<Field>()
        if (reminder.label != sanitizedLabel) {
            changedFields += Field.LABEL
        }
        if (reminder.nextTriggerAt != intent.triggerAtMillis ||
            (reminder.type != ReminderEntity.TYPE_TIME_ONE_SHOT && reminder.type != ReminderEntity.TYPE_TIME_REPEATING)
        ) {
            changedFields += Field.TIME
        }
        if (pending.intent is ReminderIntent.Place) {
            changedFields += Field.PLACE
        }
        if (changedFields.isEmpty()) {
            return ReconcileResult.NoChange
        }
        val useCases = ReminderUseCases(appContext, databaseProvider(), alarmManagerProvider())
        useCases.updateTimeReminder(
            reminderId = reminder.id,
            nextTriggerAt = intent.triggerAtMillis,
            label = sanitizedLabel,
            repeatEveryMinutes = null,
        )
        ReminderListSheet.notifyChangedBroadcast(appContext, reminder.noteId)
        logFieldsChanged(reminder.id, changedFields)
        return ReconcileResult.Updated(changedFields)
    }

    private suspend fun reconcilePlaceReminder(
        pending: PendingVoiceReminder,
        reminder: ReminderEntity,
        intent: ReminderIntent.Place,
    ): ReconcileResult {
        return try {
            val snapshot = pending.placeSnapshot
            val resolved = when {
                snapshot != null && intent.placeQuery is LocalPlaceIntentParser.PlaceQuery.CurrentLocation &&
                    snapshot.query is PendingVoiceReminder.PlaceQuerySnapshot.CurrentLocation -> snapshot.resolved.toResolvedPlace()

                snapshot != null && intent.placeQuery is LocalPlaceIntentParser.PlaceQuery.Favorite &&
                    snapshot.query is PendingVoiceReminder.PlaceQuerySnapshot.Favorite &&
                    snapshot.query.id == intent.placeQuery.id -> snapshot.resolved.toResolvedPlace()

                else -> resolvePlace(intent.placeQuery)
            }

            val sanitizedLabel = sanitizeLabel(intent.label)
            val changedFields = mutableSetOf<Field>()
            if (reminder.label != sanitizedLabel) {
                changedFields += Field.LABEL
            }

            val latChanged = reminder.lat?.let { kotlin.math.abs(it - resolved.latitude) > 1e-4 } ?: true
            val lonChanged = reminder.lon?.let { kotlin.math.abs(it - resolved.longitude) > 1e-4 } ?: true
            val radiusChanged = (reminder.radius ?: intent.radiusMeters) != intent.radiusMeters
            val cooldownChanged = (reminder.cooldownMinutes ?: intent.cooldownMinutes) != intent.cooldownMinutes
            val everyChanged = (reminder.type == ReminderEntity.TYPE_LOC_EVERY) != intent.everyTime
            val exitChanged = (reminder.triggerOnExit || reminder.disarmedUntilExit) !=
                (intent.transition == LocalPlaceIntentParser.Transition.EXIT)
            if (latChanged || lonChanged || radiusChanged || cooldownChanged || everyChanged || exitChanged) {
                changedFields += Field.PLACE
            }
            if (pending.intent is ReminderIntent.Time) {
                changedFields += Field.TIME
            }

            if (changedFields.isEmpty()) {
                return ReconcileResult.NoChange
            }

            val useCases = ReminderUseCases(appContext, databaseProvider(), alarmManagerProvider())
            useCases.updateGeofenceReminder(
                reminderId = reminder.id,
                lat = resolved.latitude,
                lon = resolved.longitude,
                radius = intent.radiusMeters,
                every = intent.everyTime,
                disarmedUntilExit = intent.transition == LocalPlaceIntentParser.Transition.EXIT,
                cooldownMinutes = intent.cooldownMinutes,
                label = sanitizedLabel,
            )
            ReminderListSheet.notifyChangedBroadcast(appContext, reminder.noteId)
            logFieldsChanged(reminder.id, changedFields)
            ReconcileResult.Updated(changedFields)
        } catch (error: IncompleteException) {
            Log.w(TAG, "reconcilePlaceReminder(): incomplete for reminderId=${reminder.id}", error)
            ReconcileResult.MarkIncomplete
        }
    }

    private fun handleNonReminder(
        pending: PendingVoiceReminder,
        reminder: ReminderEntity,
    ): ReconcileResult {
        return if (reminder.status != ReminderEntity.STATUS_ACTIVE) {
            logFieldsChanged(reminder.id, pending.usedFields)
            ReconcileResult.MarkIncomplete
        } else {
            ReconcileResult.NoChange
        }
    }

    private fun logFieldsChanged(reminderId: Long, fields: Set<Field>) {
        if (fields.isEmpty()) return
        val joined = fields.joinToString(separator = ",") { it.name }
        Log.d(TAG, "fieldsChanged reminderId=$reminderId fields=$joined")
    }

    data class PendingVoiceReminder(
        val noteId: Long,
        val reminderId: Long,
        val rawVosk: String,
        val parsedAt: Long,
        val usedFields: Set<Field>,
        val intent: ReminderIntent,
        val placeSnapshot: PlaceSnapshot?,
    ) {
        data class PlaceSnapshot(
            val label: String,
            val transition: LocalPlaceIntentParser.Transition,
            val radiusMeters: Int,
            val cooldownMinutes: Int,
            val everyTime: Boolean,
            val query: PlaceQuerySnapshot,
            val resolved: ResolvedPlaceSnapshot,
        )

        sealed class PlaceQuerySnapshot {
            object CurrentLocation : PlaceQuerySnapshot()
            data class Favorite(
                val id: Long,
                val key: String,
                val lat: Double,
                val lon: Double,
                val spokenForm: String,
                val defaultRadiusMeters: Int,
                val defaultCooldownMinutes: Int,
                val defaultEveryTime: Boolean,
            ) : PlaceQuerySnapshot()
        }

        data class ResolvedPlaceSnapshot(
            val latitude: Double,
            val longitude: Double,
            val placeLabel: String?,
            val startingInside: Boolean,
            val favoriteId: Long?,
            val favoriteKey: String?,
        )

        companion object {
            fun fromEntity(entity: PendingVoiceReminderEntity): PendingVoiceReminder {
                val used = entity.usedFields
                    .split(',')
                    .mapNotNull { raw -> raw.takeIf { it.isNotBlank() }?.let { Field.valueOf(it) } }
                    .toSet()
                val payload = JSONObject(entity.intentPayload)
                return when (entity.intentType) {
                    "TIME" -> {
                        val intent = ReminderIntent.Time(
                            triggerAtMillis = payload.getLong("triggerAt"),
                            label = payload.getString("label"),
                        )
                        PendingVoiceReminder(
                            noteId = entity.noteId,
                            reminderId = entity.reminderId,
                            rawVosk = entity.rawVosk,
                            parsedAt = entity.parsedAt,
                            usedFields = used,
                            intent = intent,
                            placeSnapshot = null,
                        )
                    }

                    "PLACE" -> {
                        val transition = LocalPlaceIntentParser.Transition.valueOf(payload.getString("transition"))
                        val radius = payload.getInt("radiusMeters")
                        val cooldown = payload.getInt("cooldownMinutes")
                        val every = payload.getBoolean("everyTime")
                        val label = payload.getString("label")
                        val queryJson = payload.getJSONObject("query")
                        val querySnapshot = when (queryJson.getString("type")) {
                            "CURRENT" -> PlaceQuerySnapshot.CurrentLocation
                            "FAVORITE" -> PlaceQuerySnapshot.Favorite(
                                id = queryJson.getLong("id"),
                                key = queryJson.getString("key"),
                                lat = queryJson.getDouble("lat"),
                                lon = queryJson.getDouble("lon"),
                                spokenForm = queryJson.getString("spoken"),
                                defaultRadiusMeters = queryJson.getInt("defaultRadius"),
                                defaultCooldownMinutes = queryJson.getInt("defaultCooldown"),
                                defaultEveryTime = queryJson.getBoolean("defaultEvery"),
                            )

                            else -> throw IllegalArgumentException("Unknown place query type ${queryJson.getString("type")}")
                        }
                        val placeQuery = when (querySnapshot) {
                            is PlaceQuerySnapshot.CurrentLocation -> LocalPlaceIntentParser.PlaceQuery.CurrentLocation
                            is PlaceQuerySnapshot.Favorite -> LocalPlaceIntentParser.PlaceQuery.Favorite(
                                id = querySnapshot.id,
                                key = querySnapshot.key,
                                lat = querySnapshot.lat,
                                lon = querySnapshot.lon,
                                spokenForm = querySnapshot.spokenForm,
                                defaultRadiusMeters = querySnapshot.defaultRadiusMeters,
                                defaultCooldownMinutes = querySnapshot.defaultCooldownMinutes,
                                defaultEveryTime = querySnapshot.defaultEveryTime,
                            )
                        }
                        val parseResult = LocalPlaceIntentParser.PlaceParseResult(
                            transition = transition,
                            place = placeQuery,
                            radiusMeters = radius,
                            cooldownMinutes = cooldown,
                            everyTime = every,
                            label = label,
                        )
                        val intent = ReminderIntent.Place(parseResult)
                        val resolvedJson = payload.getJSONObject("resolved")
                        val resolvedSnapshot = ResolvedPlaceSnapshot(
                            latitude = resolvedJson.getDouble("latitude"),
                            longitude = resolvedJson.getDouble("longitude"),
                            placeLabel = resolvedJson.optString("placeLabel").takeIf { !resolvedJson.isNull("placeLabel") },
                            startingInside = resolvedJson.getBoolean("startingInside"),
                            favoriteId = if (resolvedJson.isNull("favoriteId")) null else resolvedJson.getLong("favoriteId"),
                            favoriteKey = resolvedJson.optString("favoriteKey").takeIf { !resolvedJson.isNull("favoriteKey") },
                        )
                        val snapshot = PlaceSnapshot(
                            label = label,
                            transition = transition,
                            radiusMeters = radius,
                            cooldownMinutes = cooldown,
                            everyTime = every,
                            query = querySnapshot,
                            resolved = resolvedSnapshot,
                        )
                        PendingVoiceReminder(
                            noteId = entity.noteId,
                            reminderId = entity.reminderId,
                            rawVosk = entity.rawVosk,
                            parsedAt = entity.parsedAt,
                            usedFields = used,
                            intent = intent,
                            placeSnapshot = snapshot,
                        )
                    }

                    else -> throw IllegalArgumentException("Unknown intent type ${entity.intentType}")
                }
            }
        }
    }

    private fun ReminderIntent.Place.toSnapshot(resolved: ResolvedPlace): PendingVoiceReminder.PlaceSnapshot {
        val querySnapshot = when (val query = placeQuery) {
            LocalPlaceIntentParser.PlaceQuery.CurrentLocation -> PendingVoiceReminder.PlaceQuerySnapshot.CurrentLocation
            is LocalPlaceIntentParser.PlaceQuery.Favorite -> PendingVoiceReminder.PlaceQuerySnapshot.Favorite(
                id = query.id,
                key = query.key,
                lat = query.lat,
                lon = query.lon,
                spokenForm = query.spokenForm,
                defaultRadiusMeters = query.defaultRadiusMeters,
                defaultCooldownMinutes = query.defaultCooldownMinutes,
                defaultEveryTime = query.defaultEveryTime,
            )
        }
        val resolvedSnapshot = PendingVoiceReminder.ResolvedPlaceSnapshot(
            latitude = resolved.latitude,
            longitude = resolved.longitude,
            placeLabel = resolved.placeLabel,
            startingInside = resolved.startingInside,
            favoriteId = resolved.favoriteId,
            favoriteKey = resolved.favoriteKey,
        )
        return PendingVoiceReminder.PlaceSnapshot(
            label = label,
            transition = transition,
            radiusMeters = radiusMeters,
            cooldownMinutes = cooldownMinutes,
            everyTime = everyTime,
            query = querySnapshot,
            resolved = resolvedSnapshot,
        )
    }

    private fun PendingVoiceReminder.ResolvedPlaceSnapshot.toResolvedPlace(): ResolvedPlace {
        return ResolvedPlace(
            latitude = latitude,
            longitude = longitude,
            placeLabel = placeLabel,
            startingInside = startingInside,
            favoriteId = favoriteId,
            favoriteKey = favoriteKey,
        )
    }

    private fun PendingVoiceReminder.toEntity(): PendingVoiceReminderEntity {
        val usedJoined = usedFields.joinToString(separator = ",") { it.name }
        val (type, payload) = when (val snapshotIntent = intent) {
            is ReminderIntent.Time -> "TIME" to JSONObject().apply {
                put("triggerAt", snapshotIntent.triggerAtMillis)
                put("label", snapshotIntent.label)
            }

            is ReminderIntent.Place -> {
                val snapshot = placeSnapshot
                    ?: throw IllegalStateException("Place snapshot missing for reminderId=$reminderId")
                val queryJson = JSONObject().apply {
                    when (val query = snapshot.query) {
                        is PendingVoiceReminder.PlaceQuerySnapshot.CurrentLocation -> put("type", "CURRENT")
                        is PendingVoiceReminder.PlaceQuerySnapshot.Favorite -> {
                            put("type", "FAVORITE")
                            put("id", query.id)
                            put("key", query.key)
                            put("lat", query.lat)
                            put("lon", query.lon)
                            put("spoken", query.spokenForm)
                            put("defaultRadius", query.defaultRadiusMeters)
                            put("defaultCooldown", query.defaultCooldownMinutes)
                            put("defaultEvery", query.defaultEveryTime)
                        }
                    }
                }
                val resolvedJson = JSONObject().apply {
                    put("latitude", snapshot.resolved.latitude)
                    put("longitude", snapshot.resolved.longitude)
                    if (snapshot.resolved.placeLabel != null) {
                        put("placeLabel", snapshot.resolved.placeLabel)
                    } else {
                        put("placeLabel", JSONObject.NULL)
                    }
                    put("startingInside", snapshot.resolved.startingInside)
                    if (snapshot.resolved.favoriteId != null) {
                        put("favoriteId", snapshot.resolved.favoriteId)
                    } else {
                        put("favoriteId", JSONObject.NULL)
                    }
                    if (snapshot.resolved.favoriteKey != null) {
                        put("favoriteKey", snapshot.resolved.favoriteKey)
                    } else {
                        put("favoriteKey", JSONObject.NULL)
                    }
                }
                "PLACE" to JSONObject().apply {
                    put("label", snapshot.label)
                    put("transition", snapshot.transition.name)
                    put("radiusMeters", snapshot.radiusMeters)
                    put("cooldownMinutes", snapshot.cooldownMinutes)
                    put("everyTime", snapshot.everyTime)
                    put("query", queryJson)
                    put("resolved", resolvedJson)
                }
            }
        }
        return PendingVoiceReminderEntity(
            noteId = noteId,
            reminderId = reminderId,
            rawVosk = rawVosk,
            parsedAt = parsedAt,
            usedFields = usedJoined,
            intentType = type,
            intentPayload = payload.toString(),
        )
    }

    sealed class ReconcileResult {
        object NoChange : ReconcileResult()
        data class Updated(val changedFields: Set<Field>) : ReconcileResult()
        object MarkIncomplete : ReconcileResult()
        object Skipped : ReconcileResult()
    }

    class IncompleteException(message: String? = null) : Exception(message)

    class FavoriteNotFoundException(val query: String) : Exception(
        "Favorite not found for \"$query\"",
    )

    private data class ResolvedPlace(
        val latitude: Double,
        val longitude: Double,
        val placeLabel: String?,
        val startingInside: Boolean,
        val favoriteId: Long? = null,
        val favoriteKey: String? = null,
    )

    private fun ResolvedPlace.toLogString(
        query: LocalPlaceIntentParser.PlaceQuery,
    ): String {
        return when (query) {
            is LocalPlaceIntentParser.PlaceQuery.Favorite ->
                "Favorite(id=${query.id}, key=${query.key}, lat=${query.lat}, lon=${query.lon})"

            LocalPlaceIntentParser.PlaceQuery.CurrentLocation ->
                "CurrentLocation(lat=$latitude, lon=$longitude)"
        }
    }

    private suspend fun resolvePlace(
        query: LocalPlaceIntentParser.PlaceQuery,
    ): ResolvedPlace = when (query) {
        is LocalPlaceIntentParser.PlaceQuery.CurrentLocation -> {
            val location = currentLocationResolver()
            ResolvedPlace(
                latitude = location.latitude,
                longitude = location.longitude,
                placeLabel = null,
                startingInside = true,
            )
        }

        is LocalPlaceIntentParser.PlaceQuery.Favorite -> {
            ResolvedPlace(
                latitude = query.lat,
                longitude = query.lon,
                placeLabel = query.spokenForm,
                startingInside = false,
                favoriteId = query.id,
                favoriteKey = query.key,
            )
        }
    }

    private fun sanitizeLabel(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw IncompleteException("Parsed label was empty")
        }
        return trimmed
    }

    companion object {
        private const val TAG = "ReminderExecutor"
    }
}

private fun resolveCurrentLocation(appContext: Context): Location {
    val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val fineGranted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarseGranted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    if (!fineGranted && !coarseGranted) {
        throw ReminderExecutor.IncompleteException("Location permission missing")
    }
    val gpsEnabled = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    if (!gpsEnabled) {
        throw ReminderExecutor.IncompleteException("GPS provider disabled")
    }
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )
    val location = providers.firstNotNullOfOrNull { provider ->
        runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
    }
    return location ?: throw ReminderExecutor.IncompleteException("No last known location")
}
