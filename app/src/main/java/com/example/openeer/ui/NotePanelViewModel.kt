package com.example.openeer.ui

import android.util.Log
import androidx.annotation.StringRes
import com.example.openeer.R
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.NoteType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class NotePanelViewModel(private val repo: NoteRepository) {
    data class ConversionMessage(@StringRes val messageRes: Int, val success: Boolean)
    data class NoteConvertedToPlainEvent(val noteId: Long, val body: String)

    private val noteConvertedToPlainEventsMutable = MutableSharedFlow<NoteConvertedToPlainEvent>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val noteConvertedToPlainEvents: SharedFlow<NoteConvertedToPlainEvent> =
        noteConvertedToPlainEventsMutable.asSharedFlow()

    suspend fun convertCurrentNoteToList(note: Note?): ConversionMessage {
        val noteId = note?.id ?: return ConversionMessage(R.string.note_convert_error_missing, false)
        Log.d(TAG, "convertCurrentNoteToList called for noteId=$noteId type=${note.type}")
        val result = runCatching { repo.convertNoteToList(noteId) }
            .onFailure { error -> Log.e(TAG, "convertCurrentNoteToList failed for noteId=$noteId", error) }
            .getOrElse { return ConversionMessage(R.string.note_convert_error_generic, false) }

        return when (result) {
            is NoteRepository.NoteConversionResult.Converted -> {
                Log.d(TAG, "convertCurrentNoteToList success noteId=$noteId items=${result.itemCount}")
                ConversionMessage(R.string.note_convert_to_list_success, true)
            }
            NoteRepository.NoteConversionResult.AlreadyTarget ->
                ConversionMessage(R.string.note_convert_already_list, false)
            NoteRepository.NoteConversionResult.NotFound ->
                ConversionMessage(R.string.note_convert_error_missing, false)
        }
    }

    suspend fun convertCurrentNoteToPlain(note: Note?): ConversionMessage {
        val noteId = note?.id ?: return ConversionMessage(R.string.note_convert_error_missing, false)
        Log.d(TAG, "convertCurrentNoteToPlain called for noteId=$noteId type=${note.type}")
        if (note.type == NoteType.PLAIN) {
            return ConversionMessage(R.string.note_convert_already_plain, false)
        }

        val (itemCount, bodyText) = runCatching { repo.convertNoteToPlain(noteId) }
            .onFailure { error -> Log.e(TAG, "convertCurrentNoteToPlain failed for noteId=$noteId", error) }
            .getOrElse { error ->
                return when (error) {
                    is NoteRepository.NoteNotFoundException ->
                        ConversionMessage(R.string.note_convert_error_missing, false)
                    else -> ConversionMessage(R.string.note_convert_error_generic, false)
                }
            }

        Log.d(TAG, "convertCurrentNoteToPlain success noteId=$noteId items=$itemCount")
        Log.i(
            TAG,
            "NoteVM  convertToPlain: note=$noteId items=$itemCount -> emit immediate body (len=${bodyText.length})",
        )
        noteConvertedToPlainEventsMutable.tryEmit(NoteConvertedToPlainEvent(noteId, bodyText))
        return ConversionMessage(R.string.note_convert_to_plain_success, true)
    }

    companion object {
        private const val TAG = "NotePanelViewModel"
    }
}
