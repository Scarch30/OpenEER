package com.example.openeer.voice

class ReminderIntentParser(
    private val placeParser: LocalPlaceIntentParser,
) {
    fun parse(text: String): ReminderIntent? {
        if (text.isBlank()) return null
        val timeParse = LocalTimeIntentParser.parseReminder(text)
        if (timeParse != null) {
            return ReminderIntent.Time(
                triggerAtMillis = timeParse.triggerAtMillis,
                label = timeParse.label,
            )
        }

        val placeParse = placeParser.parse(text)
        if (placeParse != null) {
            return ReminderIntent.Place(placeParse)
        }

        return null
    }
}
