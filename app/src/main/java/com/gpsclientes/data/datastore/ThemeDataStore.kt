package com.gpsclientes.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.gpsclientes.ui.theme.ThemeId
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themeDataStore by preferencesDataStore(name = "theme_prefs")
private val THEME_KEY = stringPreferencesKey("theme")

@Singleton
class ThemeDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    val themeFlow: Flow<ThemeId> = context.themeDataStore.data.map { prefs ->
        ThemeId.fromStorage(prefs[THEME_KEY])
    }

    suspend fun setTheme(themeId: ThemeId) {
        context.themeDataStore.edit { prefs ->
            prefs[THEME_KEY] = themeId.storageKey
        }
    }

    suspend fun cycleTheme(current: ThemeId): ThemeId {
        val next = when (current) {
            ThemeId.CLARO -> ThemeId.OSCURO
            ThemeId.OSCURO -> ThemeId.MEDIO
            ThemeId.MEDIO -> ThemeId.CLARO
        }
        setTheme(next)
        return next
    }
}
