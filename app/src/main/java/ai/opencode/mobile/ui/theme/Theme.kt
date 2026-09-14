package ai.opencode.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/*
 * GNOME / libadwaita palette, mapped onto Material 3 roles.
 * Named colours follow the GNOME HIG palette and the libadwaita named colours
 * (window_bg_color, view_bg_color, headerbar_bg_color, accent_bg_color, ...).
 */
private object Adwaita {
    val Blue2 = Color(0xFF62A0EA)
    val Blue3 = Color(0xFF3584E4) // accent_bg_color
    val Blue4 = Color(0xFF1C71D8)
    val Blue5 = Color(0xFF1A5FB4)
    val Green5 = Color(0xFF26A269) // success_bg_color
    val GreenBright = Color(0xFF78E9AB) // success_color (dark)
    val Yellow5 = Color(0xFFE5A50A) // warning_bg_color
    val Yellow2 = Color(0xFFF8E45C)
    val Red3 = Color(0xFFE01B24) // destructive_bg_color (light)
    val Red4 = Color(0xFFC01C28) // destructive_bg_color (dark)
    val RedBright = Color(0xFFFF938C) // destructive_color (dark)

    // Neutrals
    val Light1 = Color(0xFFFFFFFF)
    val Light2 = Color(0xFFF6F5F4)
    val Light3 = Color(0xFFDEDDDA)
    val Light4 = Color(0xFFC0BFBC)
    val Dark3 = Color(0xFF3D3846)
    val Dark5 = Color(0xFF241F31)
}

/**
 * Semantic accents libadwaita has but Material 3 does not model. Kept out of the
 * colour scheme so "success" is not smuggled into an unrelated M3 role.
 */
@Immutable
data class GnomeAccents(
    val success: Color,
    val warning: Color,
)

val LocalGnomeAccents = staticCompositionLocalOf {
    GnomeAccents(success = Adwaita.Green5, warning = Adwaita.Yellow5)
}

// Darkened against the light background: Adwaita's own #26A269 is ~3.1:1 on
// white, and these accents carry 11sp labels.
private val LightAccents = GnomeAccents(success = Color(0xFF1B7D51), warning = Color(0xFF9C6A00))
private val DarkAccents = GnomeAccents(success = Adwaita.GreenBright, warning = Adwaita.Yellow2)

private val LightColors = lightColorScheme(
    // Accent is a *background* in Adwaita (white on blue), so primaryContainer
    // carries the flat blue while primary stays readable as text on white.
    primary = Adwaita.Blue4,
    onPrimary = Color.White,
    // Blue5 rather than the flat accent Blue3: primaryContainer carries small
    // body text (message bubbles, banners, chips) and #3584E4 with white text is
    // only ~3.8:1, short of WCAG AA.
    primaryContainer = Adwaita.Blue5,
    onPrimaryContainer = Color.White,
    inversePrimary = Adwaita.Blue2,

    secondary = Color(0xFF5E5C64),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E7F0),
    onSecondaryContainer = Color(0xFF1B1C1F),

    tertiary = Adwaita.Green5,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6F5E4),
    onTertiaryContainer = Color(0xFF04321E),

    error = Adwaita.Red3,
    onError = Color.White,
    errorContainer = Color(0xFFFBDDDE),
    onErrorContainer = Color(0xFF5E1116),

    // window_bg_color behind the content, view_bg_color for bars and sheets.
    background = Color(0xFFFAFAFB),
    onBackground = Color(0xFF1E1E1E),
    surface = Adwaita.Light1,
    onSurface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFFEBEBED),
    onSurfaceVariant = Color(0xFF5E5C64),
    surfaceTint = Adwaita.Blue3,
    inverseSurface = Color(0xFF303030),
    inverseOnSurface = Adwaita.Light2,

    outline = Adwaita.Light4,
    outlineVariant = Adwaita.Light3,
    scrim = Color(0xFF000000),

    surfaceBright = Adwaita.Light1,
    surfaceDim = Color(0xFFE4E4E6),
    surfaceContainerLowest = Adwaita.Light1,
    surfaceContainerLow = Color(0xFFFAFAFB),
    surfaceContainer = Adwaita.Light2,
    surfaceContainerHigh = Color(0xFFEFEEED),
    surfaceContainerHighest = Color(0xFFE8E7E6),
)

private val DarkColors = darkColorScheme(
    primary = Adwaita.Blue3,
    onPrimary = Color.White,
    primaryContainer = Adwaita.Blue4,
    onPrimaryContainer = Color.White,
    inversePrimary = Adwaita.Blue5,

    secondary = Color(0xFFC0BFBC),
    onSecondary = Adwaita.Dark5,
    secondaryContainer = Color(0xFF303C4B),
    onSecondaryContainer = Color(0xFFE3E7EB),

    tertiary = Adwaita.GreenBright,
    onTertiary = Color(0xFF00391F),
    tertiaryContainer = Color(0xFF1F3D30),
    onTertiaryContainer = Color(0xFFA8F2C8),

    error = Adwaita.RedBright,
    onError = Color(0xFF4A0A0E),
    errorContainer = Adwaita.Red4,
    onErrorContainer = Color(0xFFFFE6E4),

    // view_bg_color for the content area, headerbar_bg_color for bars and sheets.
    background = Color(0xFF1E1E1E),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF303030),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF383838),
    onSurfaceVariant = Color(0xFFB9B9B9),
    surfaceTint = Adwaita.Blue3,
    inverseSurface = Adwaita.Light2,
    inverseOnSurface = Color(0xFF242424),

    outline = Color(0xFF4A4A4A),
    outlineVariant = Adwaita.Dark3,
    scrim = Color(0xFF000000),

    surfaceBright = Color(0xFF3A3A3A),
    surfaceDim = Color(0xFF171717),
    surfaceContainerLowest = Color(0xFF161616),
    surfaceContainerLow = Color(0xFF1E1E1E),
    surfaceContainer = Color(0xFF242424),
    surfaceContainerHigh = Color(0xFF303030),
    surfaceContainerHighest = Color(0xFF3A3A3A),
)

@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalGnomeAccents provides if (darkTheme) DarkAccents else LightAccents) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}
