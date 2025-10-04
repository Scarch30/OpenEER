// app/src/main/java/com/example/openeer/ui/library/NotesBottomSheet.kt
package com.example.openeer.ui.library

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.databinding.SheetNotesLocationBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class NotesBottomSheet : BottomSheetDialogFragment() {

    private var _b: SheetNotesLocationBinding? = null
    private val b get() = _b!!

    private lateinit var vm: NotesVm

    companion object {
        private const val ARG_LAT = "lat"
        private const val ARG_LON = "lon"
        private const val ARG_TITLE = "title"

        fun newInstance(lat: Double, lon: Double, title: String): NotesBottomSheet =
            NotesBottomSheet().apply {
                arguments = bundleOf(
                    ARG_LAT to lat,
                    ARG_LON to lon,
                    ARG_TITLE to title
                )
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pas de viewModels KTX -> on reste en ViewModelProvider “vanilla”
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val lat = requireArguments().getDouble(ARG_LAT)
                val lon = requireArguments().getDouble(ARG_LON)
                val db = AppDatabase.get(requireContext().applicationContext)
                return NotesVm(lat, lon, db) as T
            }
        }
        vm = ViewModelProvider(this, factory)[NotesVm::class.java]
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = SheetNotesLocationBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val title = requireArguments().getString(ARG_TITLE) ?: "Lieu"
        b.title.text = title

        // Bouton "Tout voir" -> réutilise ton écran existant
        b.btnAll.setOnClickListener {
            val lat = requireArguments().getDouble(ARG_LAT)
            val lon = requireArguments().getDouble(ARG_LON)
            val i = Intent(requireContext(), LocationNotesActivity::class.java)
            i.putExtra(LocationNotesActivity.EXTRA_LAT, lat)
            i.putExtra(LocationNotesActivity.EXTRA_LON, lon)
            i.putExtra(LocationNotesActivity.EXTRA_TITLE, title)
            startActivity(i)
            dismissAllowingStateLoss()
        }

        // Charge et affiche
        viewLifecycleOwner.lifecycleScope.launch {
            val notes = vm.loadNearby()
            bindNotes(notes)
        }
    }

    private fun bindNotes(notes: List<Note>) {
        if (_b == null) return
        b.count.text = "${notes.size} note(s)"
        val container = b.listContainer
        container.removeAllViews()

        if (notes.isEmpty()) {
            b.empty.visibility = View.VISIBLE
            return
        } else {
            b.empty.visibility = View.GONE
        }

        val inflater = LayoutInflater.from(requireContext())
        notes.take(8).forEach { n ->
            val item = com.example.openeer.databinding.ItemNoteMiniBinding.inflate(inflater, container, false)
            item.title.text = n.title ?: "(Sans titre)"
            val body = (n.body ?: "").trim()
            item.body.text = if (body.isNotEmpty()) body.take(160) else "—"
            item.meta.text = n.placeLabel ?: ""
            item.root.setOnClickListener {
                val lat = requireArguments().getDouble(ARG_LAT)
                val lon = requireArguments().getDouble(ARG_LON)
                val i = Intent(requireContext(), LocationNotesActivity::class.java)
                i.putExtra(LocationNotesActivity.EXTRA_LAT, lat)
                i.putExtra(LocationNotesActivity.EXTRA_LON, lon)
                i.putExtra(LocationNotesActivity.EXTRA_TITLE, requireArguments().getString(ARG_TITLE) ?: "Lieu")
                startActivity(i)
                dismissAllowingStateLoss()
            }
            container.addView(item.root)
        }
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }

    // --- VM ---
    class NotesVm(
        private val lat: Double,
        private val lon: Double,
        private val db: AppDatabase
    ) : ViewModel() {

        suspend fun loadNearby(): List<Note> = withContext(Dispatchers.IO) {
            // On respecte ton DAO actuel: on part de getAllWithLocation() puis filtre côté Kotlin
            val all = db.noteDao().getAllWithLocation()
            if (all.isEmpty()) return@withContext emptyList<Note>()

            fun haversineMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
                val R = 6371000.0
                val dLat = Math.toRadians(bLat - aLat)
                val dLon = Math.toRadians(bLon - aLon)
                val s1 = sin(dLat / 2)
                val s2 = sin(dLon / 2)
                val aa = s1 * s1 + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * s2 * s2
                val c = 2 * atan2(sqrt(aa), sqrt(1 - aa))
                return R * c
            }

            val radiusM = 200.0 // voisinage
            all.asSequence()
                .filter { it.lat != null && it.lon != null }
                .map { n -> n to haversineMeters(lat, lon, n.lat!!, n.lon!!) }
                .filter { it.second <= radiusM }
                .sortedBy { it.second }
                .map { it.first }
                .toList()
        }
    }
}
