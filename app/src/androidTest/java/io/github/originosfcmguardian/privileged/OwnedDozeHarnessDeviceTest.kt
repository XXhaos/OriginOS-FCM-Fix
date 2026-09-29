package io.github.originosfcmguardian.privileged

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import rikka.shizuku.Shizuku
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in, single-cycle test for the parent's explicitly approved owned package.
 * It never starts through an ordinary instrumentation run. The test journal is
 * app-private and deliberately retained for host inspection, including on failure.
 */
@LargeTest
class OwnedDozeHarnessDeviceTest {
    // The outer watchdog exceeds the bounded cycle so it cannot interrupt cleanup.
    @Test(timeout = 120_000L)
    fun ownedUserWhitelistAdditionIsExternallyReversible() {
        val cycleStarted = SystemClock.elapsedRealtime()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Explicit -e guardianRunOwnedDozeHarness true is required",
            arguments.getString("guardianRunOwnedDozeHarness")?.toBooleanStrictOrNull() == true,
        )
        val expectedUid = arguments.getString("guardianExpectedOwnedDozeUid")?.toIntOrNull()
        val fingerprint = arguments.getString("guardianExpectedFingerprint")
        assumeTrue(
            "An approved fresh user-0 owned UID is required",
            expectedUid != null && expectedUid in 10_000..99_999,
        )
        assumeTrue(
            "Exact known device fingerprint is required",
            fingerprint == KNOWN_FINGERPRINT && Build.FINGERPRINT == KNOWN_FINGERPRINT,
        )
        val uid = requireNotNull(expectedUid)
        val build = requireNotNull(fingerprint)
        assertTrue("Shizuku shell binder must already be available", Shizuku.pingBinder())
        assertEquals(2_000, Shizuku.getUid())
        assertEquals(PackageManager.PERMISSION_GRANTED, Shizuku.checkSelfPermission())

        val journal = OwnedDozeTestJournal(
            File(instrumentation.targetContext.filesDir, "owned-doze-harness-test.json"),
        )
        journal.requireNew()
        val binding = OwnedDozeBinding(instrumentation.targetContext.packageName)
        var addIntentPersisted = false
        var baselineHash: String? = null
        var baselineCount = -1
        try {
            val service = binding.bind()
            assertEquals(2_000, binding.call(3) { service.serviceUid })
            val initial = binding.call(13) { service.probe(uid, build) }
            requireSuccess(initial, "PROBED")
            val raw = requireNotNull(initial.getString("baselineRaw"))
            baselineHash = requireNotNull(initial.getString("sha256"))
            baselineCount = initial.getInt("count", -1)
            assertEquals(0, initial.getInt("targetCount", -1))
            journal.start(uid, build, raw, baselineHash, baselineCount)
            sendSummary(instrumentation, "baseline", initial)

            check(SystemClock.elapsedRealtime() - cycleStarted <= 20_000L) {
                "Insufficient time reserve for bounded add and cleanup"
            }
            journal.record("ADD_INTENT", null)
            addIntentPersisted = true
            val added = binding.call(23) { service.add(uid, build) }
            journal.record("ADD_RESULT", added)
            requireSuccess(added, "ADDED")
            assertEquals(baselineCount + 1, added.getInt("count", -1))
            assertEquals(1, added.getInt("targetCount", -1))
            sendSummary(instrumentation, "added", added)
        } finally {
            val wasInterrupted = Thread.interrupted()
            try {
                if (addIntentPersisted) {
                    // An add IPC timeout still leaves a possible dispatched write.
                    // The service itself refuses cleanup unless its guarded add was dispatched.
                    journal.record("REMOVE_INTENT", null)
                    val service = requireNotNull(binding.serviceOrNull()) { "Harness service died; manual journal review required" }
                    val removed = binding.call(23, uninterruptible = true) { service.remove(uid, build) }
                    journal.record("REMOVE_RESULT", removed)
                    requireSuccess(removed, "RESTORED")
                    assertEquals(baselineHash, removed.getString("sha256"))
                    assertEquals(baselineCount, removed.getInt("count", -1))
                    assertEquals(0, removed.getInt("targetCount", -1))
                    sendSummary(instrumentation, "removed", removed)

                    // DeviceIdle's writer may settle asynchronously. This proves only
                    // exposed membership recovery, not hidden XML or reboot durability.
                    SystemClock.sleep(5_000)
                    val settled = binding.call(13, uninterruptible = true) { service.probe(uid, build) }
                    journal.record("SETTLED_READ", settled)
                    requireSuccess(settled, "PROBED")
                    assertEquals(baselineHash, settled.getString("sha256"))
                    assertEquals(baselineCount, settled.getInt("count", -1))
                    assertEquals(0, settled.getInt("targetCount", -1))
                    sendSummary(instrumentation, "settled", settled)
                    check(SystemClock.elapsedRealtime() - cycleStarted <= 90_000L) {
                        "Owned Doze cycle exceeded 90 seconds"
                    }
                }
            } finally {
                binding.close()
                if (wasInterrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private fun requireSuccess(bundle: Bundle, expectedStatus: String) {
        assertTrue("Harness refused: ${bundle.getString("status")}", bundle.getBoolean("ok", false))
        assertEquals(expectedStatus, bundle.getString("status"))
    }

    private fun sendSummary(
        instrumentation: android.app.Instrumentation,
        phase: String,
        result: Bundle,
    ) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString(
                "guardian.ownedDoze",
                "phase=$phase;status=${result.getString("status")};" +
                    "sha256=${result.getString("sha256")};count=${result.getInt("count", -1)};" +
                    "target=${result.getInt("targetCount", -1)}",
            )
        })
    }

    private companion object {
        const val KNOWN_FINGERPRINT =
            "vivo/PD2609/PD2609:17/CP2A.260605.016_V000L1/260923024147:user/release-keys"
    }
}

private class OwnedDozeBinding(private val packageName: String) : AutoCloseable {
    private val token = UUID.randomUUID().toString().replace("-", "").take(16)
    private val args = Shizuku.UserServiceArgs(
        ComponentName(packageName, OwnedDozeHarnessUserService::class.java.name),
    )
        .daemon(false)
        .tag("guardian-owned-doze-test-$token")
        .processNameSuffix("guardian_doze_$token")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)
    private val connected = CountDownLatch(1)
    private val dead = AtomicBoolean(false)
    @Volatile private var service: IOwnedDozeHarness? = null
    private val death = IBinder.DeathRecipient { dead.set(true) }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IOwnedDozeHarness.Stub.asInterface(binder)
            runCatching { binder.linkToDeath(death, 0) }.onFailure { dead.set(true) }
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            dead.set(true)
            service = null
            connected.countDown()
        }
    }
    private var bound = false

    fun bind(): IOwnedDozeHarness {
        Shizuku.bindUserService(args, connection)
        bound = true
        check(connected.await(5, TimeUnit.SECONDS)) { "Owned harness UserService bind timed out" }
        return requireNotNull(serviceOrNull()) { "Owned harness UserService unavailable" }
    }

    fun serviceOrNull(): IOwnedDozeHarness? =
        service?.takeIf { !dead.get() && it.asBinder().pingBinder() }

    fun <T> call(timeoutSeconds: Long, uninterruptible: Boolean = false, block: () -> T): T {
        check(!dead.get()) { "Owned harness UserService died" }
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "owned-doze-binder").apply { isDaemon = true }
        }
        var interrupted = false
        try {
            val future = executor.submit(Callable(block))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (true) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "Owned harness Binder call timed out" }
                try {
                    return future.get(remaining, TimeUnit.NANOSECONDS)
                } catch (error: InterruptedException) {
                    if (!uninterruptible) throw error
                    interrupted = true
                }
            }
        } finally {
            executor.shutdownNow()
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    override fun close() {
        if (bound) runCatching { Shizuku.unbindUserService(args, connection, true) }
    }
}

private class OwnedDozeTestJournal(file: File) {
    private val base = file
    private val atomic = AtomicFile(file)
    private var document: JSONObject? = null

    fun requireNew() {
        check(!base.exists() && !File(base.path + ".bak").exists() && !File(base.path + ".new").exists()) {
            "Prior owned Doze test journal exists; preserve it for parent review"
        }
    }

    fun start(uid: Int, fingerprint: String, baselineRaw: String, baselineHash: String, count: Int) {
        document = JSONObject()
            .put("schema", 1)
            .put("target", TARGET_PACKAGE)
            .put("uid", uid)
            .put("fingerprint", fingerprint)
            .put("baselineRaw", baselineRaw)
            .put("baselineSha256", baselineHash)
            .put("baselineCount", count)
            .put("outcomes", JSONArray())
        persist()
    }

    fun record(phase: String, result: Bundle?) {
        val document = requireNotNull(document)
        val event = JSONObject().put("phase", phase).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
        if (result != null) {
            event.put("ok", result.getBoolean("ok", false))
            event.put("status", result.getString("status"))
            event.put("sha256", result.getString("sha256"))
            event.put("count", result.getInt("count", -1))
            event.put("targetCount", result.getInt("targetCount", -1))
            if (result.containsKey("addDispatched")) {
                event.put("addDispatched", result.getBoolean("addDispatched"))
            }
        }
        document.getJSONArray("outcomes").put(event)
        persist()
    }

    private fun persist() {
        val bytes = requireNotNull(document).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 128 * 1024) { "Test journal exceeds bound" }
        var output: java.io.FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(bytes)
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            if (output != null) atomic.failWrite(output)
            throw error
        }
        check(atomic.openRead().use { it.readBytes() }.contentEquals(bytes)) {
            "Test journal read-back mismatch"
        }
    }
}
