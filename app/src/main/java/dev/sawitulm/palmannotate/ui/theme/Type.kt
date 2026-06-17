package dev.sawitulm.palmannotate.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

// ─── PalmAnnotate type scale ──────────────────────────────────────────────────
// Product register: ONE family (Roboto/system — preserves the web-app identity),
// a fixed sp scale (no fluid clamp on a fixed-DPI field tablet), ~1.2 ratio, and a
// legible floor. Screens reference MaterialTheme.typography.* instead of ad-hoc
// `fontSize = N.sp` literals, so sizing stays consistent and is tuned in one place.
//
// Legibility floor is 12sp: this app is read by gloved operators outdoors, so the
// old 8–11sp labels (dedup signal badges, depth read-outs) are raised here.

private val Sans = FontFamily.Default

// Trim leading/trailing line padding so dense rows align tightly.
private val tightLineHeight = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
) = TextStyle(
    fontFamily = Sans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = tightLineHeight,
)

val PalmTypography = Typography(
    // Display — large hero numerals (Results stat emphasis).
    displaySmall = style(34, 42, FontWeight.Bold),

    // Headline — section figures / big counts.
    headlineMedium = style(26, 32, FontWeight.Bold),
    headlineSmall = style(22, 28, FontWeight.Bold),

    // Title — screen + card headings.
    titleLarge = style(20, 26, FontWeight.SemiBold),
    titleMedium = style(17, 24, FontWeight.SemiBold),
    titleSmall = style(15, 20, FontWeight.Medium),

    // Body — prose + descriptions.
    bodyLarge = style(16, 24),
    bodyMedium = style(14, 20),
    bodySmall = style(13, 18),

    // Label — buttons, chips, captions, badges (12sp legibility floor).
    labelLarge = style(15, 20, FontWeight.Medium),
    labelMedium = style(13, 18, FontWeight.Medium),
    labelSmall = style(12, 16, FontWeight.Medium),
)
