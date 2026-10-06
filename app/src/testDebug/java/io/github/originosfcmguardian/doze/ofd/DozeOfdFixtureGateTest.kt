package io.github.originosfcmguardian.doze.ofd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DozeOfdFixtureGateTest {
    @Test fun unknownCannotBeClearedByLateSuccessfulCleanup() {
        val gate = DozeOfdFixtureGate()
        assertTrue(gate.enter())
        gate.markUnknown()
        assertTrue(gate.finish(true))
        assertFalse(gate.enter())
    }

    @Test fun realActionExitIsRequiredEvenWhenPublicCallerGivesUp() {
        val gate = DozeOfdFixtureGate()
        assertTrue(gate.enter())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val worker = Thread {
            try {
                gate.observe {
                    entered.countDown()
                    release.await()
                }
            } finally { exited.countDown() }
        }
        worker.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFalse(gate.finish(true))
        } finally {
            release.countDown()
            assertTrue(exited.await(2, TimeUnit.SECONDS))
            worker.join(2_000)
        }
        assertTrue(gate.finish(true))
        assertFalse(gate.enter())
    }

    @Test fun confirmedCleanFixtureMayReleaseOnlyItsOwnEntrance() {
        val gate = DozeOfdFixtureGate()
        assertTrue(gate.enter())
        assertFalse(gate.enter())
        assertTrue(gate.finish(true))
        assertTrue(gate.enter())
    }
}
