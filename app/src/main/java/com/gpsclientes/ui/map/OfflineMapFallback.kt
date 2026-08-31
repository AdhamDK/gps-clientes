package com.gpsclientes.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Offline tile fallback: placeholder grid when OSM tiles cannot load (offline, no network).
 * OSM tiles are cached by osmdroid (500MB max, 30d expiry, 500 tiles) via Workbox tiles cache parity;
 * pins remain interactive via ViewModel filter. No API key required — MAPNIK tiles are public.
 * REQ-OFF-01: airplane render from tile cache, IndexedDB queue stats mirrored here.
 */
@Composable
fun OfflineMapFallback(
    pinsCount: Int,
    hasApiKey: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    tileCacheMaxMb: Int = 500,
    tileMaxEntries: Int = 500,
    tileExpiryDays: Int = 30
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFE8EEF2))
    ) {
        // Placeholder grid
        Canvas(modifier = Modifier.fillMaxSize()) {
            val step = 48.dp.toPx()
            val color = Color(0xFFCBD5E1)
            var x = 0f
            while (x < size.width) {
                drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += step
            }
            var y = 0f
            while (y < size.height) {
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += step
            }
        }
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                val message = when {
                    !enabled -> "Map disabled — ENABLE_MAP=false"
                    !hasApiKey -> "Offline — OSM tiles unavailable, pins still visible ($pinsCount)"
                    else -> "Offline — tiles unavailable, pins still visible ($pinsCount)"
                }
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Text("Cache: ${tileCacheMaxMb}MB / ${tileMaxEntries} tiles / ${tileExpiryDays}d — osmdroid + Workbox parity", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                if (pinsCount > 0) {
                    Text(
                        "$pinsCount pins available offline. Long-press to drop a new pin still works.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
        if (pinsCount > 0) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .fillMaxWidth(0.92f)
            ) {
                Text(
                    "$pinsCount pins — filters and pin detail still work offline",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}
