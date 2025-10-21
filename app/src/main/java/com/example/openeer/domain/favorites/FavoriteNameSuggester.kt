package com.example.openeer.domain.favorites

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.Locale
import kotlin.math.max

object FavoriteNameSuggester {
    private const val PREFS_NAME = "favorite_name_suggester"
    private const val KEY_USED_HOME = "used_home"
    private const val KEY_USED_WORK = "used_work"
    private const val KEY_NEXT_INDEX = "next_index"
    private val FAVORI_PATTERN = Regex("Favori #([0-9]+)")

    fun defaultHereName(context: Context): String {
        val prefs = prefs(context)
        return when {
            !prefs.getBoolean(KEY_USED_HOME, false) -> "Maison"
            !prefs.getBoolean(KEY_USED_WORK, false) -> "Travail"
            else -> defaultSequentialName(context, prefs)
        }
    }

    fun defaultSequentialName(context: Context): String = defaultSequentialName(context, prefs(context))

    fun recordNameUsage(context: Context, name: String) {
        val prefs = prefs(context)
        when (name) {
            "Maison" -> prefs.edit { putBoolean(KEY_USED_HOME, true) }
            "Travail" -> prefs.edit { putBoolean(KEY_USED_WORK, true) }
            else -> {
                val match = FAVORI_PATTERN.matchEntire(name)
                if (match != null) {
                    val value = match.groupValues.getOrNull(1)?.toIntOrNull()
                    if (value != null) {
                        val current = prefs.getInt(KEY_NEXT_INDEX, 1)
                        prefs.edit { putInt(KEY_NEXT_INDEX, max(current, value + 1)) }
                    }
                }
            }
        }
    }

    private fun defaultSequentialName(context: Context, prefs: SharedPreferences): String {
        val index = prefs.getInt(KEY_NEXT_INDEX, 1).coerceAtLeast(1)
        return String.format(Locale.getDefault(), "Favori #%d", index)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
