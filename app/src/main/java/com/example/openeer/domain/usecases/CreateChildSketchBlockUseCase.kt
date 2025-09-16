package com.example.openeer.domain.usecases

import com.example.openeer.data.repo.BlocksRepository

class CreateChildSketchBlockUseCase(
    private val blocksRepository: BlocksRepository
) {
    suspend operator fun invoke(noteId: Long, path: String): Long =
        blocksRepository.createChildSketchBlock(noteId, path)
}
