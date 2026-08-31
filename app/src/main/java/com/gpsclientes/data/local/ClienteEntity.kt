package com.gpsclientes.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for Cliente — 31 columns: 20 Excel + 8 GPS + 3 derived + updatedAt.
 * 20 Excel: ID_Cliente, Nombre_Canonico, Alias_Txt, Nombre_Original_Txt, RIF,
 *           RIF_Status, RIF_Fuente, Telefono, Telefono_Fuente, Direccion,
 *           Direccion_Fuente, Empresa, Zona_Ruta, Saldo_CxC, Monto_Facturado,
 *           Fecha_Emision, Fecha_Vencimiento, Fecha_Corte_CxC, Origen, Confianza_Match
 * 8 GPS: lat, lng, textoBreve, referenciaManual, fechaCaptura, precisionMeters,
 *        geocodingSource, direccionOriginalExcel (frozen)
 * 3 derived: nombreNormalizado (indexed), isFlaggedImport, hasGpsFix
 * + updatedAt + auto-generated PK id (Room internal, not counted in 31).
 * Indices on nombreNormalizado, rif, hasGpsFix per spec.
 */
@Entity(
    tableName = "clientes",
    indices = [
        Index(value = ["nombreNormalizado"]),
        Index(value = ["rif"]),
        Index(value = ["hasGpsFix"])
    ]
)
data class ClienteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // 20 Excel columns
    val idCliente: Long? = null,
    val nombreCanonico: String,
    val aliasTxt: String? = null,
    val nombreOriginalTxt: String? = null,
    val rif: String? = null,
    val rifStatus: String? = null,
    val rifFuente: String? = null,
    val telefono: String? = null,
    val telefonoFuente: String? = null,
    val direccion: String? = null,
    val direccionFuente: String? = null,
    val empresa: String? = null,
    val zonaRuta: String? = null,
    val saldoCxC: Double? = null,
    val montoFacturado: Double? = null,
    val fechaEmision: String? = null,
    val fechaVencimiento: String? = null,
    val fechaCorteCxC: String? = null,
    val origen: String? = null,
    val confianzaMatch: String? = null,

    // 8 GPS columns
    val lat: Double? = null,
    val lng: Double? = null,
    val textoBreve: String? = null,
    val referenciaManual: String? = null,
    val fechaCaptura: Long? = null,
    val precisionMeters: Float? = null,
    val geocodingSource: GeocodingSource = GeocodingSource.MANUAL,
    val direccionOriginalExcel: String? = null,

    // Derived
    val nombreNormalizado: String,
    val isFlaggedImport: Boolean = false,
    val hasGpsFix: Boolean = false,

    // Audit
    val updatedAt: Long = System.currentTimeMillis()
)
