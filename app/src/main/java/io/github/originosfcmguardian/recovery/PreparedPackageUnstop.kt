package io.github.originosfcmguardian.recovery

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.TargetSelectionCodec
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

internal enum class PackageUnstopMode { COMMAND, BINDER_CHILD }

/** One immutable operation-local choice. Once start is entered it can never select/replay another. */
internal class PreparedPackageUnstop private constructor(
    identity: SelectedTargetIdentity, val recipient: Boolean, val mode: PackageUnstopMode,
    argv: List<String>, val classpath: String?, private val launch: () -> Process,
) {
    val identity = identity.copy(signerSha256 = Collections.unmodifiableList(identity.signerSha256.toList()))
    val argv: List<String> = Collections.unmodifiableList(argv.toList())
    private val started = AtomicBoolean()
    fun start(): Process { check(started.compareAndSet(false, true)); return launch() }

    companion object {
        internal fun create(identity: SelectedTargetIdentity, recipient: Boolean, mode: PackageUnstopMode,
            classpath: String? = null,
            launcher: (List<String>, String?) -> (() -> Process) = ::packageUnstopLauncher): PreparedPackageUnstop {
            require(recipient || identity.packageName == "org.telegram.messenger")
            require(identity.signerSha256.size <= 16)
            val frozen = identity.copy(signerSha256 = Collections.unmodifiableList(identity.signerSha256.toList()))
            val arguments = if (mode == PackageUnstopMode.COMMAND) stoppedRecoveryArgv(frozen) else {
                require(!classpath.isNullOrEmpty())
                listOf("/system/bin/app_process", "/system/bin", PACKAGE_STOPPED_STATE_MAIN,
                    if (recipient) "clear-recipient" else "clear-legacy", TargetSelectionCodec.encode(listOf(frozen)))
            }
            val argv = Collections.unmodifiableList(arguments.toList())
            // Construct the builder before the owner/cancellation lock. Only start runs under it.
            return PreparedPackageUnstop(frozen, recipient, mode, argv, classpath, launcher(argv, classpath))
        }
    }
}

private fun packageUnstopLauncher(argv: List<String>, classpath: String?): () -> Process {
    val builder = ProcessBuilder(argv).redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
    if (classpath != null) builder.environment()["CLASSPATH"] = classpath
    return { builder.start() }
}

/** Selection is before claim and cannot be revisited from the launch callback. */
internal fun preparePackageUnstop(identity: SelectedTargetIdentity, recipient: Boolean, active: () -> Boolean,
    command: ((() -> Boolean) -> Boolean) = PackageUnstopCapability::read,
    source: ((() -> Boolean) -> String?) = PackageUnstopCapability::guardianApk,
    probe: (String, () -> Boolean) -> Boolean = PackageUnstopCapability::binderProbe,
    launcher: (List<String>, String?) -> (() -> Process) = ::packageUnstopLauncher): PreparedPackageUnstop? {
    if (!active()) return null
    if (command(active)) return if (active()) PreparedPackageUnstop.create(identity, recipient,
        PackageUnstopMode.COMMAND, launcher = launcher) else null
    if (!active()) return null
    val apk = source(active) ?: return null
    if (!active() || !probe(apk, active) || !active()) return null
    return PreparedPackageUnstop.create(identity, recipient, PackageUnstopMode.BINDER_CHILD, apk, launcher)
}
