package com.example.openeer

import android.content.Context
import android.util.Log
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlocksRepository

object Injection {
    @Volatile
    private var blocksRepository: BlocksRepository? = null

    fun provideBlocksRepository(context: Context): BlocksRepository {
        val appContext = context.applicationContext
        val existing = blocksRepository
        if (existing != null) {
            Log.d("ListRepo", "PROVIDE repo singleton (dao wired)")
            return existing
        }
        return synchronized(this) {
            val current = blocksRepository
            if (current != null) {
                Log.d("ListRepo", "PROVIDE repo singleton (dao wired)")
                current
            } else {
                val db = AppDatabase.get(appContext)
                BlocksRepository(
                    appContext = appContext,
                    blockDao = db.blockDao(),
                    noteDao = db.noteDao(),
                    linkDao = db.blockLinkDao(),
                    listItemDao = db.listItemDao(),
                    inlineLinkDao = db.inlineLinkDao(),
                    listItemLinkDao = db.listItemLinkDao(),
                ).also { repo ->
                    blocksRepository = repo
                    Log.d("ListRepo", "PROVIDE repo singleton (dao wired)")
                }
            }
        }
    }
}
