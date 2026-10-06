package io.github.originosfcmguardian.privileged

import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StoppedTargetObservationTest {
    private val fields = StoppedPackageFields(
        STOPPED_TARGET_PACKAGE, STOPPED_TARGET_PACKAGE, 10234, 123456L,
        listOf(byteArrayOf(1, 2, 3)), true,
    )
    private val environment = StoppedProbeEnvironment(VERIFIED_AUTOSTART_FINGERPRINT, 0, 2000)

    private class Port(
        var env: StoppedProbeEnvironment,
        val samples: List<StoppedPackageFields?>,
        var owners: List<String>? = listOf(STOPPED_TARGET_PACKAGE),
    ) : StoppedIdentityReadPort {
        val calls = mutableListOf<String>()
        var index = 0
        var afterOwnership: () -> Unit = {}
        override fun environment(): StoppedProbeEnvironment { calls += "environment"; return env }
        override fun telegramPackage(): StoppedPackageFields? { calls += "package"; return samples[index++] }
        override fun packagesForUid(uid: Int): List<String>? {
            calls += "owners:$uid"
            afterOwnership()
            return owners
        }
    }

    private fun port(first: StoppedPackageFields? = fields, second: StoppedPackageFields? = first) =
        Port(environment, listOf(first, second))
    private fun probe(port: StoppedIdentityReadPort) = observeStoppedTelegram(7, port, nowMillis = { 999L })
    private fun reason(result: StoppedTargetObservation) = (result as StoppedTargetObservation.Unknown).reason

    @Test fun freshFullIdentityBracketsOwnershipAndUsesFinalStoppedFlag() {
        val port = port(fields, fields.copy(stopped = false))
        val result = probe(port) as StoppedTargetObservation.Known
        assertEquals(listOf("environment", "package", "owners:10234", "package", "environment"), port.calls)
        assertEquals(10234, result.identity.uid)
        assertEquals(123456L, result.identity.firstInstallTime)
        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", result.identity.signerSha256.single())
        assertFalse(result.stopped)
        assertTrue(result.exclusiveUidOwner)
        assertEquals(999L, result.observedAtMillis)
    }

    @Test fun rejectsRootOtherUidWrongUserAndUnverifiedBuildBeforePackageReads() {
        val cases = listOf(
            environment.copy(serviceUid = 0) to StoppedObservationUnknown.SHELL_UNAVAILABLE,
            environment.copy(serviceUid = 12345) to StoppedObservationUnknown.SHELL_UNAVAILABLE,
            environment.copy(currentUser = 1) to StoppedObservationUnknown.WRONG_USER,
            environment.copy(currentUser = null) to StoppedObservationUnknown.WRONG_USER,
            environment.copy(fingerprint = "unknown-build") to StoppedObservationUnknown.UNSUPPORTED_BUILD,
        )
        cases.forEach { (env, expected) ->
            val port = port().apply { this.env = env }
            assertEquals(expected, reason(probe(port)))
            assertEquals(listOf("environment"), port.calls)
        }
    }

    @Test fun invalidRequestDoesNotReadAnything() {
        val port = port()
        assertEquals(StoppedObservationUnknown.INVALID_REQUEST, reason(observeStoppedTelegram(0, port)))
        assertTrue(port.calls.isEmpty())
    }

    @Test fun malformedMissingPackageIdentityAndCertificateFieldsFailClosed() {
        val cases = listOf(
            null, fields.copy(packageName = "org.telegram.messenger.beta"),
            fields.copy(applicationPackageName = "different.app"), fields.copy(uid = 110234),
            fields.copy(uid = 2000), fields.copy(uid = null), fields.copy(firstInstallTime = 0),
            fields.copy(firstInstallTime = null), fields.copy(currentSignerCertificates = null),
            fields.copy(currentSignerCertificates = emptyList()),
            fields.copy(currentSignerCertificates = listOf(byteArrayOf())),
            fields.copy(currentSignerCertificates = listOf(ByteArray(64 * 1024 + 1))),
            fields.copy(currentSignerCertificates = List(MAX_CURRENT_SIGNERS + 1) { byteArrayOf(it.toByte()) }),
            fields.copy(currentSignerCertificates = listOf(byteArrayOf(1), byteArrayOf(1))),
            fields.copy(stopped = null),
        )
        cases.forEach { malformed ->
            assertNull(validatedStoppedPackage(malformed))
            val port = port(malformed)
            assertEquals(StoppedObservationUnknown.IDENTITY_UNKNOWN, reason(probe(port)))
            assertEquals(listOf("environment", "package"), port.calls)
        }
    }

    @Test fun multipleCurrentSignersAreSortedAndHashedWithoutHistory() {
        val result = validatedStoppedPackage(fields.copy(currentSignerCertificates = listOf(byteArrayOf(9), byteArrayOf(1))))!!
        assertEquals(2, result.first.signerSha256.size)
        assertEquals(result.first.signerSha256.sorted(), result.first.signerSha256)
    }

    @Test fun everyFullInstallationIdentityChangeDiscardsObservedState() {
        val changes = listOf(
            fields.copy(uid = 10235), fields.copy(firstInstallTime = 123457),
            fields.copy(currentSignerCertificates = listOf(byteArrayOf(4))),
        )
        changes.forEach { after ->
            assertEquals(StoppedObservationUnknown.IDENTITY_CHANGED, reason(probe(port(fields, after))))
        }
        assertEquals(StoppedObservationUnknown.IDENTITY_UNKNOWN, reason(probe(port(fields, null))))
    }

    @Test fun ownershipUnknownMalformedOrMissingTargetDiscardsState() {
        val cases = listOf(null, emptyList(), listOf("other.app"), listOf(STOPPED_TARGET_PACKAGE, STOPPED_TARGET_PACKAGE),
            listOf(STOPPED_TARGET_PACKAGE, "bad package"), List(65) { "owner.app$it" })
        cases.forEach { owners ->
            assertEquals(StoppedObservationUnknown.UID_OWNERSHIP_UNKNOWN, reason(probe(port().apply { this.owners = owners })))
        }
    }

    @Test fun sharedUidIsExplicitKnownNonexclusiveEvidence() {
        val result = probe(port().apply { owners = listOf(STOPPED_TARGET_PACKAGE, "other.app") }) as StoppedTargetObservation.Known
        assertFalse(result.exclusiveUidOwner)
    }

    @Test fun userOrBuildChangeDuringSamplingDiscardsEvidence() {
        val port = port().apply { afterOwnership = { env = environment.copy(currentUser = 1) } }
        assertEquals(StoppedObservationUnknown.WRONG_USER, reason(probe(port)))
        val changedBuild = port().apply { afterOwnership = { env = environment.copy(fingerprint = "new-build") } }
        assertEquals(StoppedObservationUnknown.UNSUPPORTED_BUILD, reason(probe(changedBuild)))
    }

    @Test fun cancellationAfterOwnershipNeverReturnsIdentityOrStopped() {
        val cancelled = AtomicBoolean(false)
        val port = port().apply { afterOwnership = { cancelled.set(true) } }
        val result = observeStoppedTelegram(7, port, { if (cancelled.get()) StoppedObservationUnknown.CANCELLED else null })
        assertEquals(StoppedObservationUnknown.CANCELLED, reason(result))
        assertEquals(listOf("environment", "package", "owners:10234"), port.calls)
    }

    @Test fun preexistingTimeoutStopsAllReads() {
        val port = port()
        assertEquals(StoppedObservationUnknown.TIMED_OUT, reason(observeStoppedTelegram(7, port, { StoppedObservationUnknown.TIMED_OUT })))
        assertTrue(port.calls.isEmpty())
    }

    @Test fun adapterFailureAndBadTimestampHaveNoPartialEvidence() {
        val throwingPort = object : StoppedIdentityReadPort {
            override fun environment(): StoppedProbeEnvironment = error("Hidden API unavailable")
            override fun telegramPackage(): StoppedPackageFields? = error("Must not run")
            override fun packagesForUid(uid: Int): List<String>? = error("Must not run")
        }
        assertEquals(StoppedObservationUnknown.CAPABILITY_UNAVAILABLE, reason(probe(throwingPort)))
        assertEquals(StoppedObservationUnknown.CAPABILITY_UNAVAILABLE, reason(observeStoppedTelegram(7, port(), nowMillis = { 0 })))
    }

    @Test fun codecRoundTripsKnownAndEveryUnknownWithoutPartialIdentity() {
        val result = probe(port())
        assertEquals(result, StoppedObservationCodec.decode(StoppedObservationCodec.encode(result), 7))
        StoppedObservationUnknown.entries.forEach { cause ->
            val unknown = StoppedTargetObservation.Unknown(7, cause)
            val encoded = StoppedObservationCodec.encode(unknown)
            assertEquals(4, encoded.split('|').size)
            assertEquals(unknown, StoppedObservationCodec.decode(encoded, 7))
        }
    }

    @Test fun codecKeepsFalseStoppedAndNonexclusiveDistinctFromUnknown() {
        val result = probe(port(fields.copy(stopped = false)).apply { owners = listOf(STOPPED_TARGET_PACKAGE, "other.app") })
        val decoded = StoppedObservationCodec.decode(StoppedObservationCodec.encode(result), 7) as StoppedTargetObservation.Known
        assertFalse(decoded.stopped)
        assertFalse(decoded.exclusiveUidOwner)
    }

    @Test fun codecRejectsStaleRequestOversizeExtraMissingAndInvalidFlags() {
        val encoded = StoppedObservationCodec.encode(probe(port()))
        val fields = encoded.split('|')
        val bad = listOf(null, "", "x".repeat(4097), "$encoded|extra", fields.dropLast(1).joinToString("|"),
            encoded.replace("1|7|K", "2|7|K"), encoded.replace("1|7|K", "1|8|K"),
            fields.toMutableList().apply { set(11, "true") }.joinToString("|"), "1|7|U|UNRECOGNIZED")
        bad.forEach { assertEquals(StoppedObservationUnknown.MALFORMED_RESPONSE, reason(StoppedObservationCodec.decode(it, 7))) }
    }

    @Test fun codecRejectsChangedTargetUidInstallSignerAndEnvironment() {
        val encoded = StoppedObservationCodec.encode(probe(port()))
        val edits = listOf(7 to "other.app", 8 to "110234", 9 to "0", 10 to "xyz", 4 to "unknown-build", 5 to "1", 6 to "0", 3 to "0")
        edits.forEach { (index, value) ->
            val invalid = encoded.split('|').toMutableList().apply { set(index, value) }.joinToString("|")
            assertEquals(StoppedObservationUnknown.MALFORMED_RESPONSE, reason(StoppedObservationCodec.decode(invalid, 7)))
        }
    }

    @Test fun boundedRunnerReturnsTimeoutAndBusyForUninterruptibleReadWithoutLateEvidence() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = object : StoppedIdentityReadPort {
            override fun environment(): StoppedProbeEnvironment {
                entered.countDown()
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { /* Model a noninterruptible Binder read. */ }
                }
                return environment
            }
            override fun telegramPackage(): StoppedPackageFields = fields
            override fun packagesForUid(uid: Int): List<String> = listOf(STOPPED_TARGET_PACKAGE)
        }
        val runner = StoppedIdentityProbeRunner({ blocked }, 100)
        try {
            assertEquals(StoppedObservationUnknown.TIMED_OUT, reason(runner.read(7) { false }))
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertEquals(StoppedObservationUnknown.BUSY, reason(runner.read(8) { false }))
        } finally { release.countDown(); runner.close() }
    }

    @Test fun runnerRejectsInvalidAndCancelledRequestsBeforeAdapterCreation() {
        val runner = StoppedIdentityProbeRunner({ error("Must not create adapter") })
        try {
            assertEquals(StoppedObservationUnknown.INVALID_REQUEST, reason(runner.read(0) { false }))
            assertEquals(StoppedObservationUnknown.CANCELLED, reason(runner.read(7) { true }))
        } finally { runner.close() }
    }

    @Test fun cancellationDuringBlockedReadReturnsUnknownAndNeverPublishesLateKnown() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val blocked = object : StoppedIdentityReadPort {
            override fun environment(): StoppedProbeEnvironment {
                entered.countDown()
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { }
                }
                return environment
            }
            override fun telegramPackage(): StoppedPackageFields = fields
            override fun packagesForUid(uid: Int): List<String> = listOf(STOPPED_TARGET_PACKAGE)
        }
        val runner = StoppedIdentityProbeRunner({ blocked }, 2_000)
        val call = FutureTask { runner.read(7) { cancelled.get() } }
        val caller = Thread(call, "stopped-identity-test-caller")
        caller.start()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            cancelled.set(true)
            assertEquals(StoppedObservationUnknown.CANCELLED, reason(call.get(1, TimeUnit.SECONDS)))
            assertEquals(StoppedObservationUnknown.BUSY, reason(runner.read(8) { false }))
            release.countDown()
            assertEquals(StoppedObservationUnknown.CANCELLED, reason(call.get()))
        } finally { cancelled.set(true); release.countDown(); runner.close(); caller.join(3_000) }
    }
}
