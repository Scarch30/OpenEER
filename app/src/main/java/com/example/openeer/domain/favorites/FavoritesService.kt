package com.example.openeer.domain.favorites

import com.example.openeer.data.favorites.FavoriteEntity
import java.text.Normalizer
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

class FavoritesService(
    private val repository: FavoritesRepository,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val currentLocationProvider: suspend () -> Pair<Double, Double>? = { null }
) {

    suspend fun createFavorite(
        displayName: String,
        lat: Double,
        lon: Double,
        aliases: List<String> = emptyList(),
        defaultRadiusMeters: Int = FavoriteEntity.DEFAULT_RADIUS_METERS,
        defaultCooldownMinutes: Int = FavoriteEntity.DEFAULT_COOLDOWN_MINUTES,
        defaultEveryTime: Boolean = false
    ): Long {
        val sanitizedName = displayName.trim()
        require(sanitizedName.isNotEmpty()) { "displayName must not be blank" }

        val uniqueKey = generateUniqueKey(sanitizedName)
        val aliasSet = buildAliasSet(sanitizedName, aliases)
        val now = clock()

        val entity = FavoriteEntity(
            key = uniqueKey,
            displayName = sanitizedName,
            aliasesJson = aliasesToJson(aliasSet),
            lat = lat,
            lon = lon,
            defaultRadiusMeters = defaultRadiusMeters,
            defaultCooldownMinutes = defaultCooldownMinutes,
            defaultEveryTime = defaultEveryTime,
            createdAt = now,
            updatedAt = now
        )

        return repository.insert(entity)
    }

    suspend fun updateFavorite(
        id: Long,
        displayName: String? = null,
        lat: Double? = null,
        lon: Double? = null,
        aliases: List<String>? = null,
        defaultRadiusMeters: Int? = null,
        defaultCooldownMinutes: Int? = null,
        defaultEveryTime: Boolean? = null
    ) {
        val existing = repository.getById(id) ?: return
        val newName = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: existing.displayName

        val newAliasesJson = if (aliases == null) {
            val aliasSet = LinkedHashSet(parseAliases(existing.aliasesJson))
            aliasSet.add(normalizeAlias(newName))
            aliasSet.add(slugify(newName))
            DEFAULT_ALIASES[normalizeAlias(newName)]?.forEach { aliasSet.add(normalizeAlias(it)) }
            aliasesToJson(aliasSet)
        } else {
            aliasesToJson(buildAliasSet(newName, aliases))
        }

        val updated = existing.copy(
            displayName = newName,
            aliasesJson = newAliasesJson,
            lat = lat ?: existing.lat,
            lon = lon ?: existing.lon,
            defaultRadiusMeters = defaultRadiusMeters ?: existing.defaultRadiusMeters,
            defaultCooldownMinutes = defaultCooldownMinutes ?: existing.defaultCooldownMinutes,
            defaultEveryTime = defaultEveryTime ?: existing.defaultEveryTime,
            updatedAt = clock()
        )

        repository.update(updated)
    }

    suspend fun deleteFavorite(id: Long) {
        repository.delete(id)
    }

    suspend fun getAll(): List<FavoriteEntity> = repository.getAll()

    suspend fun findByAlias(textNormalized: String): FavoriteEntity? {
        if (textNormalized.isBlank()) return null
        val normalized = normalizeAlias(textNormalized)
        val slugCandidate = slugify(textNormalized)
        return repository.getAll().firstOrNull { favorite ->
            val aliases = parseAliases(favorite.aliasesJson)
            normalized.isNotEmpty() && aliases.contains(normalized) ||
                slugCandidate.isNotEmpty() && aliases.contains(slugCandidate) ||
                slugCandidate.isNotEmpty() && favorite.key == slugCandidate ||
                normalized.isNotEmpty() && favorite.key == normalized
        }
    }

    suspend fun repositionToCurrentLocation(id: Long) {
        val existing = repository.getById(id) ?: return
        val location = withContext(Dispatchers.IO) { currentLocationProvider() } ?: return
        val (lat, lon) = location
        val updated = existing.copy(
            lat = lat,
            lon = lon,
            updatedAt = clock()
        )
        repository.update(updated)
    }

    private suspend fun generateUniqueKey(displayName: String): String {
        val base = slugify(displayName).ifBlank { DEFAULT_KEY }
        var candidate = base
        val suffix = AtomicInteger(2)
        while (withContext(Dispatchers.IO) { repository.getByKey(candidate) } != null) {
            candidate = "$base-${suffix.getAndIncrement()}"
        }
        return candidate
    }

    private fun buildAliasSet(displayName: String, userAliases: List<String>): Set<String> {
        val aliasSet = LinkedHashSet<String>()
        val normalizedName = normalizeAlias(displayName)
        if (normalizedName.isNotEmpty()) {
            aliasSet.add(normalizedName)
            DEFAULT_ALIASES[normalizedName]?.forEach { aliasSet.add(normalizeAlias(it)) }
        }
        val slug = slugify(displayName)
        if (slug.isNotEmpty()) {
            aliasSet.add(slug)
        }
        userAliases.map(::normalizeAlias)
            .filter { it.isNotEmpty() }
            .forEach { aliasSet.add(it) }
        return aliasSet
    }

    private fun aliasesToJson(aliases: Collection<String>): String {
        val array = JSONArray()
        aliases.forEach { array.put(it) }
        return array.toString()
    }

    private fun parseAliases(json: String): List<String> {
        val array = JSONArray(json)
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i)
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

    private fun slugify(value: String): String {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
        val withoutDiacritics = DIACRITICS_REGEX.replace(normalized, "")
        val cleaned = withoutDiacritics.lowercase(Locale.getDefault())
        val hyphenated = cleaned.replace(NON_SLUG_CHAR_REGEX, "-")
        return hyphenated.trim('-').replace(HYPHEN_DUPLICATES_REGEX, "-")
    }

    companion object {
        private const val DEFAULT_KEY = "favori"
        private val DIACRITICS_REGEX = "\\p{InCombiningDiacriticalMarks}+".toRegex()
        private val NON_ALPHANUMERIC_REGEX = "[^a-zA-Z0-9]+".toRegex()
        private val NON_SLUG_CHAR_REGEX = "[^a-z0-9]+".toRegex()
        private val HYPHEN_DUPLICATES_REGEX = "-+".toRegex()
        private val WHITESPACE_REGEX = "\\s+".toRegex()
        private val DEFAULT_ALIASES = mapOf(
            "maison" to listOf("maison", "chez moi", "home"),
            "travail" to listOf("travail", "bureau", "work"),
            "salle" to listOf("salle", "salle de sport", "gym"),
            "ecole" to listOf("ecole", "école", "school")
        )
    }
}
