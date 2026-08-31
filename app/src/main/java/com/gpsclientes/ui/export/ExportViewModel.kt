package com.gpsclientes.ui.export

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gpsclientes.data.export.ExportColumn
import com.gpsclientes.data.export.ExportRepository
import com.gpsclientes.data.export.ExportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ExportViewModel @Inject constructor(
    private val repository: ExportRepository
) : ViewModel() {

    private val _selectedColumns = MutableStateFlow(ExportColumn.DEFAULT_SELECTION)
    val selectedColumns: StateFlow<List<ExportColumn>> = _selectedColumns

    private val _exportState = MutableStateFlow<ExportResult?>(null)
    val exportState: StateFlow<ExportResult?> = _exportState

    fun setSelected(columns: List<ExportColumn>) {
        _selectedColumns.value = columns.ifEmpty { ExportColumn.DEFAULT_SELECTION }
    }

    fun exportExcel(file: File) {
        viewModelScope.launch {
            _exportState.value = repository.exportToExcel(_selectedColumns.value, file)
        }
    }

    fun exportPdf(file: File) {
        viewModelScope.launch {
            _exportState.value = repository.exportToPdf(_selectedColumns.value, file)
        }
    }

    fun clearState() {
        _exportState.value = null
    }
}
