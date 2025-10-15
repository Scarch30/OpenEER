package com.example.openeer.data.route

import android.util.Log
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.RoutePayload
import com.google.gson.Gson
import com.example.openeer.ui.map.RoutePersistResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Method

/**
 * Persistance d’un itinéraire côté data-layer, sans dépendre de l’UI.
 *
 * Stratégie :
 *  1) Tenter d’appeler par réflexion MapData.persistRoute(noteRepo, blocksRepo, gson, noteId, payload, mirrorText)
 *     si elle existe (même signature) dans le module UI.
 *  2) Sinon, exécuter un fallback (optionnel) fourni par l’appelant, typiquement une impl directe via les repositories.
 *
 * Résultat : pas de "Unresolved reference: MapData" et une API unique utilisable depuis le Service.
 */
object RoutePersister {

    private const val TAG = "RoutePersister"
    private const val MAPDATA_FQCN = "com.example.openeer.ui.library.MapData"
    private const val MAPDATA_METHOD = "persistRoute"

    /**
     * Persiste l’itinéraire.
     *
     * @param noteRepo       Ton NoteRepository
     * @param blocksRepo     Ton BlocksRepository
     * @param gson           Instance Gson (même que celle utilisée côté UI)
     * @param noteId         Note cible (0 = laisse ta logique décider de créer/associer)
     * @param payload        Données de l’itinéraire
     * @param mirrorText     Texte résumé (même format que l’UI)
     * @param fallbackSaver  Optionnel : implémentation de repli si MapData n’est pas trouvée
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
        val result = runCatching {
            val mapDataClass = Class.forName(MAPDATA_FQCN)
            val method: Method = mapDataClass.getDeclaredMethod(
                MAPDATA_METHOD,
                NoteRepository::class.java,
                BlocksRepository::class.java,
                Gson::class.java,
                java.lang.Long.TYPE,     // long primitive
                RoutePayload::class.java,
                String::class.java
            ).apply { isAccessible = true }

            // Appel statique : MapData.persistRoute(...)
            val invokeResult = method.invoke(
                /* obj = */ null,
                noteRepo,
                blocksRepo,
                gson,
                noteId,
                payload,
                mirrorText
            )
            (invokeResult as? RoutePersistResult)?.also {
                Log.d(TAG, "Route persistance: MapData.persistRoute() appelé avec succès.")
            }
        }.onFailure { e ->
            Log.d(TAG, "MapData.persistRoute non disponible ou signature différente: ${e.message}")
        }.getOrNull()

        if (result != null) {
            return@withContext result
        }

        // 2) Fallback optionnel
        if (fallbackSaver != null) {
            Log.d(TAG, "Route persistance: fallbackSaver() (impl data-layer) …")
            return@withContext fallbackSaver.invoke()
        }

        // 3) Rien d’autre : on log et on laisse l’appelant gérer le rechargement UI
        Log.w(TAG, "Route persistance: ni MapData.persistRoute ni fallback fournis. Rien n’a été écrit.")
        null
    }
}
