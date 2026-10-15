package io.github.originosfcmguardian.daemon

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

internal enum class AppDaemonArtifact(val suffix: String) { ORIGINAL(""), BACKUP(".bak"), NEW(".new") }

/** Fixed-role raw reads only. Null means confirmed absence; unreadable/unknown/oversize must throw. */
internal interface AppDaemonDomainReadPort {
    fun read(role: DaemonDomainRole, artifact: AppDaemonArtifact): ByteArray?
    /** Exact SharedPreferences string for selected_target_identities; absent is null, empty is empty. */
    fun selectedTargets(): String?
}

/**
 * Export only after the App owner is durably quiesced. No domain codec is called: even marker and
 * legacy bytes remain exact. Interrupted AtomicFile artifacts are retained separately and block
 * daemon execution; this exporter never calls AtomicFile.openRead (which can restore a backup).
 */
internal class AppDaemonDomainExport(private val port: AppDaemonDomainReadPort) {
    fun domains(): Map<DaemonDomainRole, ByteArray?> {
        val result = DaemonDomainRole.entries.associateWith<DaemonDomainRole, ByteArray?> { null }.toMutableMap()
        val interrupted = ByteArrayOutputStream()
        var artifactCount = 0
        var totalBytes = 0L
        DataOutputStream(interrupted).use { output ->
            for (role in FILE_NAMES.keys) {
                for (artifact in AppDaemonArtifact.entries) {
                    val bytes = port.read(role, artifact)?.copyOf() ?: continue
                    require(bytes.size <= role.maximumBytes) { "Oversize App domain: $role" }
                    totalBytes += bytes.size
                    require(totalBytes <= DaemonProtectionRootCodec.MAX_BYTES - 4096L)
                    if (artifact == AppDaemonArtifact.ORIGINAL) result[role] = bytes
                    else {
                        // Stable fixed-role/suffix tags, plus exact bytes, rather than arbitrary paths.
                        output.writeInt(role.ordinal); output.writeInt(artifact.ordinal)
                        output.writeInt(bytes.size); output.write(bytes)
                        artifactCount++
                        require(interrupted.size() <= DaemonDomainRole.INTERRUPTED_ARTIFACTS.maximumBytes - 8)
                    }
                }
            }
        }
        val selection = port.selectedTargets()?.let { text ->
            require(text.length <= DaemonDomainRole.SELECTION.maximumBytes)
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
            ByteArray(encoded.remaining()).also(encoded::get)
        }
        require(selection == null || selection.size <= DaemonDomainRole.SELECTION.maximumBytes)
        result[DaemonDomainRole.SELECTION] = selection
        if (artifactCount > 0) {
            result[DaemonDomainRole.INTERRUPTED_ARTIFACTS] = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.writeInt(0x47494131); output.writeInt(artifactCount)
                    output.write(interrupted.toByteArray())
                }
            }.toByteArray()
        }
        // All future daemon-only roles start absent; none can be imported as native provenance.
        require(result.values.sumOf { it?.size?.toLong() ?: 0 } <= DaemonProtectionRootCodec.MAX_BYTES - 4096L)
        return result
    }

    fun prepared(owner: DaemonOwnerKey, migrationId: String): DaemonProtectionRootSnapshot {
        val domains = domains()
        val authority = requireNotNull(domains[DaemonDomainRole.AUTHORITY]) { "App authority absent" }
        return DaemonProtectionRootSnapshot(DaemonControlRecord(owner, 1,
            DaemonProtectionRootSnapshot.digest(authority), DaemonControlMode.PREPARED, migrationId), domains)
    }

    companion object {
        /** Exact production noBackup storage names. Locks, lifecycle/resource history are excluded. */
        val FILE_NAMES: Map<DaemonDomainRole, String> = java.util.Collections.unmodifiableMap(linkedMapOf(
            DaemonDomainRole.AUTHORITY to "automatic-protection-authority-v1.bin",
            DaemonDomainRole.AUTHORITY_INITIALIZED to "automatic-protection-authority-v1.initialized",
            DaemonDomainRole.AUTHORITY_STOP_PENDING to "automatic-protection-authority-v1.stop-pending",
            DaemonDomainRole.STOPPED_JOURNAL to "stopped-recovery-v1.bin",
            DaemonDomainRole.STOPPED_BLOCKED to "stopped-recovery-v1.bin.blocked",
            DaemonDomainRole.RECOVERY_COUNTS to "confirmed-recovery-counts-v1.bin",
            DaemonDomainRole.COUNTS_UNAVAILABLE to "confirmed-recovery-counts-v1.bin.unavailable",
            DaemonDomainRole.FCM_PROTECTION to "fcm-protection-v1.bin",
            DaemonDomainRole.FCM_PROTECTION_HALT to "fcm-protection-off-v1",
            DaemonDomainRole.FCM_RECOVERY to "fcm-recovery-trial-v1.bin",
            DaemonDomainRole.FCM_CONSUMED to "fcm-recovery-trial-consumed-v1.bin",
            DaemonDomainRole.FCM_MCS_CONSUMED to "fcm-recovery-mcs-consumed-v1.bin",
            DaemonDomainRole.FCM_RECOVERY_HALT to "fcm-recovery-trial-off-v1",
            DaemonDomainRole.FCM_EPISODES to "fcm-recovery-episodes-v1.bin",
            DaemonDomainRole.HEARTBEAT to "gms-auto-heartbeat-v1.bin",
            DaemonDomainRole.HEARTBEAT_INITIALIZED to "gms-auto-heartbeat-v1.initialized",
            DaemonDomainRole.HEARTBEAT_HALT to "gms-auto-heartbeat-v1.halt",
            DaemonDomainRole.HEARTBEAT_PENDING to "gms-auto-heartbeat-v1.pending",
            DaemonDomainRole.LEGACY_BUDGET to "automatic-attempt-budget-v1.bin",
            DaemonDomainRole.LEGACY_BUDGET_INITIALIZED to "automatic-attempt-budget-v1.initialized",
        ))
    }
}

/** Android filesystem adapter, isolated from the pure export/protocol logic used by host tests. */
internal class AndroidAppDaemonDomainReadPort(context: Context) : AppDaemonDomainReadPort {
    private val app = context.applicationContext
    private val directory = app.noBackupFilesDir
    private val selectionFile = File(app.applicationInfo.dataDir, "shared_prefs/autostart_target_selection_v1.xml")

    override fun selectedTargets(): String? {
        // Creating SharedPreferences can itself restore/delete .bak. Reject interrupted selection
        // storage BEFORE asking Android to load it; do not normalize an older selection silently.
        checkSelectionArtifacts()
        return app.getSharedPreferences("autostart_target_selection_v1", Context.MODE_PRIVATE)
            .getString("selected_target_identities", null).also { checkSelectionArtifacts() }
    }
    private fun checkSelectionArtifacts() {
        check(statOrMissing(File(selectionFile.path + ".bak")) == null &&
            statOrMissing(File(selectionFile.path + ".new")) == null) { "Interrupted selected-app preferences" }
    }

    override fun read(role: DaemonDomainRole, artifact: AppDaemonArtifact): ByteArray? {
        val name = requireNotNull(AppDaemonDomainExport.FILE_NAMES[role]) { "Not an App file role" }
        val file = File(directory, name + artifact.suffix)
        val before = statOrMissing(file) ?: return null
        check(before.st_uid == Os.getuid() && before.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG &&
            before.st_nlink == 1L && before.st_size in 0..role.maximumBytes.toLong())
        return FileInputStream(file).use { input ->
            val opened = Os.fstat(input.fd)
            check(same(before, opened))
            val bytes = ByteArray(before.st_size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                check(count > 0) { "App domain truncated" }; offset += count
            }
            check(input.read() == -1 && same(opened, Os.fstat(input.fd)) && same(opened, requireNotNull(statOrMissing(file))))
            bytes
        }
    }

    private fun same(a: StructStat, b: StructStat): Boolean = a.st_dev == b.st_dev && a.st_ino == b.st_ino &&
        a.st_uid == b.st_uid && a.st_mode == b.st_mode && a.st_nlink == b.st_nlink &&
        a.st_size == b.st_size && a.st_mtime == b.st_mtime && a.st_ctime == b.st_ctime

    private fun statOrMissing(file: File): StructStat? = try { Os.lstat(file.absolutePath) }
        catch (failure: ErrnoException) { if (failure.errno == OsConstants.ENOENT) null else throw failure }
}
