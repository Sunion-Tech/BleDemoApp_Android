package com.sunion.ble.demoapp.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

class AppColors(
    primary: Color = Color(0xFF074B7D),
    primaryVariant: Color = Color(0xFF7496AF),
    onPrimary: Color = Color(0xFFFFFFFF),
    secondary: Color = Color(0xFF074B7D),
    secondaryVariant: Color = Color(0xFF074B7D),
    onSecondary: Color = Color(0xFFFFFFFF),
    error: Color = Color(0xFFE5404A),
    onError: Color = Color(0xFFFFFFFF),
    surface: Color = Color(0xFFFFFFFF),
    onSurface: Color = Color(0xFF212121),
    background: Color = Color(0xFFFFFFFF),
    onBackground: Color = Color(0xFF212121),
    buttonDisable: Color = Color(0xFF979797),
    blueInfo: Color = Color(0xFF007AFF),
    disconnected: Color = Color(0xFFACACAC),
    divider: Color = Color(0xFFEAEAEA),
    enableGreen: Color = Color(0xFF49F764),
    logError: Color = Color(0xFFFFDC73),
    disableGrey: Color = Color(0xFFEAEAEA),
    lightPrimary: Color = Color(0xFFEFF3F5),
    geofenceRadiusFillColor: Color = Color(0x1A007AFF),  // 10% alpha of #007AFF
    popupText: Color = Color(0xFF212121),
    isLight: Boolean
) {
    var primary by mutableStateOf(primary)
        private set
    var primaryVariant by mutableStateOf(primaryVariant)
        private set
    var onPrimary by mutableStateOf(onPrimary)
        private set
    var secondary by mutableStateOf(secondary)
        private set
    var secondaryVariant by mutableStateOf(secondaryVariant)
        private set
    var onSecondary by mutableStateOf(onSecondary)
        private set
    var error by mutableStateOf(error)
        private set
    var onError by mutableStateOf(onError)
        private set
    var surface by mutableStateOf(surface)
        private set
    var onSurface by mutableStateOf(onSurface)
        private set
    var background by mutableStateOf(background)
        private set
    var onBackground by mutableStateOf(onBackground)
        private set
    var buttonDisable by mutableStateOf(buttonDisable)
        private set
    var blueInfo by mutableStateOf(blueInfo)
        private set
    var disconnected by mutableStateOf(disconnected)
        private set
    var divider by mutableStateOf(divider)
        private set
    var enableGreen by mutableStateOf(enableGreen)
        private set
    var logError by mutableStateOf(logError)
        private set
    var disableGrey by mutableStateOf(disableGrey)
        private set
    var lightPrimary by mutableStateOf(lightPrimary)
        private set
    var geofenceRadiusFillColor by mutableStateOf(geofenceRadiusFillColor)
        private set
    var popupText by mutableStateOf(popupText)
        private set
    var isLight by mutableStateOf(isLight)
        internal set

    fun copy(
        primary: Color = this.primary,
        primaryVariant: Color = this.primaryVariant,
        onPrimary: Color = this.onPrimary,
        secondary: Color = this.secondary,
        secondaryVariant: Color = this.secondaryVariant,
        onSecondary: Color = this.onSecondary,
        error: Color = this.error,
        onError: Color = this.onError,
        surface: Color = this.surface,
        onSurface: Color = this.onSurface,
        background: Color = this.background,
        onBackground: Color = this.onBackground,
        buttonDisable: Color = this.buttonDisable,
        blueInfo: Color = this.blueInfo,
        disconnected: Color = this.disconnected,
        divider: Color = this.divider,
        enableGreen: Color = this.enableGreen,
        logError: Color = this.logError,
        disableGrey: Color = this.disableGrey,
        lightPrimary: Color = this.lightPrimary,
        geofenceRadiusFillColor: Color = this.geofenceRadiusFillColor,  // 10% alpha of #007AFF
        popupText: Color = this.popupText,
        isLight: Boolean = this.isLight
    ): AppColors = AppColors(
        primary,
        primaryVariant,
        onPrimary,
        secondary,
        secondaryVariant,
        onSecondary,
        error,
        onError,
        surface,
        onSurface,
        background,
        onBackground,
        buttonDisable,
        blueInfo,
        disconnected,
        divider,
        enableGreen,
        logError,
        disableGrey,
        lightPrimary,
        geofenceRadiusFillColor,  // 10% alpha of #007AFF
        popupText,
        isLight
    )

    fun updateColorsFrom(other: AppColors) {
        primary = other.primary
        primaryVariant = other.primaryVariant
        onPrimary = other.onPrimary
        secondary = other.secondary
        secondaryVariant = other.secondaryVariant
        onSecondary = other.onSecondary
        error = other.error
        onError = other.onError
        surface = other.surface
        onSurface = other.onSurface
        background = other.background
        onBackground = other.onBackground
        buttonDisable = other.buttonDisable
        blueInfo = other.blueInfo
        disconnected = other.disconnected
        divider = other.divider
        enableGreen = other.enableGreen
        logError = other.logError
        disableGrey = other.disableGrey
        lightPrimary = other.lightPrimary
        geofenceRadiusFillColor = other.geofenceRadiusFillColor
        popupText = other.popupText
    }
}

fun lightColors(): AppColors = AppColors(
    isLight = true
)

fun darkColors(): AppColors = AppColors(
    isLight = false
)

val LocalColors = staticCompositionLocalOf { lightColors() }