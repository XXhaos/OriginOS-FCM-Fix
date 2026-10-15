package io.github.originosfcmguardian.fcmrecovery

import io.github.originosfcmguardian.core.gms.*
import kotlinx.coroutines.CancellationException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Adapters only: this is the production read/consume/dispatch/readback path, not a test copy. */
internal interface FcmRecoveryCycleAccess {
    val store: FcmRecoveryStore
    fun wallMillis(): Long
    fun elapsedMillis(): Long
    fun current(): Boolean
    fun authorized(): Boolean
    fun admitted(sample: GmsConnectionObservation.Sample): Boolean
    fun usableNetwork(): Boolean
    fun disconnectedNetwork(): Boolean = usableNetwork()
    suspend fun read(): GmsConnectionObservation
    suspend fun request(binding: String, authorizeDispatch: () -> Boolean): FcmRecoveryResult
    suspend fun request(operation: FcmRecoveryOperation, binding: String, authorizeDispatch: () -> Boolean): FcmRecoveryResult =
        if (operation == FcmRecoveryOperation.SERVICE_ABSENT_SEND) request(binding, authorizeDispatch)
        else FcmRecoveryResult(1, FcmRecoveryStatus.NOT_SENT, FcmRecoveryReason.TARGET_UNQUALIFIED, operation)
    fun retainLive(value: AtomicBoolean)
    fun observed(observation: GmsConnectionObservation, authorized: Boolean)
    fun record(action: FcmRecoveryEventAction, sample: GmsConnectionObservation.Sample? = null,
        authorized: Boolean = false, result: FcmRecoveryResult? = null, unknown: GmsUnknown? = null)
    fun operationRecord(action: FcmRecoveryEventAction, operation: FcmRecoveryOperation,
        sample: GmsConnectionObservation.Sample, authorized: Boolean, result: FcmRecoveryResult? = null) =
        record(action, sample, authorized, result)
    fun requestStarted(event: FcmRecoveryEvent, correlation: String)
    fun requestCompleted(event: FcmRecoveryEvent, correlation: String)
    fun bindingChanged(sample: GmsConnectionObservation.Sample, saved: FcmRecoverySnapshot)
    fun finished(saved: FcmRecoverySnapshot, result: FcmRecoveryResult, connected: Boolean)
}

internal class FcmRecoveryCycle(private val access: FcmRecoveryCycleAccess) {
    suspend fun run() {
        val observation = access.read()
        if (!access.current()) return
        val sample = observation as? GmsConnectionObservation.Sample
        val allowed = access.authorized()
        val admitted = sample != null && allowed && access.admitted(sample)
        optional { access.observed(observation, admitted) }
        access.record(FcmRecoveryEventAction.OBSERVATION, sample, allowed,
            unknown = (observation as? GmsConnectionObservation.Unknown)?.reason)
        if (sample == null || !admitted) return
        var saved = access.store.read()
        val binding = FcmRecoveryPolicy.binding(sample)
        if (!saved.configured || saved.binding != binding) { access.bindingChanged(sample, saved); return }
        saved = access.store.rearm(sample, access.elapsedMillis(), access.usableNetwork(),
            saved.mcsConsumed && access.disconnectedNetwork())
        val operation = when {
            FcmRecoveryPolicy.absent(sample, access.elapsedMillis()) -> FcmRecoveryOperation.SERVICE_ABSENT_SEND
            FcmRecoveryPolicy.disconnected(sample, access.elapsedMillis()) && access.disconnectedNetwork() -> FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS
            else -> return
        }
        if (saved.consumed(operation) || !access.authorized() ||
            operation == FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS && !access.disconnectedNetwork()) return
        if (access.store.consume(sample, operation) == null) return
        access.operationRecord(FcmRecoveryEventAction.ATTEMPT_CONSUMED, operation, sample, true)
        val live = AtomicBoolean(true)
        val used = AtomicBoolean(false)
        access.retainLive(live)
        val correlation = UUID.randomUUID().toString() // Diagnostic-only, never an authority token.
        val began = evidence(FcmRecoveryEventAction.ATTEMPT_CONSUMED, sample, operation = operation)
        optional { access.requestStarted(began, correlation) }
        val result = try {
            access.request(operation, binding) {
                runCatching {
                    live.get() && access.authorized() && access.admitted(sample) &&
                        (operation != FcmRecoveryOperation.PRESENT_DISCONNECTED_MCS || access.disconnectedNetwork()) && access.store.read().let {
                        it.configured && it.consumed(operation) && it.binding == binding
                    } && live.get() && access.current() && used.compareAndSet(false, true)
                }.getOrDefault(false)
            }
        } catch (cancelled: CancellationException) {
            optional { access.requestCompleted(evidence(FcmRecoveryEventAction.REQUEST_RESULT, sample,
                FcmRecoveryResult(1, FcmRecoveryStatus.UNKNOWN, FcmRecoveryReason.CANCELLED, operation), operation), correlation) }
            throw cancelled
        } catch (error: Exception) {
            optional { access.requestCompleted(evidence(FcmRecoveryEventAction.REQUEST_RESULT, sample,
                FcmRecoveryResult(1, FcmRecoveryStatus.UNKNOWN, FcmRecoveryReason.BRIDGE_UNAVAILABLE, operation), operation), correlation) }
            throw error
        } finally { live.set(false) }
        optional { access.requestCompleted(evidence(FcmRecoveryEventAction.REQUEST_RESULT, sample, result, operation), correlation) }
        access.operationRecord(FcmRecoveryEventAction.REQUEST_RESULT, operation, sample, access.authorized(), result)
        if (!access.current() || !access.authorized()) return
        val afterObservation = access.read()
        val after = afterObservation as? GmsConnectionObservation.Sample
        if (access.current()) optional {
            access.observed(afterObservation, after != null && access.authorized() &&
                runCatching { access.admitted(after) }.getOrDefault(false))
        }
        access.record(FcmRecoveryEventAction.READBACK, after, access.authorized(),
            unknown = (afterObservation as? GmsConnectionObservation.Unknown)?.reason)
        if (after != null && access.authorized()) access.store.rearm(after, access.elapsedMillis(), access.usableNetwork(),
            access.store.read().mcsConsumed && access.disconnectedNetwork())
        access.finished(access.store.read(), result,
            after != null && access.current() && access.authorized() && FcmRecoveryPolicy.connected(after, access.elapsedMillis()))
    }

    private fun evidence(action: FcmRecoveryEventAction, sample: GmsConnectionObservation.Sample, result: FcmRecoveryResult? = null,
        operation: FcmRecoveryOperation = FcmRecoveryOperation.SERVICE_ABSENT_SEND) =
        FcmRecoveryEvent(access.wallMillis(), access.elapsedMillis(), action, sample.gcmService.state,
            sample.gcmService.completeness, sample.observation, true, result?.status, result?.reason, operation = operation)

    private inline fun optional(action: () -> Unit) { runCatching(action) }
}
