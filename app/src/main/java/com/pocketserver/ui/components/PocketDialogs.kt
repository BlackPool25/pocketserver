package com.pocketserver.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pocketserver.R
import com.pocketserver.ui.theme.PocketTheme

@Composable
fun PocketSnackbar(
    snackbarData: SnackbarData,
    modifier: Modifier = Modifier
) {
    val colors = PocketTheme.colors
    val shapes = PocketTheme.shapes
    val typography = PocketTheme.typography

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = shapes.button,
        color = colors.raised,
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = snackbarData.visuals.message,
                style = typography.body,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f)
            )
            val actionLabel = snackbarData.visuals.actionLabel
            if (actionLabel != null) {
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = { snackbarData.performAction() }
                ) {
                    Text(
                        text = actionLabel,
                        style = typography.bodyMedium,
                        color = colors.accent
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestructiveConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    cancelLabel: String = "Cancel",
    isDestructive: Boolean = true
) {
    val colors = PocketTheme.colors
    val shapes = PocketTheme.shapes
    val typography = PocketTheme.typography

    BasicAlertDialog(
        onDismissRequest = onDismiss
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = shapes.card,
            color = colors.raised,
            border = BorderStroke(1.dp, colors.border),
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                Text(
                    text = title,
                    style = typography.title,
                    color = colors.textPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = message,
                    style = typography.body,
                    color = colors.textSecondary
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                ) {
                    PocketButton(
                        onClick = onDismiss,
                        variant = PocketButtonVariant.Secondary,
                        size = PocketButtonSize.Compact,
                        text = cancelLabel
                    )
                    PocketButton(
                        onClick = onConfirm,
                        variant = if (isDestructive) PocketButtonVariant.Destructive else PocketButtonVariant.Primary,
                        size = PocketButtonSize.Compact,
                        text = confirmLabel
                    )
                }
            }
        }
    }
}

@Composable
fun ErrorState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    icon: Painter = painterResource(R.drawable.ic_error_outline)
) {
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = colors.error,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = title,
            style = typography.section,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = typography.body,
            color = colors.textSecondary,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(20.dp))
            PocketButton(
                onClick = onAction,
                variant = PocketButtonVariant.Primary,
                text = actionLabel
            )
        }
    }
}

@Preview(name = "Dark 1x", uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "Light 1x", uiMode = UI_MODE_NIGHT_NO)
@Preview(name = "Dark 2x", uiMode = UI_MODE_NIGHT_YES, fontScale = 2.0f)
@Preview(name = "Light 2x", uiMode = UI_MODE_NIGHT_NO, fontScale = 2.0f)
@Composable
private fun ErrorStatePreview() {
    PocketTheme {
        Box {
            ErrorState(
                title = "Unable to connect to service",
                message = "The process stopped unexpectedly. Check logs for details.",
                actionLabel = "Try again",
                onAction = {}
            )
        }
    }
}
