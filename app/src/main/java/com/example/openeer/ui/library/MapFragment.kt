package com.example.openeer.ui.library

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.AttachmentDao
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.databinding.FragmentMapBinding
import kotlinx.coroutines.Job
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import com.google.gson.Gson
import org.maplibre.android.plugins.annotation.Line
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.geojson.Feature
import com.example.openeer.ui.map.MapStyleIds
import com.example.openeer.ui.map.MapPin
import com.example.openeer.ui.map.RecentHere
import com.example.openeer.ui.map.RouteRecorder
import com.example.openeer.ui.map.MapClusters
import com.example.openeer.ui.map.MapIcons
import com.example.openeer.ui.map.MapManagers
import com.example.openeer.ui.map.MapCamera
import com.example.openeer.ui.map.MapPolylines
import kotlin.math.max

class MapFragment : Fragment(), OnMapReadyCallback {

    private var _b: FragmentMapBinding? = null
    private val b get() = _b!!
    internal val binding get() = b

    internal var mapView: MapView? = null
    internal var map: MapLibreMap? = null


    // Repos
    internal lateinit var database: AppDatabase
    internal lateinit var blocksRepo: BlocksRepository
    internal lateinit var noteRepo: NoteRepository
    internal lateinit var attachmentDao: AttachmentDao

    internal var targetNoteId: Long? = null
    internal var targetBlockId: Long? = null
    internal var pendingBlockFocus: Long? = null
    internal var startMode: String? = null
    internal var isStyleReady = false

    internal var awaitingHerePermission = false
    internal var lastHereLocation: RecentHere? = null
    internal var hintDismissRunnable: Runnable? = null
    internal var hasRequestedLocationPermission = false

    internal var symbolManager: SymbolManager? = null
    internal var polylineManager: LineManager? = null
    internal var recordingRouteLine: Line? = null
    internal var manualRouteLine: Line? = null
    internal var routeRecorder: RouteRecorder? = null
    internal var awaitingRoutePermission = false
    internal var isSavingRoute = false
    internal var isManualRouteMode = false
    internal var isSnapshotInProgress = false
    internal val manualPoints: MutableList<LatLng> = mutableListOf()
    internal var manualAnchorLabel: String? = null
    internal val routeGson by lazy { Gson() }

    internal val locationPins = mutableMapOf<Long, MapPin>()

    internal var selectionDialog: BottomSheetDialog? = null
    internal var selectionBinding: SheetMapSelectedLocationBinding? = null
    internal var selectionSymbol: Symbol? = null
    internal var selectionLatLng: LatLng? = null
    internal var selectionPlace: Place? = null
    internal var selectionJob: Job? = null

    // Mémoire
    internal var allNotes: List<Note> = emptyList()
    internal var lastFeatures: List<Feature>? = null

    // Permission localisation
    internal val REQ_LOC = 1001
    internal val REQ_ROUTE = 1002

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"
        private const val ARG_BLOCK_ID = "arg_block_id"
        private const val ARG_MODE = "arg_mode"
        private const val STATE_NOTE_ID = "state_note_id"
        private const val STATE_BLOCK_ID = "state_block_id"
        private const val STATE_MODE = "state_mode"
        internal const val TAG = "MapFragment"

        const val RESULT_MANUAL_ROUTE = "map_manual_route_seed"
        const val RESULT_MANUAL_ROUTE_LAT = "manual_route_lat"
        const val RESULT_MANUAL_ROUTE_LON = "manual_route_lon"
        const val RESULT_MANUAL_ROUTE_LABEL = "manual_route_label"

        private const val MENU_ROUTE_GPS = 1
        private const val MENU_ROUTE_MANUAL = 2

        fun newInstance(noteId: Long? = null, blockId: Long? = null, mode: String? = null): MapFragment = MapFragment().apply {
            arguments = Bundle().apply {
                if (noteId != null && noteId > 0) {
                    putLong(ARG_NOTE_ID, noteId)
                }
                if (blockId != null && blockId > 0) {
                    putLong(ARG_BLOCK_ID, blockId)
                }
                if (!mode.isNullOrBlank()) {
                    putString(ARG_MODE, mode)
                }
            }
        }
    }


    override fun onAttach(context: Context) {
        super.onAttach(context)
        val appCtx = context.applicationContext
        database = AppDatabase.get(appCtx)
        blocksRepo = BlocksRepository(
            blockDao = database.blockDao(),
            noteDao = database.noteDao(),
            linkDao = database.blockLinkDao()
        )
        attachmentDao = database.attachmentDao()
        noteRepo = NoteRepository(
            database.noteDao(),
            attachmentDao,
            database.blockReadDao(),
            blocksRepo
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetNoteId = savedInstanceState?.getLong(STATE_NOTE_ID, -1L)?.takeIf { it > 0 }
            ?: arguments?.getLong(ARG_NOTE_ID, -1L)?.takeIf { it > 0 }
        targetBlockId = savedInstanceState?.getLong(STATE_BLOCK_ID, -1L)?.takeIf { it > 0 }
            ?: arguments?.getLong(ARG_BLOCK_ID, -1L)?.takeIf { it > 0 }
        startMode = when (val mode = savedInstanceState?.getString(STATE_MODE)
            ?: arguments?.getString(ARG_MODE)) {
            MapActivity.MODE_BROWSE,
            MapActivity.MODE_CENTER_ON_HERE,
            MapActivity.MODE_FOCUS_NOTE -> mode
            else -> MapActivity.MODE_BROWSE
        }
        Log.d(TAG, "Starting map with mode=$startMode note=$targetNoteId block=$targetBlockId")
        pendingBlockFocus = targetBlockId
        isStyleReady = false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentMapBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mapView = b.mapView
        mapView?.onCreate(savedInstanceState)
        mapView?.getMapAsync(this)

        // Boutons UI
        b.btnZoomIn.setOnClickListener { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
        b.btnZoomOut.setOnClickListener { map?.animateCamera(CameraUpdateFactory.zoomOut()) }
        b.btnRecenter.setOnClickListener { recenterToUserOrAll() }
        recordingRouteLine = MapPolylines.clearRecordingLine(polylineManager, recordingRouteLine)
        b.btnAddHere.isEnabled = false
        b.btnAddHere.setOnClickListener { onAddHereClicked() }
        b.btnRecordRoute.isEnabled = false
        b.btnRecordRoute.setOnClickListener { onRouteButtonClicked() }
        b.btnUndoManualRoute.isVisible = false
        b.btnUndoManualRoute.setOnClickListener { onManualRouteUndoClicked() }
        b.btnCancelManualRoute.isVisible = false
        b.btnCancelManualRoute.setOnClickListener { cancelManualRouteDrawing() }
        refreshRouteButtonState()

        parentFragmentManager.setFragmentResultListener(RESULT_MANUAL_ROUTE, viewLifecycleOwner) { _, bundle ->
            val lat = bundle.getDouble(RESULT_MANUAL_ROUTE_LAT, Double.NaN)
            val lon = bundle.getDouble(RESULT_MANUAL_ROUTE_LON, Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return@setFragmentResultListener
            val label = bundle.getString(RESULT_MANUAL_ROUTE_LABEL)
            startManualRouteDrawing(LatLng(lat, lon), label)
        }
    }

    override fun onMapReady(mapboxMap: MapLibreMap) {
        map = mapboxMap
        isStyleReady = false
        val initialMode = startMode ?: MapActivity.MODE_BROWSE

        val styleUrl = "https://tiles.basemaps.cartocdn.com/gl/positron-gl-style/style.json"
        map?.setStyle(styleUrl) { style ->
            // Gestes
            map?.uiSettings?.isCompassEnabled = true
            map?.uiSettings?.isRotateGesturesEnabled = true
            map?.uiSettings?.isZoomGesturesEnabled = true
            map?.uiSettings?.isScrollGesturesEnabled = true

            // Icônes en mémoire
            MapIcons.ensureDefaultIcons(style, requireContext())
            MapIcons.ensureNotesSourceAndLayer(style)


            val mv = mapView ?: return@setStyle
            val mapInstance = map ?: return@setStyle

            symbolManager?.onDestroy()
            symbolManager = MapManagers.createSymbolManager(mv, mapInstance, style)
            refreshPins()

            polylineManager?.onDestroy()
            polylineManager = MapManagers.createLineManager(mv, mapInstance, style)
            recordingRouteLine = null
            manualRouteLine = null
            if (isManualRouteMode) {
                manualRouteLine = MapPolylines.updateManualRoutePolyline(polylineManager, manualRouteLine, manualPoints)

            }

            b.btnAddHere.isEnabled = true
            b.btnRecordRoute.isEnabled = true

            if (initialMode == MapActivity.MODE_BROWSE) {
                MapCamera.moveCameraToDefault(map)

            }

            // Charge les notes brutes (une seule fois)
            loadNotesThenRender()

            // Recalcule l’agrégation quand la caméra s’arrête → nombres corrects selon le zoom
            map?.addOnCameraIdleListener {
                MapClusters.renderGroupsForCurrentZoom(map, allNotes)
                maybeDismissSelectionOnPan()
            }

            isStyleReady = true
            applyPendingBlockFocus()
            applyStartMode(initialMode)
        }

        // Tap : zoom doux + bottom sheet (toujours)
        map?.addOnMapClickListener { latLng ->
            if (isManualRouteMode) {
                handleManualMapTap(latLng)
                return@addOnMapClickListener true
            }
            val screenPt = map?.projection?.toScreenLocation(latLng) ?: return@addOnMapClickListener false
            val features = map?.queryRenderedFeatures(screenPt, MapStyleIds.LYR_NOTES).orEmpty()
            val f = features.firstOrNull() ?: return@addOnMapClickListener false

            val lat = f.getNumberProperty("lat")?.toDouble() ?: return@addOnMapClickListener false
            val lon = f.getNumberProperty("lon")?.toDouble() ?: return@addOnMapClickListener false
            val title = f.getStringProperty("title") ?: "Lieu"
            val count = f.getNumberProperty("count")?.toInt() ?: 1

            // Zoom doux
            val currentZoom = map?.cameraPosition?.zoom ?: 5.0
            val targetZoom = max(currentZoom, 13.5)
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), targetZoom))

            showHint("$title — $count note(s)")

            // Ouvre systématiquement le bottom sheet (même si count > 1)
            NotesBottomSheet.newInstance(lat, lon, title)
                .show(parentFragmentManager, "notes_sheet")
            true
        }

        map?.addOnMapLongClickListener { latLng -> handleMapLongClick(latLng) }
    }

    internal fun showHint(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        val hintView = binding.clusterHint
        val fullText = if (actionLabel != null) {
            "$text\n$actionLabel"
        } else {
            text
        }
        hintView.animate().cancel()
        hintDismissRunnable?.let { hintView.removeCallbacks(it) }
        hintView.text = fullText
        hintView.visibility = View.VISIBLE
        hintView.alpha = 0f
        hintView.setOnClickListener(null)
        hintView.isClickable = onAction != null
        hintView.isFocusable = onAction != null
        if (onAction != null) {
            hintView.setOnClickListener { onAction() }
        }
        hintView.animate().alpha(1f).setDuration(120).withEndAction {
            val stayDuration = if (onAction != null) 2000L else 450L
            val dismissRunnable = Runnable {
                hintView.animate().alpha(0f).setDuration(450).withEndAction {
                    hintView.visibility = View.GONE
                    hintView.alpha = 1f
                    hintView.setOnClickListener(null)
                    hintView.isClickable = false
                    hintView.isFocusable = false
                    hintDismissRunnable = null
                }.start()
            }
            hintDismissRunnable = dismissRunnable
            hintView.postDelayed(dismissRunnable, stayDuration)
        }.start()
    }


    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        targetNoteId?.let { outState.putLong(STATE_NOTE_ID, it) }
        targetBlockId?.let { outState.putLong(STATE_BLOCK_ID, it) }
        startMode?.let { outState.putString(STATE_MODE, it) }
    }

    // MapView lifecycle
    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onPause() {
        cancelRouteRecording()
        mapView?.onPause()
        super.onPause()
    }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onDestroyView() {
        cancelRouteRecording()
        selectionDialog?.setOnDismissListener(null)
        selectionDialog?.dismiss()
        handleSelectionDismissed()
        mapView?.onDestroy()
        symbolManager?.onDestroy()
        symbolManager = null
        manualRouteLine = MapPolylines.clearManualRouteLine(polylineManager, manualRouteLine)
        polylineManager?.onDestroy()
        polylineManager = null
        recordingRouteLine = null
        manualRouteLine = null
        manualPoints.clear()
        manualAnchorLabel = null
        isManualRouteMode = false
        isStyleReady = false
        pendingBlockFocus = targetBlockId
        locationPins.values.forEach { it.symbol = null }
        _b?.clusterHint?.let { hintView ->
            hintDismissRunnable?.let { hintView.removeCallbacks(it) }
        }
        hintDismissRunnable = null
        super.onDestroyView()
        _b = null
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_ROUTE) {
            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            awaitingRoutePermission = false
            if (granted) {
                startRouteRecording()
            } else {
                val shouldShowRationale = permissions.any { shouldShowRequestPermissionRationale(it) }
                if (shouldShowRationale) {
                    showHint(getString(R.string.map_location_permission_needed))
                } else {
                    showLocationDisabledHint()
                }
            }
            return
        }
        if (requestCode == REQ_LOC) {
            if (awaitingHerePermission) {
                val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
                awaitingHerePermission = false
                if (granted) {
                    onAddHereClicked()
                } else {
                    val shouldShowRationale = permissions.any { shouldShowRequestPermissionRationale(it) }
                    if (shouldShowRationale) {
                        showHint(getString(R.string.map_location_permission_needed))
                    } else {
                        showLocationDisabledHint()
                    }
                }
                return
            }
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                recenterToUserOrAll()
            }
            if (grantResults.all { it == PackageManager.PERMISSION_DENIED }) {
                val shouldShowRationale = permissions.any { shouldShowRequestPermissionRationale(it) }
                if (!shouldShowRationale) {
                    showLocationDisabledHint()
                }
            }
        }
    }
}