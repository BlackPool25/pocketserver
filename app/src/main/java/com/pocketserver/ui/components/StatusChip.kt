package com.pocketserver.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pocketserver.ui.theme.PocketTheme

enum class ServiceStatusState {
    Running,
    Sleeping,
    Starting,
    Warning,
    Error,
    Stopped;

    companion object {
        fun from(raw: String?): ServiceStatusState {
            return when (raw?.uppercase()) {
                "LIVE", "RUNNING", "ACTIVE" -> Running
                "SLEEPING", "FROZEN" -> Sleeping
                "STARTING", "WAKING" -> Starting
                "WARNING", "ATTENTION", "THROTTLED" -> Warning
                "ERROR", "FAILED" -> Error
                else -> Stopped
            }
        }
    }
}

@Composable
fun StatusChip(
    status: ServiceStatusState,
    modifier: Modifier = Modifier,
    customLabel: String? = null
) {
    val colors = PocketTheme.colors
    val shapes = PocketTheme.shapes
    val typography = PocketTheme.typography
    val isInspection = LocalInspectionMode.current

    val (dotColor, defaultLabel) = when (status) {
        ServiceStatusState.Running -> colors.running to "Running"
        ServiceStatusState.Sleeping -> colors.sleeping to "Sleeping"
        ServiceStatusState.Starting -> colors.starting to "Starting"
        ServiceStatusState.Warning -> colors.warning to "Warning"
        ServiceStatusState.Error -> colors.error to "Error"
        ServiceStatusState.Stopped -> colors.stopped to "Stopped"
    }

    val label = customLabel ?: defaultLabel

    val dotAlpha = if (status == ServiceStatusState.Starting && !isInspection) {
        val transition = rememberInfiniteTransition(label = "startingPulse")
        val alpha by transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )
        alpha
    } else {
        1.0f
    }

    Surface(
        modifier = modifier
            .height(28.dp)
            .semantics {
                contentDescription = "Status: $label"
            },
        shape = shapes.chip,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(dotAlpha)
                    .background(color = dotColor, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = typography.caption,
                color = colors.textPrimary,
                maxLines = 1
            )
        }
    }
}

@Preview(name = "Dark 1x", uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "Light 1x", uiMode = UI_MODE_NIGHT_NO)
@Preview(name = "Dark 2x", uiMode = UI_MODE_NIGHT_YES, fontScale = 2.0f)
@Preview(name = "Light 2x", uiMode = UI_MODE_NIGHT_NO, fontScale = 2.0f)
@Composable
private fun StatusChipPreview() {
    PocketTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            StatusChip(status = ServiceStatusState.Running)
        }
    }
}
