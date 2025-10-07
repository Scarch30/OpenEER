package com.example.openeer.imports

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.net.toFile
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.generateGroupId
import com.example.openeer.media.AudioFromVideoExtractor
import com.example.openeer.services.WhisperRefineQueue
import com.example.openeer.services.WhisperService
import com.example.openeer.workers.VideoToTextWorker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportCoordinator(
    private val context: Context,
    private val resolver: ContentResolver,
    private val noteRepository: NoteRepository,
    private val blocksRepository: BlocksRepository,
    private val scope: CoroutineScope
) {
    private data class Meta(
        val displayName: String?,
        val size: Long?,
        val mime: String?,
        val kind: MediaKind
    )

    private val inFlightKeys = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val _events = MutableSharedFlow<ImportEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ImportEvent> = _events

    init {
        WhisperRefineQueue.start(scope)
    }

    suspend fun import(noteId: Long, uris: List<Uri>) {
        if (uris.isEmpty()) return
        _events.emit(ImportEvent.Started(uris.size))
        var success = 0
        for (uri in uris) {
            val meta = readMeta(uri)
            val key = buildKey(noteId, uri, meta)
            if (!inFlightKeys.add(key)) {
                Log.d(TAG, "Skip duplicate import for $uri")
                continue
            }
            val ok = try {
                importOne(noteId, uri, meta)
            } catch (t: Throwable) {
                Log.e(TAG, "Import failed for uri=$uri", t)
                _events.emit(ImportEvent.Failed(meta.displayName, t))
                false
            } finally {
                inFlightKeys.remove(key)
            }
            if (ok) success++
        }
        _events.emit(ImportEvent.Finished(success, uris.size))
    }

    private suspend fun importOne(noteId: Long, uri: Uri, meta: Meta): Boolean {
        persistPermission(uri)
        return when (meta.kind) {
            MediaKind.IMAGE -> handleImage(noteId, uri, meta)
            MediaKind.VIDEO -> handleVideo(noteId, uri, meta)
            MediaKind.AUDIO -> handleAudio(noteId, uri, meta)
            MediaKind.TEXT -> handleText(noteId, uri, meta)
            MediaKind.PDF -> handlePdf(noteId, uri, meta)
            MediaKind.UNKNOWN -> handleFile(noteId, uri, meta)
        }
    }

    private suspend fun handleImage(noteId: Long, uri: Uri, meta: Meta): Boolean = withContext(Dispatchers.IO) {
        val localUri = FileCopy.toAppSandbox(context, resolver, uri, meta.displayName)
        val file = localUri.toFile()
        val dimensions = decodeImageDimensions(file)
        noteRepository.addPhoto(noteId, file.absolutePath)
        blocksRepository.appendPhoto(
            noteId = noteId,
            mediaUri = file.absolutePath,
            width = dimensions?.first,
            height = dimensions?.second,
            mimeType = meta.mime,
            groupId = null
        )
        true
    }

    private suspend fun handleVideo(noteId: Long, uri: Uri, meta: Meta): Boolean = withContext(Dispatchers.IO) {
        val localUri = FileCopy.toAppSandbox(context, resolver, uri, meta.displayName)
        val file = localUri.toFile()
        val mmr = MediaMetadataRetriever()
        var duration: Long? = null
        var width: Int? = null
        var height: Int? = null
        runCatching {
            mmr.setDataSource(file.absolutePath)
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        }.onFailure { Log.w(TAG, "Metadata retrieval failed for video", it) }
        runCatching { mmr.release() }

        val groupId = generateGroupId()
        val videoBlockId = blocksRepository.appendVideo(
            noteId = noteId,
            mediaUri = file.absolutePath,
            mimeType = meta.mime ?: "video/*",
            durationMs = duration,
            width = width,
            height = height,
            groupId = groupId
        )

        _events.emit(ImportEvent.TranscriptionQueued(meta.displayName, MediaKind.VIDEO))
        val workUri = Uri.fromFile(file)
        VideoToTextWorker.enqueue(context, workUri, noteId, groupId, videoBlockId)
        true
    }

    private suspend fun handleAudio(noteId: Long, uri: Uri, meta: Meta): Boolean = withContext(Dispatchers.IO) {
        val localUri = FileCopy.toAppSandbox(context, resolver, uri, meta.displayName)
        val file = localUri.toFile()
        val mmr = MediaMetadataRetriever()
        var duration: Long? = null
        runCatching {
            mmr.setDataSource(file.absolutePath)
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }.onFailure { Log.w(TAG, "Metadata retrieval failed for audio", it) }
        runCatching { mmr.release() }

        val groupId = generateGroupId()
        val audioBlockId = blocksRepository.appendAudio(
            noteId = noteId,
            mediaUri = file.absolutePath,
            durationMs = duration,
            mimeType = meta.mime ?: "audio/*",
            groupId = groupId,
            transcription = null
        )

        val tmpDir = File(context.filesDir, "imports_audio").apply { mkdirs() }
        val wavFile = File(tmpDir, "audio_${audioBlockId}.wav")
        AudioFromVideoExtractor(context).extractToWav(Uri.fromFile(file), wavFile, 16_000)
        _events.emit(ImportEvent.TranscriptionQueued(meta.displayName, MediaKind.AUDIO))
        runCatching { WhisperService.ensureLoaded(context.applicationContext) }
        WhisperRefineQueue.enqueue(audioBlockId, wavFile.absolutePath) { refined ->
            scope.launch(Dispatchers.IO) {
                runCatching { blocksRepository.updateAudioTranscription(audioBlockId, refined) }
                runCatching {
                    val textId = blocksRepository.appendTranscription(
                        noteId = noteId,
                        text = refined,
                        groupId = groupId
                    )
                    runCatching { blocksRepository.linkAudioToText(audioBlockId, textId) }
                }
                wavFile.delete()
            }
        }
        true
    }

    private suspend fun handleText(noteId: Long, uri: Uri, meta: Meta): Boolean = withContext(Dispatchers.IO) {
        val localUri = FileCopy.toAppSandbox(context, resolver, uri, meta.displayName)
        val file = localUri.toFile()
        val content = file.readBytes()
        val text = decodeText(content)
        val finalText = buildTextWithProvenance(meta.displayName, text)
        blocksRepository.appendText(
            noteId = noteId,
            text = finalText,
            groupId = null
        )
        true
    }

    private suspend fun handlePdf(noteId: Long, uri: Uri, meta: Meta): Boolean = withContext(Dispatchers.IO) {
        val localUri = FileCopy.toAppSandbox(context, resolver, uri, meta.displayName)
        val file = localUri.toFile()
        val groupId = generateGroupId()
        val awaitingExtra = "{\"awaitingOcr\":true}"
        val text = runCatching { extractPdfText(file) }.getOrNull()
        val awaiting = text.isNullOrBlank()
        blocksRepository.appendFile(
            noteId = noteId,
            fileUri = file.absolutePath,
            displayName = meta.displayName,
            mimeType = meta.mime ?: "application/pdf",
            groupId = groupId,
            extra = if (awaiting) awaitingExtra else null
        )
        if (awaiting) {
            _events.emit(ImportEvent.OcrAwaiting(meta.displayName))
        } else {
            blocksRepository.appendTranscription(
                noteId = noteId,
                text = text,
                groupId = groupId
            )
        }
        true
    }

    private suspend fun handleFile(noteId: Long, uri: Uri, meta: Meta): Boolean = withContext(Dispatchers.IO) {
        val localUri = FileCopy.toAppSandbox(context, resolver, uri, meta.displayName)
        val file = localUri.toFile()
        blocksRepository.appendFile(
            noteId = noteId,
            fileUri = file.absolutePath,
            displayName = meta.displayName,
            mimeType = meta.mime,
            groupId = null,
            extra = null
        )
        true
    }

    private fun decodeImageDimensions(file: File): Pair<Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
        } catch (t: Throwable) {
            Log.w(TAG, "Image bounds decode failed", t)
            null
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        return runCatching { String(bytes, Charsets.UTF_8) }.getOrElse {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private fun buildTextWithProvenance(displayName: String?, text: String): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val header = buildString {
            append("📥 ")
            append(displayName ?: "Fichier texte importé")
            append(" — ")
            append(sdf.format(Date()))
        }
        return header + "\n\n" + text.trimEnd()
    }

    private fun extractPdfText(file: File): String? {
        val content = file.readText(Charsets.ISO_8859_1)
        val regex = Regex("\\(([^\\)]+)\\)\\s*(?:Tj|'|TJ)")
        val sb = StringBuilder()
        for (match in regex.findAll(content)) {
            val raw = match.groupValues[1]
            val cleaned = raw
                .replace("\\\\", "\\")
                .replace("\\(", "(")
                .replace("\\)", ")")
            sb.append(cleaned)
            sb.append('\n')
        }
        val text = sb.toString().trim()
        return text.takeIf { it.isNotEmpty() }
    }

    private fun readMeta(uri: Uri): Meta {
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
            }
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        val mime = MimeResolver.guessMime(resolver, uri)
        val kind = MimeResolver.kindOf(mime)
        return Meta(name, size, mime, kind)
    }

    private fun buildKey(noteId: Long, uri: Uri, meta: Meta): String {
        val sizePart = meta.size?.toString() ?: "-"
        return listOf(noteId.toString(), uri.toString(), sizePart).joinToString("|")
    }

    private fun persistPermission(uri: Uri) {
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // ignore if not persistable or already granted
        }
    }

    companion object {
        private const val TAG = "ImportCoordinator"
    }
}
