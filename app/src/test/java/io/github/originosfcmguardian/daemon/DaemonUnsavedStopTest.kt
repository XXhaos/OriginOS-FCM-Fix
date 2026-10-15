package io.github.originosfcmguardian.daemon

import org.junit.Assert.*
import org.junit.Test

class DaemonUnsavedStopTest {
    private val stop = DaemonAppStopIntent("5177d12d-7c6d-41d9-bcc2-ef2eb3f4d50d",
        DaemonMigrationIntent(RootFixtures.owner, "67d39891-65b4-48d2-bba8-ec3df1b7f950"), DaemonAppCommand(DaemonWire.MASTER_OFF))
    @Test fun failedWriteBeforeAnyArtifactCannotErasePendingOffAndRetryKeepsExactId() {
        val memory = DaemonUnsavedStop()
        memory.retain(stop)
        RootFixtures.fails { memory.persist { false } }
        assertEquals(stop, memory.current())
        RootFixtures.fails { memory.persist { error("storage unavailable before file creation") } }
        assertEquals(stop, memory.current())
        assertEquals(stop, memory.persist { assertEquals(stop, it); true })
        assertNull(memory.current())
    }
    @Test fun failedBootstrapAfterDurableSaveDoesNotEraseTheStoredNegativeIntent() {
        val memory = DaemonUnsavedStop()
        var durable: DaemonAppStopIntent? = null
        memory.retain(stop)
        val saved = memory.persist { durable = it; true }
        RootFixtures.fails { check(saved == stop); error("bootstrap unavailable") }
        assertNull(memory.current())
        assertEquals(stop, durable) // Restoration must resolve this, not reconcile the old ACTIVE.
    }
}
