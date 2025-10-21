package com.example.openeer.ui.library

import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Toast
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.core.widget.addTextChangedListener
import androidx.annotation.StringRes
import com.example.openeer.R
import com.example.openeer.core.LocationPerms
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
import com.example.openeer.domain.ReminderUseCases
import com.example.openeer.data.block.BlockType
import com.example.openeer.ui.map.MapCamera
import com.example.openeer.ui.map.MapClusters
import com.example.openeer.ui.map.MapIcons
import com.example.openeer.ui.map.MapManagers
import com.example.openeer.ui.map.MapPin
import com.example.openeer.ui.map.MapPolylines
import com.example.openeer.ui.map.MapRenderers
import com.example.openeer.ui.map.MapStyleIds
import com.example.openeer.ui.map.MapUiDefaults
import com.example.openeer.ui.map.RecentHere
import com.example.openeer.ui.sheets.BottomSheetReminderPicker
import com.example.openeer.ui.sheets.MapSnapshotSheet
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.plugins.annotation.Line
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.geojson.Feature
import java.util.Locale
import kotlin.math.max

// ----------------------------------------------------------------------
// Utilitaires de trace (réutilisés dans les autres fichiers du package)
// ----------------------------------------------------------------------
object MapSnapDiag {
    const val TAG = "MapSnapDiag"
    inline fun trace(msg: () -> String) { Log.d(TAG, msg()) }
    class Ticker {
        private val t0 = android.os.SystemClock.elapsedRealtime()
        fun ms() = android.os.SystemClock.elapsedRealtime() - t0
    }
}

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
    // RouteRecorder supprimé (service désormais)
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

    private lateinit var searchInput: MaterialAutoCompleteTextView
    private var searchAdapter: ArrayAdapter<String>? = null
    private var searchJob: Job? = null
    private var searchExecutionJob: Job? = null
    private var searchResults: List<Address> = emptyList()

    private sealed interface GeocodeOutcome {
        data class Success(val addresses: List<Address>) : GeocodeOutcome
        data object Unavailable : GeocodeOutcome
        data class Failure(val throwable: Throwable) : GeocodeOutcome
    }

    private var backgroundPermissionDialog: AlertDialog? = null

    private var pendingGeo: (() -> Unit)? = null
    private var waitingBgSettingsReturn = false

    // Mémoire
    internal var allNotes: List<Note> = emptyList()
    internal var lastFeatures: List<Feature>? = null

    private var targetNoteLocation: LatLng? = null
    private var targetNoteLocationResolved = false
    private var targetNoteLocationLoading = false
    private var targetNoteLocationNoteId: Long? = null

    // Permission localisation
    internal val REQ_LOC = 1001
    internal val REQ_ROUTE = 1002
    internal val REQ_FAVORITE = 1003

    companion object {
        private const val MANUAL_ROUTE_MAX_POINTS = 120
        private const val MANUAL_ROUTE_LOG_TAG = "ManualRoute"
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

        // Zoom utilisé pour centrer/relire les rues (focus bloc)
        private const val POINT_FOCUS_ZOOM = 17.6

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
        super.onCreateOptionsMenu(menu, inflater)
        menu.add(0, MENU_CREATE_REMINDER, 0, getString(R.string.map_menu_create_reminder_time)).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            isEnabled = targetNoteId != null
        }
        menu.add(0, MENU_CREATE_REMINDER_GEO_ONCE, 1, getString(R.string.map_menu_create_reminder_geo_once)).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            isEnabled = targetNoteId != null && targetNoteLocation != null
        }
        menu.add(0, MENU_CREATE_REMINDER_GEO_EVERY, 2, getString(R.string.map_menu_create_reminder_geo_every)).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            isEnabled = targetNoteId != null && targetNoteLocation != null
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        if (isPickMode) {
            super.onPrepareOptionsMenu(menu)
            return
        }
        super.onPrepareOptionsMenu(menu)
        menu.findItem(MENU_CREATE_REMINDER)?.isEnabled = targetNoteId != null
        val hasGeoLocation = targetNoteId != null && targetNoteLocation != null
        menu.findItem(MENU_CREATE_REMINDER_GEO_ONCE)?.isEnabled = hasGeoLocation
        menu.findItem(MENU_CREATE_REMINDER_GEO_EVERY)?.isEnabled = hasGeoLocation
        if (targetNoteId != null) {
            ensureTargetNoteLocation()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_CREATE_REMINDER -> {
                val noteId = targetNoteId
                if (noteId != null) {
                    BottomSheetReminderPicker.newInstance(noteId)
                        .show(parentFragmentManager, "reminder_picker")
                } else {
                    context?.let { ctx ->
                        Toast.makeText(ctx, getString(R.string.invalid_note_id), Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
            MENU_CREATE_REMINDER_GEO_ONCE -> {
                val noteId = targetNoteId
                if (noteId == null) {
                    Log.e(TAG, "GeoReminder: invalid noteId (null)")
                    return true
                }

                val location = targetNoteLocation ?: map?.cameraPosition?.target
                if (location == null) {
                    Log.e(TAG, "GeoReminder: missing target location for note=$noteId")
                    return true
                }

                Log.d(
                    TAG,
                    "GeoReminder request every=false for note=$noteId at ${location.latitude},${location.longitude} r=100m"
                )

                createGeoReminderStaged(
                    noteId = noteId,
                    lat = location.latitude,
                    lon = location.longitude,
                    radius = 100,
                    every = false
                )

                true
            }
            MENU_CREATE_REMINDER_GEO_EVERY -> {
                val noteId = targetNoteId
                if (noteId == null) {
                    Log.e(TAG, "GeoReminder: invalid noteId (null)")
                    return true
                }

                val location = targetNoteLocation ?: map?.cameraPosition?.target
                if (location == null) {
                    Log.e(TAG, "GeoReminder: missing target location for note=$noteId")
                    return true
                }

                Log.d(
                    TAG,
                    "GeoReminder request every=true for note=$noteId at ${location.latitude},${location.longitude} r=100m"
                )

                createGeoReminderStaged(
                    noteId = noteId,
                    lat = location.latitude,
                    lon = location.longitude,
                    radius = 100,
                    every = true
                )

                true
            }
            else -> super.onOptionsItemSelected(item)
        }
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

        searchInput = view.findViewById(R.id.searchInput)
        searchAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, mutableListOf())
        searchInput.setAdapter(searchAdapter)

        // écoute la saisie avec un petit délai (debounce)
        searchInput.addTextChangedListener(afterTextChanged = { text ->
            val q = text?.toString()?.trim().orEmpty()
            searchJob?.cancel()
            searchExecutionJob?.cancel()
            if (q.length < 3) {
                searchAdapter?.clear()
                searchResults = emptyList()
                clearSearchFeedback()
                searchInput.dismissDropDown()
                return@addTextChangedListener
            }
            clearSearchFeedback()
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(250) // attend un quart de seconde avant la recherche
                fetchSuggestions(q)
            }
        })

        searchInput.setOnEditorActionListener(TextView.OnEditorActionListener { textView, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@OnEditorActionListener false
            val query = textView.text?.toString()?.trim().orEmpty()
            if (query.length < 3) {
                searchInput.dismissDropDown()
                return@OnEditorActionListener true
            }
            launchDirectSearch(query)
            true
        })

        // quand l’utilisateur clique sur une suggestion
        searchInput.setOnItemClickListener { _, _, position, _ ->
            val addr = searchResults.getOrNull(position) ?: return@setOnItemClickListener
            val lat = addr.latitude
            val lon = addr.longitude
            val label = addr.getAddressLine(0) ?: addr.featureName ?: String.format(Locale.US, "%.5f, %.5f", lat, lon)
            clearSearchFeedback()
            searchInput.dismissDropDown()
            showSelectionFromSearch(
                com.example.openeer.core.Place(
                    lat = lat,
                    lon = lon,
                    label = label,
                    accuracyM = null
                )
            )
        }

        // Boutons UI
        b.btnZoomIn.setOnClickListener { map?.animateCamera(CameraUpdateFactory.zoomIn()) }
        b.btnZoomOut.setOnClickListener { map?.animateCamera(CameraUpdateFactory.zoomOut()) }
        b.btnRecenter.setOnClickListener { recenterToUserOrAll() }
        b.btnFavoriteHere.setOnClickListener { onFavoriteHereClicked() }
        b.btnFavoriteHere.isVisible = !isPickMode
        recordingRouteLine = MapPolylines.clearRecordingLine(polylineManager, recordingRouteLine)
        val showLocationActions = shouldShowLocationActions
        b.locationActions.isVisible = showLocationActions
        if (showLocationActions) {
            b.btnAddHere.isEnabled = false
            b.btnAddHere.setOnClickListener { onAddHereClicked() }
            b.btnRecordRoute.isEnabled = false
            b.btnRecordRoute.setOnClickListener { onRouteButtonClicked() }
            b.manualRouteHint.isClickable = false
            b.manualRouteHint.isLongClickable = false
            b.btnUndoManualRoute.isVisible = false
            b.btnUndoManualRoute.setOnClickListener { onManualRouteUndoClicked() }
            b.btnCancelManualRoute.isVisible = false
            b.btnCancelManualRoute.setOnClickListener { onManualRouteCancelClicked() }
            refreshRouteButtonState()

            parentFragmentManager.setFragmentResultListener(RESULT_MANUAL_ROUTE, viewLifecycleOwner) { _, bundle ->
                val lat = bundle.getDouble(RESULT_MANUAL_ROUTE_LAT, Double.NaN)
                val lon = bundle.getDouble(RESULT_MANUAL_ROUTE_LON, Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return@setFragmentResultListener
                val label = bundle.getString(RESULT_MANUAL_ROUTE_LABEL)
                // Pré-positionne le seed pour le mode manuel puis démarre sans arguments
                selectionLatLng = LatLng(lat, lon)
                manualAnchorLabel = label
                startManualRouteDrawing()
            }

            // 🔗 Branche la logique Route (service + receivers + libellé bouton)
            setupRouteUiBindings()
        }
    }

    private suspend fun fetchSuggestions(query: String) {
        when (val outcome = geocode(query, 8)) {
            is GeocodeOutcome.Success -> {
                val results = outcome.addresses
                val labels = updateSuggestionAdapter(results)
                if (labels.isNotEmpty()) {
                    clearSearchFeedback()
                    searchInput.showDropDown()
                } else {
                    showSearchHelper(R.string.map_search_no_results)
                    searchInput.dismissDropDown()
                }
            }
            GeocodeOutcome.Unavailable -> {
                updateSuggestionAdapter(emptyList())
                showSearchError(R.string.map_search_unavailable)
                searchInput.dismissDropDown()
            }
            is GeocodeOutcome.Failure -> {
                updateSuggestionAdapter(emptyList())
                showSearchError(R.string.map_search_error)
                searchInput.dismissDropDown()
            }
        }
    }

    private fun launchDirectSearch(query: String) {
        searchExecutionJob?.cancel()
        searchExecutionJob = viewLifecycleOwner.lifecycleScope.launch {
            when (val outcome = geocode(query, 8)) {
                is GeocodeOutcome.Success -> {
                    val results = outcome.addresses
                    if (results.isEmpty()) {
                        updateSuggestionAdapter(emptyList())
                        showSearchHelper(R.string.map_search_no_results)
                        searchInput.dismissDropDown()
                        return@launch
                    }

                    val labels = updateSuggestionAdapter(results)
                    clearSearchFeedback()
                    searchInput.dismissDropDown()

                    val addr = results.first()
                    val lat = addr.latitude
                    val lon = addr.longitude
                    val label = addr.getAddressLine(0) ?: addr.featureName ?: String.format(Locale.US, "%.5f, %.5f", lat, lon)
                    if (labels.isNotEmpty()) {
                        searchInput.setText(label, false)
                    }
                    showSelectionFromSearch(
                        com.example.openeer.core.Place(
                            lat = lat,
                            lon = lon,
                            label = label,
                            accuracyM = null
                        )
                    )
                }
                GeocodeOutcome.Unavailable -> {
                    updateSuggestionAdapter(emptyList())
                    showSearchError(R.string.map_search_unavailable)
                    searchInput.dismissDropDown()
                }
                is GeocodeOutcome.Failure -> {
                    updateSuggestionAdapter(emptyList())
                    showSearchError(R.string.map_search_error)
                    searchInput.dismissDropDown()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun geocode(query: String, maxResults: Int): GeocodeOutcome {
        if (!Geocoder.isPresent()) {
            Log.w(TAG, "Geocoder unavailable on this device")
            return GeocodeOutcome.Unavailable
        }

        val ctx = requireContext().applicationContext
        return withContext(Dispatchers.IO) {
            val geocoder = Geocoder(ctx, Locale.getDefault())
            runCatching {
                geocoder.getFromLocationName(query, maxResults)
            }.fold(
                onSuccess = { GeocodeOutcome.Success(it.orEmpty()) },
                onFailure = {
                    Log.w(TAG, "Geocoder failed for \"$query\"", it)
                    GeocodeOutcome.Failure(it)
                }
            )
        }
    }

    private fun updateSuggestionAdapter(results: List<Address>): List<String> {
        searchResults = results
        val labels = results.map { it.getAddressLine(0) ?: it.featureName ?: "${it.latitude}, ${it.longitude}" }
        searchAdapter?.apply {
            clear()
            addAll(labels)
            notifyDataSetChanged()
        }
        return labels
    }

    private fun showSearchError(@StringRes resId: Int) {
        b.searchContainer.error = getString(resId)
        b.searchContainer.helperText = null
    }

    private fun showSearchHelper(@StringRes resId: Int) {
        b.searchContainer.helperText = getString(resId)
        b.searchContainer.error = null
    }

    private fun clearSearchFeedback() {
        b.searchContainer.error = null
        b.searchContainer.helperText = null
    }

    override fun onStart() {
        super.onStart()
        val ctx = context ?: return
        if (!RouteDebugPreferences.shouldExecuteOverlayCode(ctx)) {
            MapUiDefaults.DEBUG_ROUTE = false
            RouteDebugOverlay.hide(this)
        } else {
            RouteDebugPreferences.refreshDebugFlag(ctx)
            if (!MapUiDefaults.DEBUG_ROUTE) {
                RouteDebugOverlay.hide(this)
            }
        }
    }

    override fun onMapReady(mapboxMap: MapLibreMap) {
        map = mapboxMap
        isStyleReady = false
        val initialMode = startMode ?: MapActivity.MODE_BROWSE

        MapSnapDiag.trace { "MF: onMapReady() — initialMode=$initialMode" }

        val styleUrl = "https://tiles.basemaps.cartocdn.com/gl/positron-gl-style/style.json"
        map?.setStyle(styleUrl) { style ->
            MapSnapDiag.trace { "MF: style loaded ($styleUrl)" }

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
            locationPins.values.forEach { it.symbol = null }
            resetUserLocationSymbolForNewManager()
            refreshPins()
            renderUserLocationSymbol()

            polylineManager?.onDestroy()
            polylineManager = MapManagers.createLineManager(mv, mapInstance, style)
            recordingRouteLine = null
            manualRouteLine = null
            if (isManualRouteMode) {
                manualRouteLine = MapPolylines.updateManualRoutePolyline(polylineManager, manualRouteLine, manualPoints)
            }

            b.locationActions.isVisible = shouldShowLocationActions
            if (shouldShowLocationActions) {
                b.btnAddHere.isEnabled = true
                b.btnRecordRoute.isEnabled = true
            } else {
                b.btnAddHere.isEnabled = false
                b.btnRecordRoute.isEnabled = false
            }
            b.btnFavoriteHere.isVisible = !isPickMode
            b.btnFavoriteHere.isEnabled = true

            if (initialMode == MapActivity.MODE_BROWSE) {
                // Centrage direct (pas d'animation) pour qu'un snapshot immédiat capture *exactement* ce qui est visible
                centerOnUserIfPossible()
            }

            // Charge les notes brutes (no-op si showLibraryPins = false ; cf. MapFragmentNotes.kt)
            MapSnapDiag.trace { "MF: loadNotesThenRender()…" }
            loadNotesThenRender()

            // Recalcule l’agrégation quand la caméra s’arrête (Library uniquement)
            val showPins = arguments?.getBoolean(ARG_SHOW_LIBRARY_PINS, false) == true
            map?.addOnCameraIdleListener {
                if (showPins) {
                    MapSnapDiag.trace { "MF: camera idle → renderGroupsForCurrentZoom (notes=${allNotes.size})" }
                    MapClusters.renderGroupsForCurrentZoom(map, allNotes)
                }
                maybeDismissSelectionOnPan()
            }

            isStyleReady = true
            applyPendingBlockFocus() // focus bloc au démarrage
            applyStartMode(initialMode)
            maybeStartUserLocationTracking()
        }

        // Tap carte
        map?.addOnMapClickListener { latLng ->
            if (isManualRouteMode) {
                handleManualMapTap(latLng)
                return@addOnMapClickListener true
            }
            if (isPickMode) {
                handleMapSelectionTap(latLng)
                return@addOnMapClickListener true
            }

            val screenPt = map?.projection?.toScreenLocation(latLng)
            val features = screenPt?.let { screen ->
                map?.queryRenderedFeatures(screen, MapStyleIds.LYR_NOTES).orEmpty()
            }.orEmpty()
            val clusterFeature = features.firstOrNull()
            if (clusterFeature != null) {
                val lat = clusterFeature.getNumberProperty("lat")?.toDouble()
                val lon = clusterFeature.getNumberProperty("lon")?.toDouble()
                val title = clusterFeature.getStringProperty("title") ?: "Lieu"
                val count = clusterFeature.getNumberProperty("count")?.toInt() ?: 1

                if (lat != null && lon != null) {
                    val currentZoom = map?.cameraPosition?.zoom ?: 5.0
                    val targetZoom = max(currentZoom, 13.5)
                    map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), targetZoom))

                    showHint("$title — $count note(s)")

                    NotesBottomSheet.newInstance(lat, lon, title)
                        .show(parentFragmentManager, "notes_sheet")
                    return@addOnMapClickListener true
                }
            }

            if (handleMapSelectionTap(latLng)) {
                return@addOnMapClickListener true
            }

            false
        }

        map?.addOnMapLongClickListener { latLng ->
            when {
                isManualRouteMode -> {
                    handleManualMapLongClick(latLng)
                    true
                }
                !isPickMode -> {
                    handleMapSelectionTap(latLng)
                    true
                }
                else -> false
            }
        }
    }

    internal fun showHint(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        val hintView = binding.clusterHint
        val fullText = if (actionLabel != null) "$text\n$actionLabel" else text
        hintView.animate().cancel()
        hintDismissRunnable?.let { hintView.removeCallbacks(it) }
        hintView.text = fullText
        hintView.visibility = View.VISIBLE
        hintView.alpha = 0f
        hintView.setOnClickListener(null)
        hintView.isClickable = onAction != null
        hintView.isFocusable = onAction != null
        if (onAction != null) hintView.setOnClickListener { onAction() }
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
        if (pickModeOverride) {
            outState.putBoolean(STATE_PICK_MODE, true)
        }
    }

    // MapView lifecycle
    override fun onResume() {
        super.onResume()
        if (waitingBgSettingsReturn) {
            waitingBgSettingsReturn = false
            val ctx = requireContext().applicationContext
            val nowHasBg = !LocationPerms.requiresBackground(ctx) || LocationPerms.hasBackground(ctx)
            Log.d(TAG, "GeoFlow onResume ← from Settings, hasBG=$nowHasBg")
            if (nowHasBg) {
                pendingGeo?.invoke()
                pendingGeo = null
            } else {
                Log.w(TAG, "GeoFlow onResume: BG still missing, not scheduling geofence")
                pendingGeo = null
            }
        }
        mapView?.onResume()
        maybeStartUserLocationTracking()
    }
    override fun onPause() {
        // ❌ on ne stoppe plus l’enregistrement ici : le service gère la vie en arrière-plan
        stopUserLocationTracking(clearLocation = false)
        mapView?.onPause()
        super.onPause()
    }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onDestroyView() {
        // ❌ idem : ne pas stopper le service ici
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
        stopUserLocationTracking(clearLocation = true)
        searchJob?.cancel()
        searchJob = null
        searchAdapter = null
        searchResults = emptyList()
        searchExecutionJob?.cancel()
        searchExecutionJob = null
        _b?.clusterHint?.let { hintView ->
            hintDismissRunnable?.let { hintView.removeCallbacks(it) }
        }
        hintDismissRunnable = null
        backgroundPermissionDialog?.dismiss()
        backgroundPermissionDialog = null
        super.onDestroyView()
        _b = null
    }

    internal fun onTargetNoteIdChanged(newId: Long?) {
        if (targetNoteLocationNoteId != newId) {
            targetNoteLocation = null
            targetNoteLocationResolved = false
            targetNoteLocationLoading = false
            targetNoteLocationNoteId = newId
        }
        activity?.invalidateOptionsMenu()
        if (newId != null) {
            ensureTargetNoteLocation()
        }
    }

    internal fun setTargetNoteLocation(lat: Double?, lon: Double?) {
        targetNoteLocation = if (lat != null && lon != null) LatLng(lat, lon) else null
        targetNoteLocationResolved = true
        targetNoteLocationLoading = false
        targetNoteLocationNoteId = targetNoteId
        activity?.invalidateOptionsMenu()
    }

    private fun ensureTargetNoteLocation(force: Boolean = false) {
        val noteId = targetNoteId
        if (noteId == null) {
            if (targetNoteLocation != null || targetNoteLocationResolved) {
                targetNoteLocation = null
                targetNoteLocationResolved = false
                targetNoteLocationLoading = false
                targetNoteLocationNoteId = null
                activity?.invalidateOptionsMenu()
            }
            return
        }

        if (!force && targetNoteLocationResolved && targetNoteLocationNoteId == noteId) return
        if (targetNoteLocationLoading && targetNoteLocationNoteId == noteId) return

        targetNoteLocationLoading = true
        targetNoteLocationNoteId = noteId
        lifecycleScope.launch {
            val location = withContext(Dispatchers.IO) {
                val note = noteRepo.noteOnce(noteId)
                val lat = note?.lat
                val lon = note?.lon
                if (lat != null && lon != null) LatLng(lat, lon) else null
            }
            targetNoteLocation = location
            targetNoteLocationResolved = true
            targetNoteLocationLoading = false
            activity?.invalidateOptionsMenu()
        }
    }

    private fun createGeoReminderStaged(
        noteId: Long,
        lat: Double,
        lon: Double,
        radius: Int,
        every: Boolean
    ) {
        val ctx = requireContext().applicationContext
        Log.d(TAG, "GeoFlow start → note=$noteId lat=$lat lon=$lon radius=$radius every=$every")

        LocationPerms.dump(ctx)
        if (!LocationPerms.hasFine(ctx)) {
            Log.d(TAG, "GeoFlow ensureForeground → requesting FINE")
            LocationPerms.requestFine(this, object : LocationPerms.Callback {
                override fun onResult(granted: Boolean) {
                    Log.d(TAG, "GeoFlow ensureForeground → $granted")
                    if (granted) {
                        createGeoReminderStaged(noteId, lat, lon, radius, every)
                    } else {
                        Log.w(TAG, "GeoFlow aborted: FINE denied")
                    }
                }
            })
            return
        }

        LocationPerms.dump(ctx)
        if (LocationPerms.requiresBackground(ctx) && !LocationPerms.hasBackground(ctx)) {
            Log.d(TAG, "GeoFlow ensureBackground → missing BG, preparing staged flow")
            showBackgroundPermissionDialog(
                onAccept = {
                    if (LocationPerms.mustOpenSettingsForBackground()) {
                        Log.d(TAG, "GeoFlow ensureBackground → launching Settings")
                        waitingBgSettingsReturn = true
                        pendingGeo = { createGeoReminderStaged(noteId, lat, lon, radius, every) }
                        LocationPerms.launchSettingsForBackground(this)
                    } else {
                        Log.d(TAG, "GeoFlow ensureBackground → direct requestPermissions(BG) API29")
                        LocationPerms.requestBackground(this, object : LocationPerms.Callback {
                            override fun onResult(granted: Boolean) {
                                Log.d(TAG, "GeoFlow ensureBackground (API29) → $granted")
                                if (granted) {
                                    createGeoReminderStaged(noteId, lat, lon, radius, every)
                                } else {
                                    Log.w(TAG, "GeoFlow aborted: BG denied")
                                }
                            }
                        })
                    }
                },
                onCancel = {
                    Log.w(TAG, "GeoFlow cancelled by user at BG rationale")
                    pendingGeo = null
                }
            )
            return
        }

        Log.d(TAG, "GeoFlow → permissions OK, scheduling geofence…")
        val coordsLabel = String.format(Locale.US, "%.5f,%.5f", lat, lon)
        Log.i(
            TAG,
            "[GEOFENCE] ENTER programmé (note=$noteId latLon=$coordsLabel every=$every radius=$radius)"
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.getInstance(requireContext())
            val alarm = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val use = ReminderUseCases(requireContext().applicationContext, db, alarm)
            val id = withContext(Dispatchers.IO) {
                use.scheduleGeofence(
                    noteId = noteId,
                    lat = lat,
                    lon = lon,
                    radiusMeters = radius,
                    every = every,
                )
            }
            Log.d(TAG, "GeoFlow → scheduleGeofence done id=$id")
        }
    }

    private fun showBackgroundPermissionDialog(onAccept: () -> Unit, onCancel: () -> Unit) {
        if (!isAdded) return
        backgroundPermissionDialog?.dismiss()
        val positiveRes = if (Build.VERSION.SDK_INT >= 30) {
            R.string.map_background_location_positive_settings
        } else {
            R.string.map_background_location_positive_request
        }
        backgroundPermissionDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.map_background_location_title)
            .setMessage(R.string.map_background_location_message)
            .setPositiveButton(positiveRes) { _, _ ->
                Log.d(TAG, "GeoFlow: background permission dialog positive")
                onAccept()
            }
            .setNegativeButton(R.string.map_background_location_negative) { _, _ ->
                Log.d(TAG, "GeoFlow: background permission dialog negative")
                onCancel()
            }
            .setOnCancelListener {
                Log.d(TAG, "GeoFlow: background permission dialog canceled")
                onCancel()
            }
            .setOnDismissListener { backgroundPermissionDialog = null }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        LocationPerms.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_ROUTE) {
            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            awaitingRoutePermission = false
            if (granted) startRouteRecording() else {
                val shouldShowRationale = permissions.any { shouldShowRequestPermissionRationale(it) }
                if (shouldShowRationale) showHint(getString(R.string.map_location_permission_needed)) else showLocationDisabledHint()
            }
            return
        }
        if (requestCode == REQ_FAVORITE) {
            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            awaitingFavoritePermission = false
            if (granted) {
                onFavoriteHerePermissionGranted()
            } else {
                context?.let {
                    Toast.makeText(it, getString(R.string.map_favorite_here_permission_denied), Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        if (requestCode == REQ_LOC) {
            if (awaitingHerePermission) {
                val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
                awaitingHerePermission = false
                if (granted) {
                    maybeStartUserLocationTracking()
                    onAddHereClicked()
                } else {
                    val shouldShowRationale = permissions.any { shouldShowRequestPermissionRationale(it) }
                    if (shouldShowRationale) showHint(getString(R.string.map_location_permission_needed)) else showLocationDisabledHint()
                    onLocationPermissionLost()
                }
                return
            }
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                maybeStartUserLocationTracking()
                recenterToUserOrAll()
            }
            if (grantResults.all { it == PackageManager.PERMISSION_DENIED }) {
                val shouldShowRationale = permissions.any { shouldShowRequestPermissionRationale(it) }
                if (!shouldShowRationale) showLocationDisabledHint()
                onLocationPermissionLost()
            }
        }
    }

    private fun centerOnUserIfPossible() {
        val ctx = context ?: return
        val locationManager = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: return

        val hasFine = requireContext().checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val hasCoarse = requireContext().checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            MapCamera.moveCameraToDefault(map)
            return
        }

        val lastKnown = locationManager.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)

        if (lastKnown != null) {
            val latLng = LatLng(lastKnown.latitude, lastKnown.longitude)
            val STREET_READABLE_ZOOM = 17.6
            map?.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, STREET_READABLE_ZOOM))
        } else {
            MapCamera.moveCameraToDefault(map)
        }
    }

    // ========= Focus bloc au démarrage =========
    private fun applyPendingBlockFocus() {
        val blockId = pendingBlockFocus ?: return
        if (!isStyleReady || map == null) return

        // éviter multiples recentrages
        pendingBlockFocus = null

        viewLifecycleOwner.lifecycleScope.launchWhenStarted {
            val block = withContext(Dispatchers.IO) { blocksRepo.getBlock(blockId) } ?: return@launchWhenStarted

            when (block.type) {
                BlockType.LOCATION -> {
                    val lat = block.lat
                    val lon = block.lon
                    if (lat != null && lon != null) {
                        map?.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 17.6)
                        )
                    }
                }
                BlockType.ROUTE -> {
                    val payload = block.routeJson?.let {
                        runCatching { routeGson.fromJson(it, RoutePayload::class.java) }.getOrNull()
                    }
                    val pts = payload?.points
                    if (!pts.isNullOrEmpty()) {
                        MapRenderers.fitToRoute(map, pts, requireContext())
                    } else {
                        val lat = block.lat
                        val lon = block.lon
                        if (lat != null && lon != null) {
                            map?.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 17.6)
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun applyStartMode(initialMode: String) {
        when (initialMode) {
            MapActivity.MODE_CENTER_ON_HERE -> recenterToUserOrAll()
            MapActivity.MODE_PICK_LOCATION -> Unit
            else -> Unit
        }
    }

    private fun startManualRouteDrawing() {
        if (!shouldShowLocationActions) return
        if (isManualRouteMode) return
        Log.d(MANUAL_ROUTE_LOG_TAG, "startManualRouteDrawing(anchor=${manualAnchorLabel ?: ""})")
        isManualRouteMode = true
        isSavingRoute = false
        manualPoints.clear()
        manualRouteLine = MapPolylines.clearManualRouteLine(polylineManager, manualRouteLine)
        updateManualRouteUi()
    }

    private fun finishManualRouteDrawing() {
        if (!isManualRouteMode && manualPoints.isEmpty()) return
        Log.d(MANUAL_ROUTE_LOG_TAG, "finishManualRouteDrawing()")
        isManualRouteMode = false
        isSavingRoute = false
        manualRouteLine = MapPolylines.clearManualRouteLine(polylineManager, manualRouteLine)
        manualPoints.clear()
        manualAnchorLabel = null
        updateManualRouteUi()
        refreshRouteButtonState()
    }

    private fun updateManualRouteUi() {
        val binding = _b ?: return
        if (!shouldShowLocationActions) {
            binding.locationActions.isVisible = false
            binding.manualRouteHint.isVisible = false
            return
        }
        val inManualMode = isManualRouteMode
        val pointCount = manualPoints.size
        val saving = isSavingRoute

        if (inManualMode) {
            val saveLabel = getString(R.string.map_manual_route_save, pointCount)
            binding.btnRecordRoute.text = saveLabel
            binding.btnRecordRoute.contentDescription = getString(R.string.map_manual_route_save_cd)
            binding.btnRecordRoute.isEnabled = pointCount >= 2 && !saving
            binding.btnCancelManualRoute.isVisible = true
            binding.btnCancelManualRoute.isEnabled = !saving
            binding.btnUndoManualRoute.isVisible = true
            binding.btnUndoManualRoute.isEnabled = pointCount > 0 && !saving
            binding.manualRouteHint.isVisible = true
            val anchor = manualAnchorLabel
            val hint = buildString {
                append(getString(R.string.map_manual_route_hint))
                if (!anchor.isNullOrBlank()) {
                    append('\n')
                    append(getString(R.string.map_manual_route_anchor_format, anchor))
                }
            }
            binding.manualRouteHint.text = hint
        } else {
            binding.manualRouteHint.isVisible = false
            binding.btnCancelManualRoute.isVisible = false
            binding.btnUndoManualRoute.isVisible = false
            binding.btnUndoManualRoute.isEnabled = false
            binding.btnCancelManualRoute.isEnabled = false
        }
    }

    private fun handleManualMapTap(latLng: LatLng) {
        if (!isManualRouteMode || isSavingRoute) return
        if (manualPoints.size >= MANUAL_ROUTE_MAX_POINTS) {
            showHint(getString(R.string.map_manual_route_limit, MANUAL_ROUTE_MAX_POINTS))
            return
        }
        addManualRoutePoint(latLng)
    }

    @Suppress("unused")
    private fun handleManualMapTap() {
        // Appels éventuels via réflexion : pas d'action spécifique.
    }

    private fun handleManualMapLongClick(latLng: LatLng): Boolean {
        if (!isManualRouteMode) return false
        if (isSavingRoute) return true
        if (manualPoints.size >= MANUAL_ROUTE_MAX_POINTS) {
            showHint(getString(R.string.map_manual_route_limit, MANUAL_ROUTE_MAX_POINTS))
            return true
        }
        addManualRoutePoint(latLng)
        Log.d(
            MANUAL_ROUTE_LOG_TAG,
            "onMapLongClick(lat=${latLng.latitude}, lon=${latLng.longitude}) count=${manualPoints.size}"
        )
        return true
    }

    private fun addManualRoutePoint(latLng: LatLng) {
        manualPoints.add(latLng)
        manualRouteLine = MapPolylines.updateManualRoutePolyline(polylineManager, manualRouteLine, manualPoints)
        updateManualRouteUi()
    }

    private fun onManualRouteUndoClicked() {
        if (!isManualRouteMode || manualPoints.isEmpty() || isSavingRoute) return
        manualPoints.removeLast()
        manualRouteLine = MapPolylines.updateManualRoutePolyline(polylineManager, manualRouteLine, manualPoints)
        updateManualRouteUi()
    }

    private fun onManualRouteCancelClicked() {
        if (!isManualRouteMode) return
        Log.d(MANUAL_ROUTE_LOG_TAG, "cancelManualRoute()")
        finishManualRouteDrawing()
    }

    private fun saveManualRoute() {
        if (!isManualRouteMode || isSavingRoute) return
        val payload = buildManualRoutePayload()
        if (payload == null) {
            showHint(getString(R.string.map_route_too_short))
            return
        }
        val mirrorText = buildManualRouteMirrorText(payload)
        isSavingRoute = true
        updateManualRouteUi()
        Log.d(MANUAL_ROUTE_LOG_TAG, "saveManualRoute(count=${payload.pointCount})")

        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                MapData.persistRoute(noteRepo, blocksRepo, routeGson, targetNoteId, payload, mirrorText)
            }

            if (result == null) {
                Log.w(MANUAL_ROUTE_LOG_TAG, "persistRoute returned null")
                showHint(getString(R.string.map_route_save_failed))
                isSavingRoute = false
                updateManualRouteUi()
                return@launch
            }

            targetNoteId = result.noteId
            onTargetNoteIdChanged(result.noteId)
            val firstPoint = payload.points.firstOrNull()
            if (firstPoint != null) {
                setTargetNoteLocation(firstPoint.lat, firstPoint.lon)
            }
            refreshNotesAsync()

            val ctx = context
            if (ctx != null) {
                MapRenderers.fitToRoute(map, payload.points, ctx)
            }
            captureRoutePreview(result) {
                if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    MapSnapshotSheet.show(parentFragmentManager, result.routeBlockId)
                }
            }

            showHint(getString(R.string.map_route_saved))
            finishManualRouteDrawing()
        }
    }

    private fun buildManualRoutePayload(): RoutePayload? {
        if (manualPoints.size < 2) return null
        val startedAt = System.currentTimeMillis()
        val points = manualPoints.mapIndexed { index, latLng ->
            RoutePointPayload(
                lat = latLng.latitude,
                lon = latLng.longitude,
                t = startedAt + index
            )
        }
        return RoutePayload(
            startedAt = startedAt,
            endedAt = startedAt + manualPoints.lastIndex,
            points = points
        )
    }

    private fun buildManualRouteMirrorText(payload: RoutePayload): String {
        val base = getString(R.string.map_manual_route_mirror_format, payload.pointCount)
        val anchor = manualAnchorLabel
        return if (!anchor.isNullOrBlank()) {
            base + "\n" + getString(R.string.map_manual_route_anchor_format, anchor)
        } else {
            base
        }
    }
}
