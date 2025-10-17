package com.example.openeer.domain

import android.app.AlarmManager
import android.content.Context
import com.example.openeer.data.AppDatabase

class ReminderCleaner(
    private val context: Context,
    private val db: AppDatabase,
    private val alarmManager: AlarmManager
) {
    private val useCases: ReminderUseCases by lazy {
        ReminderUseCases(context, db, alarmManager)
    }

    suspend fun cancelAllForNote(noteId: Long) {
        useCases.cancelAllForNote(noteId)
    }
}
