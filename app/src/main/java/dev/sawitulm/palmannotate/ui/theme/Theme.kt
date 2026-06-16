package dev.sawitulm.palmannotate.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ─── PalmAnnotate colour tokens ───────────────────────────────────────────────
// Oil-palm green palette from css/style.css — must match the web version exactly.

object PalmColors {
    // Annotation class palette (LITERAL — do not tokenize, must match canvas)
    val B1 = Color(0xFF3B82F6)        // Blue — unripe
    val B2 = Color(0xFFEF4444)        // Red — under-ripe
    val B3 = Color(0xFFF59E0B)        // Amber — ripe
    val B4 = Color(0xFF8B5CF6)        // Purple — overripe
    val Unassigned = Color(0xFF9CA3AF) // Grey — not yet classified

    // Status
    val Emerald = Color(0xFF2DD47B)    // --c-emerald: success/confirmed
    val Red = Color(0xFFF06060)        // --c-red: error/danger
    val Warning = Color(0xFFE4B84A)    // --c-warn: warning/unassigned count
    val Gold = Color(0xFFE4B84A)       // --c-gold: highlight

    // Accent (lime chartreuse — dark text on accent buttons)
    val Accent = Color(0xFFB8E04A)     // --c-accent
    val OnAccent = Color(0xFF0C120C)   // --c-on-accent: dark text on lime

    // On-media (does NOT flip in light mode — controls over camera/photo)
    val OnMediaText = Color.White.copy(alpha = 0.9f)      // --c-on-media
    val OnMediaBorder = Color.White.copy(alpha = 0.35f)   // --c-on-media-border
    val Scrim = Color.Black.copy(alpha = 0.55f)           // --c-scrim
    val Glass = Color(0x0C120CB8)                          // --c-glass: rgba(12,18,12,.72)
    val GlassStrong = Color(0x0C120CEB)                    // --c-glass-strong
    val GlassSoft = Color(0x0C120C80)                      // --c-glass-soft
}

// ─── Dark theme: oil-palm green ──────────────────────────────────────────────
// Matches css/style.css :root dark values exactly.

private val DarkColorScheme = darkColorScheme(
    // Surfaces
    background = Color(0xFF0C120C),           // --c-bg
    surface = Color(0xFF141E14),              // --c-surface
    surfaceVariant = Color(0xFF1C2C1C),       // --c-surface-raised
    surfaceContainerHigh = Color(0xFF1C2C1C),
    surfaceContainer = Color(0xFF141E14),
    surfaceContainerLow = Color(0xFF0C120C),

    // Text
    onBackground = Color(0xFFF0F5F0),         // --c-text
    onSurface = Color(0xFFF0F5F0),            // --c-text
    onSurfaceVariant = Color(0xFFA3C4A3),     // --c-text-muted

    // Accent (lime chartreuse — dark text on accent)
    primary = Color(0xFFB8E04A),              // --c-accent
    onPrimary = Color(0xFF0C120C),            // --c-on-accent
    primaryContainer = Color(0xFF1C2C1C),
    onPrimaryContainer = Color(0xFFB8E04A),

    // Secondary
    secondary = Color(0xFF2DD47B),            // --c-emerald
    onSecondary = Color(0xFF0C120C),
    secondaryContainer = Color(0xFF141E14),
    onSecondaryContainer = Color(0xFF2DD47B),

    // Error
    error = Color(0xFFF06060),                // --c-red
    onError = Color.White,
    errorContainer = Color(0xFF2C1414),
    onErrorContainer = Color(0xFFF06060),

    // Borders
    outline = Color(0x14FFFFFF),              // --c-border: rgba(255,255,255,.08)
    outlineVariant = Color(0x2EFFFFFF),       // --c-border-hover: rgba(255,255,255,.18)

    // Inverse
    inverseSurface = Color(0xFFF0F5F0),
    inverseOnSurface = Color(0xFF0C120C),
    inversePrimary = Color(0xFF0C120C),

    // Tertiary (gold for highlights)
    tertiary = Color(0xFFE4B84A),             // --c-gold
    onTertiary = Color(0xFF0C120C),
    tertiaryContainer = Color(0xFF2C2C14),
    onTertiaryContainer = Color(0xFFE4B84A),
)

// ─── Light theme: oil-palm green light variant ──────────────────────────────
// From css/theme-light.css @media (prefers-color-scheme: light)

private val LightColorScheme = lightColorScheme(
    // Surfaces
    background = Color(0xFFF5F8F5),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE8F0E8),
    surfaceContainerHigh = Color(0xFFE0E8E0),
    surfaceContainer = Color(0xFFE8F0E8),
    surfaceContainerLow = Color(0xFFF5F8F5),

    // Text
    onBackground = Color(0xFF0C120C),
    onSurface = Color(0xFF0C120C),
    onSurfaceVariant = Color(0xFF4A6A4A),

    // Accent (darker green for light theme)
    primary = Color(0xFF059669),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF065F46),

    // Secondary
    secondary = Color(0xFF10B981),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF065F46),

    // Error
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B),

    // Borders
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFCBD5E1),

    // Inverse
    inverseSurface = Color(0xFF0C120C),
    inverseOnSurface = Color(0xFFF0F5F0),
    inversePrimary = Color(0xFFB8E04A),

    // Tertiary (gold)
    tertiary = Color(0xFFD97706),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF92400E),
)

@Composable
fun PalmAnnotateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,  // disabled: we control our palette
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) DarkColorScheme else LightColorScheme
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
