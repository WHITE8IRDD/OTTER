package com.otter.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val OtterShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

object OtterDimens {
    val CardCorner = 20.dp
    val ButtonCorner = 16.dp
    val InputCorner = 16.dp
    val CardPadding = 16.dp
    val ScreenPadding = 20.dp
    val BetweenCards = 12.dp
    val BetweenSections = 24.dp
    val ListItemVertical = 14.dp
    val ButtonHeight = 52.dp
    val InkyBorder = 3.5.dp
}

object OtterSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val section = 40.dp
}
