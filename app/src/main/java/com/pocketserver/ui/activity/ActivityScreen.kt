package com.pocketserver.ui.activity

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketserver.R
import com.pocketserver.ui.components.ErrorState
import com.pocketserver.ui.dashboard.ActivityEventRecord
import com.pocketserver.ui.dashboard.PocketServerViewModel
import com.pocketserver.ui.theme.PocketTheme

@Composable
fun ActivityScreen(
    viewModel: PocketServerViewModel,
    onNavigateToServices: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography

    val events by viewModel.activityEvents.collectAsState()

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
                        text = stringResource(R.string.title_activity),
                        style = typography.display,
                        color = colors.textPrimary
                    )
                }
                HorizontalDivider(thickness = 1.dp, color = colors.border)
            }
        }
    ) { innerPadding ->
        if (events.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                ErrorState(
                    title = "No activity recorded yet",
                    message = "Service lifecycle events, sleep/wake transitions, and deployments will appear here.",
                    actionLabel = "View services",
                    onAction = onNavigateToServices,
                    icon = painterResource(R.drawable.ic_history)
                )
            }
        } else {
            val now = remember { System.currentTimeMillis() }
            val groupedEvents = remember(events) {
                val oneDayMs = 24 * 60 * 60 * 1000L
                val twoDaysMs = 2 * oneDayMs
                val oneWeekMs = 7 * oneDayMs

                val groups = mutableMapOf<String, MutableList<ActivityEventRecord>>()
                for (event in events) {
                    val delta = (now - event.timestampMs).coerceAtLeast(0L)
                    val groupTitle = when {
                        delta < oneDayMs -> "Today"
                        delta < twoDaysMs -> "Yesterday"
                        delta < oneWeekMs -> "Earlier this week"
                        else -> "Older"
                    }
                    groups.getOrPut(groupTitle) { mutableListOf() }.add(event)
                }
                groups
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for ((groupTitle, groupItems) in groupedEvents) {
                    item(key = "header_$groupTitle") {
                        Text(
                            text = groupTitle,
                            style = typography.caption,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }

                    items(groupItems, key = { it.id }) { item ->
                        ActivityEventRow(
                            event = item,
                            onItemClick = {
                                item.serviceId?.let { id -> onNavigateToDetail(id) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityEventRow(
    event: ActivityEventRecord,
    onItemClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography
    val shapes = PocketTheme.shapes

    val (iconRes, iconTint, bgTint) = when (event.type) {
        "STARTED", "WOKE" -> Triple(R.drawable.ic_play_arrow, colors.running, colors.running.copy(alpha = 0.12f))
        "SLEPT" -> Triple(R.drawable.ic_bolt, colors.sleeping, colors.sleeping.copy(alpha = 0.12f))
        "STOPPED" -> Triple(R.drawable.ic_stop, colors.stopped, colors.stopped.copy(alpha = 0.12f))
        "DELETED" -> Triple(R.drawable.ic_delete_outline, colors.error, colors.error.copy(alpha = 0.12f))
        "TUNNEL" -> Triple(R.drawable.ic_wifi, colors.accent, colors.accent.copy(alpha = 0.12f))
        "INSTALLED" -> Triple(R.drawable.ic_package_2, colors.accent, colors.accent.copy(alpha = 0.12f))
        "NETWORK" -> Triple(R.drawable.ic_refresh, colors.warning, colors.warning.copy(alpha = 0.12f))
        else -> Triple(R.drawable.ic_info_outline, colors.textSecondary, colors.surface)
    }

    val isClickable = event.serviceId != null

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.card)
            .then(if (isClickable) Modifier.clickable(onClick = onItemClick) else Modifier),
        shape = shapes.card,
        color = colors.raised,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(bgTint),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = typography.body,
                    fontWeight = FontWeight.Medium,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = event.description,
                    style = typography.caption,
                    color = colors.textSecondary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = formatRelativeTime(event.timestampMs),
                style = typography.caption,
                color = colors.textTertiary
            )
        }
    }
}

private fun formatRelativeTime(timestampMs: Long): String {
    val deltaMs = (System.currentTimeMillis() - timestampMs).coerceAtLeast(0L)
    val seconds = deltaMs / 1000L
    val minutes = seconds / 60L
    val hours = minutes / 60L
    val days = hours / 24L

    return when {
        seconds < 60 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> "${days / 7}w ago"
    }
}
