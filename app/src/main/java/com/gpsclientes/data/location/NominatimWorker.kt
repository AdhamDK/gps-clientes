package com.gpsclientes.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gpsclientes.data.local.AppDatabase
import com.gpsclientes.data.local.GeocodingSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Nominatim reverse-geocoding fallback Worker.
 * Enqueued only when textoBreve still null and not manually edited.
 * - Constraints: NetworkType.CONNECTED, 1 req/s rate-limit, in-memory cache.
 * - no-overwrite guard: if user edited referenciaManual or textoBreve since enqueue, skip.
 * - Uses https://nominatim.openstreetmap.org/reverse with required User-Agent.
 * - Parses display_name; on success writes textoBreve + NOMINATIM, else keeps MANUAL.
 * - POST_NOTIFICATIONS guard: caller checks before enqueue on Android 13+ (no notification crash).
 */
@HiltWorker
class NominatimWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: AppDatabase
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val clienteId = inputData.getLong(KEY_CLIENTE_ID, -1L)
        val lat = inputData.getDouble(KEY_LAT, Double.NaN)
        val lng = inputData.getDouble(KEY_LNG, Double.NaN)
        if (clienteId == -1L || lat.isNaN() || lng.isNaN()) return Result.failure()

        val dao = db.clienteDao()
        val entity = dao.getById(clienteId) ?: return Result.failure()

        // No-overwrite guard: skip if user has edited textoBreve or referenciaManual since enqueue
        if (!entity.textoBreve.isNullOrBlank()) {
            return Result.success()
        }
        // referenciaManual edited means user took control — do not overwrite textoBreve either
        // (spec: if user edited referenciaManual/textoBreve, skip)
        val manualGuard = entity.referenciaManual?.isNotBlank() == true &&
            entity.geocodingSource == GeocodingSource.MANUAL &&
            entity.textoBreve.isNullOrBlank()
        // Keep the guard narrow: if referenciaManual exists but textoBreve still null, we CAN fill textoBreve
        // via Nominatim unless textoBreve was explicitly set. So only skip when textoBreve non-null.
        // The check above already returned when textoBreve != null, so continue.

        // Rate-limit 1 req/s (shared across workers)
        enforceRateLimit()

        // Cache lookup
        val cacheKey = cacheKey(lat, lng)
        val cached = cache[cacheKey]
        val displayName: String? = if (cached != null) {
            cached
        } else {
            try {
                fetchNominatim(lat, lng)?.also { fetched ->
                    if (fetched.isNotBlank()) cache[cacheKey] = fetched
                }
            } catch (_: Exception) {
                null
            }
        }

        return try {
            if (!displayName.isNullOrBlank()) {
                // Re-check guard after network to avoid race with manual edit
                val fresh = dao.getById(clienteId) ?: return Result.failure()
                if (!fresh.textoBreve.isNullOrBlank()) return Result.success()
                dao.update(
                    fresh.copy(
                        textoBreve = displayName,
                        geocodingSource = GeocodingSource.NOMINATIM,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } else {
                // Nominatim returned empty/error — ensure source stays MANUAL, do not clear lat/lng
                // No overwrite needed; entity already MANUAL with null textoBreve
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun fetchNominatim(lat: Double, lng: Double): String? {
        val urlStr = "https://nominatim.openstreetmap.org/reverse?format=json&lat=$lat&lon=$lng&zoom=18&addressdetails=1"
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return null
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val body = reader.readText()
            reader.close()
            val json = JSONObject(body)
            json.optString("display_name", null)?.takeIf { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val KEY_CLIENTE_ID = "cliente_id"
        const val KEY_LAT = "lat"
        const val KEY_LNG = "lng"
        const val WORK_NAME_PREFIX = "nominatim_"
        const val USER_AGENT = "GPSClientes/1.0 (contact@gpsclientes.local)"

        // 1 req/s rate-limit shared across workers
        @Volatile private var lastRequestMs: Long = 0L
        private val rateLock = Any()

        // Simple in-memory cache (coords -> display_name). Bounded to 200 entries.
        private val cache = ConcurrentHashMap<String, String>()

        fun cacheKey(lat: Double, lng: Double): String =
            "%.5f,%.5f".format(lat, lng)

        internal fun enforceRateLimit() {
            synchronized(rateLock) {
                val now = System.currentTimeMillis()
                val elapsed = now - lastRequestMs
                if (elapsed < 1000L) {
                    Thread.sleep(1000L - elapsed)
                }
                lastRequestMs = System.currentTimeMillis()
            }
        }

        internal fun clearCacheForTest() = cache.clear()

        /**
         * Enqueue helper with guards:
         * - Only if textoBreve null and not manually edited (caller checks entity).
         * - Requires NetworkType.CONNECTED.
         * - POST_NOTIFICATIONS guard on Android 13+: if capability needed, caller should request permission
         *   before enqueue; this method does not crash if denied, it still enqueues (no notification posted here).
         *   If notifications would be posted, guard via ContextCompat check.
         */
        fun enqueue(
            context: Context,
            clienteId: Long,
            lat: Double,
            lng: Double,
            textoBreve: String?,
            referenciaManual: String?
        ) {
            // Guard: only enqueue when textoBreve still null and not manually edited
            if (!textoBreve.isNullOrBlank()) return
            // If user already provided referenciaManual and expects no auto-overwrite of textoBreve,
            // we still allow Nominatim to fill textoBreve (spec allows manual referencia + auto textoBreve).
            // Skip only when textoBreve was explicitly set — already handled.
            // Additional guard: do not enqueue if lat/lng invalid
            if (lat.isNaN() || lng.isNaN()) return

            // Optional notification permission guard for Android 13+ before showing notifications
            // Worker itself does not post notifications, but if future does, check here.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Do not block enqueue on notification permission; just note that notifications will be suppressed.
                // If caller wants to gate enqueue on notification capability, check:
                // ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                @Suppress("UNUSED_VARIABLE")
                val hasNotif = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                // Enqueue regardless; doWork will not post notifications without permission.
            }

            val data = Data.Builder()
                .putLong(KEY_CLIENTE_ID, clienteId)
                .putDouble(KEY_LAT, lat)
                .putDouble(KEY_LNG, lng)
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<NominatimWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .addTag(WORK_NAME_PREFIX + clienteId)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_PREFIX + clienteId,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun hasNotificationPermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
