package com.example.openeer.ui.panel.media

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import com.example.openeer.R
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.ui.PhotoViewerActivity
import com.example.openeer.ui.SimplePlayer
import com.example.openeer.ui.sheets.ChildPostitSheet
import com.example.openeer.ui.sheets.LocationPreviewSheet
import com.example.openeer.ui.sheets.MediaGridSheet
import com.example.openeer.ui.viewer.VideoPlayerActivity
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.Locale

class MediaActions(
    private val activity: AppCompatActivity,
    private val blocksRepo: BlocksRepository
) {
    private val uiScope = CoroutineScope(Dispatchers.Main)
    private val routeGson = Gson()

    fun handlePileClick(noteId: Long, category: MediaCategory) {
        // La grille sait déjà filtrer par catégorie ; on l’utilise aussi pour LOCATION.
        MediaGridSheet.show(activity.supportFragmentManager, noteId, category)
    }

    fun handleClick(item: MediaStripItem) {
        when (item) {
            is MediaStripItem.Pile -> {
                // Ouvert via NotePanelController.onPileClick(...)
            }

            is MediaStripItem.Image -> {
                when (item.type) {
                    BlockType.VIDEO -> {
                        // ▶️ Priorité à la transcription liée si présente.
                        uiScope.launch {
                            val ctx = withContext(Dispatchers.IO) {
                                val videoBlock = blocksRepo.getBlock(item.blockId)
                                val noteId = videoBlock?.noteId
                                val gid = videoBlock?.groupId
                                val linkedTextId = if (noteId != null && !gid.isNullOrBlank()) {
                                    val all = blocksRepo.observeBlocks(noteId).first()
                                    all.firstOrNull { it.type == BlockType.TEXT && it.groupId == gid }?.id
                                } else null
                                Triple(noteId, linkedTextId, videoBlock?.mediaUri)
                            }

                            val noteId = ctx.first
                            val linkedTextId = ctx.second

                            if (noteId != null && linkedTextId != null) {
                                ChildPostitSheet.edit(noteId, linkedTextId)
                                    .show(activity.supportFragmentManager, "child_text_edit_$linkedTextId")
                                return@launch
                            }

                            // Fallback : lecteur vidéo
                            val intent = Intent(activity, VideoPlayerActivity::class.java).apply {
                                putExtra(VideoPlayerActivity.EXTRA_URI, item.mediaUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            activity.startActivity(intent)
                        }
                    }

                    BlockType.LOCATION, BlockType.ROUTE -> {
                        uiScope.launch {
                            val block = withContext(Dispatchers.IO) { blocksRepo.getBlock(item.blockId) }
                            if (block == null) {
                                Toast.makeText(
                                    activity,
                                    activity.getString(R.string.media_missing_file),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@launch
                            }

                            val (lat, lon, label) = when (block.type) {
                                BlockType.LOCATION -> {
                                    val lat = block.lat
                                    val lon = block.lon
                                    if (lat == null || lon == null) {
                                        null
                                    } else {
                                        val lbl = block.placeName?.takeIf { it.isNotBlank() }
                                            ?: activity.getString(R.string.block_location_coordinates, lat, lon)
                                        Triple(lat, lon, lbl)
                                    }
                                }

                                BlockType.ROUTE -> {
                                    val payload = block.routeJson?.let { json ->
                                        runCatching { routeGson.fromJson(json, RoutePayload::class.java) }.getOrNull()
                                    }
                                    val firstPoint = payload?.firstPoint()
                                    val lat = firstPoint?.lat ?: block.lat
                                    val lon = firstPoint?.lon ?: block.lon
                                    if (lat == null || lon == null) {
                                        null
                                    } else {
                                        val lbl = if (payload != null && payload.pointCount > 0) {
                                            activity.getString(R.string.block_route_points, payload.pointCount)
                                        } else {
                                            activity.getString(R.string.block_location_coordinates, lat, lon)
                                        }
                                        Triple(lat, lon, lbl)
                                    }
                                }

                                else -> null
                            } ?: run {
                                Toast.makeText(
                                    activity,
                                    activity.getString(R.string.map_location_unavailable),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@launch
                            }

                            LocationPreviewSheet.show(
                                fm = activity.supportFragmentManager,
                                noteId = block.noteId,
                                blockId = block.id,
                                lat = lat,
                                lon = lon,
                                label = label,
                                type = block.type
                            )
                        }
                    }

                    else -> {
                        val intent = Intent(activity, PhotoViewerActivity::class.java).apply {
                            putExtra("path", item.mediaUri)
                        }
                        activity.startActivity(intent)
                    }
                }
            }

            is MediaStripItem.Audio -> {
                uiScope.launch {
                    val triple = withContext(Dispatchers.IO) {
                        val audioBlock = blocksRepo.getBlock(item.blockId)
                        val noteId = audioBlock?.noteId
                        val linkedTextId = audioBlock?.id?.let { blocksRepo.findTextForAudio(it) }
                        Triple(noteId, linkedTextId, audioBlock?.mediaUri)
                    }
                    val noteId = triple.first
                    val linkedTextId = triple.second

                    if (noteId != null && linkedTextId != null) {
                        ChildPostitSheet.edit(noteId, linkedTextId)
                            .show(activity.supportFragmentManager, "child_text_edit_$linkedTextId")
                        return@launch
                    }

                    // Fallback : lecture directe
                    val uriStr: String? = item.mediaUri.takeIf { it.isNotBlank() }
                    if (uriStr == null) {
                        Toast.makeText(activity, activity.getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    SimplePlayer.play(
                        ctx = activity,
                        path = uriStr,
                        onStart = { /* no-op */ },
                        onStop  = { /* no-op */ },
                        onError = { e ->
                            Toast.makeText(
                                activity,
                                "Lecture impossible : ${e?.message ?: "erreur inconnue"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            }

            is MediaStripItem.Text -> {
                ChildPostitSheet.edit(item.noteId, item.blockId)
                    .show(activity.supportFragmentManager, "child_text_edit_${item.blockId}")
            }
        }
    }

    fun showMenu(anchor: View, item: MediaStripItem) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, MENU_SHARE, 0, activity.getString(R.string.media_action_share))
        popup.menu.add(0, MENU_DELETE, 1, activity.getString(R.string.media_action_delete))

        // 🗺️ Option spéciale pour la pile Carte : “Ouvrir dans Google Maps”
        val mapsEnabledForPile = (item as? MediaStripItem.Pile)?.category == MediaCategory.LOCATION
        if (mapsEnabledForPile) {
            popup.menu.add(0, MENU_OPEN_IN_MAPS, 2, activity.getString(R.string.block_open_in_google_maps))
        }

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_SHARE -> { share(item); true }
                MENU_DELETE -> { confirmDelete(item); true }
                MENU_OPEN_IN_MAPS -> {
                    val pile = item as? MediaStripItem.Pile
                    if (pile != null) openLocationPileCoverInMaps(pile) else Unit
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    // --- “Ouvrir dans Google Maps” pour la pile Carte ---

    private fun openLocationPileCoverInMaps(pile: MediaStripItem.Pile) {
        // On ouvre selon le bloc “cover” de la pile (LOCATION ou ROUTE).
        val coverId = pile.cover.blockId
        uiScope.launch {
            val block = withContext(Dispatchers.IO) { blocksRepo.getBlock(coverId) }
            if (block == null) {
                Toast.makeText(activity, activity.getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
                return@launch
            }
            openBlockInGoogleMaps(block)
        }
    }

    private fun openBlockInGoogleMaps(block: BlockEntity) {
        val ctx = activity
        when (block.type) {
            BlockType.LOCATION -> {
                val lat = block.lat
                val lon = block.lon
                if (lat == null || lon == null) {
                    Toast.makeText(ctx, R.string.map_pick_google_maps_unavailable, Toast.LENGTH_SHORT).show()
                    return
                }
                val label = block.placeName?.takeIf { it.isNotBlank() }
                    ?: ctx.getString(R.string.block_location_coordinates, lat, lon)
                val encoded = Uri.encode(label)
                val geo = Uri.parse("geo:0,0?q=$lat,$lon($encoded)")
                launchMapsIntent(geo) || launchWebMaps(lat, lon) || toastMapsUnavailable()
            }
            BlockType.ROUTE -> {
                // On prend le 1er point comme destination (simple et robuste).
                val payload = block.routeJson?.let { runCatching { routeGson.fromJson(it, RoutePayload::class.java) }.getOrNull() }
                val first = payload?.firstPoint()
                val lat = first?.lat ?: block.lat
                val lon = first?.lon ?: block.lon
                if (lat == null || lon == null) {
                    Toast.makeText(ctx, R.string.map_pick_google_maps_unavailable, Toast.LENGTH_SHORT).show()
                    return
                }
                val label = if (payload != null && payload.pointCount > 0) {
                    ctx.getString(R.string.block_route_points, payload.pointCount)
                } else {
                    ctx.getString(R.string.block_location_coordinates, lat, lon)
                }
                val encoded = Uri.encode(label)
                val geo = Uri.parse("geo:0,0?q=$lat,$lon($encoded)")
                launchMapsIntent(geo) || launchWebMaps(lat, lon) || toastMapsUnavailable()
            }
            else -> {
                Toast.makeText(ctx, R.string.map_pick_google_maps_unavailable, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchMapsIntent(uri: Uri): Boolean {
        val pm = activity.packageManager
        val intent = Intent(Intent.ACTION_VIEW, uri)
        return if (intent.resolveActivity(pm) != null) {
            runCatching { activity.startActivity(intent) }.isSuccess
        } else false
    }

    private fun launchWebMaps(lat: Double, lon: Double): Boolean {
        val url = String.format(Locale.US, "https://www.google.com/maps/search/?api=1&query=%f,%f", lat, lon)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val pm = activity.packageManager
        return if (intent.resolveActivity(pm) != null) {
            runCatching { activity.startActivity(intent) }.isSuccess
        } else false
    }

    private fun toastMapsUnavailable(): Boolean {
        Toast.makeText(activity, R.string.map_pick_google_maps_unavailable, Toast.LENGTH_SHORT).show()
        return false
    }

    // --- Partage ---

    private fun share(item: MediaStripItem) {
        when (item) {
            is MediaStripItem.Pile -> share(item.cover)
            is MediaStripItem.Image -> shareFile(item.mediaUri, item.mimeType ?: inferImageOrVideoMime(item))
            is MediaStripItem.Audio -> shareFile(item.mediaUri, item.mimeType ?: "audio/*")
            is MediaStripItem.Text  -> shareText(item.content)
        }
    }

    private fun inferImageOrVideoMime(item: MediaStripItem.Image): String {
        return if (item.type == BlockType.VIDEO) "video/*" else "image/*"
    }

    private fun shareFile(rawPathOrUri: String, mime: String) {
        val shareUri = resolveShareUri(rawPathOrUri) ?: run {
            Toast.makeText(activity, activity.getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val targets = activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        targets.forEach { info ->
            activity.grantUriPermission(info.activityInfo.packageName, shareUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.media_action_share)))
        }.onFailure {
            Toast.makeText(activity, activity.getString(R.string.media_share_error), Toast.LENGTH_LONG).show()
        }
    }

    private fun shareText(text: String) {
        val content = text.ifBlank { " " }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
        }
        runCatching {
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.media_action_share)))
        }.onFailure {
            Toast.makeText(activity, activity.getString(R.string.media_share_error), Toast.LENGTH_LONG).show()
        }
    }

    // --- Suppression ---

    private fun confirmDelete(item: MediaStripItem) {
        val target = if (item is MediaStripItem.Pile) item.cover else item
        AlertDialog.Builder(activity)
            .setTitle(R.string.media_action_delete)
            .setMessage(R.string.media_delete_confirm)
            .setPositiveButton(R.string.action_validate) { _, _ -> performDelete(target) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun performDelete(item: MediaStripItem) {
        if (item is MediaStripItem.Pile) {
            performDelete(item.cover)
            return
        }
        uiScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    when (item) {
                        is MediaStripItem.Image -> {
                            deleteMediaFile(item.mediaUri); blocksRepo.deleteBlock(item.blockId)
                        }
                        is MediaStripItem.Audio -> {
                            deleteMediaFile(item.mediaUri); blocksRepo.deleteBlock(item.blockId)
                        }
                        is MediaStripItem.Text -> {
                            blocksRepo.deleteBlock(item.blockId)
                        }
                        is MediaStripItem.Pile -> Unit
                    }
                }.isSuccess
            }
            if (ok) {
                Toast.makeText(activity, activity.getString(R.string.media_delete_done), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, activity.getString(R.string.media_delete_error), Toast.LENGTH_LONG).show()
            }
        }
    }

    // --- Fichiers util ---

    private fun deleteMediaFile(rawUri: String) {
        runCatching {
            val uri = Uri.parse(rawUri)
            when {
                uri.scheme.isNullOrEmpty() -> File(rawUri).takeIf { it.exists() }?.delete()
                uri.scheme.equals("file", ignoreCase = true) -> uri.path?.let { p -> File(p).takeIf { it.exists() }?.delete() }
                uri.scheme.equals("content", ignoreCase = true) -> activity.contentResolver.delete(uri, null, null)
                else -> uri.path?.let { p -> File(p).takeIf { it.exists() }?.delete() }
            }
        }
    }

    private fun resolveShareUri(raw: String): Uri? {
        val parsed = Uri.parse(raw)
        return when {
            parsed.scheme.isNullOrEmpty() -> {
                val file = File(raw)
                if (!file.exists()) null
                else FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            }
            parsed.scheme.equals("file", ignoreCase = true) -> {
                val file = File(parsed.path ?: return null)
                if (!file.exists()) null
                else FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            }
            parsed.scheme.equals("content", ignoreCase = true) -> parsed
            else -> parsed
        }
    }

    private companion object {
        const val MENU_SHARE = 1
        const val MENU_DELETE = 2
        const val MENU_OPEN_IN_MAPS = 3
    }
}
