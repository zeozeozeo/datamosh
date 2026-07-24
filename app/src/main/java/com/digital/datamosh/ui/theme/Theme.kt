package com.digital.datamosh.ui.theme

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

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC8B5FF),
    onPrimary = Color(0xFF2E1363),
    primaryContainer = Color(0xFF4A2B80),
    onPrimaryContainer = Color(0xFFE8DDFF),
    secondary = Color(0xFFFFB1C8),
    tertiary = Color(0xFF72D7D0),
    surface = Color(0xFF111016),
    surfaceContainer = Color(0xFF242128),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF68439B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEBDDFF),
    secondary = Color(0xFF8E405C),
    tertiary = Color(0xFF006A66),
)

@Composable
fun DatamoshTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
