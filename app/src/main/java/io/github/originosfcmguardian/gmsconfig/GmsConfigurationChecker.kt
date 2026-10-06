package io.github.originosfcmguardian.gmsconfig

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import io.github.originosfcmguardian.autostart.TargetPackageProbe
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import io.github.originosfcmguardian.core.GMS_PACKAGE_NAME
import io.github.originosfcmguardian.core.ReadOnlyDetector
import io.github.originosfcmguardian.core.SafeCommand
import io.github.originosfcmguardian.privileged.ShizukuExecutor
import io.github.originosfcmguardian.protection.ReadSessionCoordinator
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext

fun gmsConfigContext(context: Context): GmsConfigContext {
    val identity = runCatching {
        val tuple = TargetPackageProbe(context).identity(GMS_PACKAGE_NAME) ?: return@runCatching null
        @Suppress("DEPRECATION") val info = context.packageManager.getPackageInfo(GMS_PACKAGE_NAME, 0)
        GmsConfigIdentity(tuple, info.longVersionCode, info.lastUpdateTime, requireNotNull(info.applicationInfo).enabled)
    }.getOrNull()
    return GmsConfigContext(Build.FINGERPRINT.take(512), android.os.Process.myUid() / 100_000,
        gmsConfigBootCount(context), identity)
}

internal fun gmsConfigBootCount(context: Context): Int? = runCatching {
    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT).takeIf { it >= 0 }
}.getOrNull()

/** One fixed RPC reader per owner lifetime; a stuck Binder keeps admission occupied. */
internal class GmsConfigurationChecker(private val context: Context) : AutoCloseable {
    private val executor = ShizukuExecutor(context)
    private val store = GmsConfigurationStore(context)
    private val reads = GmsConfigurationReadGate()
    suspend fun readContext(): GmsConfigContext = reads.metadata {
        executor.boundedGmsConfigurationMetadata { gmsConfigContext(context) }
    }
    suspend fun check(automatic: Boolean = false, eligible: () -> Boolean) {
        var before = GmsConfigContext(Build.FINGERPRINT.take(512), android.os.Process.myUid() / 100_000, null, null)
        var wall = System.currentTimeMillis()
        var elapsed = SystemClock.elapsedRealtime()
        GmsConfigurationCheckCoordinator.check(
            read = { reads.checkRead {
                val attemptElapsed = SystemClock.elapsedRealtime()
                before = executor.boundedGmsConfigurationMetadata { gmsConfigContext(context) }
                if (automatic && store.lastAttempt(before.bootCount)?.let {
                    attemptElapsed < it || attemptElapsed - it < GMS_CONFIG_INTERVAL_MILLIS
                } == true) return@checkRead null
                store.saveAttempt(before.bootCount, attemptElapsed)
                ReadSessionCoordinator.withSession {
                    coroutineContext.ensureActive()
                    if (!eligible()) return@withSession null
                    wall = System.currentTimeMillis()
                    elapsed = SystemClock.elapsedRealtime()
                    executor.openSession()
                    try {
                        before = executor.boundedGmsConfigurationMetadata { gmsConfigContext(context) }
                        if (!eligible()) return@withSession null
                        if (before.fingerprint != VERIFIED_AUTOSTART_FINGERPRINT || before.identity == null || before.bootCount == null || before.userId != 0)
                            return@withSession unknownGmsConfig(before, wall, elapsed, GmsConfigFailure.IDENTITY_OR_ENVIRONMENT)
                        val report = ReadOnlyDetector(executor.gmsConfigurationExecutor()).detectGmsConfiguration()
                        val after = executor.boundedGmsConfigurationMetadata { gmsConfigContext(context) }
                        val postUser = executor.gmsConfigurationExecutor().exec(SafeCommand.GetCurrentUser)
                        val user = if (postUser.command == SafeCommand.GetCurrentUser && postUser.completedCleanly)
                            postUser.stdout.trim().takeIf { it.matches(Regex("\\d+")) }?.toIntOrNull() else null
                        if (user != 0) unknownGmsConfig(before, wall, elapsed, GmsConfigFailure.IDENTITY_OR_ENVIRONMENT)
                        else gmsConfigSnapshot(before, after, report, wall, elapsed)
                    } finally { executor.retireGmsConfiguration() }
                }
            } },
            timeoutSnapshot = { unknownGmsConfig(before, wall, elapsed, GmsConfigFailure.TIMED_OUT) },
            failureSnapshot = { unknownGmsConfig(before, wall, elapsed, GmsConfigFailure.READ_FAILED) },
            eligible = eligible,
            publish = store::save,
        )
    }
    override fun close() = executor.closeGmsConfiguration()
}
