package io.github.originosfcmguardian.doze.gmsbroker

import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.core.doze.*
import io.github.originosfcmguardian.doze.DozeAtomicJournalStore
import io.github.originosfcmguardian.doze.GmsManualSessionDocumentStore
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** New production transport's no-write path only. Never removes existing GMS exemptions. */
@LargeTest
class GmsManualBrokerNoWriteDeviceTest {
    @Test(timeout = 60_000)
    fun existingFullGmsExemptionDoesNotCreateAuthorityOrStartMutation() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit read-only opt-in is required", args.getString("gmsManualBrokerNoWrite") == "true")
        assumeTrue(BuildConfig.DEBUG && Build.FINGERPRINT == FINGERPRINT)
        val expectedUid = requireNotNull(args.getString("expectedGuardianAppUID")?.toIntOrNull())
        assertEquals(expectedUid, Process.myUid())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val received = CountDownLatch(1)
        val listener = Shizuku.OnBinderReceivedListener { received.countDown() }
        try {
            Shizuku.addBinderReceivedListenerSticky(listener)
            if (!Shizuku.pingBinder()) received.await(3, TimeUnit.SECONDS)
        } finally { Shizuku.removeBinderReceivedListener(listener) }
        assumeTrue("External Shizuku must already be running", Shizuku.pingBinder())

        val names = listOf("gms-global-doze-v1.bin", "gms-doze-broker-v2.bin",
            "gms-global-broker-v2.session-fence", "doze-transaction-v2.bin")
        val directory = context.noBackupFilesDir
        fun snapshot() = names.associateWith { name -> File(directory, name).let { if (it.exists()) it.readBytes() else null } }
        val before = snapshot()
        assertTrue("New production authority must be absent; never clear existing material",
            names.take(3).all { before[it] == null })
        val actual = GmsManualBrokerTransport.get(context)
        var observed = false
        var actualDeath = false
        var mutationStarts = 0
        val noWriteTransport = object : io.github.originosfcmguardian.core.doze.GmsManualBrokerTransport {
            override fun bindInert(operation: GmsGlobalDozeOperation): GmsManualBrokerConnection {
                assertEquals(GmsGlobalDozeOperation.ADD, operation)
                val connection = actual.bindInert(operation)
                return object : GmsManualBrokerConnection by connection {
                    override fun probe(): GmsGlobalDozeObservation {
                        val value = connection.probe()
                        assertEquals(expectedUid, value.identity.guardian.installation.appId)
                        // The production observation codec already validates strict full rows.
                        val rows = value.rawWhitelist.lineSequence().toSet()
                        val appId = value.identity.gms.appId
                        assertTrue("This no-write event requires current full SYSTEM and USER membership",
                            "system,$GMS_GLOBAL_DOZE_PACKAGE,$appId" in rows &&
                                "user,$GMS_GLOBAL_DOZE_PACKAGE,$appId" in rows)
                        observed = true
                        return value
                    }
                    override fun start(proof: GmsDozeBrokerV2Proof): Boolean {
                        mutationStarts++
                        // Even if the current baseline changed, this read-only test cannot mutate.
                        throw AssertionError("Mutation start is forbidden in this event")
                    }
                    override fun activate(proof: GmsDozeBrokerV2Proof): Boolean =
                        throw AssertionError("Authority activation is forbidden in this event")
                    override fun destroyOwnAndObserveDeath(key: GmsDozeBrokerKey): GmsDozeBrokerDeath =
                        connection.destroyOwnAndObserveDeath(key).also { assertEquals(key, it.key); actualDeath = true }
                }
            }
            override fun findOwn(key: GmsDozeBrokerKey) = actual.findOwn(key)
            override fun observedDeath(key: GmsDozeBrokerKey) = actual.observedDeath(key)
        }
        val engine = GmsGlobalDozeManualBrokerEngine(GmsManualSessionDocumentStore(context), noWriteTransport,
            DozeAtomicJournalStore(context), FINGERPRINT)
        val started = System.nanoTime()
        val outcome = engine.applyOnce(System.currentTimeMillis())
        assertEquals(DozeOutcomeCode.NO_WRITE, outcome.code)
        assertNull(outcome.phase)
        assertTrue(observed && actualDeath)
        assertEquals(0, mutationStarts)
        val after = snapshot()
        for (name in names) assertArrayEquals("Authority/journal changed: $name", before[name], after[name])
        instrumentation.sendStatus(0, Bundle().apply {
            putString("guardian.gms.manual", "code=NO_WRITE;qualifiedCurrentMapping=true;fullExemption=true;" +
                "mutationStarts=0;authorityUnchanged=true;ownBinderDeath=true;" +
                "elapsedMillis=${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)};" +
                "writer=CONDITIONAL_NOT_DEVICE_VERIFIED;fcmEffect=NOT_VERIFIED")
        })
    }

    private companion object {
        const val FINGERPRINT = "vivo/PD2609/PD2609:17/CP2A.260605.016_V000L1/260923024147:user/release-keys"
    }
}
