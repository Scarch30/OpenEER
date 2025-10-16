package com.example.openeer.ui.library

import android.util.Log
import androidx.core.view.isVisible
import com.example.openeer.data.block.RoutePointPayload
import com.example.openeer.databinding.ViewRouteDebugOverlayBinding
import com.example.openeer.map.RouteSimplifier
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
        private var rawLine: Line? = null
        private var simplifiedLine: Line? = null
        private var lastRawPoints: List<RoutePointPayload> = emptyList()
        private var listenersAttached = false

        fun ensureInitialized(fragment: MapFragment) {
            if (listenersAttached) return

            listenersAttached = true

            binding.sliderEpsilon.addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                MapUiDefaults.ROUTE_SIMPLIFY_EPSILON_M = value
                recompute(fragment)
            }

            binding.sliderMinInterval.addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                MapUiDefaults.ROUTE_MIN_INTERVAL_MS = value.toLong()
                recompute(fragment)
            }

            binding.sliderMinDisplacement.addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                MapUiDefaults.ROUTE_MIN_DISPLACEMENT_M = value
                recompute(fragment)
            }

            binding.sliderMaxAccuracy.addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                MapUiDefaults.ROUTE_MAX_ACCURACY_M = value
                recompute(fragment)
            }

            binding.btnReset.setOnClickListener {
                MapUiDefaults.ROUTE_SIMPLIFY_EPSILON_M = MapUiDefaults.ROUTE_SIMPLIFY_EPSILON_M_DEFAULT
                MapUiDefaults.ROUTE_MIN_INTERVAL_MS = MapUiDefaults.ROUTE_MIN_INTERVAL_MS_DEFAULT
                MapUiDefaults.ROUTE_MIN_DISPLACEMENT_M = MapUiDefaults.ROUTE_MIN_DISPLACEMENT_M_DEFAULT
                MapUiDefaults.ROUTE_MAX_ACCURACY_M = MapUiDefaults.ROUTE_MAX_ACCURACY_M_DEFAULT
                syncSlidersFromDefaults()
                recompute(fragment)
            }

            syncSlidersFromDefaults()
        }

        fun update(fragment: MapFragment, rawPoints: List<RoutePointPayload>, syncSliders: Boolean = true) {
            lastRawPoints = rawPoints
            if (syncSliders) syncSlidersFromDefaults()

            if (rawPoints.isEmpty()) {
                binding.root.isVisible = false
                clear(fragment.polylineManager)
                return
            }

            val epsilon = MapUiDefaults.ROUTE_SIMPLIFY_EPSILON_M
            val result = RouteDebugMath.compute(rawPoints, epsilon)

            binding.root.isVisible = true
            binding.textStats.text = RouteDebugStatsFormatter.format(result.stats)
            updateSliderLabels()
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

        private fun syncSlidersFromDefaults() {
            binding.sliderEpsilon.value = MapUiDefaults.ROUTE_SIMPLIFY_EPSILON_M
            binding.sliderMinInterval.value = MapUiDefaults.ROUTE_MIN_INTERVAL_MS.toFloat()
            binding.sliderMinDisplacement.value = MapUiDefaults.ROUTE_MIN_DISPLACEMENT_M
            binding.sliderMaxAccuracy.value = MapUiDefaults.ROUTE_MAX_ACCURACY_M
            updateSliderLabels()
        }

        private fun updateSliderLabels() {
            binding.valueEpsilon.text = "${binding.sliderEpsilon.value.roundToInt()} m"
            binding.valueMinInterval.text = "${binding.sliderMinInterval.value.roundToInt()} ms"
            binding.valueMinDisplacement.text = "${binding.sliderMinDisplacement.value.roundToInt()} m"
            binding.valueMaxAccuracy.text = "${binding.sliderMaxAccuracy.value.roundToInt()} m"
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
