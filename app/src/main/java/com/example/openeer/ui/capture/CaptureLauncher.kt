package com.example.openeer.ui.capture

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.ui.KeyboardCaptureActivity
import com.example.openeer.ui.NotePanelController
import com.example.openeer.ui.util.toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val STATE_TEMP_PHOTO_PATH = "tempPhotoPath"

class CaptureLauncher(
    private val activity: AppCompatActivity,
    private val notePanel: NotePanelController,
    private val repo: NoteRepository,
    private val blocksRepo: BlocksRepository,
    private val onChildBlockSaved: (noteId: Long, blockId: Long?, message: String) -> Unit,
) {

    private var tempPhotoPath: String? = null

    private val takePhotoLauncher = activity.registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val path = tempPhotoPath
        val nid = notePanel.openNoteId
        if (ok && path != null && nid != null) {
            activity.lifecycleScope.launch(Dispatchers.IO) {
                repo.addPhoto(nid, path)
                blocksRepo.appendPhoto(nid, path, mimeType = "image/*")
            }
        } else if (path != null) {
            File(path).delete()
        }
        tempPhotoPath = null
    }

    private val pickPhotoLauncher =
        activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val nid = notePanel.openNoteId ?: return@registerForActivityResult
            if (uri != null) {
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    val dir = File(activity.filesDir, "images").apply { mkdirs() }
                    val dest = File(dir, "img_${System.currentTimeMillis()}.jpg")
                    activity.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                    repo.addPhoto(nid, dest.absolutePath)
                    blocksRepo.appendPhoto(nid, dest.absolutePath, mimeType = "image/*")
                }
            }
        }

    private val keyboardCaptureLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val noteId = data.getLongExtra(KeyboardCaptureActivity.EXTRA_NOTE_ID, -1L)
                .takeIf { it > 0 } ?: notePanel.openNoteId ?: return@registerForActivityResult
            val added = data.getBooleanExtra("addedText", false)
            if (!added) return@registerForActivityResult
            val blockId = data.getLongExtra(KeyboardCaptureActivity.EXTRA_BLOCK_ID, -1L)
                .takeIf { it > 0 }
            onChildBlockSaved(noteId, blockId, "Post-it texte ajouté")
        }

    private val sketchCaptureLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val noteId = data.getLongExtra(SketchCaptureActivity.EXTRA_NOTE_ID, -1L)
                .takeIf { it > 0 } ?: notePanel.openNoteId ?: return@registerForActivityResult
            val blockId = data.getLongExtra(SketchCaptureActivity.EXTRA_BLOCK_ID, -1L)
                .takeIf { it > 0 } ?: return@registerForActivityResult
            onChildBlockSaved(noteId, blockId, "Post-it dessin ajouté")
        }

    private val readMediaPermLauncher =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openGallery()
            else activity.toast("Permission galerie refusée", Toast.LENGTH_LONG)
        }

    fun onCreate(savedInstanceState: Bundle?) {
        tempPhotoPath = savedInstanceState?.getString(STATE_TEMP_PHOTO_PATH)
    }

    fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_TEMP_PHOTO_PATH, tempPhotoPath)
    }

    fun showPhotoSheet() {
        val sheet = BottomSheetDialog(activity)
        val list = ListView(activity)
        val opts = listOf("Prendre une photo", "Depuis la galerie")
        list.adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, opts)
        list.setOnItemClickListener { _, _, pos, _ ->
            when (pos) {
                0 -> openCamera()
                1 -> openGallery()
            }
            sheet.dismiss()
        }
        sheet.setContentView(list)
        sheet.show()
    }

    fun launchKeyboardCapture(noteId: Long) {
        val intent = Intent(activity, KeyboardCaptureActivity::class.java).apply {
            putExtra(KeyboardCaptureActivity.EXTRA_NOTE_ID, noteId)
        }
        keyboardCaptureLauncher.launch(intent)
    }

    fun launchSketchCapture(noteId: Long) {
        val intent = Intent(activity, SketchCaptureActivity::class.java).apply {
            putExtra(SketchCaptureActivity.EXTRA_NOTE_ID, noteId)
        }
        sketchCaptureLauncher.launch(intent)
    }

    fun launchTakePhoto() {
        openCamera()
    }

    fun launchPhotoPicker() {
        openGallery()
    }

    private fun openCamera() {
        val nid = notePanel.openNoteId ?: return
        val dir = File(activity.filesDir, "images").apply { mkdirs() }
        val file = File(dir, "cap_${System.currentTimeMillis()}.jpg")
        tempPhotoPath = file.absolutePath
        val uri: Uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        takePhotoLauncher.launch(uri)
    }

    private fun openGallery() {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_IMAGES)
            != PackageManager.PERMISSION_GRANTED
        ) {
            readMediaPermLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
            return
        }
        pickPhotoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}
