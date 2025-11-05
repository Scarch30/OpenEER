package com.example.openeer.voice

import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Ignore
import org.junit.Test

class LocalTimeIntentParserTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")

    @Test
    fun `parse dans 15 minutes`() {
        val now = ZonedDateTime.of(2023, 10, 20, 10, 0, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi dans 15 minutes d’envoyer le mail",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = now.plusMinutes(15).toInstant().toEpochMilli()
        assertEquals(expected, result!!.triggerAtMillis)
        assertEquals("envoyer le mail", result.label)
    }

    @Test
    fun `parse dans cinq minutes`() {
        val now = ZonedDateTime.of(2023, 10, 20, 10, 0, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi dans cinq minutes d’envoyer le colis",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = now.plusMinutes(5).toInstant().toEpochMilli()
        assertEquals(expected, result!!.triggerAtMillis)
        assertEquals("envoyer le colis", result.label)
    }

    @Test
    fun `parse dans deux heures`() {
        val now = ZonedDateTime.of(2023, 10, 20, 9, 30, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi dans deux heures de vérifier le frigo",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = now.plusHours(2).toInstant().toEpochMilli()
        assertEquals(expected, result!!.triggerAtMillis)
        assertEquals("vérifier le frigo", result.label)
    }

    @Test
    fun `parse dans vingt-cinq minutes`() {
        val now = ZonedDateTime.of(2023, 10, 20, 11, 0, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi dans vingt-cinq minutes de sortir le gâteau",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = now.plusMinutes(25).toInstant().toEpochMilli()
        assertEquals(expected, result!!.triggerAtMillis)
        assertEquals("sortir le gâteau", result.label)
    }

    @Test
    fun `parse dans un quart d’heure`() {
        val now = ZonedDateTime.of(2023, 10, 20, 14, 0, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi dans un quart d’heure de rappeler Julie",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = now.plusMinutes(15).toInstant().toEpochMilli()
        assertEquals(expected, result!!.triggerAtMillis)
        assertEquals("rappeler Julie", result.label)
    }

    @Test
    fun `parse dans une demi-heure`() {
        val now = ZonedDateTime.of(2023, 10, 20, 16, 0, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi dans une demi-heure de sortir le chien",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = now.plusMinutes(30).toInstant().toEpochMilli()
        assertEquals(expected, result!!.triggerAtMillis)
        assertEquals("sortir le chien", result.label)
    }

    @Test
    fun `parse dans 2 heures`() {
        val now = ZonedDateTime.of(2023, 10, 20, 9, 30, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi dans 2 heures de vérifier le four",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = now.plusHours(2).toInstant().toEpochMilli()
        assertEquals(expected, result!!.triggerAtMillis)
        assertEquals("vérifier le four", result.label)
    }

    @Test
    fun `parse demain a 9h`() {
        val now = ZonedDateTime.of(2023, 10, 20, 16, 0, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi demain à 9h d'appeler Paul",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = now.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0)
        assertEquals(expected.toInstant().toEpochMilli(), result!!.triggerAtMillis)
        assertEquals("appeler Paul", result.label)
    }

    @Test
    fun `parse a 8h30 futur`() {
        val now = ZonedDateTime.of(2023, 10, 20, 7, 0, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi à 8h30 de prendre mon café",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = now.withHour(8).withMinute(30).withSecond(0).withNano(0)
        assertEquals(expected.toInstant().toEpochMilli(), result!!.triggerAtMillis)
        assertEquals("prendre mon café", result.label)
    }

    @Test
    fun `parse a 8h30 passe bascule lendemain`() {
        val now = ZonedDateTime.of(2023, 10, 20, 10, 0, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi à 8h30 de prendre mes médicaments",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = now.plusDays(1).withHour(8).withMinute(30).withSecond(0).withNano(0)
        assertEquals(expected.toInstant().toEpochMilli(), result!!.triggerAtMillis)
        assertEquals("prendre mes médicaments", result.label)
    }

    @Test
    fun `parse midi et minuit`() {
        val now = ZonedDateTime.of(2023, 10, 20, 11, 0, 0, 0, zone)
        val midiResult = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi à midi de sortir le pain",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(midiResult)
        val expectedMidi = now.withHour(12).withMinute(0).withSecond(0).withNano(0)
        assertEquals(expectedMidi.toInstant().toEpochMilli(), midiResult!!.triggerAtMillis)
        assertEquals("sortir le pain", midiResult.label)

        val minuitResult = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi à minuit de vérifier la porte",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(minuitResult)
        val expectedMinuit = now.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
        assertEquals(expectedMinuit.toInstant().toEpochMilli(), minuitResult!!.triggerAtMillis)
        assertEquals("vérifier la porte", minuitResult.label)
    }

    @Test
    fun `parse ce soir`() {
        val now = ZonedDateTime.of(2023, 10, 20, 18, 0, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi ce soir de sortir les poubelles",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = now.withHour(19).withMinute(0).withSecond(0).withNano(0)
        assertEquals(expected.toInstant().toEpochMilli(), result!!.triggerAtMillis)
        assertEquals("sortir les poubelles", result.label)
    }

    @Ignore("TODO: Réparer ce test")
    @Test
    fun `parse date numerique avec heure`() {
        val now = ZonedDateTime.of(2023, 10, 1, 10, 0, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi le 21/10 à 8h30 d'envoyer le rapport",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = ZonedDateTime.of(2023, 10, 21, 8, 30, 0, 0, zone)
        assertEquals(expected.toInstant().toEpochMilli(), result!!.triggerAtMillis)
        assertEquals("envoyer le rapport", result.label)
    }

    @Ignore("TODO: Réparer ce test")
    @Test
    fun `parse date avec mois en lettres`() {
        val now = ZonedDateTime.of(2023, 9, 15, 12, 0, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi le 21 octobre d'appeler le dentiste",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = ZonedDateTime.of(2023, 10, 21, 9, 0, 0, 0, zone)
        assertEquals(expected.toInstant().toEpochMilli(), result!!.triggerAtMillis)
        assertEquals("appeler le dentiste", result.label)
    }

    @Test
    fun `parse prochain lundi`() {
        val now = ZonedDateTime.of(2023, 10, 18, 10, 0, 0, 0, zone) // mercredi
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi lundi à 7h d'arroser les plantes",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expectedDate = now.with(DayOfWeek.MONDAY).plusWeeks(1).withHour(7).withMinute(0).withSecond(0).withNano(0)
        assertEquals(expectedDate.toInstant().toEpochMilli(), result!!.triggerAtMillis)
        assertEquals("arroser les plantes", result.label)
    }

    @Test
    fun `parse ordre libre demain puis action`() {
        val now = ZonedDateTime.of(2023, 10, 20, 16, 0, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi d'appeler Paul demain à 9h",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = now.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0)
        assertEquals(expected.toInstant().toEpochMilli(), result!!.triggerAtMillis)
        assertEquals("appeler Paul", result.label)
    }

    @Test
    fun `parse ordre libre midi puis action`() {
        val now = ZonedDateTime.of(2023, 10, 20, 9, 0, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi d'acheter des timbres à midi",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = now.withHour(12).withMinute(0).withSecond(0).withNano(0)
        assertEquals(expected.toInstant().toEpochMilli(), result!!.triggerAtMillis)
        assertEquals("acheter des timbres", result.label)
    }

    @Test
    fun `parse ordre libre action puis midi`() {
        val now = ZonedDateTime.of(2023, 10, 20, 9, 0, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi à midi d'acheter des timbres",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = now.withHour(12).withMinute(0).withSecond(0).withNano(0)
        assertEquals(expected.toInstant().toEpochMilli(), result!!.triggerAtMillis)
        assertEquals("acheter des timbres", result.label)
    }

    @Test
    fun `parse ordre libre time first`() {
        val now = ZonedDateTime.of(2023, 10, 20, 16, 0, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Demain à 9h rappelle-moi d'appeler Paul",
            now.toInstant().toEpochMilli()
        )
        assertNotNull(result)
        val expected = now.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0)
        assertEquals(expected.toInstant().toEpochMilli(), result!!.triggerAtMillis)
        assertEquals("appeler Paul", result.label)
    }

    @Test
    fun `parse incomplet sans temps`() {
        val now = ZonedDateTime.of(2023, 10, 20, 9, 0, 0, 0, zone)
        val result = LocalTimeIntentParser.parseReminder(
            "Rappelle-moi d'appeler maman",
            now.toInstant().toEpochMilli()
        )
        assertEquals(null, result)
    }
}
