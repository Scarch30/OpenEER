package com.example.openeer.ui

import androidx.lifecycle.lifecycleScope
import com.example.openeer.core.getOneShotPlace
import com.example.openeer.data.NoteRepository
import com.example.openeer.ui.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Encapsule la création/opennote automatique depuis l’écran principal.
 */
class NoteCreationHelper(
    private val activity: MainActivity,
    private val repo: NoteRepository,
    private val notePanel: NotePanelController,
) {

    suspend fun ensureOpenNote(): Long {
        notePanel.openNoteId?.let { return it }
        val newId = withContext(Dispatchers.IO) {
            repo.createTextNote("")
        }
        activity.toast("Note créée (#$newId)")
        notePanel.open(newId)
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val place = runCatching { getOneShotPlace(activity) }.getOrNull()
            if (place != null) {
                repo.updateLocation(
                    id = newId,
                    lat = place.lat,
                    lon = place.lon,
                    place = place.label,
                    accuracyM = place.accuracyM,
                )
            }
        }
        return newId
    }
}
