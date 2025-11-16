package com.example.openeer.domain.favorites

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.favorites.FavoriteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FavoriteCreationRequest(
    val name: String,
    val address: String? = null,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val cooldownMinutes: Int,
    val everyTime: Boolean,
    val source: Source
) {
    enum class Source {
        HERE_BUTTON,
        MAP_TAP
    }
}

object FavoriteCreationService {
    private const val TAG = "FavoriteCreationService"

    suspend fun createFavorite(context: Context, request: FavoriteCreationRequest) {
        val created = withContext(Dispatchers.IO) {
            val database = AppDatabase.getInstance(context)
            val repository = FavoritesRepository(database.favoriteDao())
            val service = FavoritesService(repository)
            var entity: FavoriteEntity? = null

            runCatching {
                val id = service.createFavorite(
                    displayName = request.name,
                    lat = request.latitude,
                    lon = request.longitude,
                    aliases = listOfNotNull(request.address?.trim()?.takeIf { it.isNotEmpty() }),
                    defaultRadiusMeters = request.radiusMeters,
                    defaultCooldownMinutes = request.cooldownMinutes,
                    defaultEveryTime = request.everyTime
                )
                entity = repository.getById(id)
                Log.i(
                    TAG,
                    "createFavorite success id=$id name=${request.name} lat=${request.latitude} " +
                        "lon=${request.longitude} radius=${request.radiusMeters} cooldown=${request.cooldownMinutes} " +
                        "everyTime=${request.everyTime} source=${request.source}"
                )
            }.onFailure { error ->
                Log.e(
                    TAG,
                    "createFavorite failed name=${request.name} lat=${request.latitude} lon=${request.longitude}",
                    error
                )
            }
            entity
        }
        created?.let { broadcastFavoriteCreated(context.applicationContext, it) }
    }

    private fun broadcastFavoriteCreated(context: Context, favorite: FavoriteEntity) {
        val intent = Intent(ACTION_FAVORITE_CREATED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_FAVORITE_ID, favorite.id)
            putExtra(EXTRA_FAVORITE_NAME, favorite.displayName)
            putExtra(EXTRA_FAVORITE_LAT, favorite.lat)
            putExtra(EXTRA_FAVORITE_LON, favorite.lon)
            putExtra(EXTRA_FAVORITE_RADIUS_METERS, favorite.defaultRadiusMeters)
            putExtra(EXTRA_FAVORITE_COOLDOWN_MINUTES, favorite.defaultCooldownMinutes)
            putExtra(EXTRA_FAVORITE_EVERY_TIME, favorite.defaultEveryTime)
        }
        context.sendBroadcast(intent)
    }

    const val ACTION_FAVORITE_CREATED = "com.example.openeer.intent.FAVORITE_CREATED"
    const val EXTRA_FAVORITE_ID = "extra_favorite_id"
    const val EXTRA_FAVORITE_NAME = "extra_favorite_name"
    const val EXTRA_FAVORITE_LAT = "extra_favorite_lat"
    const val EXTRA_FAVORITE_LON = "extra_favorite_lon"
    const val EXTRA_FAVORITE_RADIUS_METERS = "extra_favorite_radius_meters"
    const val EXTRA_FAVORITE_COOLDOWN_MINUTES = "extra_favorite_cooldown_minutes"
    const val EXTRA_FAVORITE_EVERY_TIME = "extra_favorite_every_time"
}
