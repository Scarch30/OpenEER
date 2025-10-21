package com.example.openeer.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaceIntentParserTest {

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
}

