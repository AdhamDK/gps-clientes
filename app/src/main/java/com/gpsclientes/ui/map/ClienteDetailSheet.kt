package com.gpsclientes.ui.map

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ShareCompat
import com.gpsclientes.data.local.ClienteEntity

/**
 * Bottom sheet: pin detail with cliente info + actions [Buscar/Actualizar GPS][Editar][Compartir][Navegar].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClienteDetailSheet(
    cliente: ClienteEntity,
    onDismiss: () -> Unit,
    onBuscarActualizarGps: () -> Unit = {},
    onEditar: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(cliente.nombreCanonico, style = MaterialTheme.typography.titleLarge)
            cliente.rif?.let { Text("RIF: $it", style = MaterialTheme.typography.bodyMedium) }
            cliente.zonaRuta?.let { Text("Zona: $it", style = MaterialTheme.typography.bodySmall) }
            Text(
                cliente.textoBreve ?: cliente.referenciaManual ?: "Sin direccion — ${cliente.lat}, ${cliente.lng}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (cliente.lat != null && cliente.lng != null) {
                Text(
                    "%.6f, %.6f".format(cliente.lat, cliente.lng),
                    style = MaterialTheme.typography.bodySmall
                )
                cliente.precisionMeters?.let { Text("±${it}m", style = MaterialTheme.typography.bodySmall) }
            }
            if (cliente.isFlaggedImport) {
                Text("# flagged import", color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onBuscarActualizarGps(); onDismiss() },
                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                ) { Text("Buscar/Actualizar GPS") }
                OutlinedButton(
                    onClick = { onEditar(); onDismiss() },
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                ) { Text("Editar") }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedButton(
                    onClick = {
                        val intent = buildShareIntent(cliente, context)
                        context.startActivity(Intent.createChooser(intent, "Compartir ubicacion"))
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f).padding(end = 4.dp)
                ) { Text("Compartir") }
                Button(
                    onClick = {
                        val nav = buildNavigateIntent(cliente, "google")
                        context.startActivity(nav)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                ) { Text("Navegar") }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                OutlinedButton(
                    onClick = {
                        val waze = buildNavigateIntent(cliente, "waze")
                        context.startActivity(waze)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Navegar con Waze") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

fun buildShareIntent(c: ClienteEntity, context: android.content.Context): Intent {
    val lat = c.lat ?: 0.0
    val lng = c.lng ?: 0.0
    val label = c.textoBreve ?: c.referenciaManual ?: c.nombreCanonico
    val text = "$label\n$lat,$lng https://maps.google.com/?q=$lat,$lng"
    val geoUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng(${Uri.encode(label)})")
    return ShareCompat.IntentBuilder(context)
        .setText(text)
        .setType("text/plain")
        .createChooserIntent()
        .also { it.data = geoUri }
}

fun buildNavigateIntent(c: ClienteEntity, app: String): Intent {
    val lat = c.lat ?: 0.0
    val lng = c.lng ?: 0.0
    val uri = if (app == "waze") {
        Uri.parse("https://waze.com/ul?ll=$lat,$lng&navigate=yes")
    } else {
        Uri.parse("google.navigation:q=$lat,$lng")
    }
    return Intent(Intent.ACTION_VIEW, uri)
}

fun buildShareText(c: ClienteEntity): String {
    val lat = c.lat ?: 0.0
    val lng = c.lng ?: 0.0
    val label = c.textoBreve ?: c.referenciaManual ?: ""
    return "$label\n$lat,$lng https://maps.google.com/?q=$lat,$lng geo:$lat,$lng?q=$lat,$lng"
}
