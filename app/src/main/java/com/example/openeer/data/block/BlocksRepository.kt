package com.example.openeer.data.block

import android.content.Context
import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.openeer.BuildConfig
import com.example.openeer.data.Note
import com.example.openeer.data.NoteDao
import com.example.openeer.data.NoteType
import com.example.openeer.data.link.InlineLinkDao
import com.example.openeer.data.link.InlineLinkEntity
import com.example.openeer.data.link.ListItemLinkDao
import com.example.openeer.data.link.ListItemLinkEntity
import com.example.openeer.data.list.ListItemDao
import com.example.openeer.data.list.ListItemEntity
import com.example.openeer.data.merge.BlockSnapshot
import com.example.openeer.core.FeatureFlags
import com.example.openeer.voice.SmartListSplitter
import com.google.gson.Gson
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

fun generateGroupId(): String = UUID.randomUUID().toString()

private const val LM_TAG = "InjectMother"

private inline fun logD(msg: () -> String) {
    if (BuildConfig.DEBUG) android.util.Log.d(LM_TAG, msg())
}

private inline fun logW(msg: () -> String) {
    if (BuildConfig.DEBUG) android.util.Log.w(LM_TAG, msg())
}

private inline fun logE(msg: () -> String, t: Throwable? = null) {
    if (BuildConfig.DEBUG) android.util.Log.e(LM_TAG, msg(), t)
}

class BlocksRepository(
    private val appContext: Context,
    private val blockDao: BlockDao,
    private val noteDao: NoteDao? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val linkDao: BlockLinkDao? = null, // 🔗 optionnel pour liens AUDIO↔TEXTE / VIDEO↔TEXTE
    private val listItemDao: ListItemDao,
    private val inlineLinkDao: InlineLinkDao,
    private val listItemLinkDao: ListItemLinkDao,
) {

    private val snapshotGson by lazy { Gson() }
    private val roomDatabase: RoomDatabase? by lazy { resolveRoomDatabase() }
    internal val hasListWrite: Boolean = true
    private val migrationPrefs by lazy {
        appContext.getSharedPreferences(MIGRATION_PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val migrationScope = CoroutineScope(SupervisorJob() + io)

    init {
        Log.d(LIST_REPO_LOG_TAG, "INIT list write support = true (dao wired)")
        ensureLegacyChildRefsMigrated()
    }

    private fun resolveRoomDatabase(): RoomDatabase? {
        val daos = listOf(blockDao, listItemDao, noteDao, linkDao, inlineLinkDao, listItemLinkDao)
        for (dao in daos) {
            if (dao == null) continue
            val db = runCatching {
                val field = dao.javaClass.getDeclaredField("__db")
                field.isAccessible = true
                field.get(dao) as? RoomDatabase
            }.getOrNull()
            if (db != null) return db
        }
        return null
    }

    private suspend fun <T> runInRoomTransaction(block: suspend () -> T): T {
        val database = roomDatabase
        return if (database != null) {
            database.withTransaction { block() }
        } else {
            block()
        }
    }

    private fun normalizedLinkPair(firstBlockId: Long, secondBlockId: Long): Pair<Long, Long>? {
        if (firstBlockId == secondBlockId) return null
        return if (firstBlockId < secondBlockId) {
            firstBlockId to secondBlockId
        } else {
            secondBlockId to firstBlockId
        }
    }

    private suspend fun ensureBlockLinkExists(firstBlockId: Long, secondBlockId: Long): Boolean {
        logD { "graph.in: a=$firstBlockId b=$secondBlockId" }
        val dao = linkDao ?: return false
        val pair = normalizedLinkPair(firstBlockId, secondBlockId) ?: return false
        val (aId, bId) = pair
        val firstExists = blockDao.getById(aId) != null
        val secondExists = blockDao.getById(bId) != null
        if (!firstExists || !secondExists) {
            logW { "graph.reject.missingBlock" }
            return false
        }
        val insertedId = dao.insert(
            BlockLinkEntity(
                aBlockId = aId,
                bBlockId = bId,
            )
        )
        return if (insertedId != -1L) {
            logD { "graph.inserted" }
            true
        } else {
            logD { "graph.exists" }
            false
        }
    }

    private fun ensureLegacyChildRefsMigrated() {
        if (linkDao == null) return
        if (migrationPrefs.getBoolean(PREF_KEY_CHILD_REF_MIGRATED, false)) return
        migrationScope.launch {
            migrateLegacyChildRefsToLinks()
        }
    }

    private suspend fun migrateLegacyChildRefsToLinks() {
        withContext(io) {
            val legacyBlocks = blockDao.findAllWithChildRef()
            if (legacyBlocks.isEmpty()) {
                markLegacyMigrationDone()
                return@withContext
            }

            for (block in legacyBlocks) {
                val targetId = block.childRefTargetId ?: continue
                linkBlocks(block.id, targetId)
                val now = System.currentTimeMillis()
                blockDao.update(block.copy(childRefTargetId = null, updatedAt = now))
            }

            markLegacyMigrationDone()
        }
    }

    private fun markLegacyMigrationDone() {
        migrationPrefs.edit().putBoolean(PREF_KEY_CHILD_REF_MIGRATED, true).apply()
    }

    companion object {
        const val MIME_TYPE_TEXT_BLOCK_LIST = "text/x-openeer-list"
        private const val BLOCK_LIST_LOG_TAG = "BlockListUI"
        private const val LIST_REPO_LOG_TAG = "ListRepo"
        private const val EXTRA_KEY_TITLE = "title"
        private val DIACRITIC_REGEX = "\\p{Mn}+".toRegex()
        private const val MIGRATION_PREFS_NAME = "block_links_migrations"
        private const val PREF_KEY_CHILD_REF_MIGRATED = "child_ref_to_links_v1"
    }

    enum class AddEmptyReason {
        FLAG_DISABLED,
        NOTE_NOT_LIST_AND_CONVERSION_BLOCKED,
        NO_ITEMS_AFTER_NORMALIZATION,
        ALL_DUPLICATES,
        DB_ERROR,
        INTENT_ALREADY_APPLIED_TTL,
        NOTE_ID_MISMATCH,
    }

    data class AddItemsResult(val addedIds: List<Long>, val whyEmpty: AddEmptyReason? = null)

    data class TextBlockContent(
        val title: String,
        val body: String,
    ) {
        fun combinedText(): String = if (title.isBlank()) body else "$title\n\n$body".trimEnd()
    }

    data class ListApproxResult(
        val affectedItems: List<ListItemEntity>,
        val ambiguous: Boolean,
    )

    private data class TextBlockMetadata(val title: String) {
        fun toJson(): String = JSONObject().apply {
            put(EXTRA_KEY_TITLE, title)
        }.toString()

        companion object {
            fun from(json: String?): TextBlockMetadata? {
                if (json.isNullOrBlank()) return null
                return runCatching {
                    val obj = JSONObject(json)
                    val stored = obj.optString(EXTRA_KEY_TITLE, "").trim()
                    if (stored.isEmpty()) null else TextBlockMetadata(stored)
                }.getOrNull()
            }
        }
    }

    private fun decodeTitle(extra: String?, fallback: String = ""): String {
        val meta = TextBlockMetadata.from(extra)
        if (meta != null) {
            return meta.title
        }
        return fallback
    }

    private fun encodeTitle(title: String): String? {
        val sanitized = title.trim()
        return if (sanitized.isEmpty()) {
            null
        } else {
            TextBlockMetadata(sanitized).toJson()
        }
    }

    private fun splitCombinedText(raw: String): TextBlockContent {
        if (raw.isBlank()) return TextBlockContent(title = "", body = "")
        val parts = raw.split("\n\n", limit = 2)
        return if (parts.size == 2) {
            val title = parts[0].trim()
            val body = parts[1].trim()
            if (title.isNotEmpty()) {
                TextBlockContent(title, body)
            } else {
                TextBlockContent(title = "", body = raw.trim())
            }
        } else {
            TextBlockContent(title = "", body = raw.trim())
        }
    }

    fun extractTextContent(block: BlockEntity): TextBlockContent {
        val combined = block.text.orEmpty()
        val fallback = splitCombinedText(combined)
        val title = decodeTitle(block.extra, fallback.title).trim()
        val body = fallback.body
        return TextBlockContent(title = title, body = body)
    }

    private fun buildCombinedText(title: String, body: String): String {
        val trimmedBody = body.trim()
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return trimmedBody
        if (trimmedBody.isEmpty()) return trimmedTitle
        return "$trimmedTitle\n\n$trimmedBody"
    }

    sealed interface BlockConversionResult {
        data class Converted(val itemCount: Int) : BlockConversionResult
        object AlreadyTarget : BlockConversionResult
        object NotFound : BlockConversionResult
        object Unsupported : BlockConversionResult
        object EmptySource : BlockConversionResult
        object Incomplete : BlockConversionResult
    }
    suspend fun updateLocationLabel(blockId: Long, newPlaceName: String) {
        withContext(io) {
            val current = blockDao.getById(blockId) ?: return@withContext
            val now = System.currentTimeMillis()
            if (current.type == BlockType.LOCATION) {
                blockDao.update(current.copy(placeName = newPlaceName, updatedAt = now))
            }
        }
    }

    suspend fun updateNoteBody(noteId: Long, body: String) {
        withContext(io) {
            val dao = noteDao
            if (dao == null) {
                Log.w(
                    BLOCK_LIST_LOG_TAG,
                    "updateNoteBody appelé sans noteDao disponible (noteId=$noteId)",
                )
                return@withContext
            }
            val now = System.currentTimeMillis()
            dao.updateBody(noteId, body, now)
        }
    }

    suspend fun getNoteBody(noteId: Long): String? = withContext(io) {
        noteDao?.getByIdOnce(noteId)?.body
    }
    fun observeBlocks(noteId: Long): Flow<List<BlockEntity>> = blockDao.observeBlocks(noteId)

    suspend fun getBlock(blockId: Long): BlockEntity? = withContext(io) {
        blockDao.getById(blockId)
    }

    suspend fun getChildNameForBlock(blockId: Long): String? = withContext(io) {
        blockDao.getChildName(blockId)
    }

    private fun defaultChildName(ordinal: Int?): String? {
        val value = ordinal ?: return null
        return if (value > 0) "#${value}" else null
    }

    suspend fun setChildNameForBlock(blockId: Long, name: String?) = withContext(io) {
        val block = blockDao.getById(blockId) ?: return@withContext
        val normalized = name?.trim()?.ifBlank { null }
        if (block.type == BlockType.TEXT) {
            val resolvedTitle = normalized ?: defaultChildName(block.childOrdinal)
            val extra = resolvedTitle?.let { encodeTitle(it) }
            val now = System.currentTimeMillis()
            blockDao.update(
                block.copy(
                    childName = resolvedTitle,
                    extra = extra,
                    updatedAt = now,
                )
            )
        } else {
            blockDao.updateChildName(blockId, normalized)
        }
    }

    /**
     * Réassigne TOUS les blocs d'une note source vers une note cible en les
     * append-ant à la fin de la cible (mise à jour atomique noteId+position).
     * Évite toute collision sur l'unicité (noteId, position).
     */
    suspend fun reassignBlocksToNote(sourceNoteId: Long, targetNoteId: Long) {
        withContext(io) {
            // Ordre stable : positions croissantes dans la source
            val idsInOrder = blockDao.getBlockIdsForNote(sourceNoteId)
            if (idsInOrder.isEmpty()) return@withContext

            var nextPos = (blockDao.getMaxPosition(targetNoteId) ?: -1) + 1
            var nextOrdinal = (blockDao.getMaxChildOrdinal(targetNoteId) ?: 0) + 1
            idsInOrder.forEach { bid ->
                blockDao.updateNoteIdPositionAndOrdinal(bid, targetNoteId, nextPos++, nextOrdinal++)
            }
        }
    }

    suspend fun getBlockIds(noteId: Long): List<Long> = withContext(io) {
        blockDao.getBlockIdsForNote(noteId)
    }

    /**
     * Réassigne une liste explicite d'IDs vers la cible en les ajoutant en fin.
     * Si l'ordre importe, fournis les IDs déjà triés. Sinon, on préservera l'ordre fourni.
     */
    suspend fun reassignBlocksByIds(blockIds: List<Long>, targetNoteId: Long) {
        if (blockIds.isEmpty()) return
        withContext(io) {
            var nextPos = (blockDao.getMaxPosition(targetNoteId) ?: -1) + 1
            var nextOrdinal = (blockDao.getMaxChildOrdinal(targetNoteId) ?: 0) + 1
            blockIds.forEach { bid ->
                blockDao.updateNoteIdPositionAndOrdinal(bid, targetNoteId, nextPos++, nextOrdinal++)
            }
        }
    }

    private suspend fun insert(noteId: Long, template: BlockEntity): Long =
        withContext(io) { blockDao.insertAtEnd(noteId, template) }

    suspend fun insertFromSnapshot(noteId: Long, snapshot: BlockSnapshot): Long {
        val block = snapshotGson.fromJson(snapshot.rawJson, BlockEntity::class.java)
        val sanitized = block.copy(id = 0L, noteId = noteId)
        return insert(noteId, sanitized)
    }

    suspend fun appendText(
        noteId: Long,
        text: String,
        groupId: String? = null,
        title: String = "",
    ): Long {
        val now = System.currentTimeMillis()
        val sanitizedTitle = title.trim()
        val explicitTitle = sanitizedTitle.ifEmpty { null }
        val extra = explicitTitle?.let { encodeTitle(it) }
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.TEXT,
            position = 0,
            groupId = groupId,
            text = text,
            extra = extra,
            childName = explicitTitle,
            createdAt = now,
            updatedAt = now
        )
        val blockId = insert(noteId, block)
        if (explicitTitle == null) {
            val inserted = blockDao.getById(blockId)
            if (inserted != null) {
                val autoTitle = defaultChildName(inserted.childOrdinal)
                if (!autoTitle.isNullOrBlank()) {
                    blockDao.update(
                        inserted.copy(
                            childName = autoTitle,
                            extra = encodeTitle(autoTitle),
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
        }
        return blockId
    }

    data class ChecklistItemDraft(
        val id: Long?,
        val text: String,
        val done: Boolean,
        val order: Int,
    )

    suspend fun convertTextBlockToList(
        blockId: Long,
        allowEmpty: Boolean = true,
    ): BlockConversionResult {
        val dao = listItemDao
        return withContext(io) {
            val block = blockDao.getById(blockId) ?: return@withContext BlockConversionResult.NotFound
            if (block.type != BlockType.TEXT) return@withContext BlockConversionResult.Unsupported
            if (block.mimeType == MIME_TYPE_TEXT_BLOCK_LIST) return@withContext BlockConversionResult.AlreadyTarget
            if (block.mimeType == "text/transcript") return@withContext BlockConversionResult.Unsupported

            // 1) On n’utilise QUE le body (le titre reste indépendant).
            val content = extractTextContent(block)
            val rawBody = content.body.trimEnd() // préserve les \n internes

            // 2) Laisse le splitter gérer les virgules/« et »/lignes → pas de normalisation préalable.
            val rawItems = SmartListSplitter.splitAllCandidates(rawBody)

            // 3) Nettoyage léger item par item (espaces multiples → un espace).
            val whitespaceRegex = "\\s+".toRegex()
            val itemsTexts = rawItems.map { it.replace(whitespaceRegex, " ").trim() }
                .filter { it.isNotEmpty() }

            val now = System.currentTimeMillis()

            // 4) Aucun item détecté
            if (itemsTexts.isEmpty()) {
                if (!allowEmpty) {
                    return@withContext BlockConversionResult.Incomplete
                }
                // Création d'une liste vide (titre conservé), suppression d'anciens items s'il y en avait.
                dao.deleteForBlock(blockId)
                blockDao.update(
                    block.copy(
                        mimeType = MIME_TYPE_TEXT_BLOCK_LIST,
                        text = buildCombinedText(content.title, ""),
                        extra = encodeTitle(content.title),
                        updatedAt = now,
                    )
                )
                return@withContext BlockConversionResult.Converted(0)
            }

            // 5) Construit les entités d’items
            val entities = itemsTexts.mapIndexed { index, textLine ->
                ListItemEntity(
                    noteId = null,
                    ownerBlockId = blockId,
                    text = textLine,
                    order = index,
                    createdAt = now + index,
                )
            }

            val updatedBody = itemsTexts.joinToString(separator = "\n")
            val updatedText = buildCombinedText(content.title, updatedBody)
            val extra = encodeTitle(content.title)

            suspend fun performConversion(): BlockConversionResult {
                dao.deleteForBlock(blockId)
                if (entities.isNotEmpty()) dao.insertAll(entities)
                blockDao.update(
                    block.copy(
                        mimeType = MIME_TYPE_TEXT_BLOCK_LIST,
                        text = updatedText,
                        extra = extra,
                        updatedAt = now,
                    )
                )
                return BlockConversionResult.Converted(entities.size)
            }

            val database = roomDatabase
            if (database != null) {
                database.withTransaction { performConversion() }
            } else {
                performConversion()
            }
        }
    }


    suspend fun convertListBlockToText(blockId: Long): BlockConversionResult {
        val dao = listItemDao
        return withContext(io) {
            val block = blockDao.getById(blockId) ?: return@withContext BlockConversionResult.NotFound
            if (block.type != BlockType.TEXT) {
                return@withContext BlockConversionResult.Unsupported
            }
            if (block.mimeType != MIME_TYPE_TEXT_BLOCK_LIST) {
                return@withContext BlockConversionResult.AlreadyTarget
            }

            val items = dao.listForBlock(blockId)
            val now = System.currentTimeMillis()
            val content = extractTextContent(block)

            if (items.isEmpty()) {
                dao.deleteForBlock(blockId)
                blockDao.update(
                    block.copy(
                        mimeType = null,
                        text = buildCombinedText(content.title, ""),
                        extra = encodeTitle(content.title),
                        updatedAt = now,
                    )
                )
                return@withContext BlockConversionResult.EmptySource
            }

            val body = items.joinToString(separator = "\n") { it.text }
            dao.deleteForBlock(blockId)
            blockDao.update(
                block.copy(
                    mimeType = null,
                    text = buildCombinedText(content.title, body),
                    extra = encodeTitle(content.title),
                    updatedAt = now,
                )
            )

            BlockConversionResult.Converted(items.size)
        }
    }

    suspend fun upsertChecklistItems(blockId: Long, drafts: List<ChecklistItemDraft>) {
        val dao = listItemDao
        withContext(io) {
            runInRoomTransaction {
                val block = blockDao.getById(blockId) ?: return@runInRoomTransaction
                if (block.type != BlockType.TEXT || block.mimeType != MIME_TYPE_TEXT_BLOCK_LIST) {
                    return@runInRoomTransaction
                }

                val existing = dao.listForBlock(blockId)
                val existingById = existing.associateBy { it.id }
                val retainedIds = mutableSetOf<Long>()

                drafts.forEach { draft ->
                    val trimmed = draft.text.trim()
                    if (trimmed.isEmpty()) {
                        return@forEach
                    }

                    val targetId = draft.id?.takeIf { it > 0 }
                    val current = targetId?.let { existingById[it] }

                    if (current == null) {
                        val entity = ListItemEntity(
                            noteId = null,
                            ownerBlockId = blockId,
                            text = trimmed,
                            done = draft.done,
                            order = draft.order,
                            provisional = false,
                        )
                        val newId = dao.insert(entity)
                        retainedIds.add(newId)
                    } else {
                        val itemId = current.id
                        retainedIds.add(itemId)

                        if (current.text != trimmed || current.provisional) {
                            if (current.provisional) {
                                dao.finalizeText(itemId, trimmed)
                            } else {
                                dao.updateText(itemId, trimmed)
                            }
                        }

                        if (current.done != draft.done) {
                            dao.updateDone(itemId, draft.done)
                        }

                        if (current.order != draft.order) {
                            dao.updateOrdering(itemId, draft.order)
                        }
                    }
                }

                val toDelete = existing.map { it.id }.filter { it !in retainedIds }
                if (toDelete.isNotEmpty()) {
                    dao.deleteMany(toDelete)
                }

                updateBlockTextFromItems(blockId)
            }
        }
    }

    fun observeItemsForBlock(blockId: Long): Flow<List<ListItemEntity>> =
        listItemDao.listForBlockFlow(blockId)

    suspend fun getItemsForBlock(blockId: Long): List<ListItemEntity> = withContext(io) {
        listItemDao.listForBlock(blockId)
    }

    suspend fun addItemForBlock(blockId: Long, text: String): Long {
        return withContext(io) {
            runInRoomTransaction {
                val dao = listItemDao
                val nextOrder = (dao.maxOrderForBlock(blockId) ?: -1) + 1
                val trimmed = text.trim()
                val isBlank = trimmed.isEmpty()
                val entity = ListItemEntity(
                    noteId = null,
                    ownerBlockId = blockId,
                    text = if (isBlank) "" else trimmed,
                    order = nextOrder,
                    createdAt = System.currentTimeMillis(),
                    provisional = isBlank,
                )
                val id = dao.insert(entity)
                Log.i(BLOCK_LIST_LOG_TAG, "add block=$blockId item=$id")
                updateBlockTextFromItems(blockId)
                id
            }
        }
    }

    suspend fun addItemsToNoteList(
        noteId: Long,
        requested: List<String>,
        reqId: String? = null,
    ): AddItemsResult {
        val requestToken = reqId ?: UUID.randomUUID().toString().take(8)
        Log.d(
            LIST_REPO_LOG_TAG,
            "REPO_ENTER req=$requestToken note=$noteId requested=${requested.joinToString(prefix = "[", postfix = "]")}",
        )
        if (!FeatureFlags.listsEnabled) {
            Log.d(
                LIST_REPO_LOG_TAG,
                "REPO_RESULT req=$requestToken added=0 whyEmpty=${AddEmptyReason.FLAG_DISABLED}",
            )
            return AddItemsResult(emptyList(), AddEmptyReason.FLAG_DISABLED)
        }

        val whitespaceRegex = "\\s+".toRegex()
        val normalizedItems = mutableListOf<String>()
        val rejectedItems = mutableListOf<String>()
        requested.forEach { candidate ->
            val parts = SmartListSplitter.splitAllCandidates(candidate).ifEmpty { listOf(candidate) }
            parts.forEach { part ->
                val sanitized = part.replace(whitespaceRegex, " ").trim()
                if (sanitized.isEmpty()) {
                    rejectedItems += part.trim()
                } else {
                    normalizedItems += sanitized
                }
            }
        }

        Log.d(
            LIST_REPO_LOG_TAG,
            "REPO_SANITIZE req=$requestToken itemsNorm=${normalizedItems.joinToString(prefix = "[", postfix = "]") { "'${it}'" }} rejected=${rejectedItems.joinToString(prefix = "[", postfix = "]") { if (it.isEmpty()) "''" else "'${it}'" }}",
        )

        if (normalizedItems.isEmpty()) {
            Log.d(
                LIST_REPO_LOG_TAG,
                "REPO_RESULT req=$requestToken added=0 whyEmpty=${AddEmptyReason.NO_ITEMS_AFTER_NORMALIZATION}",
            )
            return AddItemsResult(emptyList(), AddEmptyReason.NO_ITEMS_AFTER_NORMALIZATION)
        }

        val note = noteDao?.getByIdOnce(noteId)
        val noteTypeToken = note?.type?.name ?: "UNKNOWN"
        val canConvert = note != null && note.type != NoteType.LIST && noteDao != null
        Log.d(
            LIST_REPO_LOG_TAG,
            "REPO_NOTE_STATE req=$requestToken type=$noteTypeToken canConvert=$canConvert",
        )

        if (note == null) {
            Log.d(
                LIST_REPO_LOG_TAG,
                "REPO_RESULT req=$requestToken added=0 whyEmpty=${AddEmptyReason.NOTE_ID_MISMATCH}",
            )
            return AddItemsResult(emptyList(), AddEmptyReason.NOTE_ID_MISMATCH)
        }

        return withContext(io) {
            try {
                Log.d(LIST_REPO_LOG_TAG, "REPO_DB_TX_START req=$requestToken")
                val (insertedIds, duplicatesOnly) = runInRoomTransaction {
                    val dao = listItemDao
                    val existing = dao.listForNote(noteId)
                    normalizedItems.forEach { candidate ->
                        val existsExact = existing.any { it.text == candidate }
                        val normalizedTarget = normalizeListText(candidate)
                        val approxMatch = !existsExact && existing.any {
                            normalizeListText(it.text) == normalizedTarget
                        }
                        Log.d(
                            LIST_REPO_LOG_TAG,
                            "REPO_EXISTING_MATCH req=$requestToken item='${candidate}' exists=$existsExact approx=$approxMatch",
                        )
                    }

                    val now = System.currentTimeMillis()
                    var order = (dao.maxOrderForNote(noteId) ?: -1) + 1
                    val ids = mutableListOf<Long>()
                    normalizedItems.forEachIndexed { index, textLine ->
                        val entity = ListItemEntity(
                            noteId = noteId,
                            text = textLine,
                            order = order++,
                            createdAt = now + index,
                        )
                        val id = dao.insert(entity, requestToken)
                        ids += id
                    }
                    val duplicatesOnly = normalizedItems.isNotEmpty() && normalizedItems.all { candidate ->
                        existing.any { it.text == candidate }
                    }
                    ids.toList() to duplicatesOnly
                }
                Log.d(LIST_REPO_LOG_TAG, "REPO_DB_TX_END req=$requestToken")
                if (insertedIds.isEmpty()) {
                    Log.d(
                        LIST_REPO_LOG_TAG,
                        "REPO_RESULT req=$requestToken added=0 whyEmpty=${if (duplicatesOnly) AddEmptyReason.ALL_DUPLICATES else AddEmptyReason.DB_ERROR}",
                    )
                    val reason = if (duplicatesOnly) AddEmptyReason.ALL_DUPLICATES else AddEmptyReason.DB_ERROR
                    AddItemsResult(emptyList(), reason)
                } else {
                    Log.d(
                        LIST_REPO_LOG_TAG,
                        "REPO_RESULT req=$requestToken added=${insertedIds.size} ids=$insertedIds",
                    )
                    AddItemsResult(insertedIds)
                }
            } catch (error: Throwable) {
                Log.e(
                    LIST_REPO_LOG_TAG,
                    "REPO_DB_TX_END req=$requestToken error=${error.message}",
                    error,
                )
                Log.d(
                    LIST_REPO_LOG_TAG,
                    "REPO_RESULT req=$requestToken added=0 whyEmpty=${AddEmptyReason.DB_ERROR}",
                )
                throw error
            }
        }
    }

    suspend fun getListItemsByIds(itemIds: List<Long>): List<ListItemEntity> {
        if (itemIds.isEmpty()) return emptyList()
        return withContext(io) {
            runInRoomTransaction {
                val dao = listItemDao
                itemIds.mapNotNull { id -> dao.findById(id) }
            }
        }
    }

    suspend fun removeListItems(noteId: Long, itemIds: List<Long>): List<Long> {
        if (itemIds.isEmpty()) return emptyList()
        return withContext(io) {
            runInRoomTransaction {
                val dao = listItemDao
                val existing = itemIds.mapNotNull { id ->
                    val entity = dao.findById(id)
                    if (entity?.noteId == noteId) entity.id else null
                }
                if (existing.isNotEmpty()) {
                    dao.deleteMany(existing)
                }
                existing
            }
        }
    }

    suspend fun removeItemsByApprox(
        noteId: Long,
        query: String,
        reqId: String? = null,
    ): ListApproxResult {
        val requestToken = reqId ?: UUID.randomUUID().toString().take(8)
        val sanitizedQuery = query.replace("\n", " ").replace("\r", " ")
        Log.d(
            LIST_REPO_LOG_TAG,
            "REPO_ENTER req=$requestToken note=$noteId requested='$sanitizedQuery'"
        )
        return withContext(io) {
            runInRoomTransaction {
                val dao = listItemDao
                val normalizedQuery = normalizeListText(query)
                Log.d(
                    LIST_REPO_LOG_TAG,
                    "REPO_SANITIZE req=$requestToken itemsNorm=['$normalizedQuery'] rejected=[]"
                )
                if (normalizedQuery.isEmpty()) {
                    Log.d(
                        LIST_REPO_LOG_TAG,
                        "REPO_RESULT req=$requestToken affected=0 ambiguous=false whyEmpty=NORMALIZED_EMPTY"
                    )
                    return@runInRoomTransaction ListApproxResult(emptyList(), ambiguous = false)
                }

                val items = dao.listForNote(noteId).filterNot { it.provisional }
                val matches = items.filter { normalizeListText(it.text).contains(normalizedQuery) }
                Log.d(
                    LIST_REPO_LOG_TAG,
                    "REPO_MATCH req=$requestToken candidates=${matches.map { it.id }} size=${matches.size}"
                )
                if (matches.isEmpty()) {
                    Log.d(
                        LIST_REPO_LOG_TAG,
                        "REPO_RESULT req=$requestToken affected=0 ambiguous=false whyEmpty=NOT_FOUND"
                    )
                    return@runInRoomTransaction ListApproxResult(emptyList(), ambiguous = false)
                }

                val longestLength = matches.maxOf { it.text.length }
                val longest = matches.filter { it.text.length == longestLength }
                if (longest.size != 1) {
                    Log.d(
                        LIST_REPO_LOG_TAG,
                        "REPO_RESULT req=$requestToken affected=0 ambiguous=true whyEmpty=AMBIGUOUS"
                    )
                    return@runInRoomTransaction ListApproxResult(emptyList(), ambiguous = true)
                }

                val target = longest.first()
                dao.delete(target.id)
                Log.d(
                    LIST_REPO_LOG_TAG,
                    "REPO_RESULT req=$requestToken affected=1 ambiguous=false ids=[${target.id}]"
                )
                ListApproxResult(listOf(target), ambiguous = false)
            }
        }
    }

    suspend fun toggleItemsByApprox(
        noteId: Long,
        query: String,
        done: Boolean,
        reqId: String? = null,
    ): ListApproxResult {
        val requestToken = reqId ?: UUID.randomUUID().toString().take(8)
        val sanitizedQuery = query.replace("\n", " ").replace("\r", " ")
        Log.d(
            LIST_REPO_LOG_TAG,
            "REPO_ENTER req=$requestToken note=$noteId requested='$sanitizedQuery'"
        )
        return withContext(io) {
            runInRoomTransaction {
                val dao = listItemDao
                val normalizedQuery = normalizeListText(query)
                Log.d(
                    LIST_REPO_LOG_TAG,
                    "REPO_SANITIZE req=$requestToken itemsNorm=['$normalizedQuery'] rejected=[]"
                )
                if (normalizedQuery.isEmpty()) {
                    Log.d(
                        LIST_REPO_LOG_TAG,
                        "REPO_RESULT req=$requestToken affected=0 ambiguous=false whyEmpty=NORMALIZED_EMPTY"
                    )
                    return@runInRoomTransaction ListApproxResult(emptyList(), ambiguous = false)
                }

                val items = dao.listForNote(noteId).filterNot { it.provisional }
                val matches = items.filter { normalizeListText(it.text).contains(normalizedQuery) }
                Log.d(
                    LIST_REPO_LOG_TAG,
                    "REPO_MATCH req=$requestToken candidates=${matches.map { it.id }} size=${matches.size}"
                )
                if (matches.isEmpty()) {
                    Log.d(
                        LIST_REPO_LOG_TAG,
                        "REPO_RESULT req=$requestToken affected=0 ambiguous=false whyEmpty=NOT_FOUND"
                    )
                    return@runInRoomTransaction ListApproxResult(emptyList(), ambiguous = false)
                }

                val longestLength = matches.maxOf { it.text.length }
                val longest = matches.filter { it.text.length == longestLength }
                if (longest.size != 1) {
                    Log.d(
                        LIST_REPO_LOG_TAG,
                        "REPO_RESULT req=$requestToken affected=0 ambiguous=true whyEmpty=AMBIGUOUS"
                    )
                    return@runInRoomTransaction ListApproxResult(emptyList(), ambiguous = true)
                }

                val target = longest.first()
                if (target.done != done) {
                    dao.updateDone(target.id, done)
                }
                Log.d(
                    LIST_REPO_LOG_TAG,
                    "REPO_RESULT req=$requestToken affected=1 ambiguous=false ids=[${target.id}]"
                )
                ListApproxResult(listOf(target.copy(done = done)), ambiguous = false)
            }
        }
    }

    private fun normalizeListText(input: String): String {
        if (input.isBlank()) return ""
        val normalized = Normalizer.normalize(input.lowercase(Locale.FRENCH), Normalizer.Form.NFD)
        return DIACRITIC_REGEX.replace(normalized, "").trim()
    }

    suspend fun updateItemTextForBlock(itemId: Long, text: String) {
        withContext(io) {
            runInRoomTransaction {
                val dao = listItemDao
                val current = dao.findById(itemId) ?: return@runInRoomTransaction
                val blockId = current.ownerBlockId ?: return@runInRoomTransaction
                val sanitized = text.trim()
                if (sanitized.isEmpty()) {
                    dao.delete(itemId)
                    Log.i(BLOCK_LIST_LOG_TAG, "remove block=$blockId item=$itemId")
                    updateBlockTextFromItems(blockId)
                    return@runInRoomTransaction
                }

                val changed = current.provisional || current.text != sanitized
                if (!changed) {
                    return@runInRoomTransaction
                }

                if (current.provisional) {
                    dao.finalizeText(itemId, sanitized)
                } else {
                    dao.updateText(itemId, sanitized)
                }
                Log.i(BLOCK_LIST_LOG_TAG, "edit block=$blockId item=$itemId")
                updateBlockTextFromItems(blockId)
            }
        }
    }

    suspend fun toggleItemForBlock(itemId: Long) {
        withContext(io) {
            runInRoomTransaction {
                val dao = listItemDao
                val current = dao.findById(itemId) ?: return@runInRoomTransaction
                val blockId = current.ownerBlockId ?: return@runInRoomTransaction
                val newValue = !current.done
                if (newValue == current.done) {
                    return@runInRoomTransaction
                }
                dao.updateDone(itemId, newValue)
                Log.i(BLOCK_LIST_LOG_TAG, "toggle block=$blockId item=$itemId")
            }
        }
    }

    suspend fun removeItemForBlock(itemId: Long) {
        withContext(io) {
            runInRoomTransaction {
                val dao = listItemDao
                val current = dao.findById(itemId) ?: return@runInRoomTransaction
                val blockId = current.ownerBlockId ?: return@runInRoomTransaction
                dao.delete(itemId)
                Log.i(BLOCK_LIST_LOG_TAG, "remove block=$blockId item=$itemId")
                updateBlockTextFromItems(blockId)
            }
        }
    }

    private suspend fun updateBlockTextFromItems(blockId: Long) {
        val block = blockDao.getById(blockId) ?: return
        if (block.type != BlockType.TEXT || block.mimeType != MIME_TYPE_TEXT_BLOCK_LIST) {
            return
        }
        val items = listItemDao.listForBlock(blockId)
        val content = extractTextContent(block)
        val joined = items.joinToString(separator = "\n") { it.text }
        blockDao.update(
            block.copy(
                text = buildCombinedText(content.title, joined),
                extra = encodeTitle(content.title),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** Crée explicitement une “note-fille” texte (transcription immuable et indexable). */
    suspend fun createTextChild(noteId: Long, text: String): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.TEXT,
            position = 0,
            groupId = null, // indépendante (pile TEXT)
            text = text,
            mimeType = "text/transcript",
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun createTextBlock(noteId: Long): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.TEXT,
            position = 0,
            text = "",
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun appendPhoto(
        noteId: Long,
        mediaUri: String,
        width: Int? = null,
        height: Int? = null,
        mimeType: String? = "image/*",
        groupId: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.PHOTO,
            position = 0,
            groupId = groupId,
            mediaUri = mediaUri,
            mimeType = mimeType,
            width = width,
            height = height,
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    /** --- VIDEO --- */
    suspend fun appendVideo(
        noteId: Long,
        mediaUri: String,
        mimeType: String? = "video/*",
        durationMs: Long? = null,
        width: Int? = null,
        height: Int? = null,
        groupId: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.VIDEO,
            position = 0,
            groupId = groupId,
            mediaUri = mediaUri,
            mimeType = mimeType,
            durationMs = durationMs,
            width = width,
            height = height,
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun appendAudio(
        noteId: Long,
        mediaUri: String,
        durationMs: Long?,
        mimeType: String? = "audio/*",
        groupId: String = generateGroupId(),
        transcription: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.AUDIO,
            position = 0,
            groupId = groupId,
            mediaUri = mediaUri,
            mimeType = mimeType,
            durationMs = durationMs,
            text = transcription, // Vosk initial si présent
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    /** Met à jour le texte du bloc AUDIO (ex: affinage Whisper) — n’affecte pas les blocs TEXTE. */
    suspend fun updateAudioTranscription(blockId: Long, newText: String) {
        withContext(io) {
            val audioBlock = blockDao.getById(blockId) ?: return@withContext
            val now = System.currentTimeMillis()
            blockDao.update(audioBlock.copy(text = newText, updatedAt = now))
        }
    }

    suspend fun appendTranscription(
        noteId: Long,
        text: String,
        groupId: String
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.TEXT,
            position = 0,
            groupId = groupId,
            text = text,
            mimeType = "text/transcript",
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun appendLocation(
        noteId: Long,
        lat: Double,
        lon: Double,
        placeName: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.LOCATION,
            position = 0,
            lat = lat,
            lon = lon,
            placeName = placeName,
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun appendRoute(
        noteId: Long,
        routeJson: String,
        lat: Double? = null,
        lon: Double? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.ROUTE,
            position = 0,
            lat = lat,
            lon = lon,
            routeJson = routeJson,
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun reorder(noteId: Long, orderedBlockIds: List<Long>) {
        withContext(io) {
            blockDao.reorder(noteId, orderedBlockIds)
        }
    }

    suspend fun updateText(blockId: Long, text: String, title: String? = null) {
        withContext(io) {
            val current = blockDao.getById(blockId) ?: return@withContext
            val now = System.currentTimeMillis()
            if (title == null) {
                blockDao.update(current.copy(text = text, updatedAt = now))
                return@withContext
            }

            val sanitizedTitle = title.trim()
            val resolvedTitle = if (sanitizedTitle.isEmpty()) {
                defaultChildName(current.childOrdinal)
            } else {
                sanitizedTitle
            }
            val extra = resolvedTitle?.let { encodeTitle(it) }
            blockDao.update(
                current.copy(
                    text = text,
                    extra = extra,
                    childName = resolvedTitle,
                    updatedAt = now,
                )
            )
        }
    }

    /** --- CROQUIS / DESSIN --- */

    suspend fun appendSketchImage(
        noteId: Long,
        mediaUri: String,
        width: Int? = null,
        height: Int? = null,
        mimeType: String = "image/png",
        groupId: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.SKETCH,
            position = 0,
            groupId = groupId,
            mediaUri = mediaUri,
            mimeType = mimeType,
            width = width,
            height = height,
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun createSketchBlock(
        noteId: Long,
        mediaUri: String,
        width: Int? = null,
        height: Int? = null,
        mimeType: String = "image/png"
    ): Long = appendSketchImage(noteId, mediaUri, width, height, mimeType)

    @Deprecated("Use appendSketchImage() or appendSketchVector() explicitly")
    suspend fun appendSketch(
        noteId: Long,
        mediaUri: String,
        width: Int? = null,
        height: Int? = null,
        mimeType: String = "image/png",
        groupId: String? = null
    ): Long = appendSketchImage(noteId, mediaUri, width, height, mimeType, groupId)

    suspend fun appendSketchVector(
        noteId: Long,
        strokesJson: String,
        groupId: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.SKETCH,
            position = 0,
            groupId = groupId,
            mediaUri = null,
            mimeType = "application/json",
            width = null,
            height = null,
            extra = strokesJson,
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun appendFile(
        noteId: Long,
        fileUri: String,
        displayName: String? = null,
        mimeType: String? = null,
        groupId: String? = null,
        extra: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val block = BlockEntity(
            noteId = noteId,
            type = BlockType.FILE,
            position = 0,
            groupId = groupId,
            text = displayName,
            mediaUri = fileUri,
            mimeType = mimeType,
            extra = extra,
            createdAt = now,
            updatedAt = now
        )
        return insert(noteId, block)
    }

    suspend fun updateSketchVector(
        blockId: Long,
        strokesJson: String
    ) {
        withContext(io) {
            val current = blockDao.getById(blockId) ?: return@withContext
            val now = System.currentTimeMillis()
            if (current.type == BlockType.SKETCH) {
                blockDao.update(
                    current.copy(
                        extra = strokesJson,
                        mimeType = "application/json",
                        updatedAt = now
                    )
                )
            }
        }
    }

    suspend fun ensureNoteWithInitialText(initial: String = ""): Long {
        val dao = noteDao ?: throw IllegalStateException("noteDao required")
        val noteId = withContext(io) { dao.insert(Note()) }
        if (initial.isNotEmpty()) {
            appendText(noteId, initial)
        }
        return noteId
    }

    suspend fun deleteBlock(blockId: Long) {
        withContext(io) {
            val current = blockDao.getById(blockId) ?: return@withContext
            blockDao.delete(current)
        }
    }

    // --------------------------------------------------------------------
    // 🔗 Liens AUDIO -> TEXTE
    // --------------------------------------------------------------------

    suspend fun linkAudioToText(audioBlockId: Long, textBlockId: Long) {
        val dao = linkDao ?: error("BlockLinkDao not provided to BlocksRepository")
        val pair = normalizedLinkPair(audioBlockId, textBlockId) ?: return
        val (firstId, secondId) = pair
        withContext(io) {
            dao.insert(
                BlockLinkEntity(
                    aBlockId = firstId,
                    bBlockId = secondId
                )
            )
        }
    }

    suspend fun findTextForAudio(audioBlockId: Long): Long? {
        val dao = linkDao ?: error("BlockLinkDao not provided to BlocksRepository")
        return withContext(io) { dao.findLinkedBlockIdOfType(audioBlockId, BlockType.TEXT) }
    }

    /** Inverse : retrouver l’AUDIO lié à un bloc TEXTE (fallback groupId si pas de table de liens). */
    suspend fun findAudioForText(textBlockId: Long): Long? {
        // 1) Via table de liens (meilleur chemin)
        linkDao?.let { dao ->
            val viaGraph = withContext(io) { dao.findLinkedBlockIdOfType(textBlockId, BlockType.AUDIO) }
            if (viaGraph != null) return viaGraph
        }

        // 2) Fallback via groupId (pas besoin d'un “getAllForNote”)
        return withContext(io) {
            val textBlock = blockDao.getById(textBlockId) ?: return@withContext null
            val gid = textBlock.groupId ?: return@withContext null
            blockDao.findOneByNoteGroupAndType(
                noteId = textBlock.noteId,
                groupId = gid,
                type = BlockType.AUDIO
            )?.id
        }
    }

    // --------------------------------------------------------------------
    // 🔗 Liens VIDEO -> TEXTE
    // --------------------------------------------------------------------

    suspend fun linkVideoToText(videoBlockId: Long, textBlockId: Long) {
        val dao = linkDao ?: error("BlockLinkDao not provided to BlocksRepository")
        val pair = normalizedLinkPair(videoBlockId, textBlockId) ?: return
        val (firstId, secondId) = pair
        withContext(io) {
            dao.insert(
                BlockLinkEntity(
                    aBlockId = firstId,
                    bBlockId = secondId
                )
            )
        }
    }

    /** Trouve l’ID du texte lié à une vidéo (table de liens, sinon fallback groupId partagé). */
    suspend fun findTextForVideo(videoBlockId: Long): Long? {
        linkDao?.let { dao ->
            val viaLink = withContext(io) { dao.findLinkedBlockIdOfType(videoBlockId, BlockType.TEXT) }
            if (viaLink != null) return viaLink
        }
        return withContext(io) {
            val video = blockDao.getById(videoBlockId) ?: return@withContext null
            val gid = video.groupId ?: return@withContext null
            blockDao.findOneByNoteGroupAndType(
                noteId = video.noteId,
                groupId = gid,
                type = BlockType.TEXT
            )?.id
        }
    }

    /** Retrouve la vidéo liée à un texte (table de liens, sinon fallback groupId partagé). */
    suspend fun findVideoForText(textBlockId: Long): Long? {
        linkDao?.let { dao ->
            val viaLink = withContext(io) { dao.findLinkedBlockIdOfType(textBlockId, BlockType.VIDEO) }
            if (viaLink != null) return viaLink
        }
        return withContext(io) {
            val text = blockDao.getById(textBlockId) ?: return@withContext null
            val gid = text.groupId ?: return@withContext null
            blockDao.findOneByNoteGroupAndType(
                noteId = text.noteId,
                groupId = gid,
                type = BlockType.VIDEO
            )?.id
        }
    }

    /** Helper pratique si tu veux filtrer côté UI les textes “liés à une vidéo”. */
    suspend fun isTextLinkedToVideo(textBlockId: Long): Boolean {
        return findVideoForText(textBlockId) != null
    }

    /** Met à jour le texte du bloc VIDEO (ex: transcription liée à la vidéo). */
    suspend fun updateVideoTranscription(blockId: Long, newText: String) {
        withContext(io) {
            val videoBlock = blockDao.getById(blockId) ?: return@withContext
            val now = System.currentTimeMillis()
            blockDao.update(videoBlock.copy(text = newText, updatedAt = now))
        }
    }
    // --------------------------------------------------------------------
// 🔗 Liens génériques de blocs (ex: NOTE_MERE -> NOTE_FILLE)
// --------------------------------------------------------------------

    /**
     * Crée un lien générique non orienté entre deux blocs.
     * Ne lance pas la création si linkDao n'est pas fourni.
     */
    suspend fun linkBlocks(aId: Long, bId: Long): Boolean {
        return withContext(io) { ensureBlockLinkExists(aId, bId) }
    }

    suspend fun unlinkBlocks(aId: Long, bId: Long): Boolean {
        val dao = linkDao ?: return false
        val pair = normalizedLinkPair(aId, bId) ?: return false
        return withContext(io) {
            val (firstId, secondId) = pair
            dao.deletePair(firstId, secondId) > 0
        }
    }

    suspend fun unlinkAll(blockId: Long): Boolean {
        val dao = linkDao ?: return false
        return withContext(io) { dao.deleteLinksTouching(blockId) > 0 }
    }

    suspend fun getLinkedBlocks(blockId: Long): List<Block> {
        val dao = linkDao ?: return emptyList()
        return withContext(io) {
            val linkedIds = dao.findAllLinkedBlockIds(blockId)
            if (linkedIds.isEmpty()) return@withContext emptyList()

            val result = mutableListOf<Block>()
            for (otherId in linkedIds) {
                val block = blockDao.getById(otherId)
                if (block != null) {
                    result += block
                } else {
                    val pair = normalizedLinkPair(blockId, otherId) ?: continue
                    dao.deletePair(pair.first, pair.second)
                }
            }
            result
        }
    }

    suspend fun getLinkCount(blockId: Long): Int {
        val dao = linkDao ?: return 0
        return withContext(io) { dao.countLinks(blockId) }
    }

    /**
     * Trouve (au plus un) bloc lié à `sourceBlockId`.
     * Si `expectedType` est fourni, restreint au type visé.
     */
    suspend fun findLinkedTarget(
        sourceBlockId: Long,
        expectedType: BlockType? = null
    ): Long? {
        val dao = linkDao ?: return null
        return withContext(io) {
            if (expectedType != null) {
                dao.findLinkedBlockIdOfType(sourceBlockId, expectedType)
            } else {
                dao.findAnyLinkedBlockId(sourceBlockId)
            }
        }
    }

    /**
     * Trouve (au plus un) bloc lié à `targetBlockId`.
     * Identique à [findLinkedTarget] car le graphe est non orienté.
     */
    suspend fun findLinkedSource(
        targetBlockId: Long,
        expectedType: BlockType? = null
    ): Long? {
        val dao = linkDao ?: return null
        return withContext(io) {
            if (expectedType != null) {
                dao.findLinkedBlockIdOfType(targetBlockId, expectedType)
            } else {
                dao.findAnyLinkedBlockId(targetBlockId)
            }
        }
    }

    // --------------------------------------------------------------------
    // 🔗 Ancres inline (texte de note “mère”)
    // --------------------------------------------------------------------

    suspend fun createInlineLink(
        hostBlockId: Long,
        start: Int,
        end: Int,
        targetBlockId: Long,
    ): Boolean {
        logD { "inline.in: host=$hostBlockId target=$targetBlockId span=${end - start}" }
        if (start < 0 || end < 0 || start >= end) {
            logW { "inline.reject.badSpan start=$start end=$end" }
            return false
        }
        if (hostBlockId == targetBlockId) {
            logW { "inline.reject.sameIds" }
            return false
        }
        return withContext(io) {
            runInRoomTransaction {
                val host = blockDao.getById(hostBlockId) ?: run {
                    logW { "inline.reject.hostInvalid" }
                    return@runInRoomTransaction false
                }
                if (host.type != BlockType.TEXT) {
                    logW { "inline.reject.hostInvalid" }
                    return@runInRoomTransaction false
                }
                if (blockDao.getById(targetBlockId) == null) {
                    logW { "inline.reject.targetMissing" }
                    return@runInRoomTransaction false
                }
                ensureBlockLinkExists(hostBlockId, targetBlockId)
                val inserted = inlineLinkDao.insertOrIgnore(
                    InlineLinkEntity(
                        hostBlockId = hostBlockId,
                        start = start,
                        end = end,
                        targetBlockId = targetBlockId,
                    )
                )
                val created = inserted != -1L
                logD { "inline.out: created=$created" }
                created
            }
        }
    }

    suspend fun removeInlineLinkById(id: Long): Boolean =
        withContext(io) { inlineLinkDao.deleteById(id) > 0 }

    suspend fun removeInlineLink(
        hostBlockId: Long,
        start: Int,
        end: Int,
        targetBlockId: Long,
    ): Boolean = withContext(io) {
        inlineLinkDao.deleteByHostRangeTarget(hostBlockId, start, end, targetBlockId) > 0
    }

    suspend fun getInlineLinks(hostBlockId: Long): List<InlineLinkEntity> =
        withContext(io) { inlineLinkDao.selectAllForHost(hostBlockId) }

    // --------------------------------------------------------------------
    // ✅ Liens d’items de liste
    // --------------------------------------------------------------------

    suspend fun createListItemLink(listItemId: Long, targetBlockId: Long): Boolean {
        return withContext(io) {
            runInRoomTransaction {
                val item = listItemDao.findById(listItemId) ?: return@runInRoomTransaction false
                val hostBlockId = item.ownerBlockId ?: return@runInRoomTransaction false
                val hostBlock = blockDao.getById(hostBlockId) ?: return@runInRoomTransaction false
                if (hostBlock.type != BlockType.TEXT) {
                    return@runInRoomTransaction false
                }
                if (blockDao.getById(targetBlockId) == null) {
                    return@runInRoomTransaction false
                }
                ensureBlockLinkExists(hostBlockId, targetBlockId)
                val inserted = listItemLinkDao.insertOrIgnore(
                    ListItemLinkEntity(
                        listItemId = listItemId,
                        targetBlockId = targetBlockId,
                    )
                )
                inserted != -1L
            }
        }
    }

    suspend fun removeListItemLink(listItemId: Long, targetBlockId: Long): Boolean =
        withContext(io) { listItemLinkDao.deleteByPair(listItemId, targetBlockId) > 0 }

    suspend fun getLinksForListItem(listItemId: Long): List<ListItemLinkEntity> =
        withContext(io) { listItemLinkDao.selectAllForItem(listItemId) }

    // --------------------------------------------------------------------
    // 🧰 Utilitaires pour l’UI (prompts suivants)
    // --------------------------------------------------------------------

    suspend fun ensureCanonicalMotherTextBlock(noteId: Long): Long {
        logD { "ensureHost: noteId=$noteId" }
        return withContext(io) {
            runInRoomTransaction {
                val existing = blockDao.findFirstRootTextBlock(noteId)
                if (existing != null) {
                    logD { "ensureHost.exists: id=${existing.id}" }
                    return@runInRoomTransaction existing.id
                }

                val now = System.currentTimeMillis()
                val template = BlockEntity(
                    noteId = noteId,
                    type = BlockType.TEXT,
                    position = 0,
                    text = "",
                    createdAt = now,
                    updatedAt = now,
                )

                val newId = blockDao.insertAtEnd(noteId, template)
                val orderedIds = blockDao.getBlockIdsForNote(noteId)
                val reordered = buildList {
                    add(newId)
                    orderedIds.filterNot { it == newId }.forEach { add(it) }
                }
                if (reordered.isNotEmpty()) {
                    blockDao.reorder(noteId, reordered)
                }
                logD { "ensureHost.created: id=$newId" }
                newId
            }
        }
    }

    suspend fun appendLinkedLine(
        hostTextBlockId: Long,
        label: String,
        targetBlockId: Long,
    ): Pair<Int, Int> {
        logD { "append.in: host=$hostTextBlockId target=$targetBlockId labelLen=${label.length}" }
        val sanitizedLabel = label.trim()
        require(sanitizedLabel.isNotEmpty()) { "label must not be blank" }
        return withContext(io) {
            runInRoomTransaction {
                val hostBlock = blockDao.getById(hostTextBlockId)
                    ?: throw IllegalArgumentException("Host block $hostTextBlockId not found")
                require(hostBlock.type == BlockType.TEXT) { "Host block must be TEXT" }
                blockDao.getById(targetBlockId)
                    ?: throw IllegalArgumentException("Target block $targetBlockId not found")

                val content = extractTextContent(hostBlock)
                val existingBody = content.body
                val linePrefix = "- "
                val appendedLine = linePrefix + sanitizedLabel
                val newBody = buildString {
                    if (existingBody.isNotEmpty()) {
                        append(existingBody)
                        append('\n')
                    }
                    append(appendedLine)
                }
                val combinedText = buildCombinedText(content.title, newBody)
                val now = System.currentTimeMillis()
                blockDao.update(hostBlock.copy(text = combinedText, updatedAt = now))

                val anchorStart = combinedText.length - appendedLine.length + linePrefix.length
                val anchorEnd = anchorStart + sanitizedLabel.length
                val newLen = combinedText.length
                logD {
                    "append.out: host=$hostTextBlockId start=$anchorStart end=$anchorEnd newTextLen=$newLen"
                }
                anchorStart to anchorEnd
            }
        }
    }
}
