package com.example.openeer.ui.library

import com.example.openeer.data.block.RoutePointPayload
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDebugOverlayTest {
    @Test
    fun formatStatsPreview() {
        val points = listOf(
            RoutePointPayload(48.8566, 2.3522, 0L),
            RoutePointPayload(48.8570, 2.3600, 1000L),
            RoutePointPayload(48.8580, 2.3650, 2000L),
            RoutePointPayload(48.8600, 2.3700, 3000L),
        )

        val result = RouteDebugMath.compute(points, 8f)
        val formatted = RouteDebugStatsFormatter.format(result.stats)

        println(formatted)
        assertTrue(formatted.startsWith("RAW"))
    }
}
