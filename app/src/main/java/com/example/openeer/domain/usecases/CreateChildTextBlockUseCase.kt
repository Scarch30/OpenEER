package com.example.openeer.domain.usecases

import com.example.openeer.data.repo.BlocksRepository

class CreateChildTextBlockUseCase(
    private val blocksRepository: BlocksRepository
) {
    suspend operator fun invoke(noteId: Long, text: String): Long =
        blocksRepository.createChildTextBlock(noteId, text)
}
