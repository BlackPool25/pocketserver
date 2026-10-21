package com.pocketserver.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pocketserver.ui.theme.PocketTheme

@Composable
fun shimmerBrush(
    targetValue: Float = 1000f,
    baseColor: Color = PocketTheme.colors.raised,
    highlightColor: Color = PocketTheme.colors.border
): Brush {
    val isInspection = LocalInspectionMode.current
    if (isInspection) {
        return Brush.linearGradient(listOf(baseColor, highlightColor))
    }
    val transition = rememberInfiniteTransition(label = "shimmerTransition")
    val translateAnimation by transition.animateFloat(
        initialValue = 0f,
        targetValue = targetValue,
        animationSpec = infiniteRepeatable(
            animation = tween<Float>(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerTranslate"
    )

    val offset = translateAnimation
    return Brush.linearGradient(
        colors = listOf(
            baseColor,
            highlightColor,
            baseColor
        ),
        start = Offset(offset - 200f, offset - 200f),
        end = Offset(offset, offset)
    )
}

@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp)
) {
    val brush = shimmerBrush()
    Box(
        modifier = modifier
            .clip(shape)
            .background(brush)
    )
}

@Composable
fun SkeletonServiceCard(
    modifier: Modifier = Modifier
) {
    val colors = PocketTheme.colors
    val shapes = PocketTheme.shapes

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shapes.card,
        color = colors.raised,
        border = BorderStroke(1.dp, colors.border),
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                SkeletonBox(modifier = Modifier.size(36.dp), shape = shapes.runtimeTile)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    SkeletonBox(modifier = Modifier.width(120.dp).height(18.dp), shape = RoundedCornerShape(4.dp))
                    Spacer(modifier = Modifier.height(6.dp))
                    SkeletonBox(modifier = Modifier.width(80.dp).height(12.dp), shape = RoundedCornerShape(4.dp))
                }
                SkeletonBox(modifier = Modifier.size(24.dp), shape = shapes.chip)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SkeletonBox(modifier = Modifier.width(72.dp).height(24.dp), shape = shapes.chip)
                SkeletonBox(modifier = Modifier.width(96.dp).height(16.dp), shape = RoundedCornerShape(4.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SkeletonBox(modifier = Modifier.weight(1f).height(44.dp), shape = shapes.button)
                SkeletonBox(modifier = Modifier.weight(1f).height(44.dp), shape = shapes.button)
            }
        }
    }
}

@Preview(name = "Dark 1x", uiMode = UI_MODE_NIGHT_YES)
@Preview(name = "Light 1x", uiMode = UI_MODE_NIGHT_NO)
@Preview(name = "Dark 2x", uiMode = UI_MODE_NIGHT_YES, fontScale = 2.0f)
@Preview(name = "Light 2x", uiMode = UI_MODE_NIGHT_NO, fontScale = 2.0f)
@Composable
private fun SkeletonServiceCardPreview() {
    PocketTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            SkeletonServiceCard()
        }
    }
}
