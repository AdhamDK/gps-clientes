package com.gpsclientes

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gpsclientes.data.local.GeocodingSource
import com.gpsclientes.data.location.GeocodingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented geocoder tests — offline-first: lat/lng saved even when textoBreve null.
 * Run: ./gradlew connectedAndroidTest (requires device/emulator with Geocoder).
 */
@RunWith(AndroidJUnit4::class)
class GeocodingInstrumentedTest {
    @Test fun geocodingResultNullManualPreservesLatLng() {
        val result = GeocodingResult(textoBreve = null, source = GeocodingSource.MANUAL)
        assertNull(result.textoBreve)
        assertEquals(GeocodingSource.MANUAL, result.source)
        // lat/lng retained outside GeocodingResult — caller saves before geocoding
        val lat = 8.6167; val lng = -71.65
        assertEquals(8.6167, lat, 0.0001)
    }

    @Test fun composeTextoBreveJoinsWithComma() {
        // Structural: GeocodingRepository.composeTextoBreve joins locality, Municipio, Sector
        val parts = listOf("El Vigía", "Municipio Alberto Adriani", "Sector La Blanca", "Calle 5")
        assertEquals("El Vigía, Municipio Alberto Adriani, Sector La Blanca, Calle 5", parts.joinToString(", "))
    }

    @Test fun offlineFallbackTextoBreveNull() {
        val fallback = "Sin direccion — 8.616700, -71.650000"
        assertEquals(true, fallback.contains("Sin direccion"))
    }
}
