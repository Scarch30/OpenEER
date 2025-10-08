package com.example.openeer.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.search.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryViewModel(
    private val repo: SearchRepository,
    private val noteRepo: NoteRepository
) : ViewModel() {

    private val _items = MutableStateFlow<List<Note>>(emptyList())
    val items: StateFlow<List<Note>> = _items

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private var lastQuery: String = ""

    fun search(q: String) {
        lastQuery = q
        viewModelScope.launch {
            _loading.value = true
            try {
                _items.value = repo.query(q)
            } finally {
                _loading.value = false
            }
        }
    }

    fun refresh() {
        search(lastQuery)
    }

    suspend fun mergeNotes(sourceIds: List<Long>, targetId: Long): NoteRepository.MergeResult {
        return withContext(Dispatchers.IO) {
            noteRepo.mergeNotes(sourceIds, targetId)
        }
    }

    suspend fun undoMerge(tx: NoteRepository.MergeTransaction): Boolean {
        return withContext(Dispatchers.IO) {
            noteRepo.undoMerge(tx)
        }
    }

    companion object {
        fun create(db: AppDatabase): LibraryViewModel {
            val repo = SearchRepository(
                searchDao = db.searchDao(),
                tagDao = db.tagDao()
            )
            val blocksRepo = BlocksRepository(
                blockDao = db.blockDao(),
                noteDao = db.noteDao(),
                linkDao = db.blockLinkDao()
            )
            val noteRepo = NoteRepository(db.noteDao(), db.attachmentDao(), blocksRepo)
            return LibraryViewModel(repo, noteRepo)
        }
    }
}
