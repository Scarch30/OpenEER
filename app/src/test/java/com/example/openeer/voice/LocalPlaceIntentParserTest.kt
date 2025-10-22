package com.example.openeer.voice

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaceIntentParserTest {

    @After
    fun tearDown() {
        LocalPlaceIntentParser.favoriteResolver = null
    }

    @Test
    fun `current location enter defaults`() {
        val input = "Rappelle-moi d’acheter du pain quand j’arrive ici"
        val result = LocalPlaceIntentParser.parse(input)
        assertNotNull(result)
        result!!
        assertEquals(LocalPlaceIntentParser.Transition.ENTER, result.transition)
        assertTrue(result.query is LocalPlaceIntentParser.PlaceQuery.CurrentLocation)
        assertEquals(100, result.radiusMeters)
        assertEquals(30, result.cooldownMinutes)
        assertFalse(result.everyTime)
        assertEquals("acheter du pain", result.label)
    }

    @Test
    fun `current location exit with options`() {
        val input = "Pense à badger quand je pars d’ici à chaque fois délai 45 minutes"
        val result = LocalPlaceIntentParser.parse(input)
        assertNotNull(result)
        result!!
        assertEquals(LocalPlaceIntentParser.Transition.EXIT, result.transition)
        assertTrue(result.query is LocalPlaceIntentParser.PlaceQuery.CurrentLocation)
        assertEquals(100, result.radiusMeters)
        assertEquals(45, result.cooldownMinutes)
        assertTrue(result.everyTime)
        assertEquals("badger", result.label)
    }

    @Test
    fun `free text location extracted`() {
        val input = "Rappelle-moi de passer au Biocoop quand j’arrive"
        val result = LocalPlaceIntentParser.parse(input)
        assertNotNull(result)
        result!!
        assertEquals(LocalPlaceIntentParser.Transition.ENTER, result.transition)
        val query = result.query as LocalPlaceIntentParser.PlaceQuery.FreeText
        assertEquals("Biocoop", query.text)
        assertEquals(100, result.radiusMeters)
        assertEquals(30, result.cooldownMinutes)
        assertFalse(result.everyTime)
        assertEquals("passer", result.label)
    }

    @Test
    fun `radius parsed from sentence`() {
        val input = "Rappelle-moi de passer au Biocoop quand j’arrive dans un rayon de 200 m"
        val result = LocalPlaceIntentParser.parse(input)
        assertNotNull(result)
        result!!
        val query = result.query as LocalPlaceIntentParser.PlaceQuery.FreeText
        assertEquals("Biocoop", query.text)
        assertEquals(200, result.radiusMeters)
        assertEquals("passer", result.label)
    }

    @Test
    fun `label extracted when action follows location`() {
        val input = "Quand je pars de la gare, rappelle-moi de prendre un taxi"
        val result = LocalPlaceIntentParser.parse(input)
        assertNotNull(result)
        result!!
        assertEquals(LocalPlaceIntentParser.Transition.EXIT, result.transition)
        assertTrue(result.query is LocalPlaceIntentParser.PlaceQuery.FreeText)
        assertEquals("la gare", (result.query as LocalPlaceIntentParser.PlaceQuery.FreeText).text)
        assertEquals("prendre un taxi", result.label)
    }

    @Test
    fun `missing action returns null`() {
        val input = "Quand j'arrive ici"
        val result = LocalPlaceIntentParser.parse(input)
        assertNull(result)
    }

    @Test
    fun `favorite match overrides defaults`() {
        LocalPlaceIntentParser.favoriteResolver = LocalPlaceIntentParser.FavoriteResolver { text ->
            when (text.lowercase()) {
                "maison" -> LocalPlaceIntentParser.FavoriteMatch(
                    id = 42L,
                    lat = 48.8566,
                    lon = 2.3522,
                    spokenForm = text,
                    defaultRadiusMeters = 200,
                    defaultCooldownMinutes = 15,
                    defaultEveryTime = true,
                )

                else -> null
            }
        }

        val input = "Rappelle-moi de sortir les poubelles à la maison quand j’arrive"
        val result = LocalPlaceIntentParser.parse(input)
        assertNotNull(result)
        result!!
        assertEquals(LocalPlaceIntentParser.Transition.ENTER, result.transition)
        val query = result.query as LocalPlaceIntentParser.PlaceQuery.Favorite
        assertEquals(42L, query.id)
        assertEquals(48.8566, query.lat, 0.0001)
        assertEquals(2.3522, query.lon, 0.0001)
        assertEquals(200, result.radiusMeters)
        assertEquals(15, result.cooldownMinutes)
        assertTrue(result.everyTime)
        assertEquals("sortir les poubelles", result.label)
    }

    @Test
    fun `favorite resolver fallback with connector`() {
        LocalPlaceIntentParser.favoriteResolver = LocalPlaceIntentParser.FavoriteResolver { text ->
            when (text.lowercase()) {
                "chez moi" -> LocalPlaceIntentParser.FavoriteMatch(
                    id = 7L,
                    lat = 45.0,
                    lon = 3.0,
                    spokenForm = text,
                    defaultRadiusMeters = 120,
                    defaultCooldownMinutes = 20,
                    defaultEveryTime = false,
                )

                else -> null
            }
        }

        val input = "Pense à arroser les plantes quand j’arrive chez moi"
        val result = LocalPlaceIntentParser.parse(input)
        assertNotNull(result)
        result!!
        val query = result.query as LocalPlaceIntentParser.PlaceQuery.Favorite
        assertEquals(7L, query.id)
        assertEquals("arroser les plantes", result.label)
        assertEquals("chez moi", query.spokenForm)
        assertEquals(120, result.radiusMeters)
        assertEquals(20, result.cooldownMinutes)
        assertFalse(result.everyTime)
    }

    @Test
    fun `enter transition synonyms resolve favorites`() {
        LocalPlaceIntentParser.favoriteResolver = LocalPlaceIntentParser.FavoriteResolver { text ->
            when (text.lowercase()) {
                "maison" -> LocalPlaceIntentParser.FavoriteMatch(
                    id = 99L,
                    lat = 12.0,
                    lon = 34.0,
                    spokenForm = text,
                    defaultRadiusMeters = 180,
                    defaultCooldownMinutes = 25,
                    defaultEveryTime = false,
                )

                else -> null
            }
        }

        val input = "Rappelle-moi de manger une pizza quand je rentre à la maison"
        val result = LocalPlaceIntentParser.parse(input)
        assertNotNull(result)
        result!!
        val query = result.query as LocalPlaceIntentParser.PlaceQuery.Favorite
        assertEquals(99L, query.id)
        assertEquals("manger une pizza", result.label)
        assertEquals(180, result.radiusMeters)
        assertEquals(25, result.cooldownMinutes)
        assertFalse(result.everyTime)
    }

    @Test
    fun `exit transition synonyms parsed`() {
        val input = "Fais-moi penser à appeler un taxi quand je m'en vais du bureau"
        val result = LocalPlaceIntentParser.parse(input)
        assertNotNull(result)
        result!!
        assertEquals(LocalPlaceIntentParser.Transition.EXIT, result.transition)
        val query = result.query as LocalPlaceIntentParser.PlaceQuery.FreeText
        assertEquals("bureau", query.text)
        assertEquals("appeler un taxi", result.label)
    }
}

