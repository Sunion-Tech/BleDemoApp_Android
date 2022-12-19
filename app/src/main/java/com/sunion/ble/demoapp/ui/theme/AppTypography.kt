package com.sunion.ble.demoapp.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class AppTypography(
    val h1: TextStyle = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp
    ),
    val title: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp
    ),
    val subtitle: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp
    ),
    val body: TextStyle = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),
    val button: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        color = Color.White
    ),
    val caption: TextStyle = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp
    )
)

internal val LocalTypography = staticCompositionLocalOf { AppTypography() }