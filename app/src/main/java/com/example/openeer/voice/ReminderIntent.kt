package com.example.openeer.voice

sealed class ReminderIntent {
    abstract val label: String
    abstract val usedFields: Set<Field>

    enum class Field {
        LABEL,
        TIME,
        PLACE,
    }

    data class Time(
        val triggerAtMillis: Long,
        override val label: String,
    ) : ReminderIntent() {
        override val usedFields: Set<Field> = setOf(Field.LABEL, Field.TIME)
    }

    data class Place(
        val parse: LocalPlaceIntentParser.PlaceParseResult,
    ) : ReminderIntent() {
        override val label: String = parse.label
        override val usedFields: Set<Field> = setOf(Field.LABEL, Field.PLACE)

        val transition: LocalPlaceIntentParser.Transition = parse.transition
        val radiusMeters: Int = parse.radiusMeters
        val cooldownMinutes: Int = parse.cooldownMinutes
        val everyTime: Boolean = parse.everyTime
        val placeQuery: LocalPlaceIntentParser.PlaceQuery = parse.place
    }
}
