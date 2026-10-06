package io.github.originosfcmguardian

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.core.gms.GmsUnknown
import io.github.originosfcmguardian.core.gms.SourceCompleteness
import io.github.originosfcmguardian.privileged.ShizukuExecutor
import io.github.originosfcmguardian.privileged.gmsSignerDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Opt-in D04 harness only. No runtime listeners, activation, recovery, settings or broadcast. */
@LargeTest
class GmsConnectionReadDeviceTest {
    @Test(timeout = 40_000)
    fun singleReadMatchesFreshUserZeroInstallation() {
        val optedIn = InstrumentationRegistry.getArguments()
            .getString("guardianRunGmsConnectionRead") == "true"
        if (!optedIn) status("harness=SKIPPED;featureCapability=NOT_VERIFIED;reason=OPT_IN_REQUIRED;write=false")
        assumeTrue("Explicit guardianRunGmsConnectionRead=true is required", optedIn)
        val userZero = Process.myUid() / 100_000 == 0
        if (!userZero) status("harness=SKIPPED;featureCapability=NOT_VERIFIED;reason=WRONG_USER;write=false")
        assumeTrue("User-zero instrumentation is required", userZero)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "guardian-gms-acceptance-read").apply { isDaemon = true }
        }
        val executor = ShizukuExecutor(context)
        var opened = false
        var cleanupCompleted = true
        var before: Identity? = null
        var after: Identity? = null
        var observation: GmsConnectionObservation? = null
        var startedWall = 0L
        var startedElapsed = 0L
        var finishedWall = 0L
        var finishedElapsed = 0L
        try {
            before = bounded(worker, 3_000) { identity(context) }
            if (before != null) {
                // Only this caller opens the session. A timed-out PM task cannot open it later.
                executor.openSession()
                opened = true
                startedWall = System.currentTimeMillis()
                startedElapsed = SystemClock.elapsedRealtime()
                observation = bounded(worker, 18_000) { runBlocking { executor.readGmsConnection() } }
                finishedWall = System.currentTimeMillis()
                finishedElapsed = SystemClock.elapsedRealtime()
                if (observation != null) after = bounded(worker, 3_000) { identity(context) }
            }
        } finally {
            worker.shutdownNow()
            if (opened) {
                // closePermanently contains synchronous Binder teardown. Never wait indefinitely.
                val cleanup = Executors.newSingleThreadExecutor { task ->
                    Thread(task, "guardian-gms-acceptance-cleanup").apply { isDaemon = true }
                }
                try {
                    cleanupCompleted = bounded(cleanup, 2_000) { executor.closePermanently(); true } == true
                } finally { cleanup.shutdownNow() }
            }
        }

        // Do not convert invariant failures to assumptions, or let cleanup overwrite a failure.
        if (before != null && after != null) invariant(before == after)
        val value = observation
        if (value != null) invariant(value.requestId > 0)
        if (value is GmsConnectionObservation.Unknown) {
            invariant(value.reason !in setOf(GmsUnknown.INVALID_REQUEST, GmsUnknown.MALFORMED_RESPONSE,
                GmsUnknown.IDENTITY_CHANGED, GmsUnknown.CANCELLED, GmsUnknown.BUSY))
        }
        if (value is GmsConnectionObservation.Sample) {
            if (before != null) invariant(before.matches(value))
            if (after != null) invariant(after.matches(value))
            invariant(value.observedAtMillis in startedWall..finishedWall)
            invariant(value.elapsedRealtimeMillis in startedElapsed..finishedElapsed && value.isFresh(finishedElapsed))
        }
        if (!cleanupCompleted || before == null || after == null || value == null || value is GmsConnectionObservation.Unknown) {
            val reason = when {
                !cleanupCompleted -> "CLEANUP_NOT_VERIFIED"
                value == null && opened -> "HARNESS_TIMED_OUT"
                before == null || after == null -> "APP_IDENTITY_UNAVAILABLE"
                value is GmsConnectionObservation.Unknown -> value.reason.name
                else -> "HARNESS_TIMED_OUT"
            }
            status("harness=SKIPPED;featureCapability=NOT_VERIFIED;reason=$reason;cleanupCompleted=$cleanupCompleted;write=false")
            assumeTrue("Read-only D04 environment or capability is not verified", false)
            return
        }
        value as GmsConnectionObservation.Sample
        val fullyReadable = value.tcp.completeness == SourceCompleteness.COMPLETE &&
            value.tcp6.completeness == SourceCompleteness.COMPLETE && value.receiverInspectionComplete
        status("harness=PASS;featureCapability=${if (fullyReadable) "READABLE" else "LIMITED"};" +
            "identityMatch=true;fresh=true;tcp=${value.tcp.completeness};tcpMatched=${value.tcp.matched};" +
            "tcp6=${value.tcp6.completeness};tcp6Matched=${value.tcp6.matched};socketObservation=${value.observation};" +
            "receiverQueryComplete=${value.receiverInspectionComplete};receiverCount=${value.receivers.size};" +
            "gcmServiceState=${value.gcmService.state};gcmServiceSource=${value.gcmService.completeness};" +
            "cleanupCompleted=true;d04=NOT_VERIFIED;fcmHealth=NOT_VERIFIED;write=false")
    }

    private data class Identity(val uid: Int, val version: Long, val installed: Long, val updated: Long, val signer: String) {
        fun matches(sample: GmsConnectionObservation.Sample) = uid == sample.uid && version == sample.versionCode &&
            installed == sample.firstInstallTime && updated == sample.lastUpdateTime && signer == sample.signerSha256
    }

    private fun identity(context: Context): Identity? {
        return try {
            val info = context.packageManager.getPackageInfo("com.google.android.gms", PackageManager.GET_SIGNING_CERTIFICATES)
            val app = info.applicationInfo ?: return null
            val signers = info.signingInfo?.apkContentsSigners ?: return null
            invariant(info.packageName == "com.google.android.gms" && app.packageName == "com.google.android.gms")
            invariant(app.uid in 10_000..99_999 && info.longVersionCode >= 0 && info.firstInstallTime > 0 && info.lastUpdateTime > 0)
            Identity(app.uid, info.longVersionCode, info.firstInstallTime, info.lastUpdateTime,
                gmsSignerDigest(signers.map { it.toByteArray() }))
        } catch (_: PackageManager.NameNotFoundException) { null }
        catch (_: SecurityException) { null }
    }

    private fun <T> bounded(worker: java.util.concurrent.ExecutorService, millis: Long, action: () -> T): T? {
        val future = worker.submit(Callable(action))
        return try { future.get(millis, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { future.cancel(true); null }
        catch (_: InterruptedException) { future.cancel(true); Thread.currentThread().interrupt(); null }
        catch (_: ExecutionException) { fail("Unexpected read-only acceptance harness failure"); null }
    }

    private fun invariant(valid: Boolean) = assertTrue("Read-only GMS acceptance invariant failed", valid)
    private fun status(summary: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("guardian.gms.read", summary) })
}
