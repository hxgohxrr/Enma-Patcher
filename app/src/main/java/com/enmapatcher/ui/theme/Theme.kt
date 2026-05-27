package com.enmapatcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Purple = Color(0xFF6650A4)
private val PurpleContainer = Color(0xFFEADDFF)
private val Teal = Color(0xFF03DAC6)

private val LightColors = lightColorScheme(
    primary = Purple,
    primaryContainer = PurpleContainer,
    secondary = Teal,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    primaryContainer = Color(0xFF4F378B),
    secondary = Teal,
)

@Composable
fun EnmaPatcherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content,
    )
}
