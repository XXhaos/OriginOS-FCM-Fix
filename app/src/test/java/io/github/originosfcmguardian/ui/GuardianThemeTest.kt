package io.github.originosfcmguardian.ui

import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianThemeTest {
    @Test fun bothPalettesHaveReadablePrimaryAndBodyText() {
        listOf(GuardianDarkColors, GuardianLightColors).forEach { colors ->
            fun contrast(a: Float, b: Float) = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
            assertTrue(contrast(colors.onSurface.luminance(), colors.surface.luminance()) >= 4.5f)
            assertTrue(contrast(colors.onSurfaceVariant.luminance(), colors.surfaceContainer.luminance()) >= 4.5f)
            assertTrue(contrast(colors.primary.luminance(), colors.background.luminance()) >= 4.5f)
            val dark = colors.surface.luminance() < 0.5f
            assertTrue(contrast(guardianSuccessColor(dark).luminance(), colors.surfaceContainerLow.luminance()) >= 4.5f)
            assertTrue(contrast(guardianWarningColor(dark).luminance(), colors.surfaceContainerLow.luminance()) >= 4.5f)
        }
    }

    @Test fun palettesRepresentOppositeSystemModes() {
        assertTrue(GuardianDarkColors.background.luminance() < 0.1f)
        assertTrue(GuardianLightColors.background.luminance() > 0.8f)
    }
}
