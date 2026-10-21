package com.pocketserver.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pocketserver.R
import com.pocketserver.ui.theme.PocketTheme

@Composable
fun TemplateRow(
    title: String,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: String? = null,
    trailingBadge: String? = "~5 s",
    showDivider: Boolean = true
) {
    val colors = PocketTheme.colors
    val typography = PocketTheme.typography

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 72.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = "Provision template $title",
                    onClick = onClick
                ),
            color = colors.background,
            shadowElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RuntimeTile(
                    glyph = glyph ?: title.take(2)
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        style = typography.section,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = description,
                        style = typography.body,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                if (!trailingBadge.isNullOrBlank()) {
                    Text(
                        text = trailingBadge,
                        style = typography.caption,
                        color = colors.textTertiary,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                thickness = 1.dp,
                color = colors.border
            )
        }
    }
}

@Preview(name = "Dark 1x", uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "Light 1x", uiMode = UI_MODE_NIGHT_NO)
@Preview(name = "Dark 2x", uiMode = UI_MODE_NIGHT_YES, fontScale = 2.0f)
@Preview(name = "Light 2x", uiMode = UI_MODE_NIGHT_NO, fontScale = 2.0f)
@Composable
private fun TemplateRowPreview() {
    PocketTheme {
        Box {
            TemplateRow(
                title = "FastAPI Hello World",
                description = "Lightweight Python REST API. Ready in 5 seconds.",
                glyph = "py",
                onClick = {}
            )
        }
    }
}
