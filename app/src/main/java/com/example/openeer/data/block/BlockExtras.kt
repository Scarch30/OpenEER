package com.example.openeer.data.block

/**
 * Strongly typed accessors for optional metadata stored on [BlockEntity].
 */
data class BlockExtras(
    val text: String? = null,
    val mediaUri: String? = null,
    val mimeType: String? = null,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val placeName: String? = null,
    val routeJson: String? = null,
    val extra: String? = null,
)

fun BlockEntity.updateExtras(extras: BlockExtras): BlockEntity =
    copy(
        text = extras.text ?: text,
        mediaUri = extras.mediaUri ?: mediaUri,
        mimeType = extras.mimeType ?: mimeType,
        durationMs = extras.durationMs ?: durationMs,
        width = extras.width ?: width,
        height = extras.height ?: height,
        lat = extras.lat ?: lat,
        lon = extras.lon ?: lon,
        placeName = extras.placeName ?: placeName,
        routeJson = extras.routeJson ?: routeJson,
        extra = extras.extra ?: extra,
    )
