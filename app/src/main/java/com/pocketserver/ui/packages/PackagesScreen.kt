package com.pocketserver.ui.packages

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketserver.R
import com.pocketserver.hub.download.InstallStage
import com.pocketserver.hub.search.PackageTier
import com.pocketserver.hub.search.UnifiedPackageRecord
import com.pocketserver.ui.components.ErrorState
import com.pocketserver.ui.components.PocketButton
import com.pocketserver.ui.components.PocketButtonSize
import com.pocketserver.ui.components.PocketButtonVariant
import com.pocketserver.ui.components.RuntimeTile
import com.pocketserver.ui.dashboard.PocketServerViewModel
import com.pocketserver.ui.theme.PocketTheme
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagesScreen(
    viewModel: PocketServerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography
    val shapes = PocketTheme.shapes

    val searchResults by viewModel.packageSearchResults.collectAsState()
    val installProgressMap by viewModel.installProgress.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var selectedTier by remember { mutableStateOf<PackageTier?>(null) }
    var activeDetailPackage by remember { mutableStateOf<UnifiedPackageRecord?>(null) }

    // Initial search and debounced search
    LaunchedEffect(Unit) {
        viewModel.searchPackages("", null)
    }

    LaunchedEffect(searchQuery, selectedTier) {
        delay(300)
        viewModel.searchPackages(searchQuery, selectedTier)
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
                // Header Title
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.title_packages),
                        style = typography.display,
                        color = colors.textPrimary
                    )
                }

                // Sticky Search Field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = "Search runtimes, databases, tools",
                            style = typography.body,
                            color = colors.textTertiary
                        )
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "Clear search",
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = shapes.input,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.border,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary
                    ),
                    singleLine = true
                )

                // Filter Chips Row
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = selectedTier == null,
                            onClick = { selectedTier = null },
                            label = { Text("All", style = typography.caption) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedTier == PackageTier.CORE,
                            onClick = { selectedTier = if (selectedTier == PackageTier.CORE) null else PackageTier.CORE },
                            label = { Text("Curated core", style = typography.caption) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedTier == PackageTier.ALPINE,
                            onClick = { selectedTier = if (selectedTier == PackageTier.ALPINE) null else PackageTier.ALPINE },
                            label = { Text("Alpine Linux", style = typography.caption) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedTier == PackageTier.CONDA,
                            onClick = { selectedTier = if (selectedTier == PackageTier.CONDA) null else PackageTier.CONDA },
                            label = { Text("Conda-Forge", style = typography.caption) }
                        )
                    }
                    item {
                        FilterChip(
                            selected = selectedTier == PackageTier.POCKETFORGE,
                            onClick = { selectedTier = if (selectedTier == PackageTier.POCKETFORGE) null else PackageTier.POCKETFORGE },
                            label = { Text("Community", style = typography.caption) }
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
            if (searchResults.isEmpty()) {
                if (searchQuery.isNotEmpty()) {
                    ErrorState(
                        title = "No results found",
                        message = "No packages matching \"$searchQuery\". Try a broader term.",
                        icon = painterResource(R.drawable.ic_search)
                    )
                } else {
                    ErrorState(
                        title = "Package catalog",
                        message = "Search over 70,000+ runtimes, libraries, and tools.",
                        icon = painterResource(R.drawable.ic_package_2)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(searchResults, key = { it.id }) { pkg ->
                        PackageCardRow(
                            pkg = pkg,
                            onClick = { activeDetailPackage = pkg },
                            onInstall = {
                                viewModel.installPackage(pkg)
                                Toast.makeText(context, "Installing ${pkg.name}...", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }

    // Package Detail Modal Bottom Sheet
    activeDetailPackage?.let { pkg ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val progress = installProgressMap[pkg.id]

        ModalBottomSheet(
            onDismissRequest = { activeDetailPackage = null },
            sheetState = sheetState,
            containerColor = colors.raised,
            shape = shapes.bottomSheet
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RuntimeTile(glyph = pkg.name.take(2))
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = pkg.name,
                            style = typography.title,
                            color = colors.textPrimary
                        )
                        Text(
                            text = "version ${pkg.version}",
                            style = typography.caption,
                            color = colors.textSecondary
                        )
                    }
                    Surface(
                        shape = shapes.badge,
                        color = colors.surface,
                        border = BorderStroke(1.dp, colors.borderSubtle),
                        shadowElevation = 0.dp
                    ) {
                        Text(
                            text = pkg.tier.displayName,
                            style = typography.caption,
                            color = colors.accent,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = pkg.description,
                    style = typography.body,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Metadata Rows
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = shapes.input,
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.borderSubtle),
                    shadowElevation = 0.dp
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Download size", style = typography.body, color = colors.textSecondary)
                            Text(
                                text = "${String.format("%.1f", pkg.sizeCompressedMb)} MB",
                                style = typography.mono,
                                color = colors.textPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Installed footprint", style = typography.body, color = colors.textSecondary)
                            Text(
                                text = "${String.format("%.1f", pkg.installedSizeMb)} MB",
                                style = typography.mono,
                                color = colors.textPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Security signature", style = typography.body, color = colors.textSecondary)
                            Text(
                                text = if (pkg.sha256Checksum.isNotBlank()) "Verified SHA-256" else "Unverified",
                                style = typography.caption,
                                color = if (pkg.sha256Checksum.isNotBlank()) colors.running else colors.warning
                            )
                        }
                    }
                }

                // Dependencies List
                if (pkg.dependencies.isNotEmpty()) {
                    var isExpanded by remember { mutableStateOf(false) }
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isExpanded = !isExpanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Dependencies (${pkg.dependencies.size})",
                            style = typography.caption,
                            color = colors.textSecondary
                        )
                        Icon(
                            painter = painterResource(
                                if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
                            ),
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    AnimatedVisibility(visible = isExpanded) {
                        Text(
                            text = pkg.dependencies.joinToString(", "),
                            style = typography.mono,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Install progress or button
                if (progress != null && progress.stage != InstallStage.IDLE && progress.stage != InstallStage.COMPLETED) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val stageText = when (progress.stage) {
                                InstallStage.DOWNLOADING -> "Downloading…"
                                InstallStage.VERIFYING -> "Verifying…"
                                InstallStage.EXTRACTING -> "Extracting…"
                                InstallStage.LINKING -> "Linking…"
                                InstallStage.FAILED -> "Failed"
                                else -> "Processing…"
                            }
                            Text(stageText, style = typography.bodyMedium, color = colors.textPrimary)
                            Text("${progress.progressPercent}%", style = typography.mono, color = colors.textSecondary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress.progressPercent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = colors.accent,
                            trackColor = colors.surface
                        )
                    }
                } else if (progress?.stage == InstallStage.COMPLETED || pkg.isInstalled) {
                    PocketButton(
                        onClick = { activeDetailPackage = null },
                        variant = PocketButtonVariant.Secondary,
                        text = "Installed",
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    PocketButton(
                        onClick = {
                            viewModel.installPackage(pkg)
                        },
                        variant = PocketButtonVariant.Primary,
                        text = "Install package",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun PackageCardRow(
    pkg: UnifiedPackageRecord,
    onClick: () -> Unit,
    onInstall: () -> Unit
) {
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography
    val shapes = PocketTheme.shapes

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = shapes.card,
        color = colors.raised,
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RuntimeTile(glyph = pkg.name.take(2))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pkg.name,
                        style = typography.section,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = pkg.tierBadge,
                        style = typography.caption,
                        color = colors.accent
                    )
                }
                Text(
                    text = "v${pkg.version} • ${String.format("%.1f", pkg.sizeCompressedMb)} MB",
                    style = typography.caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (pkg.isInstalled) {
                PocketButton(
                    onClick = onClick,
                    variant = PocketButtonVariant.Secondary,
                    size = PocketButtonSize.Compact,
                    text = "Installed"
                )
            } else {
                PocketButton(
                    onClick = onInstall,
                    variant = PocketButtonVariant.Primary,
                    size = PocketButtonSize.Compact,
                    text = "Install"
                )
            }
        }
    }
}
