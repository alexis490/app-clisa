package com.example.app_clisa.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ClisaColors = lightColorScheme(
    primary = Color(0xFF0866FF),
    onPrimary = Color.White,
    secondary = Color(0xFF59729B),
    background = Color(0xFFF8FBFF),
    surface = Color(0xFFF8FBFF),
    onBackground = Color(0xFF071B43),
    onSurface = Color(0xFF071B43),
    error = Color(0xFFBA1A1A),
)

@Composable
fun AppclisaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ClisaColors, typography = Typography, content = content)
}
