package io.github.originosfcmguardian.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianThemeTest {
    @Test fun bothPalettesHaveReadableTextAcrossActualSurfaces() {
        listOf(GuardianDarkColors, GuardianLightColors).forEach { colors ->
            val dark = colors.surface.luminance() < 0.5f
            val surfaces = listOf(
                colors.background, colors.surface, colors.surfaceContainerLowest,
                colors.surfaceContainerLow, colors.surfaceContainer, colors.surfaceContainerHigh,
                colors.surfaceContainerHighest,
                // Home's highlighted cards retain the same text/status roles.
                lerp(colors.surfaceContainer, colors.primaryContainer, 0.24f),
                guardianPrimaryCardColor(colors),
            )
            val foregrounds = listOf(
                colors.onSurface, colors.onSurfaceVariant, colors.primary, colors.error,
                guardianSuccessColor(dark), guardianWarningColor(dark),
            )
            surfaces.forEach { surface -> foregrounds.forEach { text ->
                assertContrast(text, surface, 4.5f)
            } }
            listOf(
                colors.onBackground to colors.background,
                colors.onPrimary to colors.primary,
                colors.onPrimaryContainer to colors.primaryContainer,
                colors.onSecondary to colors.secondary,
                colors.onSecondaryContainer to colors.secondaryContainer,
                colors.onTertiary to colors.tertiary,
                colors.onTertiaryContainer to colors.tertiaryContainer,
                colors.onSurfaceVariant to colors.surfaceVariant,
                colors.onError to colors.error,
                colors.onErrorContainer to colors.errorContainer,
                colors.inverseOnSurface to colors.inverseSurface,
                colors.onPrimaryFixed to colors.primaryFixed,
                colors.onPrimaryFixedVariant to colors.primaryFixedDim,
                colors.onSecondaryFixed to colors.secondaryFixed,
                colors.onSecondaryFixedVariant to colors.secondaryFixedDim,
                colors.onTertiaryFixed to colors.tertiaryFixed,
                colors.onTertiaryFixedVariant to colors.tertiaryFixedDim,
            ).forEach { (text, surface) -> assertContrast(text, surface, 4.5f) }
        }
    }

    @Test fun selectionSwitchThumbsAndTrackOutlinesRemainDistinct() {
        listOf(GuardianDarkColors, GuardianLightColors).forEach { colors ->
            assertContrast(colors.onPrimary, colors.primary, 3f)
            assertContrast(colors.onSurfaceVariant, colors.surfaceContainerHighest, 3f)
            assertContrast(colors.outline, colors.surfaceContainerHighest, 3f)
        }
    }

    @Test fun palettesRepresentOppositeSystemModes() {
        assertEquals(Color.Black, GuardianDarkColors.background)
        assertEquals(Color.Black, GuardianDarkColors.surface)
        assertEquals(Color.White, GuardianLightColors.background)
        assertEquals(Color.White, GuardianLightColors.surface)
    }

    @Test fun nonSemanticThemeRolesAndCardEmphasisAreNeutral() {
        listOf(GuardianDarkColors, GuardianLightColors).forEach { colors ->
            listOf(
                colors.primary, colors.onPrimary, colors.primaryContainer, colors.onPrimaryContainer,
                colors.secondary, colors.onSecondary, colors.secondaryContainer, colors.onSecondaryContainer,
                colors.tertiary, colors.onTertiary, colors.tertiaryContainer, colors.onTertiaryContainer,
                colors.background, colors.onBackground, colors.surface, colors.onSurface,
                colors.surfaceVariant, colors.onSurfaceVariant, colors.surfaceContainerLowest,
                colors.surfaceContainerLow, colors.surfaceContainer, colors.surfaceContainerHigh,
                colors.surfaceContainerHighest, colors.surfaceDim, colors.surfaceBright, colors.surfaceTint,
                colors.outline, colors.outlineVariant, colors.inverseSurface, colors.inverseOnSurface,
                colors.inversePrimary, colors.scrim, guardianPrimaryCardColor(colors),
                colors.primaryFixed, colors.primaryFixedDim, colors.onPrimaryFixed, colors.onPrimaryFixedVariant,
                colors.secondaryFixed, colors.secondaryFixedDim, colors.onSecondaryFixed, colors.onSecondaryFixedVariant,
                colors.tertiaryFixed, colors.tertiaryFixedDim, colors.onTertiaryFixed, colors.onTertiaryFixedVariant,
            ).forEach { color ->
                assertEquals("Non-semantic theme color must have equal red and green", color.red, color.green, 0f)
                assertEquals("Non-semantic theme color must have equal green and blue", color.green, color.blue, 0f)
            }
        }
    }

    @Test fun neutralSurfaceLayersRemainDistinctInBothModes() {
        listOf(GuardianDarkColors, GuardianLightColors).forEach { colors ->
            val layers = listOf(colors.surfaceContainerLowest, colors.surfaceContainerLow,
                colors.surfaceContainer, colors.surfaceContainerHigh, colors.surfaceContainerHighest)
            layers.zipWithNext().forEach { (lower, higher) ->
                if (colors == GuardianDarkColors) assertTrue(lower.luminance() < higher.luminance())
                else assertTrue(lower.luminance() > higher.luminance())
            }
        }
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Float) {
        val a = foreground.luminance()
        val b = background.luminance()
        val contrast = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
        assertTrue("$foreground on $background contrast $contrast is below $minimum", contrast >= minimum)
    }
}
