package io.github.originosfcmguardian.testing

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import io.github.originosfcmguardian.BuildConfig

/** Opt-in synthetic UI fixture only; never initialize the daily app's backend or diagnostics. */
class Phase5FixtureTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader,
        className: String,
        context: Context,
    ): Application {
        // Fail closed if this runner is accidentally selected for a normal app/test build.
        check(BuildConfig.APPLICATION_ID == FIXTURE_PACKAGE && context.packageName == FIXTURE_PACKAGE) {
            "Phase5FixtureTestRunner requires the isolated phase5 fixture target"
        }
        return super.newApplication(cl, Application::class.java.name, context)
    }

    private companion object {
        const val FIXTURE_PACKAGE = "io.github.originosfcmguardian.phase5fixture"
    }
}
