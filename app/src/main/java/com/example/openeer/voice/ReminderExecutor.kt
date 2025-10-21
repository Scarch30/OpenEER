package com.example.openeer.voice

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import com.example.openeer.data.AppDatabase
import com.example.openeer.domain.ReminderUseCases
import com.example.openeer.ui.sheets.ReminderListSheet
import java.util.concurrent.TimeUnit

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

    suspend fun createFromVoice(noteId: Long, label: String): Long {
        val useCases = ReminderUseCases(appContext, databaseProvider(), alarmManagerProvider())
        val triggerAt = System.currentTimeMillis() + PLACEHOLDER_DELAY_MS
        val reminderId = useCases.scheduleAtEpoch(
            noteId = noteId,
            timeMillis = triggerAt,
            label = label
        )
        ReminderListSheet.notifyChangedBroadcast(appContext, noteId)
        Log.d(TAG, "createFromVoice(): reminderId=$reminderId noteId=$noteId triggerAt=$triggerAt")
        return reminderId
    }

    companion object {
        private const val TAG = "ReminderExecutor"
        private val PLACEHOLDER_DELAY_MS = TimeUnit.MINUTES.toMillis(1)
    }
}
