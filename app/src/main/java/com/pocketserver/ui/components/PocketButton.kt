package com.pocketserver.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pocketserver.R
import com.pocketserver.ui.theme.PocketTheme

enum class PocketButtonVariant {
    Primary,
    Secondary,
    Ghost,
    Destructive
}

enum class PocketButtonSize {
    Regular, // 48dp height
    Compact  // 36dp height (inline card actions)
}

@Composable
fun PocketButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: PocketButtonVariant = PocketButtonVariant.Primary,
    size: PocketButtonSize = PocketButtonSize.Regular,
    enabled: Boolean = true,
    loading: Boolean = false,
    leadingIcon: Painter? = null,
    trailingIcon: Painter? = null,
    text: String? = null,
    content: (@Composable RowScope.() -> Unit)? = null
) {
    val colors = PocketTheme.colors
    val shapes = PocketTheme.shapes
    val typography = PocketTheme.typography

    val containerColor = when (variant) {
        PocketButtonVariant.Primary -> colors.primaryButtonFill
        PocketButtonVariant.Secondary, PocketButtonVariant.Ghost, PocketButtonVariant.Destructive -> Color.Transparent
    }

    val contentColor = when (variant) {
        PocketButtonVariant.Primary -> colors.primaryButtonText
        PocketButtonVariant.Secondary -> colors.textPrimary
        PocketButtonVariant.Ghost -> colors.textSecondary
        PocketButtonVariant.Destructive -> colors.error
    }

    val disabledContainerColor = when (variant) {
        PocketButtonVariant.Primary -> colors.raised
        else -> Color.Transparent
    }

    val disabledContentColor = colors.textTertiary

    val border = when {
        !enabled -> if (variant == PocketButtonVariant.Secondary) BorderStroke(1.dp, colors.borderSubtle) else null
        variant == PocketButtonVariant.Secondary -> BorderStroke(1.dp, colors.border)
        variant == PocketButtonVariant.Destructive -> BorderStroke(1.dp, colors.error.copy(alpha = 0.4f))
        else -> null
    }

    val height = if (size == PocketButtonSize.Compact) 36.dp else 48.dp
    val horizontalPadding = if (size == PocketButtonSize.Compact) 12.dp else 20.dp

    Button(
        onClick = onClick,
        modifier = modifier
            .defaultMinSize(minHeight = height)
            .height(height),
        enabled = enabled && !loading,
        shape = shapes.button,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = disabledContainerColor,
            disabledContentColor = disabledContentColor
        ),
        border = border,
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            disabledElevation = 0.dp
        ),
        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 0.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = contentColor,
                strokeWidth = 2.dp
            )
        } else if (content != null) {
            content()
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leadingIcon != null) {
                    Icon(
                        painter = leadingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (text != null) {
                    Text(
                        text = text,
                        style = if (size == PocketButtonSize.Compact) typography.captionMedium else typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (trailingIcon != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        painter = trailingIcon,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
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
private fun PocketButtonPreview() {
    PocketTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            PocketButton(
                onClick = {},
                text = "Open service",
                leadingIcon = painterResource(R.drawable.ic_open_in_browser)
            )
        }
    }
}
