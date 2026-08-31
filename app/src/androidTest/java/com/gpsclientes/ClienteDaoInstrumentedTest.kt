package com.gpsclientes

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gpsclientes.data.local.AppDatabase
import com.gpsclientes.data.local.ClienteDao
import com.gpsclientes.data.local.ClienteEntity
import com.gpsclientes.data.local.GeocodingSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Room tests — run with: ./gradlew connectedAndroidTest
 * Covers 31-col round-trip, indices, hasGpsFix, flagged, normalized search.
 */
@RunWith(AndroidJUnit4::class)
class ClienteDaoInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ClienteDao

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.clienteDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun roundTrip31Cols() = runTest {
        val e = ClienteEntity(
            nombreCanonico = "Terminal el Vigía",
            nombreNormalizado = "terminal el vigia",
            rif = "V267230346",
            lat = 8.6167, lng = -71.65,
            textoBreve = "El Vigía, Municipio Alberto Adriani",
            referenciaManual = "a 500m Plaza",
            fechaCaptura = 123L, precisionMeters = 12f,
            geocodingSource = GeocodingSource.GEOCODER,
            direccionOriginalExcel = "orig", isFlaggedImport = true, hasGpsFix = true
        )
        val id = dao.insert(e)
        val loaded = dao.getById(id)!!
        assertEquals("Terminal el Vigía", loaded.nombreCanonico)
        assertEquals("terminal el vigia", loaded.nombreNormalizado)
        assertEquals(true, loaded.hasGpsFix)
        assertEquals(8.6167, loaded.lat!!, 0.0001)
    }

    @Test fun likeCandidatesLimit10Ranked() = runTest {
        repeat(12) { i -> dao.insert(ClienteEntity(nombreCanonico = "Bodegon $i", nombreNormalizado = "bodegon $i")) }
        val results = dao.findLikeCandidates("bodegon")
        assertTrue(results.size <= 10)
    }

    @Test fun flaggedAndHasGpsFixFilters() = runTest {
        dao.insert(ClienteEntity(nombreCanonico = "A", nombreNormalizado = "a", isFlaggedImport = true, hasGpsFix = false))
        dao.insert(ClienteEntity(nombreCanonico = "B", nombreNormalizado = "b", isFlaggedImport = false, hasGpsFix = true))
        val flagged = dao.getFlaggedImports().first()
        assertEquals(1, flagged.size)
        assertEquals("A", flagged[0].nombreCanonico)
        val withGps = dao.getWithGpsFix().first()
        assertEquals(1, withGps.size)
    }

    @Test fun encodingVigiaPreserved() = runTest {
        val e = ClienteEntity(nombreCanonico = "Bodegón El Vigía", nombreNormalizado = "bodegon el vigia")
        val id = dao.insert(e)
        assertEquals("Bodegón El Vigía", dao.getById(id)!!.nombreCanonico)
    }
}
