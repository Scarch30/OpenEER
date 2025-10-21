package com.example.openeer.domain.favorites

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class FavoriteCreationRequest(
    val name: String,
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

    @Suppress("UNUSED_PARAMETER")
    suspend fun createFavorite(context: Context, request: FavoriteCreationRequest) {
        withContext(Dispatchers.IO) {
            Log.i(
                TAG,
                "createFavorite name=${request.name} lat=${request.latitude} lon=${request.longitude} " +
                    "radius=${request.radiusMeters} cooldown=${request.cooldownMinutes} " +
                    "everyTime=${request.everyTime} source=${request.source}"
            )
        }
    }
}
