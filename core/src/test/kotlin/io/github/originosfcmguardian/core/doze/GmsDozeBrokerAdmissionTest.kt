package io.github.originosfcmguardian.core.doze

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GmsDozeBrokerAdmissionTest {
    /** Exact-byte atomic HOST fake only; this is not a cross-process/storage qualification. */
    private class MemoryPort(initiallyAllowed: Boolean = true) : GmsDozeBrokerDurablePort {
        var state = GmsDozeBrokerState(0, 0, null, true, initiallyAllowed, 0, null, null)
            private set
        private var bytes = encode(state)
        var rejectCas = false
        var mismatchedReadback = false
        private var fenced = false
        var beforeCas: (() -> Unit)? = null
        @Synchronized override fun readExact(): GmsDozeBrokerProof {
            check(!fenced) { "Unknown commit remains fenced" }
            return GmsDozeBrokerProof(if (mismatchedReadback) bytes + byteArrayOf(9) else bytes, state)
        }
        override fun compareAndSet(expectedBytes: ByteArray, replacement: GmsDozeBrokerState): ByteArray? {
            beforeCas?.invoke()
            return synchronized(this) {
                check(!fenced)
                if (rejectCas || !bytes.contentEquals(expectedBytes)) null else {
                    check(replacement.revision == state.revision + 1)
                    state = replacement
                    bytes = encode(replacement)
                    bytes.clone()
                }
            }
        }
        @Synchronized override fun fenceUncertainCommit() { fenced = true }
        @Synchronized fun corruptNextReadback() { mismatchedReadback = true }
        /** Test-only bypass, not a source of production consent. */
        @Synchronized fun explicitConsentForTest() {
            check(state.brokerClosed && state.intent == null && !state.preparationAllowed)
            state = state.copy(revision = state.revision + 1, preparationAllowed = true)
            bytes = encode(state)
        }
        private fun encode(value: GmsDozeBrokerState) = value.toString().toByteArray(Charsets.UTF_8)
    }

    private class Child : GmsDozeBrokerChild
    private data class Rig(val store: MemoryPort, val app: GmsDozeBrokerCoordinator,
        val broker: GmsDozeBrokerAdmission, val key: GmsDozeBrokerKey)

    private fun rig(): Rig {
        val store = MemoryPort()
        val app = GmsDozeBrokerCoordinator(store)
        val pin = assertNotNull(app.pin("broker_one"))
        val key = assertNotNull(pin.state.pin)
        val broker = GmsDozeBrokerAdmission(key)
        assertTrue(broker.activate(pin))
        assertFalse(broker.activate(pin))
        return Rig(store, app, broker, key)
    }

    @Test fun explicitEnableOnlyFromClosedOffWithoutRetainedIntent() {
        val store = MemoryPort(initiallyAllowed = false)
        val app = GmsDozeBrokerCoordinator(store)
        assertNull(app.pin("inert"))
        val enabled = assertNotNull(app.enableFromExplicitConsent())
        assertEquals(1L, enabled.state.revision)
        assertTrue(enabled.state.preparationAllowed)
        assertNull(app.enableFromExplicitConsent())
        val key = assertNotNull(assertNotNull(app.pin("broker")).state.pin)
        val off = assertNotNull(app.disable())
        assertNull(app.enableFromExplicitConsent()) // OFF is not a matching old-broker ACK.
        assertNotNull(app.acceptRevokeAck(GmsDozeBrokerRevokeAck(key, off.state.revokeVersion)))
        assertNotNull(app.enableFromExplicitConsent())
        val second = assertNotNull(assertNotNull(app.pin("next_broker")).state.pin)
        assertNotNull(app.prepare(second, "pending", GmsDozeBrokerOperation.GLOBAL_ADD))
        val pendingOff = assertNotNull(app.disable())
        assertNotNull(app.acceptRevokeAck(GmsDozeBrokerRevokeAck(second, pendingOff.state.revokeVersion)))
        assertNull(app.enableFromExplicitConsent())
        assertNotNull(store.state.intent)
    }

    @Test fun uniqueDurablePinCompetesAndRejectsOldNonce() {
        val store = MemoryPort()
        val ready = CountDownLatch(2)
        val go = CountDownLatch(1)
        store.beforeCas = { ready.countDown(); check(go.await(5, TimeUnit.SECONDS)) }
        val proofs = arrayOfNulls<GmsDozeBrokerProof>(2)
        val workers = (0..1).map { index -> thread {
            proofs[index] = GmsDozeBrokerCoordinator(store).pin("broker_$index")
        } }
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            workers.forEach { it.join(5_000); assertFalse(it.isAlive) }
        } finally { go.countDown() }
        store.beforeCas = null
        assertEquals(1, proofs.count { it != null })
        val winner = assertNotNull(proofs.first { it != null })
        val key = assertNotNull(winner.state.pin)
        assertEquals(1L, key.generation)
        val loser = GmsDozeBrokerKey("old_nonce", key.generation)
        assertFalse(GmsDozeBrokerAdmission(loser).activate(winner))
        val app = GmsDozeBrokerCoordinator(store)
        assertNull(app.pin("replacement"))
        assertNull(app.prepare(loser, "old_tx", GmsDozeBrokerOperation.LEGACY_ADD))
        assertNull(app.acceptDeath(GmsDozeBrokerDeath(loser)))
        assertEquals(key, store.state.pin)
        val oldBroker = GmsDozeBrokerAdmission(key)
        assertTrue(oldBroker.activate(winner))
        val off = assertNotNull(app.disable())
        assertNotNull(app.acceptRevokeAck(assertNotNull(oldBroker.revoke(key, off.state.revokeVersion))))
        store.explicitConsentForTest()
        assertNull(app.pin(key.nonce))
        val nextPin = assertNotNull(app.pin("fresh_broker"))
        assertEquals(key.generation + 1, nextPin.state.pin?.generation)
        assertFalse(oldBroker.activate(nextPin))
        assertFalse(GmsDozeBrokerAdmission(key).activate(nextPin))
    }

    @Test fun revokeAndActualStartHaveBothOrdersIncludingStuckStart() {
        val before = rig()
        val prepared = assertNotNull(before.app.prepare(before.key, "tx_before", GmsDozeBrokerOperation.GLOBAL_ADD))
        val off = assertNotNull(before.app.disable())
        val ack = assertNotNull(before.broker.revoke(before.key, off.state.revokeVersion))
        assertNotNull(before.app.acceptRevokeAck(ack))
        var starts = 0
        assertEquals(GmsDozeBrokerStartStatus.REJECTED, before.broker.start(prepared) { starts++; Child() }.status)
        assertEquals(0, starts)
        assertNotNull(before.store.state.intent)

        val after = rig()
        val pending = assertNotNull(after.app.prepare(after.key, "tx_after", GmsDozeBrokerOperation.LEGACY_REMOVE))
        val entered = CountDownLatch(1)
        val finishStart = CountDownLatch(1)
        val revokeEntered = CountDownLatch(1)
        var lateAck: GmsDozeBrokerRevokeAck? = null
        val starter = thread { after.broker.start(pending) {
            entered.countDown(); check(finishStart.await(5, TimeUnit.SECONDS)); Child()
        } }
        var revoker: Thread? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // Persistent OFF finishes while actual start is still inside the shell monitor.
            val stopping = assertNotNull(after.app.disable())
            assertFalse(after.store.state.preparationAllowed)
            assertFalse(after.store.state.brokerClosed)
            assertNotNull(after.store.state.intent)
            revoker = thread {
                revokeEntered.countDown()
                lateAck = after.broker.revoke(after.key, stopping.state.revokeVersion)
            }
            assertTrue(revokeEntered.await(5, TimeUnit.SECONDS))
            assertNull(lateAck)
            finishStart.countDown()
            starter.join(5_000); revoker.join(5_000)
            assertFalse(starter.isAlive); assertFalse(revoker.isAlive)
            assertNotNull(after.app.acceptRevokeAck(assertNotNull(lateAck)))
            assertTrue(after.broker.isBusy())
            assertTrue(after.store.state.brokerClosed)
            assertNotNull(after.store.state.intent)
        } finally { finishStart.countDown(); starter.join(5_000); revoker?.join(5_000) }
    }

    @Test fun lateMessagesAndTimeoutsCannotReopenOrReplaceBroker() {
        val rig = rig()
        val pin = rig.store.readExact()
        val prepared = assertNotNull(rig.app.prepare(rig.key, "tx_late", GmsDozeBrokerOperation.GLOBAL_REMOVE))
        val firstOff = assertNotNull(rig.app.disable())
        val oldAck = assertNotNull(rig.broker.revoke(rig.key, firstOff.state.revokeVersion))
        val latestOff = assertNotNull(rig.app.disable())
        assertNull(rig.app.acceptRevokeAck(oldAck))
        assertFalse(rig.store.state.brokerClosed)
        // Missing RPC/timeout has no completion or pin-replacement transition.
        assertNull(rig.app.pin("after_timeout"))
        assertNull(rig.app.prepare(rig.key, "new_tx", GmsDozeBrokerOperation.LEGACY_ADD))
        assertFalse(rig.broker.activate(pin))
        assertEquals(GmsDozeBrokerStartStatus.REJECTED, rig.broker.start(prepared) { Child() }.status)
        val freshAck = assertNotNull(rig.broker.revoke(rig.key, latestOff.state.revokeVersion))
        assertNotNull(rig.app.acceptRevokeAck(freshAck))
        val closed = rig.store.state
        assertNull(rig.broker.revoke(rig.key, firstOff.state.revokeVersion))
        assertNull(rig.app.acceptRevokeAck(oldAck))
        assertEquals(closed, rig.store.state)
        assertFalse(rig.store.state.preparationAllowed)
    }

    @Test fun startedChildAllowsAckButEveryMissingCompletionItemKeepsBusy() {
        // Four actual callee/cleanup facts and fifth result CAS+exact readback are independent.
        for (missing in 0..5) {
            val rig = rig()
            val prepared = assertNotNull(rig.app.prepare(rig.key, "tx_cleanup", GmsDozeBrokerOperation.GLOBAL_ADD))
            val child = Child()
            assertEquals(GmsDozeBrokerStartStatus.STARTED, rig.broker.start(prepared) { child }.status)
            val off = assertNotNull(rig.app.disable())
            assertNotNull(rig.app.acceptRevokeAck(assertNotNull(rig.broker.revoke(rig.key, off.state.revokeVersion))))
            assertTrue(rig.broker.isBusy())
            val evidence = assertNotNull(rig.broker.cleanupEvidence(child, missing != 0,
                missing != 1, missing != 2, missing != 3))
            if (missing == 4) rig.store.rejectCas = true
            if (missing == 5) rig.store.beforeCas = { rig.store.corruptNextReadback() }
            val committed = rig.app.commitResult(evidence)
            assertNull(committed)
            assertTrue(rig.broker.isBusy())
            assertNull(GmsDozeBrokerCoordinator(rig.store).pin("replacement"))
        }
        val rig = rig()
        val prepared = assertNotNull(rig.app.prepare(rig.key, "complete_tx", GmsDozeBrokerOperation.LEGACY_ADD))
        val child = Child()
        assertEquals(GmsDozeBrokerStartStatus.STARTED, rig.broker.start(prepared) { child }.status)
        assertNull(rig.broker.cleanupEvidence(Child(), true, true, true, true))
        val cleanup = assertNotNull(rig.broker.cleanupEvidence(child, true, true, true, true))
        assertNull(rig.app.commitResult(cleanup.copy(intent = cleanup.intent.copy(transactionId = "wrong_tx"))))
        val result = assertNotNull(rig.app.commitResult(cleanup))
        assertTrue(rig.broker.release(child, cleanup, result))
        assertFalse(rig.broker.isBusy())
        assertNull(rig.store.state.intent)
        // Revision fencing consumes old prepared messages even after busy is released.
        assertEquals(GmsDozeBrokerStartStatus.REJECTED, rig.broker.start(prepared) { Child() }.status)
        assertNull(rig.app.prepare(rig.key, "complete_tx", GmsDozeBrokerOperation.LEGACY_ADD))
    }

    @Test fun intentThenBrokerDeathBlocksReconstructedCoordinatorAndInertBroker() {
        val rig = rig()
        val prepared = assertNotNull(rig.app.prepare(rig.key, "surviving_child", GmsDozeBrokerOperation.GLOBAL_ADD))
        rig.broker.start(prepared) { Child() }
        val intent = rig.store.state.intent
        assertNotNull(rig.app.acceptDeath(GmsDozeBrokerDeath(rig.key)))
        assertTrue(rig.store.state.brokerClosed)
        assertEquals(intent, rig.store.state.intent)
        val reconstructed = GmsDozeBrokerCoordinator(rig.store)
        assertNull(reconstructed.pin("new_broker"))
        assertNull(reconstructed.prepare(rig.key, "new_intent", GmsDozeBrokerOperation.LEGACY_REMOVE))
        val inert = GmsDozeBrokerAdmission(GmsDozeBrokerKey("new_broker", rig.key.generation + 1))
        assertFalse(inert.activate(rig.store.readExact()))
        assertEquals(GmsDozeBrokerStartStatus.REJECTED, inert.start(prepared) { Child() }.status)
        assertTrue(rig.broker.isBusy()) // Process death is not child exit/drain/close evidence.
    }
}
