package com.gpsclientes.data.importer

data class ImportResult(
    val inserted: Int,
    val flagged: Int,
    val skippedInvalidRif: Int,
    val totalRows: Int
)
