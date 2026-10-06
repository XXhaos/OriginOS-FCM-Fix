package io.github.originosfcmguardian.doze

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.util.Enumeration
import org.junit.Assert.*
import org.junit.Test

class DozeOwnerCandidatesTest {
    @Test fun activeHiddenRetainedAndProfilesAreKeptSeparate() {
        val raw = "Packages:\n" + record("android", 1_000, users = listOf(0, 10)) +
            record("com.android.apex", 0, users = listOf(0, 10)) +
            "Hidden system packages:\n" + record("android", 1_000, retained = true, users = listOf(0, 10))
        val parsed = parse(raw, setOf(0, 10))
        assertEquals(3, parsed.records.size)
        assertEquals(DozeOwnerSource.HIDDEN_SYSTEM, parsed.records.last().source)
        assertTrue(parsed.records.last().retained)
        assertFalse(parsed.records.last().profiles.first().installed)
        assertTrue(parsed.records.last().profiles.first().hidden)
        assertEquals(10, parsed.records.first().profiles.last().userId)
        assertTrue(parsed.parsedComplete)
        assertFalse(parsed.inventoryQualified)
        assertEquals(DozeOwnerInventoryQualification.UNKNOWN, parsed.inventoryQualification)
    }

    @Test fun renamedCompatibilityNamesAndSharedOwnersRemainAmbiguous() {
        val parsed = parse("Packages:\n" + record("new.pkg", 10_123, compat = "old.pkg", shared = true) +
            record("second.pkg", 10_123) + "Renamed packages:\n  new.pkg,old.pkg\n")
        assertEquals("old.pkg", parsed.records.first().packageName)
        assertEquals("new.pkg", parsed.records.first().displayName)
        assertEquals(DozeOwnerRename("new.pkg", "old.pkg"), parsed.renames.single())
        val matches = parsed.matchingRawAppId(10_123)
        assertEquals(2, matches.candidates.size)
        assertTrue(matches.hasAliasOrSharedConflict)
        assertFalse(matches.exclusiveOwner)
    }

    @Test fun sameCanonicalPackageAcrossSourcesIsNotASecondOwner() {
        val parsed = parse("Packages:\n" + record() + "Hidden system packages:\n" + record())
        val matches = parsed.matchingRawAppId(10_123)
        assertEquals(2, matches.candidates.size)
        assertFalse(matches.hasAliasOrSharedConflict)
        assertFalse(matches.exclusiveOwner)
    }

    @Test fun distinctCanonicalPackagesSharingRawAppIdRemainConflicted() {
        val parsed = parse("Packages:\n" + record() + "Hidden system packages:\n" + record("other.pkg"))
        val matches = parsed.matchingRawAppId(10_123)
        assertEquals(2, matches.candidates.size)
        assertTrue(matches.hasAliasOrSharedConflict)
        assertFalse(matches.exclusiveOwner)
    }

    @Test fun positivePccNeverBecomesANonmatchThroughAnAssumedMapping() {
        val parsed = parse("Packages:\n" + record("normal.pkg", 10_001) + record("pcc.pkg", 10_002, pcc = 99_999))
        val matches = parsed.matchingRawAppId(10_001)
        assertEquals(listOf("normal.pkg", "pcc.pkg"), matches.candidates.map { it.packageName })
        assertTrue(matches.hasUnresolvedPcc)
        assertFalse(matches.exclusiveOwner)
        assertFalse(parsed.matchingRawAppId(12_345).exclusiveOwner)
    }

    @Test fun unresolvedAppIdKeepsActiveHiddenAndRetainedRecordsSeparate() {
        val parsed = parse("Packages:\n" + record("unknown.pkg", -1, retained = true) + record() +
            "Hidden system packages:\n" + record("unknown.pkg", -1, retained = true) + record("hidden.pkg", -1))
        assertEquals(4, parsed.records.size)
        val unresolved = parsed.records.filter { it.appId == -1 }
        assertEquals(3, unresolved.size)
        assertEquals(listOf(DozeOwnerSource.ACTIVE, DozeOwnerSource.HIDDEN_SYSTEM, DozeOwnerSource.HIDDEN_SYSTEM),
            unresolved.map { it.source })
        assertTrue(unresolved[0].retained && unresolved[1].retained)
        assertFalse(unresolved[0].profiles.single().installed)
        assertFalse(parsed.inventoryQualified)
    }

    @Test fun unresolvedAppIdRemainsWithAndWithoutOrdinaryLookupMatches() {
        val parsed = parse("Packages:\n" + record("unknown.pkg", -1) + record() +
            "Hidden system packages:\n" + record("unknown.pkg", -1))
        val ordinary = parsed.matchingRawAppId(10_123)
        assertEquals(3, ordinary.candidates.size)
        assertTrue(ordinary.hasUnresolvedAppId)
        assertFalse(ordinary.exclusiveOwner)
        val unmatched = parsed.matchingRawAppId(54_321)
        assertEquals(2, unmatched.candidates.size)
        assertTrue(unmatched.hasUnresolvedAppId)
        assertEquals(listOf(DozeOwnerSource.ACTIVE, DozeOwnerSource.HIDDEN_SYSTEM), unmatched.candidates.map { it.source })
        assertFalse(unmatched.exclusiveOwner)
    }

    @Test fun noSentinelLeavesFlagFalseAndSentinelCannotBeAQueryUid() {
        val parsed = parse("Packages:\n" + record())
        assertFalse(parsed.matchingRawAppId(10_123).hasUnresolvedAppId)
        assertFalse(parsed.matchingRawAppId(54_321).hasUnresolvedAppId)
        for (lookup in listOf(-1, -2, 100_000, Int.MAX_VALUE)) {
            assertThrows(IllegalStateException::class.java) { parsed.matchingRawAppId(lookup) }
        }
    }

    @Test fun unresolvedAppIdDoesNotSuppressPccAliasOrSharedAmbiguity() {
        val parsed = parse("Packages:\n" + record("new.pkg", -1, pcc = 42, compat = "old.pkg", shared = true) +
            "Renamed packages:\n  new.pkg,old.pkg\n")
        val matches = parsed.matchingRawAppId(10_123)
        assertEquals(1, matches.candidates.size)
        assertTrue(matches.hasUnresolvedAppId)
        assertTrue(matches.hasUnresolvedPcc)
        assertTrue(matches.hasAliasOrSharedConflict)
        assertFalse(matches.exclusiveOwner)
    }

    @Test fun singletonAndRosterEqualityDoNotGrantQualification() {
        val parsed = parse("Packages:\n" + record())
        assertEquals(setOf(0), parsed.expectedUserIds)
        assertFalse(parsed.inventoryQualified)
        assertFalse(parsed.matchingRawAppId(10_123).exclusiveOwner)
    }

    @Test fun zeroPccIsUnresolvedAndRealPackageFlagMetadataIsDrained() {
        val raw = "Packages:\n" + record(pcc = 0) +
            "    pkgFlags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA ]\n" +
            "    flags=[ SYSTEM HAS_CODE ]\n    privateFlags=[ PRIVILEGED ]\n"
        val parsed = parse(raw)
        assertTrue(parsed.matchingRawAppId(10_124).hasUnresolvedPcc)
        assertEquals(1, parsed.matchingRawAppId(10_124).candidates.size)
        assertFalse(parsed.inventoryQualified)
    }

    @Test fun noTrailerMeansCompleteRecordBoundaryCannotProveWholeInventory() {
        // Both structurally parse; the emitter has no sentinel to distinguish a omitted second record.
        val first = "Packages:\n" + record()
        assertTrue(parse(first).parsedComplete)
        assertTrue(parse(first + record("second.pkg", 10_124)).parsedComplete)
        assertFalse(parse(first).inventoryQualified)
    }

    @Test fun missingAndDuplicateIdentityFieldsReject() {
        val raw = "Packages:\n" + record()
        listOf(
            raw.replace("    appId=10123\n", ""),
            raw.replace("    pccId=-1\n", ""),
            raw.replace("    pkg=Package{abc target.pkg}\n", ""),
            raw.replace("    appId=10123\n", "    appId=10123\n    appId=10123\n"),
            raw.replace("    pccId=-1\n", "    pccId=-1\n    pccId=1\n"),
            raw.replace("    pkg=Package{abc target.pkg}\n", "    pkg=null\n    pkg=null\n"),
        ).forEach(::reject)
    }

    @Test fun missingDuplicateUnknownAndInvalidProfileFieldsReject() {
        val raw = "Packages:\n" + record()
        listOf(
            raw.replace(" installed=true", ""), raw.replace(" hidden=false", ""),
            raw.replace(" enabled=0", ""), raw.replace(" installed=true", " installed=true installed=false"),
            raw.replace(" hidden=false", " hidden=unknown"), raw.replace(" enabled=0", " enabled=5"),
            raw.replace(" enabled=0", " enabled=2147483648"),
            raw.replace(" ceDataInode=0", " ceDataInode=9223372036854775808"),
            raw.replace(" quarantined=false", " futureFlag=false"),
            raw + userLine(0), raw.replace(userLine(0), ""),
        ).forEach(::reject)
    }

    @Test fun unknownHeadingsRowsDuplicateRecordsAndRenameConflictsReject() {
        val raw = "Packages:\n" + record()
        listOf(raw + "Other packages:\n", raw + "Packages:\n", raw + "  unexplained row\n",
            raw + record(), raw + "Renamed packages:\n  a.pkg,b.pkg\n  a.pkg,c.pkg\n",
            raw + "Renamed packages:\n  a.pkg -> b.pkg\n", raw.replace("(abcdef)", "(nothex)"),
            "Hidden system packages:\n" + record(), raw.replace("    appId=", "    appIdBad="),
        ).forEach(::reject)
    }

    @Test fun mismatchedAndOverflowedUserIdentityReject() {
        reject("Packages:\n" + record(users = listOf(0, 10)))
        reject("Packages:\n" + record(), setOf(0, 10))
        reject(("Packages:\n" + record()).replace("User 0:", "User 2147483648:"))
        reject("Packages:\n" + record(), setOf(10))
    }

    @Test fun identityOverflowAndInvalidPackageNamesReject() {
        val raw = "Packages:\n" + record()
        listOf(raw.replace("appId=10123", "appId=2147483648"),
            raw.replace("appId=10123", "appId=100000"), raw.replace("appId=10123", "appId=-2"),
            raw.replace("pccId=-1", "pccId=2147483648"), raw.replace("pccId=-1", "pccId=-2"),
            raw.replace("[target.pkg]", "[bad name]"),
        ).forEach(::reject)
    }

    @Test fun midRecordAndPartialFinalLineTruncationReject() {
        val raw = "Packages:\n" + record()
        reject(raw.substringBefore("    User "))
        reject(raw.dropLast(1))
        reject(raw.substringBefore(" hidden="))
    }

    @Test fun failureAndPermissionTextRejectEvenWithinMetadata() {
        val raw = "Packages:\n" + record()
        listOf("Permission Denial: cannot dump", "java.lang.SecurityException: denied", "Error: no service",
            "Failure: aborted", "Unknown argument: --include-apex", "Unable to find package x",
            "DUMP TIMEOUT", "dump timed out", "dump aborted").forEach {
            reject(raw + "      $it\n")
        }
    }

    @Test fun timeoutSubstringInsideRealPermissionAndIdentifiersIsOrdinaryMetadata() {
        val raw = "Packages:\n" + record() +
            "      android.permission.SCREEN_TIMEOUT_OVERRIDE: prot=signature\n" +
            "      com.example.timeout.PERMISSION: prot=signature\n" +
            "      timeout_identifier=value\n      dotted.timeout: prot=signature\n"
        val parsed = parse(raw)
        assertEquals(1, parsed.records.size)
        assertFalse(parsed.inventoryQualified)
    }

    @Test fun standaloneTimeoutFailureTokensStillRejectIncludingTabs() {
        val raw = "Packages:\n" + record()
        for (line in listOf("DUMP TIMEOUT", "timeout:", "timeout", "      metadata timeout occurred",
            "      timeout\tadditional", "      note: timeout: command")) {
            val failure = assertThrows(IllegalStateException::class.java) { parse(raw + line + "\n") }
            assertEquals("Package dump reported failure", failure.message)
        }
    }

    @Test fun oversizedSelectedLineRejectsButIrrelevantMetadataIsDrained() {
        val raw = "Packages:\n" + record()
        reject(raw.replace("appId=10123", "appId=" + "1".repeat(DozeOwnerCandidateParser.LINE_BYTES)))
        val parsed = parse(raw + "    permissionMetadata=" + "x".repeat(32_768) + "\n")
        assertEquals(1, parsed.records.size)
    }

    @Test fun rawAndStructuredCapacitiesReject() {
        assertThrows(IllegalStateException::class.java) {
            DozeOwnerCandidateParser.parse(GeneratedDozeOwnerDump(DozeOwnerCandidateParser.RAW_BYTES + 1), setOf(0))
        }
        val overRecords = buildString {
            append("Packages:\n")
            for (index in 0..DozeOwnerCandidateParser.MAX_RECORDS) append(record("pkg.p$index", index))
        }
        reject(overRecords)
        val overAliases = "Packages:\n" + record() + "Renamed packages:\n" +
            (0..DozeOwnerCandidateParser.MAX_RENAMES).joinToString("") { "  old.p$it,new.p$it\n" }
        reject(overAliases)
    }

    @Test fun generatedRawStreamsBeyondOldLimitAndExactlyAtNewLimitRemainUnknown() {
        assertEquals(16 * 1024 * 1024, DozeOwnerCandidateParser.RAW_BYTES)
        for (bytes in listOf(4 * 1024 * 1024 + 1, DozeOwnerCandidateParser.RAW_BYTES)) {
            val input = GeneratedDozeOwnerDump(bytes)
            val parsed = DozeOwnerCandidateParser.parse(input, setOf(0))
            assertEquals(bytes, input.consumedBytes)
            assertEquals(1, parsed.records.size)
            assertTrue(parsed.parsedComplete)
            assertFalse(parsed.inventoryQualified)
            assertTrue(input.largestRequestedRead <= 8 * 1024)
        }
    }

    @Test fun generatedNewLimitPlusOneByteRejectsWithoutRetainingWholeDump() {
        val input = GeneratedDozeOwnerDump(DozeOwnerCandidateParser.RAW_BYTES + 1)
        val failure = assertThrows(IllegalStateException::class.java) {
            DozeOwnerCandidateParser.parse(input, setOf(0))
        }
        assertEquals("Package dump exceeds raw byte bound", failure.message)
        assertEquals(DozeOwnerCandidateParser.RAW_BYTES + 1, input.consumedBytes)
        assertTrue(input.largestRequestedRead <= 8 * 1024)
    }

    @Test fun structuredBudgetStillRejectsBeforeRecordOrRawCapacity() {
        val users = (0 until DozeOwnerCandidateParser.MAX_USERS).toList()
        var index = -1
        val input = SequenceInputStream(object : Enumeration<InputStream> {
            override fun hasMoreElements() = index < 128
            override fun nextElement(): InputStream {
                val raw = if (index == -1) "Packages:\n" else record("pkg.p$index", index, users = users)
                index++
                return ByteArrayInputStream(raw.toByteArray())
            }
        })
        val failure = assertThrows(IllegalStateException::class.java) {
            input.use { DozeOwnerCandidateParser.parse(it, users.toSet()) }
        }
        assertEquals("Owner structured capacity exceeded", failure.message)
    }

    @Test fun cancellationAndMalformedUtf8Reject() {
        assertThrows(IllegalStateException::class.java) {
            DozeOwnerCandidateParser.parse(ByteArrayInputStream(("Packages:\n" + record()).toByteArray()), setOf(0)) { false }
        }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) {
            DozeOwnerCandidateParser.parse(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28, 10)), setOf(0))
        }
    }

    private fun parse(raw: String, users: Set<Int> = setOf(0)) =
        DozeOwnerCandidateParser.parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)), users)

    private fun reject(raw: String, users: Set<Int> = setOf(0)) {
        assertThrows(RuntimeException::class.java) { parse(raw, users) }
    }

    companion object {
        internal fun userLine(user: Int, installed: Boolean = true, hidden: Boolean = false) =
            "    User $user: ceDataInode=0 deDataInode=0 pccCeDataInode=0 pccDeDataInode=0" +
                " installed=$installed hidden=$hidden vHidden=1 suspended=false appLockEnabled=false" +
                " distractionFlags=0 stopped=false vStopped=false notLaunched=false enabled=0" +
                " instant=false virtual=false quarantined=false\n"

        internal fun record(pkg: String = "target.pkg", appId: Int = 10_123, pcc: Int = -1,
            retained: Boolean = false, users: List<Int> = listOf(0), compat: String? = null, shared: Boolean = false): String =
            "  Package [$pkg] (abcdef):\n" + (compat?.let { "    compat name=$it\n" } ?: "") +
                "    appId=$appId\n    pccId=$pcc\n" +
                (if (shared) "    sharedUser=SharedUserSetting{abcdef shared/10123}\n" else "") +
                "    pkg=" + (if (retained) "null" else "Package{abc $pkg}") + "\n" +
                "    codePath=/irrelevant/path\n" + users.joinToString("") { userLine(it, !retained, retained) }
    }
}

/** Constant-memory test stream: a valid small record followed by drained metadata and final LF. */
internal class GeneratedDozeOwnerDump(private val totalBytes: Int) : InputStream() {
    private val prefix = ("Packages:\n" + DozeOwnerCandidatesTest.record() + "    metadata=").toByteArray()
    var consumedBytes = 0
        private set
    var largestRequestedRead = 0
        private set
    init { require(totalBytes > prefix.size) }

    override fun read(): Int {
        largestRequestedRead = maxOf(largestRequestedRead, 1)
        if (consumedBytes == totalBytes) return -1
        val index = consumedBytes++
        return when {
            index < prefix.size -> prefix[index].toInt() and 0xff
            index == totalBytes - 1 -> '\n'.code
            else -> 'x'.code
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        largestRequestedRead = maxOf(largestRequestedRead, length)
        if (length == 0) return 0
        if (consumedBytes == totalBytes) return -1
        val count = minOf(length, totalBytes - consumedBytes)
        java.util.Arrays.fill(buffer, offset, offset + count, 'x'.code.toByte())
        val prefixCount = minOf(count, prefix.size - consumedBytes).coerceAtLeast(0)
        if (prefixCount > 0) System.arraycopy(prefix, consumedBytes, buffer, offset, prefixCount)
        if (consumedBytes + count == totalBytes) buffer[offset + count - 1] = '\n'.code.toByte()
        consumedBytes += count
        return count
    }
}
