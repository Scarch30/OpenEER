package com.example.openeer.ui.sheets

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.openeer.Injection
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.block.BlockType
import com.example.openeer.ui.dialogs.ChildNameDialog
import com.example.openeer.ui.library.MapActivity
import com.example.openeer.ui.library.MapPreviewStorage
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Affiche la vignette (snapshot) + une adresse cliquable.
 * Si le label initial est un fallback (coords / "geo:" / "Position actuelle"),
 * on montre "Adresse en cours de résolution…" puis on remplace automatiquement
 * dès que la DB contient une vraie adresse (reverse geocoding terminé).
 */
class LocationPreviewSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"
        private const val ARG_BLOCK_ID = "arg_block_id"
        private const val ARG_LAT = "arg_lat"
        private const val ARG_LON = "arg_lon"
        private const val ARG_LABEL = "arg_label"
        private const val ARG_TYPE = "arg_type"

        fun show(
            fm: FragmentManager,
            noteId: Long,
            blockId: Long,
            lat: Double,
            lon: Double,
            label: String,
            type: BlockType = BlockType.LOCATION
        ) {
            LocationPreviewSheet().apply {
                arguments = bundleOf(
                    ARG_NOTE_ID to noteId,
                    ARG_BLOCK_ID to blockId,
                    ARG_LAT to lat,
                    ARG_LON to lon,
                    ARG_LABEL to label,
                    ARG_TYPE to type.name
                )
            }.show(fm, "location_preview_$blockId")
        }
    }

    private val noteId: Long by lazy { requireArguments().getLong(ARG_NOTE_ID) }
    private val blockId: Long by lazy { requireArguments().getLong(ARG_BLOCK_ID) }
    private val lat: Double by lazy { requireArguments().getDouble(ARG_LAT) }
    private val lon: Double by lazy { requireArguments().getDouble(ARG_LON) }
    private val initialLabel: String by lazy { requireArguments().getString(ARG_LABEL).orEmpty() }
    private val type: BlockType by lazy {
        runCatching { BlockType.valueOf(requireArguments().getString(ARG_TYPE) ?: BlockType.LOCATION.name) }
            .getOrDefault(BlockType.LOCATION)
    }

    // Accès direct au DAO (simple et fiable)
    private val noteDao by lazy { AppDatabase.get(requireContext().applicationContext).noteDao() }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme).also { dialog ->
            dialog.setOnShowListener { di ->
                val bottomSheet =
                    (di as BottomSheetDialog).findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                        ?: return@setOnShowListener
                bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                BottomSheetBehavior.from(bottomSheet).apply {
                    skipCollapsed = true
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.bottomsheet_location_preview, container, false)

        val image: ShapeableImageView = root.findViewById(R.id.imagePreview)
        val addressText: MaterialTextView = root.findViewById(R.id.addressText)
        val overflowButton: View = root.findViewById(R.id.overflowButton)
        val openMaps: MaterialButton = root.findViewById(R.id.btnOpenInMaps)
        val openMapLibre: MaterialButton = root.findViewById(R.id.btnOpenMapLibre)

        overflowButton.visibility = if (blockId > 0) View.VISIBLE else View.GONE
        overflowButton.setOnClickListener { anchor ->
            if (blockId <= 0) return@setOnClickListener
            val popup = PopupMenu(requireContext(), anchor)
            popup.menuInflater.inflate(R.menu.menu_viewer_item, popup.menu)
            popup.setOnMenuItemClickListener { mi ->
                when (mi.itemId) {
                    R.id.action_rename -> {
                        viewLifecycleOwner.lifecycleScope.launch {
                            val repo = Injection.provideBlocksRepository(requireContext())
                            val current = withContext(Dispatchers.IO) { repo.getChildNameForBlock(blockId) }
                            ChildNameDialog.show(
                                context = requireContext(),
                                initialValue = current,
                                onSave = { newName ->
                                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                        repo.setChildNameForBlock(blockId, newName)
                                    }
                                },
                                onReset = {
                                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                        repo.setChildNameForBlock(blockId, null)
                                    }
                                }
                            )
                        }
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        // 1) Charger la capture si elle existe
        val file = MapPreviewStorage.fileFor(requireContext(), blockId, type)
        if (file.exists()) {
            image.visibility = View.VISIBLE
            Glide.with(image).load(file).centerCrop().into(image)
        } else {
            image.visibility = View.GONE
        }

        // 2) Texte initial
        val coordsPretty = String.format(Locale.US, "%.6f, %.6f", lat, lon)
        val initialIsFallback = isFallbackLabel(initialLabel)
        addressText.text = if (initialIsFallback) {
            // "Adresse en cours de résolution… (lat, lon)"
            getString(R.string.address_resolving_fallback, coordsPretty)
        } else {
            initialLabel
        }

        // 3) Si fallback, on relit la DB (immédiat), puis on poll ~5s pour capter la mise à jour
        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            val fromDb = withContext(Dispatchers.IO) { noteDao.getByIdOnce(noteId)?.placeLabel }
            if (!fromDb.isNullOrBlank() && !isFallbackLabel(fromDb)) {
                addressText.text = fromDb
            } else if (initialIsFallback) {
                val updated = withTimeoutOrNull(5000L) {
                    var v: String?
                    do {
                        delay(400L)
                        v = withContext(Dispatchers.IO) { noteDao.getByIdOnce(noteId)?.placeLabel }
                    } while (v.isNullOrBlank() || isFallbackLabel(v))
                    v
                }
                if (!updated.isNullOrBlank()) {
                    addressText.text = updated
                }
            }
        }

        // 4) Ouvrir dans Google Maps — utilise TOUJOURS le texte courant
        val openMapsAction: (View) -> Unit = {
            val display = addressText.text?.toString().orEmpty().ifBlank { coordsPretty }
            val encoded = Uri.encode(display)
            val geoUri = Uri.parse("geo:0,0?q=$lat,$lon($encoded)")
            val intent = Intent(Intent.ACTION_VIEW, geoUri)
            val pm = requireContext().packageManager
            val launched = runCatching {
                if (intent.resolveActivity(pm) != null) { startActivity(intent); true } else false
            }.getOrDefault(false)
            if (!launched) {
                val url = String.format(
                    Locale.US,
                    "https://www.google.com/maps/search/?api=1&query=%f,%f",
                    lat, lon
                )
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        addressText.setOnClickListener(openMapsAction)
        openMaps.setOnClickListener(openMapsAction)

        // 5) Bouton "Voir la carte"
        openMapLibre.setOnClickListener {
            startActivity(
                MapActivity.newFocusNoteIntent(
                    requireContext(),
                    noteId = noteId,
                    blockId = blockId
                )
            )
        }

        return root
    }

    /** Détection de fallback : vide, "Position actuelle", "geo:…", ou "lat, lon". */
    private fun isFallbackLabel(s: String?): Boolean {
        if (s.isNullOrBlank()) return true
        if (s.equals("Position actuelle", ignoreCase = true)) return true
        if (s.startsWith("geo:", ignoreCase = true)) return true
        val regexCoord = Regex("""^\s*[-+]?\d{1,3}\.\d{3,}\s*,\s*[-+]?\d{1,3}\.\d{3,}\s*$""")
        return regexCoord.matches(s)
    }
}
