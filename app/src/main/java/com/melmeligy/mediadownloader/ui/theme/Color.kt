package com.melmeligy.mediadownloader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand palette
val Indigo = Color(0xFF6D5DF6)
val IndigoLight = Color(0xFFB7AEFF)
val IndigoDark = Color(0xFF4A38E0)
val Teal = Color(0xFF35C6C0)

val DarkBackground = Color(0xFF0E1117)
val DarkSurface = Color(0xFF161B24)
val DarkSurfaceVariant = Color(0xFF222A38)
val LightBackground = Color(0xFFF7F8FC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFE7E9F2)

val Error = Color(0xFFFF5C6C)

val DarkColors = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF14103A),
    primaryContainer = IndigoDark,
    onPrimaryContainer = Color(0xFFEDEBFF),
    secondary = Teal,
    onSecondary = Color(0xFF00201F),
    background = DarkBackground,
    onBackground = Color(0xFFE6E9F0),
    surface = DarkSurface,
    onSurface = Color(0xFFE6E9F0),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB9C0CE),
    outline = Color(0xFF3A4356),
    error = Error,
    onError = Color(0xFF3A0007)
)

val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E0FF),
    onPrimaryContainer = Color(0xFF1A1050),
    secondary = Color(0xFF1FA9A3),
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = Color(0xFF15181F),
    surface = LightSurface,
    onSurface = Color(0xFF15181F),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF454B58),
    outline = Color(0xFFC4C8D4),
    error = Color(0xFFC0384A),
    onError = Color.White
)
