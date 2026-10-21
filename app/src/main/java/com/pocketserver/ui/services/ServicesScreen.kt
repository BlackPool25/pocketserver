package com.pocketserver.ui.services

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pocketserver.R
import com.pocketserver.persistence.boot.PersistencePreferences
import com.pocketserver.ui.components.DestructiveConfirmDialog
import com.pocketserver.ui.components.HostChip
import com.pocketserver.ui.components.PocketButton
import com.pocketserver.ui.components.PocketButtonVariant
import com.pocketserver.ui.components.QrShareModal
import com.pocketserver.ui.components.ServiceCard
import com.pocketserver.ui.components.SkeletonServiceCard
import com.pocketserver.ui.components.StatusStrip
import com.pocketserver.ui.components.TemplateRow
import com.pocketserver.ui.dashboard.PocketServerViewModel
import com.pocketserver.ui.dashboard.ServiceCardModel
import com.pocketserver.ui.onboarding.StarterTemplate
import com.pocketserver.ui.theme.PocketTheme
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ServicesScreen(
    viewModel: PocketServerViewModel,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToPackages: () -> Unit,
    onNavigateToSystem: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography
    val shapes = PocketTheme.shapes

    val filteredServices by viewModel.filteredServices.collectAsState()
    val isZeroData by viewModel.isZeroDataState.collectAsState()
    val isVpnActive = remember(context) { PersistencePreferences.isVpnShieldEnabled(context) }

    val hasRunningService = filteredServices.any { it.status.equals("LIVE", ignoreCase = true) }

    var showNewServiceSheet by remember { mutableStateOf(false) }
    var showTemplateSheet by remember { mutableStateOf(false) }
    var activeQrService by remember { mutableStateOf<ServiceCardModel?>(null) }
    var serviceToDelete by remember { mutableStateOf<ServiceCardModel?>(null) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            Toast.makeText(context, "Importing project folder...", Toast.LENGTH_SHORT).show()
            viewModel.importRepository(uri.toString())
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
        containerColor = colors.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background)
                    .statusBarsPadding()
            ) {
                // Large Top Bar: "Services" + HostChip
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.title_services),
                        style = typography.display,
                        color = colors.textPrimary
                    )
                    HostChip(
                        isRunning = hasRunningService,
                        onClick = onNavigateToSystem
                    )
                }

                // Background Protection Status Strip
                StatusStrip(
                    isProtectionActive = isVpnActive,
                    telemetryHint = if (hasRunningService) "Battery impact <1%" else null,
                    onClick = onNavigateToSystem
                )
            }
        },
        floatingActionButton = {
            PocketButton(
                onClick = { showNewServiceSheet = true },
                variant = PocketButtonVariant.Primary,
                text = stringResource(R.string.action_new_service),
                leadingIcon = painterResource(R.drawable.ic_add)
            )
        },
        floatingActionButtonPosition = FabPosition.End
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isZeroData) {
                // Zero-data Onboarding Screen
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.empty_services_description),
                            style = typography.body,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(StarterTemplate.ALL_TEMPLATES, key = { it.id }) { template ->
                        TemplateRow(
                            title = template.title,
                            description = template.description,
                            glyph = template.runtimeName.take(2),
                            trailingBadge = "~5 s",
                            onClick = {
                                Toast.makeText(context, "Provisioning ${template.title}...", Toast.LENGTH_SHORT).show()
                                viewModel.provisionTemplate(template)
                            }
                        )
                    }
                }
            } else {
                // Populated Services List
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredServices, key = { it.serviceId }) { service ->
                        ServiceCard(
                            service = service,
                            modifier = Modifier.animateItem(),
                            onCardClick = { onNavigateToDetail(service.serviceId) },
                            onOpenClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(service.localUrl))
                                context.startActivity(intent)
                            },
                            onToggleStateClick = {
                                viewModel.toggleServiceSleepState(service.serviceId)
                            },
                            onShareQrClick = {
                                activeQrService = service
                            },
                            onCopyUrlClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Service URL", service.localUrl))
                                Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            onRestartClick = {
                                viewModel.startService(service.serviceId)
                                Toast.makeText(context, "Restarting ${service.name}...", Toast.LENGTH_SHORT).show()
                            },
                            onLogsClick = {
                                onNavigateToDetail(service.serviceId)
                            },
                            onDeleteClick = {
                                serviceToDelete = service
                            }
                        )
                    }
                }
            }
        }
    }

    // Modal Sheet: "New service"
    if (showNewServiceSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showNewServiceSheet = false },
            sheetState = sheetState,
            containerColor = colors.raised,
            shape = shapes.bottomSheet
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = stringResource(R.string.title_new_service),
                    style = typography.title,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                TemplateRow(
                    title = "From template",
                    description = "Deploy Python, React, Go, Bun, or Rust in 1 tap",
                    glyph = "tm",
                    trailingBadge = "5 presets",
                    onClick = {
                        showNewServiceSheet = false
                        showTemplateSheet = true
                    }
                )
                TemplateRow(
                    title = "From package",
                    description = "Choose from 70,000+ runtimes and tools",
                    glyph = "pk",
                    trailingBadge = "70k+",
                    onClick = {
                        showNewServiceSheet = false
                        onNavigateToPackages()
                    }
                )
                TemplateRow(
                    title = "Import folder",
                    description = "Launch an existing local project repository",
                    glyph = "fd",
                    trailingBadge = "Local",
                    showDivider = false,
                    onClick = {
                        showNewServiceSheet = false
                        folderPickerLauncher.launch(null)
                    }
                )
            }
        }
    }

    // Sub-sheet: "Choose a template"
    if (showTemplateSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showTemplateSheet = false },
            sheetState = sheetState,
            containerColor = colors.raised,
            shape = shapes.bottomSheet
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "Starter templates",
                    style = typography.title,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                for (template in StarterTemplate.ALL_TEMPLATES) {
                    TemplateRow(
                        title = template.title,
                        description = template.description,
                        glyph = template.runtimeName.take(2),
                        trailingBadge = "~5 s",
                        onClick = {
                            showTemplateSheet = false
                            Toast.makeText(context, "Provisioning ${template.title}...", Toast.LENGTH_SHORT).show()
                            viewModel.provisionTemplate(template)
                        }
                    )
                }
            }
        }
    }

    // QR Share Modal
    activeQrService?.let { service ->
        QrShareModal(
            serviceName = service.name,
            localUrl = service.localUrl,
            publicTunnelUrl = service.publicTunnelUrl,
            onDismiss = { activeQrService = null },
            onCopyUrl = { url ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Service URL", url))
                Toast.makeText(context, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // Destructive Confirmation Dialog: Delete service
    serviceToDelete?.let { service ->
        DestructiveConfirmDialog(
            title = "Delete ${service.name}?",
            message = "This will terminate the service process and remove the project sandbox. This action cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                val id = service.serviceId
                serviceToDelete = null
                viewModel.deleteService(id)
                Toast.makeText(context, "Service deleted", Toast.LENGTH_SHORT).show()
            },
            onDismiss = {
                serviceToDelete = null
            }
        )
    }
}
