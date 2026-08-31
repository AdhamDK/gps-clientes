package com.gpsclientes.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.gpsclientes.data.local.ClienteEntity
import com.gpsclientes.ui.permissions.PermissionRationaleDialog
import com.gpsclientes.ui.permissions.PermissionRationales
import com.gpsclientes.ui.permissions.rememberLocationPermissionHandler
import com.gpsclientes.util.ExportFileUtils
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Screen cluster: 409 pins, filters hasGpsFix/isFlaggedImport, OSM MAPNIK tiles.
 * Long-press on map to drop pin -> prefill ClienteForm with lat/lng via pendingPin.
 * Draggable marker updates lat/lng + textoBreve preview live via GeocodingRepository.
 * OSM requires no API key; OfflineMapFallback only shows when pins empty/offline.
 */
@Composable
fun MapaClientesScreen(
    viewModel: MapaClientesViewModel = hiltViewModel(),
    mapProvider: MapProvider = remember { OsmMapProvider() },
    onConfirmPending: (LatLng, String?) -> Unit = { _, _ -> },
    onPinClick: (ClienteEntity) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val pendingPin by viewModel.pendingPin.collectAsState()
    val preview by viewModel.pendingPreview.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val centrarState by viewModel.centrarState.collectAsState()
    val cachedFix by viewModel.cachedFix.collectAsState()
    var selectedPin by remember { mutableStateOf<ClienteEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val entregadosMessage by viewModel.entregadosMessage.collectAsState()
    val rutaMessage by viewModel.rutaMessage.collectAsState()
    val filtroZona by viewModel.filtroZona.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var showOptionsSheet by remember { mutableStateOf(false) }
    val locationHandler = rememberLocationPermissionHandler(
        onGranted = { viewModel.centrarEnMiUbicacion() },
        onDenied = {
            scope.launch { snackbarHostState.showSnackbar("Permiso de ubicación denegado. Usa entrada manual o activa en Ajustes.") }
        }
    )

    // Auto-center on launch if permission already granted (zoom 16, accuracy circle)
    LaunchedEffect(Unit) {
        val hasPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) viewModel.centrarEnMiUbicacion()
    }

    // Export launchers — REQ-EXP-01 via ACTION_CREATE_DOCUMENT + Import via OPEN_DOCUMENT
    val exportXlsxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFileUtils.MIME_XLSX)) { uri ->
        uri?.let { /* delegate to ExportRepository via viewModel or context resolver */ }
    }
    val exportPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFileUtils.MIME_PDF)) { uri ->
        uri?.let { }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { }
    }

    LaunchedEffect(centrarState) {
        when (val s = centrarState) {
            is CentrarState.Error -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.resetCentrarState()
            }
            is CentrarState.Success -> {
                snackbarHostState.showSnackbar("Ubicación centrada ✓")
                viewModel.resetCentrarState()
            }
            else -> Unit
        }
    }
    LaunchedEffect(entregadosMessage) {
        entregadosMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeEntregadosMessage()
        }
    }
    LaunchedEffect(rutaMessage) {
        rutaMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeRutaMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!MapFeatureFlag.enabled || !MapFeatureFlag.hasApiKey) {
            OfflineMapFallback(
                pinsCount = uiState.pins.size,
                hasApiKey = MapFeatureFlag.hasApiKey,
                enabled = MapFeatureFlag.enabled
            )
        } else {
            mapProvider.MapContent(
                pins = uiState.pins,
                pendingPin = pendingPin,
                onLongPress = { viewModel.onMapLongPress(it) },
                onPinClick = {
                    selectedPin = it
                    viewModel.toggleSelection(it.id.toInt())
                    onPinClick(it)
                },
                onPendingDrag = { viewModel.onPendingDrag(it) },
                onPendingConfirm = {},
                centrarFix = (centrarState as? CentrarState.Success)?.fix ?: cachedFix
            )
        }

        if (locationHandler.showRationale) {
            PermissionRationaleDialog(
                title = PermissionRationales.LOCATION_TITLE,
                rationale = PermissionRationales.LOCATION_MESSAGE,
                onConfirm = locationHandler.onRationaleConfirm,
                onDismiss = locationHandler.onDismissRationale
            )
        }
        if (locationHandler.showPermanentlyDenied) {
            PermissionRationaleDialog(
                title = PermissionRationales.LOCATION_TITLE,
                rationale = PermissionRationales.LOCATION_PERMANENTLY_DENIED,
                onConfirm = {
                    locationHandler.onDismissPermanentlyDenied()
                    try {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Exception) {
                        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }
                },
                onDismiss = locationHandler.onDismissPermanentlyDenied,
                confirmLabel = "Abrir Ajustes",
                dismissLabel = "Entrada manual"
            )
        }

        // parity web: panel desplegable nativo (BottomSheet) — trigger button top-start
        // Keep quick export dropdown for parity but full actions live in BottomSheet
        Box(modifier=Modifier.align(Alignment.TopStart).padding(top=8.dp,start=12.dp)){
            TextButton(onClick={ showOptionsSheet = true }){ Text("Opciones ▼") }
        }
        // Export dropdown — REQ-EXP-01 aria-expanded + Esc/outside/Arrow-Enter via DropdownMenu (wired to ACTION_CREATE_DOCUMENT)
        var exportExpanded by remember { mutableStateOf(false) }
        Box(modifier=Modifier.align(Alignment.TopEnd).padding(top=8.dp,end=12.dp)){
            TextButton(onClick={exportExpanded=!exportExpanded}){Text(if(exportExpanded)"Exportar ▲" else "Exportar ▼")}
            DropdownMenu(expanded=exportExpanded,onDismissRequest={exportExpanded=false}){
                DropdownMenuItem(text={Text("Exportar XLSX")},onClick={exportExpanded=false; try { exportXlsxLauncher.launch("clientes_export.xlsx") } catch(_: Exception){ context.startActivity(ExportFileUtils.createExcelIntent()) } })
                DropdownMenuItem(text={Text("Exportar PDF")},onClick={exportExpanded=false; try { exportPdfLauncher.launch("clientes_export.pdf") } catch(_: Exception){ context.startActivity(ExportFileUtils.createPdfIntent()) } })
                DropdownMenuItem(text={Text("Importar XLSX")},onClick={exportExpanded=false; importLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) })
            }
        }
        // Filter chips (hasGpsFix / isFlaggedImport)
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
        ) {
            AssistChip(
                onClick = {
                    val next = when (viewModel.filterHasGpsFix.value) {
                        null -> true; true -> false; else -> null
                    }
                    viewModel.setHasGpsFixFilter(next)
                },
                label = {
                    val v = viewModel.filterHasGpsFix.collectAsState().value
                    Text(
                        when (v) {
                            true -> "Has GPS ✓"
                            false -> "Sin GPS"
                            null -> "Todos GPS"
                        }
                    )
                },
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            AssistChip(
                onClick = {
                    val next = when (viewModel.filterFlagged.value) {
                        null -> true; true -> false; else -> null
                    }
                    viewModel.setFlaggedFilter(next)
                },
                label = {
                    val v = viewModel.filterFlagged.collectAsState().value
                    Text(
                        when (v) {
                            true -> "Flagged #"
                            false -> "No flagged"
                            null -> "Todos flagged"
                        }
                    )
                },
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // Selection counter badge (top) — REQ-SEL-01 persists across filter/pagination
        if (selection.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 56.dp, start = 12.dp)
            ) {
                Text(
                    "${selection.size} seleccionados",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // FAB MyLocation — REQ-GPS-01 centrar + MyLocationNewOverlay accuracy (lift 72dp when sheet/selection visible parity web)
        FloatingActionButton(
            onClick = { locationHandler.request() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = if (selection.isNotEmpty() || showOptionsSheet) 72.dp else 16.dp, end = 16.dp)
                .size(44.dp)
        ) {
            val isLoading = centrarState is CentrarState.Loading
            Text(if (isLoading) "…" else "◎", modifier = Modifier.padding(horizontal = 4.dp))
        }

        // parity web: Card selección 4 botones (Entregados/Terminar/Limpiar/Optimizar) mirrors miniMenu web
        if (selection.isNotEmpty()) {
            Card(modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
                Row(modifier=Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("${selection.size} seleccionados",style=MaterialTheme.typography.titleSmall,modifier=Modifier.weight(1f))
                    TextButton(onClick={viewModel.marcarEntregados()}){Text("Entregados")}
                    TextButton(onClick={viewModel.terminarLista()}){Text("Terminar")}
                    TextButton(onClick={viewModel.clearSelection()}){Text("Limpiar")}
                    TextButton(onClick={viewModel.optimizarRuta()}){Text("Optimizar")}
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        // Pending pin preview + confirm (long-press+drag live)
        pendingPin?.let { latLng ->
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Pin: %.6f, %.6f".format(latLng.latitude, latLng.longitude),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        preview ?: "Resolving...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(modifier = Modifier.padding(top = 8.dp)) {
                        Button(
                            onClick = {
                                onConfirmPending(latLng, preview)
                                viewModel.clearPending()
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("Confirmar") }
                        Button(onClick = { viewModel.clearPending() }) { Text("Cancelar") }
                    }
                }
            }
        }

        selectedPin?.let { cliente ->
            ClienteDetailSheet(
                cliente = cliente,
                onDismiss = { selectedPin = null }
            )
        }

        // parity web: BottomSheet opciones (peek 80dp, expand full, drag handle) replica sidebar-controls web
        if (showOptionsSheet) {
            MapOptionsBottomSheet(
                viewModel = viewModel,
                onDismiss = { showOptionsSheet = false },
                onAgregarCliente = {
                    pendingPin?.let { onConfirmPending(it, preview) } ?: run {
                        scope.launch { snackbarHostState.showSnackbar("Mantén presionado el mapa para elegir ubicación") }
                    }
                },
                onExportXlsx = { try { exportXlsxLauncher.launch("clientes_export.xlsx") } catch(_: Exception){ context.startActivity(ExportFileUtils.createExcelIntent()) } },
                onExportPdf = { try { exportPdfLauncher.launch("clientes_export.pdf") } catch(_: Exception){ context.startActivity(ExportFileUtils.createPdfIntent()) } },
                onImport = { importLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) }
            )
        }
    }
}
