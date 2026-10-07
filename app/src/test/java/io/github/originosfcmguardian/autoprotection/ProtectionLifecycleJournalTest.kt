package io.github.originosfcmguardian.autoprotection

import org.junit.Assert.*
import org.junit.Test

class ProtectionLifecycleJournalTest {
    private fun event(kind: ProtectionLifecycleEventKind = ProtectionLifecycleEventKind.SERVICE_RUNNING,
        pid: Int = 123, at: Long = 1000) = ProtectionLifecycleEvent(at, 50, pid, kind)
    private fun exit(pid: Int = 123, at: Long = 1000, reason: Int = 9) =
        ProtectionLifecycleEvent(at, 0, pid, ProtectionLifecycleEventKind.SYSTEM_PROCESS_EXIT,
            systemExitReason = reason, systemExitStatus = 0)

    @Test fun `ring and persistence remain bounded with drop evidence`() {
        var snapshot = ProtectionLifecycleSnapshot()
        repeat(100) { snapshot = appendProtectionLifecycle(snapshot, event(at = it + 1L), dropped = 1) }
        assertEquals(64, snapshot.events.size)
        assertEquals(100L, snapshot.droppedEvents)
        val bytes = ProtectionLifecycleCodec.encode(snapshot)
        assertTrue(bytes.size < ProtectionLifecycleCodec.MAX_BYTES)
        assertEquals(snapshot, ProtectionLifecycleCodec.decode(bytes))
    }

    @Test fun `no clean service end is marked unknown on a different process only`() {
        val live = appendProtectionLifecycle(ProtectionLifecycleSnapshot(), event())
        val replacement = beginProtectionProcess(live, event(ProtectionLifecycleEventKind.PROCESS_STARTED, pid = 124))
        assertEquals(ProtectionLifecycleReason.UNKNOWN,
            replacement.events.first { it.kind == ProtectionLifecycleEventKind.PREVIOUS_SESSION_UNFINISHED }.reason)
        assertFalse(replacement.serviceExpected)
        val same = beginProtectionProcess(live, event(ProtectionLifecycleEventKind.PROCESS_STARTED))
        assertFalse(same.events.any { it.kind == ProtectionLifecycleEventKind.PREVIOUS_SESSION_UNFINISHED })
    }

    @Test fun `clean end and stale other process destroy remain distinct`() {
        val live = appendProtectionLifecycle(ProtectionLifecycleSnapshot(), event())
        val ended = appendProtectionLifecycle(live, event(ProtectionLifecycleEventKind.SERVICE_DESTROYED))
        assertFalse(ended.serviceExpected)
        assertTrue(appendProtectionLifecycle(live, event(ProtectionLifecycleEventKind.SERVICE_DESTROYED, pid = 124)).serviceExpected)
        val started = beginProtectionProcess(ended, event(ProtectionLifecycleEventKind.PROCESS_STARTED, pid = 124))
        assertFalse(started.events.any { it.kind == ProtectionLifecycleEventKind.PREVIOUS_SESSION_UNFINISHED })
    }

    @Test fun `equal timestamp system exits dedup tuples rather than lose another record`() {
        val first = mergeProtectionSystemExits(ProtectionLifecycleSnapshot(), listOf(exit()))
        val second = mergeProtectionSystemExits(first, listOf(exit(), exit(pid = 124), exit(at = 500)))
        assertEquals(2, second.events.size)
        assertEquals(1000L, second.exitHistoryThroughMillis)
        assertEquals(second, mergeProtectionSystemExits(second, listOf(exit(), exit(pid = 124))))
        assertFalse(mergeProtectionSystemExits(second, null).exitHistoryAvailable)
    }

    @Test fun `codec rejects corruption oversized and invalid records`() {
        val bytes = ProtectionLifecycleCodec.encode(appendProtectionLifecycle(ProtectionLifecycleSnapshot(), event()))
        assertThrows(IllegalArgumentException::class.java) { ProtectionLifecycleCodec.decode(bytes.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }) }
        assertThrows(IllegalArgumentException::class.java) { ProtectionLifecycleCodec.decode(ByteArray(ProtectionLifecycleCodec.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { event(pid = 0) }
        assertThrows(IllegalArgumentException::class.java) { ProtectionLifecycleSnapshot(droppedEvents = -1) }
        assertThrows(IllegalArgumentException::class.java) { ProtectionLifecycleSnapshot(availability = ProtectionLifecycleAvailability.CORRUPT).let(ProtectionLifecycleCodec::encode) }
    }

    @Test fun `diagnostic model has no raw error description or credential strings`() {
        val properties = ProtectionLifecycleEvent::class.java.declaredFields.map { it.name }
        assertFalse(properties.any { it in listOf("message", "description", "trace", "sessionId", "token", "packageName") })
        assertEquals(ProtectionLifecycleCodec.decode(ProtectionLifecycleCodec.encode(ProtectionLifecycleSnapshot())).availability,
            ProtectionLifecycleAvailability.EMPTY)
    }
}
