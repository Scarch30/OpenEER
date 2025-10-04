package com.example.openeer.ui.library

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.openeer.R
import com.example.openeer.data.AppDatabase
import com.example.openeer.data.Note
import com.example.openeer.databinding.FragmentMapBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.*

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

    // Mémoire
    private var allNotes: List<Note> = emptyList()
    private var lastFeatures: List<Feature>? = null

    // Permission localisation
    private val REQ_LOC = 1001

    companion object {
        fun newInstance(): MapFragment = MapFragment()
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
    }

    override fun onMapReady(mapboxMap: MapLibreMap) {
        map = mapboxMap

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

            // Centre France
            map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(46.7111, 1.7191), 5.0))

            // Charge les notes brutes (une seule fois)
            loadNotesThenRender()

            // Recalcule l’agrégation quand la caméra s’arrête → nombres corrects selon le zoom
            map?.addOnCameraIdleListener { renderGroupsForCurrentZoom() }
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
        val ctx = requireContext().applicationContext
        val dao = AppDatabase.get(ctx).noteDao()
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

    /** Bouton “recentrer” : priorité à l’utilisateur, sinon sur toutes les notes. */
    private fun recenterToUserOrAll() {
        val ctx = requireContext()
        val fine = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine != PackageManager.PERMISSION_GRANTED && coarse != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
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

    private fun showHint(text: String) {
        b.clusterHint.text = text
        b.clusterHint.visibility = View.VISIBLE
        b.clusterHint.alpha = 0f
        b.clusterHint.animate().alpha(1f).setDuration(120).withEndAction {
            b.clusterHint.animate().alpha(0f).setDuration(450).withEndAction {
                b.clusterHint.visibility = View.GONE
                b.clusterHint.alpha = 1f
            }.start()
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

    // MapView lifecycle
    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onDestroyView() { mapView?.onDestroy(); super.onDestroyView(); _b = null }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOC && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            recenterToUserOrAll()
        }
    }
}
