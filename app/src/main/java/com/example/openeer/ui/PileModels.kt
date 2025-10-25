package com.example.openeer.ui

import com.example.openeer.imports.MediaKind
import com.example.openeer.ui.panel.media.MediaCategory

/** Models describing media piles associated with a note. */
data class PileCounts(
    val photos: Int = 0,
    val audios: Int = 0,
    val textes: Int = 0,
    val files: Int = 0,
    val locations: Int = 0,
) {
    fun increment(kind: MediaKind): PileCounts = when (kind) {
        MediaKind.IMAGE, MediaKind.VIDEO -> copy(photos = photos + 1)
        MediaKind.AUDIO -> copy(audios = audios + 1)
        MediaKind.TEXT -> copy(textes = textes + 1)
        MediaKind.PDF, MediaKind.UNKNOWN -> copy(files = files + 1)
    }
}

data class PileUi(
    val category: MediaCategory,
    val count: Int,
    val coverBlockId: Long?,
)
