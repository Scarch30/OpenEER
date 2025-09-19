package com.example.openeer.ui.panel.media

import com.example.openeer.data.block.BlockType

/**
 * Modèles d’items affichés dans la strip média.
 * Tous rendus en tuiles carrées 90dp via MediaStripAdapter.
 */
sealed class MediaStripItem {
    abstract val blockId: Long
    abstract val mediaUri: String?
    abstract val mimeType: String?

    data class Pile(
        val category: MediaCategory,
        val count: Int,
        val cover: MediaStripItem,
    ) : MediaStripItem() {
        override val blockId: Long = -(category.ordinal + 1).toLong()
        override val mediaUri: String? = cover.mediaUri
        override val mimeType: String? = cover.mimeType
    }

    data class Image(
        override val blockId: Long,
        override val mediaUri: String,   // chemin/uri (photo, sketch, vidéo -> vignette)
        override val mimeType: String?,
        val type: BlockType,             // PHOTO, SKETCH, ou VIDEO
    ) : MediaStripItem()

    data class Audio(
        override val blockId: Long,
        override val mediaUri: String,   // chemin local attendu
        override val mimeType: String?,
        val durationMs: Long?,
    ) : MediaStripItem()

    /**
     * Post-it texte (bloc enfant TEXT). Pas de mediaUri.
     * "preview" = 1re ligne/aperçu (trim + ellipsize côté UI).
     */
    data class Text(
        override val blockId: Long,
        val noteId: Long,
        val content: String,
    ) : MediaStripItem() {
        override val mediaUri: String? = null
        override val mimeType: String? = null

        val preview: String
            get() = content
                .lineSequence()
                .firstOrNull()
                ?.trim()
                .orEmpty()
    }
}
