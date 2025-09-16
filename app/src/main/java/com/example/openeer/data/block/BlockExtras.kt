package com.example.openeer.data.block

import org.json.JSONException
import org.json.JSONObject

private const val KEY_PAYLOAD = "payload"
private const val KEY_MIME_TYPE = "mimeType"
private const val KEY_DURATION = "durationMs"
private const val KEY_WIDTH = "width"
private const val KEY_HEIGHT = "height"
private const val KEY_GROUP_ID = "groupId"
private const val KEY_LAT = "lat"
private const val KEY_LON = "lon"
private const val KEY_PLACE = "placeName"
private const val KEY_ROUTE = "routeJson"

internal data class BlockExtras(
    val payload: String? = null,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val groupId: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val placeName: String? = null,
    val routeJson: String? = null
) {
    fun toRaw(): String? {
        val obj = JSONObject()
        payload?.let { obj.put(KEY_PAYLOAD, it) }
        mimeType?.let { obj.put(KEY_MIME_TYPE, it) }
        durationMs?.let { obj.put(KEY_DURATION, it) }
        width?.let { obj.put(KEY_WIDTH, it) }
        height?.let { obj.put(KEY_HEIGHT, it) }
        groupId?.let { obj.put(KEY_GROUP_ID, it) }
        lat?.let { obj.put(KEY_LAT, it) }
        lon?.let { obj.put(KEY_LON, it) }
        placeName?.let { obj.put(KEY_PLACE, it) }
        routeJson?.let { obj.put(KEY_ROUTE, it) }
        return if (obj.length() == 0) null else obj.toString()
    }

    companion object {
        fun fromRaw(raw: String?): BlockExtras {
            if (raw.isNullOrBlank()) return BlockExtras()
            return try {
                val obj = JSONObject(raw)
                BlockExtras(
                    payload = obj.optString(KEY_PAYLOAD).takeIf { obj.has(KEY_PAYLOAD) },
                    mimeType = obj.optString(KEY_MIME_TYPE).takeIf { obj.has(KEY_MIME_TYPE) },
                    durationMs = if (obj.has(KEY_DURATION)) obj.optLong(KEY_DURATION) else null,
                    width = if (obj.has(KEY_WIDTH)) obj.optInt(KEY_WIDTH) else null,
                    height = if (obj.has(KEY_HEIGHT)) obj.optInt(KEY_HEIGHT) else null,
                    groupId = obj.optString(KEY_GROUP_ID).takeIf { obj.has(KEY_GROUP_ID) },
                    lat = if (obj.has(KEY_LAT)) obj.optDouble(KEY_LAT) else null,
                    lon = if (obj.has(KEY_LON)) obj.optDouble(KEY_LON) else null,
                    placeName = obj.optString(KEY_PLACE).takeIf { obj.has(KEY_PLACE) },
                    routeJson = obj.optString(KEY_ROUTE).takeIf { obj.has(KEY_ROUTE) }
                )
            } catch (_: JSONException) {
                BlockExtras(payload = raw)
            }
        }
    }
}

private fun BlockExtras.update(transform: BlockExtras.() -> BlockExtras): BlockExtras = transform(this)

private fun BlockEntity.extras(): BlockExtras = BlockExtras.fromRaw(extra)

private fun BlockEntity.withExtras(extras: BlockExtras): BlockEntity = copy(extra = extras.toRaw())

val BlockEntity.payload: String?
    get() = extras().payload

val BlockEntity.mimeType: String?
    get() = extras().mimeType

val BlockEntity.durationMs: Long?
    get() = extras().durationMs

val BlockEntity.width: Int?
    get() = extras().width

val BlockEntity.height: Int?
    get() = extras().height

val BlockEntity.groupId: String?
    get() = extras().groupId

val BlockEntity.lat: Double?
    get() = extras().lat

val BlockEntity.lon: Double?
    get() = extras().lon

val BlockEntity.placeName: String?
    get() = extras().placeName

val BlockEntity.routeJson: String?
    get() = extras().routeJson

internal fun BlockEntity.updateExtras(transform: BlockExtras.() -> BlockExtras): BlockEntity =
    withExtras(extras().update(transform))

fun buildExtras(
    payload: String? = null,
    mimeType: String? = null,
    durationMs: Long? = null,
    width: Int? = null,
    height: Int? = null,
    groupId: String? = null,
    lat: Double? = null,
    lon: Double? = null,
    placeName: String? = null,
    routeJson: String? = null
): String? = BlockExtras(
    payload = payload,
    mimeType = mimeType,
    durationMs = durationMs,
    width = width,
    height = height,
    groupId = groupId,
    lat = lat,
    lon = lon,
    placeName = placeName,
    routeJson = routeJson
).toRaw()
