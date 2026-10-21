package io.github.originosfcmguardian.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GuardianMotionTest {
    @Test fun disabledSystemMotionSettlesWithoutRequestingAnyAnimationFrame() {
        val disabled = object : MotionDurationScale { override val scaleFactor = 0f }
        // There is deliberately no MonotonicFrameClock: a disabled animation must not wait for one.
        runBlocking(disabled) {
            val progress = Animatable(0f)
            guardianDestinationEnter(progress)
            assertEquals(1f, progress.value, 0f)
            assertEquals(false, progress.isRunning)
        }
    }
}
