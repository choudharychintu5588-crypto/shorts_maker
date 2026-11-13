package com.shortsmaker.proof

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color(0xFFFF5C7A),
    onPrimary = Color(0xFF2B0010),
    secondary = Color(0xFF7C9CFF),
    background = Color(0xFF12121A),
    onBackground = Color(0xFFEDEDF5),
    surface = Color(0xFF1C1C27),
    onSurface = Color(0xFFEDEDF5),
    surfaceVariant = Color(0xFF262635),
    onSurfaceVariant = Color(0xFFB8B8CC),
    error = Color(0xFFFF8A80),
)

@Composable
fun ShortsTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors, content = content)
