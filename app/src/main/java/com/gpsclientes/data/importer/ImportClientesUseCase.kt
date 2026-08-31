package com.gpsclientes.data.importer

import androidx.room.withTransaction
import com.gpsclientes.data.local.AppDatabase
import com.gpsclientes.data.local.ClienteEntity
import com.gpsclientes.data.local.GeocodingSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.InputStream

/**
 * Excel import use case/repository parsing Clientes_TOM_KEVIN.xlsx via Apache POI.
 * - Maps 20 cols A1:T410, skips header
 * - Normalization NFD + lowercase/trim/collapse
 * - UTF-8 handling (POI preserves Vigía)
 * - Flags hash-prefixed rows (isFlaggedImport) preserving hashes
 * - Validates RIF [JVEGP]\d{7,9}, skips invalid (e.g. X123) but commits rest
 * - Dedup by RIF exact + nombreNormalizado before batch insert
 * - Preserves direccionOriginalExcel frozen
 * - Transaction handling via Room withTransaction, off-thread Dispatchers.IO
 */
class ImportClientesUseCase(
    private val db: AppDatabase
) {

    private val rifRegex = Regex("^[JVEGP]\\d{7,9}$")

    suspend fun import(input: InputStream): ImportResult = withContext(Dispatchers.IO) {
        val workbook = XSSFWorkbook(input)
        try {
            val sheet = workbook.getSheetAt(0)
            val dao = db.clienteDao()

            // Load existing dedup keys to avoid duplicates on re-import
            val existing = dao.getAll()
            val existingRifs = existing.mapNotNull { it.rif?.trim()?.uppercase() }.toMutableSet()
            val existingNormalized = existing.map { it.nombreNormalizado }.toMutableSet()

            var inserted = 0
            var flagged = 0
            var skippedInvalidRif = 0

            val toInsert = mutableListOf<ClienteEntity>()

            // Iterate rows 2..410 (index 1..)
            for (rowIndex in 1..sheet.lastRowNum) {
                val row = sheet.getRow(rowIndex) ?: continue
                val nombreCanonicoRaw = getString(row, 1) // B
                if (nombreCanonicoRaw.isNullOrBlank()) continue

                val rifRaw = getString(row, 4) // E
                // Validate RIF if present and not blank
                if (!rifRaw.isNullOrBlank()) {
                    val rifUpper = rifRaw.trim().uppercase()
                    if (!rifRegex.matches(rifUpper)) {
                        skippedInvalidRif++
                        continue
                    }
                }

                val isFlagged = nombreCanonicoRaw.trim().startsWith("#")
                if (isFlagged) flagged++

                val normalized = Normalize.normalize(nombreCanonicoRaw)

                // Dedup check: RIF exact or nombreNormalizado
                val rifUpper = rifRaw?.trim()?.uppercase()
                val duplicateByRif = rifUpper != null && rifUpper.isNotBlank() && existingRifs.contains(rifUpper)
                val duplicateByName = existingNormalized.contains(normalized)
                if (duplicateByRif || duplicateByName) {
                    // Also check within current batch
                    val dupInBatchByRif = rifUpper != null && toInsert.any { it.rif?.uppercase() == rifUpper }
                    val dupInBatchByName = toInsert.any { it.nombreNormalizado == normalized }
                    if (dupInBatchByRif || dupInBatchByName) continue
                    // For existing, skip only if already exists? Spec says dedup by RIF exact + nombreNormalizado
                    // We treat existing as skip to avoid duplicate inserts on re-import
                    if (duplicateByRif || duplicateByName) continue
                }

                val direccionRaw = getString(row, 9) // J Direccion
                val entity = ClienteEntity(
                    idCliente = getLong(row, 0),
                    nombreCanonico = nombreCanonicoRaw,
                    aliasTxt = getString(row, 2),
                    nombreOriginalTxt = getString(row, 3),
                    rif = rifRaw?.trim()?.takeIf { it.isNotBlank() },
                    rifStatus = getString(row, 5),
                    rifFuente = getString(row, 6),
                    telefono = getString(row, 7),
                    telefonoFuente = getString(row, 8),
                    direccion = direccionRaw,
                    direccionFuente = getString(row, 10),
                    empresa = getString(row, 11),
                    zonaRuta = getString(row, 12),
                    saldoCxC = getDouble(row, 13),
                    montoFacturado = getDouble(row, 14),
                    fechaEmision = getString(row, 15),
                    fechaVencimiento = getString(row, 16),
                    fechaCorteCxC = getString(row, 17),
                    origen = getString(row, 18),
                    confianzaMatch = getString(row, 19),
                    // GPS defaults
                    lat = null,
                    lng = null,
                    textoBreve = null,
                    referenciaManual = null,
                    fechaCaptura = null,
                    precisionMeters = null,
                    geocodingSource = GeocodingSource.MANUAL,
                    direccionOriginalExcel = direccionRaw,
                    nombreNormalizado = normalized,
                    isFlaggedImport = isFlagged,
                    hasGpsFix = false,
                    updatedAt = System.currentTimeMillis()
                )
                toInsert.add(entity)
                // track for dedup within batch
                if (rifUpper != null && rifUpper.isNotBlank()) existingRifs.add(rifUpper)
                existingNormalized.add(normalized)
            }

            // Batch insert in transaction
            db.withTransaction {
                if (toInsert.isNotEmpty()) {
                    dao.insertAll(toInsert)
                    inserted = toInsert.size
                }
            }

            ImportResult(
                inserted = inserted,
                flagged = flagged,
                skippedInvalidRif = skippedInvalidRif,
                totalRows = sheet.lastRowNum // 409 data rows
            )
        } finally {
            workbook.close()
            input.close()
        }
    }

    private fun getString(row: Row, col: Int): String? {
        val cell = row.getCell(col) ?: return null
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            CellType.NUMERIC -> {
                // For numeric cells, return as string without scientific notation if integral
                val v = cell.numericCellValue
                if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
            }
            CellType.BOOLEAN -> cell.booleanCellValue.toString()
            CellType.FORMULA -> try {
                cell.stringCellValue
            } catch (_: Exception) {
                cell.numericCellValue.toString()
            }
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private fun getLong(row: Row, col: Int): Long? {
        val cell = row.getCell(col) ?: return null
        return when (cell.cellType) {
            CellType.NUMERIC -> cell.numericCellValue.toLong()
            CellType.STRING -> cell.stringCellValue.toLongOrNull()
            else -> null
        }
    }

    private fun getDouble(row: Row, col: Int): Double? {
        val cell = row.getCell(col) ?: return null
        return when (cell.cellType) {
            CellType.NUMERIC -> cell.numericCellValue
            CellType.STRING -> cell.stringCellValue.toDoubleOrNull()
            else -> null
        }
    }
}
