package com.example.openeer.ui.util

import com.example.openeer.debug.NoteDebugGuards
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CurrentNoteState {
    private val _currentNoteId = MutableStateFlow<Long?>(null)
    val currentNoteId: StateFlow<Long?> = _currentNoteId.asStateFlow()

    fun update(noteId: Long?) {
        _currentNoteId.value = noteId
        NoteDebugGuards.setCurrentNoteId(noteId)
    }

    fun value(): Long? = _currentNoteId.value

    fun requireNoteId(): Long = NoteDebugGuards.requireNoteId(_currentNoteId.value)
}
