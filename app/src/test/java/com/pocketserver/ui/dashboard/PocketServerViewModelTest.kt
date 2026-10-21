package com.pocketserver.ui.dashboard

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketserver.storage.db.PocketDatabase
import com.pocketserver.ui.onboarding.StarterTemplate
import com.pocketserver.ui.system.PocketServerTileService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PocketServerViewModelTest {

    private lateinit var context: Context
    private lateinit var database: PocketDatabase
    private lateinit var viewModel: PocketServerViewModel
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PocketDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        viewModel = PocketServerViewModel(
            context = context,
            database = database,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testZeroDataStateInitiallyTrue() = runTest(testDispatcher) {
        viewModel.loadServicesSync()
        assertThat(viewModel.services.value).isEmpty()
        assertThat(viewModel.isZeroDataState.value).isTrue()
    }

    @Test
    fun testProvisionTemplateAndProgressiveDisclosure() = runTest(testDispatcher) {
        // 1. User taps 1-tap starter template
        viewModel.provisionTemplateSync(StarterTemplate.FASTAPI_HELLO_WORLD)

        val services = viewModel.services.value
        assertThat(services).hasSize(1)
        assertThat(viewModel.isZeroDataState.value).isFalse()

        val service = services[0]
        assertThat(service.assignedPort).isEqualTo(8000)
        assertThat(service.isExpanded).isFalse() // Surface only

        // 2. User taps "Details & Tools" (Progressive disclosure)
        viewModel.toggleServiceExpansion(service.serviceId)
        val expanded = viewModel.services.value[0]
        assertThat(expanded.isExpanded).isTrue()
    }

    @Test
    fun testFilteringAndSearchQuery() = runTest(testDispatcher) {
        viewModel.provisionTemplateSync(StarterTemplate.FASTAPI_HELLO_WORLD)
        viewModel.provisionTemplateSync(StarterTemplate.REACT_VITE_FRONTEND)

        assertThat(viewModel.services.value).hasSize(2)

        // Search by port 3000
        viewModel.setSearchQuery("3000")
        assertThat(viewModel.filteredServices.value).hasSize(1)
        assertThat(viewModel.filteredServices.value[0].assignedPort).isEqualTo(3000)

        // Reset query and filter by LIVE (both initially STOPPED)
        viewModel.setSearchQuery("")
        viewModel.setFilter(DashboardFilter.LIVE)
        assertThat(viewModel.filteredServices.value).isEmpty()

        // Filter STOPPED
        viewModel.setFilter(DashboardFilter.STOPPED)
        assertThat(viewModel.filteredServices.value).hasSize(2)
    }

    @Test
    fun testToggleServiceSleepState() = runTest(testDispatcher) {
        viewModel.provisionTemplateSync(StarterTemplate.FASTAPI_HELLO_WORLD)

        val serviceId = viewModel.services.value[0].serviceId

        // Initially STOPPED -> toggle turns to LIVE
        viewModel.toggleServiceSleepStateSync(serviceId)
        assertThat(viewModel.services.value[0].status).isEqualTo("LIVE")
        assertThat(PocketServerTileService.isServerActive.value).isTrue()

        // LIVE -> toggle turns to SLEEPING
        viewModel.toggleServiceSleepStateSync(serviceId)
        assertThat(viewModel.services.value[0].status).isEqualTo("SLEEPING")
        assertThat(PocketServerTileService.isServerActive.value).isFalse()
    }
}
