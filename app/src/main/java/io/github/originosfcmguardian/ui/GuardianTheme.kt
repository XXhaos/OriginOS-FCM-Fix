package io.github.originosfcmguardian.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal fun guardianSuccessColor(dark: Boolean) = if (dark) Color(0xFF9CE3B5) else Color(0xFF256640)
internal fun guardianWarningColor(dark: Boolean) = if (dark) Color(0xFFFFCF80) else Color(0xFF815000)

/** Fixed visual hierarchy for the primary control; never a connection/authority indicator. */
internal fun guardianPrimaryCardColor(colors: ColorScheme): Color =
    lerp(colors.surfaceContainer, colors.primaryContainer, 0.18f)

internal val GuardianDarkColors = darkColorScheme(
    primary = Color(0xFFEDEDED), onPrimary = Color(0xFF121212),
    primaryContainer = Color(0xFF2A2A2A), onPrimaryContainer = Color(0xFFF5F5F5),
    secondary = Color(0xFFC7C7C7), onSecondary = Color(0xFF202020),
    secondaryContainer = Color(0xFF242424), onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFFD6D6D6), onTertiary = Color(0xFF202020),
    tertiaryContainer = Color(0xFF303030), onTertiaryContainer = Color(0xFFF5F5F5),
    background = Color.Black, onBackground = Color(0xFFEDEDED),
    surface = Color.Black, onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF242424), onSurfaceVariant = Color(0xFFC7C7C7),
    surfaceContainer = Color(0xFF141414), surfaceContainerLow = Color(0xFF0D0D0D),
    surfaceContainerHigh = Color(0xFF1B1B1B), surfaceContainerHighest = Color(0xFF242424),
    surfaceContainerLowest = Color.Black, surfaceDim = Color.Black,
    surfaceBright = Color(0xFF303030), surfaceTint = Color(0xFFEDEDED),
    outline = Color(0xFF929292), outlineVariant = Color(0xFF454545),
    inverseSurface = Color(0xFFEDEDED), inverseOnSurface = Color(0xFF202020),
    inversePrimary = Color(0xFF333333),
    primaryFixed = Color(0xFFE6E6E6), primaryFixedDim = Color(0xFFC7C7C7),
    onPrimaryFixed = Color(0xFF202020), onPrimaryFixedVariant = Color(0xFF444444),
    secondaryFixed = Color(0xFFE6E6E6), secondaryFixedDim = Color(0xFFC7C7C7),
    onSecondaryFixed = Color(0xFF202020), onSecondaryFixedVariant = Color(0xFF444444),
    tertiaryFixed = Color(0xFFE6E6E6), tertiaryFixedDim = Color(0xFFC7C7C7),
    onTertiaryFixed = Color(0xFF202020), onTertiaryFixedVariant = Color(0xFF444444),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFFFDAD6),
)

internal val GuardianLightColors = lightColorScheme(
    primary = Color(0xFF202020), onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E6E6), onPrimaryContainer = Color(0xFF202020),
    secondary = Color(0xFF505050), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E6E6), onSecondaryContainer = Color(0xFF202020),
    tertiary = Color(0xFF595959), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEDEDED), onTertiaryContainer = Color(0xFF262626),
    background = Color.White, onBackground = Color(0xFF202020),
    surface = Color.White, onSurface = Color(0xFF202020),
    surfaceVariant = Color(0xFFEAEAEA), onSurfaceVariant = Color(0xFF555555),
    surfaceContainer = Color(0xFFF5F5F5), surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainerHigh = Color(0xFFF0F0F0), surfaceContainerHighest = Color(0xFFEAEAEA),
    surfaceContainerLowest = Color.White, surfaceDim = Color(0xFFDEDEDE),
    surfaceBright = Color.White, surfaceTint = Color(0xFF202020),
    outline = Color(0xFF747474), outlineVariant = Color(0xFFC7C7C7),
    inverseSurface = Color(0xFF262626), inverseOnSurface = Color(0xFFF5F5F5),
    inversePrimary = Color(0xFFEDEDED),
    primaryFixed = Color(0xFFE6E6E6), primaryFixedDim = Color(0xFFC7C7C7),
    onPrimaryFixed = Color(0xFF202020), onPrimaryFixedVariant = Color(0xFF444444),
    secondaryFixed = Color(0xFFE6E6E6), secondaryFixedDim = Color(0xFFC7C7C7),
    onSecondaryFixed = Color(0xFF202020), onSecondaryFixedVariant = Color(0xFF444444),
    tertiaryFixed = Color(0xFFE6E6E6), tertiaryFixedDim = Color(0xFFC7C7C7),
    onTertiaryFixed = Color(0xFF202020), onTertiaryFixedVariant = Color(0xFF444444),
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
