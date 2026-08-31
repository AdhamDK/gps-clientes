package com.gpsclientes.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gpsclientes.data.export.ExportColumn

/**
 * Column picker — checkbox list for Excel/PDF column selection.
 * Same source (ExportColumn.ALL) for both exports, default textoBreve+referencia+lat/lng+fechaCaptura.
 */
@Composable
fun ExportColumnPicker(
    selected: List<ExportColumn>,
    onSelectionChange: (List<ExportColumn>) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Select columns (${selected.size}/${ExportColumn.ALL.size})",
                style = MaterialTheme.typography.titleSmall
            )
            Row {
                TextButton(onClick = { onSelectionChange(ExportColumn.ALL) }) {
                    Text("All")
                }
                TextButton(onClick = { onSelectionChange(ExportColumn.DEFAULT_SELECTION) }) {
                    Text("Default")
                }
                TextButton(onClick = { onSelectionChange(emptyList()) }) {
                    Text("None")
                }
            }
        }
        ExportColumn.ALL.forEach { col ->
            val checked = selected.contains(col)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { isChecked ->
                        val next = if (isChecked) selected + col else selected - col
                        // Keep original order as per ExportColumn.ALL
                        val ordered = ExportColumn.ALL.filter { it in next }
                        onSelectionChange(ordered)
                    }
                )
                Text(
                    text = col.header,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
fun ExportColumnPickerDialog(
    selected: List<ExportColumn>,
    onConfirm: (List<ExportColumn>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export columns") },
        text = {
            ExportColumnPicker(
                selected = selected,
                onSelectionChange = {},
                modifier = modifier
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("Export") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
