package io.github.originosfcmguardian.autoprotection

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Closed vocabulary only: no exception messages, Intent extras, trace or package authority. */
enum class ProtectionLifecycleEventKind {
    PROCESS_STARTED, PREVIOUS_SESSION_UNFINISHED, SYSTEM_PROCESS_EXIT,
    SERVICE_CREATED, SERVICE_START, SERVICE_RESTORE, SERVICE_RUNNING, SERVICE_DESTROYED,
    SERVICE_STOP_REQUESTED, SERVICE_TASK_REMOVED,
    TEST_SERVICE_STOP,
    EXPLICIT_MASTER_OFF, RUNTIME_PAUSED, RUNTIME_INTERRUPTED,
    RESTORE_REJECTED, RESTORE_START_REQUESTED, RESTORE_LAUNCH_FAILED,
    TILE_LISTENING, TILE_RESTORE_REQUESTED, TILE_RESTORE_RESULT,
}

enum class ProtectionLifecycleReason {
    NONE, IO_ERROR, SECURITY_ERROR, STATE_ERROR, TIMEOUT, OTHER_EXCEPTION,
    PRIVILEGE, AUTHORITY, JOURNAL, IDENTITY, FOREGROUND_SERVICE, RECOVERY_RESULT, UNKNOWN,
    HOME_MASTER_OFF, NOTIFICATION_STOP,
}

enum class ProtectionLifecycleAvailability { AVAILABLE, EMPTY, CORRUPT, UNAVAILABLE }

data class ProtectionLifecycleEvent(
    val atMillis: Long,
    val elapsedMillis: Long,
    val processId: Int,
    val kind: ProtectionLifecycleEventKind,
    val reason: ProtectionLifecycleReason = ProtectionLifecycleReason.NONE,
    val systemExitReason: Int? = null,
    val systemExitStatus: Int? = null,
) {
    init {
        require(atMillis > 0 && elapsedMillis >= 0 && processId > 0)
        require((kind == ProtectionLifecycleEventKind.SYSTEM_PROCESS_EXIT) == (systemExitReason != null))
        require((systemExitReason == null) == (systemExitStatus == null))
        require(systemExitReason == null || systemExitReason in 0..100)
    }
}

data class ProtectionLifecycleSnapshot(
    val availability: ProtectionLifecycleAvailability = ProtectionLifecycleAvailability.EMPTY,
    val events: List<ProtectionLifecycleEvent> = emptyList(),
    val droppedEvents: Long = 0,
    val serviceExpected: Boolean = false,
    val serviceProcessId: Int = 0,
    val exitHistoryThroughMillis: Long = 0,
    val exitHistoryAvailable: Boolean = false,
) {
    init {
        require(events.size <= ProtectionLifecycleCodec.MAX_EVENTS && droppedEvents >= 0)
        require(serviceProcessId >= 0 && exitHistoryThroughMillis >= 0)
        require(!serviceExpected || serviceProcessId > 0)
    }
}

internal fun appendProtectionLifecycle(
    prior: ProtectionLifecycleSnapshot, event: ProtectionLifecycleEvent, dropped: Long = 0,
): ProtectionLifecycleSnapshot {
    require(dropped >= 0)
    val starting = event.kind in setOf(ProtectionLifecycleEventKind.SERVICE_CREATED,
        ProtectionLifecycleEventKind.SERVICE_START, ProtectionLifecycleEventKind.SERVICE_RESTORE,
        ProtectionLifecycleEventKind.SERVICE_RUNNING)
    val ending = event.kind == ProtectionLifecycleEventKind.SERVICE_DESTROYED &&
        event.processId == prior.serviceProcessId
    return prior.copy(availability = ProtectionLifecycleAvailability.AVAILABLE,
        events = (prior.events + event).takeLast(ProtectionLifecycleCodec.MAX_EVENTS),
        droppedEvents = Math.addExact(prior.droppedEvents, dropped),
        serviceExpected = if (starting) true else if (ending) false else prior.serviceExpected,
        serviceProcessId = if (starting) event.processId else prior.serviceProcessId)
}

/** A missing pair is not proof of an OEM kill; async records and updates can also interrupt it. */
internal fun beginProtectionProcess(
    prior: ProtectionLifecycleSnapshot, started: ProtectionLifecycleEvent,
): ProtectionLifecycleSnapshot {
    require(started.kind == ProtectionLifecycleEventKind.PROCESS_STARTED)
    val previous = if (prior.serviceExpected && prior.serviceProcessId != started.processId)
        appendProtectionLifecycle(prior, started.copy(kind = ProtectionLifecycleEventKind.PREVIOUS_SESSION_UNFINISHED,
            reason = ProtectionLifecycleReason.UNKNOWN)) else prior
    return appendProtectionLifecycle(previous.copy(serviceExpected = false), started)
}

/** Inclusive boundary plus tuple dedup preserves distinct equal-timestamp exit records. */
internal fun mergeProtectionSystemExits(
    prior: ProtectionLifecycleSnapshot, exits: List<ProtectionLifecycleEvent>?,
): ProtectionLifecycleSnapshot {
    if (exits == null) return prior.copy(exitHistoryAvailable = false)
    require(exits.size <= 64 && exits.all { it.kind == ProtectionLifecycleEventKind.SYSTEM_PROCESS_EXIT })
    var next = prior
    exits.sortedWith(compareBy<ProtectionLifecycleEvent> { it.atMillis }.thenBy { it.processId }).forEach { exit ->
        if (exit.atMillis >= prior.exitHistoryThroughMillis && next.events.none {
                it.kind == exit.kind && it.atMillis == exit.atMillis && it.processId == exit.processId &&
                    it.systemExitReason == exit.systemExitReason && it.systemExitStatus == exit.systemExitStatus })
            next = appendProtectionLifecycle(next, exit)
    }
    return next.copy(exitHistoryAvailable = true,
        exitHistoryThroughMillis = maxOf(prior.exitHistoryThroughMillis, exits.maxOfOrNull { it.atMillis } ?: 0))
}

internal object ProtectionLifecycleCodec {
    const val MAX_EVENTS = 64
    const val MAX_BYTES = 32_768
    fun encode(value: ProtectionLifecycleSnapshot): ByteArray {
        require(value.availability in setOf(ProtectionLifecycleAvailability.AVAILABLE, ProtectionLifecycleAvailability.EMPTY))
        val payload = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(0x474C4A31); out.writeInt(1)
            out.writeLong(value.droppedEvents); out.writeBoolean(value.serviceExpected)
            out.writeInt(value.serviceProcessId); out.writeLong(value.exitHistoryThroughMillis)
            out.writeBoolean(value.exitHistoryAvailable); out.writeInt(value.events.size)
            value.events.forEach { event ->
                out.writeLong(event.atMillis); out.writeLong(event.elapsedMillis); out.writeInt(event.processId)
                out.writeUTF(event.kind.name); out.writeUTF(event.reason.name)
                out.writeBoolean(event.systemExitReason != null)
                event.systemExitReason?.let { out.writeInt(it); out.writeInt(event.systemExitStatus!!) }
            }
        } }.toByteArray()
        return (payload + MessageDigest.getInstance("SHA-256").digest(payload)).also { require(it.size <= MAX_BYTES) }
    }
    fun decode(bytes: ByteArray): ProtectionLifecycleSnapshot {
        require(bytes.size in 32..MAX_BYTES)
        val payload = bytes.copyOf(bytes.size - 32)
        require(MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(payload), bytes.copyOfRange(payload.size, bytes.size)))
        return DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == 0x474C4A31 && input.readInt() == 1)
            val dropped = input.readLong(); val expected = input.readBoolean(); val pid = input.readInt()
            val watermark = input.readLong(); val available = input.readBoolean(); val count = input.readInt()
            require(count in 0..MAX_EVENTS)
            val events = List(count) {
                val at = input.readLong(); val elapsed = input.readLong(); val process = input.readInt()
                val kind = ProtectionLifecycleEventKind.valueOf(input.readUTF())
                val reason = ProtectionLifecycleReason.valueOf(input.readUTF())
                val hasExit = input.readBoolean()
                ProtectionLifecycleEvent(at, elapsed, process, kind, reason,
                    if (hasExit) input.readInt() else null, if (hasExit) input.readInt() else null)
            }
            require(input.available() == 0)
            ProtectionLifecycleSnapshot(if (events.isEmpty()) ProtectionLifecycleAvailability.EMPTY else ProtectionLifecycleAvailability.AVAILABLE,
                events, dropped, expected, pid, watermark, available)
        }
    }
}
