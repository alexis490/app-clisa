package com.example.app_clisa.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.unit.sp
import com.example.app_clisa.R

// Bundled fonts keep the same typography on every device and work offline.
@OptIn(ExperimentalTextApi::class)
val Inter = FontFamily(
    Font(R.font.inter_variable, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter_variable, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter_variable, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter_variable, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

private fun interStyle(size: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp,
)

val Typography = Typography(
    displayLarge = interStyle(28, 36, FontWeight.Bold),
    displayMedium = interStyle(26, 34, FontWeight.Bold),
    displaySmall = interStyle(24, 32, FontWeight.Bold),
    headlineLarge = interStyle(28, 36, FontWeight.Bold),
    headlineMedium = interStyle(26, 34, FontWeight.Bold),
    headlineSmall = interStyle(24, 32, FontWeight.Bold),
    titleLarge = interStyle(24, 32, FontWeight.Bold),
    titleMedium = interStyle(18, 24, FontWeight.Medium),
    titleSmall = interStyle(16, 22, FontWeight.Medium),
    bodyLarge = interStyle(16, 24, FontWeight.Normal),
    bodyMedium = interStyle(14, 20, FontWeight.Normal),
    bodySmall = interStyle(12, 18, FontWeight.Normal),
    labelLarge = interStyle(16, 22, FontWeight.Medium),
    labelMedium = interStyle(14, 20, FontWeight.Medium),
    labelSmall = interStyle(12, 18, FontWeight.Normal),
)

object ClisaTextStyles {
    val importantNumber = interStyle(26, 32, FontWeight.Bold)
}

