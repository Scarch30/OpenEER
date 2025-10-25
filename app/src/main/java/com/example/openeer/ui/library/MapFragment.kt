package com.example.openeer.ui.library

import android.content.Context
import android.location.Address
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.example.openeer.R
import com.example.openeer.core.Place
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.AttachmentDao
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.data.block.RoutePointPayload
import com.example.openeer.databinding.FragmentMapBinding
import com.example.openeer.databinding.SheetMapSelectedLocationBinding
import com.example.openeer.ui.map.MapPin
import com.example.openeer.ui.map.RecentHere
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.gson.Gson
import kotlinx.coroutines.Job
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.plugins.annotation.Line
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.geojson.Feature

class MapFragment : Fragment(), OnMapReadyCallback {

    private var _b: FragmentMapBinding? = null
    private val b get() = _b!!
    internal val binding get() = b
    internal val bindingOrNull get() = _b

    internal var mapView: MapView? = null
    internal var map: MapLibreMap? = null

    internal lateinit var database: AppDatabase
    internal lateinit var blocksRepo: BlocksRepository
    internal lateinit var noteRepo: NoteRepository
    internal lateinit var attachmentDao: AttachmentDao

    internal var targetNoteId: Long? = null
    internal var targetBlockId: Long? = null
    internal var pendingBlockFocus: Long? = null
    internal var startMode: String? = null
    private var pickModeOverride = false
    internal var isStyleReady = false

    internal val isPickMode: Boolean
        get() = pickModeOverride || startMode == MapActivity.MODE_PICK_LOCATION

    private val showLibraryPins: Boolean
        get() = arguments?.getBoolean(ARG_SHOW_LIBRARY_PINS, false) == true

    internal val shouldShowLocationActions: Boolean
        get() = !isPickMode && !showLibraryPins

    internal var awaitingHerePermission = false
    internal var awaitingFavoritePermission = false
    internal var lastHereLocation: RecentHere? = null
    internal var hintDismissRunnable: Runnable? = null
    internal var hasRequestedLocationPermission = false

    internal var symbolManager: SymbolManager? = null
    internal var polylineManager: LineManager? = null
    internal var recordingRouteLine: Line? = null
    internal var manualRouteLine: Line? = null
    internal var awaitingRoutePermission = false
    internal var isSavingRoute = false
    internal var isManualRouteMode = false
    internal var isSnapshotInProgress = false
    internal val manualPoints: MutableList<LatLng> = mutableListOf()
    internal var manualAnchorLabel: String? = null
    internal val routeGson by lazy { Gson() }

    internal val locationPins = mutableMapOf<Long, MapPin>()
    internal var userLocationSymbol: Symbol? = null
    internal var userLocationLatLng: LatLng? = null
    internal var userLocationListener: android.location.LocationListener? = null
    internal var userLocationManager: android.location.LocationManager? = null
    internal var lastUserLocation: android.location.Location? = null

    internal var selectionDialog: BottomSheetDialog? = null
    internal var selectionBinding: SheetMapSelectedLocationBinding? = null
    internal var selectionSymbol: Symbol? = null
    internal var selectionLatLng: LatLng? = null
    internal var selectionPlace: Place? = null
    internal var selectionJob: Job? = null

    internal lateinit var searchInput: MaterialAutoCompleteTextView
    internal var searchAdapter: ArrayAdapter<String>? = null
    internal var searchJob: Job? = null
    internal var searchExecutionJob: Job? = null
    internal var searchResults: List<Address> = emptyList()

    internal var backgroundPermissionDialog: AlertDialog? = null

    internal var pendingGeo: (() -> Unit)? = null
    internal var waitingBgSettingsReturn = false

    internal var allNotes: List<Note> = emptyList()
    internal var lastFeatures: List<Feature>? = null

    internal var targetNoteLocation: LatLng? = null
    internal var targetNoteLocationResolved = false
    internal var targetNoteLocationLoading = false
    internal var targetNoteLocationNoteId: Long? = null

    internal val REQ_LOC = 1001
    internal val REQ_ROUTE = 1002
    internal val REQ_FAVORITE = 1003

    companion object {
        internal const val MANUAL_ROUTE_MAX_POINTS = 120
        internal const val MANUAL_ROUTE_LOG_TAG = "ManualRoute"
        private const val MENU_CREATE_REMINDER = 1001
        private const val MENU_CREATE_REMINDER_GEO_ONCE = 1002
        private const val MENU_CREATE_REMINDER_GEO_EVERY = 1003

        const val TAG = "MapFragment"
        const val ARG_NOTE_ID = "arg_note_id"
        const val ARG_BLOCK_ID = "arg_block_id"
        const val ARG_MODE = "arg_mode"
        const val STATE_NOTE_ID = "state_note_id"
        const val STATE_BLOCK_ID = "state_block_id"
        const val STATE_MODE = "state_mode"
        const val ARG_SHOW_LIBRARY_PINS = "arg_show_library_pins"
        const val ARG_PICK_MODE = "arg_pick_mode"
        const val STATE_PICK_MODE = "state_pick_mode"
        const val RESULT_MANUAL_ROUTE = "result_manual_route"
        const val RESULT_MANUAL_ROUTE_LAT = "result_manual_route_lat"
        const val RESULT_MANUAL_ROUTE_LON = "result_manual_route_lon"
        const val RESULT_MANUAL_ROUTE_LABEL = "result_manual_route_label"
        const val RESULT_PICK_LOCATION = "result_pick_location"
        const val RESULT_PICK_LOCATION_LAT = "result_pick_location_lat"
        const val RESULT_PICK_LOCATION_LON = "result_pick_location_lon"
        const val RESULT_PICK_LOCATION_LABEL = "result_pick_location_label"

        internal const val MENU_ROUTE_GPS = 1
        internal const val MENU_ROUTE_MANUAL = 2

        fun newInstance(
            noteId: Long? = null,
            blockId: Long? = null,
            mode: String? = null,
            showLibraryPins: Boolean = false,
            pickMode: Boolean = false,
        ): MapFragment =
            MapFragment().apply {
                arguments = Bundle().apply {
                    if (noteId != null && noteId > 0) putLong(ARG_NOTE_ID, noteId)
                    if (blockId != null && blockId > 0) putLong(ARG_BLOCK_ID, blockId)
                    if (!mode.isNullOrBlank()) putString(ARG_MODE, mode)
                    putBoolean(ARG_SHOW_LIBRARY_PINS, showLibraryPins)
                    putBoolean(ARG_PICK_MODE, pickMode)
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
            appCtx,
            database.noteDao(),
            attachmentDao,
            database.blockReadDao(),
            blocksRepo,
            database.listItemDao(),
            database = database
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetNoteId = savedInstanceState?.getLong(STATE_NOTE_ID, -1L)?.takeIf { it > 0 }
            ?: arguments?.getLong(ARG_NOTE_ID, -1L)?.takeIf { it > 0 }
        targetBlockId = savedInstanceState?.getLong(STATE_BLOCK_ID, -1L)?.takeIf { it > 0 }
            ?: arguments?.getLong(ARG_BLOCK_ID, -1L)?.takeIf { it > 0 }
        pickModeOverride = savedInstanceState?.getBoolean(STATE_PICK_MODE)
            ?: arguments?.getBoolean(ARG_PICK_MODE)
            ?: false
        startMode = when {
            pickModeOverride -> MapActivity.MODE_PICK_LOCATION
            else -> when (val mode = savedInstanceState?.getString(STATE_MODE) ?: arguments?.getString(ARG_MODE)) {
                MapActivity.MODE_BROWSE,
                MapActivity.MODE_CENTER_ON_HERE,
                MapActivity.MODE_FOCUS_NOTE,
                MapActivity.MODE_PICK_LOCATION -> mode
                else -> MapActivity.MODE_BROWSE
            }
        }
        setHasOptionsMenu(!isPickMode)
        Log.d(TAG, "Starting map with mode=$startMode note=$targetNoteId block=$targetBlockId")
        pendingBlockFocus = targetBlockId
        isStyleReady = false

        onTargetNoteIdChanged(targetNoteId)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        if (isPickMode) {
            super.onCreateOptionsMenu(menu, inflater)
            return
        }
        configureOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        if (isPickMode) {
            super.onPrepareOptionsMenu(menu)
            return
        }
        prepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when {
            isPickMode -> super.onOptionsItemSelected(item)
            handleOptionsItem(item) -> true
            else -> super.onOptionsItemSelected(item)
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
        initializeSearchUi(view)
        initializeControlCenter()
    }

    override fun onStart() {
        super.onStart()
        manageDebugOverlay()
    }

    override fun onMapReady(mapboxMap: MapLibreMap) {
        map = mapboxMap
        configureMap(startMode ?: MapActivity.MODE_BROWSE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_NOTE_ID, targetNoteId ?: -1L)
        outState.putLong(STATE_BLOCK_ID, targetBlockId ?: -1L)
        outState.putString(STATE_MODE, startMode)
        outState.putBoolean(STATE_PICK_MODE, pickModeOverride)
    }

    override fun onResume() {
        super.onResume()
        handleOnResume()
    }

    override fun onPause() {
        handleOnPause()
        super.onPause()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    override fun onDestroyView() {
        cleanupOnDestroyView()
        super.onDestroyView()
        _b = null
    }

    internal fun onTargetNoteIdChanged(newId: Long?) {
        handleTargetNoteIdChanged(newId)
    }

    internal fun setTargetNoteLocation(lat: Double?, lon: Double?) {
        updateTargetNoteLocation(lat, lon)
    }

    internal fun showHint(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        displayHint(text, actionLabel, onAction)
    }

    private fun displayHint(text: String, actionLabel: String?, onAction: (() -> Unit)?) {
        val hintView = bindingOrNull?.clusterHint ?: return
        hintDismissRunnable?.let { hintView.removeCallbacks(it) }

        val hasAction = !actionLabel.isNullOrBlank() && onAction != null
        hintView.text = when {
            hasAction -> "$text — ${actionLabel!!.trim()}"
            else -> text
        }
        if (hasAction) {
            hintView.isClickable = true
            hintView.setOnClickListener {
                hintDismissRunnable?.let { runnable -> hintView.removeCallbacks(runnable) }
                hintView.isVisible = false
                hintView.setOnClickListener(null)
                hintDismissRunnable = null
                onAction?.invoke()
            }
        } else {
            hintView.isClickable = false
            hintView.setOnClickListener(null)
        }

        hintView.isVisible = true

        val dismiss = Runnable {
            hintView.isVisible = false
            hintView.setOnClickListener(null)
            hintDismissRunnable = null
        }
        hintDismissRunnable = dismiss
        hintView.postDelayed(dismiss, 4_000)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        handlePermissionsResult(requestCode, permissions, grantResults)
    }

    internal fun ensureTargetNoteLocation(force: Boolean = false) {
        resolveTargetNoteLocation(force)
    }

    internal fun centerOnUserIfPossible() {
        recenterOnUserIfAvailable()
    }

    internal fun startManualRouteDrawing() {
        beginManualRoute()
    }

    internal fun finishManualRouteDrawing() {
        endManualRoute()
    }

    internal fun updateManualRouteUi() {
        refreshManualRouteUi()
    }

    internal fun handleManualMapTap(latLng: LatLng) {
        processManualRouteTap(latLng)
    }

    internal fun handleManualMapTap() {
        processManualRouteTap()
    }

    internal fun handleManualMapLongClick(latLng: LatLng): Boolean =
        processManualRouteLongClick(latLng)

    internal fun addManualRoutePoint(latLng: LatLng) {
        appendManualRoutePoint(latLng)
    }

    internal fun onManualRouteUndoClicked() {
        handleManualRouteUndo()
    }

    internal fun onManualRouteCancelClicked() {
        handleManualRouteCancel()
    }

    internal fun saveManualRoute() {
        persistManualRoute()
    }

    internal fun buildManualRoutePayload(): RoutePayload? =
        createManualRoutePayload()

    internal fun buildManualRouteMirrorText(payload: RoutePayload): String =
        createManualRouteMirrorText(payload)
}
