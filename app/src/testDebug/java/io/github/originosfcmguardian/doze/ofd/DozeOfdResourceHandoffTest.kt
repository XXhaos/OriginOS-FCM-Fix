package io.github.originosfcmguardian.doze.ofd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DozeOfdResourceHandoffTest {
    @Test fun publishedResourceIsClosedEvenWhenFutureResultWasNeverReceived() {
        val slot = DozeOfdResourceHandoff<CountedResource>()
        val resource = CountedResource()
        // Producer has passed its last check and completed; caller never obtains its result.
        slot.produce { resource }
        assertEquals(0, resource.closed.get())
        assertTrue(slot.close())
        assertEquals(1, resource.closed.get())
        assertTrue(slot.close())
        assertEquals(1, resource.closed.get())
    }

    @Test fun revocationBeforePublicationMakesProducerCloseTheLateResource() {
        val slot = DozeOfdResourceHandoff<CountedResource>()
        val resource = CountedResource()
        val opened = CountDownLatch(1)
        val allowPublication = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val producer = Thread {
            try {
                slot.produce {
                    opened.countDown()
                    allowPublication.await()
                    resource
                }
            } finally { exited.countDown() }
        }
        producer.start()
        try {
            assertTrue(opened.await(2, TimeUnit.SECONDS))
            assertFalse(slot.close())
            assertEquals(0, resource.closed.get())
        } finally {
            allowPublication.countDown()
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            producer.join(2_000)
        }
        assertEquals(1, resource.closed.get())
        assertTrue(slot.close())
        assertEquals(1, resource.closed.get())
    }

    @Test fun resourceClosureStillInFlightCannotConfirmCleanup() {
        val slot = DozeOfdResourceHandoff<AutoCloseable>()
        val closing = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        val exited = CountDownLatch(1)
        slot.produce { AutoCloseable { closing.countDown(); allowClose.await() } }
        val closer = Thread { try { slot.close() } finally { exited.countDown() } }
        closer.start()
        try {
            assertTrue(closing.await(2, TimeUnit.SECONDS))
            assertFalse(slot.close())
        } finally {
            allowClose.countDown()
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            closer.join(2_000)
        }
        assertTrue(slot.close())
    }

    @Test fun timedOutExportBorrowKeepsPairOpenUntilItsRealFinallyExits() {
        val slot = DozeOfdResourceHandoff<CountedResource>()
        val resource = CountedResource()
        val gate = DozeOfdFixtureGate()
        assertTrue(gate.enter())
        slot.produce { resource }
        val borrowing = CountDownLatch(1)
        val allowRpcExit = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val worker = Thread {
            try {
                gate.observe {
                    slot.withBorrow {
                        borrowing.countDown()
                        allowRpcExit.await()
                    }
                }
            } finally { exited.countDown() }
        }
        worker.start()
        try {
            assertTrue(borrowing.await(2, TimeUnit.SECONDS))
            gate.markUnknown()
            assertFalse(slot.close())
            assertEquals(0, resource.closed.get())
            assertFalse(gate.finish(true))
        } finally {
            allowRpcExit.countDown()
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            worker.join(2_000)
        }
        assertEquals(1, resource.closed.get())
        assertTrue(slot.close())
        assertFalse(gate.enter())
    }

    @Test fun callerBorrowsWithoutTakingOwnershipAndCloseFailureStaysUnconfirmed() {
        val slot = DozeOfdResourceHandoff<AutoCloseable>()
        val resource = AutoCloseable { error("close failed") }
        slot.produce { resource }
        assertSame(resource, slot.borrow())
        assertFalse(slot.close())
        assertFalse(slot.close())
    }

    private class CountedResource : AutoCloseable {
        val closed = AtomicInteger()
        override fun close() { closed.incrementAndGet() }
    }
}
