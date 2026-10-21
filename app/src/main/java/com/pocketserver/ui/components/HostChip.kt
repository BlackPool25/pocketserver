package com.pocketserver.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pocketserver.ui.theme.PocketTheme

@Composable
fun HostChip(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PocketTheme.colors
    val shapes = PocketTheme.shapes
    val typography = PocketTheme.typography

    val dotColor = if (isRunning) colors.running else colors.stopped
    val label = if (isRunning) "Host running" else "Host paused"

    Surface(
        modifier = modifier
            .height(28.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = "Navigate to system settings",
                onClick = onClick
            )
            .semantics {
                contentDescription = label
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
private fun HostChipPreview() {
    PocketTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            HostChip(isRunning = true, onClick = {})
        }
    }
}
