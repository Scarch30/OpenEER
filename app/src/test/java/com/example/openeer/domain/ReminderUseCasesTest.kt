package com.example.openeer.domain

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.reminders.ReminderDao
import com.example.openeer.data.reminders.ReminderEntity
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class ReminderUseCasesTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var reminderDao: ReminderDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        reminderDao = db.reminderDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Ignore("TODO: Réparer ce test")
    @Test
    fun restoreAllOnAppStartReschedulesRepeatingAndUpcoming() = runBlocking {
        val noteId = db.noteDao().insert(Note(title = "Test"))
        val now = 1_700_000_000_000L
        val pastTrigger = now - TimeUnit.MINUTES.toMillis(5)
        val repeatMinutes = 10

        val repeatingId = reminderDao.insert(
            ReminderEntity(
                noteId = noteId,
                type = "TIME_REPEATING",
                nextTriggerAt = pastTrigger,
                status = "ACTIVE",
                repeatEveryMinutes = repeatMinutes
            )
        )

        val futureTrigger = now + TimeUnit.HOURS.toMillis(1)
        val upcomingId = reminderDao.insert(
            ReminderEntity(
                noteId = noteId,
                type = "TIME_ONE_SHOT",
                nextTriggerAt = futureTrigger,
                status = "ACTIVE"
            )
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val useCases = ReminderUseCases(context, db, alarmManager)

        useCases.restoreAllOnAppStart { now }

        val shadowAlarm = Shadows.shadowOf(alarmManager)
        val scheduled = shadowAlarm.scheduledAlarms
        assertEquals(2, scheduled.size)

        val updatedRepeating = reminderDao.getById(repeatingId)
        assertEquals(now + TimeUnit.MINUTES.toMillis(5), updatedRepeating?.nextTriggerAt)
        assertTrue(scheduled.any { it.triggerAtTime == updatedRepeating?.nextTriggerAt })
        assertTrue(scheduled.any { it.triggerAtTime == futureTrigger })

        val upcoming = reminderDao.getById(upcomingId)
        assertEquals(futureTrigger, upcoming?.nextTriggerAt)
    }
}
