package com.example.openeer.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.openeer.data.Note
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.search.SearchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val repo: SearchRepository
) : ViewModel() {

    private val _items = MutableStateFlow<List<Note>>(emptyList())
    val items: StateFlow<List<Note>> = _items

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    fun search(q: String) {
        viewModelScope.launch {
            _loading.value = true
            try {
                _items.value = repo.query(q)
            } finally {
                _loading.value = false
            }
        }
    }

    companion object {
        fun create(db: AppDatabase): LibraryViewModel {
            val repo = SearchRepository(
                searchDao = db.searchDao(),
                tagDao = db.tagDao()
            )
            return LibraryViewModel(repo)
        }
    }
}
