package com.example.openeer.ui.injection

import android.content.Context
import android.content.Intent
import com.example.openeer.Injection
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.NoteRepository
import kotlinx.coroutines.runBlocking

object InjectionCoordinator {
    const val ACTION_INSERT_MEDIA_TOKEN = "com.example.openeer.action.INSERT_MEDIA_TOKEN"
    const val EXTRA_PARENT_ID = "parentId"
    const val EXTRA_BLOCK_ID = "blockId"

    /**
     * Stratégie MVP :
     * 1) Si la note enfant a un parentId → injecter là.
     * 2) Sinon, si une note est déjà ouverte → injecter dans l’ouverte.
     * 3) Sinon → échec soft (toast).
     */
    fun injectIntoParent(
        context: Context,
        childNoteId: Long?,
        openNoteIdProvider: () -> Long?,
        blockId: Long
    ): Boolean {
        val appContext = context.applicationContext
        val db = AppDatabase.getInstance(appContext)
        val blocksRepo = Injection.provideBlocksRepository(context)
        val repo = NoteRepository(
            appContext,
            db.noteDao(),
            db.attachmentDao(),
            db.blockReadDao(),
            blocksRepo,
            db.listItemDao(),
            database = db,
        )
        val parentId: Long? = runBlocking {
            val child = childNoteId?.let { repo.noteOnce(it) }
            child?.parentId
        } ?: openNoteIdProvider()

        parentId ?: return false

        context.sendBroadcast(
            Intent(ACTION_INSERT_MEDIA_TOKEN).apply {
                putExtra(EXTRA_PARENT_ID, parentId)
                putExtra(EXTRA_BLOCK_ID, blockId)
            }
        )
        return true
    }
}
