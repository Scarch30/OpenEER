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
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.ui.PhotoViewerActivity
import com.example.openeer.ui.SimplePlayer
import com.example.openeer.ui.sheets.ChildTextViewerSheet
import com.example.openeer.ui.sheets.MediaGridSheet
import com.example.openeer.ui.viewer.VideoPlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Actions communes pour la strip média et la grille :
 * - Ouverture (image, vidéo, audio, post-it)
 * - Partage / Suppression
 * - Ouverture d’une pile en grille
 */
class MediaActions(
    private val activity: AppCompatActivity,
    private val blocksRepo: BlocksRepository
) {
    private val uiScope = CoroutineScope(Dispatchers.Main)

    /** Ouvre la grille d’une catégorie pour une note donnée. */
    fun handlePileClick(noteId: Long, category: MediaCategory) {
        MediaGridSheet.show(activity.supportFragmentManager, noteId, category)
    }

    /** Gestion du clic sur un item individuel. */
    fun handleClick(item: MediaStripItem) {
        when (item) {
            is MediaStripItem.Pile -> {
                handlePileClick(item.coverNoteId(), item.category)
            }
            is MediaStripItem.Image -> {
                if (item.type == BlockType.VIDEO) {
                    // ✅ Lecteur vidéo in-app
                    val intent = Intent(activity, VideoPlayerActivity::class.java).apply {
                        putExtra(VideoPlayerActivity.EXTRA_URI, item.mediaUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(intent)
                } else {
                    // Photo / Sketch
                    val intent = Intent(activity, PhotoViewerActivity::class.java).apply {
                        putExtra("path", item.mediaUri)
                    }
                    activity.startActivity(intent)
                }
            }
            is MediaStripItem.Audio -> {
                // ⚠️ Correction : on LIT via l’URI si possible (content:// ou file://). Fallback: chemin absolu.
                val uriStr: String? = item.mediaUri.takeIf { it.isNotBlank() }
                if (uriStr == null) {
                    Toast.makeText(activity, activity.getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
                    return
                }

                SimplePlayer.play(
                    ctx = activity,
                    path = uriStr,
                    onStart = { Toast.makeText(activity, "Lecture…", Toast.LENGTH_SHORT).show() },
                    onStop  = { Toast.makeText(activity, "Lecture terminée", Toast.LENGTH_SHORT).show() },
                    onError = { e ->
                        Toast.makeText(
                            activity,
                            "Lecture impossible : ${e?.message ?: "erreur inconnue"}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
            is MediaStripItem.Text -> {
                ChildTextViewerSheet.show(
                    activity.supportFragmentManager,
                    item.noteId,
                    item.blockId,
                )
            }
        }
    }

    /** Menu contextuel (long press). */
    fun showMenu(anchor: View, item: MediaStripItem) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, MENU_SHARE, 0, activity.getString(R.string.media_action_share))
        popup.menu.add(0, MENU_DELETE, 1, activity.getString(R.string.media_action_delete))
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_SHARE -> { share(item); true }
                MENU_DELETE -> { confirmDelete(item); true }
                else -> false
            }
        }
        popup.show()
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
            val ok = kotlinx.coroutines.withContext(Dispatchers.IO) {
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

    private fun MediaStripItem.Pile.coverNoteId(): Long = when (val c = cover) {
        is MediaStripItem.Image -> c.blockId
        is MediaStripItem.Audio -> c.blockId
        is MediaStripItem.Text  -> c.noteId
        is MediaStripItem.Pile  -> c.coverNoteId()
    }.let { _ ->
        (cover as? MediaStripItem.Text)?.noteId ?: 0L
    }

    private companion object {
        const val MENU_SHARE = 1
        const val MENU_DELETE = 2
    }
}
