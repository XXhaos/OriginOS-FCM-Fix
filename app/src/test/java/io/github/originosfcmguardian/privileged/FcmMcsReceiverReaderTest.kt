package io.github.originosfcmguardian.privileged

import org.junit.Assert.*
import org.junit.Test

class FcmMcsReceiverReaderTest {
    private val dump = """
ACTIVITY MANAGER BROADCAST STATE (dumpsys activity broadcasts)
  Registered Receivers:
  * ReceiverList{99fc1e2 19441 com.google.android.gms/10208/u0 remote:7cb9aad}
    app=19441:com.google.android.gms/u0a208 pid=19441 uid=10208 user=0 #receivers=82
    Filter #0: BroadcastFilter{ce49973}
      Action: "com.google.android.intent.action.MCS_HEARTBEAT"
      Action: "com.google.android.gms.gcm.ACTION_HEARTBEAT_NOW"
      exported=true
    Filter #1: BroadcastFilter{6089030}
      Action: "com.google.android.gms.gcm.HEARTBEAT_ALARM"
      requiredPermission=com.google.android.gms.permission.INTERNAL_BROADCAST
  Receiver Resolver Table:
    com.google.android.intent.action.MCS_HEARTBEAT:
      BroadcastFilter{ce49973}

""".trimIndent() + "\n"
    @Test fun exactRuntimeFilterIgnoresAdjacentProtectedAlarmAndSurvivesChunkBoundaries() {
        assertTrue(FcmMcsReceiverReader.parse(dump.toByteArray(), 10208))
        val collector = FcmMcsReceiverReader.Collector(10208)
        dump.toByteArray().forEach { assertTrue(collector.accept(byteArrayOf(it))) }
        assertTrue(collector.finish())
        val unrelated = "  * ReceiverList{abcdef 1111 other.package/10123/u0 remote:123abc}\n    app=1111:other.package/u0a123 pid=1111 uid=10123 user=0 #receivers=1\n    Filter #0: BroadcastFilter{abcd}\n" +
            (0..16).joinToString("") { "      Action: \"other.package.ACTION_$it\"\n" } +
            "      Action: \"com.google.android.intent.action.MCS_HEARTBEAT\"\n" +
            "      Action: \"other.package.ACTION_EXCEPTION\"\n"
        assertTrue(FcmMcsReceiverReader.parse(dump.replace("  * ReceiverList{99fc1e2", unrelated + "  * ReceiverList{99fc1e2").toByteArray(), 10208))
        assertTrue(FcmMcsReceiverReader.parse((dump + "  Historical broadcasts:\n    Intent { act=other.package.ACTION_EXCEPTION }\n").toByteArray(), 10208))
        assertTrue(FcmMcsReceiverReader.parse(dump.replace("    com.google.android.intent.action.MCS_HEARTBEAT:",
            "    other.package.Exception:\n      BroadcastFilter{abcdef}\n    com.google.android.intent.action.MCS_HEARTBEAT:").toByteArray(), 10208))
        val longHistory = dump + "  Historical broadcasts:\n    extras=" + "x".repeat(13072) + "\n"
        assertTrue(FcmMcsReceiverReader.parse(longHistory.toByteArray(), 10208))
        assertFalse(FcmMcsReceiverReader.parse(longHistory.trimEnd().toByteArray(), 10208))
        assertFalse(FcmMcsReceiverReader.parse((dump + "Permission Denial: " + "x".repeat(13072) + "\n").toByteArray(), 10208))
        assertFalse(FcmMcsReceiverReader.parse(dump.replace("    Filter #1", "      " + "x".repeat(13072) + "\n    Filter #1").toByteArray(), 10208))
    }
    @Test fun incompleteWrongOwnerAmbiguousPermissionAndExplicitNotExportedFailClosed() {
        listOf(dump.trimEnd(), dump.replace("/10208/u0", "/10208/u10"),
            dump.substringBefore("  Receiver Resolver Table:"),
            dump.replace("uid=10208", "uid=10209"), dump.replace("pid=19441", "pid=19442"),
            dump.replace("/u0a208", "/u0a209"),
            dump.replace("    Filter #1", "      requiredPermission=some.permission\n    Filter #1"),
            dump.replace("    Filter #1", "      exported=false\n    Filter #1"),
            dump + "Permission Denial\n", dump + "java.lang.SecurityException: cannot dump\n",
            dump + "*** DUMP TIMEOUT ***\n", dump + "Error while dumping service\n", dump + dump,
            dump.replace("  Registered Receivers:", "  Unknown Receivers:"),
            dump.replace("    Filter #1", "      Action: \"com.google.android.intent.action.MCS_HEARTBEAT\"\n    Filter #1"))
            .forEach { assertFalse(it.take(120), FcmMcsReceiverReader.parse(it.toByteArray(), 10208)) }
    }
    @Test fun metadataMissingAttemptIsLimitedToExactFilterWithoutContraryEvidence() {
        val legacy = dump.replace("      exported=true\n", "")
        assertFalse(FcmMcsReceiverReader.parse(legacy.toByteArray(), 10208))
        assertTrue(FcmMcsReceiverReader.parse(legacy.toByteArray(), 10208, allowMetadataMissingAttempt = true))
        listOf(dump.replace("exported=true", "exported=false"),
            dump.replace("exported=true", "exported=true\n      exported=false"),
            dump.replace("exported=true", "exported=true\n      exported=true"),
            dump.replace("exported=true", "exported=unknown"),
            dump.replace("exported=true", "requiredPermission=some.permission"),
            legacy.replace("      Action: \"com.google.android.gms.gcm.ACTION_HEARTBEAT_NOW\"\n", ""),
            legacy.replace("com.google.android.gms.gcm.ACTION_HEARTBEAT_NOW", "other.ACTION"))
            .forEach { assertFalse(FcmMcsReceiverReader.parse(it.toByteArray(), 10208, allowMetadataMissingAttempt = true)) }
    }
    @Test fun metadataMissingAttemptStillRejectsAmbiguityPermissionAndIncompleteSources() {
        val missing = dump.replace("      exported=true\n", "")
        assertTrue(FcmMcsReceiverReader.parse(missing.replace("    Filter #1",
            "      requiredPermission=null\n    Filter #1").toByteArray(), 10208, allowMetadataMissingAttempt = true))
        val invalid = listOf(missing.trimEnd(), missing.substringBefore("  Receiver Resolver Table:"),
            missing + "*** DUMP TIMEOUT ***\n", missing + missing,
            missing.replace("/10208/u0", "/10208/u10"), missing.replace("pid=19441", "pid=19442"),
            missing.replace("    Filter #1", "      Action: \"other.EXTRA_ACTION\"\n    Filter #1"),
            missing.replace("    Filter #1", "      Action: \"com.google.android.intent.action.MCS_HEARTBEAT\"\n    Filter #1"),
            missing.replace("    Filter #1", "      requiredPermission=other.PERMISSION\n    Filter #1"),
            missing.replace("    Filter #1", "      requiredPermission=null\n      requiredPermission=null\n    Filter #1"),
            missing.replace("    Filter #1", "      exported=false\n    Filter #1"),
            missing.replace("    Filter #1", "      exported=unknown\n    Filter #1"),
            missing.replace("    Filter #1", "      exported=true\n      exported=true\n    Filter #1"))
        invalid.forEach { assertFalse(FcmMcsReceiverReader.parse(it.toByteArray(), 10208, allowMetadataMissingAttempt = true)) }
    }
    @Test fun explicitRuntimeCapabilityAllowsChangedActionShapeWithoutVersionAssumptions() {
        val single = dump.replace("      Action: \"com.google.android.gms.gcm.ACTION_HEARTBEAT_NOW\"\n", "")
        assertTrue(FcmMcsReceiverReader.parse(single.toByteArray(), 10208))
        assertTrue(FcmMcsReceiverReader.parse(single.replace("exported=true",
            "exported=true\n      requiredPermission=null").toByteArray(), 10208))
        assertFalse(FcmMcsReceiverReader.parse(single.replace("exported=true",
            "exported=true\n      requiredPermission=null\n      requiredPermission=null").toByteArray(), 10208))
        assertTrue(FcmMcsReceiverReader.parse(single.replace("exported=true",
            "Action: \"com.google.android.gms.gcm.EXTRA_HEARTBEAT\"\n      exported=true").toByteArray(), 10208))
    }
    @Test fun secondaryProcessOfSameUidCannotHideAnotherTargetReceiver() {
        val secondary = dump.substringAfter("  Registered Receivers:\n")
            .substringBefore("  Receiver Resolver Table:")
            .replace("com.google.android.gms/", "com.google.android.gms.persistent/")
        assertFalse(FcmMcsReceiverReader.parse(dump.replace("  Receiver Resolver Table:",
            secondary + "  Receiver Resolver Table:").toByteArray(), 10208))
        assertFalse(FcmMcsReceiverReader.parse(dump.replace("com.google.android.gms/",
            "com.google.android.gms.persistent/").toByteArray(), 10208))
    }
    @Test fun currentPhoneSizedRegisteredSectionIsStreamedAndBoundedWithoutRetainingRawDump() {
        // Real device section is 1,308,550 bytes; it must fit the finite 2MiB section budget.
        val other = "  * ReceiverList{abcdef 1111 other.package/10123/u0 remote:123abc}\n    app=1111:other.package/u0a123 pid=1111 uid=10123 user=0 #receivers=1\n    Filter #0: BroadcastFilter{abcd}\n      Action: \"other.package.ACTION\"\n"
        val bytes = dump.replace("  Receiver Resolver Table:", other.repeat(6500) + "  Receiver Resolver Table:").toByteArray()
        assertTrue(bytes.size > 1_308_550)
        val collector = FcmMcsReceiverReader.Collector(10208)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + 4096, bytes.size)
            assertTrue(collector.accept(bytes.copyOfRange(offset, end))); offset = end
        }
        assertTrue(collector.finish())
        assertFalse(FcmMcsReceiverReader.parse(dump.replace("  Receiver Resolver Table:",
            other.repeat(11000) + "  Receiver Resolver Table:").toByteArray(), 10208))
    }
}
