package com.pocketserver.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

@Immutable
data class PocketShapes(
    val card: Shape = RoundedCornerShape(14.dp),
    val button: Shape = RoundedCornerShape(10.dp),
    val input: Shape = RoundedCornerShape(12.dp),
    val chip: Shape = CircleShape,
    val runtimeTile: Shape = RoundedCornerShape(10.dp),
    val badge: Shape = RoundedCornerShape(6.dp),
    val bottomSheet: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
)

val LocalPocketShapes = staticCompositionLocalOf { PocketShapes() }

val MaterialShapes = Shapes(
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
