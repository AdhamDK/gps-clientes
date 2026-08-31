package com.gpsclientes.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.ShareCompat
import com.gpsclientes.data.local.ClienteEntity

/**
 * Share and navigation helpers for pin detail actions.
 * Offline-safe: always produces intents/text even without network.
 * - Share: geo: URI + WhatsApp text + https://maps.google.com/?q= (chooser fallback to http)
 * - Navigate: google.navigation:q= with https fallback, waze https scheme; polished resolve check.
 */
object ShareUtils {

    fun shareIntent(context: Context, c: ClienteEntity): Intent {
        val lat = c.lat ?: 0.0
        val lng = c.lng ?: 0.0
        val label = c.textoBreve ?: c.referenciaManual ?: c.nombreCanonico
        val text = "$label\n$lat,$lng https://maps.google.com/?q=$lat,$lng"
        // Chooser must not carry geo: data URI; use EXTRA_TEXT with WhatsApp/maps fallback via https link.
        return ShareCompat.IntentBuilder(context)
            .setText(text)
            .setType("text/plain")
            .createChooserIntent()
    }

    fun shareText(c: ClienteEntity): String {
        val lat = c.lat ?: 0.0
        val lng = c.lng ?: 0.0
        val label = c.textoBreve ?: c.referenciaManual ?: ""
        return "$label\n$lat,$lng https://maps.google.com/?q=$lat,$lng geo:$lat,$lng?q=$lat,$lng"
    }

    fun navigateIntent(c: ClienteEntity, app: String = "google"): Intent {
        val lat = c.lat ?: 0.0
        val lng = c.lng ?: 0.0
        val uri = if (app == "waze") {
            Uri.parse("https://waze.com/ul?ll=$lat,$lng&navigate=yes")
        } else {
            Uri.parse("google.navigation:q=$lat,$lng")
        }
        return Intent(Intent.ACTION_VIEW, uri).apply {
            // Polish: add fallback flag so chooser can fall back to https if google.navigation not handled
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Returns fallback https intent if google.navigation is not resolvable. */
    fun navigateFallbackIntent(c: ClienteEntity): Intent {
        val lat = c.lat ?: 0.0
        val lng = c.lng ?: 0.0
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://maps.google.com/?q=$lat,$lng"))
    }

    fun canResolve(context: Context, intent: Intent): Boolean {
        return try {
            // Android 11+ package visibility: queryIntentActivities is more reliable than resolveActivity
            val pm = context.packageManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0)).isNotEmpty()
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0).isNotEmpty()
            }
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            intent.resolveActivity(context.packageManager) != null
        }
    }
}
