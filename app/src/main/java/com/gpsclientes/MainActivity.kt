package com.gpsclientes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.preference.PreferenceManager
import com.gpsclientes.data.datastore.ThemeDataStore
import com.gpsclientes.BuildConfig
import com.gpsclientes.ui.WebViewScreen
import com.gpsclientes.ui.cliente.ClienteListScreen
import com.gpsclientes.ui.map.MapFeatureFlag
import com.gpsclientes.ui.map.MapaClientesScreen
import com.gpsclientes.ui.theme.GpsClientesTheme
import com.gpsclientes.ui.theme.ThemeId
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.osmdroid.config.Configuration

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var themeDataStore: ThemeDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // OSM UserAgent — required before any MapView is instantiated (tile policy)
        Configuration.getInstance().load(
            applicationContext,
            PreferenceManager.getDefaultSharedPreferences(applicationContext)
        )
        Configuration.getInstance().userAgentValue = packageName
        setContent {
            val themeId by themeDataStore.themeFlow.collectAsState(initial = ThemeId.CLARO)
            GpsClientesTheme(themeId = themeId) {
                if (BuildConfig.ENABLE_WEBVIEW) {
                    WebViewScreen()
                } else {
                    var selected by remember { mutableIntStateOf(0) }
                    val showMap = MapFeatureFlag.enabled
                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = selected == 0,
                                    onClick = { selected = 0 },
                                    label = { Text("List") },
                                    icon = { Text("≡") }
                                )
                                if (showMap) {
                                    NavigationBarItem(
                                        selected = selected == 1,
                                        onClick = { selected = 1 },
                                        label = { Text("Map") },
                                        icon = { Text("◎") }
                                    )
                                }
                            }
                        }
                    ) { inner ->
                        Box(modifier = Modifier.padding(inner)) {
                            when (selected) {
                                0 -> ClienteListScreen()
                                1 -> if (showMap) MapaClientesScreen() else ClienteListScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}
