package dev.sawitulm.palmannotate.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * On-media control colours — stay light-on-dark in BOTH themes.
 * Per §17.2: controls layered over camera/photo surface must NOT flip in light mode.
 * Using regular onSurface there is a bug (vanishes over dark camera).
 */
object OnMediaColors {
    val Text = Color.White.copy(alpha = 0.9f)
    val TextDim = Color.White.copy(alpha = 0.65f)
    val Border = Color.White.copy(alpha = 0.35f)
    val Scrim = Color.Black.copy(alpha = 0.55f)
    val Surface = Color(red = 7, green = 18, blue = 7, alpha = 235)
    val Card = Color(red = 12, green = 24, blue = 12, alpha = 200)
}

val LocalOnMediaColors = staticCompositionLocalOf { OnMediaColors }
