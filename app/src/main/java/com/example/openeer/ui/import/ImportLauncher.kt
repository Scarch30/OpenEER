package com.example.openeer.ui.import

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.generateGroupId
import com.example.openeer.services.WhisperService
import com.example.openeer.ui.util.toast
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

private const val LOG_TAG = "ImportLauncher"
const val OCR_PLACEHOLDER_TEXT = "Texte à venir (OCR)" // TODO(sprint3): replace with real OCR output.

/**
 * Determine the import type from a mime type string.
 */
fun mimeToImportType(mimeType: String?): ImportType? {
    if (mimeType.isNullOrBlank()) return null
    return when {
        mimeType.startsWith("audio/") -> ImportType.AUDIO
        mimeType.startsWith("image/") -> ImportType.IMAGE
        mimeType.startsWith("video/") -> ImportType.VIDEO
        else -> null
    }
}

data class ImportResult(
    val noteId: Long,
    val mediaBlockId: Long?,
    val highlightBlockId: Long?,
    val localPath: String,
    val mimeType: String?
)

enum class ImportType { AUDIO, IMAGE, VIDEO }

class MediaImporter(
    private val context: Context,
    private val blocksRepo: BlocksRepository,
    private val whisperTranscribe: suspend (File) -> String = { file ->
        WhisperService.transcribeWav(file)
    }
) {

    suspend fun import(noteId: Long, uri: Uri, overrideMimeType: String? = null): ImportResult? {
        val resolvedMime = resolveMimeType(uri, overrideMimeType)
        val importType = mimeToImportType(resolvedMime)
        if (importType == null) {
            Log.w(LOG_TAG, "Unsupported mime type: $resolvedMime for uri=$uri")
            return null
        }

        val displayName = queryDisplayName(uri)
        val extension = chooseExtension(uri, displayName, resolvedMime)
        val localFile = copyToLocal(uri, extension)
        val groupId = generateGroupId()

        return when (importType) {
            ImportType.AUDIO -> importAudio(noteId, localFile, resolvedMime, groupId)
            ImportType.IMAGE -> importImage(noteId, localFile, resolvedMime, groupId)
            ImportType.VIDEO -> importVideo(noteId, localFile, resolvedMime, groupId)
        }
    }

    private suspend fun importAudio(
        noteId: Long,
        file: File,
        mimeType: String?,
        groupId: String
    ): ImportResult {
        val audioBlockId = blocksRepo.appendAudio(
            noteId = noteId,
            mediaUri = file.absolutePath,
            durationMs = null,
            mimeType = mimeType,
            groupId = groupId
        )

        val transcription = runCatching {
            withContext(Dispatchers.Default) { whisperTranscribe(file) }
        }.getOrElse { error ->
            Log.e(LOG_TAG, "Whisper transcription failed", error)
            "Transcription indisponible"
        }.ifBlank { "Transcription indisponible" }

        blocksRepo.updateAudioTranscription(audioBlockId, transcription)
        val textBlockId = blocksRepo.appendText(noteId, transcription, groupId)

        return ImportResult(
            noteId = noteId,
            mediaBlockId = audioBlockId,
            highlightBlockId = textBlockId,
            localPath = file.absolutePath,
            mimeType = mimeType
        )
    }

    private suspend fun importImage(
        noteId: Long,
        file: File,
        mimeType: String?,
        groupId: String
    ): ImportResult {
        val photoBlockId = blocksRepo.appendPhoto(
            noteId = noteId,
            mediaUri = file.absolutePath,
            mimeType = mimeType,
            groupId = groupId
        )
        val textBlockId = blocksRepo.appendText(noteId, OCR_PLACEHOLDER_TEXT, groupId)
        return ImportResult(
            noteId = noteId,
            mediaBlockId = photoBlockId,
            highlightBlockId = textBlockId,
            localPath = file.absolutePath,
            mimeType = mimeType
        )
    }

    private suspend fun importVideo(
        noteId: Long,
        file: File,
        mimeType: String?,
        groupId: String
    ): ImportResult {
        val videoBlockId = blocksRepo.appendVideo(
            noteId = noteId,
            mediaUri = file.absolutePath,
            mimeType = mimeType,
            groupId = groupId
        )
        val textBlockId = blocksRepo.appendText(noteId, OCR_PLACEHOLDER_TEXT, groupId)
        return ImportResult(
            noteId = noteId,
            mediaBlockId = videoBlockId,
            highlightBlockId = textBlockId,
            localPath = file.absolutePath,
            mimeType = mimeType
        )
    }

    private suspend fun copyToLocal(uri: Uri, extension: String?): File = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "imports").apply { mkdirs() }
        val normalizedExtension = extension?.takeIf { it.isNotBlank() }?.trimStart('.')
        val filename = buildString {
            append("import_")
            append(System.currentTimeMillis())
            if (!normalizedExtension.isNullOrBlank()) {
                append('.')
                append(normalizedExtension)
            }
        }
        val destination = File(directory, filename)
        context.contentResolver.openInputStream(uri).useInputStream { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
            }
        }
        destination
    }

    private fun InputStream?.useInputStream(block: (InputStream) -> Unit) {
        val stream = this ?: error("Unable to open input stream for import")
        stream.use(block)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    }

    private fun chooseExtension(uri: Uri, displayName: String?, mimeType: String?): String? {
        val fromName = displayName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() }
        val fromUri = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() }
        val fromMime = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return fromName ?: fromUri ?: fromMime
    }

    private fun resolveMimeType(uri: Uri, overrideMimeType: String?): String? {
        if (!overrideMimeType.isNullOrBlank()) return overrideMimeType
        return context.contentResolver.getType(uri)
            ?: guessMimeTypeFromUri(uri)
    }

    private fun guessMimeTypeFromUri(uri: Uri): String? {
        val segment = uri.lastPathSegment ?: return null
        val extension = segment.substringAfterLast('.', "").lowercase()
        if (extension.isBlank()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }
}

class ImportLauncher(
    private val activity: AppCompatActivity,
    private val blocksRepo: BlocksRepository,
    private val onChildBlockSaved: (noteId: Long, blockId: Long?, message: String) -> Unit,
    private val whisperTranscribe: suspend (File) -> String = { file ->
        WhisperService.transcribeWav(file)
    }
) {
    private val importer = MediaImporter(activity, blocksRepo, whisperTranscribe)
    private var pendingNoteId: Long? = null

    private val openDocumentLauncher =
        activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val noteId = pendingNoteId
            pendingNoteId = null
            if (uri == null || noteId == null) {
                return@registerForActivityResult
            }

            activity.lifecycleScope.launch {
                val result = runCatching { importer.import(noteId, uri) }
                    .getOrElse { error ->
                        Log.e(LOG_TAG, "Failed to import media", error)
                        activity.toast("Échec de l'import", Toast.LENGTH_LONG)
                        null
                    }
                if (result != null) {
                    val highlight = result.highlightBlockId
                    onChildBlockSaved(noteId, highlight, "Import ajouté")
                }
            }
        }

    fun launchImport(noteId: Long?) {
        pendingNoteId = noteId
        openDocumentLauncher.launch(MIME_TYPES)
    }

    companion object {
        private val MIME_TYPES = arrayOf("audio/*", "image/*", "video/*")
    }
}
