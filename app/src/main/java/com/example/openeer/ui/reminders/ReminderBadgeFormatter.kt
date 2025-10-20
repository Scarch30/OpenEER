package com.example.openeer.ui.reminders

import android.content.Context
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.reminders.ReminderEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val TIME_TYPES = setOf(
    ReminderEntity.TYPE_TIME_ONE_SHOT,
    ReminderEntity.TYPE_TIME_REPEATING
)
private val GEO_TYPES = setOf(
    ReminderEntity.TYPE_LOC_ONCE,
    ReminderEntity.TYPE_LOC_EVERY
)

/** Lightweight state used to bind reminder badges in list/detail footers. */
data class ReminderBadgeState(
    val iconText: String,
    val contentDescription: String,
    val tooltip: String
)

object ReminderBadgeFormatter {

    suspend fun loadState(context: Context, noteId: Long): ReminderBadgeState? = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(context.applicationContext).reminderDao()
        val active = dao.getActiveByNoteId(noteId)
        buildState(context, active)
    }

    fun buildState(context: Context, activeReminders: List<ReminderEntity>): ReminderBadgeState? {
        if (activeReminders.isEmpty()) return null

        val timeCount = activeReminders.count { it.type in TIME_TYPES }
        val geoCount = activeReminders.count { it.type in GEO_TYPES }
        val total = activeReminders.size

        val icon = when {
            timeCount > 0 && geoCount > 0 -> "🕑📍"
            geoCount > 0 -> "📍"
            else -> "🕑"
        }

        val res = context.resources
        val tooltip = res.getQuantityString(R.plurals.reminder_badge_tooltip, total, total)
        val contentDescription = when {
            timeCount > 0 && geoCount > 0 ->
                context.getString(R.string.reminder_badge_cd_mixed, total, timeCount, geoCount)

            timeCount > 0 -> res.getQuantityString(R.plurals.reminder_badge_cd_time, timeCount, timeCount)
            else -> res.getQuantityString(R.plurals.reminder_badge_cd_place, geoCount, geoCount)
        }

        return ReminderBadgeState(icon, contentDescription, tooltip)
    }

}
