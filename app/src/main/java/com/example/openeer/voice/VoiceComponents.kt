package com.example.openeer.voice

import android.content.Context
import android.util.Log
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.favorites.FavoriteEntity
import com.example.openeer.domain.favorites.FavoritesRepository
import com.example.openeer.domain.favorites.FavoritesService
import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray

data class VoiceDependencies(
    val favoritesService: FavoritesService,
    val placeParser: LocalPlaceIntentParser,
)

object VoiceComponents {

    @Volatile
    private var cached: VoiceDependencies? = null

    fun obtain(context: Context): VoiceDependencies {
        val existing = cached
        if (existing != null) return existing
        val appContext = context.applicationContext
        return synchronized(this) {
            val secondCheck = cached
            if (secondCheck != null) {
                secondCheck
            } else {
                val database = AppDatabase.getInstance(appContext)
                val favoritesRepository = FavoritesRepository(database.favoriteDao())
                val favoritesService = FavoritesService(favoritesRepository)
                val resolver = VoiceFavoriteResolver(favoritesService)
                val parser = LocalPlaceIntentParser(
                    favoriteResolver = resolver,
                    log = { message -> Log.d("LocalPlaceIntentParser", message) }
                )
                VoiceDependencies(
                    favoritesService = favoritesService,
                    placeParser = parser,
                ).also { cached = it }
            }
        }
    }
}

private class VoiceFavoriteResolver(
    private val favoritesService: FavoritesService,
) : LocalPlaceIntentParser.FavoriteResolver {

    override fun resolve(candidate: LocalPlaceIntentParser.FavoriteCandidate): LocalPlaceIntentParser.FavoriteResolution? {
        val entity = favoritesService.matchFavorite(candidate.raw) ?: return null
        val normalizedAliases = collectAliases(entity)
        return LocalPlaceIntentParser.FavoriteResolution(
            match = LocalPlaceIntentParser.FavoriteMatch(
                id = entity.id,
                key = entity.key,
                lat = entity.lat,
                lon = entity.lon,
                spokenForm = candidate.raw,
                defaultRadiusMeters = entity.defaultRadiusMeters,
                defaultCooldownMinutes = entity.defaultCooldownMinutes,
                defaultEveryTime = entity.defaultEveryTime,
            ),
            aliases = normalizedAliases,
            key = entity.key,
        )
    }

    private fun collectAliases(entity: FavoriteEntity): List<String> {
        val normalized = mutableSetOf<String>()
        parseAliases(entity.aliasesJson)
            .map(::normalizeAlias)
            .filter { it.isNotEmpty() }
            .forEach { normalized.add(it) }
        normalizeAlias(entity.displayName).takeIf { it.isNotEmpty() }?.let { normalized.add(it) }
        normalizeAlias(entity.key).takeIf { it.isNotEmpty() }?.let { normalized.add(it) }
        return normalized.toList().sorted()
    }

    private fun parseAliases(json: String): List<String> {
        val array = JSONArray(json)
        val result = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index)
            if (!value.isNullOrEmpty()) {
                result.add(value)
            }
        }
        return result
    }

    private fun normalizeAlias(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return ""
        val normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFD)
        val withoutDiacritics = DIACRITICS_REGEX.replace(normalized, "")
        val cleaned = NON_ALPHANUMERIC_REGEX.replace(withoutDiacritics, " ")
        val tokens = cleaned.lowercase(Locale.getDefault()).split(WHITESPACE_REGEX)
            .filter { it.isNotEmpty() }
        return tokens.joinToString(" ")
    }

    companion object {
        private val DIACRITICS_REGEX = "\\p{InCombiningDiacriticalMarks}+".toRegex()
        private val NON_ALPHANUMERIC_REGEX = "[^a-zA-Z0-9]+".toRegex()
        private val WHITESPACE_REGEX = "\\s+".toRegex()
    }
}
