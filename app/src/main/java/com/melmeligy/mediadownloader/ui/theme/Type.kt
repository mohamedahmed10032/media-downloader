package com.melmeligy.mediadownloader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val default = Typography()

/** Lightly customised Material 3 type scale using the platform default family (no font assets). */
val AppTypography = Typography(
    headlineLarge = default.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = default.headlineMedium.copy(fontWeight = FontWeight.Bold),
    titleLarge = default.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = default.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp
    )
)
