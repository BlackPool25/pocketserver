package com.pocketserver.ui.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pocketserver.R
import com.pocketserver.persistence.boot.PersistencePreferences
import com.pocketserver.persistence.vpn.PocketServerVpnService
import com.pocketserver.storage.doctor.StorageBreakdown
import com.pocketserver.ui.components.DestructiveConfirmDialog
import com.pocketserver.ui.components.PocketButton
import com.pocketserver.ui.components.PocketButtonSize
import com.pocketserver.ui.components.PocketButtonVariant
import com.pocketserver.ui.dashboard.PocketServerViewModel
import com.pocketserver.ui.theme.PocketTheme

@Composable
fun SystemScreen(
    viewModel: PocketServerViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography
    val shapes = PocketTheme.shapes

    val storageBreakdown by viewModel.storageBreakdown.collectAsState()

    var isVpnEnabled by remember { mutableStateOf(PersistencePreferences.isVpnShieldEnabled(context)) }
    var isWifiLockEnabled by remember { mutableStateOf(PersistencePreferences.isWifiLockEnabled(context)) }
    var isBootEnabled by remember { mutableStateOf(PersistencePreferences.isAutoStartOnBoot(context)) }

    var showStoragePurgeDialog by remember { mutableStateOf(false) }

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            PersistencePreferences.setVpnShieldEnabled(context, true)
            PocketServerVpnService.start(context)
            isVpnEnabled = true
            Toast.makeText(context, "Background protection activated", Toast.LENGTH_SHORT).show()
        } else {
            isVpnEnabled = false
            Toast.makeText(context, "VPN permission denied", Toast.LENGTH_SHORT).show()
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.title_system),
                        style = typography.display,
                        color = colors.textPrimary
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = colors.border)
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Section 1: Background Protection
            item {
                Text(
                    text = "Background protection",
                    style = typography.section,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
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
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_shield),
                                contentDescription = null,
                                tint = if (isVpnEnabled) colors.accent else colors.textTertiary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Loopback protection shield",
                                style = typography.bodyMedium,
                                color = colors.textPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = isVpnEnabled,
                                onCheckedChange = { enable ->
                                    if (enable) {
                                        val prepareIntent = VpnService.prepare(context)
                                        if (prepareIntent != null) {
                                            vpnConsentLauncher.launch(prepareIntent)
                                        } else {
                                            PersistencePreferences.setVpnShieldEnabled(context, true)
                                            PocketServerVpnService.start(context)
                                            isVpnEnabled = true
                                            Toast.makeText(context, "Background protection activated", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        PersistencePreferences.setVpnShieldEnabled(context, false)
                                        PocketServerVpnService.stop(context)
                                        isVpnEnabled = false
                                        Toast.makeText(context, "Background protection disabled", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Keeps services alive when the screen is off using a local VPN tunnel. You will be asked to grant VPN permission when turning this on.",
                            style = typography.caption,
                            color = colors.textSecondary
                        )
                    }
                }
            }

            // Section 2: Storage Doctor
            item {
                Text(
                    text = "Storage",
                    style = typography.section,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = shapes.card,
                    color = colors.raised,
                    border = BorderStroke(1.dp, colors.border),
                    shadowElevation = 0.dp
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val breakdown = storageBreakdown
                        if (breakdown != null) {
                            val total = breakdown.totalAppUsedBytes.coerceAtLeast(1L).toFloat()
                            val runtimesRatio = (breakdown.installedRuntimesBytes / total).coerceIn(0f, 1f)
                            val codeRatio = (breakdown.projectCodeBytes / total).coerceIn(0f, 1f)
                            val cacheRatio = (breakdown.reclaimableCacheBytes / total).coerceIn(0f, 1f)

                            // Stacked Storage Bar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(colors.border)
                            ) {
                                if (runtimesRatio > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .weight(runtimesRatio)
                                            .background(colors.accent)
                                    )
                                }
                                if (codeRatio > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .weight(codeRatio)
                                            .background(colors.running)
                                    )
                                }
                                if (cacheRatio > 0f) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .weight(cacheRatio)
                                            .background(colors.warning)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Storage breakdown rows
                            StorageLegendRow(
                                label = "Installed runtimes",
                                value = breakdown.installedRuntimesFormatted,
                                color = colors.accent
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            StorageLegendRow(
                                label = "Project code & sandboxes",
                                value = breakdown.projectCodeFormatted,
                                color = colors.running
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            StorageLegendRow(
                                label = "Reclaimable compiler caches",
                                value = breakdown.reclaimableCacheFormatted,
                                color = colors.warning
                            )

                            if (breakdown.reclaimableCacheBytes > 0L) {
                                Spacer(modifier = Modifier.height(18.dp))
                                PocketButton(
                                    onClick = { showStoragePurgeDialog = true },
                                    variant = PocketButtonVariant.Secondary,
                                    size = PocketButtonSize.Compact,
                                    text = "Free ${breakdown.reclaimableCacheFormatted}",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        } else {
                            Text(
                                text = "Analyzing disk usage…",
                                style = typography.body,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
            }

            // Section 3: Network & Reliability
            item {
                Text(
                    text = "Power & network reliability",
                    style = typography.section,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
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
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Wi-Fi high-performance lock", style = typography.bodyMedium, color = colors.textPrimary)
                                Text("Prevents mobile radio sleep during active server traffic", style = typography.caption, color = colors.textSecondary)
                            }
                            Switch(
                                checked = isWifiLockEnabled,
                                onCheckedChange = {
                                    isWifiLockEnabled = it
                                    PersistencePreferences.setWifiLockEnabled(context, it)
                                }
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-start on boot", style = typography.bodyMedium, color = colors.textPrimary)
                                Text("Restores active services automatically after device restarts", style = typography.caption, color = colors.textSecondary)
                            }
                            Switch(
                                checked = isBootEnabled,
                                onCheckedChange = {
                                    isBootEnabled = it
                                    PersistencePreferences.setAutoStartOnBoot(context, it)
                                }
                            )
                        }
                    }
                }
            }

            // Section 4: About
            item {
                Text(
                    text = "About",
                    style = typography.section,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
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
                            Text("Version", style = typography.body, color = colors.textSecondary)
                            Text("1.0.0 (Build 1)", style = typography.mono, color = colors.textPrimary)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Architecture", style = typography.body, color = colors.textSecondary)
                            Text("Linux aarch64 hermetic", style = typography.mono, color = colors.textPrimary)
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        PocketButton(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/pocketserver/pocketserver/issues"))
                                context.startActivity(intent)
                            },
                            variant = PocketButtonVariant.Ghost,
                            size = PocketButtonSize.Compact,
                            text = "Report an issue on GitHub"
                        )
                    }
                }
            }
        }
    }

    if (showStoragePurgeDialog) {
        DestructiveConfirmDialog(
            title = "Clean compiler caches?",
            message = "This will safely prune dangling cargo crate sources, python wheel caches, and build intermediates. Your user code and installed packages are guaranteed safe.",
            confirmLabel = "Clean caches",
            onConfirm = {
                showStoragePurgeDialog = false
                viewModel.cleanStorageDoctorCaches()
                Toast.makeText(context, "Storage caches cleaned cleanly", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showStoragePurgeDialog = false }
        )
    }
}

@Composable
private fun StorageLegendRow(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    val typography = PocketTheme.typography
    val colors = PocketTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = typography.caption,
            color = colors.textSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = typography.mono,
            color = colors.textPrimary
        )
    }
}
