package com.pocketserver.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pocketserver.R
import com.pocketserver.ui.theme.PocketTheme

@Composable
fun StatusStrip(
    isProtectionActive: Boolean,
    modifier: Modifier = Modifier,
    telemetryHint: String? = null,
    onClick: (() -> Unit)? = null
) {
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography

    val shieldTint = if (isProtectionActive) colors.accent else colors.textTertiary
    val statusText = if (isProtectionActive) {
        "Background protection on"
    } else {
        "Background protection off"
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else Modifier
                ),
            color = colors.surface,
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_shield),
                    contentDescription = null,
                    tint = shieldTint,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = statusText,
                    style = typography.body,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                if (!telemetryHint.isNullOrBlank()) {
                    Text(
                        text = telemetryHint,
                        style = typography.caption,
                        color = colors.textSecondary,
                        maxLines = 1
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.align(Alignment.BottomCenter),
            thickness = 1.dp,
            color = colors.border
        )
    }
}

@Preview(name = "Dark 1x", uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "Light 1x", uiMode = UI_MODE_NIGHT_NO)
@Preview(name = "Dark 2x", uiMode = UI_MODE_NIGHT_YES, fontScale = 2.0f)
@Preview(name = "Light 2x", uiMode = UI_MODE_NIGHT_NO, fontScale = 2.0f)
@Composable
private fun StatusStripPreview() {
    PocketTheme {
        StatusStrip(
            isProtectionActive = true,
            telemetryHint = "Battery impact <1%"
        )
    }
}
