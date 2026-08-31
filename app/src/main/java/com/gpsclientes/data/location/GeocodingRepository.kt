package com.gpsclientes.data.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.gpsclientes.data.local.GeocodingSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * Result of primary Geocoder resolution.
 * textoBreve null => Geocoder empty/failed; caller keeps lat/lng with MANUAL source (offline-first).
 */
data class GeocodingResult(
    val textoBreve: String?,
    val source: GeocodingSource,
    val rawAddress: Address? = null
) {
    /** Display fallback for UI when textoBreve is null (coords text). Does not mutate stored value. */
    fun displayText(lat: Double, lng: Double): String =
        textoBreve ?: "Sin direccion — %.6f, %.6f".format(lat, lng)
}

interface GeocodingRepository {
    suspend fun resolve(lat: Double, lng: Double): GeocodingResult
    fun composeTextoBreve(address: Address): String?
    suspend fun resolveWithCoordsFallback(lat: Double, lng: Double): GeocodingResult =
        resolve(lat, lng)
}

/**
 * Primary Geocoder implementation.
 * - Uses Geocoder.getFromLocation (blocking) off IO dispatcher; Android 33+ uses async listener.
 * - Parses Address: locality -> El Vigia, adminArea/subAdminArea -> Municipio Alberto Adriani,
 *   subLocality/thoroughfare/subThoroughfare/featureName -> sector/calle/av.
 * - Joins non-null parts with ", " to compose textoBreve.
 * - On empty/null/IOException returns textoBreve=null, source=MANUAL (no overwrite of lat/lng).
 * - Synchronous composeTextoBreve is usable for draggable pin live preview.
 */
class GeocoderGeocodingRepository @Inject constructor(
    @ApplicationContext private val context: Context
) : GeocodingRepository {

    private val geocoder by lazy { Geocoder(context, Locale("es", "VE")) }

    override suspend fun resolve(lat: Double, lng: Double): GeocodingResult =
        withContext(Dispatchers.IO) {
            try {
                val addresses = getAddresses(lat, lng)
                if (addresses.isNullOrEmpty()) {
                    return@withContext GeocodingResult(null, GeocodingSource.MANUAL, null)
                }
                val addr = addresses.first()
                val composed = composeTextoBreve(addr)
                if (composed.isNullOrBlank()) {
                    GeocodingResult(null, GeocodingSource.MANUAL, addr)
                } else {
                    GeocodingResult(composed, GeocodingSource.GEOCODER, addr)
                }
            } catch (_: IOException) {
                GeocodingResult(null, GeocodingSource.MANUAL, null)
            } catch (_: IllegalArgumentException) {
                GeocodingResult(null, GeocodingSource.MANUAL, null)
            }
        }

    @Suppress("DEPRECATION")
    private suspend fun getAddresses(lat: Double, lng: Double): List<Address>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return suspendCancellableCoroutine { cont ->
                try {
                    geocoder.getFromLocation(lat, lng, 1) { addresses ->
                        if (cont.isActive) cont.resume(addresses)
                    }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        } else {
            return try {
                geocoder.getFromLocation(lat, lng, 1)
            } catch (_: IOException) {
                null
            }
        }
    }

    /**
     * Composes textoBreve from Address components joining non-null with ", ".
     * Mapping:
     * - locality (e.g. El Vigia)
     * - adminArea/subAdminArea -> "Municipio X" (Alberto Adriani) — prefer subAdminArea, fallback adminArea
     * - subLocality -> "Sector X" (La Blanca)
     * - thoroughfare / subThoroughfare / featureName -> street/calle/av parts
     */
    override fun composeTextoBreve(address: Address): String? {
        val parts = mutableListOf<String>()

        // Locality (city/town) — e.g. El Vigia
        address.locality?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }

        // Municipio via adminArea / subAdminArea — e.g. Alberto Adriani
        val municipio = address.subAdminArea?.takeIf { it.isNotBlank() }
            ?: address.adminArea?.takeIf { it.isNotBlank() }
        municipio?.let { raw ->
            val label = if (raw.startsWith("Municipio", ignoreCase = true)) raw.trim() else "Municipio $raw".trim()
            // Avoid duplicate if locality already equals municipio
            if (parts.none { it.equals(raw, ignoreCase = true) || it.equals(label, ignoreCase = true) }) {
                parts.add(label)
            }
        }

        // Sector / barrio via subLocality
        address.subLocality?.takeIf { it.isNotBlank() }?.let { parts.add("Sector ${it.trim()}") }

        // Street components: thoroughfare, subThoroughfare, featureName, premises
        address.thoroughfare?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
        address.subThoroughfare?.takeIf { it.isNotBlank() }?.let { sub ->
            // Append number to thoroughfare if present, else standalone
            val last = parts.lastOrNull()
            if (last != null && address.thoroughfare != null && last == address.thoroughfare) {
                parts[parts.lastIndex] = "$last $sub".trim()
            } else {
                parts.add(sub.trim())
            }
        }
        // featureName as fallback street identifier if no thoroughfare
        if (address.thoroughfare.isNullOrBlank() && address.subThoroughfare.isNullOrBlank()) {
            address.featureName?.takeIf { it.isNotBlank() && it != address.locality && it != address.subLocality }?.let {
                parts.add(it.trim())
            }
        }

        // Optional: postal / country not included in textoBreve (kept minimal for rural El Vigia)

        val joined = parts.filter { it.isNotBlank() }.joinToString(", ")
        return joined.takeIf { it.isNotBlank() }
    }
}
