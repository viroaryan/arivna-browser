package com.arivna.browser

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class ArivnaColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val text: Color,
    val text2: Color,
    val text3: Color,
    val border: Color,
    val borderStrong: Color,
    val accent: Color,
    val accentStrong: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val danger: Color,
    val success: Color,
    val scrim: Color,
    val isDark: Boolean,
)

// One accent now; the enum survives only because Data.kt (stored pref default) and AppState.accentColor() still name it.
enum class AccentPreset(val accent: Color) {
    Terracotta(Color(0xFF9059FF));

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: Terracotta
    }
}

// Firefox for Android palette (docs/firefox/SPEC.md).
private val lightPalette = ArivnaColors(
    bg = Color(0xFFFFFFFF),
    surface = Color(0xFFF0F0F4),
    surface2 = Color(0xFFE0E0E6),
    surface3 = Color(0xFFCFCFD8),
    text = Color(0xFF15141A),
    text2 = Color(0xFF5B5B66),
    text3 = Color(0xFF8F8F9D),
    border = Color(0xFFE0E0E6),
    borderStrong = Color(0xFFBFBFC9),
    accent = Color(0xFF6B2BD9),
    accentStrong = Color(0xFF6B2BD9),
    accentSoft = Color(0x336B2BD9),
    onAccent = Color.White,
    danger = Color(0xFFC50042),
    success = Color(0xFF058B00),
    scrim = Color(0x66000000),
    isDark = false,
)

private val darkPalette = ArivnaColors(
    bg = Color(0xFF1C1B22),
    surface = Color(0xFF2B2A33),
    surface2 = Color(0xFF42414D),
    surface3 = Color(0xFF52525E),
    text = Color(0xFFFBFBFE),
    text2 = Color(0xFFAAA9B2),
    text3 = Color(0xFF8F8F9D),
    border = Color(0xFF3A3944),
    borderStrong = Color(0xFF5B5B66),
    accent = Color(0xFFCFA9FF),
    accentStrong = Color(0xFF9059FF),
    accentSoft = Color(0x33CFA9FF),
    onAccent = Color(0xFF20123A),
    danger = Color(0xFFFF6F8D),
    success = Color(0xFF54FFBD),
    scrim = Color(0x99000000),
    isDark = true,
)

private val privatePalette = darkPalette.copy(
    bg = Color(0xFF25003E),
    surface = Color(0xFF3B1659),
    surface2 = Color(0xFF4E2A70),
    surface3 = Color(0xFF5F3A82),
    border = Color(0xFF4E2A70),
)

val LocalArivna = staticCompositionLocalOf { lightPalette }

object Arivna {
    val colors: ArivnaColors
        @Composable get() = LocalArivna.current
}

object Type {
    val display = TextStyle(fontSize = 28.sp, lineHeight = 36.sp)
    val title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp)
    val headline = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
    val bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun ArivnaTheme(dark: Boolean = isSystemInDarkTheme(), private: Boolean = false, content: @Composable () -> Unit) {
    val c = if (private) privatePalette else if (dark) darkPalette else lightPalette
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = c.onAccent, secondary = c.accent,
        background = c.bg, onBackground = c.text, surface = c.surface, onSurface = c.text,
        surfaceVariant = c.surface2, onSurfaceVariant = c.text2, outline = c.borderStrong,
        error = c.danger,
    )
    CompositionLocalProvider(LocalArivna provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
