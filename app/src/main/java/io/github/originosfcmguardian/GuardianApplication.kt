package io.github.originosfcmguardian

import android.app.Application
import io.github.originosfcmguardian.autoprotection.ProtectionLifecycleDiagnostics

class GuardianApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (getProcessName() == packageName) {
            io.github.originosfcmguardian.daemon.DaemonAppRuntime.onAppCreated(this)
            io.github.originosfcmguardian.protection.LegacyInspectionCleanup.cancel(this)
            ProtectionLifecycleDiagnostics.initialize(this)
        }
    }
}
