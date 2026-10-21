package com.pocketserver.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.pocketserver.PocketServerApplication
import com.pocketserver.ui.dashboard.DashboardFilter
import com.pocketserver.ui.dashboard.PocketServerViewModel
import com.pocketserver.ui.dashboard.ServiceCardModel
import com.pocketserver.ui.editor.HotReloadCoordinator
import com.pocketserver.ui.onboarding.StarterTemplate
import com.pocketserver.ui.share.QrSharingController
import com.pocketserver.ui.theme.PocketDesignTokens
import kotlinx.coroutines.launch
import java.io.File

/**
 * Main Activity for PocketServer.
 *
 * Implements the Human-Centric Zinc 950 Dark UI System with progressive disclosure,
 * quick start onboarding, monorepo import, PocketEditor, friendly command runner,
 * and high-fidelity QR sharing modal.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: PocketServerViewModel
    private var activeQrController: QrSharingController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply Zinc 950 Dark canvas styling to window
        window.statusBarColor = PocketDesignTokens.CanvasColor
        window.navigationBarColor = PocketDesignTokens.CanvasColor

        val app = application as? PocketServerApplication
        if (app != null) {
            viewModel = PocketServerViewModel(
                context = this,
                database = app.database,
                wakeCoordinator = app.wakeCoordinator,
                searchDb = app.searchDb,
                pocketForgeManager = app.pocketForgeManager
            )
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.services.collect { services ->
                onServicesUpdated(services)
            }
        }

        lifecycleScope.launch {
            viewModel.isZeroDataState.collect { isZeroData ->
                if (isZeroData) {
                    showZeroDataOnboarding()
                }
            }
        }
    }

    private fun onServicesUpdated(services: List<ServiceCardModel>) {
        // Updates UI list and badges
    }

    private fun showZeroDataOnboarding() {
        // Presents 1-tap quick start sample templates
    }

    // --- User Actions ---

    /**
     * 1-Tap Quick Start Template Launcher.
     */
    fun onLaunchTemplateClicked(template: StarterTemplate) {
        viewModel.provisionTemplate(template)
    }

    /**
     * 3-Tap Repository Importer.
     */
    fun onImportRepositorySubmitted(repoUrl: String) {
        viewModel.importRepository(repoUrl)
    }

    /**
     * Open hosted service in default mobile browser (Chrome/Firefox/Samsung Internet).
     */
    fun onOpenInBrowserClicked(service: ServiceCardModel) {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(service.localUrl))
        startActivity(browserIntent)
    }

    /**
     * Opens High-Fidelity QR Sharing Modal with auto-brightness boost.
     */
    fun onShareClicked(service: ServiceCardModel) {
        val controller = QrSharingController(
            serviceName = service.name,
            localWifiUrl = service.localUrl,
            mDnsUrl = service.mDnsUrl,
            publicTunnelUrl = service.publicTunnelUrl
        )
        activeQrController = controller
        controller.onModalShown(window)
    }

    /**
     * Dismisses High-Fidelity QR Sharing Modal, restoring original brightness.
     */
    fun onDismissShareModal() {
        activeQrController?.onModalDismissed(window)
        activeQrController = null
    }

    /**
     * 1-Tap Storage Doctor Deep Purge.
     */
    fun onCleanStorageDoctorClicked() {
        viewModel.cleanStorageDoctorCaches()
    }

    /**
     * Executes PocketEditor Hot Reload [Save & Restart].
     */
    suspend fun onSaveAndRestartEditor(
        serviceId: String,
        file: File,
        newContent: String,
        restartAction: suspend (String) -> Unit
    ) {
        val session = viewModel.getEditorSession(serviceId)
        val coordinator = HotReloadCoordinator(session)
        coordinator.saveAndRestart(serviceId, file, newContent, restartAction)
    }

    fun onFilterSelected(filter: DashboardFilter) {
        viewModel.setFilter(filter)
    }

    fun onSearchQueryChanged(query: String) {
        viewModel.setSearchQuery(query)
    }

    fun onToggleSleepStateClicked(serviceId: String) {
        viewModel.toggleServiceSleepState(serviceId)
    }

    fun onServiceCardTapped(serviceId: String) {
        viewModel.toggleServiceExpansion(serviceId)
    }
}
