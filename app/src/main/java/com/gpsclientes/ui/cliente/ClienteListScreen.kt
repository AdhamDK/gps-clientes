package com.gpsclientes.ui.cliente

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gpsclientes.data.local.ClienteEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClienteListScreen(
    viewModel: ClienteListViewModel = hiltViewModel(),
    searchViewModel: ClienteSearchViewModel = hiltViewModel()
) {
    val clientes by viewModel.clientes.collectAsState()
    val query by searchViewModel.query.collectAsState()
    val results by searchViewModel.results.collectAsState()
    val importMsg by viewModel.importMessage.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val entregadosMsg by viewModel.entregadosMessage.collectAsState()
    val rutaMsg by viewModel.rutaMessage.collectAsState()
    val filtroZona by viewModel.filtroZona.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var selected by remember { mutableStateOf<ClienteEntity?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<ClienteEntity?>(null) }
    var editing by remember { mutableStateOf<ClienteEntity?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    // Display logic: if query blank show full list (31 GPX), else filtered via ClienteSearchViewModel LIKE NFD
    // parity web: also filter by filtroZona (Todas, VIGIA, Zona Norte, Lunes, Centro + dinámico)
    val baseList = if (query.isBlank()) clientes else results
    val displayList = if (filtroZona.isNullOrBlank()) baseList else baseList.filter { it.zonaRuta == filtroZona }
    val titleCount = clientes.size
    // parity web: dynamic zonas distinct for dropdown
    val staticZonas = listOf("Todas", "VIGIA", "Zona Norte", "Lunes", "Centro")
    val dynamicZonas = remember(clientes) { clientes.mapNotNull { it.zonaRuta }.distinct().sorted() }
    val allZonas = remember(staticZonas, dynamicZonas) {
        val s = linkedSetOf<String>(); staticZonas.forEach { s.add(it) }; dynamicZonas.forEach { s.add(it) }; s.toList()
    }

    LaunchedEffect(importMsg) {
        importMsg?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeImportMessage()
        }
    }
    LaunchedEffect(entregadosMsg) {
        entregadosMsg?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeEntregadosMessage()
        }
    }
    LaunchedEffect(rutaMsg) {
        rutaMsg?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeRutaMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Text("Clientes ($titleCount)", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            // Search field + filtroZona dropdown parity web (search-row + zona select)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { searchViewModel.onQueryChange(it) },
                    label = { Text("Buscar") },
                    placeholder = { Text("Vigía, RIF…") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                var zonaExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = zonaExpanded, onExpandedChange = { zonaExpanded = !zonaExpanded }, modifier = Modifier.weight(0.9f)) {
                    OutlinedTextField(
                        value = filtroZona ?: "Todas",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Zona") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = zonaExpanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        singleLine = true
                    )
                    ExposedDropdownMenu(expanded = zonaExpanded, onDismissRequest = { zonaExpanded = false }) {
                        allZonas.forEach { zona ->
                            DropdownMenuItem(
                                text = { Text(if (zona == "Todas") "Todas las zonas" else zona) },
                                onClick = { viewModel.setFiltroZona(if (zona == "Todas") null else zona); zonaExpanded = false },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (query.isBlank()) "$titleCount clientes" else "${displayList.size} resultados para \"$query\"",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = { viewModel.importGpx31() }) {
                    Text("Importar GPX 31")
                }
            }
            // Selection badge + clear (mirrors web selectionBadge)
            if (selection.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${selection.size} seleccionados",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    TextButton(onClick = { viewModel.clearSelection() }) { Text("Limpiar") }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (displayList.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (query.isBlank()) "Sin clientes. Importa GPX 31 o agrega uno."
                            else "Sin resultados",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(8.dp))
                        if (query.isBlank()) {
                            Button(onClick = { viewModel.importGpx31() }) { Text("Importar GPX 31") }
                        }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(displayList, key = { it.id }) { cliente ->
                        ClienteSelectableRow(
                            cliente = cliente,
                            checked = selection.contains(cliente.id),
                            onCheckedChange = { viewModel.toggleSelection(cliente.id) },
                            onClick = { selected = cliente }
                        )
                    }
                }
            }
        }

        // Mini-menu with counter (web parity: #miniMenu) — 4 botones parity web: Entregados/Terminar/Limpiar/Optimizar
        if (selection.isNotEmpty()) {
            Card(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${selection.size} seleccionados", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.marcarEntregados() }) { Text("Entregados") }
                    TextButton(onClick = { viewModel.terminarLista() }) { Text("Terminar") }
                    TextButton(onClick = { viewModel.clearSelection() }) { Text("Limpiar") }
                    TextButton(onClick = { viewModel.optimizarRuta() }) { Text("Optimizar") }
                }
            }
        }

        // FAB Agregar — lifted when selection bar visible (FAB 44dp 72dp lift parity)
        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = if (selection.isNotEmpty()) 72.dp else 16.dp, end = 16.dp)
        ) { Text("+") }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    // Detail sheet -> Editar / Borrar / Actualizar GPS
    selected?.let { cliente ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(cliente.nombreCanonico) },
            text = {
                Column {
                    Text(cliente.textoBreve ?: cliente.referenciaManual ?: "Sin direccion", style = MaterialTheme.typography.bodyMedium)
                    cliente.zonaRuta?.let { Text("Zona: $it", style = MaterialTheme.typography.bodySmall) }
                    if (cliente.lat != null && cliente.lng != null) {
                        Text("%.6f, %.6f".format(cliente.lat, cliente.lng), style = MaterialTheme.typography.bodySmall)
                        cliente.precisionMeters?.let { Text("±${it}m", style = MaterialTheme.typography.bodySmall) }
                    }
                    if (cliente.isFlaggedImport) Text("# flagged import", color = MaterialTheme.colorScheme.error)
                    cliente.rif?.let { Text("RIF: $it", style = MaterialTheme.typography.bodySmall) }
                    cliente.telefono?.let { Text("Tel: $it", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        selected = null
                        viewModel.actualizarGps(cliente) { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                    }) { Text("Actualizar GPS") }
                    TextButton(onClick = { editing = cliente; selected = null }) { Text("Editar") }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDeleteConfirm = cliente; selected = null }) { Text("Borrar") }
                    TextButton(onClick = { selected = null }) { Text("Cerrar") }
                }
            }
        )
    }

    showDeleteConfirm?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Borrar cliente") },
            text = { Text("¿Borrar \"${toDelete.nombreCanonico}\"? Esta accion no se puede deshacer.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteCliente(toDelete)
                    showDeleteConfirm = null
                    scope.launch { snackbarHostState.showSnackbar("Cliente borrado") }
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text("Cancelar") } }
        )
    }

    editing?.let { e ->
        EditClienteDialog(
            entity = e,
            onDismiss = { editing = null },
            onSave = { updated ->
                viewModel.updateCliente(updated)
                editing = null
                scope.launch { snackbarHostState.showSnackbar("Cliente actualizado") }
            }
        )
    }

    if (showAdd) {
        AddClienteDialog(
            onDismiss = { showAdd = false },
            viewModel = viewModel,
            snackbarHostState = snackbarHostState
        )
    }
}

@Composable
private fun ClienteSelectableRow(
    cliente: ClienteEntity,
    checked: Boolean,
    onCheckedChange: () -> Unit,
    onClick: () -> Unit
) {
    val hasFix = cliente.hasGpsFix && cliente.lat != null && cliente.lng != null
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onCheckedChange() },
                enabled = hasFix
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cliente.nombreCanonico, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (hasFix) Text("📍", style = MaterialTheme.typography.labelSmall)
                    else Text("⚠ Sin GPS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    cliente.textoBreve ?: cliente.referenciaManual ?: "Sin direccion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row {
                    cliente.zonaRuta?.let { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 8.dp)) }
                    if (cliente.lat != null && cliente.lng != null) {
                        Text("%.4f, %.4f".format(cliente.lat, cliente.lng), style = MaterialTheme.typography.labelSmall)
                    }
                    if (cliente.isFlaggedImport) {
                        Spacer(Modifier.width(8.dp))
                        Text("# flagged", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ClienteRow(cliente: ClienteEntity, onClick: () -> Unit) {
    ClienteSelectableRow(cliente = cliente, checked = false, onCheckedChange = {}, onClick = onClick)
}

@Composable
private fun EditClienteDialog(entity: ClienteEntity, onDismiss: () -> Unit, onSave: (ClienteEntity) -> Unit) {
    var nombre by remember { mutableStateOf(entity.nombreCanonico) }
    var zona by remember { mutableStateOf(entity.zonaRuta ?: "") }
    var rif by remember { mutableStateOf(entity.rif ?: "") }
    var rifError by remember { mutableStateOf<String?>(null) }
    var textoBreve by remember { mutableStateOf(entity.textoBreve ?: "") }
    var lat by remember { mutableStateOf(entity.lat?.toString() ?: "") }
    var lng by remember { mutableStateOf(entity.lng?.toString() ?: "") }
    val rifRegex = remember { Regex("^[JVEGP]\\d{7,9}$") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar cliente") },
        text = {
            Column {
                OutlinedTextField(value = nombre, onValueChange = { nombre = it }, label = { Text("Nombre *") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = zona, onValueChange = { zona = it }, label = { Text("Zona Ruta") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = rif,
                    onValueChange = { rif = it.uppercase(); rifError = null },
                    label = { Text("RIF (J/V/E/G/P + 7-9 dígitos)") },
                    isError = rifError != null,
                    supportingText = { rifError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(value = textoBreve, onValueChange = { textoBreve = it }, label = { Text("Texto breve") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = lat, onValueChange = { lat = it }, label = { Text("Lat") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = lng, onValueChange = { lng = it }, label = { Text("Lng") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // parity web app.js:485 — RIF validation before save
                    val rifTrim = rif.trim().uppercase()
                    if (rifTrim.isNotBlank() && !rifRegex.matches(rifTrim)) {
                        rifError = "RIF inválido: J/V/E/G/P + 7-9 dígitos"
                        return@Button
                    }
                    val normalized = com.gpsclientes.domain.SearchNormalize.normalizeForSearch(nombre)
                    val latD = lat.toDoubleOrNull()
                    val lngD = lng.toDoubleOrNull()
                    onSave(
                        entity.copy(
                            nombreCanonico = nombre,
                            nombreNormalizado = normalized,
                            rif = rifTrim.takeIf { it.isNotBlank() },
                            zonaRuta = zona.takeIf { it.isNotBlank() },
                            textoBreve = textoBreve.takeIf { it.isNotBlank() },
                            lat = latD,
                            lng = lngD,
                            hasGpsFix = latD != null && lngD != null,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                },
                enabled = nombre.isNotBlank()
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun AddClienteDialog(
    onDismiss: () -> Unit,
    viewModel: ClienteListViewModel,
    snackbarHostState: SnackbarHostState
) {
    var nombre by remember { mutableStateOf("") }
    var zona by remember { mutableStateOf("") }
    var rif by remember { mutableStateOf("") }
    var rifError by remember { mutableStateOf<String?>(null) }
    val rifRegex = remember { Regex("^[JVEGP]\\d{7,9}$") }
    var textoBreve by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Agregar cliente") },
        text = {
            Column {
                OutlinedTextField(value = nombre, onValueChange = { nombre = it }, label = { Text("Nombre *") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = zona, onValueChange = { zona = it }, label = { Text("Zona Ruta") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = rif,
                    onValueChange = { rif = it.uppercase(); rifError = null },
                    label = { Text("RIF (J/V/E/G/P + 7-9 dígitos)") },
                    isError = rifError != null,
                    supportingText = { rifError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(value = textoBreve, onValueChange = { textoBreve = it }, label = { Text("Texto breve") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val rifTrim = rif.trim().uppercase()
                    // parity web app.js:485 — RIF validation
                    if (rifTrim.isNotBlank() && !rifRegex.matches(rifTrim)) {
                        rifError = "RIF inválido: J/V/E/G/P + 7-9 dígitos"
                        return@Button
                    }
                    val normalized = com.gpsclientes.domain.SearchNormalize.normalizeForSearch(nombre)
                    val entity = ClienteEntity(
                        nombreCanonico = nombre,
                        nombreNormalizado = normalized,
                        rif = rifTrim.takeIf { it.isNotBlank() },
                        zonaRuta = zona.takeIf { it.isNotBlank() },
                        textoBreve = textoBreve.takeIf { it.isNotBlank() },
                        hasGpsFix = false,
                        updatedAt = System.currentTimeMillis()
                    )
                    viewModel.insertCliente(entity) {
                        scope.launch { snackbarHostState.showSnackbar("Cliente agregado") }
                    }
                    onDismiss()
                },
                enabled = nombre.isNotBlank()
            ) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
