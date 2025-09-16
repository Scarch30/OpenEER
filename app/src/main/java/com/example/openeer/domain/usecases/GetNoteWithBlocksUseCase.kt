package com.example.openeer.domain.usecases

import com.example.openeer.data.block.NoteWithBlocks
import com.example.openeer.data.repo.BlocksRepository

class GetNoteWithBlocksUseCase(
    private val blocksRepository: BlocksRepository
) {
    suspend operator fun invoke(noteId: Long): NoteWithBlocks =
        blocksRepository.getNoteWithBlocks(noteId)
}
