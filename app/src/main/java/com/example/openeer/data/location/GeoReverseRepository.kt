package com.example.openeer.data.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

class GeoReverseRepository private constructor(private val context: Context) {

    private val cacheMutex = Mutex()
    private val memoryCache = object : LinkedHashMap<String, String?>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?): Boolean {
            return size > MAX_CACHE_SIZE
        }
    }

    fun cachedAddressFor(lat: Double, lon: Double): String? {
        val key = keyFor(lat, lon)
        return synchronized(memoryCache) {
            if (!memoryCache.containsKey(key)) {
                null
            } else {
                memoryCache[key]
            }
        }
    }

    suspend fun addressFor(lat: Double, lon: Double): String? {
        val key = keyFor(lat, lon)
        cachedAddressFor(lat, lon)?.let { return it }
        val fetched = cacheMutex.withLock {
            if (memoryCache.containsKey(key)) {
                return@withLock memoryCache[key]
            }
            val resolved = fetchAddress(lat, lon)
            memoryCache[key] = resolved
            resolved
        }
        return fetched
    }

    private suspend fun fetchAddress(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        val locale = Locale.getDefault()
        val local = geocodeLocal(lat, lon, locale)
        if (!local.isNullOrBlank()) {
            return@withContext local
        }
        val fallback = fetchFromNominatim(lat, lon, locale)
        fallback
    }

    private fun geocodeLocal(lat: Double, lon: Double, locale: Locale): String? {
        return try {
            val geocoder = Geocoder(context, locale)
            val results: List<Address>? = geocoder.getFromLocation(lat, lon, 1)
            val address = results?.firstOrNull()
            address?.getAddressLine(0)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (ioe: IOException) {
            Log.w(TAG, "Geocoder I/O error for ($lat,$lon)", ioe)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Geocoder argument error for ($lat,$lon)", e)
            null
        }
    }

    private fun fetchFromNominatim(lat: Double, lon: Double, locale: Locale): String? {
        val language = locale.toLanguageTag()
        val url = URL("https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lon&accept-language=$language")
        var connection: HttpURLConnection? = null
        return try {
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            val response = connection.inputStream.use { input ->
                BufferedReader(InputStreamReader(input)).use(BufferedReader::readText)
            }
            val json = JSONObject(response)
            json.optString("display_name").trim().takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Log.w(TAG, "Fallback reverse geocoding failed for ($lat,$lon)", t)
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun keyFor(lat: Double, lon: Double): String {
        return String.format(Locale.US, "%.5f,%.5f", lat, lon)
    }

    companion object {
        private const val TAG = "GeoReverseRepo"
        private const val USER_AGENT = "OpenEER/ReverseGeocoder"
        private const val HTTP_TIMEOUT_MS = 5_000
        private const val MAX_CACHE_SIZE = 64

        @Volatile
        private var INSTANCE: GeoReverseRepository? = null

        fun getInstance(context: Context): GeoReverseRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GeoReverseRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
