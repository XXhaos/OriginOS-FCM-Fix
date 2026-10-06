package io.github.originosfcmguardian.doze

import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.system.Os
import androidx.annotation.Keep
import io.github.originosfcmguardian.BuildConfig
import java.util.concurrent.atomic.AtomicInteger

/** Standalone debug shell service; no writer, journal, protection, heartbeat or automatic caller. */
class DozeOwnerReadOnlyUserService : IDozeOwnerReadOnly.Stub {
    private val session = DozeOwnerReadOnlySession()
    private val caller = AtomicInteger(-1)
    private val expiry = DozeServiceExpiry(Os.getuid(), ::terminateShell)

    constructor()
    @Keep constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun read(clientLifetime: IBinder?): Bundle {
        val callerUid = Binder.getCallingUid()
        var linkedLifetime: IBinder? = null
        val death = IBinder.DeathRecipient { session.cancel() }
        try {
            val summary = session.read { scope ->
                check(BuildConfig.DEBUG && Os.getuid() == 2_000 && Build.FINGERPRINT == DOZE_READ_ONLY_FINGERPRINT)
                check(callerUid in 10_000..99_999)
                val lifetime = requireNotNull(clientLifetime)
                lifetime.linkToDeath(death, 0)
                linkedLifetime = lifetime
                scope.requireActive()
                val runner = DozeOwnerReadOnlyPrerequisites(scope)
                // This is a fresh caller-identity check, not an inventory or shared-owner proof.
                val packages = runner.readCallerPackages()
                check(reportedGuardianUidIsExact(packages, callerUid))
                scope.requireActive()
                check(caller.compareAndSet(-1, callerUid) || caller.get() == callerUid)
                val reportedUsers = parseAndroidUsers(runner.readReportedUsers())
                scope.requireActive()
                DozeOwnerCandidateReader(scope::mayContinue, scope::remainingMillis, scope::onProcess)
                    .read(reportedUsers)
            }
            return summary.toBundle()
        } finally {
            // Keep caller death cancellation through summary creation and IPC projection.
            linkedLifetime?.let { runCatching { it.unlinkToDeath(death, 0) } }
        }
    }

    override fun readEffectiveOwners(clientLifetime: IBinder?): Bundle {
        val callerUid = Binder.getCallingUid()
        var linkedLifetime: IBinder? = null
        var port: SystemDozeEffectiveOwnerReadPort? = null
        val death = IBinder.DeathRecipient { session.cancel() }
        try {
            return session.readEffective({ scope ->
                check(BuildConfig.DEBUG && Os.getuid() == 2_000 && Build.FINGERPRINT == DOZE_READ_ONLY_FINGERPRINT)
                check(callerUid in 10_000..99_999)
                val lifetime = requireNotNull(clientLifetime)
                lifetime.linkToDeath(death, 0)
                linkedLifetime = lifetime
                scope.requireActive()
                val prerequisites = DozeOwnerReadOnlyPrerequisites(scope)
                check(reportedGuardianUidIsExact(prerequisites.readCallerPackages(), callerUid))
                scope.requireActive()
                check(caller.compareAndSet(-1, callerUid) || caller.get() == callerUid)
                val reportedUsers = parseAndroidUsers(prerequisites.readReportedUsers())
                val reader = DozeOwnerCandidateReader(scope::mayContinue, scope::remainingMillis, scope::onProcess)
                val source = SystemDozeEffectiveOwnerReadPort({ reader.read(reportedUsers) }, scope::requireActive,
                    scope::remainingMillis, session::cancel)
                port = source
                DozeEffectiveOwnerMapping(source, DOZE_READ_ONLY_FINGERPRINT, scope::requireActive).read()
            }, { port?.uncertainBinderExit == true }).toBundle()
        } finally {
            port?.close()
            linkedLifetime?.let { runCatching { it.unlinkToDeath(death, 0) } }
        }
    }

    override fun cancel() {
        if (trustedControlCaller()) session.cancel()
    }

    override fun destroy() {
        // Shizuku's own shell teardown is also allowed to destroy this independent shell process.
        if (Os.getuid() == 2_000 && (Binder.getCallingUid() == 2_000 || trustedControlCaller())) expiry.terminateNow()
    }

    private fun trustedControlCaller(): Boolean = BuildConfig.DEBUG && Os.getuid() == 2_000 &&
        Build.FINGERPRINT == DOZE_READ_ONLY_FINGERPRINT && Binder.getCallingUid() == caller.get() && caller.get() in 10_000..99_999

    private fun terminateShell() { session.cancel(); System.exit(0) }
}

internal fun DozeOwnerReadOnlySummary.toBundle() = Bundle().apply {
    putLong("requestedAtMillis", requestedAtMillis)
    putLong("finishedAtMillis", finishedAtMillis)
    putLong("elapsedMillis", elapsedMillis)
    putString("code", code.name)
    putBoolean("parsedComplete", parsedComplete)
    putString("inventory", inventory)
    putInt("activeRecords", activeRecords)
    putInt("hiddenSystemRecords", hiddenSystemRecords)
    putInt("retainedRecords", retainedRecords)
    putInt("canonicalPackages", canonicalPackages)
    putInt("renameRows", renameRows)
    putInt("reportedUsers", reportedUsers)
    putString("normalizedSha256", normalizedSha256)
    putString("normalizedSchema", normalizedSchema)
}
