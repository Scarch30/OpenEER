package com.example.openeer.ui.sheets

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.example.openeer.R
import com.example.openeer.Injection
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.map.RouteSimplifier
import com.example.openeer.map.buildMapsUrl
import com.example.openeer.ui.library.MapPreviewStorage
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.Gson
import kotlinx.coroutines.launch
import java.util.Locale
import org.maplibre.android.geometry.LatLng

class MapSnapshotSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_BLOCK_ID = "arg_block_id"

        fun show(fm: androidx.fragment.app.FragmentManager, blockId: Long) {
            val sheet = MapSnapshotSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_BLOCK_ID, blockId)
                }
            }
            sheet.show(fm, "map_snapshot_sheet")
        }
    }

    private val blockId: Long by lazy {
        requireArguments().getLong(ARG_BLOCK_ID)
    }

    private val blocksRepo: BlocksRepository by lazy {
        Injection.provideBlocksRepository(requireContext())
    }

    private val routeGson by lazy { Gson() }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = BottomSheetDialog(requireContext(), theme)
        dialog.setOnShowListener { di ->
            val bottomSheet =
                (di as BottomSheetDialog).findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                    ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(bottomSheet)
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.bottomsheet_map_snapshot, container, false)
        val img = view.findViewById<ImageView>(R.id.mapSnapshotImage)
        val title = view.findViewById<TextView>(R.id.mapSnapshotTitle)
        val btn = view.findViewById<Button>(R.id.mapSnapshotOpenBtn)
        val routeBtn = view.findViewById<Button>(R.id.btnOpenRouteInMaps)

        viewLifecycleOwner.lifecycleScope.launch {
            val block = blocksRepo.getBlock(blockId)
            if (block == null) {
                Toast.makeText(requireContext(), "Bloc introuvable", Toast.LENGTH_SHORT).show()
                dismiss()
                return@launch
            }

            val routePayload = if (block.type == BlockType.ROUTE) {
                block.routeJson?.let {
                    runCatching { routeGson.fromJson(it, RoutePayload::class.java) }.getOrNull()
                }
            } else {
                null
            }

            val file = MapPreviewStorage.fileFor(requireContext(), block.id, block.type)
            if (!file.exists()) {
                Toast.makeText(requireContext(), "Aucune capture disponible", Toast.LENGTH_SHORT).show()
                dismiss()
                return@launch
            }

            Glide.with(img)
                .load(file)
                .centerCrop()
                .into(img)

            val label = buildLabel(block, routePayload)
            title.text = label

            val mapsAction = buildMapsAction(block, routePayload)
            btn.isEnabled = mapsAction != null
            btn.setOnClickListener {
                val action = mapsAction
                if (action != null) {
                    action()
                } else {
                    showMapsUnavailableToast()
                }
            }

            setupRouteButton(routeBtn, block, routePayload)
        }

        return view
    }

    private fun buildLabel(block: BlockEntity, routePayload: RoutePayload?): String {
        return when {
            !block.placeName.isNullOrBlank() -> block.placeName!!
            block.type == BlockType.ROUTE && routePayload != null ->
                getString(R.string.block_route_points, routePayload.points.size)
            block.lat != null && block.lon != null -> String.format(
                Locale.US,
                "%.5f, %.5f",
                block.lat,
                block.lon
            )
            else -> getString(R.string.map_pick_google_maps_unavailable)
        }
    }

    private fun buildMapsAction(
        block: BlockEntity,
        payload: RoutePayload?,
    ): (() -> Unit)? {
        if (block.type == BlockType.ROUTE && payload != null) {
            val points = payload.points.map { LatLng(it.lat, it.lon) }
            val url = buildMapsUrl(points)
            if (url != null) {
                return { openRouteInGoogleMaps(url) }
            }

            val firstPoint = payload.firstPoint()
            val lat = firstPoint?.lat ?: block.lat
            val lon = firstPoint?.lon ?: block.lon
            if (lat != null && lon != null) {
                val label = block.placeName?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.block_route_points, payload.pointCount)
                return { openCoordinateInMaps(lat, lon, label) }
            }
        }

        val lat = block.lat
        val lon = block.lon
        if (lat != null && lon != null) {
            val label = block.placeName?.takeIf { it.isNotBlank() }
                ?: getString(R.string.block_location_coordinates, lat, lon)
            return { openCoordinateInMaps(lat, lon, label) }
        }

        return null
    }

    private fun openCoordinateInMaps(lat: Double, lon: Double, label: String) {
        val encodedLabel = Uri.encode(label)
        val geoUri = Uri.parse("geo:0,0?q=$lat,$lon($encodedLabel)")
        val pm = requireContext().packageManager

        var launched = false
        val geoIntent = Intent(Intent.ACTION_VIEW, geoUri)
        if (geoIntent.resolveActivity(pm) != null) {
            launched = runCatching { startActivity(geoIntent) }.isSuccess
        }

        if (!launched) {
            val url = String.format(Locale.US, "https://www.google.com/maps/search/?api=1&query=%f,%f", lat, lon)
            val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            if (fallbackIntent.resolveActivity(pm) != null) {
                launched = runCatching { startActivity(fallbackIntent) }.isSuccess
            }
        }

        if (!launched) {
            showMapsUnavailableToast()
        }
    }

    private fun showMapsUnavailableToast() {
        Toast.makeText(requireContext(), R.string.map_pick_google_maps_unavailable, Toast.LENGTH_SHORT).show()
    }

    private fun setupRouteButton(routeBtn: Button, block: BlockEntity, payload: RoutePayload?) {
        if (block.type != BlockType.ROUTE || payload == null) {
            routeBtn.visibility = View.GONE
            routeBtn.setOnClickListener(null)
            return
        }

        val adaptiveEpsilon = RouteSimplifier.adaptiveEpsilonMeters(payload.points)
        val simplified = RouteSimplifier.simplifyMeters(payload.points, adaptiveEpsilon)
        val effectivePoints = if (simplified.size >= 2) simplified else payload.points
        val latLngPoints = effectivePoints.map { LatLng(it.lat, it.lon) }
        val url = buildMapsUrl(latLngPoints)

        if (url != null) {
            routeBtn.visibility = View.VISIBLE
            routeBtn.setOnClickListener {
                openRouteInGoogleMaps(url)
            }
        } else {
            routeBtn.visibility = View.GONE
            routeBtn.setOnClickListener(null)
        }
    }

    private fun openRouteInGoogleMaps(url: String) {
        val uri = Uri.parse(url)
        val intent = Intent(Intent.ACTION_VIEW, uri)

        val mapsPackage = "com.google.android.apps.maps"
        val packageManager = requireContext().packageManager
        val mapsIntent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage(mapsPackage) }
        if (mapsIntent.resolveActivity(packageManager) != null) {
            intent.`package` = mapsPackage
        }

        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(requireContext(), R.string.map_pick_google_maps_unavailable, Toast.LENGTH_SHORT).show()
        }
    }
}
