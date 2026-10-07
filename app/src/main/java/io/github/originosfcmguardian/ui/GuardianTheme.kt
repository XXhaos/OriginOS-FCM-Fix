package io.github.originosfcmguardian.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal fun guardianSuccessColor(dark: Boolean) = if (dark) Color(0xFF9CE3B5) else Color(0xFF256640)
internal fun guardianWarningColor(dark: Boolean) = if (dark) Color(0xFFFFCF80) else Color(0xFF815000)

internal val GuardianDarkColors = darkColorScheme(
    primary = Color(0xFF77D9C6), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF234B45), onPrimaryContainer = Color(0xFFE0FFF6),
    secondary = Color(0xFFB0CCC3), onSecondary = Color(0xFF203A32),
    secondaryContainer = Color(0xFF304A42), onSecondaryContainer = Color(0xFFD2EAE1),
    tertiary = Color(0xFFBCCAE7), onTertiary = Color(0xFF25334C),
    tertiaryContainer = Color(0xFF3B4963), onTertiaryContainer = Color(0xFFDCE5FF),
    background = Color(0xFF0D1719), onBackground = Color(0xFFE0ECE8),
    surface = Color(0xFF0D1719), onSurface = Color(0xFFE0ECE8),
    surfaceVariant = Color(0xFF2B3C3F), onSurfaceVariant = Color(0xFFC4D7D2),
    surfaceContainer = Color(0xFF162427), surfaceContainerLow = Color(0xFF132023),
    surfaceContainerHigh = Color(0xFF1C2D30), surfaceContainerHighest = Color(0xFF2B3C3F),
    surfaceContainerLowest = Color(0xFF081113), surfaceDim = Color(0xFF0D1719),
    surfaceBright = Color(0xFF324346), surfaceTint = Color(0xFF77D9C6),
    outline = Color(0xFF819790), outlineVariant = Color(0xFF3A504C),
    inverseSurface = Color(0xFFE0ECE8), inverseOnSurface = Color(0xFF22312E),
    inversePrimary = Color(0xFF006B5D),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFFFDAD6),
)

internal val GuardianLightColors = lightColorScheme(
    primary = Color(0xFF006B5D), onPrimary = Color.White,
    primaryContainer = Color(0xFFD2F1E8), onPrimaryContainer = Color(0xFF00382F),
    secondary = Color(0xFF49665B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEE8DB), onSecondaryContainer = Color(0xFF143C2D),
    tertiary = Color(0xFF52617D), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE5FF), onTertiaryContainer = Color(0xFF26334C),
    background = Color(0xFFF3F8F7), onBackground = Color(0xFF172A28),
    surface = Color(0xFFF3F8F7), onSurface = Color(0xFF172A28),
    surfaceVariant = Color(0xFFDCEBE6), onSurfaceVariant = Color(0xFF455B55),
    surfaceContainer = Color.White, surfaceContainerLow = Color(0xFFEAF3F1),
    surfaceContainerHigh = Color(0xFFE4EFEC), surfaceContainerHighest = Color(0xFFDCEBE6),
    surfaceContainerLowest = Color.White, surfaceDim = Color(0xFFD4E1DD),
    surfaceBright = Color(0xFFF3F8F7), surfaceTint = Color(0xFF006B5D),
    outline = Color(0xFF6E847C), outlineVariant = Color(0xFFBECEC8),
    inverseSurface = Color(0xFF22312E), inverseOnSurface = Color(0xFFE5F2ED),
    inversePrimary = Color(0xFF77D9C6),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

internal val GuardianShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val BaseGuardianTypography = Typography()
internal val GuardianTypography = BaseGuardianTypography.copy(
    headlineMedium = BaseGuardianTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseGuardianTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseGuardianTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseGuardianTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
)

/** A system setting, not another persisted preference or protection restart trigger. */
@Composable
fun GuardianTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) GuardianDarkColors else GuardianLightColors,
        shapes = GuardianShapes, typography = GuardianTypography, content = content)
}
