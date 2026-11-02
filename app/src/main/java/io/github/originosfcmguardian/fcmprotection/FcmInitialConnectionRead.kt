package io.github.originosfcmguardian.fcmprotection

import io.github.originosfcmguardian.core.gms.GmsConnectionObservation
import io.github.originosfcmguardian.core.gms.GmsUnknown
import io.github.originosfcmguardian.privileged.GmsConnectionReadStage
import io.github.originosfcmguardian.protection.InteractiveGmsReadCoordinator
import io.github.originosfcmguardian.protection.ReadSessionCoordinator
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicReference

/** One latest App-side initial-confirmation failure, held in RAM and exported without raw inputs. */
data class FcmInitialReadFailure(val stage: GmsConnectionReadStage, val reason: GmsUnknown,
    val atEpochMillis: Long, val elapsedMillis: Long) {
    init { require(atEpochMillis > 0 && elapsedMillis >= 0) }
}

/** No retries or consent changes. The deadline also bounds waiting behind another read session. */
internal suspend fun readInitialFcmConnection(
    elapsedMillis: () -> Long,
    failure: (FcmInitialReadFailure) -> Unit,
    coordinator: InteractiveGmsReadCoordinator = InteractiveGmsReadCoordinator.process,
    timeoutMillis: Long = 24_000,
    wallMillis: () -> Long = System::currentTimeMillis,
    read: suspend ((GmsConnectionReadStage) -> Unit) -> GmsConnectionObservation,
): GmsConnectionObservation? {
    require(timeoutMillis in 1..24_000)
    val stage = AtomicReference(GmsConnectionReadStage.INTERACTIVE_ADMISSION)
    fun record(reason: GmsUnknown) {
        // Optional evidence cannot alter consent, replace the result or break read cleanup.
        runCatching { failure(FcmInitialReadFailure(stage.get(), reason,
            wallMillis().coerceAtLeast(1), elapsedMillis().coerceAtLeast(0))) }
    }
    return try {
        val observation = coordinator.confirmation(timeoutMillis) {
            stage.set(GmsConnectionReadStage.READ_SESSION)
            ReadSessionCoordinator.withSession { read(stage::set) }
        }
        if (observation == null) record(GmsUnknown.TIMED_OUT)
        else if (observation is GmsConnectionObservation.Unknown) record(observation.reason)
        observation
    } catch (cancelled: CancellationException) {
        record(GmsUnknown.CANCELLED)
        throw cancelled
    } catch (_: Exception) {
        record(GmsUnknown.BRIDGE_UNAVAILABLE)
        null
    }
}
