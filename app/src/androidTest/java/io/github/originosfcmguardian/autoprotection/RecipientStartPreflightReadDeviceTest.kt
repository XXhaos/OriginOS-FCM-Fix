package io.github.originosfcmguardian.autoprotection

import android.content.Intent
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.autostart.TargetPackageProbe
import io.github.originosfcmguardian.autostart.TargetSelectionStore
import io.github.originosfcmguardian.autostart.qualifiedRecoveryLauncher
import io.github.originosfcmguardian.autostart.effectiveRecoveryComponentEnabled
import io.github.originosfcmguardian.privileged.ShizukuRuntime
import io.github.originosfcmguardian.recovery.AndroidRecoveryJournal
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Fixed, opt-in read-only diagnosis. Never invokes protection start/grant/recovery or writes a store. */
@LargeTest
class RecipientStartPreflightReadDeviceTest {
    @Suppress("DEPRECATION")
    @Test(timeout = 60_000)
    fun fixedRecipientStartPrerequisites() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("guardianRunRecipientStartPreflightRead") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val probe = TargetPackageProbe(context)
        val runtime = ShizukuRuntime(context)
        runtime.start()
        fun evidence(block: () -> Any?): String = runCatching(block).fold(
            { it?.toString() ?: "unknown" }, { "unknown(${it.javaClass.simpleName})" })
        try {
            val selected = runCatching { TargetSelectionStore(context).load() }
            val journal = runCatching { AndroidRecoveryJournal.open(context).load() }
            val authority = runCatching { ProtectionConsentStore(AtomicProtectionAuthorityStorage(context)).readSnapshot() }
            val shared = listOf(
                "shellReady=${evidence { ProtectionStartupProbe.privilegeEvidence().shellReady }}",
                "selectionReadable=${selected.isSuccess}",
                "journalClear=${evidence { journal.getOrThrow().latest?.requiresReconcile != true }}",
                "authorityReadable=${authority.isSuccess}",
                "authorityMode=${evidence { authority.getOrThrow()?.mode?.name }}",
                "authorityGeneration=${evidence { authority.getOrThrow()?.generation }}",
                "authorityTargets=${evidence { authority.getOrThrow()?.consents()?.size }}",
                "authorityReadError=${authority.exceptionOrNull()?.javaClass?.simpleName ?: "none"}",
                "write=false",
            ).joinToString(";")
            instrumentation.sendStatus(0, Bundle().apply { putString("guardian.start.preflight.shared", shared) })
            var reports = 0
            for (packageName in listOf("org.telegram.messenger", "fr.smarquis.fcm")) {
                val stored = selected.getOrNull()?.singleOrNull { it.packageName == packageName }
                val current = probe.identity(packageName)
                val application = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }
                val launchers = runCatching {
                    context.packageManager.queryIntentActivities(
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName), 0)
                }
                val row = listOf(
                    "package=$packageName",
                    "selected=${stored != null}",
                    "identityKnown=${current != null}",
                    "identityMatches=${stored != null && current == stored}",
                    "candidate=${evidence { stored != null && probe.isRecoveryCandidate(stored) }}",
                    "installed=${evidence { application.getOrThrow().flags and ApplicationInfo.FLAG_INSTALLED != 0 }}",
                    "enabled=${evidence { application.getOrThrow().enabled }}",
                    "system=${evidence { application.getOrThrow().flags and ApplicationInfo.FLAG_SYSTEM != 0 }}",
                    "updatedSystem=${evidence { application.getOrThrow().flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 }}",
                    "persistent=${evidence { application.getOrThrow().flags and ApplicationInfo.FLAG_PERSISTENT != 0 }}",
                    "launcherCount=${evidence { launchers.getOrThrow().size }}",
                    "enabledExportedLauncher=${evidence { launchers.getOrThrow().any { resolved ->
                        val activity = resolved.activityInfo
                        activity != null && stored != null && qualifiedRecoveryLauncher(packageName, stored.uid,
                            activity.packageName, activity.applicationInfo?.uid, activity.enabled, activity.exported,
                            activity.applicationInfo?.enabled) {
                            context.packageManager.getComponentEnabledSetting(ComponentName(activity.packageName, activity.name))
                        }
                    } }}",
                    "exclusiveUid=${evidence { stored != null && context.packageManager.getPackagesForUid(stored.uid)?.toList() == listOf(packageName) }}",
                    "stopped=${evidence { application.getOrThrow().flags and ApplicationInfo.FLAG_STOPPED != 0 }}",
                    "write=false",
                ).joinToString(";")
                instrumentation.sendStatus(0, Bundle().apply { putString("guardian.start.preflight.recipient", row) })
                launchers.getOrNull()?.take(16)?.forEachIndexed { index, resolved ->
                    val activity = resolved.activityInfo
                    val launcher = listOf(
                        "package=$packageName", "index=$index", "activityKnown=${activity != null}",
                        "packageMatches=${activity?.packageName == packageName}",
                        "manifestActivityEnabled=${activity?.enabled}",
                        "effectiveActivityEnabled=${evidence { requireNotNull(activity)
                            effectiveRecoveryComponentEnabled(activity.enabled,
                                context.packageManager.getComponentEnabledSetting(ComponentName(activity.packageName, activity.name))) }}",
                        "exported=${activity?.exported}",
                        "appUidMatches=${stored != null && activity?.applicationInfo?.uid == stored.uid}",
                        "applicationEnabled=${activity?.applicationInfo?.enabled}",
                        "isAlias=${activity?.targetActivity != null}",
                        "componentEnabledSetting=${evidence { requireNotNull(activity)
                            context.packageManager.getComponentEnabledSetting(ComponentName(activity.packageName, activity.name)) }}",
                        "applicationEnabledSetting=${evidence { context.packageManager.getApplicationEnabledSetting(packageName) }}",
                        "write=false",
                    ).joinToString(";")
                    instrumentation.sendStatus(0, Bundle().apply { putString("guardian.start.preflight.launcher", launcher) })
                }
                reports++
            }
            assertEquals(2, reports)
        } finally { runtime.close() }
    }
}
