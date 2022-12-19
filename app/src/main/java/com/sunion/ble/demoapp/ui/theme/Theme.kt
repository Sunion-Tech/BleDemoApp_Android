package com.sunion.ble.demoapp.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable

val DarkColorPalette = darkColors(
    primary = Color(0xFF074B7D),
    primaryVariant = Color(0xFF7496AF),
    secondary = Color(0xFF074B7D),
    secondaryVariant = Color(0xFF074B7D),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFE5404A),
    onPrimary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF212121),
    onSurface = Color(0xFF212121),
    onError = Color(0xFFFFFFFF),
)

val LightColorPalette = lightColors(
    primary = Color(0xFF074B7D),
    primaryVariant = Color(0xFF7496AF),
    secondary = Color(0xFF074B7D),
    secondaryVariant = Color(0xFF074B7D),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    error = Color(0xFFE5404A),
    onPrimary = Color(0xFFFFFFFF),
    onSecondary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF212121),
    onSurface = Color(0xFF212121),
    onError = Color(0xFFFFFFFF),
)

@Composable
fun BleDemoAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable() () -> Unit
) {
    val colors = if (darkTheme) {
        DarkColorPalette
    } else {
        LightColorPalette
    }

    MaterialTheme(
        colors = colors,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}