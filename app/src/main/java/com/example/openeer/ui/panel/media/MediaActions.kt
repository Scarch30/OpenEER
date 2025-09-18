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
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.ui.PhotoViewerActivity
import com.example.openeer.ui.SimplePlayer
import com.example.openeer.ui.sheets.ChildTextEditorSheet
import com.example.openeer.ui.sheets.MediaGridSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * Actions communes pour les items de la strip média (click / long press menu).
 * Gère maintenant IMAGE, AUDIO et TEXT (post-it).
 */
class MediaActions(
    private val activity: AppCompatActivity,
    private val blocksRepo: BlocksRepository
) {
    private val uiScope = CoroutineScope(Dispatchers.Main)

    fun handlePileClick(noteId: Long, category: MediaCategory) {
        MediaGridSheet.show(activity.supportFragmentManager, noteId, category)
    }

    fun handleClick(item: MediaStripItem) {
        when (item) {
            is MediaStripItem.Pile -> handleClick(item.cover)
            is MediaStripItem.Image -> {
                // Ouvrir viewer photo
                val intent = Intent(activity, PhotoViewerActivity::class.java).apply {
                    putExtra("path", item.mediaUri) // non-null
                }
                activity.startActivity(intent)
            }
            is MediaStripItem.Audio -> {
                // Jouer audio local
                val path = item.mediaUri // non-null
                if (path.startsWith("content://")) {
                    Toast.makeText(activity, activity.getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
                    return
                }
                val file = File(path)
                if (!file.exists()) {
                    Toast.makeText(activity, activity.getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
                    return
                }
                SimplePlayer.play(
                    ctx = activity,
                    path = file.absolutePath,
                    onStart = { Toast.makeText(activity, "Lecture…", Toast.LENGTH_SHORT).show() },
                    onStop = { Toast.makeText(activity, "Lecture terminée", Toast.LENGTH_SHORT).show() },
                    onError = { e ->
                        Toast.makeText(activity, "Lecture impossible : ${e.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
            is MediaStripItem.Text -> {
                val sheet = ChildTextEditorSheet.edit(
                    noteId = item.noteId,
                    blockId = item.blockId,
                    initialContent = item.content,
                )
                sheet.show(activity.supportFragmentManager, "child_text")
            }
        }
    }

    fun showMenu(anchor: View, item: MediaStripItem) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, MENU_SHARE, 0, activity.getString(R.string.media_action_share))
        popup.menu.add(0, MENU_DELETE, 1, activity.getString(R.string.media_action_delete))
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_SHARE -> {
                    share(item)
                    true
                }
                MENU_DELETE -> {
                    confirmDelete(item)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun share(item: MediaStripItem) {
        when (item) {
            is MediaStripItem.Pile -> share(item.cover)
            is MediaStripItem.Image -> {
                shareFile(item.mediaUri, item.mimeType ?: "image/*")
            }
            is MediaStripItem.Audio -> {
                shareFile(item.mediaUri, item.mimeType ?: "audio/*")
            }
            is MediaStripItem.Text -> {
                shareText(item.content)
            }
        }
    }

    private fun confirmDelete(item: MediaStripItem) {
        val target = if (item is MediaStripItem.Pile) item.cover else item
        AlertDialog.Builder(activity)
            .setTitle(R.string.media_action_delete)
            .setMessage(R.string.media_delete_confirm)
            .setPositiveButton(R.string.action_validate) { _, _ ->
                performDelete(target)
            }
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
                            deleteMediaFile(item.mediaUri)
                            blocksRepo.deleteBlock(item.blockId)
                        }
                        is MediaStripItem.Audio -> {
                            deleteMediaFile(item.mediaUri)
                            blocksRepo.deleteBlock(item.blockId)
                        }
                        is MediaStripItem.Text -> {
                            // Pas de fichier à supprimer
                            blocksRepo.deleteBlock(item.blockId)
                        }
                        else -> Unit
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

    // ---- Partage ----

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

    // ---- Fichiers ----

    private fun deleteMediaFile(rawUri: String) {
        runCatching {
            val uri = Uri.parse(rawUri)
            when {
                uri.scheme.isNullOrEmpty() -> File(rawUri).takeIf { it.exists() }?.delete()
                uri.scheme.equals("file", ignoreCase = true) -> uri.path?.let { path ->
                    File(path).takeIf { it.exists() }?.delete()
                }
                uri.scheme.equals("content", ignoreCase = true) ->
                    activity.contentResolver.delete(uri, null, null)
                else -> uri.path?.let { path ->
                    File(path).takeIf { it.exists() }?.delete()
                }
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
    }
}
