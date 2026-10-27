package com.bagaglimarocco.autista.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val RoadBlue = Color(0xFF0B4F8A)
val RoadBlueDark = Color(0xFF1D6DB3)
val Amber = Color(0xFFF2A900)
val AmberInk = Color(0xFF1D1500)

@Immutable
data class StatusColors(
    val ok: Color, val okSoft: Color,
    val bad: Color, val badSoft: Color,
    val waitSoft: Color,
)

val LocalStatus = staticCompositionLocalOf {
    StatusColors(Color(0xFF1F8A4C), Color(0xFFE3F3EA), Color(0xFFC62F2F), Color(0xFFFBE7E7), Color(0xFFFFF4D6))
}

private val Light = lightColorScheme(
    primary = RoadBlue, onPrimary = Color.White,
    secondary = Amber, onSecondary = AmberInk,
    background = Color(0xFFEEF1F5), onBackground = Color(0xFF14202E),
    surface = Color.White, onSurface = Color(0xFF14202E),
    surfaceVariant = Color(0xFFE4E9F0), onSurfaceVariant = Color(0xFF5B6878),
    outline = Color(0xFFD6DDE6), error = Color(0xFFC62F2F),
)

private val Dark = darkColorScheme(
    primary = RoadBlueDark, onPrimary = Color.White,
    secondary = Color(0xFFFFBF1F), onSecondary = AmberInk,
    background = Color(0xFF0C131B), onBackground = Color(0xFFE8EEF5),
    surface = Color(0xFF151F2A), onSurface = Color(0xFFE8EEF5),
    surfaceVariant = Color(0xFF1E2A37), onSurfaceVariant = Color(0xFF93A2B3),
    outline = Color(0xFF26323F), error = Color(0xFFFF6B6B),
)

// Testi grandi: l'app si usa in piedi, al sole, con i guanti.
private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
)

@Composable
fun AutistaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val status = if (dark) {
        StatusColors(Color(0xFF3FBF75), Color(0xFF16301F), Color(0xFFFF6B6B), Color(0xFF3A1A1A), Color(0xFF3A3016))
    } else {
        StatusColors(Color(0xFF1F8A4C), Color(0xFFE3F3EA), Color(0xFFC62F2F), Color(0xFFFBE7E7), Color(0xFFFFF4D6))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatus provides status) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
    }
}
