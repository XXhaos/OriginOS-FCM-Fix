package io.github.originosfcmguardian

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Disposable ordinary-app surface used only for the opt-in OEM autostart UI experiment. */
class AutostartProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "Guardian Autostart Probe\nNo background or network behavior"
                gravity = Gravity.CENTER
            },
        )
    }
}
