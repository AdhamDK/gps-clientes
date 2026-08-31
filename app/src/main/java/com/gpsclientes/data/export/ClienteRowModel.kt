package com.gpsclientes.data.export

import com.gpsclientes.data.local.ClienteEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Shared RowModel for Excel and PDF exports — single source of truth.
 * Both exporters use the same columns + order, satisfying spec parity requirement
 * "Excel/PDF MUST share ClienteRowModel (PDF print of Excel)".
 */
enum class ExportColumn(val header: String) {
    ID_CLIENTE("ID_Cliente"),
    NOMBRE_CANONICO("Nombre_Canonico"),
    ALIAS_TXT("Alias_Txt"),
    NOMBRE_ORIGINAL_TXT("Nombre_Original_Txt"),
    RIF("RIF"),
    RIF_STATUS("RIF_Status"),
    RIF_FUENTE("RIF_Fuente"),
    TELEFONO("Telefono"),
    TELEFONO_FUENTE("Telefono_Fuente"),
    DIRECCION("Direccion"),
    DIRECCION_FUENTE("Direccion_Fuente"),
    EMPRESA("Empresa"),
    ZONA_RUTA("Zona_Ruta"),
    SALDO_CXC("Saldo_CxC"),
    MONTO_FACTURADO("Monto_Facturado"),
    FECHA_EMISION("Fecha_Emision"),
    FECHA_VENCIMIENTO("Fecha_Vencimiento"),
    FECHA_CORTE_CXC("Fecha_Corte_CxC"),
    ORIGEN("Origen"),
    CONFIANZA_MATCH("Confianza_Match"),
    LAT("lat"),
    LNG("lng"),
    TEXTO_BREVE("textoBreve"),
    REFERENCIA_MANUAL("referenciaManual"),
    FECHA_CAPTURA("fechaCaptura"),
    PRECISION_METERS("precisionMeters"),
    GEOCODING_SOURCE("geocodingSource"),
    HAS_GPS_FIX("hasGpsFix"),
    IS_FLAGGED_IMPORT("isFlaggedImport");

    companion object {
        /** Default selection per spec: textoBreve + referencia + lat/lng + fechaCaptura */
        val DEFAULT_SELECTION: List<ExportColumn> = listOf(
            NOMBRE_CANONICO,
            TEXTO_BREVE,
            REFERENCIA_MANUAL,
            LAT,
            LNG,
            FECHA_CAPTURA,
            PRECISION_METERS,
            ZONA_RUTA
        )

        /** All available columns (20 Excel + GPS) */
        val ALL: List<ExportColumn> = entries.toList()
    }
}

/**
 * Single row for export — ordered values aligned with [columns].
 * UTF-8 safe: all values are plain Kotlin strings (POI/PdfDocument handle Vigia encoding natively).
 */
data class ClienteRowModel(
    val columns: List<ExportColumn>,
    val values: List<String>
) {
    init {
        require(columns.size == values.size) { "columns and values size mismatch" }
    }
}

object ClienteRowModelMapper {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    fun from(entity: ClienteEntity, columns: List<ExportColumn>): ClienteRowModel {
        val values = columns.map { col -> format(entity, col) }
        return ClienteRowModel(columns, values)
    }

    fun fromAll(entities: List<ClienteEntity>, columns: List<ExportColumn>): List<ClienteRowModel> =
        entities.map { from(it, columns) }

    private fun format(entity: ClienteEntity, col: ExportColumn): String = when (col) {
        ExportColumn.ID_CLIENTE -> entity.idCliente?.toString() ?: ""
        ExportColumn.NOMBRE_CANONICO -> entity.nombreCanonico
        ExportColumn.ALIAS_TXT -> entity.aliasTxt ?: ""
        ExportColumn.NOMBRE_ORIGINAL_TXT -> entity.nombreOriginalTxt ?: ""
        ExportColumn.RIF -> entity.rif ?: ""
        ExportColumn.RIF_STATUS -> entity.rifStatus ?: ""
        ExportColumn.RIF_FUENTE -> entity.rifFuente ?: ""
        ExportColumn.TELEFONO -> entity.telefono ?: ""
        ExportColumn.TELEFONO_FUENTE -> entity.telefonoFuente ?: ""
        ExportColumn.DIRECCION -> entity.direccion ?: ""
        ExportColumn.DIRECCION_FUENTE -> entity.direccionFuente ?: ""
        ExportColumn.EMPRESA -> entity.empresa ?: ""
        ExportColumn.ZONA_RUTA -> entity.zonaRuta ?: ""
        ExportColumn.SALDO_CXC -> entity.saldoCxC?.toString() ?: ""
        ExportColumn.MONTO_FACTURADO -> entity.montoFacturado?.toString() ?: ""
        ExportColumn.FECHA_EMISION -> entity.fechaEmision ?: ""
        ExportColumn.FECHA_VENCIMIENTO -> entity.fechaVencimiento ?: ""
        ExportColumn.FECHA_CORTE_CXC -> entity.fechaCorteCxC ?: ""
        ExportColumn.ORIGEN -> entity.origen ?: ""
        ExportColumn.CONFIANZA_MATCH -> entity.confianzaMatch ?: ""
        ExportColumn.LAT -> entity.lat?.toString() ?: ""
        ExportColumn.LNG -> entity.lng?.toString() ?: ""
        ExportColumn.TEXTO_BREVE -> entity.textoBreve ?: ""
        ExportColumn.REFERENCIA_MANUAL -> entity.referenciaManual ?: ""
        ExportColumn.FECHA_CAPTURA -> entity.fechaCaptura?.let { dateFormat.format(Date(it)) } ?: ""
        ExportColumn.PRECISION_METERS -> entity.precisionMeters?.toString() ?: ""
        ExportColumn.GEOCODING_SOURCE -> entity.geocodingSource.name
        ExportColumn.HAS_GPS_FIX -> entity.hasGpsFix.toString()
        ExportColumn.IS_FLAGGED_IMPORT -> entity.isFlaggedImport.toString()
    }
}
