package com.example.openeer.ui

import androidx.lifecycle.ViewModel
import com.example.openeer.data.NoteRepository

class NotesSearchViewModel(private val repo: NoteRepository) : ViewModel() {
    fun searchByTags(tags: List<String>) = repo.searchNotesByTags(tags)
}

// TODO(sprint3): Wire NotesSearchViewModel.searchByTags(tags) into the upcoming tag search UI.
