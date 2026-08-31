package com.gpsclientes.ui.permissions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

/**
 * Permission handler that shows rationale before requesting.
 * Uses ActivityResultContracts.RequestMultiplePermissions for location
 * and RequestPermission for POST_NOTIFICATIONS. Never blocks manual fallback.
 */
@Composable
fun rememberLocationPermissionHandler(
    onGranted: () -> Unit,
    onDenied: () -> Unit
): LocationPermissionHandler {
    val context = LocalContext.current
    var showRationale by remember { mutableStateOf(false) }
    var showPermanentlyDenied by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) onGranted() else {
            val activity = context.findActivity()
            val shouldShow = activity?.let {
                androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                    it,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            } ?: false
            if (!shouldShow) showPermanentlyDenied = true
            onDenied()
        }
    }

    return LocationPermissionHandler(
        showRationale = showRationale,
        showPermanentlyDenied = showPermanentlyDenied,
        onDismissRationale = { showRationale = false },
        onDismissPermanentlyDenied = { showPermanentlyDenied = false },
        request = {
            val hasFine = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasCoarse = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (hasFine || hasCoarse) {
                onGranted()
            } else {
                val activity = context.findActivity()
                val shouldShow = activity?.let {
                    androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                        it,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    )
                } ?: false
                if (shouldShow) showRationale = true else launcher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        },
        onRationaleConfirm = {
            showRationale = false
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    )
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

data class LocationPermissionHandler(
    val showRationale: Boolean,
    val showPermanentlyDenied: Boolean,
    val onDismissRationale: () -> Unit,
    val onDismissPermanentlyDenied: () -> Unit,
    val request: () -> Unit,
    val onRationaleConfirm: () -> Unit
)

@Composable
fun rememberNotificationsPermissionHandler(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> onResult(granted) }
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) onResult(true) else launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onResult(true)
        }
    }
}
