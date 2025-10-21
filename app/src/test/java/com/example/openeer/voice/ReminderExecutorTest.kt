package com.example.openeer.voice

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.reminders.ReminderDao
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
class ReminderExecutorTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var reminderDao: ReminderDao
    private lateinit var alarmManager: AlarmManager
    private lateinit var executor: ReminderExecutor

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        reminderDao = db.reminderDao()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        executor = ReminderExecutor(context, { db }, { alarmManager })
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `create reminder from voice attaches to note`() = runBlocking {
        val zone = ZoneId.of("Europe/Paris")
        val now = ZonedDateTime.of(2023, 10, 20, 8, 0, 0, 0, zone)
        ShadowSystemClock.setCurrentTimeMillis(now.toInstant().toEpochMilli())

        val noteId = db.noteDao().insert(Note(title = "Test", body = "Corps existant"))

        val reminderId = executor.createFromVoice(noteId, "Rappelle-moi demain à 9h d'appeler Paul")

        val reminders = reminderDao.listForNoteOrdered(noteId)
        assertEquals(1, reminders.size)
        val reminder = reminders.first()
        assertEquals(noteId, reminder.noteId)
        assertEquals("appeler Paul", reminder.label)
        assertEquals(reminderId, reminder.id)
        val expectedTrigger = now.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0)
        assertEquals(expectedTrigger.toInstant().toEpochMilli(), reminder.nextTriggerAt)

        val persistedNote = db.noteDao().getByIdOnce(noteId)
        assertEquals("Corps existant", persistedNote?.body)
        val allNotes = db.noteDao().getAllOnce()
        assertEquals(1, allNotes.size)
    }

    @Test
    fun `parser failure throws`() = runBlocking {
        val now = ZonedDateTime.of(2023, 10, 20, 8, 0, 0, 0, ZoneId.of("Europe/Paris"))
        ShadowSystemClock.setCurrentTimeMillis(now.toInstant().toEpochMilli())
        val noteId = db.noteDao().insert(Note(title = "Test"))

        assertFailsWith<IllegalArgumentException> {
            executor.createFromVoice(noteId, "Rappelle-moi d'appeler Paul")
        }

        val reminders = reminderDao.listForNoteOrdered(noteId)
        assertTrue(reminders.isEmpty())
    }
}
