package com.example.openeer.domain.usecases

import com.example.openeer.data.repo.NoteRepository

class AppendMotherTextUseCase(
    private val noteRepository: NoteRepository
) {
    suspend operator fun invoke(noteId: Long, delta: String) {
        noteRepository.appendMotherText(noteId, delta)
    }
}
