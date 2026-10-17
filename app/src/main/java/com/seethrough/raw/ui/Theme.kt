package com.seethrough.raw.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF07090D)
val Surface = Color(0xFF10151C)
val SurfaceAlt = Color(0xFF181F2A)
val Line = Color(0xFF2A3544)
val TextPrimary = Color(0xFFE8EEF7)
val Muted = Color(0xFF8E9BB0)
val Dim = Color(0xFF5C6B80)
val ImuAccent = Color(0xFF86EFAC)
val WifiAccent = Color(0xFFFDA4AF)
val MagAccent = Color(0xFFC4B5FD)
val GyroAccent = Color(0xFFFBBF24)
val AccelAccent = Color(0xFF5EEAD4)
val Danger = Color(0xFFFB7185)

private val Colors = darkColorScheme(
    background = Ink,
    surface = Surface,
    primary = AccelAccent,
    onPrimary = Color(0xFF07110F),
    secondary = ImuAccent,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

@Composable
fun SeeThroughTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        content = content,
    )
}
