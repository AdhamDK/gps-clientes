package com.gpsclientes.ui.map

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpsclientes.data.local.ClienteDao
import com.gpsclientes.ui.map.LatLng
import com.gpsclientes.data.local.ClienteEntity
import com.gpsclientes.data.location.GeocodingRepository
import com.gpsclientes.data.location.GeocodingResult
import com.gpsclientes.data.location.FusedLocationRepository
import com.gpsclientes.data.location.LatLngPrecision
import com.gpsclientes.data.location.LocationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MapUiState(
    val pins: List<ClienteEntity> = emptyList(),
    val filterHasGpsFix: Boolean? = null,
    val filterFlagged: Boolean? = null,
    val pendingPin: LatLng? = null,
    val pendingPreview: String? = null,
    val pendingGeocoding: GeocodingResult? = null
)

sealed class CentrarState {
    data object Idle : CentrarState()
    data object Loading : CentrarState()
    data class Success(val fix: LatLngPrecision) : CentrarState()
    data class Error(val message: String) : CentrarState()
}

@HiltViewModel
class MapaClientesViewModel @Inject constructor(
    private val dao: ClienteDao,
    private val geocodingRepository: GeocodingRepository,
    private val fusedLocationRepository: FusedLocationRepository
) : ViewModel() {

    private val _allPins = dao.observeAll()
    private val _filterHasGpsFix = MutableStateFlow<Boolean?>(null)
    private val _filterFlagged = MutableStateFlow<Boolean?>(null)
    private val _pendingPin = MutableStateFlow<LatLng?>(null)
    private val _pendingPreview = MutableStateFlow<String?>(null)
    private val _pendingGeocoding = MutableStateFlow<GeocodingResult?>(null)
    // parity web sidebar-controls: filtroZona + búsqueda (q) replica frontend filtroZona select + #q
    private val _filtroZona = MutableStateFlow<String?>(null)
    val filtroZona: StateFlow<String?> = _filtroZona
    private val _searchQuery = MutableStateFlow<String>("")
    val searchQuery: StateFlow<String> = _searchQuery
    // parity web route actions: optimizable via ViewModel (POST /rutas/optimizar parity)
    private val _rutaMessage = MutableStateFlow<String?>(null)
    val rutaMessage: StateFlow<String?> = _rutaMessage
    fun consumeRutaMessage() { _rutaMessage.value = null }

    // REQ-SEL-01 — Set-based selection persists across filter/pagination (Int unified)
    private val _selection = MutableStateFlow<Set<Int>>(emptySet())
    val selection: StateFlow<Set<Int>> = _selection

    // REQ-GPS-01 — centrarState for FAB + accuracy handling
    private val _centrarState = MutableStateFlow<CentrarState>(CentrarState.Idle)
    val centrarState: StateFlow<CentrarState> = _centrarState
    private val _cachedFix = MutableStateFlow<LatLngPrecision?>(null)
    val cachedFix: StateFlow<LatLngPrecision?> = _cachedFix
    private var lastCentrarTapMs: Long = 0L
    private val debounceMs: Long = 500L
    private val ttlMs: Long = 30000L

    val filterHasGpsFix: StateFlow<Boolean?> = _filterHasGpsFix
    val filterFlagged: StateFlow<Boolean?> = _filterFlagged
    val pendingPin: StateFlow<LatLng?> = _pendingPin
    val pendingPreview: StateFlow<String?> = _pendingPreview

    val uiState: StateFlow<MapUiState> = combine(
        _allPins, _filterHasGpsFix, _filterFlagged, _pendingPin, _pendingPreview, _pendingGeocoding, _filtroZona, _searchQuery
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val all = args[0] as List<ClienteEntity>
        val hasFix = args[1] as Boolean?
        val flagged = args[2] as Boolean?
        val pending = args[3] as LatLng?
        val preview = args[4] as String?
        val geocoding = args[5] as GeocodingResult?
        val filtroZona = args[6] as String?
        val q = (args[7] as String).trim()
        val filtered = all.filter { e ->
            val hasFixPass = when (hasFix) {
                true -> e.hasGpsFix
                false -> !e.hasGpsFix
                null -> true
            }
            val flaggedPass = when (flagged) {
                true -> e.isFlaggedImport
                false -> !e.isFlaggedImport
                null -> true
            }
            // parity web: zona == filtroZona + q contains nombre/rif/zona (NFD already normalized elsewhere)
            val zonaPass = filtroZona.isNullOrBlank() || e.zonaRuta == filtroZona
            val qPass = q.isBlank() || e.nombreCanonico.contains(q, ignoreCase = true) ||
                (e.rif?.contains(q, ignoreCase = true) == true) ||
                (e.zonaRuta?.contains(q, ignoreCase = true) == true) ||
                (e.textoBreve?.contains(q, ignoreCase = true) == true)
            hasFixPass && flaggedPass && zonaPass && qPass
        }
        MapUiState(filtered, hasFix, flagged, pending, preview, geocoding)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MapUiState())

    fun setHasGpsFixFilter(value: Boolean?) { _filterHasGpsFix.value = value }
    fun setFlaggedFilter(value: Boolean?) { _filterFlagged.value = value }
    // parity web: filtroZona + búsqueda
    fun setFiltroZona(value: String?) { _filtroZona.value = value?.takeIf { it.isNotBlank() } }
    fun setSearchQuery(value: String) { _searchQuery.value = value }
    // TODO unify selection Set<String> across map+list (current map Int vs list Long); keep Int for parity minimal

    // parity web: ruta actions (optimizar / ver / limpiar) — wire to backend via POST /rutas/optimizar
    fun optimizarRuta() {
        val ids = _selection.value
        if (ids.size < 2) { _rutaMessage.value = "Selecciona al menos 2 clientes con GPS"; return }
        val start = cachedStartOrNull()
        // TODO parity web POST /rutas/optimizar with cliente_ids + start ; for now Toast + state
        _rutaMessage.value = "Optimizar Ruta: ${ids.size} clientes" + (start?.let { " start=[${it[0]},${it[1]}]" } ?: "") + " ✓ (TODO wire backend POST /rutas/optimizar)"
    }
    fun loadRutasHoy() {
        // TODO parity web GET /rutas/hoy ; for now message
        _rutaMessage.value = "Ver Ruta Guardada (TODO wire backend GET /rutas/hoy)"
    }
    fun limpiarRuta() {
        _selection.value = emptySet()
        _entregadosIds.value = emptySet()
        _rutaMessage.value = "Ruta limpiada ✓"
    }

    fun onMapLongPress(latLng: LatLng) {
        _pendingPin.value = latLng
        refreshPreview(latLng)
    }

    fun onPendingDrag(latLng: LatLng) {
        _pendingPin.value = latLng
        refreshPreview(latLng)
    }

    fun clearPending() {
        _pendingPin.value = null
        _pendingPreview.value = null
        _pendingGeocoding.value = null
    }

    private fun refreshPreview(latLng: LatLng) {
        viewModelScope.launch {
            val result = geocodingRepository.resolve(latLng.latitude, latLng.longitude)
            _pendingGeocoding.value = result
            _pendingPreview.value = result.textoBreve
                ?: "Sin direccion — %.6f, %.6f".format(latLng.latitude, latLng.longitude)
        }
    }

    fun toggleSelection(id: Int) { val cur=_selection.value.toMutableSet(); if(!cur.add(id)) cur.remove(id); _selection.value=cur }
    fun clearSelection() { _selection.value=emptySet() }

    // REQ-ENT-01 entregados: offline queue + idempotent; REQ-ENT-02 terminar blocked until all entregados
    private val _entregadosIds = MutableStateFlow<Set<Int>>(emptySet())
    val entregadosIds: StateFlow<Set<Int>> = _entregadosIds
    private val _entregadosMessage = MutableStateFlow<String?>(null)
    val entregadosMessage: StateFlow<String?> = _entregadosMessage

    fun marcarEntregados() {
        // B2: filter by hasGpsFix before POST, prune disabled
        val pins = uiState.value.pins
        val validIds = pins.filter { it.hasGpsFix }.map { it.id.toInt() }.toSet()
        // prune disabled from selection
        val pruned = _selection.value.filter { it in validIds }.toSet()
        if (pruned.size != _selection.value.size) { _selection.value = pruned }
        val cur = pruned
        if (cur.isEmpty()) { _entregadosMessage.value = "Selecciona clientes con GPS fix"; return }
        _entregadosIds.value = _entregadosIds.value + cur
        _selection.value = emptySet()
        _entregadosMessage.value = "Marcados entregados: ${cur.size} ✓"
        // In real app: enqueue PATCH /rutas/hoy/entregado with BackgroundSync + local DB sync
    }

    fun terminarLista() {
        // Mirror web: GET /rutas/hoy?entregado=false pending count (409 semantics)
        val pins = uiState.value.pins
        val pendingCount = pins.count { it.id.toInt() !in _entregadosIds.value }
        if (pins.isNotEmpty() && pendingCount > 0) {
            _entregadosMessage.value = "No se puede terminar: $pendingCount pendiente(s)"
            return
        }
        _entregadosIds.value = emptySet()
        _selection.value = emptySet()
        _entregadosMessage.value = "Lista terminada ✓"
    }

    fun consumeEntregadosMessage() { _entregadosMessage.value = null }

    fun centrarEnMiUbicacion() {
        val now = System.currentTimeMillis()
        if (now - lastCentrarTapMs < debounceMs) return
        lastCentrarTapMs = now
        if (_centrarState.value is CentrarState.Loading) return
        // TTL instant path: cachedFix <30s -> center instantly without network
        val cached = _cachedFix.value
        if (cached != null && (now - cached.fechaCaptura) < ttlMs) {
            _centrarState.value = CentrarState.Success(cached)
            return
        }
        viewModelScope.launch {
            _centrarState.value=CentrarState.Loading
            when(val r=fusedLocationRepository.getCurrentLocation()){
                is LocationResult.Success -> { _cachedFix.value=r.value; _centrarState.value=CentrarState.Success(r.value) }
                is LocationResult.Failure -> _centrarState.value=CentrarState.Error(r.message)
            }
        }
    }
    fun resetCentrarState() { _centrarState.value = CentrarState.Idle }
    fun cachedStartOrNull(): List<Double>? {
        val c = _cachedFix.value ?: return null
        if (System.currentTimeMillis() - c.fechaCaptura >= ttlMs) return null
        return listOf(c.lng, c.lat)
    }
    fun onOverlayLocation(location: Location) {
        _cachedFix.value = LatLngPrecision(
            lat = location.latitude,
            lng = location.longitude,
            precisionMeters = location.accuracy,
            fechaCaptura = System.currentTimeMillis()
        )
    }
    fun onMyLocationFix(fix: LatLngPrecision) {
        _cachedFix.value = fix
    }
}
