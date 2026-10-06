package io.github.originosfcmguardian.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal fun guardianSuccessColor(dark: Boolean) = if (dark) Color(0xFF8DDB91) else Color(0xFF276C2B)
internal fun guardianWarningColor(dark: Boolean) = if (dark) Color(0xFFFFCF80) else Color(0xFF815000)

internal val GuardianDarkColors = darkColorScheme(
    primary = Color(0xFFA4CCF4), onPrimary = Color(0xFF10344C),
    primaryContainer = Color(0xFF384957), onPrimaryContainer = Color(0xFFD4E7FB),
    secondary = Color(0xFFBAC8D4), onSecondary = Color(0xFF24323D),
    secondaryContainer = Color(0xFF384957), onSecondaryContainer = Color(0xFFD4E7FB),
    background = Color(0xFF0F1417), onBackground = Color(0xFFE0E3E6),
    surface = Color(0xFF0F1417), onSurface = Color(0xFFE0E3E6),
    surfaceVariant = Color(0xFF30363C), onSurfaceVariant = Color(0xFFC1C7CE),
    surfaceContainer = Color(0xFF191E22), surfaceContainerLow = Color(0xFF171C20),
    surfaceContainerHigh = Color(0xFF22282D), surfaceContainerHighest = Color(0xFF30363C),
    outline = Color(0xFF899199), outlineVariant = Color(0xFF40484F),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFFFDAD6),
)

internal val GuardianLightColors = lightColorScheme(
    primary = Color(0xFF285F89), onPrimary = Color.White,
    primaryContainer = Color(0xFFD0E5FB), onPrimaryContainer = Color(0xFF10344C),
    secondary = Color(0xFF52616F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE7F1), onSecondaryContainer = Color(0xFF263A4B),
    background = Color(0xFFF6F8FA), onBackground = Color(0xFF191D21),
    surface = Color(0xFFF6F8FA), onSurface = Color(0xFF191D21),
    surfaceVariant = Color(0xFFE0E7ED), onSurfaceVariant = Color(0xFF424B53),
    surfaceContainer = Color(0xFFEBF0F5), surfaceContainerLow = Color(0xFFF0F4F8),
    surfaceContainerHigh = Color(0xFFE5EBF1), surfaceContainerHighest = Color(0xFFDCE3EA),
    outline = Color(0xFF737D86), outlineVariant = Color(0xFFC3CDD5),
)

/** A system setting, not another persisted preference or protection restart trigger. */
@Composable
fun GuardianTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) GuardianDarkColors else GuardianLightColors, content = content)
}
