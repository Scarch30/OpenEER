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
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.ui.PhotoViewerActivity
import com.example.openeer.ui.SimplePlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MediaActions(
    private val activity: AppCompatActivity,
    private val blocksRepo: BlocksRepository,
) {

    fun handleClick(item: MediaStripItem) {
        when (item) {
            is MediaStripItem.Image -> {
                val intent = Intent(activity, PhotoViewerActivity::class.java).apply {
                    putExtra("path", item.mediaUri)
                }
                activity.startActivity(intent)
            }
            is MediaStripItem.Audio -> playAudio(item)
        }
    }

    fun showMenu(anchor: View, item: MediaStripItem) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, MENU_SHARE, 0, activity.getString(R.string.media_action_share))
        popup.menu.add(0, MENU_DELETE, 1, activity.getString(R.string.media_action_delete))
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_SHARE -> {
                    shareMedia(item)
                    true
                }
                MENU_DELETE -> {
                    confirmDeleteMedia(item)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun shareMedia(item: MediaStripItem) {
        val shareUri = resolveShareUri(item.mediaUri)
        if (shareUri == null) {
            Toast.makeText(activity, activity.getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
            return
        }
        val mime = when (item) {
            is MediaStripItem.Audio -> item.mimeType ?: "audio/*"
            is MediaStripItem.Image -> item.mimeType ?: "image/*"
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

    private fun confirmDeleteMedia(item: MediaStripItem) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.media_action_delete)
            .setMessage(R.string.media_delete_confirm)
            .setPositiveButton(R.string.action_validate) { _, _ ->
                performDeleteMedia(item)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun performDeleteMedia(item: MediaStripItem) {
        activity.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    deleteMediaFile(item.mediaUri)
                    blocksRepo.deleteBlock(item.blockId)
                }.isSuccess
            }
            if (success) {
                Toast.makeText(activity, activity.getString(R.string.media_delete_done), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, activity.getString(R.string.media_delete_error), Toast.LENGTH_LONG).show()
            }
        }
    }

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
            parsed.scheme.isNullOrEmpty() -> fileProviderUri(File(raw))
            parsed.scheme.equals("file", ignoreCase = true) -> parsed.path?.let { path ->
                fileProviderUri(File(path))
            }
            parsed.scheme.equals("content", ignoreCase = true) -> parsed
            else -> parsed
        }
    }

    private fun fileProviderUri(file: File): Uri? {
        if (!file.exists()) return null
        return try {
            FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun playAudio(item: MediaStripItem.Audio) {
        val raw = item.mediaUri
        if (raw.startsWith("content://")) {
            Toast.makeText(activity, activity.getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(raw)
        if (!file.exists()) {
            Toast.makeText(activity, activity.getString(R.string.media_missing_file), Toast.LENGTH_SHORT).show()
            return
        }
        SimplePlayer.play(
            ctx = activity,
            path = file.absolutePath,
            onStart = {
                Toast.makeText(activity, "Lecture…", Toast.LENGTH_SHORT).show()
            },
            onStop = {
                Toast.makeText(activity, "Lecture terminée", Toast.LENGTH_SHORT).show()
            },
            onError = { e ->
                Toast.makeText(activity, "Lecture impossible : ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private companion object {
        const val MENU_SHARE = 1
        const val MENU_DELETE = 2
    }
}
