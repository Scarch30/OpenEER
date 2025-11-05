package com.example.openeer.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class LocalPlaceIntentParserTest {

    private val emptyResolver = LocalPlaceIntentParser.FavoriteResolver { null }

    @Test
    fun `current location enter defaults`() {
        val parser = LocalPlaceIntentParser(emptyResolver)
        val input = "Rappelle-moi d’acheter du pain quand j’arrive ici"
        val result = parser.parse(input)
        assertNotNull(result)
        result!!
        assertEquals(LocalPlaceIntentParser.Transition.ENTER, result.transition)
        assertTrue(result.place is LocalPlaceIntentParser.PlaceQuery.CurrentLocation)
        assertEquals(100, result.radiusMeters)
        assertEquals(30, result.cooldownMinutes)
        assertFalse(result.everyTime)
        assertEquals("acheter du pain", result.label)
    }

    @Test
    fun `current location exit with options`() {
        val parser = LocalPlaceIntentParser(emptyResolver)
        val input = "Pense à badger quand je pars d’ici à chaque fois délai 45 minutes"
        val result = parser.parse(input)
        assertNotNull(result)
        result!!
        assertEquals(LocalPlaceIntentParser.Transition.EXIT, result.transition)
        assertTrue(result.place is LocalPlaceIntentParser.PlaceQuery.CurrentLocation)
        assertEquals(100, result.radiusMeters)
        assertEquals(45, result.cooldownMinutes)
        assertTrue(result.everyTime)
        assertEquals("badger", result.label)
    }

    @Ignore("TODO: Réparer ce test")
    @Test
    fun `free text location extracted`() {
        val parser = LocalPlaceIntentParser(emptyResolver)
        val input = "Rappelle-moi de passer au Biocoop quand j’arrive"
        val result = parser.parse(input)
        assertNotNull(result)
        result!!
        assertEquals(LocalPlaceIntentParser.Transition.ENTER, result.transition)
        val query = result.place as LocalPlaceIntentParser.PlaceQuery.FreeText
        assertEquals("biocoop", query.normalized)
        assertEquals("Biocoop", query.spokenForm)
        assertEquals(100, result.radiusMeters)
        assertEquals(30, result.cooldownMinutes)
        assertFalse(result.everyTime)
        assertEquals("passer", result.label)
    }

    @Ignore("TODO: Réparer ce test")
    @Test
    fun `radius parsed from sentence`() {
        val parser = LocalPlaceIntentParser(emptyResolver)
        val input = "Rappelle-moi de passer au Biocoop quand j’arrive dans un rayon de 200 m"
        val result = parser.parse(input)
        assertNotNull(result)
        result!!
        val query = result.place as LocalPlaceIntentParser.PlaceQuery.FreeText
        assertEquals("biocoop", query.normalized)
        assertEquals(200, result.radiusMeters)
        assertEquals("passer", result.label)
    }

    @Ignore("TODO: Réparer ce test")
    @Test
    fun `label extracted when action follows location`() {
        val parser = LocalPlaceIntentParser(emptyResolver)
        val input = "Quand je pars de la gare, rappelle-moi de prendre un taxi"
        val result = parser.parse(input)
        assertNotNull(result)
        result!!
        assertEquals(LocalPlaceIntentParser.Transition.EXIT, result.transition)
        assertTrue(result.place is LocalPlaceIntentParser.PlaceQuery.FreeText)
        assertEquals("gare", (result.place as LocalPlaceIntentParser.PlaceQuery.FreeText).normalized)
        assertEquals("prendre un taxi", result.label)
    }

    @Test
    fun `missing action returns null`() {
        val parser = LocalPlaceIntentParser(emptyResolver)
        val input = "Quand j'arrive ici"
        val result = parser.parse(input)
        assertNull(result)
    }

    @Ignore("TODO: Réparer ce test")
    @Test
    fun `favorite match overrides defaults`() {
        val parser = LocalPlaceIntentParser(
            favoriteResolver = LocalPlaceIntentParser.FavoriteResolver { candidate ->
                when (candidate.normalized) {
                    "maison" -> LocalPlaceIntentParser.FavoriteResolution(
                        match = LocalPlaceIntentParser.FavoriteMatch(
                            id = 42L,
                            key = "maison",
                            lat = 48.8566,
                            lon = 2.3522,
                            spokenForm = candidate.raw,
                            defaultRadiusMeters = 200,
                            defaultCooldownMinutes = 15,
                            defaultEveryTime = true,
                        ),
                        aliases = listOf("maison"),
                        key = "maison",
                    )

                    else -> null
                }
            }
        )

        val input = "Rappelle-moi de sortir les poubelles à la maison quand j’arrive"
        val result = parser.parse(input)
        assertNotNull(result)
        result!!
        assertEquals(LocalPlaceIntentParser.Transition.ENTER, result.transition)
        val query = result.place as LocalPlaceIntentParser.PlaceQuery.Favorite
        assertEquals(42L, query.id)
        assertEquals(48.8566, query.lat, 0.0001)
        assertEquals(2.3522, query.lon, 0.0001)
        assertEquals("maison", query.spokenForm)
        assertEquals(200, result.radiusMeters)
        assertEquals(15, result.cooldownMinutes)
        assertTrue(result.everyTime)
        assertEquals("sortir les poubelles", result.label)
    }

    @Test
    fun `favorite resolver fallback with connector`() {
        val parser = LocalPlaceIntentParser(
            favoriteResolver = LocalPlaceIntentParser.FavoriteResolver { candidate ->
                when (candidate.normalized) {
                    "chez moi" -> LocalPlaceIntentParser.FavoriteResolution(
                        match = LocalPlaceIntentParser.FavoriteMatch(
                            id = 7L,
                            key = "chez-moi",
                            lat = 45.0,
                            lon = 3.0,
                            spokenForm = candidate.raw,
                            defaultRadiusMeters = 120,
                            defaultCooldownMinutes = 20,
                            defaultEveryTime = false,
                        ),
                        aliases = listOf("chez moi"),
                        key = "chez-moi",
                    )

                    else -> null
                }
            }
        )

        val input = "Pense à arroser les plantes quand j’arrive chez moi"
        val result = parser.parse(input)
        assertNotNull(result)
        result!!
        val query = result.place as LocalPlaceIntentParser.PlaceQuery.Favorite
        assertEquals(7L, query.id)
        assertEquals("arroser les plantes", result.label)
        assertEquals("chez moi", query.spokenForm)
        assertEquals(120, result.radiusMeters)
        assertEquals(20, result.cooldownMinutes)
        assertFalse(result.everyTime)
    }

    @Test
    fun `enter transition synonyms resolve favorites`() {
        val parser = LocalPlaceIntentParser(
            favoriteResolver = LocalPlaceIntentParser.FavoriteResolver { candidate ->
                when (candidate.normalized) {
                    "maison" -> LocalPlaceIntentParser.FavoriteResolution(
                        match = LocalPlaceIntentParser.FavoriteMatch(
                            id = 99L,
                            key = "maison",
                            lat = 12.0,
                            lon = 34.0,
                            spokenForm = candidate.raw,
                            defaultRadiusMeters = 180,
                            defaultCooldownMinutes = 25,
                            defaultEveryTime = false,
                        ),
                        aliases = listOf("maison"),
                        key = "maison",
                    )

                    else -> null
                }
            }
        )

        val input = "Rappelle-moi de manger une pizza quand je rentre à la maison"
        val result = parser.parse(input)
        assertNotNull(result)
        result!!
        val query = result.place as LocalPlaceIntentParser.PlaceQuery.Favorite
        assertEquals(99L, query.id)
        assertEquals("maison", query.spokenForm)
        assertEquals("manger une pizza", result.label)
        assertEquals(180, result.radiusMeters)
        assertEquals(25, result.cooldownMinutes)
        assertFalse(result.everyTime)
    }

    @Ignore("TODO: Réparer ce test")
    @Test
    fun `exit transition synonyms parsed`() {
        val parser = LocalPlaceIntentParser(emptyResolver)
        val input = "Fais-moi penser à appeler un taxi quand je m'en vais du bureau"
        val result = parser.parse(input)
        assertNotNull(result)
        result!!
        assertEquals(LocalPlaceIntentParser.Transition.EXIT, result.transition)
        val query = result.place as LocalPlaceIntentParser.PlaceQuery.FreeText
        assertEquals("bureau", query.normalized)
        assertEquals("appeler un taxi", result.label)
    }

    @Ignore("TODO: Réparer ce test")
    @Test
    fun `fuzzy favorite match tolerated`() {
        val parser = LocalPlaceIntentParser(
            favoriteResolver = LocalPlaceIntentParser.FavoriteResolver { candidate ->
                when (candidate.normalized) {
                    "maison" -> LocalPlaceIntentParser.FavoriteResolution(
                        match = LocalPlaceIntentParser.FavoriteMatch(
                            id = 12L,
                            key = "maison",
                            lat = 1.0,
                            lon = 2.0,
                            spokenForm = candidate.raw,
                            defaultRadiusMeters = 100,
                            defaultCooldownMinutes = 30,
                            defaultEveryTime = false,
                        ),
                        aliases = listOf("maison", "chez moi"),
                        key = "maison",
                    )

                    else -> null
                }
            }
        )

        val result = parser.parse("Rappelle-moi de manger une pizza quand je rentre à maisom")
        assertNotNull(result)
        result!!
        val query = result.place as LocalPlaceIntentParser.PlaceQuery.Favorite
        assertEquals(12L, query.id)
        assertEquals("manger une pizza", result.label)
    }
}

