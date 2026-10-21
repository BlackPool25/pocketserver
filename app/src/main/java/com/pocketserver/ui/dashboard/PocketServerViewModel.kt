package com.pocketserver.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketserver.hub.forge.PocketForgeManager
import com.pocketserver.hub.forge.PocketForgeRepository
import com.pocketserver.hub.search.PackageTier
import com.pocketserver.hub.search.UnifiedPackageRecord
import com.pocketserver.hub.search.UnifiedPackageSearchDb
import com.pocketserver.hub.shim.BinaryShimGenerator
import com.pocketserver.storage.cas.ContentAddressableStore
import com.pocketserver.storage.db.PocketDatabase
import com.pocketserver.storage.db.SandboxEntity
import com.pocketserver.storage.doctor.StorageBreakdown
import com.pocketserver.storage.doctor.StorageDoctor
import com.pocketserver.ui.commands.FriendlyCommandRunner
import com.pocketserver.ui.editor.PocketEditorSession
import com.pocketserver.ui.logs.HumanLogViewer
import com.pocketserver.ui.monorepo.MonorepoWakeCoordinator
import com.pocketserver.ui.onboarding.StarterTemplate
import com.pocketserver.ui.onboarding.TemplateProvisioner
import com.pocketserver.ui.onboarding.repo.RepoImportOrchestrator
import com.pocketserver.ui.system.PocketNotificationController
import com.pocketserver.ui.system.PocketServerTileService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Main ViewModel driving the PocketServer dashboard, service lifecycles,
 * progressive disclosure states, onboarding, storage doctor,
 * and the 70,000+ package search engine with PocketForge community repositories.
 */
class PocketServerViewModel(
    private val context: Context,
    private val database: PocketDatabase,
    val wakeCoordinator: MonorepoWakeCoordinator = MonorepoWakeCoordinator(),
    val storageDoctor: StorageDoctor = StorageDoctor(null, database),
    val searchDb: UnifiedPackageSearchDb = UnifiedPackageSearchDb(context),
    val pocketForgeManager: PocketForgeManager = PocketForgeManager(
        searchDb = searchDb,
        casStore = ContentAddressableStore(File(context.filesDir, "store"), database),
        shimGenerator = BinaryShimGenerator(File(context.filesDir, "bin"))
    ),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {

    private val templateProvisioner = TemplateProvisioner(context, database)
    private val repoImporter = RepoImportOrchestrator(
        context = context,
        database = database,
        wakeCoordinator = wakeCoordinator
    )
    private val notificationController = PocketNotificationController(context)

    private val _services = MutableStateFlow<List<ServiceCardModel>>(emptyList())
    val services: StateFlow<List<ServiceCardModel>> = _services.asStateFlow()

    private val _activeFilter = MutableStateFlow(DashboardFilter.ALL)
    val activeFilter: StateFlow<DashboardFilter> = _activeFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _storageBreakdown = MutableStateFlow<StorageBreakdown?>(null)
    val storageBreakdown: StateFlow<StorageBreakdown?> = _storageBreakdown.asStateFlow()

    // Package search & community forge flows
    private val _packageSearchResults = MutableStateFlow<List<UnifiedPackageRecord>>(emptyList())
    val packageSearchResults: StateFlow<List<UnifiedPackageRecord>> = _packageSearchResults.asStateFlow()

    private val _customRepositories = MutableStateFlow<List<PocketForgeRepository>>(emptyList())
    val customRepositories: StateFlow<List<PocketForgeRepository>> = _customRepositories.asStateFlow()

    // Per-service log viewers, editors, and command runners
    private val logViewers = ConcurrentHashMap<String, HumanLogViewer>()
    private val editorSessions = ConcurrentHashMap<String, PocketEditorSession>()
    private val commandRunners = ConcurrentHashMap<String, FriendlyCommandRunner>()

    private val _isZeroDataState = MutableStateFlow(true)
    val isZeroDataState: StateFlow<Boolean> = _isZeroDataState.asStateFlow()

    private val _filteredServices = MutableStateFlow<List<ServiceCardModel>>(emptyList())
    val filteredServices: StateFlow<List<ServiceCardModel>> = _filteredServices.asStateFlow()

    private fun recomputeFilteredServices() {
        val allServices = _services.value
        val filter = _activeFilter.value
        val queryLower = _searchQuery.value.trim().lowercase()

        _isZeroDataState.value = allServices.isEmpty()

        _filteredServices.value = allServices.filter { service ->
            val matchesFilter = when (filter) {
                DashboardFilter.ALL -> true
                DashboardFilter.LIVE -> service.status.equals("LIVE", ignoreCase = true)
                DashboardFilter.SLEEPING -> service.status.equals("SLEEPING", ignoreCase = true)
                DashboardFilter.STOPPED -> service.status.equals("STOPPED", ignoreCase = true)
            }
            val matchesQuery = queryLower.isEmpty() ||
                    service.name.lowercase().contains(queryLower) ||
                    service.framework.lowercase().contains(queryLower) ||
                    service.assignedPort.toString().contains(queryLower)

            matchesFilter && matchesQuery
        }
    }

    init {
        loadServices()
        refreshStorageDoctor()
        registerQsTileListener()
    }

    private fun registerQsTileListener() {
        PocketServerTileService.onToggleListener = { active ->
            if (active) {
                resumeAllServices()
            } else {
                pauseAllServices()
            }
        }
    }

    /**
     * Loads all sandboxes from the database and constructs presentation cards synchronously.
     */
    suspend fun loadServicesSync() = withContext(ioDispatcher) {
        val entities = database.sandboxDao().getAll()
        val localIp = "192.168.1.105" // Default resolved Wi-Fi baseline

        val cards = entities.map { entity ->
            val link = wakeCoordinator.getLinkForService(entity.serviceId)
            val monorepoBadge = when {
                link != null && link.backendServiceId == entity.serviceId -> "[Linked to Frontend]"
                link != null && link.frontendServiceId == entity.serviceId -> "[Linked to Backend]"
                link != null -> "[Linked Service]"
                else -> null
            }

            val framework = inferFramework(entity.projectRepoPath)

            ServiceCardModel(
                serviceId = entity.serviceId,
                name = entity.name,
                framework = framework,
                assignedPort = entity.assignedPort,
                localUrl = "http://$localIp:${entity.assignedPort}",
                mDnsUrl = "${entity.name}.local",
                publicTunnelUrl = "https://${entity.name}.trycloudflare.com",
                status = entity.status,
                monorepoBadge = monorepoBadge,
                ramUsageMb = if (entity.status == "LIVE") 24L else 0L,
                cpuPercent = if (entity.status == "LIVE") 0.1 else 0.0
            )
        }

        _services.value = cards
        recomputeFilteredServices()
        updateSystemNotifications(cards)
    }

    /**
     * Asynchronously loads all sandboxes from the database.
     */
    fun loadServices() {
        viewModelScope.launch(ioDispatcher) {
            loadServicesSync()
        }
    }

    private fun inferFramework(repoPath: String): String {
        val dir = File(repoPath)
        return when {
            File(dir, "package.json").exists() -> "Bun / Vite Frontend"
            File(dir, "main.py").exists() -> "Python FastAPI"
            File(dir, "go.mod").exists() -> "Go Microservice"
            File(dir, "Cargo.toml").exists() -> "Rust Axum"
            else -> "Web Service"
        }
    }

    /**
     * Toggles progressive disclosure (The Layer Beneath) for a service card.
     */
    fun toggleServiceExpansion(serviceId: String) {
        _services.value = _services.value.map {
            if (it.serviceId == serviceId) it.copy(isExpanded = !it.isExpanded) else it
        }
        recomputeFilteredServices()
    }

    /**
     * Synchronously toggles a service between LIVE and SLEEPING.
     */
    suspend fun toggleServiceSleepStateSync(serviceId: String) = withContext(ioDispatcher) {
        val current = _services.value.firstOrNull { it.serviceId == serviceId } ?: return@withContext
        val nextStatus = when (current.status.uppercase()) {
            "LIVE", "ACTIVE", "RUNNING" -> "SLEEPING"
            "SLEEPING" -> "LIVE"
            else -> "LIVE"
        }

        database.sandboxDao().updateStatus(serviceId, nextStatus)

        val updated = _services.value.map {
            if (it.serviceId == serviceId) it.copy(status = nextStatus) else it
        }
        _services.value = updated
        recomputeFilteredServices()
        updateSystemNotifications(updated)
    }

    fun toggleServiceSleepState(serviceId: String) {
        viewModelScope.launch(ioDispatcher) {
            toggleServiceSleepStateSync(serviceId)
        }
    }

    suspend fun pauseAllServicesSync() = withContext(ioDispatcher) {
        for (service in _services.value) {
            database.sandboxDao().updateStatus(service.serviceId, "SLEEPING")
        }
        val updated = _services.value.map { it.copy(status = "SLEEPING") }
        _services.value = updated
        recomputeFilteredServices()
        updateSystemNotifications(updated)
    }

    fun pauseAllServices() {
        viewModelScope.launch(ioDispatcher) {
            pauseAllServicesSync()
        }
    }

    suspend fun resumeAllServicesSync() = withContext(ioDispatcher) {
        for (service in _services.value) {
            database.sandboxDao().updateStatus(service.serviceId, "LIVE")
        }
        val updated = _services.value.map { it.copy(status = "LIVE") }
        _services.value = updated
        recomputeFilteredServices()
        updateSystemNotifications(updated)
    }

    fun resumeAllServices() {
        viewModelScope.launch(ioDispatcher) {
            resumeAllServicesSync()
        }
    }

    private fun updateSystemNotifications(cards: List<ServiceCardModel>) {
        val liveList = cards.filter { it.status == "LIVE" }
        val sleepingList = cards.filter { it.status == "SLEEPING" }
        val isAnyLive = liveList.isNotEmpty()
        val ports = liveList.map { it.assignedPort }

        PocketServerTileService.setServerStatus(isAnyLive, ports)
        notificationController.buildNotification(
            liveCount = liveList.size,
            sleepingCount = sleepingList.size,
            activePorts = ports
        )
    }

    fun setFilter(filter: DashboardFilter) {
        _activeFilter.value = filter
        recomputeFilteredServices()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        recomputeFilteredServices()
    }

    /**
     * Synchronously provisions a starter template.
     */
    suspend fun provisionTemplateSync(template: StarterTemplate) {
        templateProvisioner.provision(template)
        loadServicesSync()
    }

    fun provisionTemplate(template: StarterTemplate) {
        viewModelScope.launch(ioDispatcher) {
            provisionTemplateSync(template)
        }
    }

    /**
     * Synchronously imports a project repository.
     */
    suspend fun importRepositorySync(repoUrl: String, sourceDir: File? = null) {
        repoImporter.importProject(repoUrl, sourceDir)
        loadServicesSync()
    }

    fun importRepository(repoUrl: String, sourceDir: File? = null) {
        viewModelScope.launch(ioDispatcher) {
            importRepositorySync(repoUrl, sourceDir)
        }
    }

    fun getLogViewer(serviceId: String): HumanLogViewer {
        return logViewers.getOrPut(serviceId) { HumanLogViewer(serviceId) }
    }

    fun getEditorSession(serviceId: String): PocketEditorSession {
        return editorSessions.getOrPut(serviceId) { PocketEditorSession(serviceId) }
    }

    fun getCommandRunner(serviceId: String, workingDir: File): FriendlyCommandRunner {
        return commandRunners.getOrPut(serviceId) { FriendlyCommandRunner(workingDir) }
    }

    fun refreshStorageDoctor() {
        viewModelScope.launch(ioDispatcher) {
            val breakdown = storageDoctor.analyze(context.filesDir)
            _storageBreakdown.value = breakdown
        }
    }

    fun cleanStorageDoctorCaches() {
        viewModelScope.launch(ioDispatcher) {
            storageDoctor.cleanReclaimableCaches(baseDir = context.filesDir)
            refreshStorageDoctor()
        }
    }

    // --- Phase 5 Universal Package Search & PocketForge Operations ---

    /**
     * Executes sub-10ms search across 70,000+ packages.
     */
    fun searchPackages(query: String, filterTier: PackageTier? = null) {
        viewModelScope.launch(ioDispatcher) {
            searchPackagesSync(query, filterTier)
        }
    }

    fun searchPackagesSync(query: String, filterTier: PackageTier? = null) {
        val results = searchDb.search(query, filterTier)
        _packageSearchResults.value = results
    }

    fun loadCustomRepositories() {
        _customRepositories.value = pocketForgeManager.getRepositories()
    }

    fun addCustomRepository(repository: PocketForgeRepository): Result<PocketForgeRepository> {
        val result = pocketForgeManager.addRepository(repository)
        if (result.isSuccess) {
            loadCustomRepositories()
        }
        return result
    }

    fun removeCustomRepository(repositoryId: String): Boolean {
        val removed = pocketForgeManager.removeRepository(repositoryId)
        if (removed) {
            loadCustomRepositories()
        }
        return removed
    }
}
