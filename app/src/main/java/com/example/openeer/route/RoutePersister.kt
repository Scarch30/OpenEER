package com.example.openeer.data.route

import android.util.Log
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.ui.map.RoutePersistResult
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

/**
 * Persistance d’un itinéraire côté data-layer, sans dépendre de l’UI.
 *
 * Stratégie :
 *  1) Tenter d’appeler par réflexion MapData.persistRoute(noteRepo, blocksRepo, gson, noteId, payload, mirrorText)
 *     (même signature) et récupérer un RoutePersistResult.
 *  2) Sinon, exécuter un fallback (optionnel) fourni par l’appelant, qui retourne aussi un RoutePersistResult?.
 *
 * Résultat : API unique utilisable depuis le Service, avec retour des IDs créés.
 */
object RoutePersister {

    private const val TAG = "RoutePersister"
    private const val MAPDATA_FQCN = "com.example.openeer.ui.library.MapData"
    private const val MAPDATA_METHOD = "persistRoute"

    /**
     * Persiste l’itinéraire et retourne les IDs créés, ou null si rien n’a été écrit.
     *
     * @param noteRepo       NoteRepository
     * @param blocksRepo     BlocksRepository
     * @param gson           Instance Gson (alignée avec l’UI)
     * @param noteId         Note cible (0 => création gérée par la logique de persistance)
     * @param payload        Données de l’itinéraire
     * @param mirrorText     Résumé texte miroir
     * @param fallbackSaver  Impl de repli si MapData n’est pas dispo (retourne RoutePersistResult?)
     */
    suspend fun persistRoute(
        noteRepo: NoteRepository,
        blocksRepo: BlocksRepository,
        gson: Gson,
        noteId: Long,
        payload: RoutePayload,
        mirrorText: String,
        fallbackSaver: (suspend () -> RoutePersistResult)? = null
    ): RoutePersistResult? = withContext(Dispatchers.IO) {
        // 1) Tentative via MapData.persistRoute(...) par réflexion
        val resultFromReflection = runCatching {
            val mapDataClass = Class.forName(MAPDATA_FQCN)
            val method: Method = mapDataClass.getDeclaredMethod(
                MAPDATA_METHOD,
                NoteRepository::class.java,
                BlocksRepository::class.java,
                Gson::class.java,
                java.lang.Long.TYPE, // primitive long
                RoutePayload::class.java,
                String::class.java
            ).apply { isAccessible = true }

            // Appel statique : MapData.persistRoute(...)
            val res = method.invoke(
                /* obj = */ null,
                noteRepo,
                blocksRepo,
                gson,
                noteId,
                payload,
                mirrorText
            )
            (res as? RoutePersistResult)?.also {
                Log.d(TAG, "MapData.persistRoute() OK → noteId=${it.noteId}, routeBlockId=${it.routeBlockId}")
            }
        }.onFailure { e ->
            Log.d(TAG, "MapData.persistRoute indisponible ou signature différente: ${e.message}")
        }.getOrNull()

        if (resultFromReflection != null) return@withContext resultFromReflection

        // 2) Fallback optionnel
        if (fallbackSaver != null) {
            Log.d(TAG, "Route persistance: fallbackSaver()…")
            return@withContext runCatching { fallbackSaver.invoke() }
                .onFailure { Log.e(TAG, "fallbackSaver() a échoué", it) }
                .getOrNull()
        }

        // 3) Rien d’autre → log et retourner null
        Log.w(TAG, "Route persistance: ni MapData.persistRoute ni fallback fournis. Rien n’a été écrit.")
        null
    }
}
