package io.github.originosfcmguardian.gmsconfig

import kotlinx.coroutines.*

internal data class GmsConfigurationLoad(
    val snapshot: GmsConfigSnapshot?,
    val context: GmsConfigContext?,
    val storeFailed: Boolean = false,
    val contextFailed: Boolean = false,
)

/** A metadata failure invalidates freshness, but cannot establish record corruption. */
internal suspend fun readGmsConfiguration(
    loadSnapshot: () -> GmsConfigSnapshot?,
    readContext: suspend () -> GmsConfigContext,
): GmsConfigurationLoad {
    currentCoroutineContext().ensureActive()
    val snapshot = try { loadSnapshot() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { return GmsConfigurationLoad(null, null, storeFailed = true) }
    currentCoroutineContext().ensureActive()
    val context = try { readContext() }
    catch (_: TimeoutCancellationException) { currentCoroutineContext().ensureActive(); null }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { null }
    currentCoroutineContext().ensureActive()
    return GmsConfigurationLoad(snapshot, context, contextFailed = context == null)
}

/** One active load and one coalesced refresh; newer signals suppress older publication. */
internal class GmsConfigurationLoader(
    private val scope: CoroutineScope,
    private val invalidateContext: () -> Unit,
    private val read: suspend () -> GmsConfigurationLoad,
    private val publish: (GmsConfigurationLoad) -> Unit,
) : AutoCloseable {
    private val monitor = Any()
    private var job: Job? = null
    private var pending = false
    private var generation = 0L
    private var closed = false

    fun refresh() = synchronized(monitor) {
        if (closed || !scope.isActive) return@synchronized
        invalidateContext()
        generation++
        pending = true
        if (job != null) return@synchronized
        val next = scope.launch(start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext().job
            try {
                while (true) {
                    val token = synchronized(monitor) {
                        if (closed || !pending) {
                            if (job === owner) job = null
                            null
                        } else {
                            pending = false
                            generation
                        }
                    } ?: return@launch
                    val result = read()
                    currentCoroutineContext().ensureActive()
                    synchronized(monitor) {
                        owner.ensureActive()
                        if (!closed && token == generation) publish(result)
                    }
                }
            } finally {
                synchronized(monitor) {
                    if (job === owner) { job = null; pending = false }
                }
            }
        }
        job = next
        next.start()
    }

    override fun close() {
        synchronized(monitor) {
            closed = true
            generation++
            pending = false
            job?.cancel()
        }
    }
}
