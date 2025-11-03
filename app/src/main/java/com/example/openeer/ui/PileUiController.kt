package com.example.openeer.ui

import android.widget.TextView
import androidx.core.view.isGone
import com.example.openeer.R
import com.example.openeer.databinding.ActivityMainBinding
import com.example.openeer.imports.MediaKind
import com.example.openeer.ui.panel.media.MediaCategory

/**
 * Gère l’affichage des piles médias (compteurs + libellés).
 */
class PileUiController(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
    private val notePanel: NotePanelController,
) {

    private val pileCountViews: List<TextView> by lazy {
        listOf(
            requireNotNull(binding.root.findViewWithTag("pileCount1")) as TextView,
            requireNotNull(binding.root.findViewWithTag("pileCount2")) as TextView,
            requireNotNull(binding.root.findViewWithTag("pileCount3")) as TextView,
            requireNotNull(binding.root.findViewWithTag("pileCount4")) as TextView,
            requireNotNull(binding.root.findViewWithTag("pileCount5")) as TextView,
        )
    }

    private var lastPileCounts = PileCounts()

    fun onPileCountsChanged(counts: PileCounts) {
        lastPileCounts = counts
        val hasOpenNote = notePanel.openNoteId != null
        binding.pileCounters.isGone = !hasOpenNote
        val currentPiles = if (hasOpenNote) notePanel.currentPileUi() else emptyList()
        renderPiles(currentPiles)
    }

    fun increment(kind: MediaKind) {
        if (notePanel.openNoteId == null) return
        onPileCountsChanged(lastPileCounts.increment(kind))
    }

    fun renderPiles(piles: List<PileUi>) {
        val labels = listOf(binding.pileLabel1, binding.pileLabel2, binding.pileLabel3, binding.pileLabel4, binding.pileLabel5)
        val titleByCategory = mapOf(
            MediaCategory.PHOTO to "Photos/Vidéos",
            MediaCategory.AUDIO to "Audios",
            MediaCategory.TEXT to "Textes",
            MediaCategory.SKETCH to "Fichiers",
            MediaCategory.LOCATION to activity.getString(R.string.pile_label_locations),
        )
        val fallbackOrder = listOf(
            MediaCategory.PHOTO,
            MediaCategory.AUDIO,
            MediaCategory.TEXT,
            MediaCategory.SKETCH,
            MediaCategory.LOCATION,
        )
        val orderedCategories = buildList {
            piles.forEach { add(it.category) }
            fallbackOrder.forEach { category ->
                if (category !in this) add(category)
            }
        }
        for (i in labels.indices) {
            val category = orderedCategories.getOrNull(i)
            if (category == null) {
                labels[i].text = "—"
                pileCountViews[i].text = "0"
            } else {
                labels[i].text = titleByCategory[category] ?: "—"
                val countFromPiles = piles.firstOrNull { it.category == category }?.count
                val fallbackCount = when (category) {
                    MediaCategory.PHOTO -> lastPileCounts.photos
                    MediaCategory.AUDIO -> lastPileCounts.audios
                    MediaCategory.TEXT -> lastPileCounts.textes
                    MediaCategory.SKETCH -> lastPileCounts.files
                    MediaCategory.LOCATION -> lastPileCounts.locations
                }
                pileCountViews[i].text = (countFromPiles ?: fallbackCount).toString()
            }
        }
    }
}
