package com.gpsclientes.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

/**
 * Location result capturing Fused high-accuracy fix.
 * precisionMeters = Location.accuracy, fechaCaptura = System.currentTimeMillis().
 */
data class LatLngPrecision(
    val lat: Double,
    val lng: Double,
    val precisionMeters: Float,
    val fechaCaptura: Long
)

sealed class LocationResult {
    data class Success(val value: LatLngPrecision) : LocationResult()
    data class Failure(val reason: LocationFailureReason, val message: String) : LocationResult()
}

enum class LocationFailureReason {
    PERMISSION_DENIED,
    LOCATION_UNAVAILABLE,
    TIMEOUT,
    UNKNOWN
}

interface LocationRepository {
    suspend fun getCurrentLocation(): LocationResult
}

/**
 * Fused HIGH_ACCURACY implementation.
 * - Checks ACCESS_FINE_LOCATION before requesting (returns Failure PERMISSION_DENIED for UI prompt).
 * - Uses getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY) with CancellationToken.
 * - Maps Location.accuracy -> precisionMeters, millis -> fechaCaptura.
 * - Offline-safe: returns lat/lng even without network; geocoding is deferred to GeocodingRepository.
 * - Caller must save lat/lng BEFORE invoking geocoding (offline-first).
 */
class FusedLocationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fusedClient: FusedLocationProviderClient
) : LocationRepository {

    override suspend fun getCurrentLocation(): LocationResult {
        // Permission check (ACCESS_FINE_LOCATION required for HIGH_ACCURACY)
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            return LocationResult.Failure(
                LocationFailureReason.PERMISSION_DENIED,
                "Location permission not granted. Enable precise location or enter coordinates manually."
            )
        }

        return try {
            val cts = CancellationTokenSource()
            val location = fusedClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cts.token
            ).await()

            if (location == null) {
                return LocationResult.Failure(
                    LocationFailureReason.LOCATION_UNAVAILABLE,
                    "No location fix available. Try outdoors or enter coordinates manually."
                )
            }

            LocationResult.Success(
                LatLngPrecision(
                    lat = location.latitude,
                    lng = location.longitude,
                    precisionMeters = location.accuracy,
                    fechaCaptura = System.currentTimeMillis()
                )
            )
        } catch (e: SecurityException) {
            LocationResult.Failure(
                LocationFailureReason.PERMISSION_DENIED,
                e.message ?: "Permission denied"
            )
        } catch (e: Exception) {
            val reason = if (e.message?.contains("timeout", ignoreCase = true) == true) {
                LocationFailureReason.TIMEOUT
            } else {
                LocationFailureReason.UNKNOWN
            }
            LocationResult.Failure(reason, e.message ?: "Unknown location error")
        }
    }
}
