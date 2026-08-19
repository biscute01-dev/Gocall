package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = CyanGlow,
    onPrimary = SlateDark950,
    primaryContainer = IndigoAccent,
    onPrimaryContainer = Color.White,
    secondary = IndigoLight,
    onSecondary = SlateDark950,
    secondaryContainer = SlateDark800,
    onSecondaryContainer = SlateTextPrimary,
    tertiary = EmeraldGlow,
    onTertiary = SlateDark950,
    background = SlateDark950,
    onBackground = SlateTextPrimary,
    surface = SlateDark900,
    onSurface = SlateTextPrimary,
    surfaceVariant = SlateDark800,
    onSurfaceVariant = SlateTextSecondary,
    outline = SlateDark700,
    error = RoseDestructive,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Default to sleek dark video call aesthetic
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
