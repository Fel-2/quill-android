package fel.quill.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0F766E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF042F2E),
    tertiary = Color(0xFFB45309),
    onTertiary = Color.White,
    background = Color(0xFFF8F7FC),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFF8F7FC),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE8E7EF),
    onSurfaceVariant = Color(0xFF5F5E68),
    outline = Color(0xFF8A8993),
    error = Color(0xFFB42318),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF3730A3),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF115E59),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFFFBBF24),
    onTertiary = Color(0xFF451A03),
    background = Color(0xFF111116),
    onBackground = Color(0xFFE8E7EF),
    surface = Color(0xFF111116),
    onSurface = Color(0xFFE8E7EF),
    surfaceVariant = Color(0xFF292832),
    onSurfaceVariant = Color(0xFFC9C8D2),
    outline = Color(0xFF8A8993),
    error = Color(0xFFFFB4AB),
)

private val QuillTypography = Typography(
    headlineMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
    ),
    bodyLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
    ),
    labelSmall = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun QuillTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = QuillTypography,
        content = content,
    )
}
