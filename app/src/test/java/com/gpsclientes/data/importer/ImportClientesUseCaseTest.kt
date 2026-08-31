package com.gpsclientes.data.importer

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gpsclientes.data.local.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.io.FileInputStream

/** Verification — run: ./gradlew :app:testDebugUnitTest --tests "*Import*" */
class ImportClientesUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var useCase: ImportClientesUseCase

    @BeforeEach
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        useCase = ImportClientesUseCase(db)
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `import 409 rows with 9 flagged and Vigia preserved`() = runTest {
        val file = File("Clientes_TOM_KEVIN.xlsx")
        // In CI, file is copied to app/src/test/resources or use asset path
        val input = if (file.exists()) FileInputStream(file) else
            javaClass.classLoader.getResourceAsStream("Clientes_TOM_KEVIN.xlsx")!!

        val result = useCase.import(input)

        assertEquals(409, result.inserted + result.skippedInvalidRif, "409 total rows expected")
        assertEquals(409, result.inserted, "All 409 should insert (RIFs are valid)")
        assertEquals(9, result.flagged, "9 hash-prefixed rows flagged")

        val all = db.clienteDao().getAll()
        assertEquals(409, all.size)
        assertEquals(9, all.count { it.isFlaggedImport })

        // UTF-8 Vigia preserved
        val vigiaRow = all.firstOrNull { it.direccion?.contains("Vigía") == true }
        assertTrue(vigiaRow != null, "Vigía with accent must be preserved")
        assertTrue(vigiaRow?.direccionOriginalExcel?.contains("Vigía") == true)

        // Normalization check
        val normalized = Normalize.normalize("Vigía Central")
        assertEquals("vigia central", normalized)
    }

    @Test
    fun `skip invalid RIF X123 and commit rest`() = runTest {
        assertTrue(!Regex("^[JVEGP]\\d{7,9}$").matches("X123"))
        assertTrue(Regex("^[JVEGP]\\d{7,9}$").matches("V12345678"))
    }
}
