package io.github.originosfcmguardian.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedDozeHarnessGuardTest {
    private val baseline = parseOwnedDozeWhitelist(
        "system-excidle,com.google.android.gms,10208\n" +
            "system,com.google.android.gms,10208\n" +
            "user,com.example.other,10450\n",
    )

    @Test
    fun completeParserRejectsMalformedUnknownSourceAndDuplicates() {
        assertEquals(3, baseline.entries.size)
        listOf(
            "other,com.example.other,10450",
            "user,com.example.other,10450\nuser,com.example.other,10450",
            "user,com.example.other,abc",
            "user,com.example.other,10450,extra",
            "",
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { parseOwnedDozeWhitelist(raw) }
        }
    }

    @Test
    fun targetMustBeAbsentFromEverySourceBeforeAdd() {
        requireOwnedTargetAbsent(baseline)
        for (source in listOf("system-excidle", "system", "user")) {
            val occupied = parseOwnedDozeWhitelist(
                baseline.raw + "$source,$TARGET_PACKAGE,10493\n",
            )
            assertThrows(IllegalArgumentException::class.java) { requireOwnedTargetAbsent(occupied) }
        }
    }

    @Test
    fun cleanupOnlyRemovesExactOwnedUserAddition() {
        val uid = 10493
        val added = parseOwnedDozeWhitelist(baseline.raw + "user,$TARGET_PACKAGE,$uid\n")
        assertEquals(
            OwnedCleanupDecision.REMOVE_EXACT_USER_ENTRY,
            ownedCleanupDecision(baseline, added, uid, addDispatched = true),
        )
        assertEquals(
            OwnedCleanupDecision.ALREADY_RESTORED,
            ownedCleanupDecision(baseline, baseline, uid, addDispatched = true),
        )
        assertEquals(
            OwnedCleanupDecision.REFUSE,
            ownedCleanupDecision(baseline, added, uid, addDispatched = false),
        )
        assertEquals(
            OwnedCleanupDecision.REFUSE,
            ownedCleanupDecision(baseline, added, uid + 1, addDispatched = true),
        )
    }

    @Test
    fun cleanupRefusesUnrelatedDriftAndOtherSourceTarget() {
        val uid = 10493
        val unrelated = parseOwnedDozeWhitelist(
            baseline.raw + "user,$TARGET_PACKAGE,$uid\nuser,com.example.changed,10494\n",
        )
        val otherSource = parseOwnedDozeWhitelist(
            baseline.raw + "system,$TARGET_PACKAGE,$uid\n",
        )
        assertEquals(OwnedCleanupDecision.REFUSE, ownedCleanupDecision(baseline, unrelated, uid, true))
        assertEquals(OwnedCleanupDecision.REFUSE, ownedCleanupDecision(baseline, otherSource, uid, true))
    }

    @Test
    fun ownedUidMustBeExactAndUnique() {
        val listing = "package:com.example.other uid:10400\n" +
            "package:$TARGET_PACKAGE uid:10493\n"
        assertTrue(verifiedOwnedUid(listing, 10493))
        assertFalse(verifiedOwnedUid(listing, 10494))
        assertFalse(verifiedOwnedUid(listing + "package:com.example.shared uid:10493\n", 10493))
        assertFalse(verifiedOwnedUid(listing + "package:$TARGET_PACKAGE uid:10493\n", 10493))
        assertFalse(verifiedOwnedUid(listing + "unparsed row\n", 10493))
    }

    @Test
    fun pccPreflightNeedsOneExplicitNegativeOneAndMatchingAppId() {
        val dump = "Packages:\n  Package [$TARGET_PACKAGE] (abc):\n" +
            "    appId=10493\n    pccId=-1\n" +
            "    User 0: installed=true stopped=true\n" +
            "    User 666: installed=false stopped=true\n"
        assertTrue(verifiedOwnedPackagePcc(dump, 10493))
        assertFalse(verifiedOwnedPackagePcc(dump.replace("pccId=-1", "pccId=10494"), 10493))
        assertFalse(verifiedOwnedPackagePcc(dump.replace("appId=10493", "appId=10494"), 10493))
        assertFalse(verifiedOwnedPackagePcc(dump.replace("    pccId=-1\n", ""), 10493))
        assertFalse(verifiedOwnedPackagePcc(dump + "    pccId=-1\n", 10493))
        assertFalse(verifiedOwnedPackagePcc(dump + "  Package [other] (def):\n", 10493))
        assertFalse(verifiedOwnedPackagePcc(dump.replace("User 666: installed=false", "User 666: installed=true"), 10493))
        assertFalse(verifiedOwnedPackagePcc(dump.replace("User 0: installed=true", "User 0: installed=false"), 10493))
        assertFalse(verifiedOwnedPackagePcc(dump + "    User 0: installed=true stopped=true\n", 10493))
    }
}
