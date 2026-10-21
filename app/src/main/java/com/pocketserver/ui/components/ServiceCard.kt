package com.pocketserver.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pocketserver.R
import com.pocketserver.ui.dashboard.ServiceCardModel
import com.pocketserver.ui.theme.PocketTheme

@Composable
fun ServiceCard(
    service: ServiceCardModel,
    onCardClick: () -> Unit,
    onOpenClick: () -> Unit,
    onToggleStateClick: () -> Unit,
    onShareQrClick: () -> Unit,
    onCopyUrlClick: () -> Unit,
    onRestartClick: () -> Unit,
    onLogsClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PocketTheme.colors
    val shapes = PocketTheme.shapes
    val typography = PocketTheme.typography

    val statusState = ServiceStatusState.from(service.status)
    val isRunning = statusState == ServiceStatusState.Running
    val isSleeping = statusState == ServiceStatusState.Sleeping

    var showMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = "Open ${service.name} details",
                onClick = onCardClick
            ),
        shape = shapes.card,
        color = colors.raised,
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Row 1: Runtime Tile + Name + Monorepo Badge + Overflow Menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RuntimeTile(
                    glyph = service.framework.take(2)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = service.name,
                        style = typography.section,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = service.framework,
                        style = typography.caption,
                        color = if (isSleeping) colors.textTertiary else colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!service.monorepoBadge.isNullOrBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = service.monorepoBadge,
                        style = typography.caption,
                        color = colors.accent,
                        maxLines = 1
                    )
                }
                Box {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert),
                            contentDescription = "More options for ${service.name}",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Restart", style = typography.body) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_restart_alt),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onRestartClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Logs", style = typography.body) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_terminal),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onLogsClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete service", style = typography.body, color = colors.error) },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete_outline),
                                    contentDescription = null,
                                    tint = colors.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showMenu = false
                                onDeleteClick()
                            }
                        )
                    }
                }
            }

            // Row 2: StatusChip + Port Mono + RAM (only if real data > 0)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusChip(status = statusState)
                Text(
                    text = "port ${service.assignedPort}",
                    style = typography.mono,
                    color = if (isSleeping) colors.textTertiary else colors.textSecondary
                )
                if (isRunning && service.ramUsageMb > 0L) {
                    Text(
                        text = "•",
                        style = typography.caption,
                        color = colors.textTertiary
                    )
                    Text(
                        text = "${service.ramUsageMb} MB",
                        style = typography.caption,
                        color = colors.textSecondary
                    )
                }
            }

            // Row 3: [Running Only] URL row with Mono Text + Copy + QR buttons
            if (isRunning) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = shapes.input,
                    color = colors.surface,
                    border = BorderStroke(1.dp, colors.borderSubtle),
                    shadowElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = service.localUrl,
                            style = typography.mono,
                            color = colors.accent,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(
                            onClick = onCopyUrlClick,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_content_copy),
                                contentDescription = "Copy local URL",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(
                            onClick = onShareQrClick,
                            modifier = Modifier.size(32.dp)
                        ) {
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

            // Row 4: Action row: Open (Primary) + Start / Stop / Wake / Sleep (Secondary)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (isRunning) {
                    PocketButton(
                        onClick = onOpenClick,
                        variant = PocketButtonVariant.Primary,
                        size = PocketButtonSize.Compact,
                        text = "Open",
                        leadingIcon = painterResource(R.drawable.ic_open_in_browser),
                        modifier = Modifier.weight(1f)
                    )
                    PocketButton(
                        onClick = onToggleStateClick,
                        variant = PocketButtonVariant.Secondary,
                        size = PocketButtonSize.Compact,
                        text = "Sleep",
                        leadingIcon = painterResource(R.drawable.ic_stop),
                        modifier = Modifier.weight(1f)
                    )
                } else if (isSleeping) {
                    PocketButton(
                        onClick = onToggleStateClick,
                        variant = PocketButtonVariant.Primary,
                        size = PocketButtonSize.Compact,
                        text = "Wake",
                        leadingIcon = painterResource(R.drawable.ic_play_arrow),
                        modifier = Modifier.weight(1f)
                    )
                    PocketButton(
                        onClick = onLogsClick,
                        variant = PocketButtonVariant.Secondary,
                        size = PocketButtonSize.Compact,
                        text = "Logs",
                        leadingIcon = painterResource(R.drawable.ic_terminal),
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    PocketButton(
                        onClick = onToggleStateClick,
                        variant = PocketButtonVariant.Primary,
                        size = PocketButtonSize.Compact,
                        text = "Start",
                        leadingIcon = painterResource(R.drawable.ic_play_arrow),
                        modifier = Modifier.weight(1f)
                    )
                    PocketButton(
                        onClick = onLogsClick,
                        variant = PocketButtonVariant.Secondary,
                        size = PocketButtonSize.Compact,
                        text = "Logs",
                        leadingIcon = painterResource(R.drawable.ic_terminal),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Preview(name = "Dark 1x", uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "Light 1x", uiMode = UI_MODE_NIGHT_NO)
@Preview(name = "Dark 2x", uiMode = UI_MODE_NIGHT_YES, fontScale = 2.0f)
@Preview(name = "Light 2x", uiMode = UI_MODE_NIGHT_NO, fontScale = 2.0f)
@Composable
private fun ServiceCardPreview() {
    PocketTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            ServiceCard(
                service = ServiceCardModel(
                    serviceId = "svc-1",
                    name = "FastAPI Service",
                    framework = "Python FastAPI",
                    assignedPort = 8000,
                    localUrl = "http://192.168.1.105:8000",
                    mDnsUrl = "fastapi.local",
                    status = "LIVE",
                    ramUsageMb = 34L
                ),
                onCardClick = {},
                onOpenClick = {},
                onToggleStateClick = {},
                onShareQrClick = {},
                onCopyUrlClick = {},
                onRestartClick = {},
                onLogsClick = {},
                onDeleteClick = {}
            )
        }
    }
}
