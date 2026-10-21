package com.pocketserver.ui.main

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pocketserver.PocketServerApplication
import com.pocketserver.persistence.boot.PersistencePreferences
import com.pocketserver.persistence.vpn.PocketServerVpnService
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

    private lateinit var servicesContainer: LinearLayout
    private lateinit var zeroDataContainer: LinearLayout
    private lateinit var statusBadgeView: TextView
    private lateinit var vpnShieldButton: Button

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            PersistencePreferences.setVpnShieldEnabled(this, true)
            PocketServerVpnService.start(this)
            updateVpnButtonState()
            Toast.makeText(this, "Loopback persistence shield activated", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "VPN permission denied by user", Toast.LENGTH_SHORT).show()
        }
    }

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

        buildDashboardLayout()
        observeViewModel()
    }

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }

    private fun buildDashboardLayout() {
        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(PocketDesignTokens.CanvasColor)
            isFillViewport = true
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16f), dpToPx(24f), dpToPx(16f), dpToPx(32f))
        }

        // 1. Header Bar: Brand Logo & Title + Status Chip
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(16f))
        }

        val titleView = TextView(this).apply {
            text = "PocketServer"
            textSize = 22f
            setTextColor(PocketDesignTokens.TextPrimaryColor)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        statusBadgeView = TextView(this).apply {
            text = "HOST ACTIVE"
            textSize = 11f
            setTextColor(PocketDesignTokens.StatusLiveColor)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpToPx(8f), dpToPx(4f), dpToPx(8f), dpToPx(4f))
            background = GradientDrawable().apply {
                setColor(PocketDesignTokens.SurfaceElevatedColor)
                cornerRadius = dpToPx(PocketDesignTokens.BADGE_CORNER_RADIUS_DP).toFloat()
                setStroke(dpToPx(1f), PocketDesignTokens.StatusLiveColor)
            }
        }

        headerLayout.addView(titleView)
        headerLayout.addView(statusBadgeView)
        contentLayout.addView(headerLayout)

        // 2. Search & Package Discovery Bar
        val searchInput = EditText(this).apply {
            hint = "Search 70,000+ packages or runtimes..."
            setHintTextColor(PocketDesignTokens.TextMutedColor)
            setTextColor(PocketDesignTokens.TextPrimaryColor)
            textSize = 14f
            setPadding(dpToPx(14f), dpToPx(12f), dpToPx(14f), dpToPx(12f))
            background = GradientDrawable().apply {
                setColor(PocketDesignTokens.SurfaceColor)
                cornerRadius = dpToPx(PocketDesignTokens.INPUT_CORNER_RADIUS_DP).toFloat()
                setStroke(dpToPx(1f), PocketDesignTokens.BorderColor)
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    onSearchQueryChanged(s?.toString().orEmpty())
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        val searchParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, dpToPx(16f))
        }
        contentLayout.addView(searchInput, searchParams)

        // 3. System Host Diagnostics Card
        val sysCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16f), dpToPx(14f), dpToPx(16f), dpToPx(14f))
            background = GradientDrawable().apply {
                setColor(PocketDesignTokens.SurfaceColor)
                cornerRadius = dpToPx(PocketDesignTokens.CARD_CORNER_RADIUS_DP).toFloat()
                setStroke(dpToPx(1f), PocketDesignTokens.BorderColor)
            }
        }

        val cardTitle = TextView(this).apply {
            text = "Local Node Environment"
            textSize = 14f
            setTextColor(PocketDesignTokens.TextPrimaryColor)
            typeface = Typeface.DEFAULT_BOLD
        }
        val cardSubtitle = TextView(this).apply {
            text = "Non-Root • 16KB Aligned • Zero VM Overhead"
            textSize = 12f
            setTextColor(PocketDesignTokens.TextSecondaryColor)
            setPadding(0, dpToPx(2f), 0, dpToPx(8f))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val vpnBtn = Button(this).apply {
            textSize = 12f
            setPadding(dpToPx(12f), dpToPx(6f), dpToPx(12f), dpToPx(6f))
            setOnClickListener {
                toggleVpnShield()
            }
        }
        vpnShieldButton = vpnBtn
        updateVpnButtonState()

        val cleanBtn = Button(this).apply {
            text = "Storage Doctor"
            textSize = 12f
            setTextColor(PocketDesignTokens.TextPrimaryColor)
            setBackgroundColor(PocketDesignTokens.SurfaceElevatedColor)
            setOnClickListener {
                onCleanStorageDoctorClicked()
                Toast.makeText(this@MainActivity, "Storage caches purged cleanly", Toast.LENGTH_SHORT).show()
            }
        }

        btnRow.addView(vpnShieldButton)
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(8f), 1)
        }
        btnRow.addView(spacer)
        btnRow.addView(cleanBtn)

        sysCard.addView(cardTitle)
        sysCard.addView(cardSubtitle)
        sysCard.addView(btnRow)

        val sysParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, dpToPx(20f))
        }
        contentLayout.addView(sysCard, sysParams)

        // 4. Services List Container
        val servicesHeader = TextView(this).apply {
            text = "Hosted Services & Containers"
            textSize = 16f
            setTextColor(PocketDesignTokens.TextPrimaryColor)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dpToPx(8f))
        }
        contentLayout.addView(servicesHeader)

        servicesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentLayout.addView(servicesContainer)

        // 5. Zero Data Onboarding Container
        zeroDataContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        buildZeroDataTemplatesView()
        contentLayout.addView(zeroDataContainer)

        rootScroll.addView(contentLayout)
        setContentView(rootScroll)
    }

    private fun updateVpnButtonState() {
        if (!::vpnShieldButton.isInitialized) return
        val isEnabled = PersistencePreferences.isVpnShieldEnabled(this)
        vpnShieldButton.text = if (isEnabled) "Shield: Active" else "Shield: Off"
        vpnShieldButton.setTextColor(
            if (isEnabled) PocketDesignTokens.StatusLiveColor else PocketDesignTokens.TextSecondaryColor
        )
        vpnShieldButton.setBackgroundColor(PocketDesignTokens.SurfaceElevatedColor)
    }

    private fun toggleVpnShield() {
        val isEnabled = PersistencePreferences.isVpnShieldEnabled(this)
        if (isEnabled) {
            PersistencePreferences.setVpnShieldEnabled(this, false)
            PocketServerVpnService.stop(this)
            updateVpnButtonState()
            Toast.makeText(this, "Loopback persistence shield disabled", Toast.LENGTH_SHORT).show()
        } else {
            val prepareIntent = VpnService.prepare(this)
            if (prepareIntent != null) {
                vpnConsentLauncher.launch(prepareIntent)
            } else {
                PersistencePreferences.setVpnShieldEnabled(this, true)
                PocketServerVpnService.start(this)
                updateVpnButtonState()
                Toast.makeText(this, "Loopback persistence shield activated", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildZeroDataTemplatesView() {
        zeroDataContainer.removeAllViews()

        val emptyText = TextView(this).apply {
            text = "No services currently running. Deploy your first local service in 1-tap:"
            textSize = 13f
            setTextColor(PocketDesignTokens.TextSecondaryColor)
            setPadding(0, dpToPx(8f), 0, dpToPx(12f))
        }
        zeroDataContainer.addView(emptyText)

        val templates = StarterTemplate.ALL_TEMPLATES

        for (tmpl in templates) {
            val tmplBtn = Button(this).apply {
                text = "${tmpl.title} — ${tmpl.description}"
                textSize = 12f
                setTextColor(PocketDesignTokens.TextPrimaryColor)
                setBackgroundColor(PocketDesignTokens.SurfaceColor)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    onLaunchTemplateClicked(tmpl)
                    Toast.makeText(this@MainActivity, "Provisioning ${tmpl.title}...", Toast.LENGTH_SHORT).show()
                }
            }
            val p = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(8f))
            }
            zeroDataContainer.addView(tmplBtn, p)
        }
    }

    private fun observeViewModel() {
        if (!::viewModel.isInitialized) return

        lifecycleScope.launch {
            viewModel.services.collect { services ->
                onServicesUpdated(services)
            }
        }

        lifecycleScope.launch {
            viewModel.isZeroDataState.collect { isZeroData ->
                zeroDataContainer.visibility = if (isZeroData) View.VISIBLE else View.GONE
            }
        }
    }

    private fun onServicesUpdated(services: List<ServiceCardModel>) {
        servicesContainer.removeAllViews()
        for (service in services) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(14f), dpToPx(12f), dpToPx(14f), dpToPx(12f))
                background = GradientDrawable().apply {
                    setColor(PocketDesignTokens.SurfaceColor)
                    cornerRadius = dpToPx(PocketDesignTokens.CARD_CORNER_RADIUS_DP).toFloat()
                    setStroke(dpToPx(1f), PocketDesignTokens.BorderColor)
                }
            }

            val title = TextView(this).apply {
                text = service.name
                textSize = 14f
                setTextColor(PocketDesignTokens.TextPrimaryColor)
                typeface = Typeface.DEFAULT_BOLD
            }

            val statusText = TextView(this).apply {
                text = "${PocketDesignTokens.getHumanStatusDescription(service.status)} • ${service.localUrl}"
                textSize = 12f
                setTextColor(PocketDesignTokens.getStatusColor(service.status))
                setPadding(0, dpToPx(2f), 0, dpToPx(8f))
            }

            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val openBtn = Button(this).apply {
                text = "Open"
                textSize = 11f
                setOnClickListener { onOpenInBrowserClicked(service) }
            }

            val shareBtn = Button(this).apply {
                text = "Share QR"
                textSize = 11f
                setOnClickListener { onShareClicked(service) }
            }

            val toggleBtn = Button(this).apply {
                text = if (service.status == "RUNNING") "Sleep" else "Wake"
                textSize = 11f
                setOnClickListener { onToggleSleepStateClicked(service.serviceId) }
            }

            actionRow.addView(openBtn)
            actionRow.addView(shareBtn)
            actionRow.addView(toggleBtn)

            card.addView(title)
            card.addView(statusText)
            card.addView(actionRow)

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(10f))
            }
            servicesContainer.addView(card, lp)
        }
    }

    private fun showZeroDataOnboarding() {
        zeroDataContainer.visibility = View.VISIBLE
    }

    // --- User Actions ---

    fun onLaunchTemplateClicked(template: StarterTemplate) {
        if (::viewModel.isInitialized) {
            viewModel.provisionTemplate(template)
        }
    }

    fun onImportRepositorySubmitted(repoUrl: String) {
        if (::viewModel.isInitialized) {
            viewModel.importRepository(repoUrl)
        }
    }

    fun onOpenInBrowserClicked(service: ServiceCardModel) {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(service.localUrl))
        startActivity(browserIntent)
    }

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

    fun onDismissShareModal() {
        activeQrController?.onModalDismissed(window)
        activeQrController = null
    }

    fun onCleanStorageDoctorClicked() {
        if (::viewModel.isInitialized) {
            viewModel.cleanStorageDoctorCaches()
        }
    }

    suspend fun onSaveAndRestartEditor(
        serviceId: String,
        file: File,
        newContent: String,
        restartAction: suspend (String) -> Unit
    ) {
        if (::viewModel.isInitialized) {
            val session = viewModel.getEditorSession(serviceId)
            val coordinator = HotReloadCoordinator(session)
            coordinator.saveAndRestart(serviceId, file, newContent, restartAction)
        }
    }

    fun onFilterSelected(filter: DashboardFilter) {
        if (::viewModel.isInitialized) {
            viewModel.setFilter(filter)
        }
    }

    fun onSearchQueryChanged(query: String) {
        if (::viewModel.isInitialized) {
            viewModel.setSearchQuery(query)
        }
    }

    fun onToggleSleepStateClicked(serviceId: String) {
        if (::viewModel.isInitialized) {
            viewModel.toggleServiceSleepState(serviceId)
        }
    }

    fun onServiceCardTapped(serviceId: String) {
        if (::viewModel.isInitialized) {
            viewModel.toggleServiceExpansion(serviceId)
        }
    }
}
