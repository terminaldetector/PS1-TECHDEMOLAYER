package com.metro.opticalshell.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Windows Phone 8 Metro, not Windows 11 (TZ 3.1).
 *
 * - absolute black AMOLED field;
 * - flat accent tiles, white high-contrast type;
 * - large geometry, minimal decoration, NO blur and no heavy effects;
 * - light type weights at large sizes, which is what made WP8 look like WP8.
 */
object MetroColors {
    val Black = Color(0xFF000000)
    val White = Color(0xFFFFFFFF)
    val Dim = Color(0xFFBFBFBF)
    val Divider = Color(0xFF2A2A2A)
    val TileFallback = Color(0xFF1F1F1F)

    /** The WP8 accent palette, used for tiles and the accent picker. */
    val Accents: List<Pair<String, Color>> = listOf(
        "Cobalt" to Color(0xFF2D89EF),
        "Green" to Color(0xFF00A300),
        "Orange" to Color(0xFFE3A21A),
        "Crimson" to Color(0xFFB91D47),
        "Violet" to Color(0xFF603CBA),
        "Teal" to Color(0xFF00ABA9),
        "Magenta" to Color(0xFFD80073),
        "Lime" to Color(0xFF8CBF26),
        "Steel" to Color(0xFF647687),
        "Mango" to Color(0xFFF09609),
    )
}

/** Shell-side scale, applied INSIDE the app only (TZ 4.1). */
data class ShellScale(
    val tile: Float = 1.0f,
    val typography: Float = 1.0f,
)

val LocalShellScale = staticCompositionLocalOf { ShellScale() }
val LocalAccent = staticCompositionLocalOf { MetroColors.Accents[0].second }

/** Metro type ramp. Light weights, tight tracking, scaled by the shell. */
fun metroTypography(scale: Float): Typography {
    fun sp(v: Float) = (v * scale).sp
    val light = FontWeight.Light
    val semi = FontWeight.Medium
    return Typography(
        displayLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = light,
            fontSize = sp(48f),
            lineHeight = sp(52f),
            color = MetroColors.White,
        ),
        headlineMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = light,
            fontSize = sp(28f),
            lineHeight = sp(32f),
            color = MetroColors.White,
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = semi,
            fontSize = sp(16f),
            color = MetroColors.White,
        ),
        bodyLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = sp(15f),
            color = MetroColors.White,
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = sp(13f),
            color = MetroColors.Dim,
        ),
        labelSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Normal,
            fontSize = sp(11f),
            color = MetroColors.White,
        ),
    )
}

/** Metro geometry. The gap between tiles is the WP8 signature. */
object MetroMetrics {
    /** One grid unit at scale 1.0. A MEDIUM tile is 2 units minus the gap. */
    val BaseUnitDp = 36.dp
    val TileGapDp = 6.dp
    val SurfacePaddingDp = 12.dp
    val HeaderHeightDp = 72.dp
}

@Composable
fun MetroTheme(
    accent: Color,
    scale: ShellScale,
    content: @Composable () -> Unit,
) {
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme() // Metro is always dark; read and ignored deliberately.

    val colors = darkColorScheme(
        primary = accent,
        background = MetroColors.Black,
        surface = MetroColors.Black,
        onPrimary = MetroColors.White,
        onBackground = MetroColors.White,
        onSurface = MetroColors.White,
    )

    CompositionLocalProvider(
        LocalShellScale provides scale,
        LocalAccent provides accent,
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = metroTypography(scale.typography),
            content = content,
        )
    }
}
