package com.example.trackpulse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val TrackPulseDarkColorScheme = darkColorScheme(
    primary = RacingRed,
    onPrimary = TextPrimary,
    primaryContainer = RacingRedDark,
    onPrimaryContainer = TextPrimary,
    secondary = TelemetryCyan,
    onSecondary = AsphaltBlack,
    secondaryContainer = TelemetryCyanDark,
    onSecondaryContainer = TextPrimary,
    tertiary = VoltGreen,
    onTertiary = AsphaltBlack,
    background = AsphaltBlack,
    onBackground = TextPrimary,
    surface = CarbonSurface,
    onSurface = TextPrimary,
    surfaceVariant = CarbonElevated,
    onSurfaceVariant = TextSecondary,
    outline = CarbonBorder,
    error = RacingRed,
    onError = TextPrimary
)

@Composable
fun TrackPulseTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = TrackPulseDarkColorScheme,
        typography = Typography,
        content = content
    )
}
