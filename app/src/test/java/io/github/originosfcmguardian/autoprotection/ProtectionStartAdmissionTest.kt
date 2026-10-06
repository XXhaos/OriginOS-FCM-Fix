package io.github.originosfcmguardian.autoprotection

import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import org.junit.Assert.*
import org.junit.Test

class ProtectionStartAdmissionTest {
    private class Storage : ProtectionAuthorityStorage {
        var bytes: ByteArray? = null
        var failNextReadback = false
        @Synchronized override fun <T> exclusive(action: () -> T): T = action()
        override fun read() = bytes?.let(ProtectionAuthorityCodec::decode)
        override fun write(value: ProtectionAuthoritySnapshot) {
            bytes = ProtectionAuthorityCodec.encode(value)
            if (failNextReadback) { failNextReadback = false; error("durable commit, failed read-back") }
        }
    }

    private class Fixture {
        val storage = Storage()
        val store = ProtectionConsentStore(storage)
        val first = SelectedTargetIdentity("org.telegram.messenger", 10123, 100, listOf("a".repeat(64)))
        val added = SelectedTargetIdentity("example.recipient", 10124, 101, listOf("b".repeat(64)))
        val clock = BudgetClock(14, 600_000, 1_000_000)
        var revision = 1L
        var request: String? = "request"
        var owner: Any? = Any()
        var commits = 0
        var rejections = 0
        var failures = 0
        var reads = 0
        val admission = ProtectionStartAdmission()

        init {
            store.grantRecipientExplicit(first, VERIFIED_AUTOSTART_FINGERPRINT, clock)
            assertTrue(store.enableMaster(store.readSnapshot(), clock))
            val lease = store.desiredLeases().single()
            assertEquals(AutomaticCheckDecision.Recover, store.take(lease, clock))
        }

        fun run(preflight: () -> String? = { null }, beforeAdmission: () -> Unit = {},
            beforeFailureRetirement: () -> Unit = {}) {
            admission.run(
                isCurrent = { request == "request" && revision == 1L },
                preflight = { reads++; store.readSnapshot(); preflight() },
                admit = {
                    beforeAdmission()
                    if (request != "request" || revision != 1L) false else { owner = null; true }
                },
                commit = {
                    commits++
                    store.grantRecipientExplicit(added, VERIFIED_AUTOSTART_FINGERPRINT, clock)
                    store.desiredLeases()
                    owner = Any()
                    request = null
                },
                reject = { rejections++; request = null },
                failClosed = { commitMayHaveWritten ->
                    beforeFailureRetirement()
                    if (commitMayHaveWritten || (request == "request" && revision == 1L)) {
                        failures++
                        owner = null
                        store.suspendLease(null, ProtectionSuspensionReason.SERVICE_UNAVAILABLE)
                        request = null
                    }
                },
                obsolete = { if (request == "request") request = null },
            )
        }
    }

    @Test fun `ordinary preflight rejection preserves exact prior owner tokens and budget bytes`() {
        for (reason in listOf("shell unavailable", "target removed", "target changed", "valid pending receipt", "PM timed out", "PM busy")) {
            val f = Fixture()
            val priorOwner = f.owner
            val priorBytes = f.storage.bytes!!.copyOf()
            val prior = f.store.readSnapshot()
            f.run(preflight = {
                assertSame(priorOwner, f.owner)
                assertArrayEquals(priorBytes, f.storage.bytes)
                reason
            })
            assertSame(priorOwner, f.owner)
            assertEquals(prior, f.store.readSnapshot())
            assertArrayEquals(priorBytes, f.storage.bytes)
            assertEquals(1, f.rejections)
            assertEquals(0, f.commits)
            assertEquals(0, f.failures)
        }
    }

    @Test fun `successful admission retires owner only after preflight and retains previous token and budget`() {
        val f = Fixture()
        val priorOwner = f.owner
        val prior = f.store.readSnapshot()!!
        f.run(preflight = { assertSame(priorOwner, f.owner); null })
        val current = f.store.readSnapshot()!!
        assertNotSame(priorOwner, f.owner)
        assertEquals(prior.targetConsents.single(), current.targetConsents.first { it.identity == f.first })
        assertEquals(prior.budget, current.budget)
        assertEquals(2, current.targetConsents.size)
        assertEquals(1, f.commits)
    }

    @Test fun `OFF during preflight cannot grant or resurrect the stopped owner`() {
        val f = Fixture()
        f.run(preflight = {
            f.request = null
            f.revision++
            f.owner = null
            f.store.revoke()
            null
        })
        assertNull(f.owner)
        assertEquals(ProtectionConsentMode.USER_PAUSED, f.store.readMode())
        assertEquals(listOf(f.first), f.store.configuredConsents().map { it.identity })
        assertTrue(f.store.desiredLeases().isEmpty())
        assertEquals(0, f.commits)
        assertEquals(0, f.failures)
    }

    @Test fun `replacement immediately before admission defeats stale result`() {
        val f = Fixture()
        val replacement = Any()
        val priorBytes = f.storage.bytes!!.copyOf()
        f.run(beforeAdmission = { f.revision++; f.request = "replacement"; f.owner = replacement })
        assertSame(replacement, f.owner)
        assertEquals("replacement", f.request)
        assertArrayEquals(priorBytes, f.storage.bytes)
        assertEquals(0, f.commits)
        assertEquals(0, f.failures)
    }

    @Test fun `repeated or initially obsolete preflight does not reread or admit`() {
        val f = Fixture()
        f.run()
        f.run()
        assertEquals(1, f.reads)
        assertEquals(1, f.commits)
        val obsolete = Fixture()
        obsolete.revision++
        obsolete.run()
        assertEquals(0, obsolete.reads)
        assertEquals(0, obsolete.commits)
    }

    @Test fun `shared storage exception fails closed but an obsolete read failure cannot retire replacement`() {
        for (source in listOf("authority", "selection", "journal")) {
            val f = Fixture()
            val budget = f.store.readSnapshot()!!.budget
            f.run(preflight = { error("$source corrupt") })
            assertNull(f.owner)
            assertEquals(ProtectionConsentMode.SUSPENDED, f.store.readMode())
            assertTrue(f.store.desiredLeases().isEmpty())
            assertEquals(budget, f.store.readSnapshot()!!.budget)
            assertEquals(1, f.failures)
            assertEquals(0, f.commits)
        }
        val f = Fixture()
        val prior = f.storage.bytes!!.copyOf()
        val replacement = Any()
        f.run(preflight = { f.revision++; f.request = "replacement"; f.owner = replacement; error("late read") })
        assertSame(replacement, f.owner)
        assertArrayEquals(prior, f.storage.bytes)
        assertEquals(0, f.failures)
    }

    @Test fun `ambiguous durable commit still suspends all authority without resetting budget`() {
        val f = Fixture()
        val budget = f.store.readSnapshot()!!.budget
        f.storage.failNextReadback = true
        f.run()
        assertNull(f.owner)
        assertEquals(ProtectionConsentMode.SUSPENDED, f.store.readMode())
        assertTrue(f.store.desiredLeases().isEmpty())
        assertEquals(budget, f.store.readSnapshot()!!.budget)
        assertEquals(1, f.failures)
        assertEquals(1, f.commits)
    }

    @Test fun `target removal after failed-read check cannot erase unrelated authority`() {
        val f = Fixture()
        val before = f.storage.bytes!!.copyOf()
        // Models stopTarget invalidating the request and queuing exact-target removal after
        // the helper's last isCurrent read, before the runtime acquires its retirement lock.
        f.run(preflight = { error("read failed") }, beforeFailureRetirement = {
            f.revision++
            f.request = "remove-target"
            f.owner = null
        })
        assertArrayEquals(before, f.storage.bytes)
        assertEquals(0, f.failures)
        assertEquals(0, f.commits)
        assertEquals("remove-target", f.request)
        assertTrue(f.store.revokeTarget(f.added))
        assertEquals(listOf(f.first), f.store.desiredLeases().map { it.consent.identity })
    }
}
