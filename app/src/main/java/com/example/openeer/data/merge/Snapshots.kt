package com.example.openeer.data.merge

import com.example.openeer.data.block.BlockEntity
import com.google.gson.Gson
import java.security.MessageDigest

private val snapshotGson: Gson by lazy { Gson() }

private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte)
}

data class BlockSnapshot(
    val id: Long,
    val noteIdSource: Long,
    val type: String,
    val groupId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val rawJson: String,
    val hash: String
)

data class MergeSnapshot(
    val sourceId: Long,
    val targetId: Long,
    val blocks: List<BlockSnapshot>
)

fun computeBlockHash(block: BlockEntity): String {
    val digest = MessageDigest.getInstance("SHA-1")
    val payload = buildString {
        append(block.type.name)
        append('|')
        append(block.text.orEmpty())
        append('|')
        append(block.mediaUri.orEmpty())
        append('|')
        append(block.mimeType.orEmpty())
        append('|')
        append(block.durationMs?.toString().orEmpty())
        append('|')
        append(block.width?.toString().orEmpty())
        append('|')
        append(block.height?.toString().orEmpty())
        append('|')
        append(block.lat?.toString().orEmpty())
        append('|')
        append(block.lon?.toString().orEmpty())
        append('|')
        append(block.placeName.orEmpty())
        append('|')
        append(block.routeJson.orEmpty())
        append('|')
        append(block.extra.orEmpty())
    }
    return digest.digest(payload.toByteArray(Charsets.UTF_8)).toHexString()
}

fun toSnapshot(sourceNoteId: Long, block: BlockEntity): BlockSnapshot {
    val raw = snapshotGson.toJson(block)
    return BlockSnapshot(
        id = block.id,
        noteIdSource = sourceNoteId,
        type = block.type.name,
        groupId = block.groupId,
        createdAt = block.createdAt,
        updatedAt = block.updatedAt,
        rawJson = raw,
        hash = computeBlockHash(block)
    )
}
