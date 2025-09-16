package com.example.openeer.domain.usecases

import com.example.openeer.data.repo.NoteRepository
import com.example.openeer.domain.model.Source

class CreateMotherNoteUseCase(
    private val noteRepository: NoteRepository
) {
    suspend operator fun invoke(source: Source, seedText: String?): Long =
        noteRepository.createMotherNote(source, seedText)
}
