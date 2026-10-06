package io.github.originosfcmguardian.doze

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class DozeOwnerVersionCodeTest {
    @Test fun actualMetadataParsesLongVersionAndChangesStructureDigest() {
        val first = parse("    versionCode=263635035 minSdk=35 targetSdk=37\n")
        val second = parse("    versionCode=263635036 minSdk=35 targetSdk=37\n")
        assertEquals(263635035L, first.records.single().versionCode)
        assertNotEquals(dozeOwnerStructureDigest(first), dozeOwnerStructureDigest(second))
    }
    @Test fun oldFixturesRemainParsedWithUnknownVersion() {
        val value = parse("")
        assertNull(value.records.single().versionCode)
        assertFalse(value.inventoryQualified)
        assertFalse(value.matchingRawAppId(10123).exclusiveOwner)
    }
    @Test fun zeroAndLongMaximumAreLexicallyAccepted() {
        assertEquals(0L, parse("    versionCode=0 minSdk=0 targetSdk=0\n").records.single().versionCode)
        assertEquals(Long.MAX_VALUE, parse("    versionCode=${Long.MAX_VALUE} minSdk=37 targetSdk=37\n").records.single().versionCode)
    }
    @Test fun duplicatesAndOverflowReject() {
        reject("    versionCode=37 minSdk=37 targetSdk=37\n    versionCode=37 minSdk=37 targetSdk=37\n")
        reject("    versionCode=9223372036854775808 minSdk=37 targetSdk=37\n")
        reject("    versionCode=37 minSdk=2147483648 targetSdk=37\n")
    }
    @Test fun malformedOrOversizedVersionCannotBeDrainedAsIrrelevantMetadata() {
        for (line in listOf("    versionCode=-1 minSdk=37 targetSdk=37", "    versionCode=037 minSdk=37 targetSdk=37",
            "    versionCode=37 targetSdk=37 minSdk=37", "    versionCode=37 minSdk=37 targetSdk=37 extra=true",
            "    versionCode=" + "1".repeat(DozeOwnerCandidateParser.LINE_BYTES + 1))) reject("$line\n")
    }
    private fun parse(metadata: String): DozeOwnerCandidates = DozeOwnerCandidateParser.parse(
        ByteArrayInputStream(("Packages:\n" + DozeOwnerCandidatesTest.record() + metadata).toByteArray()), setOf(0))
    private fun reject(metadata: String) { assertThrows(RuntimeException::class.java) { parse(metadata) } }
}
