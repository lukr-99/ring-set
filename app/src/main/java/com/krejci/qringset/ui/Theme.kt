package com.krejci.qringset.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.krejci.qringset.data.MetricType

val Teal = Color(0xFF22D3EE)
val Pink = Color(0xFF7B2D5B)
val InkDark = Color(0xFF0A0F22)
val SurfaceDark = Color(0xFF121A33)

private val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF04222B),
    secondary = Color(0xFFF472B6),
    background = InkDark,
    onBackground = Color(0xFFEAF0FF),
    surface = SurfaceDark,
    onSurface = Color(0xFFEAF0FF),
    surfaceVariant = Color(0xFF19244A),
    onSurfaceVariant = Color(0xFF8A9BC2),
    outline = Color(0xFF35406A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0E97B4),
    onPrimary = Color.White,
    secondary = Color(0xFFC03D86),
    background = Color(0xFFEEF2FB),
    onBackground = Color(0xFF0C1630),
    surface = Color.White,
    onSurface = Color(0xFF0C1630),
    surfaceVariant = Color(0xFFEAF0FB),
    onSurfaceVariant = Color(0xFF4D5D80),
    outline = Color(0xFFC7D2E8),
)

@Composable
fun RingSetTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

/** Per-metric accent colors (separate from the app accent). */
fun metricColor(m: MetricType): Color = when (m) {
    MetricType.HR -> Color(0xFFF472B6)
    MetricType.SPO2 -> Color(0xFF22D3EE)
    MetricType.HRV -> Color(0xFFA78BFA)
    MetricType.STRESS -> Color(0xFFFBBF24)
    MetricType.STEPS -> Color(0xFF34D399)
}

val SleepColor = Color(0xFF818CF8)
