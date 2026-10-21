package com.pocketserver.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketserver.R
import com.pocketserver.ui.theme.GeistSans
import com.pocketserver.ui.theme.PocketTheme

@Composable
fun RuntimeTile(
    modifier: Modifier = Modifier,
    glyph: String? = null,
    icon: Painter? = null
) {
    val colors = PocketTheme.colors
    val shapes = PocketTheme.shapes

    Box(
        modifier = modifier
            .size(36.dp)
            .background(colors.raised, shapes.runtimeTile)
            .border(BorderStroke(1.dp, colors.border), shapes.runtimeTile),
        contentAlignment = Alignment.Center
    ) {
        when {
            icon != null -> {
                Icon(
                    painter = icon,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            !glyph.isNullOrBlank() -> {
                Text(
                    text = glyph.take(2).lowercase(),
                    fontFamily = GeistSans,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = colors.textSecondary
                )
            }
            else -> {
                Icon(
                    painter = painterResource(R.drawable.ic_code),
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
