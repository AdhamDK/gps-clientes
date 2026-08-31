package com.gpsclientes.ui.cliente

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpsclientes.data.local.ClienteEntity

/**
 * Advisory duplicate dialog — not blocking.
 * Shows exact / fuzzy / RIF candidates with three actions:
 * [View client] [Create anyway] [Cancel]
 * Integrates with ClienteForm save flow: caller checks duplicates before insert,
 * shows this modal if candidates exist, allows bypass via onCreateAnyway.
 */
@Composable
fun DuplicateConfirmDialog(
    exactMatches: List<ClienteEntity>,
    fuzzyMatches: List<ClienteEntity>,
    rifMatch: ClienteEntity?,
    onViewCliente: (ClienteEntity) -> Unit,
    onCreateAnyway: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Possible duplicate") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "We found similar clients. This is advisory — you can still create.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))

                if (rifMatch != null) {
                    Text(
                        "RIF already associated: ${rifMatch.rif} — ${rifMatch.nombreCanonico}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = { onViewCliente(rifMatch) }) {
                        Text("Ver / View client")
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (exactMatches.isNotEmpty()) {
                    Text("Exact match:", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(modifier = Modifier.height(80.dp)) {
                        items(exactMatches) { c ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(c.nombreCanonico, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        c.rif ?: "",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    if (c.isFlaggedImport) {
                                        Text(
                                            "Flagged import",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                                TextButton(onClick = { onViewCliente(c) }) {
                                    Text("Ver / View client")
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (fuzzyMatches.isNotEmpty()) {
                    Text(
                        "Similar names:",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        "Nombres similares",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    LazyColumn(modifier = Modifier.height(100.dp)) {
                        items(fuzzyMatches.take(5)) { c ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(c.nombreCanonico, style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        "Zona: ${c.zonaRuta ?: "-"}",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    if (c.isFlaggedImport) {
                                        Text(
                                            "Flagged — deprioritized",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                TextButton(onClick = { onViewCliente(c) }) {
                                    Text("Ver / View client")
                                }
                            }
                        }
                    }
                }

                if (exactMatches.isEmpty() && fuzzyMatches.isEmpty() && rifMatch == null) {
                    Text("No duplicates found.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = onCancel) {
                    Text("Cancelar / Cancel")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onCreateAnyway) {
                    Text("Crear de todos modos / Create anyway")
                }
            }
        },
        dismissButton = null
    )
}

/**
 * Convenience overload taking DuplicateResult.
 */
@Composable
fun DuplicateConfirmDialog(
    result: com.gpsclientes.domain.SearchDuplicateHelper.DuplicateResult,
    onViewCliente: (ClienteEntity) -> Unit,
    onCreateAnyway: () -> Unit,
    onCancel: () -> Unit
) {
    DuplicateConfirmDialog(
        exactMatches = result.exactMatches,
        fuzzyMatches = result.fuzzyMatches,
        rifMatch = result.rifMatch,
        onViewCliente = onViewCliente,
        onCreateAnyway = onCreateAnyway,
        onCancel = onCancel
    )
}
