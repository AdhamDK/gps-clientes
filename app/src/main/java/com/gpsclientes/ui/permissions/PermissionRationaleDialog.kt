package com.gpsclientes.ui.permissions

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * Rationale dialog for runtime permissions.
 * Shown before requesting location or notifications to explain why the
 * permission is needed and how offline fallback works if denied.
 */
@Composable
fun PermissionRationaleDialog(
    title: String,
    rationale: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Allow",
    dismissLabel: String = "Not now"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(rationale) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        }
    )
}

/** Rationale copy for location — explains GPS capture and manual fallback. */
object PermissionRationales {
    const val LOCATION_TITLE = "Location permission"
    const val LOCATION_MESSAGE =
        "GPS Clientes uses precise location to capture client coordinates " +
            "and generate the address automatically. You can still enter " +
            "coordinates manually if you deny this permission."

    const val NOTIFICATIONS_TITLE = "Notifications permission"
    const val NOTIFICATIONS_MESSAGE =
        "Allow notifications so Nominatim address enrichment can run in " +
            "background and notify you when the short address is ready. " +
            "If denied, addresses will be resolved only while the app is open."

    const val LOCATION_PERMANENTLY_DENIED =
        "Location is disabled. Go to Settings > Apps > GPS Clientes > Permissions " +
            "to enable it, or enter coordinates manually."
}
