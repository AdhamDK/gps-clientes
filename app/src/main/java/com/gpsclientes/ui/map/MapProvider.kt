package com.gpsclientes.ui.map

import androidx.compose.runtime.Composable
import com.gpsclientes.data.local.ClienteEntity

/**
 * Abstraction for map rendering — now backed by osmdroid (MAPNIK tiles).
 * No Google Maps dependency; no API key required.
 */
interface MapProvider {
    @Composable
    fun MapContent(
        pins: List<ClienteEntity>,
        pendingPin: LatLng?,
        onLongPress: (LatLng) -> Unit,
        onPinClick: (ClienteEntity) -> Unit,
        onPendingDrag: (LatLng) -> Unit,
        onPendingConfirm: () -> Unit,
        centrarFix: com.gpsclientes.data.location.LatLngPrecision?
    )
}

/**
 * Feature flag — OSM map is always enabled (no API key gating).
 * ENABLE_MAP BuildConfig flag retained for rollout but defaults to true.
 */
object MapFeatureFlag {
    val enabled: Boolean get() = true
    val hasApiKey: Boolean get() = true
}
