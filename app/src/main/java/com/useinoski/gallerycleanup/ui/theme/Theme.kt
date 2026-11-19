package com.useinoski.gallerycleanup.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Pine = Color(0xFF1E3A34)
private val PineBright = Color(0xFF8FBFB0)
private val Ember = Color(0xFFB85C38)
private val Paper = Color(0xFFF3EEE6)
private val PaperCard = Color(0xFFFAF7F2)
private val PaperDeep = Color(0xFFE7E0D4)
private val Ink = Color(0xFF1C1B19)
private val InkMuted = Color(0xFF5C574E)
private val Night = Color(0xFF121412)
private val NightCard = Color(0xFF1C201E)
private val NightInk = Color(0xFFE7E2D8)

private val LightColors = lightColorScheme(
    primary = Pine,
    onPrimary = Paper,
    secondary = Ember,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF3D7C8),
    onSecondaryContainer = Color(0xFF3D2418),
    background = Paper,
    onBackground = Ink,
    surface = PaperCard,
    onSurface = Ink,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = InkMuted,
    error = Color(0xFF8C2F2F),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = PineBright,
    onPrimary = Night,
    secondary = Color(0xFFE7B496),
    onSecondary = Night,
    secondaryContainer = Color(0xFF5A3224),
    onSecondaryContainer = Color(0xFFF6E4D8),
    background = Night,
    onBackground = NightInk,
    surface = NightCard,
    onSurface = NightInk,
    surfaceVariant = Color(0xFF2A302C),
    onSurfaceVariant = Color(0xFFC9C2B6),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val GalleryTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 17.sp,
        lineHeight = 26.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
)

private val GalleryShapes = Shapes(
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(28.dp),
)

@Composable
fun GalleryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = GalleryTypography,
        shapes = GalleryShapes,
        content = content,
    )
}
