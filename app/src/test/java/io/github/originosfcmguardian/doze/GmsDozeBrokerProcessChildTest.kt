package io.github.originosfcmguardian.doze

import io.github.originosfcmguardian.core.doze.GmsDozeBrokerAdmission
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerCodec
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerIntent
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerKey
import io.github.originosfcmguardian.core.doze.GmsDozeBrokerOperation
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class GmsDozeBrokerProcessChildTest {
    private class Input(bytes: ByteArray = byteArrayOf(), val gate: CountDownLatch? = null,
        private val failRead: Boolean = false, private val failClose: Boolean = false,
        val closeGate: CountDownLatch? = null) : InputStream() {
        private val data = ByteArrayInputStream(bytes)
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        val entered = CountDownLatch(1)
        override fun read(): Int = error("Buffered read expected")
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            reads.incrementAndGet()
            entered.countDown()
            gate?.await()
            if (failRead) throw IOException("Injected read failure")
            return data.read(bytes, offset, length)
        }
        override fun close() {
            closes.incrementAndGet()
            closeGate?.await()
            if (failClose) throw IOException("Injected close failure")
        }
    }

    private class ChildProcess(val out: Input = Input(), val err: Input = Input(),
        exited: Boolean = true, val stdinGate: CountDownLatch? = null,
        private val failStdin: Boolean = false, private val code: Int = 0,
        private val failExitValue: Boolean = false) : Process() {
        val exit = CountDownLatch(if (exited) 0 else 1)
        val waitEntered = CountDownLatch(1)
        val waits = AtomicInteger()
        val exitValueCalls = AtomicInteger()
        val streamGets = AtomicInteger()
        val stdinCloses = AtomicInteger()
        val destroyed = AtomicInteger()
        private val stdin = object : ByteArrayOutputStream() {
            override fun close() {
                stdinCloses.incrementAndGet()
                stdinGate?.await()
                if (failStdin) throw IOException("Injected stdin close failure")
            }
        }
        override fun getOutputStream(): OutputStream { streamGets.incrementAndGet(); return stdin }
        override fun getInputStream(): InputStream { streamGets.incrementAndGet(); return out }
        override fun getErrorStream(): InputStream { streamGets.incrementAndGet(); return err }
        override fun waitFor(): Int { exit.await(); return code }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waits.incrementAndGet(); waitEntered.countDown()
            return exit.await(timeout, unit)
        }
        override fun exitValue(): Int {
            exitValueCalls.incrementAndGet()
            if (exit.count != 0L) throw IllegalThreadStateException()
            if (failExitValue) throw IllegalStateException("Injected exitValue failure")
            return code
        }
        override fun isAlive(): Boolean = error("Process liveness must not supply exit evidence")
        override fun destroy() { destroyed.incrementAndGet() }
    }

    private fun complete(evidence: GmsDozeBrokerProcessEvidence): Boolean = evidence.childExited &&
        evidence.stdoutEofAndDrained && evidence.stderrEofAndDrained && evidence.handlesClosed

    @Test fun constructorOnlyRetainsHandleAndRealExitDualEofThreeClosesComplete() {
        val process = ChildProcess(Input("out".toByteArray()), Input("err".toByteArray()))
        val child = GmsDozeBrokerProcessChild(process)
        assertEquals(0, process.waits.get())
        assertEquals(0, process.streamGets.get())
        assertEquals(0, process.stdinCloses.get())
        val result = child.awaitEvidence(1000)
        assertTrue(complete(result))
        assertEquals(0, result.exitCode)
        assertEquals("out", result.stdout)
        assertEquals("err", result.stderr)
        assertFalse(result.outputTruncated)
        assertEquals(0, process.destroyed.get())
    }

    @Test fun nonzeroExitStillCompletesCleanupAndRetainsTheActualFailureCode() {
        val process = ChildProcess(code = 23)
        val child = GmsDozeBrokerProcessChild(process)
        val result = child.awaitEvidence(1000)
        assertTrue(complete(result))
        assertEquals(23, result.exitCode)
        assertTrue(result.exitCode != 0)
        val repeated = child.awaitEvidence(1000)
        assertTrue(complete(repeated))
        assertEquals(23, repeated.exitCode)
        assertEquals(1, process.waits.get())
        assertEquals(1, process.exitValueCalls.get())
        assertEquals(0, process.destroyed.get())
    }

    @Test fun successfulWaitWithThrowingExitValueCannotInventAnExitCodeOrChildExit() {
        val process = ChildProcess(code = 23, failExitValue = true)
        val child = GmsDozeBrokerProcessChild(process)
        val result = child.awaitEvidence(1000)
        assertEquals(1, process.waits.get())
        assertEquals(1, process.exitValueCalls.get())
        assertNull(result.exitCode)
        assertFalse(result.childExited)
        assertFalse(complete(result))
        assertNull(child.awaitEvidence(1000).exitCode)
        assertEquals(0, process.destroyed.get())
    }

    @Test fun childExitDoesNotSubstituteForEitherStreamEofOrCollectorTermination() {
        for (stderr in listOf(false, true)) {
            val gate = CountDownLatch(1)
            val blocked = Input(gate = gate)
            val process = if (stderr) ChildProcess(err = blocked) else ChildProcess(out = blocked)
            val child = GmsDozeBrokerProcessChild(process)
            try {
                val pending = child.awaitEvidence(20)
                assertTrue(pending.childExited)
                assertFalse(complete(pending))
                assertFalse(pending.handlesClosed)
                assertFalse(if (stderr) pending.stderrEofAndDrained else pending.stdoutEofAndDrained)
            } finally { gate.countDown() }
            assertTrue(complete(child.awaitEvidence(1000)))
        }
        val closeGate = CountDownLatch(1)
        val child = GmsDozeBrokerProcessChild(ChildProcess(out = Input(closeGate = closeGate)))
        try {
            val pending = child.awaitEvidence(20)
            assertFalse(pending.stdoutEofAndDrained) // EOF is not a joined collector yet.
            assertFalse(pending.handlesClosed)
        } finally { closeGate.countDown() }
        assertTrue(complete(child.awaitEvidence(1000)))
    }

    @Test fun dualEofDoesNotSubstituteForActualChildExitAndTimeoutRetainsHandle() {
        val process = ChildProcess(exited = false)
        val child = GmsDozeBrokerProcessChild(process)
        try {
            val first = child.awaitEvidence(20)
            assertFalse(complete(first))
            assertNull(first.exitCode)
            val second = child.awaitEvidence(20)
            assertFalse(second.childExited)
            assertNull(second.exitCode)
            assertEquals(0, process.exitValueCalls.get())
            assertEquals(0, process.destroyed.get())
        } finally { process.exit.countDown() }
        val completed = child.awaitEvidence(1000)
        assertTrue(complete(completed))
        assertEquals(0, completed.exitCode)
        assertEquals(1, process.exitValueCalls.get())
    }

    @Test fun interruptionReadCloseAndBlockedStdinNeverInventCompletion() {
        val process = ChildProcess(exited = false)
        val child = GmsDozeBrokerProcessChild(process)
        val result = AtomicReference<GmsDozeBrokerProcessEvidence>()
        val waiter = thread { result.set(child.awaitEvidence(1000)) }
        try {
            assertTrue(process.waitEntered.await(1, TimeUnit.SECONDS))
            waiter.interrupt()
            waiter.join(1000)
            assertFalse(waiter.isAlive)
            assertFalse(complete(requireNotNull(result.get())))
        } finally { process.exit.countDown(); waiter.join(1000) }
        assertTrue(complete(child.awaitEvidence(1000)))
        for (failed in listOf(ChildProcess(out = Input(failRead = true)),
            ChildProcess(err = Input(failClose = true)), ChildProcess(failStdin = true))) {
            val retained = GmsDozeBrokerProcessChild(failed)
            assertFalse(complete(retained.awaitEvidence(1000)))
            assertFalse(complete(retained.awaitEvidence(1000)))
            assertEquals(1, failed.stdinCloses.get())
            assertEquals(1, failed.out.closes.get())
            assertEquals(1, failed.err.closes.get())
        }
        val stdinGate = CountDownLatch(1)
        val blocked = GmsDozeBrokerProcessChild(ChildProcess(stdinGate = stdinGate))
        try { assertFalse(complete(blocked.awaitEvidence(20))) }
        finally { stdinGate.countDown() }
        assertTrue(complete(blocked.awaitEvidence(1000)))
    }

    @Test fun overflowStillDrainsSameCollectorsAndWrongChildCannotProduceBrokerEvidence() {
        val process = ChildProcess(Input(ByteArray(GmsDozeBrokerProcessChild.STDOUT_BYTES + 500) { 65 }),
            Input(ByteArray(GmsDozeBrokerProcessChild.STDERR_BYTES + 500) { 66 }))
        val child = GmsDozeBrokerProcessChild(process)
        val facts = child.awaitEvidence(1000)
        assertTrue(complete(facts))
        assertTrue(facts.outputTruncated)
        assertEquals(GmsDozeBrokerProcessChild.STDOUT_BYTES, facts.stdout.length)
        assertEquals(GmsDozeBrokerProcessChild.STDERR_BYTES, facts.stderr.length)
        assertTrue(complete(child.awaitEvidence(1000)))
        assertEquals(3, process.streamGets.get())
        assertEquals(1, process.stdinCloses.get())
        assertEquals(1, process.out.closes.get())
        assertEquals(1, process.err.closes.get())

        val key = GmsDozeBrokerKey("broker", 1)
        val state = GmsDozeBrokerCodec.initialState().copy(revision = 2, lastBrokerGeneration = 1,
            pin = key, brokerClosed = false, preparationAllowed = true)
        val broker = GmsDozeBrokerAdmission(key)
        assertTrue(broker.activate(GmsDozeBrokerCodec.decodeProof(GmsDozeBrokerCodec.encode(state))))
        val intent = GmsDozeBrokerIntent(key, "tx", GmsDozeBrokerOperation.GLOBAL_ADD, 0, 3)
        broker.start(GmsDozeBrokerCodec.decodeProof(GmsDozeBrokerCodec.encode(state.copy(revision = 3,
            intent = intent)))) { child }
        assertNull(broker.cleanupEvidence(GmsDozeBrokerProcessChild(ChildProcess()), true, true, true, true))
        assertTrue(requireNotNull(broker.cleanupEvidence(child, facts.childExited,
            facts.stdoutEofAndDrained, facts.stderrEofAndDrained, facts.handlesClosed)).complete())
        assertTrue(broker.isBusy()) // Still needs matching durable result CAS/readback and release.
    }
}
