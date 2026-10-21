package io.github.originosfcmguardian.recovery

import android.system.Os
import io.github.originosfcmguardian.autostart.TargetSelectionCodec
import io.github.originosfcmguardian.privileged.*

/** Fixed isolated fixture only; no commands, app startup, journal, consent or daemon attachment. */
object ReadOnlyStoppedFixtureMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isEmpty() && Os.getuid() == 2000)
        val name = "io.github.originosfcmguardian.signerrotationfixture"
        val port = SystemStoppedIdentityReadPort()
        val expected = checkNotNull(validatedStoppedPackage(port.targetPackage(name), name)).first
        val observed = observeStoppedTarget(1, expected, port) as? StoppedTargetObservation.Known
            ?: error("Fixture evidence unavailable")
        check(observed.identity == expected && observed.qualifiedRecipient && observed.hasVerifiedPackageUid)
        println("STOPPED_FIXTURE stopped=${observed.stopped}")
        print(TargetSelectionCodec.encode(listOf(expected)))
    }
}
