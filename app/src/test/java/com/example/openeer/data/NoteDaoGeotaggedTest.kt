package com.example.openeer.data

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.openeer.rules.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NoteDaoGeotaggedTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    // TODO(sprint3): migrate to shared in-memory helper when available
    @get:Rule
    val dispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase
    private lateinit var noteDao: NoteDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        noteDao = db.noteDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun geotaggedNotesFiltersAndOrdersByRecencyAndId() = runTest {
        val notes = listOf(
            Note(
                title = "With coordinates old",
                body = "",
                createdAt = 1_000L,
                updatedAt = 2_000L,
                lat = 48.8566,
                lon = 2.3522
            ),
            Note(
                title = "No location",
                body = "",
                createdAt = 2_000L,
                updatedAt = 3_000L
            ),
            Note(
                title = "With place newer",
                body = "",
                createdAt = 3_000L,
                updatedAt = 5_000L,
                placeLabel = "Paris"
            ),
            Note(
                title = "With coordinates same time",
                body = "",
                createdAt = 4_000L,
                updatedAt = 5_000L,
                lat = 40.7128,
                lon = -74.0060
            ),
            Note(
                title = "Partial location",
                body = "",
                createdAt = 5_000L,
                updatedAt = 6_000L,
                lat = 12.34,
                lon = null
            ),
            Note(
                title = "Blank place",
                body = "",
                createdAt = 6_000L,
                updatedAt = 7_000L,
                placeLabel = "   "
            )
        )

        val ids = notes.map { noteDao.insert(it) }

        advanceUntilIdle()

        val results = noteDao.geotaggedNotes().first()
        val resultIds = results.map { it.id }

        val expectedIds = listOf(ids[3], ids[2], ids[0])
        assertEquals(expectedIds, resultIds)
    }
}
