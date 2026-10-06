package io.github.originosfcmguardian.doze

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class DozeOwnerReadOnlySummaryTest {
    @Test fun normalizedDigestKeepsSourcesAndProfilesWithoutExportingPackageRows() {
        val raw = "Packages:\n" + DozeOwnerCandidatesTest.record("new.pkg", compat = "old.pkg") +
            "Renamed packages:\n  new.pkg,old.pkg\nHidden system packages:\n" +
            DozeOwnerCandidatesTest.record("new.pkg", compat = "old.pkg", retained = true)
        val summary = summarizeDozeOwnerReadOnly(parse(raw), 100, 110, 10)
        assertEquals(1, summary.activeRecords)
        assertEquals(1, summary.hiddenSystemRecords)
        assertEquals(1, summary.retainedRecords)
        assertEquals(1, summary.canonicalPackages)
        assertEquals(1, summary.renameRows)
        assertEquals(1, summary.reportedUsers)
        assertEquals("UNKNOWN", summary.inventory)
        assertTrue(summary.isValidReadOnlySummary())
        assertFalse(summary.toString().contains("new.pkg"))
        assertFalse(summary.toString().contains("old.pkg"))
    }

    @Test fun recordOrderAndVolatileObjectHashesDoNotChangeNormalizedDigest() {
        val a = DozeOwnerCandidatesTest.record("a.pkg", shared = true)
        val b = DozeOwnerCandidatesTest.record("b.pkg", 10_124)
        val one = summarizeDozeOwnerReadOnly(parse("Packages:\n$a$b"), 1, 2, 1)
        val two = summarizeDozeOwnerReadOnly(parse("Packages:\n$b$a".replace("abcdef", "fedcba")), 3, 4, 1)
        assertEquals(one.normalizedSha256, two.normalizedSha256)
        val changed = summarizeDozeOwnerReadOnly(parse("Packages:\n$a$b".replace("hidden=false", "hidden=true")), 1, 2, 1)
        assertNotEquals(one.normalizedSha256, changed.normalizedSha256)
    }

    @Test fun malformedAndUnboundedSummaryShapesReject() {
        val base = summarizeDozeOwnerReadOnly(parse("Packages:\n" + DozeOwnerCandidatesTest.record()), 1, 2, 1)
        for (value in listOf(base.copy(activeRecords = -1), base.copy(hiddenSystemRecords = Int.MAX_VALUE),
            base.copy(canonicalPackages = 0), base.copy(normalizedSha256 = "raw package dump"),
            base.copy(reportedUsers = 65), base.copy(elapsedMillis = 40_001), base.copy(finishedAtMillis = 0))) {
            assertFalse(value.isValidReadOnlySummary())
        }
        assertFalse(base.copy(code = DozeOwnerReadOnlyCode.REJECTED).isValidReadOnlySummary())
        assertTrue(DozeOwnerReadOnlySummary(1, 2, 1, DozeOwnerReadOnlyCode.REJECTED).isValidReadOnlySummary())
    }

    @Test fun exactReportedGuardianUidRejectsAnotherPackageSameUidOrCallerClaims() {
        val raw = "package:android uid:1000\npackage:$GUARDIAN_DOZE_READ_PACKAGE uid:10123\n"
        assertTrue(reportedGuardianUidIsExact(raw, 10_123))
        assertFalse(reportedGuardianUidIsExact(raw, 10_124))
        assertFalse(reportedGuardianUidIsExact(raw, 110_123))
        assertFalse(reportedGuardianUidIsExact(raw + "package:other.pkg uid:10123\n", 10_123))
        assertFalse(reportedGuardianUidIsExact(raw + "Permission Denial: denied\n", 10_123))
        assertFalse(reportedGuardianUidIsExact(raw + "package:android uid:1000\n", 10_123))
        assertFalse(reportedGuardianUidIsExact("", 10_123))
    }

    private fun parse(raw: String) = DozeOwnerCandidateParser.parse(ByteArrayInputStream(raw.toByteArray()), setOf(0))
}
