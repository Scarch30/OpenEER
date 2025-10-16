package com.example.openeer.ui.library

import android.content.Context
import android.util.Log
import android.widget.SeekBar
import androidx.core.view.isVisible
import com.example.openeer.data.block.RoutePointPayload
import com.example.openeer.databinding.ViewRouteDebugOverlayBinding
import com.example.openeer.map.RouteSimplifier
import com.example.openeer.R
import com.example.openeer.ui.map.MapUiDefaults
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.roundToInt
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.plugins.annotation.Line
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions

internal object RouteDebugOverlay {
    private const val TAG = "RouteDebug"
    private const val PREFS_NAME = "route_debug_overlay"
    private const val KEY_EPSILON = "epsilon"
    private const val KEY_MIN_INTERVAL = "min_interval"
    private const val KEY_MIN_DISPLACEMENT = "min_displacement"
    private const val KEY_MAX_ACCURACY = "max_accuracy"

    private val states = WeakHashMap<MapFragment, DebugState>()

    fun update(fragment: MapFragment, rawPoints: List<RoutePointPayload>) {
        if (!MapUiDefaults.DEBUG_ROUTE) {
            hide(fragment)
            return
        }

        val binding = runCatching { fragment.binding.routeDebugOverlay }.getOrNull() ?: return
        val state = states.getOrPut(fragment) { DebugState(binding) }
        state.ensureInitialized(fragment)
        state.update(fragment, rawPoints)
    }

    fun hide(fragment: MapFragment) {
        val binding = runCatching { fragment.binding.routeDebugOverlay }.getOrNull()
        binding?.root?.isVisible = false
        states.remove(fragment)?.clear(fragment.polylineManager)
    }

    private class DebugState(
        val binding: ViewRouteDebugOverlayBinding,
    ) {
        private val seekEps = binding.root.findViewById<SeekBar>(R.id.seekEps)
        private val seekMinInterval = binding.root.findViewById<SeekBar>(R.id.seekMinInterval)
        private val seekMinDisp = binding.root.findViewById<SeekBar>(R.id.seekMinDisp)
        private val seekMaxAcc = binding.root.findViewById<SeekBar>(R.id.seekMaxAcc)

        private var rawLine: Line? = null
        private var simplifiedLine: Line? = null
        private var lastRawPoints: List<RoutePointPayload> = emptyList()
        private var listenersAttached = false

        fun ensureInitialized(fragment: MapFragment) {
            if (listenersAttached) return

            listenersAttached = true

            loadPrefs(fragment)
            syncSeekBarsFromDefaults()

            val listener = object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || seekBar == null) return
                    updateMapDefaultsFromSeekBars()
                    updateSeekBarLabels()
                    savePrefs(fragment)
                    recompute(fragment)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            }

            seekEps.setOnSeekBarChangeListener(listener)
            seekMinInterval.setOnSeekBarChangeListener(listener)
            seekMinDisp.setOnSeekBarChangeListener(listener)
            seekMaxAcc.setOnSeekBarChangeListener(listener)

            binding.btnReset.setOnClickListener {
                MapUiDefaults.ROUTE_SIMPLIFY_EPSILON_M = MapUiDefaults.ROUTE_SIMPLIFY_EPSILON_M_DEFAULT
                MapUiDefaults.ROUTE_MIN_INTERVAL_MS = MapUiDefaults.ROUTE_MIN_INTERVAL_MS_DEFAULT
                MapUiDefaults.ROUTE_MIN_DISPLACEMENT_M = MapUiDefaults.ROUTE_MIN_DISPLACEMENT_M_DEFAULT
                MapUiDefaults.ROUTE_MAX_ACCURACY_M = MapUiDefaults.ROUTE_MAX_ACCURACY_M_DEFAULT
                syncSeekBarsFromDefaults()
                savePrefs(fragment)
                recompute(fragment)
            }

            updateSeekBarLabels()
        }

        fun update(fragment: MapFragment, rawPoints: List<RoutePointPayload>, syncSliders: Boolean = true) {
            lastRawPoints = rawPoints
            if (syncSliders) syncSeekBarsFromDefaults()

            if (rawPoints.isEmpty()) {
                binding.root.isVisible = false
                clear(fragment.polylineManager)
                return
            }

            val epsilon = MapUiDefaults.ROUTE_SIMPLIFY_EPSILON_M
            val result = RouteDebugMath.compute(rawPoints, epsilon)

            binding.root.isVisible = true
            binding.textStats.text = RouteDebugStatsFormatter.format(result.stats)
            updateSeekBarLabels()
            updatePolylines(fragment.polylineManager, rawPoints, result.simplified)

            Log.d(TAG, "overlay → ${binding.textStats.text}")
        }

        fun clear(manager: LineManager?) {
            rawLine?.let { line -> runCatching { manager?.delete(line) } }
            simplifiedLine?.let { line -> runCatching { manager?.delete(line) } }
            rawLine = null
            simplifiedLine = null
            lastRawPoints = emptyList()
        }

        private fun recompute(fragment: MapFragment) {
            if (lastRawPoints.isEmpty()) {
                binding.root.isVisible = false
                return
            }
            update(fragment, lastRawPoints, syncSliders = false)
        }

        private fun syncSeekBarsFromDefaults() {
            seekEps.progress = computeEpsProgress(MapUiDefaults.ROUTE_SIMPLIFY_EPSILON_M)
            seekMinInterval.progress = computeMinIntervalProgress(MapUiDefaults.ROUTE_MIN_INTERVAL_MS)
            seekMinDisp.progress = computeMinDispProgress(MapUiDefaults.ROUTE_MIN_DISPLACEMENT_M)
            seekMaxAcc.progress = computeMaxAccProgress(MapUiDefaults.ROUTE_MAX_ACCURACY_M)
            updateSeekBarLabels()
        }

        private fun updateSeekBarLabels() {
            binding.valueEpsilon.text = "${epsValue().roundToInt()} m"
            binding.valueMinInterval.text = "${minIntervalValue()} ms"
            binding.valueMinDisplacement.text = "${minDispValue().roundToInt()} m"
            binding.valueMaxAccuracy.text = "${maxAccValue().roundToInt()} m"
        }

        private fun updateMapDefaultsFromSeekBars() {
            MapUiDefaults.ROUTE_SIMPLIFY_EPSILON_M = epsValue()
            MapUiDefaults.ROUTE_MIN_INTERVAL_MS = minIntervalValue()
            MapUiDefaults.ROUTE_MIN_DISPLACEMENT_M = minDispValue()
            MapUiDefaults.ROUTE_MAX_ACCURACY_M = maxAccValue()
        }

        private fun epsValue(): Float = 1f + seekEps.progress

        private fun minIntervalValue(): Long = 200L + seekMinInterval.progress.toLong() * 100L

        private fun minDispValue(): Float = 1f + seekMinDisp.progress

        private fun maxAccValue(): Float = 5f + seekMaxAcc.progress

        private fun computeEpsProgress(value: Float): Int {
            val rounded = value.roundToInt() - 1
            return rounded.coerceIn(0, seekEps.max)
        }

        private fun computeMinIntervalProgress(value: Long): Int {
            val scaled = ((value - 200L) / 100L).toInt()
            return scaled.coerceIn(0, seekMinInterval.max)
        }

        private fun computeMinDispProgress(value: Float): Int {
            val rounded = value.roundToInt() - 1
            return rounded.coerceIn(0, seekMinDisp.max)
        }

        private fun computeMaxAccProgress(value: Float): Int {
            val rounded = value.roundToInt() - 5
            return rounded.coerceIn(0, seekMaxAcc.max)
        }

        private fun loadPrefs(fragment: MapFragment) {
            val prefs = fragment.requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            MapUiDefaults.ROUTE_SIMPLIFY_EPSILON_M =
                prefs.getFloat(KEY_EPSILON, MapUiDefaults.ROUTE_SIMPLIFY_EPSILON_M_DEFAULT)
            MapUiDefaults.ROUTE_MIN_INTERVAL_MS =
                prefs.getLong(KEY_MIN_INTERVAL, MapUiDefaults.ROUTE_MIN_INTERVAL_MS_DEFAULT)
            MapUiDefaults.ROUTE_MIN_DISPLACEMENT_M =
                prefs.getFloat(KEY_MIN_DISPLACEMENT, MapUiDefaults.ROUTE_MIN_DISPLACEMENT_M_DEFAULT)
            MapUiDefaults.ROUTE_MAX_ACCURACY_M =
                prefs.getFloat(KEY_MAX_ACCURACY, MapUiDefaults.ROUTE_MAX_ACCURACY_M_DEFAULT)
        }

        private fun savePrefs(fragment: MapFragment) {
            val prefs = fragment.requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putFloat(KEY_EPSILON, MapUiDefaults.ROUTE_SIMPLIFY_EPSILON_M)
                .putLong(KEY_MIN_INTERVAL, MapUiDefaults.ROUTE_MIN_INTERVAL_MS)
                .putFloat(KEY_MIN_DISPLACEMENT, MapUiDefaults.ROUTE_MIN_DISPLACEMENT_M)
                .putFloat(KEY_MAX_ACCURACY, MapUiDefaults.ROUTE_MAX_ACCURACY_M)
                .apply()
        }

        private fun updatePolylines(
            manager: LineManager?,
            rawPoints: List<RoutePointPayload>,
            simplifiedPoints: List<RoutePointPayload>,
        ) {
            val m = manager ?: return
            rawLine?.let { line -> runCatching { m.delete(line) } }
            simplifiedLine?.let { line -> runCatching { m.delete(line) } }

            rawLine = createLine(m, rawPoints, MapUiDefaults.DEBUG_ROUTE_RAW_COLOR)
            simplifiedLine = createLine(m, simplifiedPoints, MapUiDefaults.DEBUG_ROUTE_SIMPL_COLOR)
        }

        private fun createLine(
            manager: LineManager,
            points: List<RoutePointPayload>,
            color: String,
        ): Line? {
            if (points.size < 2) return null
            val latLngs = points.map { LatLng(it.lat, it.lon) }
            val options = LineOptions()
                .withLatLngs(latLngs)
                .withLineColor(color)
                .withLineWidth(MapUiDefaults.ROUTE_LINE_WIDTH)
            return runCatching { manager.create(options) }.getOrNull()
        }
    }
}

internal data class RouteDebugStats(
    val rawCount: Int,
    val simplifiedCount: Int,
    val reductionPercent: Int,
    val lengthKm: Double,
    val epsilonMeters: Float,
)

internal data class RouteDebugResult(
    val stats: RouteDebugStats,
    val simplified: List<RoutePointPayload>,
)

internal object RouteDebugMath {
    fun compute(rawPoints: List<RoutePointPayload>, epsilonMeters: Float): RouteDebugResult {
        if (rawPoints.isEmpty()) {
            val stats = RouteDebugStats(0, 0, 0, 0.0, epsilonMeters)
            return RouteDebugResult(stats, emptyList())
        }

        val simplified = if (rawPoints.size >= 2) {
            RouteSimplifier.simplifyMeters(rawPoints, epsilonMeters.toDouble())
        } else {
            rawPoints
        }

        val rawCount = rawPoints.size
        val simplifiedCount = simplified.size
        val reductionPercent = if (rawCount == 0) {
            0
        } else {
            val diff = (rawCount - simplifiedCount).coerceAtLeast(0)
            ((diff.toDouble() / rawCount.toDouble()) * 100.0).roundToInt()
        }
        val lengthKm = RouteSimplifier.totalLengthMeters(rawPoints) / 1000.0
        val stats = RouteDebugStats(rawCount, simplifiedCount, reductionPercent, lengthKm, epsilonMeters)
        return RouteDebugResult(stats, simplified)
    }
}

internal object RouteDebugStatsFormatter {
    fun format(stats: RouteDebugStats): String {
        val length = String.format(Locale.US, "%.2f", stats.lengthKm)
        val epsilon = stats.epsilonMeters.roundToInt()
        return "RAW n=${stats.rawCount}, SIMPL n=${stats.simplifiedCount} (-${stats.reductionPercent}%), L=${length} km, eps=${epsilon} m"
    }
}
