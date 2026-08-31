package com.gpsclientes.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.PreferenceManager
import com.gpsclientes.data.local.ClienteEntity
import com.gpsclientes.data.location.LatLngPrecision
import javax.inject.Inject
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationRequest

/**
 * osmdroid implementation of MapProvider.
 * - Configures UserAgent via PreferenceManager (required by OSM tile policy).
 * - TileSource MAPNIK, zoom 15.0, centered on EL_VIGIA or pendingPin.
 * - Multi-touch enabled; markers for all pins + draggable pending pin.
 * - Long-press via MapEventsOverlay -> onLongPress(LatLng).
 * - Marker click -> onPinClick(entity).
 * - Lifecycle: onResume/onPause forwarded via LifecycleEventObserver.
 */
class OsmMapProvider @Inject constructor() : MapProvider {

    @Composable
    override fun MapContent(
        pins: List<ClienteEntity>,
        pendingPin: LatLng?,
        onLongPress: (LatLng) -> Unit,
        onPinClick: (ClienteEntity) -> Unit,
        onPendingDrag: (LatLng) -> Unit,
        onPendingConfirm: () -> Unit,
        centrarFix: LatLngPrecision?
    ) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        // Ensure osmdroid config is initialized once per composition
        remember(context) {
            Configuration.getInstance().load(
                context,
                PreferenceManager.getDefaultSharedPreferences(context)
            )
            Configuration.getInstance().userAgentValue = context.packageName
            Configuration.getInstance().tileFileSystemCacheMaxBytes = 500L * 1024 * 1024
            Configuration.getInstance().tileFileSystemCacheTrimBytes = 450L * 1024 * 1024
            true
        }

        val mapView = remember {
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)
                controller.setCenter(EL_VIGIA.toGeoPoint())
                // Enable hardware acceleration where available
                isTilesScaledToDpi = true
            }
        }

        // Keep latest onMyLocationChanged lambda for IMyLocationProvider callbacks
        // MyLocationNewOverlay — REQ-GPS-01 Fused coalesced visual (FusedLocationProviderClient via IMyLocationProvider, 1s) — uses applicationContext to avoid leak
        val appContext = context.applicationContext
        val fusedProvider = remember(appContext) {
            var selfRef: IMyLocationProvider? = null
            val provider = object : IMyLocationProvider {
                private var consumer: IMyLocationConsumer? = null
                private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)
                private var callback: com.google.android.gms.location.LocationCallback? = null
                override fun startLocationProvider(consumer: IMyLocationConsumer?): Boolean {
                    this.consumer = consumer
                    // Guard: don't request if permission not granted (avoid SecurityException, show Snackbar via ViewModel)
                    val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                        appContext, android.Manifest.permission.ACCESS_FINE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            appContext, android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!hasPerm) return false
                    val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).build()
                    val outer = selfRef
                    callback = object : com.google.android.gms.location.LocationCallback() {
                        override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                            result.lastLocation?.let { loc ->
                                outer?.let { consumer?.onLocationChanged(loc, it) }
                            }
                        }
                    }
                    try { fusedClient.requestLocationUpdates(request, callback!!, android.os.Looper.getMainLooper()) } catch (_: Exception) {}
                    return true
                }
                override fun stopLocationProvider() {
                    // Cancel callback to avoid leak
                    callback?.let { cb -> try { fusedClient.removeLocationUpdates(cb) } catch (_: Exception) {} }
                    callback = null
                    consumer = null
                }
                override fun getLastKnownLocation(): android.location.Location? = null
                override fun destroy() { stopLocationProvider() }
            }
            selfRef = provider
            provider
        }
        val myLocationOverlay = remember(mapView, fusedProvider) {
            MyLocationNewOverlay(fusedProvider, mapView).apply {
                // Style accuracy circle to Strava FC4C02 stroke 2 fill 0.12 per spec
                try {
                    val paintField = this::class.java.getDeclaredField("mCirclePaint").apply { isAccessible = true }
                    val paint = paintField.get(this) as Paint
                    paint.color = 0xFFFC4C02.toInt()
                    paint.strokeWidth = 2f
                    paint.isAntiAlias = true
                } catch (_: Exception) {
                    // fallback: try superclass field name
                    try {
                        val pf = MyLocationNewOverlay::class.java.superclass?.getDeclaredField("mCirclePaint")
                        pf?.isAccessible = true
                        (pf?.get(this) as? Paint)?.let { p ->
                            p.color = 0xFFFC4C02.toInt()
                            p.strokeWidth = 2f
                        }
                    } catch (_: Exception) {}
                }
                // Only auto-enable if permission already granted; FAB will trigger otherwise — check fresh each time
                val grantedNow = androidx.core.content.ContextCompat.checkSelfPermission(
                    appContext, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    androidx.core.content.ContextCompat.checkSelfPermission(
                        appContext, android.Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (grantedNow) {
                    try { enableMyLocation() } catch (_: Exception) {}
                }
            }
        }

        // Lifecycle forwarding — check permission each ON_RESUME (FAB dead after grant fix) + use applicationContext
        DisposableEffect(lifecycleOwner, mapView, myLocationOverlay) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        mapView.onResume()
                        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                            appContext, android.Manifest.permission.ACCESS_FINE_LOCATION
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                            androidx.core.content.ContextCompat.checkSelfPermission(
                                appContext, android.Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (granted) try { myLocationOverlay.enableMyLocation() } catch (_: Exception) {}
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        try { myLocationOverlay.disableMyLocation() } catch (_: Exception) {}
                        mapView.onPause()
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            // Ensure resume initially — check permission fresh each time
            mapView.onResume()
            val initiallyGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                appContext, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    appContext, android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (initiallyGranted) try { myLocationOverlay.enableMyLocation() } catch (_: Exception) {}
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                try { myLocationOverlay.disableMyLocation() } catch (_: Exception) {}
                mapView.onPause()
            }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { mv ->
                // Re-center if pendingPin moves, else keep pins visible
                // Don't animate every recomposition; only adjust center if first load or pending moved
                // Keep current center unless app wants to recenter — we just ensure overlays updated

                mv.overlays.clear()

                // MyLocation overlay first (accuracy circle + blue dot)
                if (!mv.overlays.contains(myLocationOverlay)) {
                    mv.overlays.add(myLocationOverlay)
                } else {
                    // ensure it stays at index 0 for accuracy circle behind pins
                    mv.overlays.remove(myLocationOverlay)
                    mv.overlays.add(0, myLocationOverlay)
                }

                // Long-press overlay must be second so it receives events
                val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false
                    override fun longPressHelper(p: GeoPoint?): Boolean {
                        p?.let { onLongPress(it.toLatLng()) }
                        return true
                    }
                })
                mv.overlays.add(eventsOverlay)

                // Pending route polyline — connects pending pins (web: refreshPendingView L.polyline #FC4C02 weight 5)
                if (pins.size > 1) {
                    val points = pins.mapNotNull { e -> val la = e.lat; val ln = e.lng; if (la != null && ln != null) GeoPoint(la, ln) else null }
                    if (points.size > 1) {
                        try {
                            val polyline = Polyline().apply { setPoints(points); outlinePaint.color = 0xFFFC4C02.toInt(); outlinePaint.strokeWidth = 8f; outlinePaint.isAntiAlias = true }
                            mv.overlays.add(polyline)
                        } catch (_: Exception) {}
                    }
                }

                // Pins & Clusters — RadiusMarkerClusterer equivalent maxRadius 40, Strava tokens // fix cache: parity 40
                // Clusters >2 within 40px -> chip 40dp (#1E1E1E / #FC4C02), single shows numbered pin 72px FC4C02, tap expands
                val maxClusterRadiusPx = 40 // fix cache: parity with frontend 40 (was 50)
                if (pins.isNotEmpty()) {
                    // Build clusters by pixel proximity using projection
                    val projection = mv.projection
                    val clusters = mutableListOf<MutableList<ClienteEntity>>()
                    val clusterCenters = mutableListOf<GeoPoint>()
                    val pinIndexMap = pins.mapIndexed { i, e -> e.id to (i + 1) }.toMap()
                    pins.forEach { entity ->
                        val gp = GeoPoint(entity.lat ?: EL_VIGIA.latitude, entity.lng ?: EL_VIGIA.longitude)
                        val pt = android.graphics.Point()
                        try { projection.toPixels(gp, pt) } catch (_: Exception) { pt.set(Int.MAX_VALUE, Int.MAX_VALUE) }
                        var merged = -1
                        for (k in clusters.indices) {
                            val cpt = android.graphics.Point()
                            try { projection.toPixels(clusterCenters[k], cpt) } catch (_: Exception) { continue }
                            val dx = pt.x - cpt.x
                            val dy = pt.y - cpt.y
                            if (dx * dx + dy * dy <= maxClusterRadiusPx * maxClusterRadiusPx) { merged = k; break }
                        }
                        if (merged >= 0) {
                            clusters[merged].add(entity)
                        } else {
                            clusters.add(mutableListOf(entity))
                            clusterCenters.add(gp)
                        }
                    }
                    clusters.forEachIndexed { cIdx, cluster ->
                        if (cluster.size == 1) {
                            val entity = cluster[0]
                            val number = pinIndexMap[entity.id] ?: (cIdx + 1)
                            val marker = Marker(mv).apply {
                                position = GeoPoint(entity.lat ?: EL_VIGIA.latitude, entity.lng ?: EL_VIGIA.longitude)
                                title = entity.nombreCanonico
                                snippet = entity.textoBreve ?: entity.referenciaManual ?: ""
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                icon = getMarkerIcon(context, entity, number)
                                setOnMarkerClickListener { _, _ ->
                                    onPinClick(entity)
                                    true
                                }
                            }
                            mv.overlays.add(marker)
                        } else {
                            // Cluster chip — RadiusMarkerClusterer maxRadius 50, chip 72px #1E1E1E / #FC4C02
                            val avgLat = cluster.mapNotNull { it.lat }.average().let { if (it.isNaN()) clusterCenters[cIdx].latitude else it }
                            val avgLng = cluster.mapNotNull { it.lng }.average().let { if (it.isNaN()) clusterCenters[cIdx].longitude else it }
                            val clusterPos = GeoPoint(avgLat, avgLng)
                            val count = cluster.size
                            val marker = Marker(mv).apply {
                                position = clusterPos
                                title = "$count clientes"
                                snippet = "Toca para expandir"
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                icon = createClusterDrawable(context, count)
                                setOnMarkerClickListener { _, mapView ->
                                    // spiderfy/expand: zoom in and animate to cluster
                                    try {
                                        val curZoom = mapView.zoomLevelDouble
                                        mapView.controller.animateTo(clusterPos)
                                        if (curZoom < 19) mapView.controller.setZoom(curZoom + 2)
                                    } catch (_: Exception) {}
                                    true
                                }
                            }
                            mv.overlays.add(marker)
                        }
                    }
                }

                // Pending draggable pin
                pendingPin?.let { ll ->
                    val pendingMarker = Marker(mv).apply {
                        position = ll.toGeoPoint()
                        title = "Pending pin"
                        snippet = "Drag to adjust"
                        isDraggable = true
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        // Azure hue equivalent: use default but keep draggable
                        setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                            override fun onMarkerDragStart(m: Marker?) {}
                            override fun onMarkerDrag(m: Marker?) {}
                            override fun onMarkerDragEnd(m: Marker?) {
                                m?.position?.let { gp -> onPendingDrag(gp.toLatLng()) }
                            }
                        })
                    }
                    mv.overlays.add(pendingMarker)
                    // Center on pending pin when first created
                    mv.controller.animateTo(pendingMarker.position)
                }

                // Centrar on MyLocation fix when requested
                centrarFix?.let { fix ->
                    mv.controller.animateTo(GeoPoint(fix.lat, fix.lng))
                    if (mv.zoomLevelDouble < 15.0) mv.controller.setZoom(16.0)
                }

                // If no pending and pins exist, ensure reasonable view — center on El Vigia initially
                if (pendingPin == null && pins.isEmpty() && centrarFix == null) {
                    // keep existing center/zoom
                }

                mv.invalidate()
            }
        )
    }

    private fun getMarkerIcon(context: Context, entity: ClienteEntity, number: Int): Drawable? {
        return try {
            createNumberedDrawable(context, number)
        } catch (_: Exception) {
            null
        }
    }

    // Dot-only Strava pin — no numbers (createNumberedDrawable kept for compat)
    private fun createDotDrawable(context: Context): Drawable {
        val size = 48
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFC4C02.toInt(); style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f }
        val radius = size / 2f - 3f
        canvas.drawCircle(size / 2f, size / 2f, radius, bgPaint)
        canvas.drawCircle(size / 2f, size / 2f, radius, strokePaint)
        return BitmapDrawable(context.resources, bitmap)
    }

    // parity web: pins numerados 1..n sobre dot 72px (web createNumberedIcon) — restaura número, size 72 to match test
    private fun createNumberedDrawable(context: Context, number: Int): Drawable {
        val size = 72
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFC4C02.toInt(); style = Paint.Style.FILL }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 4f }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 28f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        val radius = size / 2f - 3f
        canvas.drawCircle(size / 2f, size / 2f, radius, bgPaint)
        canvas.drawCircle(size / 2f, size / 2f, radius, strokePaint)
        val label = if (number > 99) "99+" else number.toString()
        if (label.length > 2) textPaint.textSize = 22f
        val y = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, size / 2f, y, textPaint)
        return BitmapDrawable(context.resources, bitmap)
    }

    // RadiusMarkerClusterer chip — 72px, bg #1E1E1E border #FC4C02, white count, 99+ cap
    private fun createClusterDrawable(context: Context, count: Int): Drawable {
        val size = 72
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E1E1E.toInt(); style = Paint.Style.FILL }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFC4C02.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 30f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        val radius = size / 2f - 2f
        canvas.drawCircle(size / 2f, size / 2f, radius, bgPaint)
        canvas.drawCircle(size / 2f, size / 2f, radius, borderPaint)
        val label = if (count > 99) "99+" else count.toString()
        if (label.length > 2) textPaint.textSize = 24f
        val y = size / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, size / 2f, y, textPaint)
        return BitmapDrawable(context.resources, bitmap)
    }
}
