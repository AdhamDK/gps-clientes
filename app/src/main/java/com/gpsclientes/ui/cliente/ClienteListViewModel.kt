package com.gpsclientes.ui.cliente

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpsclientes.data.local.ClienteDao
import com.gpsclientes.data.local.ClienteEntity
import com.gpsclientes.data.local.GeocodingSource
import com.gpsclientes.data.location.FusedLocationRepository
import com.gpsclientes.data.location.LocationResult
import com.gpsclientes.domain.SearchNormalize
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ClienteListViewModel @Inject constructor(
    private val clienteDao: ClienteDao,
    private val fusedLocationRepository: FusedLocationRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val clientes: StateFlow<List<ClienteEntity>> = clienteDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _importMessage = MutableStateFlow<String?>(null)
    val importMessage: StateFlow<String?> = _importMessage.asStateFlow()
    fun consumeImportMessage() { _importMessage.value = null }

    // REQ-SEL-01 — selection persists across filter/pagination (mirrors web selectedIds Set + mini-menu)
    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()
    fun toggleSelection(id: Long) {
        val cur = _selection.value.toMutableSet()
        if (!cur.add(id)) cur.remove(id)
        _selection.value = cur
    }
    fun clearSelection() { _selection.value = emptySet() }

    private val _entregadosIds = MutableStateFlow<Set<Long>>(emptySet())
    val entregadosIds: StateFlow<Set<Long>> = _entregadosIds.asStateFlow()
    private val _entregadosMessage = MutableStateFlow<String?>(null)
    val entregadosMessage: StateFlow<String?> = _entregadosMessage.asStateFlow()
    fun consumeEntregadosMessage() { _entregadosMessage.value = null }

    // parity web: filtroZona dropdown (Todas, VIGIA, Zona Norte, Lunes, Centro + dinámico)
    private val _filtroZona = MutableStateFlow<String?>(null)
    val filtroZona: StateFlow<String?> = _filtroZona.asStateFlow()
    fun setFiltroZona(value: String?) { _filtroZona.value = value?.takeIf { it.isNotBlank() } }

    // parity web: ruta actions stub (keep selection parity)
    private val _rutaMessage = MutableStateFlow<String?>(null)
    val rutaMessage: StateFlow<String?> = _rutaMessage.asStateFlow()
    fun consumeRutaMessage() { _rutaMessage.value = null }
    fun optimizarRuta() {
        if (_selection.value.size < 2) { _rutaMessage.value = "Selecciona al menos 2 clientes con GPS"; return }
        // TODO parity web POST /rutas/optimizar ; for now toast
        _rutaMessage.value = "Optimizar Ruta: ${_selection.value.size} clientes ✓ (TODO wire backend POST /rutas/optimizar)"
    }
    fun limpiarRuta() {
        _selection.value = emptySet()
        _entregadosIds.value = emptySet()
        _rutaMessage.value = "Ruta limpiada ✓"
    }
    // TODO unify selection Set<String> across map+list (map uses Int, list uses Long)

    fun marcarEntregados() {
        val validIds = clientes.value.filter { it.hasGpsFix }.map { it.id }.toSet()
        val pruned = _selection.value.filter { it in validIds }.toSet()
        if (pruned.size != _selection.value.size) _selection.value = pruned
        if (pruned.isEmpty()) { _entregadosMessage.value = "Selecciona clientes con GPS fix"; return }
        _entregadosIds.value = _entregadosIds.value + pruned
        _selection.value = emptySet()
        _entregadosMessage.value = "Marcados entregados: ${pruned.size} ✓"
    }
    fun terminarLista() {
        val pendingCount = clientes.value.count { it.id !in _entregadosIds.value }
        if (clientes.value.isNotEmpty() && pendingCount > 0) {
            _entregadosMessage.value = "No se puede terminar: $pendingCount pendiente(s)"
            return
        }
        _entregadosIds.value = emptySet()
        _selection.value = emptySet()
        _entregadosMessage.value = "Lista terminada ✓"
    }

    fun deleteCliente(entity: ClienteEntity) {
        viewModelScope.launch { clienteDao.delete(entity) }
    }

    fun updateCliente(entity: ClienteEntity) {
        viewModelScope.launch { clienteDao.update(entity) }
    }

    fun insertCliente(entity: ClienteEntity, onDone: (() -> Unit)? = null) {
        viewModelScope.launch {
            clienteDao.insert(entity)
            onDone?.invoke()
        }
    }

    suspend fun getById(id: Long) = clienteDao.getById(id)

    fun actualizarGps(entity: ClienteEntity, onResult: (String) -> Unit) {
        viewModelScope.launch {
            when (val r = fusedLocationRepository.getCurrentLocation()) {
                is LocationResult.Success -> {
                    val updated = entity.copy(
                        lat = r.value.lat,
                        lng = r.value.lng,
                        precisionMeters = r.value.precisionMeters,
                        fechaCaptura = r.value.fechaCaptura,
                        hasGpsFix = true,
                        updatedAt = System.currentTimeMillis()
                    )
                    clienteDao.update(updated)
                    onResult("GPS actualizado: %.6f, %.6f ±%.0fm".format(r.value.lat, r.value.lng, r.value.precisionMeters))
                }
                is LocationResult.Failure -> onResult(r.message)
            }
        }
    }

    fun importGpx31() {
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { doImportGpx() }
                _importMessage.value = if (count == 0) "GPX ya importado (31 clientes)" else "Importados $count clientes desde GPX"
            } catch (e: Exception) {
                _importMessage.value = "Error GPX: ${e.message}"
            }
        }
    }

    private suspend fun doImportGpx(): Int {
        val existing = clienteDao.getAll()
        // If already 31 with gps fix, consider imported
        if (existing.size >= 31 && existing.count { it.hasGpsFix } >= 31) return 0
        val input = try { context.assets.open("favorites-Rutero.gpx") } catch (_: Exception) { return 0 }
        val text = input.bufferedReader().readText()
        input.close()
        // Simple regex parse wpt
        val wptRegex = Regex("""<wpt lat="([^"]+)" lon="([^"]+)">.*?<name>([^<]+)</name>""", RegexOption.DOT_MATCHES_ALL)
        val matches = wptRegex.findAll(text).toList()
        if (matches.isEmpty()) return 0
        val existingNames = existing.map { it.nombreNormalizado }.toMutableSet()
        val toInsert = mutableListOf<ClienteEntity>()
        for (m in matches) {
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lng = m.groupValues[2].toDoubleOrNull() ?: continue
            val name = m.groupValues[3].trim()
            if (name.isBlank()) continue
            val normalized = SearchNormalize.normalizeForSearch(name)
            if (existingNames.contains(normalized)) continue
            if (toInsert.any { it.nombreNormalizado == normalized }) continue
            toInsert.add(
                ClienteEntity(
                    nombreCanonico = name,
                    nombreNormalizado = normalized,
                    lat = lat,
                    lng = lng,
                    textoBreve = name,
                    zonaRuta = "Rutero",
                    hasGpsFix = true,
                    isFlaggedImport = false,
                    geocodingSource = GeocodingSource.MANUAL,
                    direccionOriginalExcel = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
            existingNames.add(normalized)
        }
        if (toInsert.isNotEmpty()) clienteDao.insertAll(toInsert)
        return toInsert.size
    }
}
