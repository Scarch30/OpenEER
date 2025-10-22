package com.example.openeer.voice

import android.content.Context
import android.util.Log
import com.example.openeer.domain.favorites.FavoritesService
import com.example.openeer.data.AppDatabase

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
                val database = AppDatabase.get(appContext)
                val favoritesService = FavoritesService(database)
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
        val match = favoritesService.findByAliasNormalized(candidate.normalized) ?: return null
        return LocalPlaceIntentParser.FavoriteResolution(
            match = LocalPlaceIntentParser.FavoriteMatch(
                id = match.id,
                key = match.key,
                lat = match.lat,
                lon = match.lon,
                spokenForm = candidate.raw,
                defaultRadiusMeters = match.defaultRadiusMeters,
                defaultCooldownMinutes = match.defaultCooldownMinutes,
                defaultEveryTime = match.defaultEveryTime,
            ),
            aliases = match.aliases,
            key = match.key,
        )
    }
}
