package com.gpsclientes.ui.cliente

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpsclientes.data.local.ClienteDao
import com.gpsclientes.data.local.ClienteEntity
import com.gpsclientes.domain.SearchDuplicateHelper
import com.gpsclientes.domain.SearchNormalize
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Search-as-you-type ViewModel.
 * - Debounces 300ms (spec)
 * - Normalizes query via [SearchNormalize] (NFD, lower, strip #, collapse)
 * - Queries Dao.findLikeCandidates LIMIT10 ranked exact-first
 * - Applies Levenshtein filter + LIKE contains both directions + flagged deprioritization
 * - Supports RIF exact match as additional candidate
 * - Exposes Flow<List<ClienteEntity>> with query normalization
 */
@HiltViewModel
class ClienteSearchViewModel @Inject constructor(
    private val dao: ClienteDao
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // Optional filter: when non-null, restrict ranked results to hasGpsFix value
    private val _hasGpsFixFilter = MutableStateFlow<Boolean?>(null)
    val hasGpsFixFilter: StateFlow<Boolean?> = _hasGpsFixFilter.asStateFlow()

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun setHasGpsFixFilter(value: Boolean?) {
        _hasGpsFixFilter.value = value
    }

    fun normalizedQuery(): String = SearchNormalize.normalizeForSearch(_query.value)

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<ClienteEntity>> = _query
        .debounce(300)
        .mapLatest { raw ->
            val normalized = SearchNormalize.normalizeForSearch(raw)
            if (normalized.isBlank()) {
                // If blank but RIF pattern, still try RIF lookup
                val rifUpper = SearchNormalize.normalizeRif(raw)
                if (SearchNormalize.isRifPattern(rifUpper)) {
                    dao.findByRif(rifUpper)?.let { listOf(it) } ?: emptyList()
                } else {
                    emptyList()
                }
            } else {
                val like = dao.findLikeCandidates(normalized)
                val rifUpper = SearchNormalize.normalizeRif(raw)
                val rifMatch = if (SearchNormalize.isRifPattern(rifUpper)) dao.findByRif(rifUpper) else null
                val combined = (like + listOfNotNull(rifMatch)).distinctBy { it.id }

                // Levenshtein filtered + LIKE contains both directions
                val filtered = combined.filter { e ->
                    val target = e.nombreNormalizado
                    target == normalized ||
                        target.contains(normalized) ||
                        normalized.contains(target) ||
                        SearchDuplicateHelper.isFuzzyMatch(normalized, target) ||
                        e.rif?.uppercase() == rifUpper
                }

                SearchDuplicateHelper.rankForSearch(normalized, filtered).take(10)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * For ClienteForm save flow: check exact/fuzzy/RIF before insert.
     * Returns classified DuplicateResult; flagged imports are deprioritized but still returned.
     */
    suspend fun checkDuplicates(nombreCanonico: String, rif: String?): SearchDuplicateHelper.DuplicateResult {
        val normalized = SearchNormalize.normalizeForSearch(nombreCanonico)
        if (normalized.isBlank() && rif.isNullOrBlank()) {
            return SearchDuplicateHelper.DuplicateResult(emptyList(), emptyList(), null)
        }
        val candidates = if (normalized.isBlank()) emptyList() else dao.findLikeCandidates(normalized)
        val rifUpper = rif?.takeIf { it.isNotBlank() }?.let { SearchNormalize.normalizeRif(it) }
        val rifMatch = if (rifUpper != null && SearchNormalize.isRifPattern(rifUpper)) {
            dao.findByRif(rifUpper)
        } else if (rifUpper != null) {
            // Even if not valid RIF pattern, still check exact RIF duplicate
            dao.findByRif(rifUpper)
        } else null

        val all = (candidates + listOfNotNull(rifMatch)).distinctBy { it.id }
        return SearchDuplicateHelper.classify(normalized, all, rif)
    }
}
