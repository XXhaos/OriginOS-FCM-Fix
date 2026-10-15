package io.github.originosfcmguardian.daemon

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Assert.*
import org.junit.Test

class AppDaemonDomainExportTest {
    private class Port : AppDaemonDomainReadPort {
        val files = mutableMapOf<Pair<DaemonDomainRole, AppDaemonArtifact>, ByteArray>()
        var targets: String? = null
        var failure = false
        val reads = mutableListOf<Pair<DaemonDomainRole, AppDaemonArtifact>>()
        override fun read(role: DaemonDomainRole, artifact: AppDaemonArtifact): ByteArray? {
            if (failure) error("unreadable")
            reads += role to artifact
            return files[role to artifact]
        }
        override fun selectedTargets() = targets
    }

    @Test fun allRolesAreExplicitAndEveryOriginalIncludingEmptyMarkersIsPreservedExactly() {
        val port = Port()
        for ((index, role) in AppDaemonDomainExport.FILE_NAMES.keys.withIndex())
            port.files[role to AppDaemonArtifact.ORIGINAL] = if (role.maximumBytes == 1) byteArrayOf()
                else byteArrayOf(0, index.toByte(), -1, 13, 10)
        port.targets = "exact\r\n选择\u0000"
        val exported = AppDaemonDomainExport(port).domains()
        assertEquals(DaemonDomainRole.entries.toSet(), exported.keys)
        for (role in AppDaemonDomainExport.FILE_NAMES.keys)
            assertArrayEquals(port.files[role to AppDaemonArtifact.ORIGINAL], exported[role])
        assertArrayEquals(port.targets!!.toByteArray(Charsets.UTF_8), exported[DaemonDomainRole.SELECTION])
        assertNull(exported[DaemonDomainRole.HEARTBEAT_DAEMON_OWNER])
        assertNull(exported[DaemonDomainRole.HEARTBEAT_DERIVATION])
        assertNull(exported[DaemonDomainRole.INTERRUPTED_ARTIFACTS])
        exported[DaemonDomainRole.AUTHORITY]!![0] = 99
        assertEquals(0.toByte(), port.files[DaemonDomainRole.AUTHORITY to AppDaemonArtifact.ORIGINAL]!![0])
        assertEquals(AppDaemonDomainExport.FILE_NAMES.size * 3, port.reads.size)
        assertFalse(AppDaemonDomainExport.FILE_NAMES.values.any { it.contains("lifecycle") || it.contains("resource") || it.endsWith(".lock") })
    }

    @Test fun backupAndNewArePreservedAsInterruptedEvidenceWithoutRestoringOrReplacingOriginal() {
        val port = Port()
        val original = byteArrayOf(1, 2, 3)
        val backup = byteArrayOf(9, 8)
        port.files[DaemonDomainRole.AUTHORITY to AppDaemonArtifact.ORIGINAL] = original
        port.files[DaemonDomainRole.AUTHORITY to AppDaemonArtifact.BACKUP] = backup
        port.files[DaemonDomainRole.HEARTBEAT to AppDaemonArtifact.NEW] = byteArrayOf()
        val exported = AppDaemonDomainExport(port).domains()
        assertArrayEquals(original, exported[DaemonDomainRole.AUTHORITY])
        assertNull(exported[DaemonDomainRole.HEARTBEAT])
        DataInputStream(ByteArrayInputStream(exported[DaemonDomainRole.INTERRUPTED_ARTIFACTS]!!)).use { input ->
            assertEquals(0x47494131, input.readInt()); assertEquals(2, input.readInt())
            assertEquals(DaemonDomainRole.AUTHORITY.ordinal, input.readInt())
            assertEquals(AppDaemonArtifact.BACKUP.ordinal, input.readInt())
            assertEquals(backup.size, input.readInt()); assertArrayEquals(backup, ByteArray(backup.size).also(input::readFully))
            assertEquals(DaemonDomainRole.HEARTBEAT.ordinal, input.readInt())
            assertEquals(AppDaemonArtifact.NEW.ordinal, input.readInt()); assertEquals(0, input.readInt())
            assertEquals(0, input.available())
        }
        assertArrayEquals(backup, port.files[DaemonDomainRole.AUTHORITY to AppDaemonArtifact.BACKUP])
    }

    @Test fun absentAndEmptySelectionRemainDistinctAndPreparedDigestUsesRawAuthority() {
        val port = Port()
        assertNull(AppDaemonDomainExport(port).domains()[DaemonDomainRole.SELECTION])
        port.targets = ""
        assertArrayEquals(byteArrayOf(), AppDaemonDomainExport(port).domains()[DaemonDomainRole.SELECTION])
        port.files[DaemonDomainRole.AUTHORITY to AppDaemonArtifact.ORIGINAL] = byteArrayOf(0, -1, 10)
        val prepared = AppDaemonDomainExport(port).prepared(RootFixtures.owner, "migration")
        assertEquals(DaemonControlMode.PREPARED, prepared.control.mode)
        assertEquals(prepared.authorityDigest(), prepared.control.authorityDigest)
        assertArrayEquals(byteArrayOf(0, -1, 10), prepared.bytes(DaemonDomainRole.AUTHORITY))
    }

    @Test fun unreadableOversizeUnknownOrExcessiveInterruptedDataNeverBecomesAbsence() {
        val port = Port().apply { failure = true }
        RootFixtures.fails { AppDaemonDomainExport(port).domains() }
        port.failure = false
        port.files[DaemonDomainRole.AUTHORITY_INITIALIZED to AppDaemonArtifact.ORIGINAL] = byteArrayOf(1, 2)
        RootFixtures.fails { AppDaemonDomainExport(port).domains() }
        port.files.clear()
        port.targets = "x".repeat(DaemonDomainRole.SELECTION.maximumBytes + 1)
        RootFixtures.fails { AppDaemonDomainExport(port).domains() }
        port.targets = "\uD800"
        RootFixtures.fails { AppDaemonDomainExport(port).domains() }
        port.targets = null
        port.files[DaemonDomainRole.RECOVERY_COUNTS to AppDaemonArtifact.BACKUP] = ByteArray(300_000)
        RootFixtures.fails { AppDaemonDomainExport(port).domains() }
        port.files.clear()
        RootFixtures.fails { AppDaemonDomainExport(port).prepared(RootFixtures.owner, "migration") }
    }
}
