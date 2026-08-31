package com.gpsclientes.ui.map

import android.location.Location
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.gpsclientes.data.local.ClienteDao
import com.gpsclientes.data.location.FusedLocationRepository
import com.gpsclientes.data.location.GeocodingRepository
import com.gpsclientes.data.location.LatLngPrecision
import com.gpsclientes.data.location.LocationFailureReason
import com.gpsclientes.data.location.LocationResult
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.Test

/**
 * PR4 5.1 — Unit Turbine for ViewModel GPS parity.
 * Covers debounce 500ms, TTL 30s instant/stale, VROOM start [lng,lat], timeout TIMEOUT, overlay sync.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapaClientesViewModelTest {

    @get:Rule val instantRule = InstantTaskExecutorRule()
    private val dispatcher = StandardTestDispatcher()

    private lateinit var dao: ClienteDao
    private lateinit var geocoding: GeocodingRepository
    private lateinit var fused: FusedLocationRepository

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        dao = mockk(relaxed = true)
        every { dao.observeAll() } returns flowOf(emptyList())
        geocoding = mockk(relaxed = true)
        fused = mockk()
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun buildVm() = MapaClientesViewModel(dao, geocoding, fused)

    @Test fun `debounce second tap within 500ms ignored`() = runTest(dispatcher) {
        coEvery { fused.getCurrentLocation() } coAnswers {
            kotlinx.coroutines.delay(200)
            LocationResult.Success(LatLngPrecision(8.6, -71.6, 12f, System.currentTimeMillis()))
        }
        val vm = buildVm()
        vm.centrarEnMiUbicacion()
        vm.centrarEnMiUbicacion() // <500ms -> ignored
        advanceUntilIdle()
        coVerify(exactly = 1) { fused.getCurrentLocation() }
        vm.centrarState.test {
            val last = awaitItem()
            assertTrue(last is CentrarState.Success || last is CentrarState.Loading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `TTL instant re-center under 30s without Fused call`() = runTest(dispatcher) {
        coEvery { fused.getCurrentLocation() } returns LocationResult.Success(
            LatLngPrecision(8.6, -71.6, 10f, System.currentTimeMillis())
        )
        val vm = buildVm()
        // seed fresh fix via overlay sync (<30s) — use mockk Location to avoid Android not-mocked
        val loc = mockk<Location>()
        every { loc.latitude } returns 8.61
        every { loc.longitude } returns -71.65
        every { loc.accuracy } returns 48f
        vm.onOverlayLocation(loc)
        // second call should be instant Success without repo
        coEvery { fused.getCurrentLocation() } coAnswers { error("should not be called") }
        vm.centrarEnMiUbicacion()
        advanceUntilIdle()
        val state = vm.centrarState.value
        assertTrue(state is CentrarState.Success, "expected instant Success from cache")
        val fix = (state as CentrarState.Success).fix
        assertEquals(8.61, fix.lat, 0.001)
        // VROOM start must be [lng,lat]
        val start = vm.cachedStartOrNull()
        assertNotNull(start)
        assertEquals(-71.65, start!![0], 0.001)
        assertEquals(8.61, start[1], 0.001)
    }

    @Test fun `TTL stale over 30s triggers fresh Fused request`() = runTest(dispatcher) {
        val vm = buildVm()
        // stale fix 31s old
        val stale = LatLngPrecision(8.6, -71.6, 10f, System.currentTimeMillis() - 31_000)
        vm.onMyLocationFix(stale)
        // fresh mock
        coEvery { fused.getCurrentLocation() } returns LocationResult.Success(
            LatLngPrecision(8.7, -71.7, 15f, System.currentTimeMillis())
        )
        vm.centrarEnMiUbicacion()
        advanceUntilIdle()
        coVerify(exactly = 1) { fused.getCurrentLocation() }
        assertTrue(vm.centrarState.value is CentrarState.Success)
    }

    @Test fun `timeout maps to Error Timeout 10s`() = runTest(dispatcher) {
        coEvery { fused.getCurrentLocation() } returns LocationResult.Failure(
            LocationFailureReason.TIMEOUT, "Timeout 10s — intenta de nuevo"
        )
        val vm = buildVm()
        vm.centrarEnMiUbicacion()
        advanceUntilIdle()
        val state = vm.centrarState.value
        assertTrue(state is CentrarState.Error)
        assertEquals("Timeout 10s — intenta de nuevo", (state as CentrarState.Error).message)
    }

    @Test fun `permission denied maps to unified copy`() = runTest(dispatcher) {
        coEvery { fused.getCurrentLocation() } returns LocationResult.Failure(
            LocationFailureReason.PERMISSION_DENIED, "Permiso denegado — ingresa manual"
        )
        val vm = buildVm()
        vm.centrarEnMiUbicacion()
        advanceUntilIdle()
        val err = vm.centrarState.value as CentrarState.Error
        assertEquals("Permiso denegado — ingresa manual", err.message)
    }

    @Test fun `cachedStartOrNull null when stale or missing`() = runTest(dispatcher) {
        val vm = buildVm()
        assertNull(vm.cachedStartOrNull(), "no fix -> null")
        vm.onMyLocationFix(LatLngPrecision(8.6, -71.6, 10f, System.currentTimeMillis() - 31_000))
        assertNull(vm.cachedStartOrNull(), "stale -> null")
        vm.onMyLocationFix(LatLngPrecision(8.6, -71.6, 10f, System.currentTimeMillis()))
        assertNotNull(vm.cachedStartOrNull())
    }

    @Test fun `onOverlayLocation syncs cachedFix with TTL`() = runTest(dispatcher) {
        val vm = buildVm()
        val loc = mockk<Location>()
        every { loc.latitude } returns 8.62
        every { loc.longitude } returns -71.66
        every { loc.accuracy } returns 800f
        vm.onOverlayLocation(loc)
        val cached = vm.cachedFix.value
        assertNotNull(cached)
        assertEquals(800f, cached!!.precisionMeters)
        // radius 800m case per spec large accuracy
        assertEquals(8.62, cached.lat, 0.001)
    }
}
