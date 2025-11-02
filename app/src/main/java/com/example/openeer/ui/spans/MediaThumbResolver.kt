package com.example.openeer.ui.spans

import android.content.Context
import com.example.openeer.Injection
import com.example.openeer.data.block.BlockType
import com.example.openeer.ui.library.MapPreviewStorage
import java.io.File
import kotlinx.coroutines.runBlocking

object MediaThumbResolver {

    fun resolveSource(context: Context, blockId: Long): ThumbSource {
        val repository = Injection.provideBlocksRepository(context)
        val block = runBlocking { repository.getBlock(blockId) }
            ?: return ThumbSource.Placeholder(ThumbSource.Kind.UNKNOWN)

        return when (block.type) {
            BlockType.PHOTO, BlockType.SKETCH, BlockType.VIDEO -> {
                val uri = block.mediaUri
                if (!uri.isNullOrBlank()) {
                    ThumbSource.Image(uri)
                } else {
                    ThumbSource.Placeholder(ThumbSource.Kind.UNKNOWN)
                }
            }
            BlockType.LOCATION -> {
                val file = MapPreviewStorage.locationPreviewFile(context, blockId)
                file?.let { ThumbSource.MapSnapshot(it) }
                    ?: ThumbSource.Placeholder(ThumbSource.Kind.UNKNOWN)
            }
            BlockType.ROUTE -> {
                val file = MapPreviewStorage.routePreviewFile(context, blockId)
                file?.let { ThumbSource.MapSnapshot(it) }
                    ?: ThumbSource.Placeholder(ThumbSource.Kind.UNKNOWN)
            }
            BlockType.AUDIO -> ThumbSource.Placeholder(ThumbSource.Kind.AUDIO)
            BlockType.FILE -> ThumbSource.Placeholder(ThumbSource.Kind.FILE)
            else -> ThumbSource.Placeholder(ThumbSource.Kind.UNKNOWN)
        }
    }

    sealed class ThumbSource {
        data class Image(val model: Any) : ThumbSource()
        data class MapSnapshot(val file: File) : ThumbSource()
        data class Placeholder(val kind: Kind) : ThumbSource()

        enum class Kind { AUDIO, FILE, UNKNOWN }
    }
}
