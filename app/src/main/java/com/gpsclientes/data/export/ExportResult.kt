package com.gpsclientes.data.export

sealed class ExportResult {
    data class Success(val uri: String, val rowCount: Int) : ExportResult()
    data class Failure(val message: String, val cause: Throwable? = null) : ExportResult()
}
