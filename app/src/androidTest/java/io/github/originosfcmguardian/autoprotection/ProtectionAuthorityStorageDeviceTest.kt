package io.github.originosfcmguardian.autoprotection

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.originosfcmguardian.autostart.SelectedTargetIdentity
import io.github.originosfcmguardian.autostart.VERIFIED_AUTOSTART_FINGERPRINT
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android disk/lock checks with synthetic records, never the production authority path. */
@RunWith(AndroidJUnit4::class)
class ProtectionAuthorityStorageDeviceTest {
    private lateinit var directory: File
    private lateinit var cacheDirectory: File
    private lateinit var isolatedContext: Context

    // This record is deliberately synthetic; no package probe or privileged operation is used.
    private val identity = SelectedTargetIdentity(
        "org.telegram.messenger", 10123, 1234L, listOf("a".repeat(64)),
    )

    @Before fun setUp() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        cacheDirectory = targetContext.cacheDir.canonicalFile
        directory = File(cacheDirectory, "protection-authority-test-${UUID.randomUUID()}").canonicalFile
        check(directory.parentFile == cacheDirectory)
        check(directory != targetContext.noBackupFilesDir.canonicalFile)
        check(directory.mkdir())
        isolatedContext = object : ContextWrapper(targetContext) {
            override fun getNoBackupFilesDir(): File = directory
            override fun getApplicationContext(): Context = this
        }
    }

    @After fun tearDown() {
        if (!::directory.isInitialized || !directory.exists()) return
        check(directory.canonicalFile.parentFile == cacheDirectory)
        // Delete only the known isolated storage artifacts; never recurse into app storage.
        listOf(
            AUTHORITY_FILE,
            "$AUTHORITY_FILE.bak",
            "$AUTHORITY_FILE.new",
            "automatic-protection-authority-v1.lock",
            "automatic-protection-authority-v1.initialized",
            "automatic-protection-authority-v1.stop-pending",
        ).forEach { name ->
            val artifact = File(directory, name)
            check(!artifact.exists() || artifact.delete())
        }
        check(directory.delete())
    }

    @Test fun explicitGrantSurvivesReconstructedStorageOwner() {
        val firstOwner = newStore()
        assertNull(firstOwner.readDesired())
        assertEquals(ProtectionConsentMode.DISABLED, firstOwner.readMode())

        val token = grant(firstOwner)
        val committed = authorityFile().readBytes()
        val reconstructed = newStore()

        assertEquals(token, reconstructed.readDesired())
        assertEquals(ProtectionConsentMode.ACTIVE, reconstructed.readMode())
        assertTrue(reconstructed.verifyAuthorized(token))
        assertEquals(identity, token.identity)
        assertEquals(VERIFIED_AUTOSTART_FINGERPRINT, token.fingerprint)
        assertArrayEquals(committed, authorityFile().readBytes())
        assertFalse(File(directory, "$AUTHORITY_FILE.new").exists())
    }

    @Test fun reservedAttemptSurvivesReconstructionRevocationAndRegrant() {
        val firstOwner = newStore()
        val firstToken = grant(firstOwner)
        assertEquals(AutomaticCheckDecision.Recover, firstOwner.take(firstToken, clock(0)))

        val secondOwner = newStore()
        assertEquals(AutomaticCheckDecision.Cooldown(299_999L), secondOwner.take(firstToken, clock(1)))
        assertTrue(secondOwner.revoke(firstToken))
        val nextToken = grant(newStore(), 2)
        val thirdOwner = newStore()

        assertEquals(AutomaticCheckDecision.Cooldown(299_998L), thirdOwner.take(nextToken, clock(2)))
        assertFalse(firstOwner.verifyAuthorized(firstToken))
        assertTrue(thirdOwner.verifyAuthorized(nextToken))
        assertEquals(AutomaticCheckDecision.Recover, thirdOwner.take(nextToken, clock(300_000)))
        assertEquals(AutomaticCheckDecision.Cooldown(300_000L), newStore().take(nextToken, clock(300_000)))
    }

    @Test fun revocationPersistsAndPreviouslyConstructedOwnerReadsFreshAuthority() {
        val firstOwner = newStore()
        val token = grant(firstOwner)
        val secondOwner = newStore()
        assertTrue(secondOwner.verifyAuthorized(token))

        assertTrue(firstOwner.revoke(token))

        assertFalse(secondOwner.verifyAuthorized(token))
        assertNull(secondOwner.readDesired())
        val reconstructed = newStore()
        assertNull(reconstructed.readDesired())
        assertEquals(ProtectionConsentMode.DISABLED, reconstructed.readMode())
        assertTrue(reconstructed.take(token, clock(300_000)) is AutomaticCheckDecision.Pause)
    }

    @Test fun separateStorageOwnerCannotReserveWhileAuthorityFileLockIsHeld() {
        val firstStorage = AtomicProtectionAuthorityStorage(isolatedContext)
        val token = grant(ProtectionConsentStore(firstStorage))
        val secondOwner = newStore()
        val committed = authorityFile().readBytes()

        firstStorage.exclusive {
            assertTrue(secondOwner.take(token, clock(0)) is AutomaticCheckDecision.Pause)
            assertFalse(secondOwner.verifyAuthorized(token))
            assertArrayEquals(committed, authorityFile().readBytes())
        }

        assertTrue(secondOwner.verifyAuthorized(token))
        assertEquals(AutomaticCheckDecision.Recover, secondOwner.take(token, clock(0)))
        assertEquals(AutomaticCheckDecision.Cooldown(300_000L), newStore().take(token, clock(0)))
    }

    @Test fun malformedInitializedAuthorityBlocksConsentAndReservationWithoutOverwrite() {
        val token = grant(newStore())
        val malformed = byteArrayOf(1, 2, 3)
        authorityFile().writeBytes(malformed)
        val reconstructed = newStore()

        assertFalse(reconstructed.verifyAuthorized(token))
        assertTrue(reconstructed.take(token, clock(0)) is AutomaticCheckDecision.Pause)
        assertThrows(Exception::class.java) { grant(reconstructed) }
        assertArrayEquals(malformed, authorityFile().readBytes())
    }

    @Test fun missingAuthorityAfterInitializationBlocksFreshConsentAndReservation() {
        val token = grant(newStore())
        val marker = File(directory, "automatic-protection-authority-v1.initialized")
        assertTrue(marker.isFile)
        assertTrue(authorityFile().delete())
        val reconstructed = newStore()

        assertFalse(reconstructed.verifyAuthorized(token))
        assertTrue(reconstructed.take(token, clock(0)) is AutomaticCheckDecision.Pause)
        assertThrows(Exception::class.java) { grant(reconstructed) }
        assertFalse(authorityFile().exists())
        assertArrayEquals(byteArrayOf(1), marker.readBytes())
    }

    @Test fun restartConsentCheckDoesNotReserveAndEmptyBudgetRebasesAfterBoot() {
        val legacy = grant(newStore())
        assertEquals(1, newStore().readDesired()?.policyVersion)
        val store = newStore()
        val token = store.grantExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, clock(1), RESTART_CONSENT_POLICY_VERSION)
        assertFalse(store.verifyAuthorized(legacy))
        assertEquals(2, newStore().readDesired()?.policyVersion)
        val reboot = BudgetClock(2, 20, 2_000_000)
        repeat(3) { assertEquals(AutomaticCheckDecision.Recover, newStore().checkBudget(token, reboot)) }
        assertTrue(newStore().readSnapshot()?.budget?.attempts?.isEmpty() == true)
        assertEquals(AutomaticCheckDecision.Recover, newStore().take(token, reboot))
        assertEquals(1, newStore().readSnapshot()?.budget?.attempts?.size)
        assertTrue(newStore().revoke(token))
        assertFalse(newStore().verifyAuthorized(token))
    }

    @Test fun interruptedRetirementCannotRestoreOldActiveGrantOrResetBudget() {
        val store = newStore()
        val token = store.grantExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, clock(0), 2)
        assertEquals(AutomaticCheckDecision.Recover, store.take(token, clock(0)))
        // Synthetic crash point: retirement marker reached disk but ACTIVE file has not changed.
        val marker = File(directory, "automatic-protection-authority-v1.stop-pending")
        marker.outputStream().use { it.write(1); it.fd.sync() }
        val reconstructed = newStore()
        assertFalse(reconstructed.verifyAuthorized(token))
        assertNull(reconstructed.readDesired())
        assertEquals(ProtectionConsentMode.SUSPENDED, reconstructed.readMode())
        assertEquals(listOf(0L), reconstructed.readSnapshot()?.budget?.attempts)
        assertTrue(reconstructed.take(token, clock(1)) is AutomaticCheckDecision.Pause)
        val explicitlyReconfirmed = reconstructed.grantExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, clock(1), 2)
        assertFalse(marker.exists())
        assertEquals(AutomaticCheckDecision.Cooldown(299_999), newStore().take(explicitlyReconfirmed, clock(1)))
    }

    @Test fun hourlyLimitWaitPreservesRecipientConsentAcrossStorageReconstruction() {
        val store = newStore()
        val lease = store.grantRecipientExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, clock(0))
        for (elapsed in listOf(0L, 300_000L, 600_000L)) {
            assertEquals(AutomaticCheckDecision.Recover, store.take(lease, clock(elapsed)))
        }
        val beforeWait = requireNotNull(store.readSnapshot())
        val reconstructed = newStore()
        assertEquals(AutomaticCheckDecision.Cooldown(2_700_000L), reconstructed.checkBudget(lease, clock(900_000)))
        assertEquals(AutomaticCheckDecision.Cooldown(2_700_000L), reconstructed.take(lease, clock(900_000)))
        val waiting = requireNotNull(newStore().readSnapshot())
        assertEquals(ProtectionConsentMode.ACTIVE, waiting.mode)
        assertEquals(beforeWait.generation, waiting.generation)
        assertEquals(beforeWait.consents(), waiting.consents())
        assertEquals(beforeWait.budget.attempts, waiting.budget.attempts)
        assertEquals(AutomaticCheckDecision.Recover, newStore().checkBudget(lease, clock(3_600_000)))
        assertEquals(listOf(300_000L, 600_000L), newStore().readSnapshot()?.budget?.attempts)
        assertEquals(AutomaticCheckDecision.Recover, newStore().take(lease, clock(3_600_000)))
        assertEquals(listOf(300_000L, 600_000L, 3_600_000L), newStore().readSnapshot()?.budget?.attempts)
        assertTrue(newStore().revokeTarget(identity))
        assertEquals(ProtectionConsentMode.DISABLED, newStore().readMode())
        assertTrue(newStore().take(lease, clock(3_900_000)) is AutomaticCheckDecision.Pause)
    }

    private fun newStore() = ProtectionConsentStore(AtomicProtectionAuthorityStorage(isolatedContext))
    private fun authorityFile() = File(directory, AUTHORITY_FILE)
    private fun clock(elapsed: Long) = BudgetClock(1L, elapsed, 1_000_000L + elapsed)
    private fun grant(store: ProtectionConsentStore, elapsed: Long = 0L) =
        store.grantExplicit(identity, VERIFIED_AUTOSTART_FINGERPRINT, clock(elapsed))

    private companion object {
        const val AUTHORITY_FILE = "automatic-protection-authority-v1.bin"
    }
}
