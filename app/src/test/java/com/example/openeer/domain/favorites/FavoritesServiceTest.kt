package com.example.openeer.domain.favorites

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.openeer.data.AppDatabase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FavoritesServiceTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: FavoritesRepository
    private lateinit var service: FavoritesService

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = FavoritesRepository(db.favoriteDao())
        service = FavoritesService(repository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun createReadUpdateDeleteFlow() = runBlocking {
        val favoriteId = service.createFavorite(
            displayName = "Parc",
            lat = 1.23,
            lon = 4.56,
            aliases = listOf("Le Parc"),
            defaultRadiusMeters = 150,
            defaultCooldownMinutes = 60,
            defaultEveryTime = true
        )

        val created = repository.getById(favoriteId)
        assertNotNull(created)
        assertEquals("parc", created.key)
        assertEquals(1.23, created.lat, 0.0001)
        assertEquals(4.56, created.lon, 0.0001)
        assertEquals(150, created.defaultRadiusMeters)
        assertEquals(60, created.defaultCooldownMinutes)
        assertTrue(created.defaultEveryTime)
        val aliases = parseAliases(created.aliasesJson)
        assertTrue("parc" in aliases)
        assertTrue("le parc" in aliases)

        service.updateFavorite(
            id = favoriteId,
            defaultRadiusMeters = 250,
            defaultCooldownMinutes = 15,
            defaultEveryTime = false
        )

        val updated = repository.getById(favoriteId)
        assertNotNull(updated)
        assertEquals(250, updated.defaultRadiusMeters)
        assertEquals(15, updated.defaultCooldownMinutes)
        assertTrue(!updated.defaultEveryTime)

        val all = service.getAll()
        assertEquals(1, all.size)

        service.deleteFavorite(favoriteId)
        assertTrue(service.getAll().isEmpty())
        assertNull(repository.getById(favoriteId))
    }

    @Test
    fun ensureUniqueKeys() = runBlocking {
        val firstId = service.createFavorite("Bureau", 0.0, 0.0)
        val secondId = service.createFavorite("Bureau", 1.0, 1.0)

        val first = repository.getById(firstId)
        val second = repository.getById(secondId)
        assertEquals("bureau", first?.key)
        assertEquals("bureau-2", second?.key)
    }

    @Test
    fun defaultAliasesInjectedForKnownNames() = runBlocking {
        val favoriteId = service.createFavorite("Maison", 10.0, 20.0)
        val favorite = repository.getById(favoriteId)
        assertNotNull(favorite)
        val aliases = parseAliases(favorite.aliasesJson)
        assertTrue(aliases.containsAll(listOf("maison", "chez moi", "home")))

        val fromAlias = service.findByAlias("chez moi")
        assertNotNull(fromAlias)
        assertEquals(favoriteId, fromAlias.id)
    }

    private fun parseAliases(json: String): List<String> {
        val array = JSONArray(json)
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (!value.isNullOrEmpty()) {
                result.add(value)
            }
        }
        return result
    }
}
