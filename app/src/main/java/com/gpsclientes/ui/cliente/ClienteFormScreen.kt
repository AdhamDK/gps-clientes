package com.gpsclientes.ui.cliente

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.gpsclientes.data.local.ClienteDao
import com.gpsclientes.data.local.ClienteEntity
import com.gpsclientes.data.local.GeocodingSource
import com.gpsclientes.domain.SearchDuplicateHelper
import com.gpsclientes.domain.SearchNormalize
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope

/**
 * Form ViewModel handling duplicate check before insert.
 * Advisory: shows modal if candidates, allows bypass via confirm.
 */
@HiltViewModel
class ClienteFormViewModel @Inject constructor(
    private val dao: ClienteDao
) : ViewModel() {

    suspend fun checkDuplicates(
        nombreCanonico: String,
        rif: String?
    ): SearchDuplicateHelper.DuplicateResult {
        val normalized = SearchNormalize.normalizeForSearch(nombreCanonico)
        val candidates = if (normalized.isBlank()) emptyList() else dao.findLikeCandidates(normalized)
        val rifUpper = rif?.takeIf { it.isNotBlank() }?.let { SearchNormalize.normalizeRif(it) }
        val rifMatch = rifUpper?.let { dao.findByRif(it) }
        val all = (candidates + listOfNotNull(rifMatch)).distinctBy { it.id }
        return SearchDuplicateHelper.classify(normalized, all, rif)
    }

    suspend fun insertCliente(entity: ClienteEntity): Long = dao.insert(entity)

    fun buildEntity(
        nombreCanonico: String,
        rif: String?,
        zonaRuta: String?,
        telefono: String?,
        lat: String? = null,
        lng: String? = null,
        textoBreve: String? = null
    ): ClienteEntity {
        val normalized = SearchNormalize.normalizeForSearch(nombreCanonico)
        val latD = lat?.toDoubleOrNull()
        val lngD = lng?.toDoubleOrNull()
        val hasFix = latD != null && lngD != null
        return ClienteEntity(
            nombreCanonico = nombreCanonico,
            rif = rif?.takeIf { it.isNotBlank() },
            zonaRuta = zonaRuta?.takeIf { it.isNotBlank() },
            telefono = telefono?.takeIf { it.isNotBlank() },
            lat = latD,
            lng = lngD,
            textoBreve = textoBreve?.takeIf { it.isNotBlank() },
            nombreNormalizado = normalized,
            isFlaggedImport = false,
            hasGpsFix = hasFix,
            geocodingSource = if (hasFix && !textoBreve.isNullOrBlank()) GeocodingSource.GEOCODER else GeocodingSource.MANUAL,
            updatedAt = System.currentTimeMillis()
        )
    }

    // Backwards-compat overload
    fun buildEntity(
        nombreCanonico: String,
        rif: String?,
        zonaRuta: String?,
        telefono: String?
    ): ClienteEntity = buildEntity(nombreCanonico, rif, zonaRuta, telefono, null, null, null)
}

@Composable
fun ClienteFormScreen(
    onViewCliente: (Long) -> Unit = {},
    onCreated: (Long) -> Unit = {},
    viewModel: ClienteFormViewModel = hiltViewModel(),
    initialLat: Double? = null,
    initialLng: Double? = null,
    initialTextoBreve: String? = null
) {
    var nombre by remember { mutableStateOf("") }
    var rif by remember { mutableStateOf("") }
    var rifError by remember { mutableStateOf<String?>(null) }
    val rifRegex = remember { Regex("^[JVEGP]\\d{7,9}$") }
    var zona by remember { mutableStateOf("") }
    var telefono by remember { mutableStateOf("") }
    var lat by remember(initialLat) { mutableStateOf(initialLat?.toString() ?: "") }
    var lng by remember(initialLng) { mutableStateOf(initialLng?.toString() ?: "") }
    var textoBreve by remember(initialTextoBreve) { mutableStateOf(initialTextoBreve ?: "") }
    var showDuplicate by remember { mutableStateOf<SearchDuplicateHelper.DuplicateResult?>(null) }
    var pendingEntity by remember { mutableStateOf<ClienteEntity?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("New Client", style = MaterialTheme.typography.titleLarge)
        if (initialLat != null && initialLng != null) {
            Text(
                "Pin: %.6f, %.6f — ${initialTextoBreve ?: "Sin direccion"}".format(initialLat, initialLng),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = nombre,
            onValueChange = { nombre = it },
            label = { Text("Nombre *") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = rif,
            onValueChange = { rif = it.uppercase(); rifError = null },
            label = { Text("RIF (optional J/V/E/G/P + 7-9 dígitos)") },
            isError = rifError != null,
            supportingText = { rifError?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = zona,
            onValueChange = { zona = it },
            label = { Text("Zona Ruta (optional)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = telefono,
            onValueChange = { telefono = it },
            label = { Text("Telefono") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = lat,
            onValueChange = { lat = it },
            label = { Text("Lat (from map long-press)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = lng,
            onValueChange = { lng = it },
            label = { Text("Lng (draggable preview)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = textoBreve,
            onValueChange = { textoBreve = it },
            label = { Text("Texto breve (Geocoder live)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                // parity web app.js:485 — RIF validation before save
                val rifTrim = rif.trim().uppercase()
                if (rifTrim.isNotBlank() && !rifRegex.matches(rifTrim)) {
                    rifError = "RIF inválido: J/V/E/G/P + 7-9 dígitos"
                    scope.launch { snackbarHostState.showSnackbar("RIF inválido: J/V/E/G/P + 7-9 dígitos") }
                    return@Button
                }
                scope.launch {
                    val entity = viewModel.buildEntity(nombre, rifTrim, zona, telefono, lat, lng, textoBreve)
                    val dup = viewModel.checkDuplicates(nombre, rifTrim)
                    if (dup.hasCandidates) {
                        pendingEntity = entity
                        showDuplicate = dup
                    } else {
                        val id = viewModel.insertCliente(entity)
                        onCreated(id)
                    }
                }
            },
            enabled = nombre.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save")
        }
    }

    showDuplicate?.let { result ->
        DuplicateConfirmDialog(
            result = result,
            onViewCliente = { c -> onViewCliente(c.id) },
            onCreateAnyway = {
                scope.launch {
                    pendingEntity?.let {
                        val id = viewModel.insertCliente(it)
                        showDuplicate = null
                        pendingEntity = null
                        onCreated(id)
                    }
                }
            },
            onCancel = {
                showDuplicate = null
                pendingEntity = null
            }
        )
    }
}
