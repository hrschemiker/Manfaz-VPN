package com.manfaz.vpn.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

private val LightColors = lightColorScheme(
    primary = BrandOrange,
    onPrimary = LightSurface,
    primaryContainer = BrandOrangeLight,
    secondary = BrandAmber,
    tertiary = BrandGold,
    background = LightBg,
    surface = LightSurface,
    onSurface = LightOnSurface,
)

private val DarkColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = Color(0xFF06201B),
    primaryContainer = DarkAccentContainer,
    secondary = DarkSecondary,
    tertiary = DarkTertiary,
    background = DarkBg,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
)

private val AmoledColors = DarkColors.copy(
    background = AmoledBg,
    surface = AmoledSurface,
)

@Composable
fun ManfazTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        themeMode == ThemeMode.AMOLED -> AmoledColors
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        content = content,
    )
}
