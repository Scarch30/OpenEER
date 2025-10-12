package com.example.openeer.ui.sheets

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.bumptech.glide.Glide
import com.example.openeer.R
import com.example.openeer.data.block.BlockType
import com.example.openeer.ui.library.MapActivity
import com.example.openeer.ui.library.MapPreviewStorage
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textview.MaterialTextView
import java.util.Locale

/**
 * Affiche 1) la capture (snapshot) et 2) une adresse/étiquette
 * cliquable qui ouvre Google Maps. Bouton "Voir la carte" pour
 * ouvrir MapActivity centrée sur la note/bloc.
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
    private val label: String by lazy { requireArguments().getString(ARG_LABEL).orEmpty() }
    private val type: BlockType by lazy {
        val s = requireArguments().getString(ARG_TYPE) ?: BlockType.LOCATION.name
        runCatching { BlockType.valueOf(s) }.getOrDefault(BlockType.LOCATION)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return BottomSheetDialog(requireContext(), theme).also { dialog ->
            dialog.setOnShowListener { di ->
                val bottomSheet =
                    (di as BottomSheetDialog).findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                        ?: return@setOnShowListener
                bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.skipCollapsed = true
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
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
        val openMaps: MaterialButton = root.findViewById(R.id.btnOpenInMaps)
        val openMapLibre: MaterialButton = root.findViewById(R.id.btnOpenMapLibre)

        // 1) Charger la capture si elle existe
        val file = MapPreviewStorage.fileFor(requireContext(), blockId, type)
        if (file.exists()) {
            image.visibility = View.VISIBLE
            Glide.with(image).load(file).centerCrop().into(image)
        } else {
            image.visibility = View.GONE
        }

        // 2) Adresse/étiquette cliquable → Google Maps
        val display = label.ifBlank {
            String.format(
                Locale.US,
                getString(R.string.block_location_coordinates),
                lat,
                lon
            )
        }
        addressText.text = display
        val openMapsAction: (View) -> Unit = {
            val encoded = Uri.encode(display)
            val geoUri = Uri.parse("geo:0,0?q=$lat,$lon($encoded)")
            val intent = Intent(Intent.ACTION_VIEW, geoUri)
            val pm = requireContext().packageManager
            val launched = runCatching {
                if (intent.resolveActivity(pm) != null) {
                    startActivity(intent); true
                } else false
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

        // 3) Bouton "Voir la carte" → focus sur la note/bloc (zoom précis)
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
}
