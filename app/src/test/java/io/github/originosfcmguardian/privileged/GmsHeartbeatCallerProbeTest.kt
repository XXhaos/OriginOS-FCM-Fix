package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.core.GUARDIAN_PACKAGE_NAME
import io.github.originosfcmguardian.core.gms.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class GmsHeartbeatCallerProbeTest {
    private val boot = "11111111-2222-3333-4444-555555555555"
    private val current = GmsHeartbeatCallerIdentity(10333, 2345, 200, boot)
    private val previous = current.copy(pid = 1234, startTicks = 100)
    private inner class Port : GmsHeartbeatCallerReadPort {
        var environment = GmsProbeEnvironment(0, 2000, boot)
        var owners: List<String>? = listOf(GUARDIAN_PACKAGE_NAME)
        var old: GmsHeartbeatCallerProcess = GmsHeartbeatCallerProcess.Gone
        var afterOld: () -> Unit = {}
        var currentTicks = current.startTicks
        var reads = 0
        override fun environment() = environment
        override fun packagesForUid(uid: Int) = owners
        override fun process(pid: Int, uid: Int, interrupted: () -> Boolean): GmsHeartbeatCallerProcess {
            reads++
            return if (pid == current.pid) GmsHeartbeatCallerProcess.Present(currentTicks)
                else old.also { afterOld() }
        }
    }
    private fun qualify(port: Port, old: GmsHeartbeatCallerIdentity? = previous, stopped: () -> Boolean = { false }) =
        qualifyGmsHeartbeatCaller(current.uid, current.pid, old, port, stopped)
    @Test fun currentOnlyIsFreshAndSameBootAbsenceOrPidReuseProvesOldGone() {
        assertEquals(GmsHeartbeatCallerQualification(current, GmsHeartbeatCallerReason.CURRENT_ONLY), qualify(Port(), null))
        assertEquals(GmsHeartbeatCallerQualification(current, GmsHeartbeatCallerReason.OLD_INSTANCE_GONE), qualify(Port()))
        val reused = Port().apply { old = GmsHeartbeatCallerProcess.Present(previous.startTicks + 1) }
        assertEquals(GmsHeartbeatCallerReason.OLD_INSTANCE_GONE, qualify(reused).reason)
        // The old PID may now be this new caller; differing starttime still proves the old instance gone.
        assertEquals(GmsHeartbeatCallerReason.OLD_INSTANCE_GONE, qualify(Port(), current.copy(startTicks = 100)).reason)
    }
    @Test fun oldAliveUnknownAndDifferentBootNeverQualifyGone() {
        val alive = Port().apply { old = GmsHeartbeatCallerProcess.Present(previous.startTicks) }
        assertEquals(GmsHeartbeatCallerReason.OLD_INSTANCE_ALIVE, qualify(alive).reason)
        val deniedOrMalformed = Port().apply { old = GmsHeartbeatCallerProcess.Unknown }
        assertEquals(GmsHeartbeatCallerReason.UNKNOWN, qualify(deniedOrMalformed).reason)
        val changedBoot = Port()
        assertEquals(GmsHeartbeatCallerReason.BOOT_CHANGED,
            qualify(changedBoot, previous.copy(kernelBootId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")).reason)
        assertEquals(2, changedBoot.reads) // Does not inspect a previous boot's recycled PID.
    }
    @Test fun genuineBinderIdentityRequiresExclusiveGuardianUidAndUserZeroShell() {
        listOf(null, emptyList(), listOf("other.package"), listOf(GUARDIAN_PACKAGE_NAME, "shared.package"),
            listOf(GUARDIAN_PACKAGE_NAME, GUARDIAN_PACKAGE_NAME)).forEach { owners ->
            val port = Port().apply { this.owners = owners }
            assertNull(qualify(port).current); assertEquals(0, port.reads)
        }
        listOf(GmsProbeEnvironment(10, 2000, boot), GmsProbeEnvironment(0, 0, boot)).forEach { env ->
            val port = Port().apply { environment = env }
            assertNull(qualify(port).current); assertEquals(0, port.reads)
        }
        assertNull(qualify(Port(), previous.copy(uid = previous.uid + 1)).current)
        assertNull(qualifyGmsHeartbeatCaller(110333, current.pid, null, Port()) { false }.current)
        assertNull(qualifyGmsHeartbeatCaller(current.uid, 0, null, Port()) { false }.current)
    }
    @Test fun callerBootOwnershipDriftOrCancellationDiscardsEvenConfirmedAbsence() {
        listOf<(Port) -> Unit>(
            { it.currentTicks++ }, { it.environment = it.environment.copy(currentUser = 10) },
            { it.environment = it.environment.copy(bootId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee") },
            { it.owners = listOf("other.package") }).forEach { change ->
            val port = Port(); port.afterOld = { change(port) }
            assertEquals(GmsHeartbeatCallerQualification(null, GmsHeartbeatCallerReason.UNKNOWN), qualify(port))
        }
        var cancelled = false
        val port = Port().apply { afterOld = { cancelled = true } }
        assertNull(qualify(port, stopped = { cancelled }).current)
    }
    @Test fun stuckReadTimesOutKeepsNoQueueAdmissionAndCannotPublishLateGone() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val runner = GmsHeartbeatCallerProbeRunner({
            entered.countDown()
            while (release.count > 0) { try { release.await() } catch (_: InterruptedException) { } }
            Port()
        }, 100)
        try {
            assertEquals(GmsHeartbeatCallerReason.UNKNOWN, runner.read(current.uid, current.pid, previous) { false }.reason)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertNull(runner.read(current.uid, current.pid, previous) { false }.current)
        } finally { release.countDown(); runner.close() }
    }
}
