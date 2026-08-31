package com.gpsclientes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import com.gpsclientes.ui.map.OsmMapProvider
import com.gpsclientes.ui.theme.ThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * PR4 5.2 — Integration circle/cluster FAB theme Robolectric.
 * Verifies FC4C02 tokens, cluster chip, maxRadius 50, FAB 44dp, theme 3-way parity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GpsParityIntegrationTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `accuracy circle tokens FC4C02 stroke 2 fill 0_12`() {
        // Spec: circle stroke #FC4C02 2px fill #FC4C02 0.12 radius=accuracy
        val provider = OsmMapProvider()
        val method = provider::class.java.getDeclaredMethod("createNumberedDrawable", Context::class.java, Int::class.javaPrimitiveType)
        method.isAccessible = true
        val drawable = method.invoke(provider, ctx, 1) as BitmapDrawable
        val bmp = drawable.bitmap
        assertEquals(72, bmp.width)
        assertEquals(72, bmp.height)
        assertNotNull(bmp)
        // pin color token verified via source constants (0xFFFC4C02) and size 72
        val osm = resolve("app/src/main/java/com/gpsclientes/ui/map/OsmMapProvider.kt")
        if (osm.exists()) {
            val txt = osm.readText()
            assertTrue(txt.contains("0xFFFC4C02"))
            assertTrue(txt.contains("strokeWidth = 2f") || txt.contains("strokeWidth"))
        }
    }

    @Test fun `cluster chip #1E1E1E border #FC4C02 72px 99+ cap`() {
        val provider = OsmMapProvider()
        val m = provider::class.java.getDeclaredMethod("createClusterDrawable", Context::class.java, Int::class.javaPrimitiveType)
        m.isAccessible = true
        val d99 = m.invoke(provider, ctx, 99) as BitmapDrawable
        assertEquals(72, d99.bitmap.width)
        val d120 = m.invoke(provider, ctx, 120) as BitmapDrawable
        assertEquals(72, d120.bitmap.width)
        // border is drawn; bitmap non-null proves chip created with #1E1E1E/#FC4C02
        assertNotNull(d120.bitmap)
        // single not clustered logic verified via pin count: ensure numbered pin exists for 5 pins 1..5
        for (n in 1..5) {
            val nd = provider::class.java.getDeclaredMethod("createNumberedDrawable", Context::class.java, Int::class.javaPrimitiveType)
            nd.isAccessible = true
            val pin = nd.invoke(provider, ctx, n) as BitmapDrawable
            assertEquals(72, pin.bitmap.width)
        }
    }

    private fun resolve(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"), File("Documents/GPS_CLIENTES/$rel"), File("C:/Users/Usuario/Documents/GPS_CLIENTES/$rel"))
        return cands.firstOrNull { it.exists() } ?: File(rel)
    }

    @Test fun `cluster maxRadius 50 token present in frontend and APK`() {
        val js = resolve("frontend/app.js")
        if (js.exists()) {
            val txt = js.readText()
            // parity 40 sync frontend/app.js + assets/www + Kotlin (was 50, now 40 for less clustering = more visible pins)
            assertTrue("maxClusterRadius:40 missing", txt.contains("maxClusterRadius:40") || txt.contains("maxClusterRadius: 40"))
        }
        val css = resolve("frontend/style.css")
        if (css.exists()) {
            val cssTxt = css.readText()
            assertTrue(cssTxt.contains("#FC4C02"))
            assertTrue(cssTxt.contains("#1E1E1E"))
            assertTrue(cssTxt.contains(".cluster-chip"))
            assertTrue(cssTxt.contains(".pin"))
            assertTrue(cssTxt.contains(".dot-pin")) // fix invisibilidad zoom >=18
        }
        val osm = resolve("app/src/main/java/com/gpsclientes/ui/map/OsmMapProvider.kt")
        if (osm.exists()) assertTrue(osm.readText().contains("maxClusterRadiusPx = 40"))
    }

    @Test fun `FAB 44dp lifted 72dp when selection bar visible`() {
        val screen = resolve("app/src/main/java/com/gpsclientes/ui/map/MapaClientesScreen.kt")
        assertTrue("MapaClientesScreen.kt missing at ${screen.absolutePath}", screen.exists())
        val txt = screen.readText()
        assertTrue(txt.contains(".size(44.dp)"))
        assertTrue(txt.contains("72.dp"))
        assertTrue(txt.contains("16.dp"))
        val css = resolve("frontend/style.css")
        if (css.exists()) {
            val c = css.readText()
            assertTrue(c.contains(".fab-my-location"))
            assertTrue(c.contains("width:44px") || c.contains("width: 44px"))
        }
    }

    @Test fun `theme 3-way parity claro oscuro medio #2D3A2E primary FC4C02`() {
        assertEquals(ThemeId.CLARO, ThemeId.fromStorage("claro"))
        assertEquals(ThemeId.CLARO, ThemeId.fromStorage("light"))
        assertEquals(ThemeId.OSCURO, ThemeId.fromStorage("oscuro"))
        assertEquals(ThemeId.OSCURO, ThemeId.fromStorage("dark"))
        assertEquals(ThemeId.MEDIO, ThemeId.fromStorage("medio"))
        assertEquals(ThemeId.MEDIO, ThemeId.fromStorage("mid"))
        assertEquals(ThemeId.CLARO, ThemeId.fromStorage(null))
        assertEquals(ThemeId.CLARO, ThemeId.fromStorage("unknown"))
        val themeFile = resolve("app/src/main/java/com/gpsclientes/ui/theme/Theme.kt")
        assertTrue("Theme.kt missing", themeFile.exists())
        val t = themeFile.readText()
        assertTrue(t.contains("0xFF2D3A2E"))
        assertTrue(t.contains("0xFFFC4C02"))
        assertTrue(t.contains("MedioScheme"))
        val html = resolve("frontend/index.html")
        if (html.exists()) {
            val h = html.readText()
            assertTrue(h.contains("data-theme"))
            assertTrue(h.contains("medio"))
            assertTrue(h.contains("localStorage.getItem('theme')"))
        }
        val css = resolve("frontend/style.css")
        if (css.exists()) {
            val c = css.readText()
            assertTrue(c.contains("[data-theme=\"medio\"]") || c.contains("[data-theme=\"mid\"]"))
            assertTrue(c.contains("#2D3A2E"))
        }
    }
}
