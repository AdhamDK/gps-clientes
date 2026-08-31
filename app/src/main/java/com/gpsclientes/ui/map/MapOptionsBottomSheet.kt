package com.gpsclientes.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Parity web sidebar-controls as native BottomSheet (not Drawer).
 * Peek 80dp via ModalBottomSheet skipPartiallyExpanded=false, drag handle, expand full.
 * Replicates: búsqueda + filtroZona, actions-grid 2x (Agregar/Optimizar, Exportar/Ver/Limpiar).
 * Uses MapaClientesViewModel flows for filtroZona/search and route actions.
 */
// parity web: panel desplegable nativo sobre las opciones (BottomSheet), no Drawer
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapOptionsBottomSheet(
    viewModel: MapaClientesViewModel,
    onDismiss: () -> Unit,
    onAgregarCliente: () -> Unit,
    onExportXlsx: () -> Unit = {},
    onExportPdf: () -> Unit = {},
    onImport: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val filtroZona by viewModel.filtroZona.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val uiState by viewModel.uiState.collectAsState()

    // dynamic zonas = distinct zonaRuta from pins + static web options
    val staticZonas = listOf("Todas", "VIGIA", "Zona Norte", "Lunes", "Centro")
    val dynamicZonas = remember(uiState.pins) {
        uiState.pins.mapNotNull { it.zonaRuta }.distinct().sorted()
    }
    val allZonas = remember(staticZonas, dynamicZonas) {
        val set = linkedSetOf<String>()
        staticZonas.forEach { set.add(it) }
        dynamicZonas.forEach { set.add(it) }
        set.toList()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Drag handle is built-in; add title row
            Text("Opciones", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))

            // Row búsqueda + filtroZona (parity web search-row)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    label = { Text("Buscar") },
                    placeholder = { Text("Vigía, RIF, nombre…") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded },
                    modifier = Modifier.weight(0.9f)
                ) {
                    OutlinedTextField(
                        value = filtroZona ?: "Todas",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Zona") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        allZonas.forEach { zona ->
                            DropdownMenuItem(
                                text = { Text(if (zona == "Todas") "Todas las zonas" else zona) },
                                onClick = {
                                    viewModel.setFiltroZona(if (zona == "Todas") null else zona)
                                    expanded = false
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Acciones", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // Actions-grid fila 1: +Agregar Cliente + Optimizar Ruta de Hoy (web app.js:536)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onDismiss(); onAgregarCliente() },
                    modifier = Modifier.weight(1f)
                ) { Text("+ Agregar Cliente") }
                Button(
                    onClick = { viewModel.optimizarRuta() },
                    modifier = Modifier.weight(1f)
                ) { Text("Optimizar Ruta de Hoy") }
            }
            // Actions-grid fila 2: Exportar ▼ + Ver Ruta Guardada + Limpiar Ruta
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Export dropdown parity web
                var exportExpanded by remember { mutableStateOf(false) }
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(onClick = { exportExpanded = !exportExpanded }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (exportExpanded) "Exportar ▲" else "Exportar ▼")
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = exportExpanded,
                        onDismissRequest = { exportExpanded = false }
                    ) {
                        DropdownMenuItem(text = { Text("Exportar XLSX") }, onClick = { exportExpanded = false; onExportXlsx() })
                        DropdownMenuItem(text = { Text("Exportar PDF") }, onClick = { exportExpanded = false; onExportPdf() })
                        DropdownMenuItem(text = { Text("Importar XLSX") }, onClick = { exportExpanded = false; onImport() })
                    }
                }
                OutlinedButton(onClick = { viewModel.loadRutasHoy() }, modifier = Modifier.weight(1f)) { Text("Ver Ruta Guardada") }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { viewModel.limpiarRuta() }, modifier = Modifier.weight(1f)) { Text("Limpiar Ruta") }
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cerrar") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
