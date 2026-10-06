package io.github.originosfcmguardian.ui

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry

internal enum class SyntheticPhase {
    INITIAL, DIAGNOSTICS_OPEN, EXPORT_SCROLLED, EXPORT_READY, EXPORT_TOUCHED,
}

/** Test-only fixed fields: no tree text, app data, exception text or real service callbacks. */
internal class SyntheticFixtureEvidence(private val rootCount: () -> Int) {
    var activity: ComponentActivity? = null
        private set

    fun capture(context: Context) {
        activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>().firstOrNull()
    }

    fun record(phase: SyntheticPhase, counter: Int = 0) {
        var lifecycle = "NO_ACTIVITY"
        var focused = false
        var finishing = false
        var destroyed = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity?.let {
                lifecycle = it.lifecycle.currentState.name
                focused = it.hasWindowFocus()
                finishing = it.isFinishing
                destroyed = it.isDestroyed
            }
        }
        val roots = try {
            rootCount()
        } catch (_: AssertionError) { -1 }
          catch (_: RuntimeException) { -1 }
        Log.i("GuardianSyntheticPhase", "phase=$phase lifecycle=$lifecycle focused=$focused finishing=$finishing destroyed=$destroyed roots=$roots counter=$counter")
    }

    fun checked(phase: SyntheticPhase, block: () -> Unit) {
        record(phase)
        try { block() } catch (failure: AssertionError) {
            record(phase)
            throw failure
        } catch (failure: RuntimeException) {
            record(phase)
            throw failure
        }
    }
}
