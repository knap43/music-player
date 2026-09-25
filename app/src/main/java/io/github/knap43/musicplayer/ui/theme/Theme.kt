package io.github.knap43.musicplayer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFE1A34F)
val AccentDim = Color(0xFF5C4220)
val Background = Color(0xFF1C1C1E)
val Surface = Color(0xFF242426)
val SurfaceHigh = Color(0xFF2E2E31)
val SurfaceHighest = Color(0xFF38383B)
val OnSurface = Color(0xFFECECEC)
val OnSurfaceMuted = Color(0xFFA9A9AE)

private val colors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1C1C1E),
    primaryContainer = AccentDim,
    onPrimaryContainer = Color(0xFFFFDDB3),
    secondary = Accent,
    onSecondary = Color(0xFF1C1C1E),
    secondaryContainer = AccentDim,
    onSecondaryContainer = Color(0xFFFFDDB3),
    tertiary = Accent,
    onTertiary = Color(0xFF1C1C1E),
    background = Background,
    onBackground = OnSurface,
    surface = Background,
    onSurface = OnSurface,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = OnSurfaceMuted,
    surfaceTint = Accent,
    surfaceContainerLowest = Color(0xFF161618),
    surfaceContainerLow = Color(0xFF202022),
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh,
    surfaceContainerHighest = SurfaceHighest,
    surfaceBright = SurfaceHighest,
    surfaceDim = Background,
    outline = Color(0xFF5A5A5F),
    outlineVariant = Color(0xFF3A3A3D),
    inverseSurface = OnSurface,
    inverseOnSurface = Background,
    inversePrimary = AccentDim,
)

@Composable
fun MusicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
