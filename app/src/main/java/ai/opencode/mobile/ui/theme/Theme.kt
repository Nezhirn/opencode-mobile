package ai.opencode.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF7C9C6B)
private val AccentDark = Color(0xFF9CBE88)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF14210D),
    primaryContainer = Color(0xFF2C3B24),
    onPrimaryContainer = Color(0xFFD7E8C9),
    secondary = Color(0xFFB8C4B0),
    background = Color(0xFF0F1210),
    onBackground = Color(0xFFE2E5DE),
    surface = Color(0xFF151915),
    onSurface = Color(0xFFE2E5DE),
    surfaceVariant = Color(0xFF232823),
    onSurfaceVariant = Color(0xFFBFC6B8),
    outline = Color(0xFF434A40),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E8C9),
    onPrimaryContainer = Color(0xFF14210D),
    background = Color(0xFFFAFBF6),
    onBackground = Color(0xFF1A1C18),
    surface = Color(0xFFF3F5EE),
    onSurface = Color(0xFF1A1C18),
    surfaceVariant = Color(0xFFE1E4DB),
    onSurfaceVariant = Color(0xFF434A40),
)

@Composable
fun OpenCodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
