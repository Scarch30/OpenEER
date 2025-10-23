package com.example.openeer.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandRouterTest {

    private val parser = LocalPlaceIntentParser(LocalPlaceIntentParser.FavoriteResolver { null })
    private val router = VoiceCommandRouter(parser, isVoiceCommandsEnabled = { true })

    @Test
    fun `reminder intent with date and time`() {
        val sentence = "Rappelle-moi demain à 9h d'appeler Paul"
        val decision = router.route(sentence)
        assertEquals(VoiceRouteDecision.REMINDER_TIME, decision)
    }

    @Test
    fun `relative reminder with trigger`() {
        val sentence = "Rappelle-moi dans cinq minutes de boire de l'eau"
        val decision = router.route(sentence)
        assertEquals(VoiceRouteDecision.REMINDER_TIME, decision)
    }

    @Test
    fun `false positive phrases stay as notes`() {
        val sentence = "Je me rappelle de ce film"
        val decision = router.route(sentence)
        assertEquals(VoiceRouteDecision.NOTE, decision)
    }

    @Test
    fun `missing timing information is incomplete`() {
        val sentence = "Rappelle-moi d'appeler maman"
        val decision = router.route(sentence)
        assertEquals(VoiceRouteDecision.INCOMPLETE, decision)
    }

    @Test
    fun `place reminder is detected`() {
        val sentence = "Rappelle-moi d’acheter du pain quand j’arrive ici"
        val decision = router.route(sentence)
        assertEquals(VoiceRouteDecision.REMINDER_PLACE, decision)
    }

    @Test
    fun `convert command routes to list`() {
        val sentence = "Transforme cette note en liste"
        val decision = router.route(sentence)
        assertTrue(decision is VoiceRouteDecision.List && decision.action == VoiceListAction.CONVERT)
    }

    @Test
    fun `convert command without keyword uses list context`() {
        val sentence = "Converti en texte"
        val decision = router.route(sentence, assumeListContext = true)
        assertTrue(decision is VoiceRouteDecision.List && decision.action == VoiceListAction.CONVERT)
    }

    @Test
    fun `add command extracts items`() {
        val sentence = "Ajoute lait, oeufs et farine à la liste"
        val decision = router.route(sentence)
        assertTrue(decision is VoiceRouteDecision.List && decision.action == VoiceListAction.ADD)
        decision as VoiceRouteDecision.List
        assertEquals(listOf("lait", "oeufs", "farine"), decision.items)
    }

    @Test
    fun `add command without liste keyword uses list context`() {
        val sentence = "Ajoute tomates"
        val decision = router.route(sentence, assumeListContext = true)
        assertTrue(decision is VoiceRouteDecision.List && decision.action == VoiceListAction.ADD)
        decision as VoiceRouteDecision.List
        assertEquals(listOf("tomates"), decision.items)
    }

    @Test
    fun `toggle command without liste keyword works`() {
        val sentence = "Coche oeufs"
        val decision = router.route(sentence)
        assertTrue(decision is VoiceRouteDecision.List && decision.action == VoiceListAction.TOGGLE)
    }

    @Test
    fun `untick command supports accents`() {
        val sentence = "Décoche tomates"
        val decision = router.route(sentence)
        assertTrue(decision is VoiceRouteDecision.List && decision.action == VoiceListAction.UNTICK)
    }

    @Test
    fun `remove command uses list route`() {
        val sentence = "Supprime pain"
        val decision = router.route(sentence)
        assertTrue(decision is VoiceRouteDecision.List && decision.action == VoiceListAction.REMOVE)
    }

    @Test
    fun `incomplete add command returns list incomplete`() {
        val sentence = "Ajoute à la liste"
        val decision = router.route(sentence)
        assertEquals(VoiceRouteDecision.LIST_INCOMPLETE, decision)
    }

    @Test
    fun `feature flag disabled keeps note behaviour`() {
        val disabledRouter = VoiceCommandRouter(parser, isVoiceCommandsEnabled = { false })
        val sentence = "Rappelle-moi demain à 9h d'appeler Paul"
        val decision = disabledRouter.route(sentence)
        assertEquals(VoiceRouteDecision.NOTE, decision)
    }
}
