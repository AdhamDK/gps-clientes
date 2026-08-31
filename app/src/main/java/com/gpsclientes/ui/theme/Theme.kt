package com.gpsclientes.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Theme parity — claro/oscuro/medio via Material3.
 * medio #2D3A2E greys+greens, primary #FC4C02 constant on map+cards per spec.
 */
enum class ThemeId(val storageKey: String) {
    CLARO("claro"),
    OSCURO("oscuro"),
    MEDIO("medio");

    companion object {
        fun fromStorage(value: String?): ThemeId = when (value?.lowercase()) {
            "claro", "light" -> CLARO
            "oscuro", "dark" -> OSCURO
            "medio", "mid" -> MEDIO
            else -> CLARO
        }
    }
}

private val StravaOrange = Color(0xFFFC4C02)
private val MidBg = Color(0xFF2D3A2E)
private val MidSurface = Color(0xFF3A4A3C)
private val MidBorder = Color(0xFF4A5D4E)
private val MidOnSurface = Color(0xFFE8F0E8)

private val LightScheme = lightColorScheme(
    primary = StravaOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCB),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1E21),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF1C1E21),
    outline = Color(0xFFE5E7EB)
)

private val DarkScheme = darkColorScheme(
    primary = StravaOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5A2D1A),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFE5E7EB),
    background = Color(0xFF121212),
    onBackground = Color(0xFFE5E7EB),
    outline = Color(0xFF2A2A2A)
)

private val MedioScheme = darkColorScheme(
    primary = StravaOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A2E1A),
    surface = MidSurface,
    onSurface = MidOnSurface,
    background = MidBg,
    onBackground = MidOnSurface,
    outline = MidBorder,
    surfaceVariant = MidSurface,
    onSurfaceVariant = MidOnSurface
)

@Composable
fun GpsClientesTheme(
    themeId: ThemeId = ThemeId.CLARO,
    content: @Composable () -> Unit
) {
    val scheme = when (themeId) {
        ThemeId.CLARO -> LightScheme
        ThemeId.OSCURO -> DarkScheme
        ThemeId.MEDIO -> MedioScheme
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content
    )
}
