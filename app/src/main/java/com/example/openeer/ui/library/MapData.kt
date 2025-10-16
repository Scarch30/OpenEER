package com.example.openeer.ui.library

import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.data.block.RoutePointPayload
import com.example.openeer.ui.map.RoutePersistResult
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking

/**
 * Persistance directe d'un itinéraire depuis l'UI (réutilisée par le service via réflexion).
 */
object MapData {

    private const val TAG = "MapData"

    @JvmStatic
    fun persistRoute(
        noteRepo: NoteRepository,
        blocksRepo: BlocksRepository,
        gson: Gson,
        noteId: Long?,
        payload: RoutePayload,
        mirrorText: String
    ): RoutePersistResult? {
        val database = extractDatabase(noteRepo, blocksRepo)
        if (database == null) {
            Log.e(TAG, "persistRoute(): impossible de récupérer la base de données")
            return null
        }

        val normalizedNoteId = noteId?.takeIf { it > 0 }
        val serializedRoute = gson.toJson(payload)
        val firstPoint = payload.firstPoint()
        val mirrorContent = mirrorText.trim().ifEmpty {
            "Itinéraire (${payload.pointCount} pts)"
        }

        return runCatching {
            runBlocking {
                database.withTransaction {
                    val targetNoteId = ensureNote(noteRepo, normalizedNoteId, firstPoint)
                    val routeBlockId = blocksRepo.appendRoute(
                        noteId = targetNoteId,
                        routeJson = serializedRoute,
                        lat = firstPoint?.lat,
                        lon = firstPoint?.lon
                    )
                    val mirrorBlockId = blocksRepo.appendText(targetNoteId, mirrorContent)

                    RoutePersistResult(
                        noteId = targetNoteId,
                        routeBlockId = routeBlockId,
                        mirrorBlockId = mirrorBlockId,
                        payload = payload
                    )
                }
            }
        }.onFailure { e ->
            Log.e(TAG, "persistRoute() a échoué", e)
        }.getOrNull()
    }

    private fun extractDatabase(
        noteRepo: NoteRepository,
        blocksRepo: BlocksRepository
    ): RoomDatabase? {
        resolveDatabaseFromNoteRepo(noteRepo)?.let { return it }
        resolveDatabaseFromBlocksRepo(blocksRepo)?.let { return it }
        return null
    }

    private fun resolveDatabaseFromNoteRepo(noteRepo: NoteRepository): RoomDatabase? =
        runCatching {
            val field = NoteRepository::class.java.getDeclaredField("noteDao")
            field.isAccessible = true
            val dao = field.get(noteRepo)
            resolveDatabaseFromDao(dao)
        }.getOrNull()

    private fun resolveDatabaseFromBlocksRepo(blocksRepo: BlocksRepository): RoomDatabase? {
        val candidateFields = arrayOf("noteDao", "blockDao")
        for (name in candidateFields) {
            val db = runCatching {
                val field = BlocksRepository::class.java.getDeclaredField(name)
                field.isAccessible = true
                val dao = field.get(blocksRepo)
                resolveDatabaseFromDao(dao)
            }.getOrNull()
            if (db != null) return db
        }
        return null
    }

    private fun resolveDatabaseFromDao(dao: Any?): RoomDatabase? =
        runCatching {
            val implClass = dao?.javaClass ?: return null
            val dbField = implClass.getDeclaredField("__db")
            dbField.isAccessible = true
            dbField.get(dao) as? RoomDatabase
        }.getOrNull()

    private suspend fun ensureNote(
        noteRepo: NoteRepository,
        noteId: Long?,
        firstPoint: RoutePointPayload?
    ): Long {
        val existing = noteId?.let { id ->
            noteRepo.noteOnce(id)
        }
        if (existing != null) return existing.id

        val lat = firstPoint?.lat
        val lon = firstPoint?.lon
        return noteRepo.createTextNote(
            body = "",
            lat = lat,
            lon = lon,
            place = null,
            accuracyM = null
        )
    }
}
