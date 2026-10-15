package io.github.originosfcmguardian.ui

import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.github.originosfcmguardian.autoprotection.AutomaticProtectionState
import org.junit.Rule
import org.junit.Test

/** Display fixtures only: does not launch or stop actual protection. */
class AutomaticProtectionUptimeControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun oneLineAppearsOnlyWhileSessionIsReady() {
        val state = mutableStateOf(AutomaticProtectionState(masterEnabled = true, enabled = true,
            coreSessionStartedAtElapsedMillis = SystemClock.elapsedRealtime()))
        compose.setContent { MaterialTheme { AutomaticProtectionUptime(state.value) } }
        compose.onNodeWithText("已开启时长", substring = true).assertExists()
        compose.onNodeWithText("本次检测会话；中断后重新计时。").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(waitingForPrivilege = true) }
        compose.onNodeWithText("已开启时长", substring = true).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(waitingForPrivilege = false) }
        compose.onNodeWithText("已开启时长", substring = true).assertExists()
        compose.runOnIdle { state.value = state.value.copy(masterEnabled = false) }
        compose.onNodeWithText("已开启时长", substring = true).assertDoesNotExist()
    }
}
