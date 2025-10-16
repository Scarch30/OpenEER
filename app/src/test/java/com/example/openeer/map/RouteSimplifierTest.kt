package com.example.openeer.map

import com.example.openeer.data.block.RoutePointPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSimplifierTest {

    @Test
    fun `simplify reduces polyline complexity`() {
        val points = listOf(
            RoutePointPayload(0.0, 0.0, 0L),
            RoutePointPayload(0.0, 0.0001, 10L),
            RoutePointPayload(0.0, 0.0002, 20L),
            RoutePointPayload(0.0001, 0.0002, 30L),
            RoutePointPayload(0.0002, 0.0002, 40L),
            RoutePointPayload(0.0002, 0.0003, 50L),
        )

        val simplified = RouteSimplifier.simplifyMeters(points, 8.0)

        assertTrue("simplified list should be smaller", simplified.size in 3 until points.size)
    }

    @Test
    fun `simplify keeps first and last points`() {
        val points = samplePoints()

        val simplified = RouteSimplifier.simplifyMeters(points, 8.0)

        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
    }

    @Test
    fun `large epsilon collapses to endpoints`() {
        val points = samplePoints()

        val simplified = RouteSimplifier.simplifyMeters(points, 1000.0)

        assertEquals(2, simplified.size)
        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
    }

    private fun samplePoints(): List<RoutePointPayload> {
        return listOf(
            RoutePointPayload(0.0, 0.0, 0L),
            RoutePointPayload(0.00005, 0.00002, 10L),
            RoutePointPayload(0.0001, 0.00005, 20L),
            RoutePointPayload(0.00015, 0.00007, 30L),
            RoutePointPayload(0.0002, 0.0001, 40L),
            RoutePointPayload(0.00025, 0.00012, 50L),
        )
    }
}
