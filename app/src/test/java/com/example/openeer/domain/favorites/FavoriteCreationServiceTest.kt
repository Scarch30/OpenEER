package com.example.openeer.domain.favorites

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.openeer.data.AppDatabase
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FavoriteCreationServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        resetDatabase()
    }

    @After
    fun tearDown() {
        resetDatabase()
    }

    @Test
    fun `createFavorite persists entity`() = runBlocking {
        FavoriteCreationService.createFavorite(
            context,
            FavoriteCreationRequest(
                name = "Parc",
                latitude = 48.8566,
                longitude = 2.3522,
                radiusMeters = 123,
                cooldownMinutes = 45,
                everyTime = true,
                source = FavoriteCreationRequest.Source.MAP_TAP
            )
        )

        val database = AppDatabase.getInstance(context)
        val repository = FavoritesRepository(database.favoriteDao())
        val favorites = repository.getAll()

        assertEquals(1, favorites.size)
        val favorite = favorites.first()
        assertEquals("Parc", favorite.displayName)
        assertEquals(48.8566, favorite.lat, 0.0001)
        assertEquals(2.3522, favorite.lon, 0.0001)
        assertEquals(123, favorite.defaultRadiusMeters)
        assertEquals(45, favorite.defaultCooldownMinutes)
        assertTrue(favorite.defaultEveryTime)
    }

    private fun resetDatabase() {
        val field = AppDatabase::class.java.getDeclaredField("INSTANCE")
        field.isAccessible = true
        val current = field.get(null) as? AppDatabase
        current?.close()
        field.set(null, null)
        if (this::context.isInitialized) {
            context.deleteDatabase("openEER.db")
        }
    }
}

