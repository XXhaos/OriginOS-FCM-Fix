package io.github.originosfcmguardian.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Isolated visual/callback fixtures only. No ViewModel, app command, authority or privileged read. */
class GuardianVisualRefinementTest {
    @get:Rule val compose = createComposeRule()

    @Test fun destinationEntryKeepsOnlyCurrentContentAndDoesNotResetRememberedState() {
        compose.mainClock.autoAdvance = false
        val destination = mutableStateOf("home")
        val mounted = mutableSetOf<String>()
        val tokens = mutableListOf<Any>()
        compose.setContent {
            GuardianTheme {
                Column(Modifier.guardianDestinationEnter(destination.value)) {
                    val token = remember { Any() }
                    DisposableEffect(destination.value) {
                        val current = destination.value
                        mounted += current; tokens += token
                        onDispose { mounted -= current }
                    }
                    Text("destination:${destination.value}")
                }
            }
        }
        compose.onNodeWithText("destination:home").assertExists()
        compose.runOnIdle { destination.value = "apps" }
        compose.onNodeWithText("destination:home").assertDoesNotExist()
        compose.onNodeWithText("destination:apps").assertExists()
        compose.runOnIdle {
            assertEquals(setOf("apps"), mounted)
            assertSame(tokens.first(), tokens.last())
            destination.value = "settings"
        }
        compose.onNodeWithText("destination:apps").assertDoesNotExist()
        compose.onNodeWithText("destination:settings").assertExists()
        compose.runOnIdle { assertEquals(setOf("settings"), mounted); assertSame(tokens.first(), tokens.last()) }
        compose.mainClock.advanceTimeBy(200)
    }

    @Test fun persistentOnIntentAndOffCallbackRemainImmediateWhileWaitingForExecution() {
        var enabled = 0; var disabled = 0
        val state = HomeUiState(automaticProtection = AutomaticProtectionState(masterEnabled = true))
        compose.setContent { GuardianTheme { PushProtectionCard(state, { enabled++ }, { disabled++ }, {}) } }
        compose.onNodeWithContentDescription("FCM推送保护开关").assertIsOn()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "总开关已开启，等待保护恢复"))
            .performClick()
        compose.runOnIdle { assertEquals(0, enabled); assertEquals(1, disabled) }
    }

    @Test fun largeFontClocksRetainBothLabelsAndCompleteValuesInCombinedSemantics() {
        val dark = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                GuardianTheme(darkTheme = dark.value) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().padding(24.dp)) {
                            Column(Modifier.width(220.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                PushProtectionClockLine("已开启时长", "123:45:56")
                                PushProtectionClockLine("FCM连接时长", "--:--:--")
                            }
                        }
                    }
                }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnIdle { dark.value = mode }
            compose.onNodeWithText("已开启时长 123:45:56").assertExists()
            compose.onNodeWithText("FCM连接时长 --:--:--").assertExists()
            if (BuildConfig.APPLICATION_ID == "io.github.originosfcmguardian.phase5fixture") {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                check(context.packageName == BuildConfig.APPLICATION_ID)
                val directory = checkNotNull(context.getExternalFilesDir(null))
                val name = "phase5-clock-${if (mode) "dark" else "light"}-220dp-2x.png"
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        }
    }
}
