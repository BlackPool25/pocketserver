package com.pocketserver.ui.services

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketserver.R
import com.pocketserver.ui.components.ErrorState
import com.pocketserver.ui.components.PocketButton
import com.pocketserver.ui.components.PocketButtonSize
import com.pocketserver.ui.components.PocketButtonVariant
import com.pocketserver.ui.components.QrShareModal
import com.pocketserver.ui.components.ServiceStatusState
import com.pocketserver.ui.components.StatusChip
import com.pocketserver.ui.dashboard.PocketServerViewModel
import com.pocketserver.ui.editor.ProjectFileNode
import com.pocketserver.ui.editor.ProjectFileTree
import com.pocketserver.ui.logs.LogFilter
import com.pocketserver.ui.logs.LogLevel
import com.pocketserver.ui.theme.PocketTheme
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ServiceDetailScreen(
    serviceId: String,
    viewModel: PocketServerViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography
    val shapes = PocketTheme.shapes
    val scope = rememberCoroutineScope()

    val services by viewModel.services.collectAsState()
    val service = services.firstOrNull { it.serviceId == serviceId }

    if (service == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
        ) {
            ErrorState(
                title = "Service not found",
                message = "The requested service sandbox could not be located.",
                actionLabel = "Back to services",
                onAction = onBack
            )
        }
        return
    }

    val statusState = ServiceStatusState.from(service.status)
    val isRunning = statusState == ServiceStatusState.Running

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabTitles = listOf(
        stringResource(R.string.tab_overview),
        stringResource(R.string.tab_logs),
        stringResource(R.string.tab_files),
        stringResource(R.string.tab_env),
        stringResource(R.string.tab_network)
    )

    var showQrModal by remember { mutableStateOf(false) }

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
                // Header Bar: Back Button + Service Name + StatusChip + Action Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = "Back",
                            tint = colors.textPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = service.name,
                            style = typography.title,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    StatusChip(status = statusState)
                    Spacer(modifier = Modifier.width(10.dp))
                    if (isRunning) {
                        PocketButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(service.localUrl))
                                context.startActivity(intent)
                            },
                            variant = PocketButtonVariant.Primary,
                            size = PocketButtonSize.Compact,
                            text = "Open"
                        )
                    } else {
                        PocketButton(
                            onClick = {
                                viewModel.startService(service.serviceId)
                                Toast.makeText(context, "Starting ${service.name}...", Toast.LENGTH_SHORT).show()
                            },
                            variant = PocketButtonVariant.Primary,
                            size = PocketButtonSize.Compact,
                            text = "Start"
                        )
                    }
                }

                // Segmented Tabs
                PrimaryTabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = colors.background,
                    contentColor = colors.textPrimary,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = {
                                Text(
                                    text = title,
                                    style = typography.bodyMedium,
                                    color = if (selectedTabIndex == index) colors.textPrimary else colors.textSecondary
                                )
                            }
                        )
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = colors.border)
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTabIndex) {
                0 -> DetailOverviewTab(
                    service = service,
                    onOpenUrl = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(service.localUrl))
                        context.startActivity(intent)
                    },
                    onShareQr = { showQrModal = true },
                    onCopyUrl = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Service URL", service.localUrl))
                        Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                    }
                )
                1 -> DetailLogsTab(
                    serviceId = service.serviceId,
                    viewModel = viewModel
                )
                2 -> DetailFilesTab(
                    serviceId = service.serviceId,
                    viewModel = viewModel
                )
                3 -> DetailEnvTab(
                    serviceId = service.serviceId,
                    viewModel = viewModel
                )
                4 -> DetailNetworkTab(
                    service = service,
                    viewModel = viewModel
                )
            }
        }
    }

    if (showQrModal) {
        QrShareModal(
            serviceName = service.name,
            localUrl = service.localUrl,
            publicTunnelUrl = service.publicTunnelUrl,
            onDismiss = { showQrModal = false },
            onCopyUrl = { url ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Service URL", url))
                Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun DetailOverviewTab(
    service: com.pocketserver.ui.dashboard.ServiceCardModel,
    onOpenUrl: () -> Unit,
    onShareQr: () -> Unit,
    onCopyUrl: () -> Unit
) {
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography
    val shapes = PocketTheme.shapes

    val statusState = ServiceStatusState.from(service.status)
    val isRunning = statusState == ServiceStatusState.Running
    val isSleeping = statusState == ServiceStatusState.Sleeping

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. URL Block (Always shown with real port)
        item {
            Text(
                text = "Service endpoint",
                style = typography.section,
                color = colors.textPrimary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = shapes.card,
                color = colors.raised,
                border = BorderStroke(1.dp, colors.border),
                shadowElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Local Wi-Fi URL",
                        style = typography.caption,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = service.localUrl,
                            style = typography.mono,
                            color = colors.accent,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onCopyUrl, modifier = Modifier.size(32.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_content_copy),
                                contentDescription = "Copy local URL",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(onClick = onShareQr, modifier = Modifier.size(32.dp)) {
                            Icon(
                                painter = painterResource(R.drawable.ic_qr_code_2),
                                contentDescription = "Share QR code",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // 2. Wake Behavior Hint (only if sleeping)
        if (isSleeping) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = shapes.card,
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.borderSubtle),
                    shadowElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_bolt),
                            contentDescription = null,
                            tint = colors.sleeping,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Wakes on the next request. Memory is frozen cleanly via SIGSTOP.",
                            style = typography.body,
                            color = colors.textPrimary
                        )
                    }
                }
            }
        }

        // 3. Resource Metrics (Shown ONLY if real data > 0 per DESIGN.md §9b)
        if (isRunning && (service.ramUsageMb > 0L || service.cpuPercent > 0.0)) {
            item {
                Text(
                    text = "Live telemetry",
                    style = typography.section,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = shapes.card,
                    color = colors.raised,
                    border = BorderStroke(1.dp, colors.border),
                    shadowElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        if (service.ramUsageMb > 0L) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "RAM usage",
                                    style = typography.caption,
                                    color = colors.textSecondary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${service.ramUsageMb} MB",
                                    style = typography.title,
                                    color = colors.textPrimary
                                )
                            }
                        }
                        if (service.cpuPercent > 0.0) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "CPU usage",
                                    style = typography.caption,
                                    color = colors.textSecondary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${String.format("%.1f", service.cpuPercent)}%",
                                    style = typography.title,
                                    color = colors.textPrimary
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. Framework & Runtime specs
        item {
            Text(
                text = "Configuration",
                style = typography.section,
                color = colors.textPrimary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = shapes.card,
                color = colors.raised,
                border = BorderStroke(1.dp, colors.border),
                shadowElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Framework", style = typography.body, color = colors.textSecondary)
                        Text(service.framework, style = typography.bodyMedium, color = colors.textPrimary)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Port", style = typography.body, color = colors.textSecondary)
                        Text(service.assignedPort.toString(), style = typography.mono, color = colors.textPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailLogsTab(
    serviceId: String,
    viewModel: PocketServerViewModel
) {
    val context = LocalContext.current
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography
    val viewer = remember(serviceId) { viewModel.getLogViewer(serviceId) }
    val logs by viewer.filteredLogs.collectAsState()
    val activeFilter by viewer.currentFilter.collectAsState()
    val isPaused by viewer.isAutoScrollPaused.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll to latest unless user touched / paused
    LaunchedEffect(logs.size, isPaused) {
        if (!isPaused && logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Filter Bar + Copy & Clear Actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = activeFilter == LogFilter.ALL,
                        onClick = { viewer.setFilter(LogFilter.ALL) },
                        label = { Text("All", style = typography.caption) }
                    )
                    FilterChip(
                        selected = activeFilter == LogFilter.ERRORS_ONLY,
                        onClick = { viewer.setFilter(LogFilter.ERRORS_ONLY) },
                        label = { Text("Errors", style = typography.caption) }
                    )
                    FilterChip(
                        selected = activeFilter == LogFilter.HTTP_REQUESTS,
                        onClick = { viewer.setFilter(LogFilter.HTTP_REQUESTS) },
                        label = { Text("HTTP", style = typography.caption) }
                    )
                }

                Row {
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Logs", viewer.copyCurrentLogsFormatted()))
                            Toast.makeText(context, "Logs copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_content_copy),
                            contentDescription = "Copy logs",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = { viewer.clear() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete_outline),
                            contentDescription = "Clear logs",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = colors.border)

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No log output recorded",
                        style = typography.body,
                        color = colors.textTertiary
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(logs, key = { it.id }) { log ->
                        val logColor = when (log.level) {
                            LogLevel.ERROR -> colors.error
                            LogLevel.WARN -> colors.warning
                            LogLevel.HTTP -> colors.accent
                            LogLevel.DEBUG -> colors.textTertiary
                            LogLevel.INFO -> colors.textPrimary
                        }
                        Text(
                            text = "[${log.formattedTimestamp}] ${log.message}",
                            style = typography.mono,
                            color = logColor
                        )
                    }
                }
            }
        }

        // Jump to Latest Floating Pill (§7 Motion: slide up + fade)
        AnimatedVisibility(
            visible = isPaused,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 20.dp)
        ) {
            PocketButton(
                onClick = { viewer.jumpToLatest() },
                variant = PocketButtonVariant.Primary,
                size = PocketButtonSize.Compact,
                text = "Jump to latest"
            )
        }
    }
}

@Composable
private fun DetailFilesTab(
    serviceId: String,
    viewModel: PocketServerViewModel
) {
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography
    val context = LocalContext.current

    val session = remember(serviceId) { viewModel.getEditorSession(serviceId) }
    val editorState by session.state.collectAsState()

    // Inspect sandbox directory
    val baseDir = remember(serviceId) {
        val cand1 = File(context.filesDir, "sandboxes/$serviceId")
        val cand2 = File(context.filesDir, "projects/$serviceId")
        if (cand1.exists()) cand1 else cand2
    }

    if (!baseDir.exists() || baseDir.listFiles().isNullOrEmpty()) {
        ErrorState(
            title = "No project files found",
            message = "Files will appear here once the sandbox has been provisioned.",
            icon = painterResource(R.drawable.ic_folder)
        )
    } else {
        val tree = remember(baseDir) { ProjectFileTree(baseDir).buildTree() }
        var selectedFile by remember { mutableStateOf<File?>(null) }
        var fileContent by remember { mutableStateOf("") }

        Row(modifier = Modifier.fillMaxSize()) {
            // File List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                items(tree.children) { node ->
                    FileTreeItem(
                        node = node,
                        onFileClick = { file ->
                            selectedFile = file
                            fileContent = if (file.exists()) file.readText() else ""
                        }
                    )
                }
            }

            // Simple File Viewer
            if (selectedFile != null) {
                Column(
                    modifier = Modifier
                        .weight(1.5f)
                        .fillMaxSize()
                        .background(colors.surface)
                        .padding(12.dp)
                ) {
                    Text(
                        text = selectedFile?.name.orEmpty(),
                        style = typography.section,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = fileContent,
                        style = typography.mono,
                        color = colors.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun FileTreeItem(
    node: ProjectFileNode,
    onFileClick: (File) -> Unit
) {
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (!node.isDirectory) {
                    onFileClick(node.file)
                }
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(if (node.isDirectory) R.drawable.ic_folder else R.drawable.ic_code),
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = node.name,
            style = typography.body,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DetailEnvTab(
    serviceId: String,
    viewModel: PocketServerViewModel
) {
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography
    val shapes = PocketTheme.shapes
    val context = LocalContext.current

    val envFile = remember(serviceId) {
        val f1 = File(context.filesDir, "sandboxes/$serviceId/.env")
        val f2 = File(context.filesDir, "projects/$serviceId/.env")
        if (f1.exists()) f1 else f2
    }

    val envEntries = remember(envFile) {
        mutableStateMapOf<String, String>().apply {
            if (envFile.exists()) {
                envFile.readLines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
                        val parts = trimmed.split("=", limit = 2)
                        put(parts[0].trim(), parts[1].trim())
                    }
                }
            }
        }
    }

    var isDirty by remember { mutableStateOf(false) }
    val maskedKeys = remember { mutableStateMapOf<String, Boolean>() }

    if (envEntries.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No environment variables configured",
                style = typography.body,
                color = colors.textTertiary
            )
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(envEntries.keys.toList()) { key ->
                    val value = envEntries[key].orEmpty()
                    val isSecret = key.contains("KEY", ignoreCase = true) ||
                            key.contains("SECRET", ignoreCase = true) ||
                            key.contains("PASSWORD", ignoreCase = true) ||
                            key.contains("TOKEN", ignoreCase = true)
                    val isRevealed = maskedKeys[key] == true

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = shapes.input,
                        color = colors.raised,
                        border = BorderStroke(1.dp, colors.border),
                        shadowElevation = 0.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = key,
                                style = typography.mono,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = if (isSecret && !isRevealed) "••••••••" else value,
                                style = typography.mono,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isSecret) {
                                Spacer(modifier = Modifier.width(6.dp))
                                IconButton(
                                    onClick = { maskedKeys[key] = !isRevealed },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            if (isRevealed) R.drawable.ic_visibility_off else R.drawable.ic_visibility
                                        ),
                                        contentDescription = if (isRevealed) "Hide secret" else "Reveal secret",
                                        tint = colors.textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Sticky Save and Restart Bar (Visible only when dirty)
            AnimatedVisibility(visible = isDirty) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.raised,
                    border = BorderStroke(1.dp, colors.border),
                    shadowElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Unsaved environment changes",
                            style = typography.body,
                            color = colors.textPrimary
                        )
                        PocketButton(
                            onClick = {
                                val content = envEntries.entries.joinToString("\n") { "${it.key}=${it.value}" }
                                envFile.writeText(content)
                                isDirty = false
                                viewModel.startService(serviceId)
                                Toast.makeText(context, "Saved & restarting...", Toast.LENGTH_SHORT).show()
                            },
                            variant = PocketButtonVariant.Primary,
                            size = PocketButtonSize.Compact,
                            text = "Save & restart"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailNetworkTab(
    service: com.pocketserver.ui.dashboard.ServiceCardModel,
    viewModel: PocketServerViewModel
) {
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography
    val shapes = PocketTheme.shapes

    val hasTunnel = !service.publicTunnelUrl.isNullOrBlank()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Network endpoints",
                style = typography.section,
                color = colors.textPrimary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = shapes.card,
                color = colors.raised,
                border = BorderStroke(1.dp, colors.border),
                shadowElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Assigned port",
                        style = typography.caption,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = service.assignedPort.toString(),
                        style = typography.mono,
                        color = colors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Local Wi-Fi endpoint",
                        style = typography.caption,
                        color = colors.textSecondary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = service.localUrl,
                        style = typography.mono,
                        color = colors.accent
                    )
                }
            }
        }

        // Cloudflare Tunnel Management
        item {
            Text(
                text = "Public tunnel (Cloudflare)",
                style = typography.section,
                color = colors.textPrimary,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = shapes.card,
                color = colors.raised,
                border = BorderStroke(1.dp, colors.border),
                shadowElevation = 0.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (hasTunnel) {
                        Text(
                            text = "Tunnel active",
                            style = typography.caption,
                            color = colors.running
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = service.publicTunnelUrl.orEmpty(),
                            style = typography.mono,
                            color = colors.accent
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        PocketButton(
                            onClick = { viewModel.stopTunnel(service.serviceId) },
                            variant = PocketButtonVariant.Secondary,
                            size = PocketButtonSize.Compact,
                            text = "Stop tunnel"
                        )
                    } else {
                        Text(
                            text = "Expose this local service securely to the public internet using a Cloudflare Quick Tunnel.",
                            style = typography.body,
                            color = colors.textSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        PocketButton(
                            onClick = { viewModel.startTunnel(service.serviceId, service.assignedPort) },
                            variant = PocketButtonVariant.Primary,
                            size = PocketButtonSize.Compact,
                            text = "Create quick tunnel"
                        )
                    }
                }
            }
        }
    }
}
