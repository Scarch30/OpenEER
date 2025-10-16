package com.example.openeer.map

import org.junit.Test
import org.junit.Assert.*
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.maplibre.android.geometry.LatLng

class RouteDirectionsUrlTest {

    @Test
    fun `returns null when less than two points`() {
        assertNull(buildMapsUrl(emptyList()))
        assertNull(buildMapsUrl(listOf(LatLng(48.0, 2.0))))
    }

    @Test
    fun `builds url for origin and destination`() {
        val points = listOf(
            LatLng(48.8566, 2.3522),
            LatLng(48.8584, 2.2945)
        )

        val url = buildMapsUrl(points)
        assertNotNull(url)

        val query = URI(url).query
        assertNotNull(query)
        val params = parseParams(query!!)

        assertEquals("48.856600,2.352200", params["origin"])
        assertEquals("48.858400,2.294500", params["destination"])
        assertEquals("walking", params["travelmode"])
        assertEquals(null, params["waypoints"])
    }

    @Test
    fun `builds url with waypoints when within limit`() {
        val points = listOf(
            LatLng(40.7128, -74.0060),
            LatLng(40.7138, -74.0050),
            LatLng(40.7148, -74.0040),
            LatLng(40.7158, -74.0030),
            LatLng(40.7168, -74.0020),
            LatLng(40.7178, -74.0010)
        )

        val url = buildMapsUrl(points)
        assertNotNull(url)

        val query = URI(url).query
        assertNotNull(query)
        val params = parseParams(query!!)
        val wp = params["waypoints"]?.split('|')
        assertNotNull(wp)
        val waypoints = wp!!
        assertEquals(4, waypoints.size)
    }

    @Test
    fun `downsamples waypoints when above limit`() {
        val points = (0 until 30).map { index ->
            LatLng(35.0 + index * 0.001, -120.0 - index * 0.001)
        }

        val url = buildMapsUrl(points)
        assertNotNull(url)

        val query = URI(url).query
        assertNotNull(query)
        val params = parseParams(query!!)
        val wp = params["waypoints"]?.split('|')
        assertNotNull(wp)
        val waypoints = wp!!
        assertEquals(9, waypoints.size)
    }

    private fun parseParams(query: String): Map<String, String?> {
        return query.split('&').associate { pair ->
            val (key, value) = pair.split('=')
            val decoded = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
            key to decoded
        }
    }
}
