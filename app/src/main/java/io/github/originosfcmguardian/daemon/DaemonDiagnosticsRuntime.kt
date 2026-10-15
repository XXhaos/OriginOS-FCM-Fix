package io.github.originosfcmguardian.daemon

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import io.github.originosfcmguardian.BuildConfig
import io.github.originosfcmguardian.autoprotection.parseProtectionProcStatus
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class DaemonDiagnosticsOpportunity(val atMillis: Long, val elapsedMillis: Long,
    val state: DaemonDiagnosticsState, val stoppedCheckedAtMillis: Long? = null, val fcmCheckedAtMillis: Long? = null)

/** Pure single-writer journal. UNKNOWN_END has no invented death time, including across boots. */
internal class DaemonDiagnosticsJournal(private val storage: DaemonDiagnosticsStorage,
    private val sample: (DaemonDiagnosticsOpportunity) -> DaemonDiagnosticsSample) {
    private var value = DaemonDiagnosticsSnapshot()
    private var lastSampleElapsed: Long? = null
    private var lastOpportunityElapsed: Long? = null
    fun begin(session: DaemonDiagnosticsSession, previousFailures: Long = 0): DaemonDiagnosticsSnapshot {
        val prior = storage.read() ?: DaemonDiagnosticsSnapshot()
        var unknown = 0L
        val old = prior.sessions.map { s ->
            if (s.state !in setOf(DaemonDiagnosticsState.NORMAL_END, DaemonDiagnosticsState.UNKNOWN_END)) {
                unknown++; s.copy(state = DaemonDiagnosticsState.UNKNOWN_END, endedAtMillis = null, endedElapsedMillis = null)
            } else s
        }
        val all = old + session
        val retained = all.takeLast(DaemonDiagnosticsCodec.MAX_SESSIONS)
        val ids = retained.map { it.sessionId }.toSet()
        val samples = prior.samples.filter { it.sessionId in ids }
        val events = prior.events.filter { it.sessionId in ids }
        value = prior.copy(availability = DaemonDiagnosticsAvailability.AVAILABLE, currentSessionId = session.sessionId,
            sessions = retained, samples = samples, events = events,
            evictedSessions = add(prior.evictedSessions, (all.size - retained.size).toLong()),
            evictedSamples = add(prior.evictedSamples, (prior.samples.size - samples.size).toLong()),
            evictedEvents = add(prior.evictedEvents, (prior.events.size - events.size).toLong()),
            failedOperations = add(prior.failedOperations, previousFailures),
            unknownIntervals = add(prior.unknownIntervals, unknown))
        return commit(event(value, DaemonDiagnosticsEvent(session.sessionId, session.startedAtMillis,
            session.startedElapsedMillis, DaemonDiagnosticsState.BEGIN)))
    }
    fun observe(opportunity: DaemonDiagnosticsOpportunity, skipped: Long): DaemonDiagnosticsSnapshot {
        val current = value.sessions.last()
        val gap = lastOpportunityElapsed?.let { (opportunity.elapsedMillis - it).coerceAtLeast(0) } ?: 0
        lastOpportunityElapsed = opportunity.elapsedMillis
        val next = current.copy(lastCheckpointAtMillis = opportunity.atMillis,
            lastCheckpointElapsedMillis = opportunity.elapsedMillis, state = opportunity.state,
            stoppedCheckedAtMillis = opportunity.stoppedCheckedAtMillis ?: current.stoppedCheckedAtMillis,
            fcmCheckedAtMillis = opportunity.fcmCheckedAtMillis ?: current.fcmCheckedAtMillis,
            maxPollGapMillis = maxOf(current.maxPollGapMillis, gap),
            endedAtMillis = opportunity.atMillis.takeIf { opportunity.state == DaemonDiagnosticsState.NORMAL_END },
            endedElapsedMillis = opportunity.elapsedMillis.takeIf { opportunity.state == DaemonDiagnosticsState.NORMAL_END })
        var update = value.copy(sessions = value.sessions.dropLast(1) + next, skippedSamples = add(value.skippedSamples, skipped),
            unknownIntervals = add(value.unknownIntervals, if (gap > 90_000) 1 else 0))
        val transition = current.state != next.state
        if (transition) update = event(update, DaemonDiagnosticsEvent(current.sessionId, opportunity.atMillis, opportunity.elapsedMillis, next.state))
        val due = next.state != DaemonDiagnosticsState.NORMAL_END &&
            (lastSampleElapsed == null || opportunity.elapsedMillis >= lastSampleElapsed!! && opportunity.elapsedMillis - lastSampleElapsed!! >= SAMPLE_INTERVAL_MILLIS)
        if (due) {
            val captured = sample(opportunity)
            val all = update.samples + captured
            update = update.copy(samples = all.takeLast(DaemonDiagnosticsCodec.MAX_SAMPLES),
                evictedSamples = add(update.evictedSamples, (all.size - DaemonDiagnosticsCodec.MAX_SAMPLES).coerceAtLeast(0).toLong()))
            lastSampleElapsed = captured.elapsedMillis
        }
        // Regular opportunities update RAM, while disk writes remain low frequency.
        return if (transition || due) commit(update) else freeze(update.copy(generation = Math.addExact(value.generation, 1))).also { value = it }
    }
    private fun event(snapshot: DaemonDiagnosticsSnapshot, event: DaemonDiagnosticsEvent): DaemonDiagnosticsSnapshot {
        val all = snapshot.events + event
        return snapshot.copy(events = all.takeLast(DaemonDiagnosticsCodec.MAX_EVENTS),
            evictedEvents = add(snapshot.evictedEvents, (all.size - DaemonDiagnosticsCodec.MAX_EVENTS).coerceAtLeast(0).toLong()))
    }
    private fun commit(snapshot: DaemonDiagnosticsSnapshot): DaemonDiagnosticsSnapshot {
        val next = freeze(snapshot.copy(generation = Math.addExact(value.generation, 1)))
        storage.write(next); value = next; return next
    }
    companion object {
        const val SAMPLE_INTERVAL_MILLIS = 300_000L
        fun add(a: Long, b: Long) = if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b
        fun freeze(v: DaemonDiagnosticsSnapshot) = v.copy(sessions = Collections.unmodifiableList(v.sessions.toList()),
            events = Collections.unmodifiableList(v.events.toList()), samples = Collections.unmodifiableList(v.samples.toList()))
    }
}

/** One optional worker per Host. Calls from protection are bounded memory operations only. */
internal class DaemonDiagnosticsRuntime(
    private val session: DaemonDiagnosticsSession,
    private val open: () -> DaemonDiagnosticsStorage,
    private val capture: (DaemonDiagnosticsOpportunity) -> DaemonDiagnosticsSample,
    private val wall: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val processStartTicks: () -> Long? = { session.processStartTicks },
) {
    private val latest = AtomicReference(DaemonDiagnosticsSnapshot(DaemonDiagnosticsAvailability.INITIALIZING,
        currentSessionId = session.sessionId))
    private val monitor = Any()
    private val pending = ArrayDeque<DaemonDiagnosticsOpportunity>()
    private var ending: DaemonDiagnosticsOpportunity? = null
    private var scheduled = false
    private var disabled = false
    private var waitingForLock = false
    private var skipped = 0L
    private var storage: DaemonDiagnosticsStorage? = null
    private var journal: DaemonDiagnosticsJournal? = null
    private var lockFailures = 0L // Worker-owned; carried into the eventual durable BEGIN.
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { task -> Thread(task, "guardian-daemon-diagnostics").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    init { synchronized(monitor) { schedule() } }
    fun opportunity(state: DaemonDiagnosticsState, stoppedCheckedAtMillis: Long?, fcmCheckedAtMillis: Long?) {
        val opportunity = DaemonDiagnosticsOpportunity(wall().coerceAtLeast(1), elapsed(), state, stoppedCheckedAtMillis, fcmCheckedAtMillis)
        synchronized(monitor) {
            if (disabled || ending != null) return
            if (pending.lastOrNull()?.state == state) {
                pending.removeLast(); skipped = DaemonDiagnosticsJournal.add(skipped, 1)
            } else if (pending.size >= 16) {
                // Preserve the bounded transitions already queued; expose lost coverage.
                skipped = DaemonDiagnosticsJournal.add(skipped, 1); return
            }
            pending.addLast(opportunity); schedule()
        }
    }
    /** Called only after actual protection workers/children and authority lease have drained. */
    fun normalEnd() = synchronized(monitor) {
        if (disabled || ending != null) return@synchronized
        // A Host which never acquired diagnostic ownership cannot claim a durable normal end.
        // In particular, do not start a new lock retry just because the Host is draining.
        if (waitingForLock && !scheduled) {
            disabled = true; pending.clear(); worker.shutdown(); return@synchronized
        }
        ending = DaemonDiagnosticsOpportunity(wall().coerceAtLeast(1), elapsed(), DaemonDiagnosticsState.NORMAL_END)
        schedule()
    }
    fun snapshot(): DaemonDiagnosticsSnapshot = latest.get().copy(generatedAtMillis = wall().coerceAtLeast(1), generatedElapsedMillis = elapsed())
    fun isFinished(): Boolean = synchronized(monitor) { disabled }
    private fun schedule() {
        if (scheduled || disabled) return
        scheduled = true
        try { worker.execute(::drain) } catch (_: Exception) { fail(DaemonDiagnosticsAvailability.UNAVAILABLE) }
    }
    private fun drain() {
        try {
            if (journal == null) {
                val opened = open(); storage = opened
                synchronized(monitor) { waitingForLock = false }
                val writer = DaemonDiagnosticsJournal(opened) { source ->
                    // The queue opportunity is not the eventual resource-read time. Old queued
                    // transitions must not manufacture closely spaced backdated resource samples.
                    capture(source.copy(atMillis = wall().coerceAtLeast(1), elapsedMillis = elapsed()))
                }; journal = writer
                latest.set(writer.begin(session.copy(processStartTicks = runCatching(processStartTicks).getOrNull()), lockFailures))
            }
            while (true) {
                val work = synchronized(monitor) {
                    val next = if (pending.isNotEmpty()) pending.removeFirst() else ending
                    if (next == null) { scheduled = false; return }
                    val drops = skipped; skipped = 0; next to drops
                }
                latest.set(checkNotNull(journal).observe(work.first, work.second))
                if (work.first.state == DaemonDiagnosticsState.NORMAL_END) {
                    synchronized(monitor) { disabled = true; scheduled = false }
                    storage?.close(); worker.shutdown(); return
                }
            }
        } catch (failure: Exception) {
            if (failure is DaemonDiagnosticsLocked) {
                // The previous Host may have released CONTROL while its independent END write
                // still owns this lease. Retry ONLY on a later existing poll opportunity.
                runCatching { storage?.close() }; storage = null; journal = null
                lockFailures = DaemonDiagnosticsJournal.add(lockFailures, 1)
                val stop = synchronized(monitor) {
                    latest.updateAndGet { it.copy(availability = DaemonDiagnosticsAvailability.LOCKED,
                        failedOperations = DaemonDiagnosticsJournal.add(it.failedOperations, 1)) }
                    waitingForLock = true; scheduled = false
                    skipped = DaemonDiagnosticsJournal.add(skipped, pending.size.toLong()); pending.clear()
                    (ending != null).also { if (it) disabled = true }
                }
                if (stop) worker.shutdown()
                return
            }
            fail(when (failure) { is DaemonDiagnosticsCorrupt -> DaemonDiagnosticsAvailability.CORRUPT
                else -> DaemonDiagnosticsAvailability.UNAVAILABLE })
            runCatching { storage?.close() }; worker.shutdown()
        }
    }
    private fun fail(availability: DaemonDiagnosticsAvailability) {
        synchronized(monitor) { disabled = true; scheduled = false; pending.clear() }
        latest.updateAndGet { it.copy(availability = availability, failedOperations = DaemonDiagnosticsJournal.add(it.failedOperations, 1)) }
    }
    companion object {
        fun create(instance: String, bootId: String): DaemonDiagnosticsRuntime {
            val id = UUID.randomUUID().toString()
            val wall = System.currentTimeMillis().coerceAtLeast(1); val elapsed = SystemClock.elapsedRealtime()
            // /proc parsing and optional Binder reads belong to the diagnostics worker.
            val session = DaemonDiagnosticsSession(id, bootId, Process.myPid(), null, BuildConfig.VERSION_CODE.toLong(),
                wall, elapsed, wall, elapsed, DaemonDiagnosticsState.BEGIN)
            return DaemonDiagnosticsRuntime(session,
                open = { SecureDaemonDiagnosticsStorage(instance) },
                capture = { opportunity -> captureDaemonDiagnostics(id, opportunity) }, processStartTicks = ::daemonDiagnosticsStartTicks)
        }
    }
}

private fun boundedProc(path: String): String = File(path).inputStream().use { input ->
    val bytes = ByteArray(16_385); var length = 0
    while (length < bytes.size) { val n = input.read(bytes, length, bytes.size - length); if (n < 0) break; check(n > 0); length += n }
    require(length <= 16_384); String(bytes, 0, length, Charsets.US_ASCII)
}
internal fun daemonDiagnosticsStartTicks(): Long? = runCatching {
    val stat = boundedProc("/proc/self/stat"); stat.substring(stat.lastIndexOf(')') + 2).split(' ')[19].toLong().takeIf { it >= 0 }
}.getOrNull()

private fun captureDaemonDiagnostics(id: String, opportunity: DaemonDiagnosticsOpportunity): DaemonDiagnosticsSample {
    val proc = runCatching { parseProtectionProcStatus(boundedProc("/proc/self/status")) }.getOrNull()
    val heap = runCatching { Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) to it.maxMemory() } }.getOrNull()
    fun service(name: String, stub: String): Any? = runCatching {
        val binderType = Class.forName("android.os.IBinder")
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name)
        Class.forName(stub).getMethod("asInterface", binderType).invoke(null, binder)
    }.getOrNull()
    val power = service("power", "android.os.IPowerManager\$Stub")
    fun powerFlag(name: String): Boolean? = runCatching {
        if (power == null) null else Class.forName("android.os.IPowerManager").getMethod(name).invoke(power) as? Boolean
    }.getOrNull()
    val battery = service("batteryproperties", "android.os.IBatteryPropertiesRegistrar\$Stub")
    fun batteryProperty(property: Int): Long? = runCatching {
        val type = Class.forName("android.os.BatteryProperty"); val value = type.getDeclaredConstructor().newInstance()
        val status = if (battery == null) null else Class.forName("android.os.IBatteryPropertiesRegistrar")
            .getMethod("getProperty", Int::class.javaPrimitiveType, type).invoke(battery, property, value)
        if (status != 0) null else type.getMethod("getLong").invoke(value) as? Long
    }.getOrNull()
    val percent = batteryProperty(4)?.takeIf { it in 0..100 }?.toInt()
    val charging = when (batteryProperty(6)) { 2L -> "CHARGING"; 3L -> "DISCHARGING"; 4L -> "NOT_CHARGING"; 5L -> "FULL"; else -> null }
    return DaemonDiagnosticsSample(id, opportunity.atMillis, opportunity.elapsedMillis,
        runCatching { Process.getElapsedCpuTime() }.getOrNull(), proc?.rssKiB, heap?.first, heap?.second,
        runCatching { Debug.getNativeHeapAllocatedSize() }.getOrNull(), proc?.threads, percent, charging,
        powerFlag("isInteractive"), powerFlag("isDeviceIdleMode"))
}
