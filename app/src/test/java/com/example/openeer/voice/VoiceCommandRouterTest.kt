package com.example.openeer.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandRouterTest {

    private val router = VoiceCommandRouter(isVoiceCommandsEnabled = { true })

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
    fun `feature flag disabled keeps note behaviour`() {
        val disabledRouter = VoiceCommandRouter(isVoiceCommandsEnabled = { false })
        val sentence = "Rappelle-moi demain à 9h d'appeler Paul"
        val decision = disabledRouter.route(sentence)
        assertEquals(VoiceRouteDecision.NOTE, decision)
    }
}
