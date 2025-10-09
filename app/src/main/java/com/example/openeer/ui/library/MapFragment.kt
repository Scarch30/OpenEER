package com.example.openeer.ui.library

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.data.NoteRepository
import com.example.openeer.data.block.BlockEntity
import com.example.openeer.data.block.BlockType
import com.example.openeer.data.block.BlocksRepository
import com.example.openeer.data.block.RoutePayload
import com.example.openeer.data.block.RoutePointPayload
import com.example.openeer.core.Place
import com.example.openeer.core.getOneShotPlace
import com.example.openeer.databinding.FragmentMapBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import com.google.gson.Gson
import org.maplibre.android.plugins.annotation.Line
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.*
import java.util.Locale

class MapFragment : Fragment(), OnMapReadyCallback {

    private var _b: FragmentMapBinding? = null
    private val b get() = _b!!

    private var mapView: MapView? = null
    private var map: MapLibreMap? = null

    // Style IDs
    private val SRC_NOTES = "notes-source"
    private val LYR_NOTES = "notes-layer"
    private val ICON_SINGLE = "icon-single"
    private val ICON_FEW = "icon-few"
    private val ICON_MANY = "icon-many"
    private val ICON_HERE = "icon-here"

    // Repos
    private lateinit var database: AppDatabase
    private lateinit var blocksRepo: BlocksRepository
    private lateinit var noteRepo: NoteRepository

    private var targetNoteId: Long? = null
    private var targetBlockId: Long? = null
    private var pendingBlockFocus: Long? = null
    private var startMode: String? = null
    private var isStyleReady = false

    private var awaitingHerePermission = false
    private var lastHereLocation: RecentHere? = null
    private var hintDismissRunnable: Runnable? = null
    private var hasRequestedLocationPermission = false

    private var symbolManager: SymbolManager? = null
    private var polylineManager: LineManager? = null
    private var recordingRouteLine: Line? = null
    private var routeRecorder: RouteRecorder? = null
    private var awaitingRoutePermission = false
    private var isSavingRoute = false
    private val routeGson by lazy { Gson() }

    private data class MapPin(val lat: Double, val lon: Double, var symbol: Symbol? = null)

    private data class LocationAddResult(
        val noteId: Long,
        val locationBlockId: Long,
        val mirrorBlockId: Long,
        val previousLat: Double?,
        val previousLon: Double?,
        val previousPlace: String?,
        val previousAccuracy: Float?
    )

    private data class RoutePersistResult(
        val noteId: Long,
        val routeBlockId: Long,
        val mirrorBlockId: Long,
        val payload: RoutePayload
    )

    private val locationPins = mutableMapOf<Long, MapPin>()

    // Mémoire
    private var allNotes: List<Note> = emptyList()
    private var lastFeatures: List<Feature>? = null

    // Permission localisation
    private val REQ_LOC = 1001
    private val REQ_ROUTE = 1002

    private data class RecentHere(val lat: Double, val lon: Double, val timestamp: Long)

    companion object {
        private const val ARG_NOTE_ID = "arg_note_id"
        private const val ARG_BLOCK_ID = "arg_block_id"
        private const val ARG_MODE = "arg_mode"
        private const val STATE_NOTE_ID = "state_note_id"
        private const val STATE_BLOCK_ID = "state_block_id"
        private const val STATE_MODE = "state_mode"
        private const val TAG = "MapFragment"

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

    private object MapUiDefaults {
        const val MIN_DISTANCE_METERS = 20f
        const val MIN_TIME_BETWEEN_UPDATES_MS = 1_000L
        const val REQUEST_INTERVAL_MS = 1_500L
        const val MAX_ROUTE_POINTS = 500
        const val ROUTE_LINE_COLOR = "#FF2E7D32"
        const val ROUTE_LINE_WIDTH = 4f
        const val ROUTE_BOUNDS_PADDING_DP = 48
        const val HERE_COOLDOWN_MS = 15_000L
        const val HERE_COOLDOWN_DISTANCE_M = 30f
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
        noteRepo = NoteRepository(
            database.noteDao(),
            database.attachmentDao(),
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
        clearRecordingLine()
        b.btnAddHere.isEnabled = false
        b.btnAddHere.setOnClickListener { onAddHereClicked() }
        b.btnRecordRoute.isEnabled = false
        b.btnRecordRoute.setOnClickListener { onRouteButtonClicked() }
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
            if (style.getImage(ICON_SINGLE) == null) style.addImage(ICON_SINGLE, makeDot(22, R.color.purple_500))
            if (style.getImage(ICON_FEW) == null)    style.addImage(ICON_FEW,    makeDot(22, android.R.color.holo_blue_light))
            if (style.getImage(ICON_MANY) == null)   style.addImage(ICON_MANY,   makeDot(22, android.R.color.holo_red_light))
            if (style.getImage(ICON_HERE) == null)   style.addImage(ICON_HERE,   makeDot(24, R.color.teal_200))

            // Source
            if (style.getSource(SRC_NOTES) == null) {
                style.addSource(GeoJsonSource(SRC_NOTES, FeatureCollection.fromFeatures(arrayListOf())))
            }

            // Couche (au-dessus de tout)
            if (style.getLayer(LYR_NOTES) == null) {
                val layer = SymbolLayer(LYR_NOTES, SRC_NOTES).withProperties(
                    iconImage(Expression.get("icon")),
                    iconSize(1.15f),
                    iconAllowOverlap(true),
                    iconIgnorePlacement(true),

                    // COMPTE TOTAL DE NOTES DU GROUPE
                    textField(Expression.toString(Expression.get("count"))),
                    textSize(12f),
                    textOffset(arrayOf(0f, 1.5f)),
                    textColor(ContextCompat.getColor(requireContext(), android.R.color.white)),
                    textHaloColor(ContextCompat.getColor(requireContext(), android.R.color.black)),
                    textHaloWidth(1.6f)
                )
                val top = style.layers.lastOrNull()?.id
                if (top != null) style.addLayerAbove(layer, top) else style.addLayer(layer)
            }

            setupSymbolManager(style)
            setupRouteManager(style)
            b.btnAddHere.isEnabled = true
            b.btnRecordRoute.isEnabled = true

            if (initialMode == MapActivity.MODE_BROWSE) {
                moveCameraToDefault()
            }

            // Charge les notes brutes (une seule fois)
            loadNotesThenRender()

            // Recalcule l’agrégation quand la caméra s’arrête → nombres corrects selon le zoom
            map?.addOnCameraIdleListener { renderGroupsForCurrentZoom() }

            isStyleReady = true
            applyPendingBlockFocus()
            applyStartMode(initialMode)
        }

        // Tap : zoom doux + bottom sheet (toujours)
        map?.addOnMapClickListener { latLng ->
            val screenPt = map?.projection?.toScreenLocation(latLng) ?: return@addOnMapClickListener false
            val features = map?.queryRenderedFeatures(screenPt, LYR_NOTES).orEmpty()
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
    }

    /** 1) Récupère une fois toutes les notes géolocalisées, 2) rend selon le zoom courant. */
    private fun loadNotesThenRender() {
        val dao = database.noteDao()
        viewLifecycleOwner.lifecycleScope.launch {
            allNotes = withContext(Dispatchers.IO) { dao.getAllWithLocation() }
            if (allNotes.isEmpty()) {
                showHint("Aucune note géolocalisée")
                return@launch
            } else {
                showHint("${allNotes.size} note(s) géolocalisée(s)")
            }
            renderGroupsForCurrentZoom()
        }
    }

    /** Agrège dynamiquement par “cellules écran” (≈48 px) → libellé = total de notes du groupe. */
    private fun renderGroupsForCurrentZoom() {
        val m = map ?: return
        val style = m.style ?: return
        if (allNotes.isEmpty()) return

        val cam = m.cameraPosition ?: return
        val target = cam.target ?: return  // ✅ null-safe
        val centerLat = target.latitude
        val zoom = cam.zoom

        // ~48 px à l’écran
        val clusterRadiusPx = 48.0
        val mpp = metersPerPixelAtLat(centerLat, zoom) // mètres par pixel
        val radiusM = clusterRadiusPx * mpp

        // Convertit un rayon en degrés (avec garde-fous)
        val degLat = radiusM / 111_000.0
        val degLon = radiusM / (111_320.0 * max(0.2, cos(Math.toRadians(centerLat))))
        if (degLat <= 0.0 || degLon <= 0.0 || degLat.isNaN() || degLon.isNaN() || degLat.isInfinite() || degLon.isInfinite()) return

        // Regroupe par “cellule” (key = indices de grille)
        val groups = HashMap<Pair<Int, Int>, MutableList<Note>>()
        allNotes.forEach { n ->
            val la = n.lat ?: return@forEach
            val lo = n.lon ?: return@forEach
            val gx = floor(la / degLat).toInt()
            val gy = floor(lo / degLon).toInt()
            groups.getOrPut(gx to gy) { mutableListOf() }.add(n)
        }

        // (facultatif) si tu synthétises des Features ici :
        val features = groups.map { (_, list) ->
            val lat = list.mapNotNull { it.lat }.average()
            val lon = list.mapNotNull { it.lon }.average()
            val count = list.size
            val title = list.firstOrNull()?.placeLabel ?: "Lieu"
            val icon = when {
                count > 4 -> ICON_MANY
                count > 1 -> ICON_FEW
                else -> ICON_SINGLE
            }
            Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                addStringProperty("title", title)
                addNumberProperty("count", count)
                addNumberProperty("lat", lat)
                addNumberProperty("lon", lon)
                addStringProperty("icon", icon)
            }
        }

        style.getSourceAs<GeoJsonSource>(SRC_NOTES)
            ?.setGeoJson(FeatureCollection.fromFeatures(features))
    }


    /** m/pixel à une latitude pour un zoom donné. */
    private fun metersPerPixelAtLat(lat: Double, zoom: Double): Double {
        return 156543.03392 * cos(Math.toRadians(lat)) / 2.0.pow(zoom)
    }

    private fun fitToAll(features: List<Feature>) {
        if (features.isEmpty()) return
        val bds = LatLngBounds.Builder()
        features.forEach { f ->
            val p = f.geometry() as? Point ?: return@forEach
            bds.include(LatLng(p.latitude(), p.longitude()))
        }
        val bounds = bds.build()
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 64))
    }

    private fun moveCameraToDefault() {
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(46.7111, 1.7191), 5.0))
    }

    private fun applyStartMode(mode: String) {
        when (mode) {
            MapActivity.MODE_CENTER_ON_HERE -> {
                val centered = tryCenterOnLastKnownLocation()
                if (!centered) {
                    moveCameraToDefault()
                }
            }
            MapActivity.MODE_FOCUS_NOTE -> focusOnTargetNoteOrFallback { moveCameraToDefault() }
            else -> Unit
        }
    }

    private fun tryCenterOnLastKnownLocation(): Boolean {
        if (!hasLocationPermission()) return false
        val me = lastKnownLatLng(requireContext()) ?: return false
        val currentZoom = map?.cameraPosition?.zoom ?: 5.0
        val targetZoom = max(currentZoom, 14.0)
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(me, targetZoom))
        showHint("Votre position")
        return true
    }

    private fun focusOnTargetNoteOrFallback(onFallback: () -> Unit) {
        val noteId = targetNoteId ?: return onFallback()
        viewLifecycleOwner.lifecycleScope.launch {
            val note = withContext(Dispatchers.IO) { noteRepo.noteOnce(noteId) }
            val lat = note?.lat
            val lon = note?.lon
            if (lat != null && lon != null) {
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 15.0))
            } else {
                onFallback()
            }
        }
    }

    private fun applyPendingBlockFocus() {
        val blockId = pendingBlockFocus ?: return
        if (!isStyleReady || map == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val block = blocksRepo.getBlock(blockId)
            if (block != null) {
                targetBlockId = blockId
                targetNoteId = block.noteId
                focusOnBlock(block)
            }
            pendingBlockFocus = null
        }
    }

    private fun focusOnBlock(block: BlockEntity) {
        when (block.type) {
            BlockType.ROUTE -> {
                val handled = focusOnRoute(block)
                if (!handled) {
                    focusOnLatLon(block.lat, block.lon)
                }
            }
            BlockType.LOCATION -> focusOnLatLon(block.lat, block.lon)
            else -> focusOnLatLon(block.lat, block.lon)
        }
    }

    private fun focusOnRoute(block: BlockEntity): Boolean {
        val payload = block.routeJson?.let { json ->
            runCatching { routeGson.fromJson(json, RoutePayload::class.java) }.getOrNull()
        }
        val points = payload?.points ?: emptyList()
        if (points.isEmpty()) return false
        if (points.size == 1) {
            return focusOnLatLon(points.first().lat, points.first().lon)
        }
        val builder = LatLngBounds.Builder()
        points.forEach { builder.include(LatLng(it.lat, it.lon)) }
        val bounds = runCatching { builder.build() }.getOrNull()
        if (bounds != null) {
            val padding = dpToPx(MapUiDefaults.ROUTE_BOUNDS_PADDING_DP)
            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
            showHint(getString(R.string.block_view_on_map))
            return true
        }
        return focusOnLatLon(points.first().lat, points.first().lon)
    }

    private fun focusOnLatLon(lat: Double?, lon: Double?, zoom: Double = 15.0): Boolean {
        if (lat == null || lon == null) return false
        val target = LatLng(lat, lon)
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(target, zoom))
        showHint(getString(R.string.block_view_on_map))
        return true
    }

    /** Bouton “recentrer” : priorité à l’utilisateur, sinon sur toutes les notes. */
    private fun recenterToUserOrAll() {
        val ctx = requireContext()
        val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            val previouslyRequested = hasRequestedLocationPermission
            val showFineRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            val showCoarseRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
            hasRequestedLocationPermission = true
            if (previouslyRequested && !showFineRationale && !showCoarseRationale) {
                showLocationDisabledHint()
            } else {
                showHint(getString(R.string.map_location_permission_needed))
            }
            return
        }
        val me = lastKnownLatLng(ctx)
        if (me != null) {
            val currentZoom = map?.cameraPosition?.zoom ?: 5.0
            val target = max(currentZoom, 14.0)
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(me, target))
            showHint("Votre position")
        } else {
            lastFeatures?.let { fitToAll(it) }
        }
    }

    private fun lastKnownLatLng(ctx: Context): LatLng? {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        for (p in providers) {
            @Suppress("MissingPermission")
            val loc = lm.getLastKnownLocation(p)
            if (loc != null) return LatLng(loc.latitude, loc.longitude)
        }
        return null
    }

    /** Appelé par l’Activity si besoin. */
    fun recenterToAll() {
        lastFeatures?.takeIf { it.isNotEmpty() }?.let { fitToAll(it) }
    }

    private fun openLocationNotes(lat: Double, lon: Double, title: String) {
        val i = Intent(requireContext(), LocationNotesActivity::class.java)
        i.putExtra(LocationNotesActivity.EXTRA_LAT, lat)
        i.putExtra(LocationNotesActivity.EXTRA_LON, lon)
        i.putExtra(LocationNotesActivity.EXTRA_TITLE, title)
        startActivity(i)
    }

    private fun setupSymbolManager(style: Style) {
        val mv = mapView ?: return
        val mapInstance = map ?: return
        symbolManager?.onDestroy()
        symbolManager = SymbolManager(mv, mapInstance, style).apply {
            iconAllowOverlap = true
            iconIgnorePlacement = true
        }
        refreshPins()
    }

    private fun setupRouteManager(style: Style) {
        val mv = mapView ?: return
        val mapInstance = map ?: return
        polylineManager?.onDestroy()
        polylineManager = LineManager(mv, mapInstance, style).apply {
            lineCap = Property.LINE_CAP_ROUND
        }
        recordingRouteLine = null
    }

    private fun refreshPins() {
        val manager = symbolManager ?: return
        manager.deleteAll()
        locationPins.forEach { (_, pin) ->
            val symbol = manager.create(
                SymbolOptions()
                    .withLatLng(LatLng(pin.lat, pin.lon))
                    .withIconImage(ICON_HERE)
            )
            pin.symbol = symbol
        }
    }

    private fun addCustomPin(blockId: Long, lat: Double, lon: Double) {
        locationPins[blockId] = MapPin(lat, lon)
        refreshPins()
    }

    private fun removeCustomPin(blockId: Long) {
        locationPins.remove(blockId)
        refreshPins()
    }

    private fun hasLocationPermission(): Boolean {
        val ctx = requireContext()
        val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun showLocationDisabledHint() {
        showHint(
            getString(R.string.map_location_disabled),
            getString(R.string.map_location_open_settings)
        ) {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        val context = requireContext()
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
    }

    private fun onAddHereClicked() {
        val ctx = requireContext()
        if (!hasLocationPermission()) {
            val previouslyRequested = hasRequestedLocationPermission
            val showFineRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            val showCoarseRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
            awaitingHerePermission = true
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_LOC
            )
            hasRequestedLocationPermission = true
            if (previouslyRequested && !showFineRationale && !showCoarseRationale) {
                showLocationDisabledHint()
            } else {
                showHint(getString(R.string.map_location_permission_needed))
            }
            return
        }
        if (symbolManager == null) {
            showHint(getString(R.string.map_location_unavailable))
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            b.btnAddHere.isEnabled = false
            try {
                val place = withContext(Dispatchers.IO) {
                    runCatching { getOneShotPlace(ctx) }.getOrNull()
                }
                if (place == null) {
                    showHint(getString(R.string.map_location_unavailable))
                    return@launch
                }
                val now = System.currentTimeMillis()
                val last = lastHereLocation
                if (last != null && now - last.timestamp < MapUiDefaults.HERE_COOLDOWN_MS) {
                    val results = FloatArray(1)
                    Location.distanceBetween(last.lat, last.lon, place.lat, place.lon, results)
                    if (results[0] < MapUiDefaults.HERE_COOLDOWN_DISTANCE_M) {
                        showHint(getString(R.string.map_location_recent_duplicate))
                        return@launch
                    }
                }
                val result = appendLocation(place)
                if (result == null) {
                    showHint(getString(R.string.map_location_unavailable))
                    return@launch
                }
                targetNoteId = result.noteId
                val displayLabel = displayLabelFor(place)
                addCustomPin(result.locationBlockId, place.lat, place.lon)
                showHint(getString(R.string.map_location_added))
                refreshNotesAsync()
                showUndoSnackbar(result, displayLabel)
                lastHereLocation = RecentHere(place.lat, place.lon, now)
            } finally {
                b.btnAddHere.isEnabled = true
            }
        }
    }

    private suspend fun appendLocation(place: Place): LocationAddResult? {
        return withContext(Dispatchers.IO) {
            runCatching {
                var noteId = targetNoteId
                var previousLat: Double? = null
                var previousLon: Double? = null
                var previousPlace: String? = null
                var previousAccuracy: Float? = null

                if (noteId != null) {
                    val existing = noteRepo.noteOnce(noteId)
                    previousLat = existing?.lat
                    previousLon = existing?.lon
                    previousPlace = existing?.placeLabel
                    previousAccuracy = existing?.accuracyM
                } else {
                    noteId = noteRepo.createTextNote(
                        body = "",
                        lat = place.lat,
                        lon = place.lon,
                        place = place.label,
                        accuracyM = place.accuracyM
                    )
                }

                noteRepo.updateLocation(
                    id = noteId!!,
                    lat = place.lat,
                    lon = place.lon,
                    place = place.label,
                    accuracyM = place.accuracyM
                )

                val locationBlockId = blocksRepo.appendLocation(noteId!!, place.lat, place.lon, place.label)
                val mirrorBlockId = blocksRepo.appendText(noteId!!, buildMirrorText(place))

                LocationAddResult(
                    noteId = noteId!!,
                    locationBlockId = locationBlockId,
                    mirrorBlockId = mirrorBlockId,
                    previousLat = previousLat,
                    previousLon = previousLon,
                    previousPlace = previousPlace,
                    previousAccuracy = previousAccuracy
                )
            }.onFailure { e ->
                Log.e(TAG, "Failed to append location", e)
            }.getOrNull()
        }
    }

    private fun buildMirrorText(place: Place): String {
        return "📍 Ajouté: ${displayLabelFor(place)}"
    }

    private fun onRouteButtonClicked() {
        if (routeRecorder != null) {
            stopRouteRecording(save = true)
            return
        }
        if (isSavingRoute) {
            return
        }
        if (!hasLocationPermission()) {
            awaitingRoutePermission = true
            val previouslyRequested = hasRequestedLocationPermission
            val showFineRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
            val showCoarseRationale = shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_ROUTE
            )
            hasRequestedLocationPermission = true
            if (previouslyRequested && !showFineRationale && !showCoarseRationale) {
                showLocationDisabledHint()
            } else {
                showHint(getString(R.string.map_location_permission_needed))
            }
            return
        }
        startRouteRecording()
    }

    private fun startRouteRecording() {
        if (routeRecorder != null || isSavingRoute) return
        val ctx = requireContext()
        val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            showHint(getString(R.string.map_route_provider_unavailable))
            return
        }
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        ).filter { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }.distinct()
        if (providers.isEmpty()) {
            showHint(getString(R.string.map_route_provider_unavailable))
            return
        }
        b.btnAddHere.isEnabled = false
        b.btnRecordRoute.text = getString(R.string.map_route_stop, 0)
        b.btnRecordRoute.isEnabled = true
        showHint(getString(R.string.map_route_recording_hint))
        val recorder = RouteRecorder(manager, providers) { points -> onRoutePointsChanged(points) }
        routeRecorder = recorder
        val started = runCatching { recorder.start() }.isSuccess
        if (!started) {
            routeRecorder = null
            b.btnAddHere.isEnabled = true
            resetRouteButton()
            showHint(getString(R.string.map_route_provider_unavailable))
        }
    }

    private fun stopRouteRecording(save: Boolean) {
        val recorder = routeRecorder ?: return
        routeRecorder = null
        b.btnAddHere.isEnabled = true
        if (!save) {
            recorder.cancel()
            clearRecordingLine()
            resetRouteButton()
            return
        }
        b.btnRecordRoute.isEnabled = false
        val payload = recorder.stop()
        if (payload == null) {
            showHint(getString(R.string.map_route_too_short))
            clearRecordingLine()
            resetRouteButton()
            return
        }
        val mirrorText = getString(R.string.map_route_mirror_format, payload.pointCount)
        isSavingRoute = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = persistRoute(payload, mirrorText)
            isSavingRoute = false
            if (result == null) {
                showHint(getString(R.string.map_route_save_failed))
                clearRecordingLine()
            } else {
                targetNoteId = result.noteId
                showHint(getString(R.string.map_route_saved))
                fitToRoute(result.payload.points)
                refreshNotesAsync()
            }
            resetRouteButton()
        }
    }

    private fun cancelRouteRecording() {
        val recorder = routeRecorder ?: return
        routeRecorder = null
        recorder.cancel()
        b.btnAddHere.isEnabled = true
        clearRecordingLine()
        resetRouteButton()
    }

    private fun onRoutePointsChanged(points: List<RoutePointPayload>) {
        b.btnRecordRoute.text = getString(R.string.map_route_stop, points.size)
        updateRoutePolyline(points)
    }

    private fun resetRouteButton() {
        b.btnRecordRoute.text = getString(R.string.map_route_start)
        b.btnRecordRoute.isEnabled = !isSavingRoute
    }

    private fun updateRoutePolyline(points: List<RoutePointPayload>) {
        val manager = polylineManager ?: return
        recordingRouteLine?.let { existing ->
            manager.delete(existing)
            recordingRouteLine = null
        }
        if (points.size < 2) return
        val latLngs = points.map { LatLng(it.lat, it.lon) }
        val options: LineOptions = LineOptions()
            .withLatLngs(latLngs)
            .withLineColor(MapUiDefaults.ROUTE_LINE_COLOR)
            .withLineWidth(MapUiDefaults.ROUTE_LINE_WIDTH)
        recordingRouteLine = manager.create(options as LineOptions)
    }

    private fun clearRecordingLine() {
        recordingRouteLine?.let { line ->
            polylineManager?.delete(line)
        }
        recordingRouteLine = null
    }

    private fun fitToRoute(points: List<RoutePointPayload>) {
        if (points.isEmpty()) return
        val latLngs = points.map { LatLng(it.lat, it.lon) }
        if (latLngs.isEmpty()) return
        if (latLngs.size == 1) {
            map?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLngs.first(), 15.0))
            return
        }
        val builder = LatLngBounds.Builder()
        latLngs.forEach { builder.include(it) }
        val bounds = runCatching { builder.build() }.getOrNull() ?: return
        val padding = dpToPx(MapUiDefaults.ROUTE_BOUNDS_PADDING_DP)
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
    }

    private suspend fun persistRoute(
        payload: RoutePayload,
        mirrorText: String
    ): RoutePersistResult? {
        val firstPoint = payload.firstPoint()
        val lat = firstPoint?.lat
        val lon = firstPoint?.lon
        return withContext(Dispatchers.IO) {
            runCatching {
                var noteId = targetNoteId
                if (noteId == null) {
                    noteId = noteRepo.createTextNote(
                        body = "",
                        lat = lat,
                        lon = lon,
                        place = null,
                        accuracyM = null
                    )
                } else if (lat != null && lon != null) {
                    noteRepo.updateLocation(noteId!!, lat, lon, null, null)
                }
                val routeJson = routeGson.toJson(payload)
                val routeBlockId = blocksRepo.appendRoute(noteId!!, routeJson, lat, lon)
                val mirrorBlockId = blocksRepo.appendText(noteId!!, mirrorText)
                RoutePersistResult(
                    noteId = noteId!!,
                    routeBlockId = routeBlockId,
                    mirrorBlockId = mirrorBlockId,
                    payload = payload
                )
            }.onFailure { e ->
                Log.e(TAG, "Failed to persist route", e)
            }.getOrNull()
        }
    }

    private fun displayLabelFor(place: Place): String {
        val label = place.label?.takeIf { it.isNotBlank() }
        return label ?: String.format(Locale.US, "%.5f, %.5f", place.lat, place.lon)
    }

    private inner class RouteRecorder(
        private val locationManager: LocationManager,
        private val providers: List<String>,
        private val onPointsChanged: (List<RoutePointPayload>) -> Unit
    ) : LocationListener {

        private val points = mutableListOf<RoutePointPayload>()
        private var lastAcceptedAt: Long = 0L
        private var lastLocation: Location? = null

        @SuppressLint("MissingPermission")
        fun start() {
            providers.forEach { provider ->
                locationManager.requestLocationUpdates(
                    provider,
                    MapUiDefaults.REQUEST_INTERVAL_MS,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
            }
            val seed = providers.firstNotNullOfOrNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            val now = System.currentTimeMillis()
            seed?.let { accept(it, now) }
        }

        override fun onLocationChanged(location: Location) {
            accept(location, System.currentTimeMillis())
        }

        private fun accept(location: Location, timestamp: Long) {
            if (points.isNotEmpty()) {
                val dt = timestamp - lastAcceptedAt
                val distance = lastLocation?.distanceTo(location) ?: Float.MAX_VALUE
                if (
                    dt < MapUiDefaults.MIN_TIME_BETWEEN_UPDATES_MS &&
                    distance < MapUiDefaults.MIN_DISTANCE_METERS
                ) {
                    return
                }
                if (points.size >= MapUiDefaults.MAX_ROUTE_POINTS) {
                    return
                }
            }
            val point = RoutePointPayload(location.latitude, location.longitude, timestamp)
            points.add(point)
            lastAcceptedAt = timestamp
            lastLocation = Location(location)
            onPointsChanged(points.toList())
        }

        fun stop(): RoutePayload? {
            providers.forEach { provider -> locationManager.removeUpdates(this) }
            if (points.size < 2) return null
            val first = points.first()
            val last = points.last()
            return RoutePayload(
                startedAt = first.t,
                endedAt = last.t,
                points = points.toList()
            )
        }

        fun cancel() {
            providers.forEach { provider -> locationManager.removeUpdates(this) }
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun showUndoSnackbar(result: LocationAddResult, displayLabel: String) {
        Snackbar.make(b.root, "${getString(R.string.map_location_added)} — $displayLabel", Snackbar.LENGTH_LONG)
            .setAction(R.string.action_undo) {
                viewLifecycleOwner.lifecycleScope.launch {
                    undoLocationAdd(result)
                }
            }
            .show()
    }

    private suspend fun undoLocationAdd(result: LocationAddResult) {
        val success = withContext(Dispatchers.IO) {
            runCatching {
                blocksRepo.deleteBlock(result.locationBlockId)
                blocksRepo.deleteBlock(result.mirrorBlockId)
                noteRepo.updateLocation(
                    id = result.noteId,
                    lat = result.previousLat,
                    lon = result.previousLon,
                    place = result.previousPlace,
                    accuracyM = result.previousAccuracy
                )
            }.isSuccess
        }
        if (success) {
            removeCustomPin(result.locationBlockId)
            refreshNotesAsync()
            showHint(getString(R.string.map_location_undo_success))
            Snackbar.make(b.root, getString(R.string.map_location_undo_success), Snackbar.LENGTH_SHORT).show()
            lastHereLocation = null
        } else {
            Snackbar.make(b.root, getString(R.string.map_location_unavailable), Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun refreshNotesAsync() {
        viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { database.noteDao().getAllWithLocation() }
            allNotes = list
            renderGroupsForCurrentZoom()
        }
    }

    private fun showHint(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        val hintView = b.clusterHint
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

    private fun makeDot(sizeDp: Int = 18, colorRes: Int): Bitmap {
        val dm = resources.displayMetrics
        val px = (sizeDp * dm.density).toInt().coerceAtLeast(10)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(requireContext(), colorRes)
            style = Paint.Style.FILL
        }
        c.drawCircle(px / 2f, px / 2f, px / 2.2f, p)
        return bmp
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
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
        mapView?.onDestroy()
        symbolManager?.onDestroy()
        symbolManager = null
        polylineManager?.onDestroy()
        polylineManager = null
        recordingRouteLine = null
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
