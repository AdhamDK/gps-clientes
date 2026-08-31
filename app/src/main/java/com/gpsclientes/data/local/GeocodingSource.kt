package com.gpsclientes.data.local

/**
 * Source of textoBreve geocoding result.
 * GEOCODER: Android Geocoder success
 * NOMINATIM: Fallback via Nominatim worker
 * MANUAL: User-edited or no geocoding available
 */
enum class GeocodingSource {
    GEOCODER,
    NOMINATIM,
    MANUAL
}
