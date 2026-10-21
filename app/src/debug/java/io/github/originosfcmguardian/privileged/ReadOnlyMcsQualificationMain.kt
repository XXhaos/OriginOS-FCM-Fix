package io.github.originosfcmguardian.privileged

import android.system.Os

/** Debug-only app_process probe, never registered in the manifest or attached to a daemon. */
object ReadOnlyMcsQualificationMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty())
        require(Os.getuid() == 2000)
        val port = SystemGmsConnectionReadPort()
        val environment = port.environment()
        val identity = checkNotNull(port.gmsPackage())
        check(identity.versionCode >= 0)
        val attemptAllowed = port.disconnectedMcsAttemptAllowed()
        // Do not label a result with diagnostic identity/environment that changed mid-read.
        check(port.gmsPackage() == identity && port.environment() == environment)
        println("MCS_QUALIFICATION version=${identity.versionCode} shellUid=${environment.serviceUid} " +
            "user=${environment.currentUser} attemptAllowed=$attemptAllowed")
    }
}
