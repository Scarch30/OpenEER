package com.example.openeer.voice

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import com.example.openeer.data.AppDatabase
import com.example.openeer.domain.ReminderUseCases
import com.example.openeer.ui.sheets.ReminderListSheet

class ReminderExecutor(
    context: Context,
    private val databaseProvider: () -> AppDatabase = {
        AppDatabase.getInstance(context.applicationContext)
    },
    private val alarmManagerProvider: () -> AlarmManager = {
        context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }
) {

    private val appContext = context.applicationContext

    suspend fun createFromVoice(noteId: Long, labelFromWhisper: String): Long {
        val parseResult = LocalTimeIntentParser.parseReminder(labelFromWhisper)
            ?: throw IllegalArgumentException("Unable to parse reminder timing from voice input")
        val useCases = ReminderUseCases(appContext, databaseProvider(), alarmManagerProvider())
        val triggerAt = parseResult.triggerAtMillis
        val reminderId = useCases.scheduleAtEpoch(
            noteId = noteId,
            timeMillis = triggerAt,
            label = parseResult.label
        )
        ReminderListSheet.notifyChangedBroadcast(appContext, noteId)
        Log.d(
            TAG,
            "createFromVoice(): reminderId=$reminderId noteId=$noteId triggerAt=$triggerAt label='${parseResult.label}'"
        )
        return reminderId
    }

    companion object {
        private const val TAG = "ReminderExecutor"
    }
}
