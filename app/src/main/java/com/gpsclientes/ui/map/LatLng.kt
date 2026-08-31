package com.gpsclientes.ui.map

/**
 * Lightweight LatLng value — replaces Google Maps LatLng
 * after migration to osmdroid. Used by ViewModel, MapProvider and UI.
 */
data class LatLng(
    val latitude: Double,
    val longitude: Double
)

/** Default center: El Vigia (8.6167, -71.65) */
val EL_VIGIA = LatLng(8.6167, -71.65)

/** Convert to osmdroid GeoPoint */
fun LatLng.toGeoPoint(): org.osmdroid.util.GeoPoint =
    org.osmdroid.util.GeoPoint(latitude, longitude)

/** Convert from osmdroid GeoPoint */
fun org.osmdroid.util.GeoPoint.toLatLng(): LatLng =
    LatLng(latitude, longitude)
