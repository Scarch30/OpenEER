package com.example.openeer.voice

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.location.LocationManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.reminders.ReminderDao
import com.example.openeer.domain.favorites.FavoritesRepository
import com.example.openeer.domain.favorites.FavoritesService
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
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowApplication
import org.robolectric.shadows.ShadowLocationManager
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
class ReminderExecutorTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var reminderDao: ReminderDao
    private lateinit var alarmManager: AlarmManager
    private lateinit var executor: ReminderExecutor
    private lateinit var voiceDependencies: VoiceDependencies

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        reminderDao = db.reminderDao()
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val favoritesRepository = FavoritesRepository(db.favoriteDao())
        val favoritesService = FavoritesService(favoritesRepository)
        val placeParser = LocalPlaceIntentParser()
        voiceDependencies = VoiceDependencies(favoritesService, placeParser)
        executor = ReminderExecutor(context, voiceDependencies, { db }, { alarmManager })
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

    @Test
    fun `current location reminder requires gps`() = runBlocking {
        ShadowApplication.getInstance().grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowLocationManager: ShadowLocationManager = Shadows.shadowOf(locationManager)
        shadowLocationManager.setProviderEnabled(LocationManager.GPS_PROVIDER, false)

        val noteId = db.noteDao().insert(Note(title = "Test"))

        assertFailsWith<ReminderExecutor.IncompleteException> {
            executor.createPlaceReminderFromVoice(noteId, "Rappelle-moi d’acheter du pain quand j’arrive ici")
        }

        val reminders = reminderDao.listForNoteOrdered(noteId)
        assertTrue(reminders.isEmpty())
    }

    @Test
    fun `unknown address results in incomplete`() = runBlocking {
        val noteId = db.noteDao().insert(Note(title = "Geo"))
        val geoExecutor = ReminderExecutor(
            context,
            voiceDependencies,
            { db },
            { alarmManager },
            geocodeResolver = { null }
        )

        assertFailsWith<ReminderExecutor.IncompleteException> {
            geoExecutor.createPlaceReminderFromVoice(noteId, "Rappelle-moi de passer au Xyzzzz quand j’arrive")
        }

        val reminders = reminderDao.listForNoteOrdered(noteId)
        assertTrue(reminders.isEmpty())
    }

    @Test
    fun `geocoded address creates geofence reminder`() = runBlocking {
        val noteId = db.noteDao().insert(Note(title = "Geo note", body = "Body"))
        val lat = 48.8566
        val lon = 2.3522
        val geoExecutor = ReminderExecutor(
            context,
            voiceDependencies,
            { db },
            { alarmManager },
            geocodeResolver = {
                ReminderExecutor.GeocodedPlace(
                    latitude = lat,
                    longitude = lon,
                    label = "Paris"
                )
            }
        )

        val reminderId = geoExecutor.createPlaceReminderFromVoice(
            noteId,
            "Rappelle-moi de passer à Paris quand j’arrive"
        )

        val reminders = reminderDao.listForNoteOrdered(noteId)
        assertEquals(1, reminders.size)
        val reminder = reminders.first()
        assertEquals(reminderId, reminder.id)
        assertEquals(lat, reminder.lat, 0.0001)
        assertEquals(lon, reminder.lon, 0.0001)
        assertEquals(100, reminder.radius)
        assertEquals(30, reminder.cooldownMinutes)
        assertTrue(reminder.noteId == noteId)
        assertEquals("Body", db.noteDao().getByIdOnce(noteId)?.body)
    }
}
